package org.draken.usagi.history.ui

import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.appcompat.view.ActionMode
import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint
import org.draken.usagi.R
import org.draken.usagi.core.nav.router
import org.draken.usagi.core.ui.dialog.buildAlertDialog
import org.draken.usagi.core.ui.list.ListSelectionController
import org.draken.usagi.core.ui.list.RecyclerScrollKeeper
import org.draken.usagi.core.ui.util.MenuInvalidator
import org.draken.usagi.core.util.ext.addMenuProvider
import org.draken.usagi.core.util.ext.observe
import org.draken.usagi.databinding.FragmentListBinding
import org.draken.usagi.list.ui.MangaListFragment
import org.draken.usagi.list.ui.size.DynamicItemSizeResolver

@AndroidEntryPoint
class HistoryListFragment : MangaListFragment() {

	override val viewModel by viewModels<HistoryListViewModel>()
	override val isSwipeRefreshEnabled = false

	override fun onViewBindingCreated(binding: FragmentListBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		RecyclerScrollKeeper(binding.recyclerView).attach()
		addMenuProvider(HistoryListMenuProvider(binding.root.context, router, viewModel))
		viewModel.isStatsEnabled.observe(viewLifecycleOwner, MenuInvalidator(requireActivity()))
	}

	override fun onScrolledToEnd() = viewModel.requestMoreItems()

	override fun onEmptyActionClick() = viewModel.clearFilter()

	override fun onCreateActionMode(
		controller: ListSelectionController,
		menuInflater: MenuInflater,
		menu: Menu
	): Boolean {
		menuInflater.inflate(R.menu.mode_history, menu)
		return super.onCreateActionMode(controller, menuInflater, menu)
	}

	override fun onActionItemClicked(controller: ListSelectionController, mode: ActionMode?, item: MenuItem): Boolean {
		return when (item.itemId) {
			R.id.action_remove -> {
				viewModel.removeFromHistory(selectedItemsIds)
				mode?.finish()
				true
			}

			R.id.action_mark_current -> {
				val itemsSnapshot = selectedItems
				buildAlertDialog(context ?: return false, isCentered = true) {
					setTitle(item.title)
					setIcon(item.icon)
					setMessage(R.string.mark_as_completed_prompt)
					setNegativeButton(android.R.string.cancel, null)
					setPositiveButton(android.R.string.ok) { _, _ ->
						viewModel.markAsRead(itemsSnapshot)
						mode?.finish()
					}
				}.show()
				true
			}

			else -> super.onActionItemClicked(controller, mode, item)
		}
	}

	override fun onCreateAdapter() = HistoryListAdapter(
		this,
		DynamicItemSizeResolver(resources, viewLifecycleOwner, settings, adjustWidth = false),
	)
}
