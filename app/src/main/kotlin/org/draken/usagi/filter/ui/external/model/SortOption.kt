package org.draken.usagi.filter.ui.external.model

import org.draken.usagi.list.ui.ListModelDiffCallback.Companion.PAYLOAD_CHECKED_CHANGED
import org.draken.usagi.list.ui.model.ListModel

/** A single row of the compact sort picker. [id] is the source sort index or the [java.lang.Enum.ordinal] of a SortOrder. */
class SortOption(
	val id: Int,
	val title: String,
	val indicator: Indicator,
) : ListModel {

	enum class Indicator { NONE, ASCENDING, DESCENDING, SELECTED }

	override fun areItemsTheSame(other: ListModel) = other is SortOption && other.id == id

	override fun equals(other: Any?) =
		other is SortOption && other.id == id && other.title == title && other.indicator == indicator

	override fun hashCode() = id * 31 + indicator.ordinal

	override fun getChangePayload(previousState: ListModel) =
		if (previousState is SortOption && previousState.indicator != indicator) PAYLOAD_CHECKED_CHANGED else null
}
