package org.draken.usagi.backups.import.mihon.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import org.draken.usagi.R
import org.draken.usagi.backups.domain.BackupSection
import org.draken.usagi.backups.import.mihon.MihonImportService
import org.draken.usagi.core.ui.AlertDialogFragment
import org.draken.usagi.core.ui.list.OnListItemClickListener
import org.draken.usagi.databinding.DialogRestoreBinding

@AndroidEntryPoint
class MihonImportDialogFragment :
	AlertDialogFragment<DialogRestoreBinding>(),
	OnListItemClickListener<org.draken.usagi.backups.ui.restore.BackupSectionModel>,
	View.OnClickListener {

	private var backupUri: android.net.Uri? = null

	override fun onCreateViewBinding(
		inflater: LayoutInflater,
		container: ViewGroup?,
	) = DialogRestoreBinding.inflate(inflater, container, false)

	override fun onViewBindingCreated(
		binding: DialogRestoreBinding,
		savedInstanceState: Bundle?,
	) {
		super.onViewBindingCreated(binding, savedInstanceState)
		backupUri = arguments?.getParcelable(ARG_BACKUP_URI)
		val adapter = org.draken.usagi.backups.ui.restore.BackupSectionsAdapter(this)
		binding.recyclerView.adapter = adapter
		binding.buttonCancel.setOnClickListener(this)
		binding.buttonRestore.setOnClickListener(this)
		binding.progressBar.isVisible = false
		binding.textViewSubtitle.text = getString(R.string.restore_backup)

		val sections = listOf(
			org.draken.usagi.backups.ui.restore.BackupSectionModel(
				section = BackupSection.HISTORY,
				isChecked = true,
				isEnabled = true,
			),
			org.draken.usagi.backups.ui.restore.BackupSectionModel(
				section = BackupSection.CATEGORIES,
				isChecked = true,
				isEnabled = true,
			),
			org.draken.usagi.backups.ui.restore.BackupSectionModel(
				section = BackupSection.FAVOURITES,
				isChecked = true,
				isEnabled = true,
			),
			org.draken.usagi.backups.ui.restore.BackupSectionModel(
				section = BackupSection.SOURCES,
				isChecked = true,
				isEnabled = true,
			),
			org.draken.usagi.backups.ui.restore.BackupSectionModel(
				section = BackupSection.SCROBBLING,
				isChecked = true,
				isEnabled = true,
			),
		)
		adapter.submitList(sections)
	}

	override fun onBuildDialog(builder: MaterialAlertDialogBuilder): MaterialAlertDialogBuilder =
		super
			.onBuildDialog(builder)
			.setTitle(R.string.restore_backup)
			.setCancelable(false)

	override fun onClick(v: View) {
		when (v.id) {
			R.id.button_cancel -> {
				dismiss()
			}

			R.id.button_restore -> {
				val uri = backupUri
				if (uri != null) {
					val sections = getSelectedSections()
					if (MihonImportService.start(requireContext(), uri, sections)) {
						Toast.makeText(v.context, R.string.backup_restored_background, Toast.LENGTH_SHORT).show()
						dismiss()
					} else {
						Toast.makeText(v.context, R.string.operation_not_supported, Toast.LENGTH_SHORT).show()
					}
				}
			}
		}
	}

	override fun onItemClick(
		item: org.draken.usagi.backups.ui.restore.BackupSectionModel,
		view: View,
	) {
		val adapter = (viewBinding?.recyclerView?.adapter as? org.draken.usagi.backups.ui.restore.BackupSectionsAdapter) ?: return
		val currentList = adapter.currentList.toMutableList()
		val index = currentList.indexOfFirst { it.section == item.section }
		if (index >= 0) {
			currentList[index] = item.copy(isChecked = !item.isChecked)
			adapter.submitList(currentList)
		}
	}

	private fun getSelectedSections(): Set<BackupSection> {
		val adapter = (viewBinding?.recyclerView?.adapter as? org.draken.usagi.backups.ui.restore.BackupSectionsAdapter)
			?: return emptySet()
		return adapter.currentList
			.filter { it.isChecked }
			.map { it.section }
			.toSet()
	}

	companion object {
		const val TAG = "MihonImportDialogFragment"
		private const val ARG_BACKUP_URI = "backup_uri"

		fun newInstance(backupUri: android.net.Uri): MihonImportDialogFragment {
			return MihonImportDialogFragment().apply {
				arguments = Bundle().apply {
					putParcelable(ARG_BACKUP_URI, backupUri)
				}
			}
		}
	}
}
