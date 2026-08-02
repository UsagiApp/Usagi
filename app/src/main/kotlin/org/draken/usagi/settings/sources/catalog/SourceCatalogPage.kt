package org.draken.usagi.settings.sources.catalog

import org.draken.usagi.list.ui.ListModelDiffCallback
import org.draken.usagi.list.ui.model.ListModel
import tsuki.model.ContentType

data class SourceCatalogPage(
	val type: ContentType,
	val items: List<SourceCatalogItem>,
) : ListModel {
	override fun areItemsTheSame(other: ListModel): Boolean = other is SourceCatalogPage && other.type == type

	override fun getChangePayload(previousState: ListModel): Any = ListModelDiffCallback.PAYLOAD_NESTED_LIST_CHANGED
}
