package org.draken.usagi.filter.ui.saved

import android.content.Context
import android.text.InputFilter
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.widget.PopupMenu
import org.draken.usagi.R
import org.draken.usagi.core.ui.dialog.buildAlertDialog
import org.draken.usagi.core.ui.dialog.setEditText
import org.draken.usagi.core.util.AlphanumComparator
import org.draken.usagi.filter.data.PersistableFilter
import org.draken.usagi.filter.data.PersistableFilter.Companion.MAX_TITLE_LENGTH
import org.draken.usagi.filter.ui.FilterCoordinator
import java.util.TreeSet

/** Shared save/rename/delete dialogs and menus for native and dynamic filter sheets. */
class SavedFilterActions(
	private val context: Context,
	private val coordinator: FilterCoordinator,
) {
	fun showSaveDialog(name: String = "") {
		val existingNames =
			coordinator.savedFilters.value.availableItems
				.mapTo(TreeSet(AlphanumComparator()), PersistableFilter::name)
		buildAlertDialog(context) {
			val input =
				setEditText(
					entries = existingNames.toList(),
					inputType = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_FLAG_CAP_SENTENCES,
					singleLine = true,
				)
			input.setHint(R.string.enter_name)
			input.setText(name)
			input.filters += InputFilter.LengthFilter(MAX_TITLE_LENGTH)
			setTitle(R.string.save_filter)
			setPositiveButton(R.string.save) { _, _ ->
				val text = input.text?.toString()?.trim()
				if (text.isNullOrEmpty()) {
					Toast.makeText(context, R.string.invalid_value_message, Toast.LENGTH_SHORT).show()
					showSaveDialog()
				} else if (text in existingNames) {
					confirmOverwrite(text)
				} else {
					coordinator.saveCurrentFilter(text)
				}
			}
			setNegativeButton(android.R.string.cancel, null)
		}.show()
	}

	fun showMenu(
		anchor: View,
		preset: PersistableFilter,
	) {
		val menu = PopupMenu(context, anchor)
		menu.inflate(R.menu.popup_saved_filter)
		menu.setOnMenuItemClickListener { menuItem ->
			when (menuItem.itemId) {
				R.id.action_delete -> coordinator.deleteSavedFilter(preset.id)
				R.id.action_rename -> showRenameDialog(preset)
			}
			true
		}
		menu.show()
	}

	private fun showRenameDialog(preset: PersistableFilter) {
		val existingNames = coordinator.savedFilters.value.availableItems.map { it.name }.toSet()
		buildAlertDialog(context) {
			val input =
				setEditText(
					inputType = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_FLAG_CAP_SENTENCES,
					singleLine = true,
				)
			input.filters += InputFilter.LengthFilter(MAX_TITLE_LENGTH)
			input.setHint(R.string.enter_name)
			input.setText(preset.name)
			setTitle(R.string.rename)
			setPositiveButton(R.string.save) { _, _ ->
				val text = input.text?.toString()?.trim()
				if (text.isNullOrEmpty() || text in existingNames) {
					Toast.makeText(context, R.string.invalid_value_message, Toast.LENGTH_SHORT).show()
				} else {
					coordinator.renameSavedFilter(preset.id, text)
				}
			}
			setNegativeButton(android.R.string.cancel, null)
		}.show()
	}

	private fun confirmOverwrite(name: String) {
		buildAlertDialog(context) {
			setTitle(R.string.save_filter)
			setMessage(context.getString(R.string.filter_overwrite_confirm, name))
			setPositiveButton(R.string.overwrite) { _, _ ->
				coordinator.saveCurrentFilter(name)
			}
			setNegativeButton(android.R.string.cancel) { _, _ ->
				showSaveDialog(name)
			}
		}.show()
	}
}
