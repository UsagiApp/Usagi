package org.draken.usagi.core.exceptions

data class PluginLoadException(
	val name: String,
	val e: Throwable,
)
