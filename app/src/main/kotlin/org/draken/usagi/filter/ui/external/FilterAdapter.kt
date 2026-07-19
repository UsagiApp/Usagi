package org.draken.usagi.filter.ui.external

import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePaddingRelative
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import eu.kanade.tachiyomi.source.model.Filter
import org.draken.usagi.R
import org.draken.usagi.core.ui.BaseListAdapter
import org.draken.usagi.core.ui.widgets.ChipsView
import org.draken.usagi.databinding.ItemCheckboxBinding
import org.draken.usagi.databinding.ItemChipsBinding
import org.draken.usagi.databinding.ItemExpandableBinding
import org.draken.usagi.databinding.ItemExternalHeaderBinding
import org.draken.usagi.databinding.ItemSeparatorBinding
import org.draken.usagi.databinding.ItemSelectBinding
import org.draken.usagi.databinding.ItemSortBinding
import org.draken.usagi.databinding.ItemTextBinding
import org.draken.usagi.databinding.ItemTristateBinding
import org.draken.usagi.filter.ui.external.model.FilterItem
import org.draken.usagi.list.ui.adapter.ListItemType

private const val INDENT_DP = 16

class FilterAdapter(listener: FilterListener) : BaseListAdapter<FilterItem>() {

	init {
		addDelegate(ListItemType.T_HEADER, headerDelegate())
		addDelegate(ListItemType.SEPARATOR, separatorDelegate())
		addDelegate(ListItemType.CHECKBOX, checkBoxDelegate(listener))
		addDelegate(ListItemType.CHIPS, checkBoxChipsDelegate(listener))
		addDelegate(ListItemType.TRISTATE, triStateDelegate(listener))
		addDelegate(ListItemType.TEXT, textDelegate(listener))
		addDelegate(ListItemType.SELECT, selectDelegate(listener))
		addDelegate(ListItemType.EXPANDABLE, expandableDelegate(listener))
		addDelegate(ListItemType.OPTION, sortOptionDelegate(listener))
	}

	private fun View.applyPaddingIndent(depth: Int) {
		val step = (INDENT_DP * resources.displayMetrics.density).toInt()
		val base = resources.getDimensionPixelOffset(R.dimen.margin_normal)
		updatePaddingRelative(start = base + depth * step)
	}

	private fun View.applyMarginIndent(depth: Int) {
		val step = (INDENT_DP * resources.displayMetrics.density).toInt()
		val base = resources.getDimensionPixelOffset(R.dimen.margin_normal)
		updateLayoutParams<android.view.ViewGroup.MarginLayoutParams> { marginStart = base + depth * step }
	}

	private fun View.applyInputPaddingIndent(depth: Int) {
		val step = (INDENT_DP * resources.displayMetrics.density).toInt()
		val base = resources.getDimensionPixelOffset(R.dimen.margin_normal)
		val inset = base + depth * step
		updatePaddingRelative(start = inset, end = inset)
	}

	private fun headerDelegate() =
		adapterDelegateViewBinding<FilterItem.Header, FilterItem, ItemExternalHeaderBinding>(
			{ inflater, parent -> ItemExternalHeaderBinding.inflate(inflater, parent, false) },
		) {
			bind {
				binding.textViewTitle.text = item.title
				binding.root.applyPaddingIndent(item.depth)
			}
		}

	private fun separatorDelegate() =
		adapterDelegateViewBinding<FilterItem.Separator, FilterItem, ItemSeparatorBinding>(
			{ inflater, parent -> ItemSeparatorBinding.inflate(inflater, parent, false) },
		) {} // Nothing to bind

	private fun checkBoxDelegate(listener: FilterListener) =
		adapterDelegateViewBinding<FilterItem.CheckBox, FilterItem, ItemCheckboxBinding>(
			{ inflater, parent -> ItemCheckboxBinding.inflate(inflater, parent, false) },
		) {
			binding.layoutRoot.setOnClickListener { listener.onCheckBoxClick(item) }
			bind {
				binding.textViewTitle.text = item.title
				binding.checkbox.isChecked = item.isChecked
				binding.layoutRoot.applyPaddingIndent(item.depth)
			}
		}

	private fun checkBoxChipsDelegate(listener: FilterListener) =
		adapterDelegateViewBinding<FilterItem.CheckBoxChips, FilterItem, ItemChipsBinding>(
			{ inflater, parent -> ItemChipsBinding.inflate(inflater, parent, false) },
		) {
			binding.chipsView.onChipClickListener = ChipsView.OnChipClickListener { _, data ->
				(data as? String)?.let(listener::onCheckBoxChipClick)
			}
			bind {
				binding.chipsView.setChips(
					item.chips.map { chip ->
						ChipsView.ChipModel(
							title = chip.title,
							isChecked = chip.checked,
							data = chip.path,
						)
					},
				)
				binding.chipsView.applyMarginIndent(item.depth)
			}
		}

	private fun triStateDelegate(listener: FilterListener) =
		adapterDelegateViewBinding<FilterItem.TriState, FilterItem, ItemTristateBinding>(
			{ inflater, parent -> ItemTristateBinding.inflate(inflater, parent, false) },
		) {
			binding.layoutRoot.setOnClickListener { listener.onTriStateClick(item) }
			bind {
				binding.textViewTitle.text = item.title
				val iconRes = when (item.state) {
					Filter.TriState.STATE_INCLUDE -> R.drawable.ic_true
					Filter.TriState.STATE_EXCLUDE -> R.drawable.ic_false
					else -> R.drawable.ic_uncheck
				}
				binding.imageViewState.setImageResource(iconRes)
				binding.layoutRoot.applyPaddingIndent(item.depth)
			}
		}

	private fun textDelegate(listener: FilterListener) =
		adapterDelegateViewBinding<FilterItem.Text, FilterItem, ItemTextBinding>(
			{ inflater, parent -> ItemTextBinding.inflate(inflater, parent, false) },
		) {
			fun commit() { listener.onTextChanged(item, binding.editText.text?.toString().orEmpty()) }
			binding.editText.setOnEditorActionListener { _, actionId, _ ->
				if (actionId == EditorInfo.IME_ACTION_DONE) {
					commit()
					binding.editText.clearFocus()
					true
				} else false
			}
			binding.editText.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commit() }
			bind {
				binding.layoutInput.hint = item.title
				if (binding.editText.text?.toString() != item.value) binding.editText.setText(item.value)
				binding.root.applyInputPaddingIndent(item.depth)
			}
		}

	private fun selectDelegate(listener: FilterListener) =
		adapterDelegateViewBinding<FilterItem.Select, FilterItem, ItemSelectBinding>(
			{ inflater, parent -> ItemSelectBinding.inflate(inflater, parent, false) },
		) {
			binding.editSelect.setOnItemClickListener { _, _, p, _ -> listener.onSelectChanged(item, p) }
			bind {
				binding.layoutInput.hint = item.title
				binding.editSelect.setAdapter(ArrayAdapter(context, android.R.layout.simple_list_item_1, item.options))
				val selected = item.options.getOrNull(item.selectedIndex).orEmpty()
				binding.editSelect.setText(selected, false)
				binding.root.applyInputPaddingIndent(item.depth)
			}
		}

	private fun expandableDelegate(listener: FilterListener) =
		adapterDelegateViewBinding<FilterItem.ExpandableHeader, FilterItem, ItemExpandableBinding>(
			{ inflater, parent -> ItemExpandableBinding.inflate(inflater, parent, false) },
		) {
			binding.layoutRoot.setOnClickListener { listener.onExpandClick(item) }
			bind {
				binding.textViewTitle.text = item.title
				val summary = item.activeSummary
				if (summary.isNullOrEmpty()) { binding.textViewSummary.visibility = View.GONE } else {
					binding.textViewSummary.visibility = View.VISIBLE
					binding.textViewSummary.text = summary
				}
				binding.imageViewChevron.rotation = if (item.isExpanded) 0f else 180f
				binding.layoutRoot.applyPaddingIndent(item.depth)
			}
		}

	private fun sortOptionDelegate(listener: FilterListener) =
		adapterDelegateViewBinding<FilterItem.SortOption, FilterItem, ItemSortBinding>(
			{ inflater, parent -> ItemSortBinding.inflate(inflater, parent, false) },
		) {
			binding.layoutRoot.setOnClickListener { listener.onSortOptionClick(item) }
			bind {
				binding.textViewTitle.text = item.title
				when (item.isAscending) {
					true -> {
						binding.imageViewArrow.visibility = View.VISIBLE
						binding.imageViewArrow.rotation = 0f
					}

					false -> {
						binding.imageViewArrow.visibility = View.VISIBLE
						binding.imageViewArrow.rotation = 180f
					}

					null -> binding.imageViewArrow.visibility = View.INVISIBLE
				}
				binding.layoutRoot.applyPaddingIndent(item.depth)
			}
		}
}
