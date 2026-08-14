package org.draken.usagi.core.util

import java.util.Locale

fun canonicalLanguageCode(value: String?): String? {
	if (value == null) return null
	val normalized = value.trim().replace('_', '-')
	if (normalized.isBlank() || normalized.equals("all", true)) return "all"
	return normalized.substringBefore('-').lowercase(Locale.ROOT)
}
