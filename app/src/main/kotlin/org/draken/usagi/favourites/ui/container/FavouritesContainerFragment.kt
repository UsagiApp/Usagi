package org.draken.usagi.favourites.ui.container

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewStub
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ActionMode
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.children
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
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
import org.draken.usagi.core.util.ext.setTextAndVisible
import org.draken.usagi.databinding.FragmentFavouritesContainerBinding
import org.draken.usagi.databinding.ItemEmptyStateBinding
import org.draken.usagi.favourites.domain.FavouriteOrganizerRefreshResult
import org.draken.usagi.favourites.domain.FavouriteScope
import org.draken.usagi.favourites.domain.FavouriteStage
import org.draken.usagi.favourites.domain.SmartFolderContent
import org.draken.usagi.favourites.domain.SmartFolderDevice
import org.draken.usagi.favourites.domain.SmartFolderRules
import org.draken.usagi.favourites.ui.list.FavouritesListFragment
import org.draken.usagi.list.domain.ListFilterOption
import java.util.Collections
import java.util.EnumMap
import java.util.WeakHashMap

@AndroidEntryPoint
class FavouritesContainerFragment :
	BaseFragment<FragmentFavouritesContainerBinding>(),
	ActionModeListener,
	RecyclerViewOwner,
	ViewStub.OnInflateListener,
	View.OnClickListener {
	private val viewModel: FavouritesContainerViewModel by viewModels()
	private val stageChipIds = EnumMap<FavouriteStage, Int>(FavouriteStage::class.java)
	private val observedFragments = Collections.newSetFromMap(WeakHashMap<FavouritesListFragment, Boolean>())
	private var isBindingStage = false
	private var pageChangeCallback: ViewPager2.OnPageChangeCallback? = null
	private var pagerAdapter: FavouritesContainerAdapter? = null

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
		binding.buttonScope.setOnClickListener(this)
		binding.buttonRules.setOnClickListener(this)
		binding.buttonEditScope.setOnClickListener(this)
		binding.buttonAddScope.setOnClickListener(this)
		binding.buttonRefreshOrganizer.setOnClickListener(this)
		binding.textRulesSummary.setOnClickListener(this)
		pageChangeCallback =
			object : ViewPager2.OnPageChangeCallback() {
				override fun onPageSelected(position: Int) {
					binding.pager.post(::bindCurrentPage)
				}
			}.also(binding.pager::registerOnPageChangeCallback)
		binding.stubEmpty.setOnInflateListener(this)
		actionModeDelegate.addListener(this)
		viewModel.categories.observe(viewLifecycleOwner, pagerAdapter)
		viewModel.categories.observe(viewLifecycleOwner) {
			binding.pager.post(::bindCurrentPage)
		}
		viewModel.isEmpty.observe(viewLifecycleOwner, ::onEmptyStateChanged)
		addMenuProvider(FavouritesContainerMenuProvider(router))
		viewModel.onActionDone.observeEvent(viewLifecycleOwner, ReversibleActionObserver(binding.pager))
		binding.pager.post(::bindCurrentPage)
	}

	override fun onDestroyView() {
		pageChangeCallback?.let { callback -> viewBinding?.pager?.unregisterOnPageChangeCallback(callback) }
		pageChangeCallback = null
		pagerAdapter = null
		stageChipIds.clear()
		observedFragments.clear()
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
			stageChips.children.forEach { child -> child.isEnabled = false }
			organizerHeader.children.forEach { child -> child.isEnabled = false }
			textRulesSummary.isEnabled = false
		}
	}

	override fun onActionModeFinished(mode: ActionMode) {
		viewBinding?.run {
			pager.isUserInputEnabled = true
			stageChips.children.forEach { child -> child.isEnabled = true }
			organizerHeader.children.forEach { child -> child.isEnabled = true }
			textRulesSummary.isEnabled = true
		}
	}

	override fun onInflate(
		stub: ViewStub?,
		inflated: View,
	) {
		val stubBinding = ItemEmptyStateBinding.bind(inflated)
		stubBinding.icon.setImageAsync(R.drawable.ic_empty_favourites)
		stubBinding.textPrimary.setText(R.string.text_empty_holder_primary)
		stubBinding.textSecondary.setTextAndVisible(R.string.empty_favourite_categories)
		stubBinding.buttonRetry.setTextAndVisible(R.string.manage)
		stubBinding.buttonRetry.setOnClickListener(this)
	}

	override fun onClick(v: View) {
		when (v.id) {
			R.id.button_retry -> router.openFavoriteCategories()

			R.id.button_scope -> showScopeSelector()

			R.id.button_rules,
			R.id.text_rules_summary,
			-> showRuleSelector()

			R.id.button_edit_scope -> editCurrentScope()

			R.id.button_add_scope -> router.openSmartFolderCreate()

			R.id.button_refresh_organizer -> (findCurrentFragment() as? FavouritesListFragment)?.refreshOrganizer()
		}
	}

	private fun onEmptyStateChanged(isEmpty: Boolean) {
		viewBinding?.run {
			pager.isGone = isEmpty
			organizerHeader.isGone = isEmpty
			textRulesSummary.isGone = isEmpty
			stageChips.isGone = isEmpty
			stubEmpty.isVisible = isEmpty
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
			(findCurrentFragment() as? FavouritesListFragment)?.setStage(stage)
		}
	}

	private fun bindCurrentPage() {
		val fragment = findCurrentFragment() as? FavouritesListFragment ?: return
		if (observedFragments.add(fragment)) {
			fragment.selectedStage.observe(viewLifecycleOwner) {
				if (findCurrentFragment() === fragment) renderStages(fragment)
			}
			fragment.stageCounts.observe(viewLifecycleOwner) {
				if (findCurrentFragment() === fragment) renderStages(fragment)
			}
			fragment.availableRuleOptions.observe(viewLifecycleOwner) {
				if (findCurrentFragment() === fragment) renderOrganizerHeader(fragment)
			}
			fragment.selectedRuleOptions.observe(viewLifecycleOwner) {
				if (findCurrentFragment() === fragment) renderOrganizerHeader(fragment)
			}
			fragment.isOrganizerRefreshing.observe(viewLifecycleOwner) {
				if (findCurrentFragment() === fragment) renderOrganizerHeader(fragment)
			}
			fragment.onOrganizerRefreshed.observeEvent(viewLifecycleOwner, ::showRefreshResult)
		}
		renderStages(fragment)
		renderOrganizerHeader(fragment)
	}

	private fun renderStages(fragment: FavouritesListFragment) {
		val binding = viewBinding ?: return
		val counts = fragment.stageCounts.value
		FavouriteStage.entries.forEach { stage ->
			val chip = binding.stageChips.findViewById<Chip>(stageChipIds.getValue(stage))
			val title = getString(stage.titleResId)
			chip.text = getString(R.string.favourite_stage_with_count, title, counts[stage])
		}
		isBindingStage = true
		binding.stageChips.check(stageChipIds.getValue(fragment.selectedStage.value))
		isBindingStage = false
	}

	private fun renderOrganizerHeader(fragment: FavouritesListFragment) {
		val binding = viewBinding ?: return
		val tab = pagerAdapter?.getItemOrNull(binding.pager.currentItem) ?: return
		binding.buttonScope.text = tab.title ?: getString(R.string.all_favourites)
		binding.buttonEditScope.isVisible = tab.scope != FavouriteScope.All
		binding.buttonRules.isEnabled = fragment.availableRuleOptions.value.isNotEmpty()
		binding.buttonRefreshOrganizer.isEnabled = !fragment.isOrganizerRefreshing.value
		binding.buttonRefreshOrganizer.alpha = if (fragment.isOrganizerRefreshing.value) 0.5f else 1f
		binding.textRulesSummary.text = buildRulesSummary(tab, fragment.selectedRuleOptions.value)
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

	private fun showScopeSelector() {
		val binding = viewBinding ?: return
		val tabs = viewModel.categories.value
		if (tabs.isEmpty()) return
		val titles = tabs.map { tab -> tab.title ?: getString(R.string.all_favourites) }.toTypedArray()
		MaterialAlertDialogBuilder(binding.root.context)
			.setTitle(R.string.favourite_organizer_scope)
			.setSingleChoiceItems(titles, binding.pager.currentItem) { dialog, which ->
				binding.pager.setCurrentItem(which, false)
				dialog.dismiss()
			}.show()
	}

	private fun showRuleSelector() {
		val fragment = findCurrentFragment() as? FavouritesListFragment ?: return
		val options = fragment.availableRuleOptions.value
		if (options.isEmpty()) return
		val selected = fragment.selectedRuleOptions.value.toMutableSet()
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
			}.setNeutralButton(R.string.reset_filter) { _, _ -> fragment.clearRuleOptions() }
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(R.string.apply) { _, _ ->
				fragment.clearRuleOptions()
				options.filter(selected::contains).forEach { option -> fragment.setRuleOption(option, true) }
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

	private fun editCurrentScope() {
		when (val scope = pagerAdapter?.getItemOrNull(viewBinding?.pager?.currentItem ?: return)?.scope) {
			is FavouriteScope.Category -> router.openFavoriteCategoryEdit(scope.id)

			is FavouriteScope.SmartFolder -> router.openSmartFolderEdit(scope.id)

			FavouriteScope.All,
			null,
			-> Unit
		}
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
}
