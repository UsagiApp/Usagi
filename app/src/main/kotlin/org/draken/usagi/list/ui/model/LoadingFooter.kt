package org.draken.usagi.list.ui.model

data class LoadingFooter
	@JvmOverloads
	constructor(
		val key: Int = 0,
	) : ListModel {
		override fun areItemsTheSame(other: ListModel): Boolean = other is LoadingFooter && key == other.key
	}
