package org.draken.usagi.favourites.ui.smartfolders

import android.os.Bundle
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import androidx.activity.viewModels
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import dagger.hilt.android.AndroidEntryPoint
import org.draken.usagi.R
import org.draken.usagi.core.exceptions.resolve.SnackbarErrorObserver
import org.draken.usagi.core.nav.router
import org.draken.usagi.core.ui.BaseActivity
import org.draken.usagi.core.ui.dialog.buildAlertDialog
import org.draken.usagi.core.util.ext.consumeAllSystemBarsInsets
import org.draken.usagi.core.util.ext.end
import org.draken.usagi.core.util.ext.observe
import org.draken.usagi.core.util.ext.observeEvent
import org.draken.usagi.core.util.ext.systemBarsInsets
import org.draken.usagi.databinding.ActivityCategoriesBinding
import org.draken.usagi.favourites.domain.SmartFolder

@AndroidEntryPoint
class SmartFoldersActivity :
	BaseActivity<ActivityCategoriesBinding>(),
	SmartFoldersAdapter.Listener {
	private val viewModel by viewModels<SmartFoldersViewModel>()
	private val adapter = SmartFoldersAdapter(this)
	private lateinit var reorderHelper: ItemTouchHelper

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(ActivityCategoriesBinding.inflate(layoutInflater))
		setDisplayHomeAsUp(isEnabled = true, showUpAsClose = false)
		setTitle(R.string.smart_folders)
		viewBinding.recyclerView.adapter = adapter
		viewBinding.recyclerView.setHasFixedSize(true)
		viewBinding.fabAdd.contentDescription = getString(R.string.create_smart_folder)
		viewBinding.fabAdd.setOnClickListener { router.openSmartFolderCreate() }

		reorderHelper =
			ItemTouchHelper(ReorderCallback()).also { helper ->
				helper.attachToRecyclerView(viewBinding.recyclerView)
			}
		viewModel.folders.observe(this, adapter::setItems)
		viewModel.onError.observeEvent(this, SnackbarErrorObserver(viewBinding.recyclerView, null))
	}

	override fun onApplyWindowInsets(
		v: View,
		insets: WindowInsetsCompat,
	): WindowInsetsCompat {
		val barsInsets = insets.systemBarsInsets
		viewBinding.recyclerView.updatePadding(
			left = barsInsets.left,
			right = barsInsets.right,
			bottom = barsInsets.bottom,
		)
		viewBinding.appbar.updatePadding(
			left = barsInsets.left,
			right = barsInsets.right,
			top = barsInsets.top,
		)
		viewBinding.fabAdd.updateLayoutParams<MarginLayoutParams> {
			marginEnd = topMargin + barsInsets.end(v)
			bottomMargin = topMargin + barsInsets.bottom
		}
		return insets.consumeAllSystemBarsInsets()
	}

	override fun onEdit(folder: SmartFolder) {
		router.openSmartFolderEdit(folder.id)
	}

	override fun onDelete(folder: SmartFolder) {
		buildAlertDialog(this, isCentered = true) {
			setTitle(R.string.delete_smart_folder)
			setMessage(R.string.smart_folder_delete_confirm)
			setIcon(R.drawable.ic_delete)
			setNegativeButton(android.R.string.cancel, null)
			setPositiveButton(R.string.delete) { _, _ -> viewModel.delete(folder) }
		}.show()
	}

	override fun onStartDrag(holder: RecyclerView.ViewHolder) {
		reorderHelper.startDrag(holder)
	}

	private inner class ReorderCallback : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
		override fun onMove(
			recyclerView: RecyclerView,
			viewHolder: RecyclerView.ViewHolder,
			target: RecyclerView.ViewHolder,
		): Boolean = adapter.move(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)

		override fun onSwiped(
			viewHolder: RecyclerView.ViewHolder,
			direction: Int,
		) = Unit

		override fun isLongPressDragEnabled(): Boolean = false

		override fun clearView(
			recyclerView: RecyclerView,
			viewHolder: RecyclerView.ViewHolder,
		) {
			super.clearView(recyclerView, viewHolder)
			viewModel.saveOrder(adapter.snapshot())
		}
	}
}
