package org.draken.usagi.tracker.ui.feed.model

import org.draken.usagi.list.ui.ListModelDiffCallback
import org.draken.usagi.list.ui.model.ListModel
import org.draken.usagi.list.ui.model.MangaListModel

data class UpdatedMangaHeader(
	val list: List<MangaListModel>,
) : ListModel {
	override fun areItemsTheSame(other: ListModel): Boolean = other is UpdatedMangaHeader

	override fun getChangePayload(previousState: ListModel): Any = ListModelDiffCallback.PAYLOAD_NESTED_LIST_CHANGED
}
