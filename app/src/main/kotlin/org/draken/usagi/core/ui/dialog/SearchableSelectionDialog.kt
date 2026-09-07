package org.draken.usagi.core.ui.dialog

import android.content.Context
import android.widget.ArrayAdapter
import androidx.annotation.StringRes
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.draken.usagi.R
import org.draken.usagi.databinding.DialogSearchableSelectionBinding

object SearchableSelectionDialog {
	fun <T> show(
		context: Context,
		@StringRes titleResId: Int,
		items: List<SearchableSelectionItem<T>>,
		selected: Set<T>,
		onApply: (Set<T>) -> Unit,
	) {
		val binding = DialogSearchableSelectionBinding.inflate(android.view.LayoutInflater.from(context))
		val state = SearchableSelectionState(items, selected)
		var visibleItems = emptyList<SearchableSelectionItem<T>>()

		fun render(query: String) {
			visibleItems = state.filtered(query)
			binding.listView.adapter =
				ArrayAdapter(
					context,
					android.R.layout.simple_list_item_multiple_choice,
					visibleItems.map(SearchableSelectionItem<T>::title),
				)
			visibleItems.forEachIndexed { index, item ->
				binding.listView.setItemChecked(index, state.isSelected(item.id))
			}
		}

		binding.listView.setOnItemClickListener { _, _, position, _ ->
			val item = visibleItems.getOrNull(position) ?: return@setOnItemClickListener
			state.setSelected(item.id, binding.listView.isItemChecked(position))
		}
		binding.editSearch.doAfterTextChanged { text -> render(text?.toString().orEmpty()) }
		render("")

		val dialog =
			MaterialAlertDialogBuilder(context)
				.setTitle(titleResId)
				.setView(binding.root)
				.setNeutralButton(R.string.clear, null)
				.setNegativeButton(android.R.string.cancel, null)
				.setPositiveButton(R.string.apply, null)
				.create()
		dialog.setOnShowListener {
			dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
				state.clear()
				render(
					binding.editSearch.text
						?.toString()
						.orEmpty(),
				)
			}
			dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
				onApply(state.selection())
				dialog.dismiss()
			}
		}
		dialog.show()
	}
}
