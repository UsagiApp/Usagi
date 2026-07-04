package org.draken.usagi.core.model

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import tsuki.model.MangaSource

object MangaSourceRegistry {

	@Volatile
	var snapshot: SourceSnapshot = SourceSnapshot.EMPTY
		private set

	val sources: List<MangaSource>
		get() = snapshot.sources

	val version: Int
		get() = snapshot.version

	val entries: List<MangaSource>
		get() = snapshot.sources

	val updates = MutableSharedFlow<Unit>(
		replay = 1,
		onBufferOverflow = BufferOverflow.DROP_OLDEST,
	)

	fun publish(newSources: List<MangaSource>) {
		val name = HashMap<String, MangaSource>(newSources.size * 2)
		val shortName = HashMap<String, MangaSource>(newSources.size)
		for (source in newSources) {
			name.putIfAbsent(source.name, source)
			if (source is PluginMangaSource) {
				shortName.putIfAbsent(source.sourceName, source)
			}
		}
		snapshot = SourceSnapshot(
			sources = newSources,
			version = snapshot.version + 1,
			byName = name,
			byShortName = shortName,
		)
		updates.tryEmit(Unit)
	}

	fun resolveByName(name: String): MangaSource? {
		val snap = snapshot
		return snap.byName[name] ?: snap.byShortName[name]
	}
}
