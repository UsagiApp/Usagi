package org.draken.usagi.core.prefs

import android.content.Context
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import androidx.core.content.edit
import org.draken.usagi.core.util.ext.getEnumValue
import org.draken.usagi.core.util.ext.putEnumValue
import org.draken.usagi.core.util.ext.sanitizeHeaderValue
import tsuki.config.ConfigKey
import tsuki.config.MangaSourceConfig
import tsuki.model.MangaSource
import tsuki.model.SortOrder
import tsuki.util.ifNullOrEmpty
import tsuki.util.nullIfEmpty
import org.draken.usagi.settings.utils.validation.DomainValidator

class SourceSettings(context: Context, source: MangaSource) : MangaSourceConfig {

    private val prefs = context.getSharedPreferences(
        prefsName(source),
        Context.MODE_PRIVATE,
    )

	var defaultSortOrder: SortOrder?
		get() = prefs.getEnumValue(KEY_SORT_ORDER, SortOrder::class.java)
		set(value) = prefs.edit(true) { putEnumValue(KEY_SORT_ORDER, value) }

	var lastSortTagKey: String?
		get() = prefs.getString(KEY_LAST_SORT_KEY, null)
		set(value) = prefs.edit(true) { putString(KEY_LAST_SORT_KEY, value) }

	var lastSortTagTitle: String?
		get() = prefs.getString(KEY_LAST_SORT_TITLE, null)
		set(value) = prefs.edit(true) { putString(KEY_LAST_SORT_TITLE, value) }

	val isSlowdownEnabled: Boolean
		get() = prefs.getBoolean(KEY_SLOWDOWN, false)

	val isCaptchaNotificationsDisabled: Boolean
		get() = prefs.getBoolean(KEY_NO_CAPTCHA, false)

	@Suppress("UNCHECKED_CAST")
	override fun <T> get(key: ConfigKey<T>): T {
		return when (key) {
			is ConfigKey.UserAgent -> prefs.getString(key.key, key.defaultValue)
				.ifNullOrEmpty { key.defaultValue }
				.sanitizeHeaderValue()

			is ConfigKey.Domain -> prefs.getString(key.key, key.defaultValue)
				?.trim()
				?.takeIf { DomainValidator.isValidDomain(it) }
				?: key.defaultValue

			is ConfigKey.ShowSuspiciousContent -> prefs.getBoolean(key.key, key.defaultValue)
			is ConfigKey.SplitByTranslations -> prefs.getBoolean(key.key, key.defaultValue)
			is ConfigKey.PreferredImageServer -> prefs.getString(key.key, key.defaultValue)?.nullIfEmpty()
		} as T
	}

	operator fun <T> set(key: ConfigKey<T>, value: T) = prefs.edit(commit = true) {
		when (key) {
			is ConfigKey.Domain -> putString(key.key, value as String?)
			is ConfigKey.ShowSuspiciousContent -> putBoolean(key.key, value as Boolean)
			is ConfigKey.UserAgent -> putString(key.key, (value as String?)?.sanitizeHeaderValue())
			is ConfigKey.SplitByTranslations -> putBoolean(key.key, value as Boolean)
			is ConfigKey.PreferredImageServer -> putString(key.key, value as String?)
		}
	}

	fun subscribe(listener: OnSharedPreferenceChangeListener) {
		prefs.registerOnSharedPreferenceChangeListener(listener)
	}

	fun unsubscribe(listener: OnSharedPreferenceChangeListener) {
		prefs.unregisterOnSharedPreferenceChangeListener(listener)
	}

	companion object {

		const val KEY_DOMAIN = "domain"
		const val KEY_NO_CAPTCHA = "no_captcha"
		const val KEY_SLOWDOWN = "slowdown"
		const val KEY_SORT_ORDER = "sort_order"
		const val KEY_LAST_SORT_KEY = "last_sort_key"
		const val KEY_LAST_SORT_TITLE = "last_sort_title"
		private val SOURCE_REGEX = "[^a-zA-Z0-9]".toRegex()

		fun prefsName(source: MangaSource): String {
			return source.name.substringAfter(':').replace(SOURCE_REGEX, "_") + "_settings"
		}
	}
}
