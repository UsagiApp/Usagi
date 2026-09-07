package org.draken.usagi.favourites.ui.list

import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.appcompat.view.ActionMode
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.ItemTouchHelper
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import org.draken.usagi.R
import org.draken.usagi.core.nav.AppRouter
import org.draken.usagi.core.ui.list.ListSelectionController
import org.draken.usagi.core.util.ext.sortedByOrdinal
import org.draken.usagi.core.util.ext.withArgs
import org.draken.usagi.databinding.FragmentListBinding
import org.draken.usagi.favourites.domain.FavouriteScope
import org.draken.usagi.favourites.domain.FavouriteStage
import org.draken.usagi.favourites.ui.FavouritesPage
import org.draken.usagi.list.domain.ListFilterOption
import org.draken.usagi.list.domain.ListSortOrder
import org.draken.usagi.list.ui.MangaListFragment
import org.draken.usagi.list.ui.adapter.MangaListAdapter
import org.draken.usagi.list.ui.model.MangaListModel

@AndroidEntryPoint
class FavouritesListFragment :
	MangaListFragment(),
	FavouritesPage,
	PopupMenu.OnMenuItemClickListener {
	override val viewModel by viewModels<FavouritesListViewModel>()

	override val isSwipeRefreshEnabled = false

	val categoryId
		get() = viewModel.categoryId
	val scope
		get() = viewModel.scope
	override val uiState
		get() = viewModel.pageUiState
	override val organizerRefreshResults
		get() = viewModel.onOrganizerRefreshed
	override val sortOrder
		get() = viewModel.sortOrder

	override fun setStage(stage: FavouriteStage) = viewModel.setStage(stage)

	override fun setRuleOption(
		option: ListFilterOption,
		isApplied: Boolean,
	) = viewModel.setFilterOption(option, isApplied)

	override fun clearRuleOptions() = viewModel.clearFilter()

	override fun setSortOrder(sortOrder: ListSortOrder) = viewModel.setSortOrder(sortOrder)

	private val favouritesAdapter: MangaListAdapter?
		get() = recyclerView?.adapter as? MangaListAdapter

	private var reorderHelper: ItemTouchHelper? = null

	override fun onViewBindingCreated(
		binding: FragmentListBinding,
		savedInstanceState: Bundle?,
	) {
		super.onViewBindingCreated(binding, savedInstanceState)
		binding.recyclerView.isVP2BugWorkaroundEnabled = true
		reorderHelper =
			ItemTouchHelper(
				FavouritesReorderCallback(
					sortOrder = { viewModel.sortOrder.value },
					getAdapter = { favouritesAdapter },
					getSelectedItemsIds = { selectedItemsIds },
					saveMangaOrder = { viewModel.saveMangaOrder(it) },
					onDragStateChanged = { isDrag -> recyclerView?.isNestedScrollingEnabled = !isDrag },
					canDrag = { viewModel.categoryId != NO_ID && selectedItemsIds.isNotEmpty() },
				),
			).also { it.attachToRecyclerView(binding.recyclerView) }
		binding.recyclerView.addOnItemTouchListener(
			FavouritesTouchListener(
				sortOrder = { viewModel.sortOrder.value },
				reorderHelper = { reorderHelper },
				canDrag = { viewModel.categoryId != NO_ID && selectedItemsIds.isNotEmpty() },
			),
		)
	}

	override fun onScrolledToEnd() = viewModel.requestMoreItems()

	override fun onEmptyActionClick() = viewModel.clearFilter()

	override fun onFilterClick(view: View?) {
		val menu = PopupMenu(view?.context ?: return, view)
		menu.setOnMenuItemClickListener(this)
		val orders = ListSortOrder.FAVORITES.sortedByOrdinal()
		for ((i, item) in orders.withIndex()) {
			menu.menu.add(Menu.NONE, Menu.NONE, i, item.titleResId)
		}
		menu.show()
	}

	override fun onMenuItemClick(item: MenuItem): Boolean {
		val order = ListSortOrder.FAVORITES.sortedByOrdinal().getOrNull(item.order) ?: return false
		viewModel.setSortOrder(order)
		return true
	}

	override fun onCreateActionMode(
		controller: ListSelectionController,
		menuInflater: MenuInflater,
		menu: Menu,
	): Boolean {
		menuInflater.inflate(R.menu.mode_favourites, menu)
		return super.onCreateActionMode(controller, menuInflater, menu)
	}

	override fun onActionItemClicked(
		controller: ListSelectionController,
		mode: ActionMode?,
		item: MenuItem,
	): Boolean {
		return when (item.itemId) {
			R.id.action_remove -> {
				viewModel.removeFromFavourites(selectedItemsIds)
				mode?.finish()
				true
			}

			R.id.action_mark_current -> {
				val itemsSnapshot = selectedItems
				MaterialAlertDialogBuilder(context ?: return false)
					.setTitle(item.title)
					.setMessage(R.string.mark_as_completed_prompt)
					.setNegativeButton(android.R.string.cancel, null)
					.setPositiveButton(android.R.string.ok) { _, _ ->
						viewModel.markAsRead(itemsSnapshot)
						mode?.finish()
					}.show()
				true
			}

			else -> {
				super.onActionItemClicked(controller, mode, item)
			}
		}
	}

	override fun onItemLongClick(
		item: MangaListModel,
		view: View,
	): Boolean {
		if (viewModel.sortOrder.value == ListSortOrder.NEWEST && selectedItemsIds.isNotEmpty()) {
			val holder = recyclerView?.findContainingViewHolder(view)
			if (holder != null) {
				reorderHelper?.startDrag(holder)
				return true
			}
		}
		return super.onItemLongClick(item, view)
	}

	companion object {
		const val NO_ID = 0L
		const val KEY_SCOPE_TYPE = "favourite_scope_type"
		const val KEY_STAGE = "favourite_stage"
		const val SCOPE_ALL = "all"
		const val SCOPE_CATEGORY = "category"
		const val SCOPE_SMART_FOLDER = "smart_folder"

		fun newInstance(scope: FavouriteScope) =
			FavouritesListFragment().withArgs(2) {
				when (scope) {
					FavouriteScope.All -> {
						putString(KEY_SCOPE_TYPE, SCOPE_ALL)
					}

					is FavouriteScope.Category -> {
						putString(KEY_SCOPE_TYPE, SCOPE_CATEGORY)
						putLong(AppRouter.KEY_ID, scope.id)
					}

					is FavouriteScope.SmartFolder -> {
						putString(KEY_SCOPE_TYPE, SCOPE_SMART_FOLDER)
						putLong(AppRouter.KEY_ID, scope.id)
					}
				}
			}
	}
}
