package org.draken.usagi.settings.sources.manage.plugins

data class ExternalPluginDto(
	val repository: String,
	val tag: String,
	val fileName: String,
	val downloadUrl: String,
)
