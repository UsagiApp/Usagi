package org.draken.usagi.details.data

import org.draken.usagi.core.model.getLocale
import org.draken.usagi.core.model.isLocal
import org.draken.usagi.core.model.isTachiyomiExtensionSource
import org.draken.usagi.core.model.withOverride
import org.draken.usagi.core.ui.model.MangaOverride
import org.draken.usagi.local.domain.model.LocalManga
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.util.ifNullOrEmpty
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.draken.usagi.reader.data.filterChapters
import java.util.Locale

data class MangaDetails(
    private val manga: Manga,
    private val localManga: LocalManga?,
    private val override: MangaOverride?,
    val description: CharSequence?,
    val isLoaded: Boolean,
) {

    constructor(manga: Manga) : this(
        manga = manga,
        localManga = null,
        override = null,
        description = null,
        isLoaded = false,
    )

    val id: Long
        get() = manga.id

    val allChapters: List<MangaChapter> by lazy { mergeChapters() }

    val chapters: Map<String?, List<MangaChapter>> by lazy {
        allChapters.groupBy { it.branch }
    }

    val isLocal
        get() = manga.isLocal

    val local: LocalManga?
        get() = localManga ?: if (manga.isLocal) LocalManga(manga) else null

    val backdropUrl: String?
        get() = manga.largeCoverUrl
            .ifNullOrEmpty { override?.coverUrl }
            .ifNullOrEmpty { manga.coverUrl }
            .ifNullOrEmpty { localManga?.manga?.coverUrl }
            ?.nullIfEmpty()

    val isRestricted: Boolean
        get() = manga.state == MangaState.RESTRICTED

    private val mergedManga by lazy {
        if (localManga == null) {
            // fast path
            manga.withOverride(override)
        } else {
            manga.copy(
                title = override?.title.ifNullOrEmpty { manga.title },
                coverUrl = override?.coverUrl.ifNullOrEmpty { manga.coverUrl },
                largeCoverUrl = override?.coverUrl.ifNullOrEmpty { manga.largeCoverUrl },
                contentRating = override?.contentRating ?: manga.contentRating,
                chapters = allChapters,
            )
        }
    }

    fun toManga() = mergedManga

	fun coverUrl(preferLarge: Boolean = false): String? =
		override?.coverUrl
			.ifNullOrEmpty { if (preferLarge) manga.largeCoverUrl else null }
			.ifNullOrEmpty { manga.coverUrl }
			.ifNullOrEmpty { localManga?.manga?.coverUrl }
			?.nullIfEmpty()

    fun getLocale(): Locale? {
        findAppropriateLocale(chapters.keys.singleOrNull())?.let {
            return it
        }
        return manga.source.getLocale()
    }

    fun filterChapters(branch: String?) = copy(
        manga = manga.filterChapters(branch),
        localManga = localManga?.run {
            copy(manga = manga.filterChapters(branch))
        },
    )

    private fun mergeChapters(): List<MangaChapter> {
        val chapters = manga.chapters
        val localChapters = local?.manga?.chapters.orEmpty()
        if (chapters.isNullOrEmpty()) {
            return localChapters
        }
        val localMap = if (localChapters.isNotEmpty()) {
            localChapters.associateByTo(LinkedHashMap(localChapters.size)) { it.id }
        } else {
            null
        }
        val result = ArrayList<MangaChapter>(chapters.size)
        for (chapter in chapters) {
            val local = localMap?.remove(chapter.id)
            result += local ?: chapter
        }
        if (!localMap.isNullOrEmpty()) {
            result.addAll(localMap.values)
        }
        return normalizeTachiyomiChapterOrder(result)
    }

    private fun normalizeTachiyomiChapterOrder(chapters: List<MangaChapter>): List<MangaChapter> {
        if (!manga.source.isTachiyomiExtensionSource() || chapters.size < 2) {
            return chapters
        }
        val grouped = LinkedHashMap<String?, MutableList<MangaChapter>>()
        for (chapter in chapters) {
            grouped.getOrPut(chapter.branch) { ArrayList() } += chapter
        }
        var hasChanges = false
        val normalized = ArrayList<MangaChapter>(chapters.size)
        for (items in grouped.values) {
            if (detectChapterOrder(items) == SortDirection.DESCENDING) {
                normalized.addAll(items.asReversed())
                hasChanges = true
            } else {
                normalized.addAll(items)
            }
        }
        return if (hasChanges) normalized else chapters
    }

    private fun detectChapterOrder(chapters: List<MangaChapter>): SortDirection {
        val byNumber = detectChapterNumberOrder(chapters)
        if (byNumber != SortDirection.UNKNOWN) return byNumber
        return detectChapterDateOrder(chapters)
    }

    private fun detectChapterNumberOrder(chapters: List<MangaChapter>): SortDirection {
        var asc = 0
        var desc = 0
        var previous: Float? = null
        for (chapter in chapters) {
            val number = chapter.number
                .takeIf { it > 0f }
                ?: parseChapterNumber(chapter.title).takeIf { it > 0f }
                ?: continue
            val prev = previous
            if (prev != null) {
                when {
                    number > prev + CHAPTER_NUMBER_EPSILON -> asc++
                    number < prev - CHAPTER_NUMBER_EPSILON -> desc++
                }
            }
            previous = number
        }
        return resolveOrder(asc, desc)
    }

    private fun detectChapterDateOrder(chapters: List<MangaChapter>): SortDirection {
        var asc = 0
        var desc = 0
        var previous: Long? = null
        for (chapter in chapters) {
            val date = chapter.uploadDate.takeIf { it > 0L } ?: continue
            val prev = previous
            if (prev != null) {
                when {
                    date > prev -> asc++
                    date < prev -> desc++
                }
            }
            previous = date
        }
        return resolveOrder(asc, desc)
    }

    private fun parseChapterNumber(title: String?): Float {
        val value = CHAPTER_NUMBER_REGEX.find(title.orEmpty())
            ?.groupValues
            ?.getOrNull(1)
        return value?.toFloatOrNull() ?: 0f
    }

    private fun resolveOrder(asc: Int, desc: Int): SortDirection {
        val comparisons = asc + desc
        if (comparisons < MIN_ORDER_COMPARISONS) {
            return SortDirection.UNKNOWN
        }
        val ascValue = asc.toFloat()
        val descValue = desc.toFloat()
        return when {
            ascValue >= (descValue * ORDER_DOMINANCE_FACTOR) -> SortDirection.ASCENDING
            descValue >= (ascValue * ORDER_DOMINANCE_FACTOR) -> SortDirection.DESCENDING
            else -> SortDirection.UNKNOWN
        }
    }

    private fun findAppropriateLocale(name: String?): Locale? {
        if (name.isNullOrEmpty()) {
            return null
        }
        return Locale.getAvailableLocales().find { lc ->
            name.contains(lc.getDisplayName(lc), ignoreCase = true) ||
                name.contains(lc.getDisplayName(Locale.ENGLISH), ignoreCase = true) ||
                name.contains(lc.getDisplayLanguage(lc), ignoreCase = true) ||
                name.contains(lc.getDisplayLanguage(Locale.ENGLISH), ignoreCase = true)
        }
    }

    private enum class SortDirection {
        ASCENDING,
        DESCENDING,
        UNKNOWN,
    }

    private companion object {
        private val CHAPTER_NUMBER_REGEX = Regex("""(?i)(?:ch(?:apter)?|ep(?:isode)?|#)?\s*(\d+(?:\.\d+)?)""")
        private const val MIN_ORDER_COMPARISONS = 3
        private const val ORDER_DOMINANCE_FACTOR = 1.2f
        private const val CHAPTER_NUMBER_EPSILON = 0.0001f
    }
}
