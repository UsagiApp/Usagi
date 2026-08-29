package org.draken.usagi.favourites.ui.container

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayoutMediator
import dagger.hilt.android.AndroidEntryPoint
import org.draken.usagi.R
import org.draken.usagi.core.model.getTitle
import org.draken.usagi.core.nav.AppRouter
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
import org.draken.usagi.core.util.ext.withArgs
import org.draken.usagi.databinding.FragmentFavouritesContainerBinding
import org.draken.usagi.favourites.domain.FavouriteOrganizerRefreshResult
import org.draken.usagi.favourites.domain.FavouriteStage
import org.draken.usagi.favourites.ui.FavouritesOptionsHost
import org.draken.usagi.favourites.ui.FavouritesPage
import org.draken.usagi.favourites.ui.FavouritesPageUiState
import org.draken.usagi.favourites.ui.list.FavouritesListFragment
import org.draken.usagi.favourites.ui.smartfolders.formatSummary
import org.draken.usagi.list.domain.ListFilterOption
import org.draken.usagi.list.domain.ListSortOrder
import org.draken.usagi.list.ui.config.FavoritesOptionsMode
import org.draken.usagi.list.ui.config.ListConfigBottomSheet
import org.draken.usagi.list.ui.config.ListConfigSection
import java.util.EnumMap

@AndroidEntryPoint
class FavouritesContainerFragment :
	BaseFragment<FragmentFavouritesContainerBinding>(),
	ActionModeListener,
	RecyclerViewOwner,
	FavouritesOptionsHost {
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
		binding.textRulesSummary.setOnClickListener { showFavouritesOptions() }
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
			stageChips.children.forEach { child -> child.isEnabled = false }
			textRulesSummary.isEnabled = false
		}
	}

	override fun onActionModeFinished(mode: ActionMode) {
		viewBinding?.run {
			pager.isUserInputEnabled = true
			tabs.setTabsEnabled(true)
			stageChips.children.forEach { child -> child.isEnabled = true }
			textRulesSummary.isEnabled = true
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
		val selectedFilters = state.selectedRuleOptions
		val summary = buildRulesSummary(tab, selectedFilters)
		binding.textRulesSummary.text = summary
		binding.textRulesSummary.isVisible = summary != null
	}

	private fun buildRulesSummary(
		tab: FavouriteTabModel,
		selectedFilters: Set<ListFilterOption>,
	): CharSequence? {
		val persistentSummary = tab.rules?.formatSummary(requireContext())
		return buildFavouriteRulesSummary(
			invalidRulesLabel = tab.rulesError?.let { getString(R.string.favourite_organizer_invalid_rules) },
			persistentSummary = persistentSummary,
			selectedFilterTitles = selectedFilters.map(::getFilterTitle),
			overflowFilterSummary = getString(R.string.favourite_organizer_filter_count, selectedFilters.size),
		)
	}

	private fun getFilterTitle(option: ListFilterOption): String =
		when (option) {
			is ListFilterOption.Source -> {
				option.mangaSource.getTitle(requireContext())
			}

			else -> {
				option.titleText?.toString()
					?: getString(option.titleResId.also { require(it != 0) { "Filter option has no display title" } })
			}
		}

	private fun showRefreshResult(result: FavouriteOrganizerRefreshResult) {
		if (result.failed == 0) return
		val binding = viewBinding ?: return
		val message = getString(R.string.favourite_organizer_refresh_result, result.updated, result.requested, result.failed)
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

	override fun showFavouritesOptions() {
		if (childFragmentManager.findFragmentByTag(LIST_CONFIG_TAG) != null) return
		ListConfigBottomSheet()
			.withArgs(1) {
				putParcelable(
					AppRouter.KEY_LIST_SECTION,
					ListConfigSection.Favorites(
						categoryId = categoryId ?: FavouritesListFragment.NO_ID,
						mode = FavoritesOptionsMode.ORGANIZER,
					),
				)
			}.show(childFragmentManager, LIST_CONFIG_TAG)
	}

	override fun currentFavouritesOptions(): FavouritesPageUiState? = findCurrentPage()?.uiState?.value

	override fun currentFavouritesSortOrder(): ListSortOrder? = findCurrentPage()?.sortOrder?.value

	override fun applyFavouritesFilters(options: Set<ListFilterOption>) {
		val page = findCurrentPage() ?: return
		val available =
			page.uiState.value.availableRuleOptions
				.toSet()
		page.clearRuleOptions()
		options.filter(available::contains).forEach { option -> page.setRuleOption(option, true) }
	}

	override fun setFavouritesSortOrder(sortOrder: ListSortOrder) {
		findCurrentPage()?.setSortOrder(sortOrder)
	}

	override fun openSmartFolders() {
		router.openSmartFolders()
	}

	private companion object {
		const val LIST_CONFIG_TAG = "favourites_list_config"
	}
}
