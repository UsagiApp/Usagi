package org.draken.usagi.list.ui.config

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CompoundButton
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.slider.Slider
import dagger.hilt.android.AndroidEntryPoint
import org.draken.usagi.R
import org.draken.usagi.core.model.getTitle
import org.draken.usagi.core.prefs.ListMode
import org.draken.usagi.core.ui.dialog.SearchableSelectionDialog
import org.draken.usagi.core.ui.dialog.SearchableSelectionItem
import org.draken.usagi.core.ui.sheet.BaseAdaptiveSheet
import org.draken.usagi.core.util.ext.consume
import org.draken.usagi.core.util.ext.setValueRounded
import org.draken.usagi.core.util.progress.IntPercentLabelFormatter
import org.draken.usagi.databinding.SheetListModeBinding
import org.draken.usagi.favourites.ui.FavouritesOptionsHost
import org.draken.usagi.favourites.ui.container.FavouriteFilterSelectionState
import org.draken.usagi.list.domain.ListFilterOption

@AndroidEntryPoint
class ListConfigBottomSheet :
	BaseAdaptiveSheet<SheetListModeBinding>(),
	Slider.OnChangeListener,
	MaterialButtonToggleGroup.OnButtonCheckedListener,
	CompoundButton.OnCheckedChangeListener,
	AdapterView.OnItemSelectedListener {
	private val viewModel by viewModels<ListConfigViewModel>()
	private var favouritesHost: FavouritesOptionsHost? = null
	private var filterSelection: FavouriteFilterSelectionState? = null
	private var availableFilters = emptyList<ListFilterOption>()

	override fun onCreateViewBinding(
		inflater: LayoutInflater,
		container: ViewGroup?,
	) = SheetListModeBinding.inflate(inflater, container, false)

	override fun onViewBindingCreated(
		binding: SheetListModeBinding,
		savedInstanceState: Bundle?,
	) {
		super.onViewBindingCreated(binding, savedInstanceState)
		if (viewModel.section is ListConfigSection.Favorites) {
			val host =
				requireNotNull(parentFragment as? FavouritesOptionsHost) {
					"Favorites list options must be shown by FavouritesOptionsHost"
				}
			favouritesHost = host
			setupFavouritesOptions(binding, host)
		}
		val mode = viewModel.listMode
		binding.buttonList.isChecked = mode == ListMode.LIST
		binding.buttonListDetailed.isChecked = mode == ListMode.DETAILED_LIST
		binding.buttonGrid.isChecked = mode == ListMode.GRID
		binding.buttonCoverOnly.isChecked = mode == ListMode.COVER_ONLY
		val isGridMode = mode == ListMode.GRID || mode == ListMode.COVER_ONLY
		binding.textViewGridTitle.isVisible = isGridMode
		binding.sliderGrid.isVisible = isGridMode

		binding.sliderGrid.setLabelFormatter(IntPercentLabelFormatter(binding.root.context))
		binding.sliderGrid.setValueRounded(viewModel.gridSize.toFloat())
		binding.sliderGrid.addOnChangeListener(this)

		binding.checkableGroup.addOnButtonCheckedListener(this)

		binding.switchGrouping.isVisible = viewModel.isGroupingSupported
		if (viewModel.isGroupingSupported) {
			binding.switchGrouping.isEnabled = viewModel.isGroupingAvailable
		}
		binding.switchGrouping.isChecked = viewModel.isGroupingEnabled
		binding.switchGrouping.setOnCheckedChangeListener(this)

		val sortOrders = viewModel.getSortOrders()
		if (sortOrders != null) {
			binding.textViewOrderTitle.isVisible = true
			binding.spinnerOrder.adapter =
				ArrayAdapter(
					binding.spinnerOrder.context,
					android.R.layout.simple_spinner_dropdown_item,
					android.R.id.text1,
					sortOrders.map { binding.spinnerOrder.context.getString(it.titleResId) },
				)
			val selected = sortOrders.indexOf(favouritesHost?.currentFavouritesSortOrder() ?: viewModel.getSelectedSortOrder())
			if (selected >= 0) {
				binding.spinnerOrder.setSelection(selected, false)
			}
			binding.spinnerOrder.onItemSelectedListener = this
			binding.cardOrder.isVisible = true
		}
	}

	override fun onDestroyView() {
		favouritesHost = null
		filterSelection = null
		availableFilters = emptyList()
		super.onDestroyView()
	}

	override fun onApplyWindowInsets(
		v: View,
		insets: WindowInsetsCompat,
	): WindowInsetsCompat {
		val typeMask = WindowInsetsCompat.Type.systemBars()
		viewBinding?.scrollView?.updatePadding(
			bottom = insets.getInsets(typeMask).bottom,
		)
		return insets.consume(v, typeMask, bottom = true)
	}

	override fun onButtonChecked(
		group: MaterialButtonToggleGroup?,
		checkedId: Int,
		isChecked: Boolean,
	) {
		if (!isChecked) {
			return
		}
		val mode =
			when (checkedId) {
				R.id.button_list -> ListMode.LIST
				R.id.button_list_detailed -> ListMode.DETAILED_LIST
				R.id.button_grid -> ListMode.GRID
				R.id.button_cover_only -> ListMode.COVER_ONLY
				else -> return
			}
		val isGridMode = mode == ListMode.GRID || mode == ListMode.COVER_ONLY
		requireViewBinding().textViewGridTitle.isVisible = isGridMode
		requireViewBinding().sliderGrid.isVisible = isGridMode
		viewModel.listMode = mode
	}

	override fun onCheckedChanged(
		buttonView: CompoundButton,
		isChecked: Boolean,
	) {
		when (buttonView.id) {
			R.id.switch_grouping -> viewModel.isGroupingEnabled = isChecked
		}
	}

	override fun onValueChange(
		slider: Slider,
		value: Float,
		fromUser: Boolean,
	) {
		if (fromUser) {
			viewModel.gridSize = value.toInt()
		}
	}

	override fun onItemSelected(
		parent: AdapterView<*>,
		view: View?,
		position: Int,
		id: Long,
	) {
		when (parent.id) {
			R.id.spinner_order -> {
				val order = viewModel.getSortOrders()?.getOrNull(position)
				if (order != null && favouritesHost != null) {
					favouritesHost?.setFavouritesSortOrder(order)
				} else {
					viewModel.setSortOrder(position)
				}
				viewBinding?.switchGrouping?.isEnabled = viewModel.isGroupingAvailable
			}
		}
	}

	override fun onNothingSelected(parent: AdapterView<*>?) = Unit

	private fun setupFavouritesOptions(
		binding: SheetListModeBinding,
		host: FavouritesOptionsHost,
	) {
		val state = requireNotNull(host.currentFavouritesOptions()) { "Active Favorites page is not ready" }
		availableFilters = state.availableRuleOptions
		filterSelection = FavouriteFilterSelectionState(state.selectedRuleOptions).also { it.retainAvailable(availableFilters) }
		binding.favouritesOptions.isVisible = true
		binding.buttonManageSmartFolders.setOnClickListener {
			dismiss()
			host.openSmartFolders()
		}
		binding.buttonRefreshFavourites.isEnabled = !state.isOrganizerRefreshing
		binding.buttonRefreshFavourites.subtitle =
			if (state.isOrganizerRefreshing) {
				getString(R.string.loading_)
			} else {
				getString(R.string.favourite_organizer_refresh_summary)
			}
		binding.buttonRefreshFavourites.setOnClickListener {
			dismiss()
			host.refreshFavouritesOrganizer()
		}
		renderFavouritesFilters(binding)
	}

	private fun renderFavouritesFilters(binding: SheetListModeBinding) {
		val state = filterSelection ?: return
		val simpleOptions = availableFilters.filterNot { it is ListFilterOption.Source || it is ListFilterOption.Tag }
		binding.favouritesSimpleFilters.removeAllViews()
		simpleOptions.forEach { option ->
			binding.favouritesSimpleFilters.addView(
				(
					LayoutInflater.from(requireContext()).inflate(
						R.layout.item_favourite_quick_filter,
						binding.favouritesSimpleFilters,
						false,
					) as MaterialCheckBox
				).apply {
					text = filterTitle(option)
					isChecked = option in state.selection()
					setOnCheckedChangeListener { _, checked ->
						state.setSelected(option, checked)
						applyFavouritesFilters()
						renderFavouritesFilters(binding)
					}
				},
			)
		}

		val sources = availableFilters.filterIsInstance<ListFilterOption.Source>()
		binding.buttonFavouriteSources.isVisible = sources.isNotEmpty()
		binding.buttonFavouriteSources.subtitle = selectionSubtitle(sources)
		binding.buttonFavouriteSources.setOnClickListener {
			showSearchableOptions(R.string.smart_folder_sources, sources)
		}

		val tags = availableFilters.filterIsInstance<ListFilterOption.Tag>()
		binding.buttonFavouriteTags.isVisible = tags.isNotEmpty()
		binding.buttonFavouriteTags.subtitle = selectionSubtitle(tags)
		binding.buttonFavouriteTags.setOnClickListener {
			showSearchableOptions(R.string.genres, tags)
		}
	}

	private fun <T : ListFilterOption> selectionSubtitle(options: List<T>): String {
		val selectedCount = options.count { it in filterSelection?.selection().orEmpty() }
		return if (selectedCount == 0) {
			getString(R.string.any)
		} else {
			resources.getQuantityString(R.plurals.items, selectedCount, selectedCount)
		}
	}

	private fun <T : ListFilterOption> showSearchableOptions(
		@androidx.annotation.StringRes titleResId: Int,
		options: List<T>,
	) {
		val selection = filterSelection ?: return
		SearchableSelectionDialog.show(
			context = requireContext(),
			titleResId = titleResId,
			items =
				options.map { option ->
					SearchableSelectionItem(
						id = option,
						title = filterTitle(option),
					)
				},
			selected = options.filterTo(linkedSetOf()) { it in selection.selection() },
		) { selected ->
			options.forEach { option -> selection.setSelected(option, option in selected) }
			applyFavouritesFilters()
			viewBinding?.let(::renderFavouritesFilters)
		}
	}

	private fun applyFavouritesFilters() {
		favouritesHost?.applyFavouritesFilters(filterSelection?.selection().orEmpty())
	}

	private fun filterTitle(option: ListFilterOption): String =
		when (option) {
			is ListFilterOption.Source -> option.mangaSource.getTitle(requireContext())
			else -> option.titleText?.toString() ?: getString(option.titleResId)
		}
}
