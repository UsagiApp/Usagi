package org.draken.usagi.list.ui.model

data class ErrorFooter(
	val exception: Throwable,
) : ListModel {
	override fun areItemsTheSame(other: ListModel): Boolean = other is ErrorFooter && exception == other.exception
}
