package org.draken.usagi.favourites.ui.container

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ActionMode
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayoutMediator
import dagger.hilt.android.AndroidEntryPoint
import org.draken.usagi.R
import org.draken.usagi.core.nav.router
import org.draken.usagi.core.ui.BaseFragment
import org.draken.usagi.core.ui.util.ActionModeListener
import org.draken.usagi.core.ui.util.RecyclerViewOwner
import org.draken.usagi.core.ui.util.ReversibleActionObserver
import org.draken.usagi.core.util.ext.addMenuProvider
import org.draken.usagi.core.util.ext.findCurrentPagerFragment
import org.draken.usagi.core.util.ext.observe
import org.draken.usagi.core.util.ext.observeEvent
import org.draken.usagi.core.util.ext.recyclerView
import org.draken.usagi.core.util.ext.setTabsEnabled
import org.draken.usagi.databinding.FragmentFavouritesContainerBinding
import org.draken.usagi.favourites.domain.FavouriteOrganizerRefreshResult
import org.draken.usagi.favourites.domain.FavouriteScope
import org.draken.usagi.favourites.domain.FavouriteStage
import org.draken.usagi.favourites.domain.SmartFolderContent
import org.draken.usagi.favourites.domain.SmartFolderDevice
import org.draken.usagi.favourites.domain.SmartFolderRules
import org.draken.usagi.favourites.ui.FavouritesPage
import org.draken.usagi.favourites.ui.FavouritesPageUiState
import org.draken.usagi.favourites.ui.list.FavouritesListFragment
import org.draken.usagi.list.domain.ListFilterOption
import java.util.EnumMap

@AndroidEntryPoint
class FavouritesContainerFragment :
	BaseFragment<FragmentFavouritesContainerBinding>(),
	ActionModeListener,
	RecyclerViewOwner,
	View.OnClickListener {
	private val viewModel: FavouritesContainerViewModel by viewModels()
	private val stageChipIds = EnumMap<FavouriteStage, Int>(FavouriteStage::class.java)
	private var isBindingStage = false
	private var pageBinding: FavouritesPageBinding? = null
	private var pageChangeCallback: ViewPager2.OnPageChangeCallback? = null
	private var pagerAdapter: FavouritesContainerAdapter? = null
	private var tabLayoutMediator: TabLayoutMediator? = null

	override val recyclerView: RecyclerView?
		get() = (findCurrentFragment() as? RecyclerViewOwner)?.recyclerView

	val categoryId get() = (findCurrentFragment() as? FavouritesListFragment)?.categoryId

	override fun onCreateViewBinding(
		inflater: LayoutInflater,
		container: ViewGroup?,
	) = FragmentFavouritesContainerBinding.inflate(inflater, container, false)

	override fun onViewBindingCreated(
		binding: FragmentFavouritesContainerBinding,
		savedInstanceState: Bundle?,
	) {
		super.onViewBindingCreated(binding, savedInstanceState)
		val pagerAdapter = FavouritesContainerAdapter(this).also { this.pagerAdapter = it }
		setupStageChips(binding)
		binding.pager.adapter = pagerAdapter
		binding.pager.offscreenPageLimit = 1
		binding.pager.recyclerView?.isNestedScrollingEnabled = false
		tabLayoutMediator =
			TabLayoutMediator(
				binding.tabs,
				binding.pager,
				FavouritesTabConfigurationStrategy(pagerAdapter, viewModel, router),
			).also(TabLayoutMediator::attach)
		binding.buttonRules.setOnClickListener(this)
		binding.buttonAddScope.setOnClickListener(this)
		binding.buttonRefreshOrganizer.setOnClickListener(this)
		binding.textRulesSummary.setOnClickListener(this)
		pageBinding =
			FavouritesPageBinding(
				scope = viewLifecycleOwner.lifecycleScope,
				render = ::renderCurrentPage,
				showRefreshResult = ::showRefreshResult,
			)
		pageChangeCallback =
			object : ViewPager2.OnPageChangeCallback() {
				override fun onPageSelected(position: Int) {
					pageBinding?.clear()
					binding.pager.post(::bindCurrentPage)
				}
			}.also(binding.pager::registerOnPageChangeCallback)
		actionModeDelegate.addListener(this)
		viewModel.categories.observe(viewLifecycleOwner, pagerAdapter)
		viewModel.categories.observe(viewLifecycleOwner) {
			pageBinding?.clear()
			binding.pager.post(::bindCurrentPage)
		}
		addMenuProvider(FavouritesContainerMenuProvider(router))
		viewModel.onActionDone.observeEvent(viewLifecycleOwner, ReversibleActionObserver(binding.pager))
		binding.pager.post(::bindCurrentPage)
	}

	override fun onDestroyView() {
		tabLayoutMediator?.detach()
		tabLayoutMediator = null
		pageChangeCallback?.let { callback -> viewBinding?.pager?.unregisterOnPageChangeCallback(callback) }
		pageChangeCallback = null
		pagerAdapter = null
		stageChipIds.clear()
		pageBinding?.clear()
		pageBinding = null
		actionModeDelegate.removeListener(this)
		super.onDestroyView()
	}

	override fun onApplyWindowInsets(
		v: View,
		insets: WindowInsetsCompat,
	): WindowInsetsCompat = insets

	override fun onActionModeStarted(mode: ActionMode) {
		viewBinding?.run {
			pager.isUserInputEnabled = false
			tabs.setTabsEnabled(false)
			buttonAddScope.isEnabled = false
			stageChips.children.forEach { child -> child.isEnabled = false }
			buttonRules.isEnabled = false
			buttonRefreshOrganizer.isEnabled = false
			textRulesSummary.isEnabled = false
		}
	}

	override fun onActionModeFinished(mode: ActionMode) {
		viewBinding?.run {
			pager.isUserInputEnabled = true
			tabs.setTabsEnabled(true)
			buttonAddScope.isEnabled = true
			stageChips.children.forEach { child -> child.isEnabled = true }
			buttonRules.isEnabled = true
			buttonRefreshOrganizer.isEnabled = true
			textRulesSummary.isEnabled = true
		}
	}

	override fun onClick(v: View) {
		when (v.id) {
			R.id.button_rules,
			R.id.text_rules_summary,
			-> showRuleSelector()

			R.id.button_add_scope -> router.openSmartFolderCreate()

			R.id.button_refresh_organizer -> findCurrentPage()?.refreshOrganizer()
		}
	}

	private fun setupStageChips(binding: FragmentFavouritesContainerBinding) {
		FavouriteStage.entries.forEach { stage ->
			val chip =
				Chip(binding.root.context).apply {
					id = View.generateViewId()
					isCheckable = true
					text = getString(stage.titleResId)
				}
			stageChipIds[stage] = chip.id
			binding.stageChips.addView(chip)
		}
		binding.stageChips.setOnCheckedStateChangeListener { _, checkedIds ->
			if (isBindingStage) return@setOnCheckedStateChangeListener
			val checkedId = checkedIds.singleOrNull() ?: return@setOnCheckedStateChangeListener
			val stage = stageChipIds.entries.firstOrNull { entry -> entry.value == checkedId }?.key ?: return@setOnCheckedStateChangeListener
			findCurrentPage()?.setStage(stage)
		}
	}

	private fun bindCurrentPage() {
		val fragment =
			findCurrentFragment() ?: run {
				pageBinding?.clear()
				return
			}
		val page =
			fragment as? FavouritesPage ?: run {
				pageBinding?.clear()
				return
			}
		pageBinding?.bind(page, fragment.viewLifecycleOwner)
	}

	private fun renderCurrentPage(state: FavouritesPageUiState) {
		renderStages(state)
		renderOrganizerHeader(state)
	}

	private fun renderStages(state: FavouritesPageUiState) {
		val binding = viewBinding ?: return
		val counts = state.stageCounts
		FavouriteStage.entries.forEach { stage ->
			val chip = binding.stageChips.findViewById<Chip>(stageChipIds.getValue(stage))
			val title = getString(stage.titleResId)
			chip.text = counts?.let { getString(R.string.favourite_stage_with_count, title, it[stage]) } ?: title
		}
		isBindingStage = true
		binding.stageChips.check(stageChipIds.getValue(state.selectedStage))
		isBindingStage = false
	}

	private fun renderOrganizerHeader(state: FavouritesPageUiState) {
		val binding = viewBinding ?: return
		val tab = pagerAdapter?.getItemOrNull(binding.pager.currentItem) ?: return
		binding.buttonRules.isEnabled = state.availableRuleOptions.isNotEmpty()
		binding.buttonRefreshOrganizer.isEnabled = !state.isOrganizerRefreshing
		binding.buttonRefreshOrganizer.alpha = if (state.isOrganizerRefreshing) 0.5f else 1f
		val selectedFilters = state.selectedRuleOptions
		binding.textRulesSummary.text = buildRulesSummary(tab, selectedFilters)
		binding.textRulesSummary.isVisible = tab.rulesError != null || tab.rules != null || selectedFilters.isNotEmpty()
	}

	private fun buildRulesSummary(
		tab: FavouriteTabModel,
		selectedFilters: Set<ListFilterOption>,
	): CharSequence {
		if (tab.rulesError != null) return getString(R.string.favourite_organizer_invalid_rules)
		val persistentSummary = tab.rules?.let(::buildSmartFolderRulesSummary)
		val transientSummary =
			when {
				selectedFilters.isEmpty() -> null
				selectedFilters.size <= 3 -> selectedFilters.joinToString(" · ", transform = ::getFilterTitle)
				else -> getString(R.string.favourite_organizer_filter_count, selectedFilters.size)
			}
		return listOfNotNull(persistentSummary, transientSummary).joinToString(" · ").ifEmpty {
			getString(R.string.favourite_organizer_any_rules)
		}
	}

	private fun buildSmartFolderRulesSummary(rules: SmartFolderRules): String =
		buildList {
			if (rules.sources.isNotEmpty()) {
				add(resources.getQuantityString(R.plurals.smart_folder_source_count, rules.sources.size, rules.sources.size))
			}
			if (rules.categoryIds.isNotEmpty()) {
				add(
					resources.getQuantityString(
						R.plurals.smart_folder_category_count,
						rules.categoryIds.size,
						rules.categoryIds.size,
					),
				)
			}
			if (rules.tagIds.isNotEmpty()) {
				add(resources.getQuantityString(R.plurals.smart_folder_tag_count, rules.tagIds.size, rules.tagIds.size))
			}
			when (rules.content) {
				SmartFolderContent.ANY -> Unit
				SmartFolderContent.SFW -> add(getString(R.string.smart_folder_sfw))
				SmartFolderContent.NSFW -> add(getString(R.string.smart_folder_nsfw))
			}
			when (rules.device) {
				SmartFolderDevice.ANY -> Unit
				SmartFolderDevice.ON_DEVICE -> add(getString(R.string.smart_folder_on_device))
				SmartFolderDevice.NOT_ON_DEVICE -> add(getString(R.string.smart_folder_not_on_device))
			}
		}.joinToString(" · ")

	private fun showRuleSelector() {
		val page = findCurrentPage() ?: return
		val state = page.uiState.value
		val options = state.availableRuleOptions
		if (options.isEmpty()) return
		val selected = state.selectedRuleOptions.toMutableSet()
		val titles = options.map(::getFilterTitle).toTypedArray()
		val checked = BooleanArray(options.size) { index -> options[index] in selected }
		MaterialAlertDialogBuilder(requireContext())
			.setTitle(R.string.favourite_organizer_rules_title)
			.setMultiChoiceItems(titles, checked) { dialog, which, isChecked ->
				val option = options[which]
				if (isChecked) {
					selected += option
					val conflict = option.conflictingContentFilter()
					if (conflict != null && selected.remove(conflict)) {
						val conflictIndex = options.indexOf(conflict)
						if (conflictIndex >= 0) {
							(dialog as AlertDialog).listView.setItemChecked(conflictIndex, false)
						}
					}
				} else {
					selected -= option
				}
			}.setNeutralButton(R.string.reset_filter) { _, _ -> page.clearRuleOptions() }
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(R.string.apply) { _, _ ->
				page.clearRuleOptions()
				options.filter(selected::contains).forEach { option -> page.setRuleOption(option, true) }
			}.show()
	}

	private fun getFilterTitle(option: ListFilterOption): String =
		option.titleText?.toString()
			?: getString(option.titleResId.also { require(it != 0) { "Filter option has no display title" } })

	private fun ListFilterOption.conflictingContentFilter(): ListFilterOption? =
		when (this) {
			ListFilterOption.SFW -> ListFilterOption.Macro.NSFW
			ListFilterOption.Macro.NSFW -> ListFilterOption.SFW
			else -> null
		}

	private fun showRefreshResult(result: FavouriteOrganizerRefreshResult) {
		val binding = viewBinding ?: return
		val message =
			if (result.requested == 0) {
				getString(R.string.favourite_organizer_refresh_empty)
			} else {
				getString(R.string.favourite_organizer_refresh_result, result.updated, result.requested, result.failed)
			}
		Snackbar.make(binding.pager, message, Snackbar.LENGTH_LONG).show()
	}

	private val FavouriteStage.titleResId: Int
		get() =
			when (this) {
				FavouriteStage.ALL -> R.string.favourite_stage_all
				FavouriteStage.NOT_STARTED -> R.string.favourite_stage_not_started
				FavouriteStage.READING -> R.string.favourite_stage_reading
				FavouriteStage.WAITING -> R.string.favourite_stage_waiting
				FavouriteStage.COMPLETED -> R.string.favourite_stage_completed
				FavouriteStage.NEEDS_REVIEW -> R.string.favourite_stage_needs_review
			}

	private fun findCurrentFragment(): Fragment? {
		return childFragmentManager.findCurrentPagerFragment(
			viewBinding?.pager ?: return null,
		)
	}

	private fun findCurrentPage(): FavouritesPage? = findCurrentFragment() as? FavouritesPage
}
