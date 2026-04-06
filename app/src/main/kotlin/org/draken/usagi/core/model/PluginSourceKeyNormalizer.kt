package org.draken.usagi.core.model

import androidx.room.withTransaction
import org.draken.usagi.core.db.MangaDatabase
import org.draken.usagi.filter.data.SavedFiltersRepository

object PluginSourceKeyNormalizer {

	fun uniqueLegacyShortToCompound(): Map<String, String> {
		if (MangaSourceRegistry.sources.isEmpty()) return emptyMap()
		val plugins = MangaSourceRegistry.sources.filterIsInstance<PluginMangaSource>()
		val byShort = plugins.groupBy { it.sourceName }
		val out = LinkedHashMap<String, String>()
		for ((short, list) in byShort) {
			if (list.size != 1) continue
			if (':' in short) continue
			val compound = list.single().name
			if (compound != short) out[short] = compound
		}
		return out
	}

	suspend fun normalize(database: MangaDatabase, savedFiltersRepository: SavedFiltersRepository) {
		val map = uniqueLegacyShortToCompound()
		if (map.isEmpty()) return
		database.withTransaction {
			for ((short, compound) in map) {
				database.getSourcesDao().mergeLegacyPluginSourceKeys(short, compound)
				database.getMangaDao().rewriteStoredSourceKey(short, compound)
				database.getChaptersDao().rewriteStoredSourceKey(short, compound)
			}
		}
		for ((short, compound) in map) {
			savedFiltersRepository.remapFiltersStorageKey(short, compound)
		}
	}
}
