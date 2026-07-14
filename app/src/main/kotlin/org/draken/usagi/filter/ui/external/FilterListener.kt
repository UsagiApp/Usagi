package org.draken.usagi.filter.ui.external

import org.draken.usagi.filter.ui.external.model.FilterItem

/** Callbacks from the dynamic Mihon filter sheet rows. */
interface FilterListener {
	fun onCheckBoxClick(item: FilterItem.CheckBox)
	fun onCheckBoxChipClick(checkBoxPath: String)
	fun onTriStateClick(item: FilterItem.TriState)
	fun onTextChanged(item: FilterItem.Text, value: String)
	fun onSelectChanged(item: FilterItem.Select, index: Int)
	fun onExpandClick(item: FilterItem.ExpandableHeader)
	fun onSortOptionClick(item: FilterItem.SortOption)
}
