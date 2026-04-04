package org.draken.usagi.filter.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.Reusable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.draken.usagi.core.model.mangaSourceFromStoredKey
import org.draken.usagi.core.util.ext.observeChanges
import org.draken.usagi.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaSource
import java.io.File
import javax.inject.Inject

@Reusable
class SavedFiltersRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun observeAll(source: MangaSource): Flow<List<PersistableFilter>> = getPrefs(source).observeChanges()
        .onStart { emit(null) }
        .map {
            getAll(source)
        }.distinctUntilChanged()
        .flowOn(Dispatchers.Default)

    suspend fun getAll(source: MangaSource): List<PersistableFilter> = withContext(Dispatchers.Default) {
        val prefs = getPrefs(source)
        val keys = prefs.all.keys.filter { it.startsWith(FILTER_PREFIX) }
        keys.mapNotNull { key ->
            val value = prefs.getString(key, null) ?: return@mapNotNull null
            try {
                Json.decodeFromString(value)
            } catch (e: SerializationException) {
                e.printStackTraceDebug()
                null
            }
        }
    }

    suspend fun save(
        source: MangaSource,
        name: String,
        filter: MangaListFilter,
    ): PersistableFilter = withContext(Dispatchers.Default) {
        val persistableFilter = PersistableFilter(
            name = name,
            source = source,
            filter = filter,
        )
        persist(persistableFilter)
        persistableFilter
    }

    suspend fun save(
        filter: PersistableFilter,
    ) = withContext(Dispatchers.Default) {
        persist(filter)
    }

    suspend fun rename(source: MangaSource, id: Int, newName: String) = withContext(Dispatchers.Default) {
        val filter = load(source, id) ?: return@withContext
        val newFilter = filter.copy(name = newName)
        val prefs = getPrefs(source)
        prefs.edit(commit = true) {
            remove(key(id))
            putString(key(newFilter.id), Json.encodeToString(newFilter))
        }
        newFilter
    }

    suspend fun delete(source: MangaSource, id: Int) = withContext(Dispatchers.Default) {
        val prefs = getPrefs(source)
        prefs.edit(commit = true) {
            remove(key(id))
        }
    }

    suspend fun remapFiltersStorageKey(oldSourceKey: String, newSourceKey: String) = withContext(Dispatchers.Default) {
        if (oldSourceKey == newSourceKey) return@withContext
        val oldSan = oldSourceKey.replace(File.separatorChar, '$')
        val newSan = newSourceKey.replace(File.separatorChar, '$')
        if (oldSan == newSan) return@withContext
        val oldPrefs = context.getSharedPreferences(oldSan, Context.MODE_PRIVATE)
        if (oldPrefs.all.isEmpty()) return@withContext
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
		context.getSharedPreferences(newSan, Context.MODE_PRIVATE).edit {
			for ((k, v) in oldPrefs.all) {
				if (k.startsWith(FILTER_PREFIX) && v is String) {
					val rewritten = runCatching {
						val filter = json.decodeFromString<PersistableFilter>(v)
						json.encodeToString(
							filter.copy(source = mangaSourceFromStoredKey(newSourceKey)),
						)
					}.getOrElse { v }
					putString(k, rewritten)
				} else if (v is String) {
					putString(k, v)
				} else if (v is Boolean) {
					putBoolean(k, v)
				} else if (v is Int) {
					putInt(k, v)
				} else if (v is Long) {
					putLong(k, v)
				} else if (v is Float) {
					putFloat(k, v)
				}
			}
		}
        oldPrefs.edit(commit = true) { clear() }
    }

    private fun persist(persistableFilter: PersistableFilter) {
        val prefs = getPrefs(persistableFilter.source)
        val json = Json.encodeToString(persistableFilter)
        prefs.edit(commit = true) {
            putString(key(persistableFilter.id), json)
        }
    }

    private fun load(source: MangaSource, id: Int): PersistableFilter? {
        val prefs = getPrefs(source)
        val json = prefs.getString(key(id), null) ?: return null
        return try {
            Json.decodeFromString<PersistableFilter>(json)
        } catch (e: SerializationException) {
            e.printStackTraceDebug()
            null
        }
    }

    private fun getPrefs(source: MangaSource): SharedPreferences {
        val key = source.name.replace(File.separatorChar, '$')
        return context.getSharedPreferences(key, Context.MODE_PRIVATE)
    }

    private companion object {

        const val FILTER_PREFIX = "__pf_"

        fun key(id: Int) = FILTER_PREFIX + id
    }
}
