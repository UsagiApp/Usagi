package org.draken.usagi.core.prefs

import android.content.Context
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import androidx.core.content.edit
import org.draken.usagi.core.util.ext.getEnumValue
import org.draken.usagi.core.util.ext.putEnumValue
import org.draken.usagi.core.util.ext.sanitizeHeaderValue
import org.draken.usagi.core.model.PluginMangaSource
import org.draken.usagi.core.model.TachiyomiPluginSource
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.config.MangaSourceConfig
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.ifNullOrEmpty
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.draken.usagi.settings.utils.validation.DomainValidator
import java.io.File

class SourceSettings(context: Context, source: MangaSource) : MangaSourceConfig {

	private val prefsName = prefsName(source)
	private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

	init {
		migrateLegacyTachiyomiPrefs(context, source, prefsName)
	}

	var defaultSortOrder: SortOrder?
		get() = prefs.getEnumValue(KEY_SORT_ORDER, SortOrder::class.java)
		set(value) = prefs.edit { putEnumValue(KEY_SORT_ORDER, value) }

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

	operator fun <T> set(key: ConfigKey<T>, value: T) = prefs.edit {
		when (key) {
			is ConfigKey.Domain -> putString(key.key, value as String?)
			is ConfigKey.ShowSuspiciousContent -> putBoolean(key.key, value as Boolean)
			is ConfigKey.UserAgent -> putString(key.key, (value as String?)?.sanitizeHeaderValue())
			is ConfigKey.SplitByTranslations -> putBoolean(key.key, value as Boolean)
			is ConfigKey.PreferredImageServer -> putString(key.key, value as String? ?: "")
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

			fun prefsName(source: MangaSource): String {
				val unwrapped = if (source is PluginMangaSource) source.delegate else source
				if (unwrapped is TachiyomiPluginSource) {
					return "source_${unwrapped.sourceId}"
				}
				val name = if (source is PluginMangaSource) source.sourceName else source.name
				return name.replace(File.separatorChar, '$')
			}

			private fun migrateLegacyTachiyomiPrefs(context: Context, source: MangaSource, targetName: String) {
				val delegate = (source as? PluginMangaSource)?.delegate as? TachiyomiPluginSource ?: return
				val legacyName = delegate.name.replace(File.separatorChar, '$')
				if (legacyName == targetName) return
				val legacy = context.getSharedPreferences(legacyName, Context.MODE_PRIVATE)
				if (legacy.all.isEmpty()) return
				val target = context.getSharedPreferences(targetName, Context.MODE_PRIVATE)
				if (target.all.isNotEmpty()) return
				target.edit(commit = true) {
					for ((key, value) in legacy.all) {
						when (value) {
							is Boolean -> putBoolean(key, value)
							is Float -> putFloat(key, value)
							is Int -> putInt(key, value)
							is Long -> putLong(key, value)
							is String -> putString(key, value)
							is Set<*> -> {
								@Suppress("UNCHECKED_CAST")
								putStringSet(key, value.filterIsInstance<String>().toSet())
							}
						}
					}
				}
			}
		}
}
