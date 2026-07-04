package org.draken.usagi.settings.sources.catalog

import tsuki.model.ContentType

data class SourcesCatalogFilter(
	val types: Set<ContentType>,
	val locale: String?,
	val isNewOnly: Boolean,
	val plugin: String?,
)
