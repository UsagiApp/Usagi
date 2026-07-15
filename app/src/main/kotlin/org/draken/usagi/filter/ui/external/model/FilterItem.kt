package org.draken.usagi.filter.ui.external.model

import org.draken.usagi.list.ui.ListModelDiffCallback.Companion.PAYLOAD_CHECKED_CHANGED
import org.draken.usagi.list.ui.model.ListModel

/**
 * View models for the dynamic Mihon filter sheet. Each item is identified by the [path] of its backing
 * Mihon `Filter` within the filter tree (e.g. `2`, `5.1`); [depth] drives left indentation for nested
 * group/sort children.
 */
sealed class FilterItem(
	val path: String,
	val depth: Int,
) : ListModel {

	class Header(path: String, depth: Int, val title: String) : FilterItem(path, depth) {
		override fun areItemsTheSame(other: ListModel) = other is Header && other.path == path
		override fun equals(other: Any?) = other is Header && other.path == path && other.title == title && other.depth == depth
		override fun hashCode() = path.hashCode() * 31 + title.hashCode()
	}

	class Separator(path: String, depth: Int) : FilterItem(path, depth) {
		override fun areItemsTheSame(other: ListModel) = other is Separator && other.path == path
		override fun equals(other: Any?) = other is Separator && other.path == path
		override fun hashCode() = path.hashCode()
	}

	class CheckBox(path: String, depth: Int, val title: String, val isChecked: Boolean) : FilterItem(path, depth) {
		override fun areItemsTheSame(other: ListModel) = other is CheckBox && other.path == path
		override fun equals(other: Any?) =
			other is CheckBox && other.path == path && other.title == title && other.isChecked == isChecked && other.depth == depth
		override fun hashCode() = path.hashCode() * 31 + isChecked.hashCode()
		override fun getChangePayload(previousState: ListModel) =
			if (previousState is CheckBox && previousState.isChecked != isChecked) PAYLOAD_CHECKED_CHANGED else null
	}

	/** [state] uses `Filter.TriState.STATE_*` (0 ignore, 1 include, 2 exclude). */
	class TriState(path: String, depth: Int, val title: String, val state: Int) : FilterItem(path, depth) {
		override fun areItemsTheSame(other: ListModel) = other is TriState && other.path == path
		override fun equals(other: Any?) =
			other is TriState && other.path == path && other.title == title && other.state == state && other.depth == depth
		override fun hashCode() = path.hashCode() * 31 + state
		override fun getChangePayload(previousState: ListModel) =
			if (previousState is TriState && previousState.state != state) PAYLOAD_CHECKED_CHANGED else null
	}

	class Text(path: String, depth: Int, val title: String, val value: String) : FilterItem(path, depth) {
		override fun areItemsTheSame(other: ListModel) = other is Text && other.path == path
		override fun equals(other: Any?) =
			other is Text && other.path == path && other.title == title && other.value == value && other.depth == depth
		override fun hashCode() = path.hashCode() * 31 + value.hashCode()
	}

	class Select(
		path: String, depth: Int, val title: String,
		val options: List<String>, val selectedIndex: Int,
	) : FilterItem(path, depth) {
		override fun areItemsTheSame(other: ListModel) = other is Select && other.path == path
		override fun equals(other: Any?) =
			other is Select && other.path == path && other.title == title && other.options == options &&
				other.selectedIndex == selectedIndex && other.depth == depth
		override fun hashCode() = path.hashCode() * 31 + selectedIndex
	}

	/** A run of [eu.kanade.tachiyomi.source.model.Filter.CheckBox]es rendered as a wrapping chip row. */
	class CheckBoxChips(path: String, depth: Int, val chips: List<Chip>) : FilterItem(path, depth) {
		/** [path] is the backing checkbox's index path; [checked] its current state. */
		class Chip(val path: String, val title: String, val checked: Boolean) {
			override fun equals(other: Any?) =
				other is Chip && other.path == path && other.title == title && other.checked == checked
			override fun hashCode() = (path.hashCode() * 31 + title.hashCode()) * 31 + checked.hashCode()
		}

		override fun areItemsTheSame(other: ListModel) = other is CheckBoxChips && other.path == path
		override fun equals(other: Any?) =
			other is CheckBoxChips && other.path == path && other.chips == chips && other.depth == depth
		override fun hashCode() = path.hashCode() * 31 + chips.hashCode()
	}

	/** Expandable header for a [eu.kanade.tachiyomi.source.model.Filter.Group] or `Filter.Sort`. */
	class ExpandableHeader(
		path: String, depth: Int, val title: String,
		val isExpanded: Boolean, val activeSummary: String?,
	) : FilterItem(path, depth) {
		override fun areItemsTheSame(other: ListModel) = other is ExpandableHeader && other.path == path
		override fun equals(other: Any?) =
			other is ExpandableHeader && other.path == path && other.title == title &&
				other.isExpanded == isExpanded && other.activeSummary == activeSummary && other.depth == depth
		override fun hashCode() = path.hashCode() * 31 + isExpanded.hashCode()
	}

	/** A single option row of a `Filter.Sort`. [isAscending] is null when this option isn't selected. */
	class SortOption(
		path: String, depth: Int, val optionIndex: Int,
		val title: String, val isAscending: Boolean?,
	) : FilterItem(path, depth) {
		override fun areItemsTheSame(other: ListModel) =
			other is SortOption && other.path == path && other.optionIndex == optionIndex
		override fun equals(other: Any?) =
			other is SortOption && other.path == path && other.optionIndex == optionIndex &&
				other.title == title && other.isAscending == isAscending && other.depth == depth
		override fun hashCode() = (path.hashCode() * 31 + optionIndex) * 31 + (isAscending?.hashCode() ?: 0)
		override fun getChangePayload(previousState: ListModel) =
			if (previousState is SortOption && previousState.isAscending != isAscending) PAYLOAD_CHECKED_CHANGED else null
	}
}
