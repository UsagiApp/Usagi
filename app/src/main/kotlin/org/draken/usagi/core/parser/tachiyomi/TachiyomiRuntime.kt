package org.draken.usagi.core.parser.tachiyomi

import androidx.preference.PreferenceScreen
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import org.draken.tsukimix.core.parser.tachiyomi.model.TachiyomiMangaSource
import org.draken.usagi.core.model.MangaSourceRegistry
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import org.draken.tsukimix.core.parser.tachiyomi.TachiyomiExtensionManager as InstalledManager

@Singleton
class TachiyomiRuntime
	@Inject
	constructor(
		private val installedManager: InstalledManager,
		private val directManager: DirectTachiyomiExtensionManager,
	) {
		val sources: Flow<List<TachiyomiMangaSource>> =
			combine(installedManager.sources, directManager.sources) { installed, direct ->
				(direct + installed).distinctBy { it.sourceId }
			}.distinctUntilChanged()

		suspend fun ensureReady(forceRefresh: Boolean = false) {
			installedManager.ensureReady(forceRefresh)
			directManager.ensureReady(forceRefresh)
			syncRegistry()
		}

		private fun syncRegistry() {
			val nonTachiyomi = MangaSourceRegistry.sources.filterNot { it is TachiyomiMangaSource }
			MangaSourceRegistry.publish(nonTachiyomi + getActiveSources())
		}

		fun getActiveSources(): List<TachiyomiMangaSource> = merge(installedManager.getActiveSources(), directManager.getActiveSources())

		fun getSourceByName(name: String): TachiyomiMangaSource? = directManager.getSourceByName(name) ?: installedManager.getSourceByName(name)

		fun resolve(source: TachiyomiMangaSource): TachiyomiMangaSource = if (directManager.owns(source)) directManager.resolve(source) else installedManager.resolve(source)

		fun getLanguage(source: TachiyomiMangaSource): List<TachiyomiMangaSource> = if (directManager.owns(source)) directManager.getLanguage(source) else installedManager.getLanguage(source)

		fun getActiveLanguage(source: TachiyomiMangaSource): String? = if (directManager.owns(source)) directManager.getActiveLanguage(source) else installedManager.getActiveLanguage(source)

		fun setActiveLanguage(
			source: TachiyomiMangaSource,
			language: String,
		) {
			if (directManager.owns(source)) directManager.setActiveLanguage(source, language) else installedManager.setActiveLanguage(source, language)
		}

		fun addLangToPref(
			screen: PreferenceScreen,
			source: TachiyomiMangaSource,
			title: CharSequence,
			onChanged: () -> Unit,
		) {
			val variants = getLanguage(source).distinctBy { it.locale.lowercase(Locale.ROOT) }.sortedBy { it.languageDisplayName }
			if (variants.size <= 1) return
			androidx.preference.ListPreference(screen.context).apply {
				key = "language"
				order = 1
				isPersistent = false
				isIconSpaceReserved = false
				entries = variants.map { it.languageDisplayName }.toTypedArray()
				entryValues = variants.map { it.locale }.toTypedArray()
				value = getActiveLanguage(source) ?: variants.first().locale
				summaryProvider =
					androidx.preference.ListPreference.SimpleSummaryProvider
						.getInstance()
				this.title = title
				dialogTitle = title
				setOnPreferenceChangeListener { _, newValue ->
					val language = newValue as? String ?: return@setOnPreferenceChangeListener false
					setActiveLanguage(source, language)
					onChanged()
					true
				}
				screen.addPreference(this)
			}
		}

		private fun merge(vararg lists: List<TachiyomiMangaSource>): List<TachiyomiMangaSource> =
			lists
				.asSequence()
				.flatten()
				.distinctBy { it.sourceId }
				.toList()
	}
