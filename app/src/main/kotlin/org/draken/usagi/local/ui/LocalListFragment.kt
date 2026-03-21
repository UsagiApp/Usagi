package org.draken.usagi.local.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.view.ActionMode
import androidx.core.net.toFile
import androidx.core.net.toUri
import androidx.fragment.app.viewModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import org.draken.usagi.R
import org.draken.usagi.core.model.LocalMangaSource
import org.draken.usagi.core.nav.router
import org.draken.usagi.core.ui.list.ListSelectionController
import org.draken.usagi.core.ui.widgets.TipView
import org.draken.usagi.core.util.ShareHelper
import org.draken.usagi.core.util.ext.addMenuProvider
import org.draken.usagi.core.util.ext.observeEvent
import org.draken.usagi.core.util.ext.tryLaunch
import org.draken.usagi.databinding.FragmentListBinding
import org.draken.usagi.filter.ui.FilterCoordinator
import org.draken.usagi.list.ui.MangaListFragment
import org.draken.usagi.remotelist.ui.MangaSearchMenuProvider
import org.draken.usagi.remotelist.ui.RemoteListFragment
import org.draken.usagi.settings.storage.RequestStorageManagerPermissionContract

class LocalListFragment : MangaListFragment(), FilterCoordinator.Owner {

	private val permissionRequestLauncher = registerForActivityResult(
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
			RequestStorageManagerPermissionContract()
		} else {
			ActivityResultContracts.RequestPermission()
		},
	) {
		if (it) {
			viewModel.onRefresh()
		}
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		val args = arguments ?: Bundle(1)
		args.putString(
			RemoteListFragment.ARG_SOURCE,
			LocalMangaSource.name,
		) // required by FilterCoordinator
		arguments = args
	}

	override val viewModel by viewModels<LocalListViewModel>()

	override val filterCoordinator: FilterCoordinator
		get() = viewModel.filterCoordinator

	override fun onViewBindingCreated(binding: FragmentListBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		addMenuProvider(LocalListMenuProvider(this, this::onEmptyActionClick))
		addMenuProvider(MangaSearchMenuProvider(filterCoordinator, viewModel))
		viewModel.onMangaRemoved.observeEvent(viewLifecycleOwner) { onItemRemoved() }
	}

	override fun onEmptyActionClick() {
		router.showImportDialog()
	}

	override fun onFilterClick(view: View?) {
		router.showFilterSheet()
	}

	override fun onPrimaryButtonClick(tipView: TipView) {
		if (!permissionRequestLauncher.tryLaunch(Manifest.permission.READ_EXTERNAL_STORAGE)) {
			Snackbar.make(tipView, R.string.operation_not_supported, Snackbar.LENGTH_SHORT).show()
		}
	}

	override fun onSecondaryButtonClick(tipView: TipView) {
		router.openDirectoriesSettings()
	}

	override fun onScrolledToEnd() = viewModel.loadNextPage()

	override fun onCreateActionMode(
		controller: ListSelectionController,
		menuInflater: MenuInflater,
		menu: Menu,
	): Boolean {
		menuInflater.inflate(R.menu.mode_local, menu)
		return super.onCreateActionMode(controller, menuInflater, menu)
	}

	override fun onActionItemClicked(
		controller: ListSelectionController,
		mode: ActionMode?,
		item: MenuItem,
	): Boolean {
		return when (item.itemId) {
			R.id.action_remove -> {
				showDeletionConfirm(selectedItemsIds, mode)
				true
			}

			R.id.action_share -> {
				val files = selectedItems.map { it.url.toUri().toFile() }
				ShareHelper(requireContext()).shareCbz(files)
				mode?.finish()
				true
			}

			else -> super.onActionItemClicked(controller, mode, item)
		}
	}

	private fun showDeletionConfirm(ids: Set<Long>, mode: ActionMode?) {
		MaterialAlertDialogBuilder(context ?: return)
			.setTitle(R.string.delete_manga)
			.setMessage(getString(R.string.text_delete_local_manga_batch))
			.setPositiveButton(R.string.delete) { _, _ ->
				viewModel.delete(ids)
				mode?.finish()
			}
			.setNegativeButton(android.R.string.cancel, null)
			.show()
	}

	private fun onItemRemoved() {
		Snackbar.make(
			requireViewBinding().recyclerView,
			R.string.removal_completed,
			Snackbar.LENGTH_SHORT,
		).show()
	}
}
