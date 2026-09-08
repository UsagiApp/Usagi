package org.draken.usagi.backups.import.mihon

import org.draken.usagi.backups.data.model.CategoryBackup
import org.draken.usagi.backups.data.model.FavouriteBackup
import org.draken.usagi.backups.data.model.HistoryBackup
import org.draken.usagi.backups.data.model.MangaBackup
import org.draken.usagi.backups.data.model.ScrobblingBackup
import org.draken.usagi.backups.data.model.TagBackup
import org.draken.usagi.backups.import.mihon.model.Backup
import org.draken.usagi.backups.import.mihon.model.BackupCategory
import org.draken.usagi.backups.import.mihon.model.BackupChapter
import org.draken.usagi.backups.import.mihon.model.BackupHistory
import org.draken.usagi.backups.import.mihon.model.BackupManga
import org.draken.usagi.backups.import.mihon.model.BackupTracking
import org.draken.usagi.backups.import.mihon.model.BackupSource

/**
 * Maps Mihon backup models to Usagi backup models.
 * Handles source name normalization and skips Mihon settings entirely.
 */
object MihonBackupMapper {

    /**
     * Map a full Mihon backup to Usagi backup sections.
     * Returns a result containing lists of Usagi backup models.
     */
    fun mapBackup(backup: Backup): MappedBackup {
        val sources = backup.backupSources.associate { it.sourceId to it.name }
        val mangaList = backup.backupManga.map { mapManga(it, sources) }
        val categories = backup.backupCategories.map { mapCategory(it) }
        val sourcesList = backup.backupSources.map { mapSource(it) }

        val historyList = mutableListOf<HistoryBackup>()
        val scrobblingList = mutableListOf<ScrobblingBackup>()
        backup.backupManga.forEach { mihonManga ->
            val mangaBackup = mapManga(mihonManga, sources)
            val mangaId = mangaBackup.url.hashCode().toLong().and(0x7FFFFFFFFFFFFFFF)

            mihonManga.chapters.forEach { chapter ->
                val chapterId = chapter.url.hashCode().toLong().and(0x7FFFFFFFFFFFFFFF)
                mihonManga.history.forEach { history ->
                    if (chapter.url == history.url) {
                        historyList.add(mapHistory(history, mangaId, chapterId))
                    }
                }
            }
            mihonManga.tracking.forEach { tracking ->
                scrobblingList.add(mapTracking(tracking, mangaId))
            }
        }

        return MappedBackup(
            manga = mangaList,
            categories = categories,
            sources = sourcesList,
            history = historyList,
            scrobbling = scrobblingList,
        )
    }

    /**
     * Map a Mihon BackupManga to Usagi MangaBackup.
     * Maps chapters, categories, tracking, and history as nested data.
     */
    fun mapManga(mihonManga: BackupManga, sources: Map<Long, String> = emptyMap()): MangaBackup {
        val sourceName = sources[mihonManga.source] ?: "Unknown"

        return MangaBackup(
            id = 0L, // Let Usagi assign the ID
            title = mihonManga.title,
            altTitles = mihonManga.artist, // Artist as alt title placeholder
            url = mihonManga.url,
            publicUrl = mihonManga.url,
            rating = 0f,
            isNsfw = false,
            contentRating = null,
            coverUrl = mihonManga.thumbnailUrl ?: "",
            largeCoverUrl = null,
            state = mihonManga.status.toString(),
            authors = mihonManga.author,
            source = sourceName,
            tags = mihonManga.genre.map { genre ->
                TagBackup(
                    id = 0L,
                    title = genre,
                    key = genre.lowercase().replace(" ", "_"),
                    source = sourceName,
                )
            }.toSet(),
        )
    }

    /**
     * Map Mihon chapters to Usagi chapter data.
     * Returns a list of chapter-related backup data.
     */
    fun mapChapters(mihonManga: BackupManga): List<MappedChapter> {
        return mihonManga.chapters.map { chapter ->
            MappedChapter(
                url = chapter.url,
                name = chapter.name,
                scanlator = chapter.scanlator,
                read = chapter.read,
                bookmark = chapter.bookmark,
                lastPageRead = chapter.lastPageRead.toInt(),
                dateFetch = chapter.dateFetch,
                dateUpload = chapter.dateUpload,
                chapterNumber = chapter.chapterNumber,
                sourceOrder = chapter.sourceOrder.toInt(),
            )
        }
    }

    /**
     * Map Mihon history to Usagi HistoryBackup.
     * Requires mangaId and chapterId which must be resolved during import.
     */
    fun mapHistory(mihonHistory: BackupHistory, mangaId: Long, chapterId: Long): HistoryBackup {
        return HistoryBackup(
            mangaId = mangaId,
            createdAt = mihonHistory.lastRead,
            updatedAt = mihonHistory.lastRead,
            chapterId = chapterId,
            page = 0,
            scroll = 0f,
            percent = 0f,
            chaptersCount = 0,
            manga = MangaBackup(
                id = mangaId,
                title = "",
                url = mihonHistory.url,
                publicUrl = mihonHistory.url,
                coverUrl = "",
                source = "",
            ),
        )
    }

    /**
     * Map Mihon category to Usagi CategoryBackup.
     */
    fun mapCategory(mihonCategory: BackupCategory): CategoryBackup {
        return CategoryBackup(
            categoryId = mihonCategory.id.toInt(),
            createdAt = System.currentTimeMillis(),
            sortKey = mihonCategory.order.toInt(),
            title = mihonCategory.name,
            order = "NEWEST",
            track = true,
            isVisibleInLibrary = true,
        )
    }

    /**
     * Map Mihon tracking to Usagi ScrobblingBackup.
     * Requires mangaId which must be resolved during import.
     */
    fun mapTracking(mihonTracking: BackupTracking, mangaId: Long): ScrobblingBackup {
        return ScrobblingBackup(
            scrobbler = mihonTracking.syncId,
            id = 0,
            mangaId = mangaId,
            targetId = if (mihonTracking.mediaIdInt != 0) {
                mihonTracking.mediaIdInt.toLong()
            } else {
                mihonTracking.mediaId
            },
            status = mihonTracking.status.toString(),
            chapter = mihonTracking.lastChapterRead.toInt(),
            comment = null,
            rating = mihonTracking.score,
        )
    }

    /**
     * Map Mihon source to Usagi SourceBackup.
     */
    fun mapSource(mihonSource: BackupSource): org.draken.usagi.backups.data.model.SourceBackup {
        return org.draken.usagi.backups.data.model.SourceBackup(
            source = mihonSource.name,
            sortKey = 0,
            lastUsedAt = System.currentTimeMillis(),
            addedIn = 0,
            isPinned = false,
            isEnabled = true,
        )
    }
}

/**
 * Result of mapping a Mihon backup to Usagi models.
 */
data class MappedBackup(
    val manga: List<MangaBackup>,
    val categories: List<CategoryBackup>,
    val sources: List<org.draken.usagi.backups.data.model.SourceBackup>,
    val history: List<HistoryBackup>,
    val scrobbling: List<ScrobblingBackup>,
)

/**
 * Mapped chapter data from Mihon format.
 */
data class MappedChapter(
    val url: String,
    val name: String,
    val scanlator: String?,
    val read: Boolean,
    val bookmark: Boolean,
    val lastPageRead: Int,
    val dateFetch: Long,
    val dateUpload: Long,
    val chapterNumber: Float,
    val sourceOrder: Int,
)
