package org.draken.usagi.favourites.data

import android.database.DatabaseUtils.sqlEscapeString
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import org.draken.usagi.core.db.MangaQueryBuilder
import org.draken.usagi.core.db.TABLE_FAVOURITES
import org.draken.usagi.core.db.entity.MangaEntity
import org.draken.usagi.core.db.entity.MangaWithTags
import org.draken.usagi.favourites.domain.FavouriteScope
import org.draken.usagi.favourites.domain.FavouriteStage
import org.draken.usagi.favourites.domain.SmartFolderContent
import org.draken.usagi.favourites.domain.SmartFolderDevice
import org.draken.usagi.favourites.domain.SmartFolderRules
import org.draken.usagi.favourites.domain.model.Cover
import org.draken.usagi.history.data.HistoryEntity
import org.draken.usagi.list.domain.ListFilterOption
import org.draken.usagi.list.domain.ListSortOrder
import org.draken.usagi.list.domain.ReadingProgress.Companion.COMPLETION_THRESHOLD
import org.draken.usagi.local.data.index.LocalMangaIndexEntity
import org.draken.usagi.tracker.data.TrackEntity
import org.intellij.lang.annotations.Language

@Dao
abstract class FavouritesDao : MangaQueryBuilder.ConditionCallback {
	/** SELECT **/

	@Transaction
	@Query("SELECT * FROM favourites WHERE category_id = 0 AND deleted_at = 0 ORDER BY created_at DESC")
	abstract suspend fun findAll(): List<FavouriteManga>

	@Transaction
	@Query("SELECT * FROM favourites WHERE category_id = 0 AND deleted_at = 0 ORDER BY created_at DESC LIMIT :limit")
	abstract suspend fun findLast(limit: Int): List<FavouriteManga>

	@Transaction
	@Query("SELECT manga.* FROM favourites LEFT JOIN manga ON manga.manga_id = favourites.manga_id WHERE favourites.category_id = 0 AND favourites.deleted_at = 0 AND (manga.title LIKE :query OR manga.alt_title LIKE :query) LIMIT :limit")
	abstract suspend fun searchByTitle(
		query: String,
		limit: Int,
	): List<MangaWithTags>

	@Transaction
	@Query("SELECT manga.* FROM favourites LEFT JOIN manga ON manga.manga_id = favourites.manga_id WHERE favourites.category_id = 0 AND favourites.deleted_at = 0 AND (manga.author LIKE :query) LIMIT :limit")
	abstract suspend fun searchByAuthor(
		query: String,
		limit: Int,
	): List<MangaWithTags>

	@Transaction
	@Query("SELECT manga.* FROM favourites LEFT JOIN manga ON manga.manga_id = favourites.manga_id WHERE favourites.category_id = 0 AND favourites.deleted_at = 0 AND EXISTS(SELECT 1 FROM tags LEFT JOIN manga_tags ON manga_tags.tag_id = tags.tag_id WHERE manga_tags.manga_id = manga.manga_id AND tags.title LIKE :query) LIMIT :limit")
	abstract suspend fun searchByTag(
		query: String,
		limit: Int,
	): List<MangaWithTags>

	fun observeAll(
		order: ListSortOrder,
		filterOptions: Set<ListFilterOption>,
		limit: Int,
	): Flow<List<FavouriteManga>> = observeAll(0L, order, filterOptions, limit)

	@Transaction
	@Query("SELECT * FROM favourites WHERE category_id != 0 AND deleted_at = 0 ORDER BY created_at DESC LIMIT :limit OFFSET :offset")
	abstract suspend fun findAllRaw(
		offset: Int,
		limit: Int,
	): List<FavouriteManga>

	@Query("SELECT DISTINCT manga_id FROM favourites WHERE deleted_at = 0 AND category_id IN (SELECT category_id FROM favourite_categories WHERE track = 1 AND deleted_at = 0)")
	abstract suspend fun findIdsWithTrack(): LongArray

	@Transaction
	@Query(
		"SELECT * FROM favourites WHERE category_id = :categoryId AND deleted_at = 0 " +
			"GROUP BY manga_id ORDER BY created_at DESC",
	)
	abstract suspend fun findAll(categoryId: Long): List<FavouriteManga>

	@Query("SELECT manga_id FROM favourites WHERE category_id = :categoryId AND deleted_at = 0 ORDER BY sort_key ASC, created_at DESC")
	abstract suspend fun findAllIds(categoryId: Long): LongArray

	fun observeAll(
		categoryId: Long,
		order: ListSortOrder,
		filterOptions: Set<ListFilterOption>,
		limit: Int,
	): Flow<List<FavouriteManga>> =
		observeAllImpl(
			MangaQueryBuilder(TABLE_FAVOURITES, this)
				.join("LEFT JOIN manga ON favourites.manga_id = manga.manga_id")
				.where("deleted_at = 0")
				.where(
					if (categoryId != ALL_FAVORITES_CATEGORY_ID) {
						"category_id = $categoryId"
					} else {
						"favourites.category_id = $ALL_FAVORITES_CATEGORY_ID"
					},
				).filters(filterOptions)
				.groupBy("favourites.manga_id")
				.orderBy(getOrderBy(order))
				.limit(limit)
				.build(),
		)

	fun observeAll(
		scope: FavouriteScope,
		stage: FavouriteStage,
		rules: SmartFolderRules?,
		order: ListSortOrder,
		filterOptions: Set<ListFilterOption>,
		limit: Int,
	): Flow<List<FavouriteManga>> =
		observeAllImpl(
			MangaQueryBuilder(TABLE_FAVOURITES, this)
				.join("LEFT JOIN manga ON favourites.manga_id = manga.manga_id")
				.where("favourites.deleted_at = 0")
				.where(getScopeCondition(scope, rules))
				.where(getStageCondition(stage))
				.filters(filterOptions)
				.groupBy("favourites.manga_id")
				.orderBy(getOrderBy(order))
				.limit(limit)
				.build(),
		)

	fun observeStageCounts(
		scope: FavouriteScope,
		rules: SmartFolderRules?,
	): Flow<FavouriteStageCounts> {
		val scopeCondition = getScopeCondition(scope, rules)
		val countFields =
			LifecycleFields(
				hasHistory = "history_percent IS NOT NULL",
				percent = "history_percent",
				newChapters = "new_chapters",
				state = "state",
			)
		val notStartedCondition = getStageCondition(FavouriteStage.NOT_STARTED, countFields)
		val readingCondition = getStageCondition(FavouriteStage.READING, countFields)
		val waitingCondition = getStageCondition(FavouriteStage.WAITING, countFields)
		val completedCondition = getStageCondition(FavouriteStage.COMPLETED, countFields)
		val needsReviewCondition = getStageCondition(FavouriteStage.NEEDS_REVIEW, countFields)
		val query =
			SimpleSQLiteQuery(
				"""SELECT
					COUNT(*) AS all_count,
					COALESCE(SUM(CASE WHEN $notStartedCondition THEN 1 ELSE 0 END), 0) AS not_started_count,
					COALESCE(SUM(CASE WHEN $readingCondition THEN 1 ELSE 0 END), 0) AS reading_count,
					COALESCE(SUM(CASE WHEN $waitingCondition THEN 1 ELSE 0 END), 0) AS waiting_count,
					COALESCE(SUM(CASE WHEN $completedCondition THEN 1 ELSE 0 END), 0) AS completed_count,
					COALESCE(SUM(CASE WHEN $needsReviewCondition THEN 1 ELSE 0 END), 0) AS needs_review_count
				FROM (
					SELECT manga.manga_id,
						manga.state AS state,
						(SELECT percent FROM history WHERE history.manga_id = favourites.manga_id AND history.deleted_at = 0 LIMIT 1) AS history_percent,
						COALESCE((SELECT chapters_new FROM tracks WHERE tracks.manga_id = favourites.manga_id LIMIT 1), 0) AS new_chapters
					FROM favourites
					LEFT JOIN manga ON favourites.manga_id = manga.manga_id
					WHERE favourites.deleted_at = 0 AND $scopeCondition
					GROUP BY favourites.manga_id
				)""",
			)
		return observeStageCountsImpl(query)
	}

	suspend fun findCovers(
		categoryId: Long,
		order: ListSortOrder,
	): List<Cover> {
		val orderBy = getOrderBy(order)

		@Language("RoomSql")
		val query =
			SimpleSQLiteQuery(
				"SELECT manga.cover_url AS url, manga.source AS source FROM favourites " +
					"LEFT JOIN manga ON favourites.manga_id = manga.manga_id " +
					"WHERE favourites.category_id = ? AND deleted_at = 0 ORDER BY $orderBy",
				arrayOf<Any>(categoryId),
			)
		return findCoversImpl(query)
	}

	suspend fun findCovers(
		order: ListSortOrder,
		limit: Int,
	): List<Cover> {
		val orderBy = getOrderBy(order)

		@Language("RoomSql")
		val query =
			SimpleSQLiteQuery(
				"SELECT manga.cover_url AS url, manga.source AS source FROM favourites " +
					"LEFT JOIN manga ON favourites.manga_id = manga.manga_id " +
					"WHERE favourites.category_id = $ALL_FAVORITES_CATEGORY_ID AND deleted_at = 0 " +
					"ORDER BY $orderBy LIMIT ?",
				arrayOf<Any>(limit),
			)
		return findCoversImpl(query)
	}

	@Query("SELECT COUNT(*) FROM favourites WHERE category_id = 0 AND deleted_at = 0")
	abstract fun observeMangaCount(): Flow<Int>

	@Query("SELECT * FROM favourites WHERE manga_id = :mangaId AND deleted_at = 0")
	abstract suspend fun findAllRaw(mangaId: Long): List<FavouriteEntity>

	@Query("SELECT DISTINCT category_id FROM favourites WHERE manga_id = :id AND category_id != 0 AND deleted_at = 0")
	abstract fun observeIds(id: Long): Flow<List<Long>>

	@Query("SELECT favourite_categories.* FROM favourites LEFT JOIN favourite_categories ON favourite_categories.category_id = favourites.category_id WHERE favourites.manga_id = :mangaId AND favourites.category_id != 0 AND favourites.deleted_at = 0")
	abstract fun observeCategories(mangaId: Long): Flow<List<FavouriteCategoryEntity>>

	@Query("SELECT DISTINCT category_id FROM favourites WHERE manga_id = :mangaId AND category_id != 0 AND deleted_at = 0 ORDER BY favourites.created_at ASC")
	abstract suspend fun findCategoriesIds(mangaId: Long): List<Long>

	@Query("SELECT COUNT(*) FROM favourites WHERE manga_id = :mangaId AND category_id = 0 AND deleted_at = 0")
	abstract suspend fun findCategoriesCount(mangaId: Long): Int

	@Query("SELECT EXISTS(SELECT 1 FROM favourites WHERE manga_id = :mangaId AND category_id = 0 AND deleted_at = 0)")
	abstract fun observeIsFavorite(mangaId: Long): Flow<Boolean>

	@Query("SELECT manga.source AS count FROM favourites LEFT JOIN manga ON manga.manga_id = favourites.manga_id WHERE favourites.category_id = 0 AND favourites.deleted_at = 0 GROUP BY manga.source ORDER BY COUNT(manga.source) DESC LIMIT :limit")
	abstract suspend fun findPopularSources(limit: Int): List<String>

	@Query("SELECT manga.source AS count FROM favourites LEFT JOIN manga ON manga.manga_id = favourites.manga_id WHERE favourites.category_id = :categoryId AND favourites.deleted_at = 0 GROUP BY manga.source ORDER BY COUNT(manga.source) DESC LIMIT :limit")
	abstract suspend fun findPopularSources(
		categoryId: Long,
		limit: Int,
	): List<String>

	fun dump(): Flow<FavouriteManga> =
		flow {
			val window = 10
			var offset = 0
			while (currentCoroutineContext().isActive) {
				val list = findAllRaw(offset, window)
				if (list.isEmpty()) {
					break
				}
				offset += window
				list.forEach { emit(it) }
			}
		}

	/** INSERT **/

	@Insert(onConflict = OnConflictStrategy.REPLACE)
	abstract suspend fun insert(favourite: FavouriteEntity)

	/** DELETE **/

	suspend fun delete(mangaId: Long) =
		setDeletedAt(
			mangaId = mangaId,
			deletedAt = System.currentTimeMillis(),
		)

	suspend fun delete(
		mangaId: Long,
		categoryId: Long,
	) = setDeletedAt(
		categoryId = categoryId,
		mangaId = mangaId,
		deletedAt = System.currentTimeMillis(),
	)

	suspend fun deleteAll(categoryId: Long) =
		setDeletedAtAll(
			categoryId = categoryId,
			deletedAt = System.currentTimeMillis(),
		)

	suspend fun recover(mangaId: Long) =
		setDeletedAt(
			mangaId = mangaId,
			deletedAt = 0L,
		)

	suspend fun recover(
		categoryId: Long,
		mangaId: Long,
	) = setDeletedAt(
		categoryId = categoryId,
		mangaId = mangaId,
		deletedAt = 0L,
	)

	suspend fun recoverAllFavorites(mangaIds: Collection<Long>) {
		mangaIds.forEach { mangaId ->
			setDeletedAt(ALL_FAVORITES_CATEGORY_ID, mangaId, 0L)
		}
	}

	@Query("DELETE FROM favourites WHERE deleted_at != 0 AND deleted_at < :maxDeletionTime")
	abstract suspend fun gc(maxDeletionTime: Long)

	/** TOOLS **/

	@Upsert
	abstract suspend fun upsert(entity: FavouriteEntity)

	@Transaction
	@RawQuery(
		observedEntities = [
			FavouriteEntity::class,
			FavouriteCategoryEntity::class,
			MangaEntity::class,
			HistoryEntity::class,
			TrackEntity::class,
			LocalMangaIndexEntity::class,
		],
	)
	protected abstract fun observeAllImpl(query: SupportSQLiteQuery): Flow<List<FavouriteManga>>

	@RawQuery(
		observedEntities = [
			FavouriteEntity::class,
			FavouriteCategoryEntity::class,
			MangaEntity::class,
			HistoryEntity::class,
			TrackEntity::class,
			LocalMangaIndexEntity::class,
		],
	)
	protected abstract fun observeStageCountsImpl(query: SupportSQLiteQuery): Flow<FavouriteStageCounts>

	@RawQuery
	protected abstract suspend fun findCoversImpl(query: SupportSQLiteQuery): List<Cover>

	@Query("UPDATE favourites SET deleted_at = :deletedAt WHERE manga_id = :mangaId")
	protected abstract suspend fun setDeletedAt(
		mangaId: Long,
		deletedAt: Long,
	)

	@Query("UPDATE favourites SET deleted_at = :deletedAt WHERE manga_id = :mangaId AND category_id = :categoryId")
	protected abstract suspend fun setDeletedAt(
		categoryId: Long,
		mangaId: Long,
		deletedAt: Long,
	)

	@Query("UPDATE favourites SET deleted_at = :deletedAt WHERE category_id = :categoryId AND deleted_at = 0")
	protected abstract suspend fun setDeletedAtAll(
		categoryId: Long,
		deletedAt: Long,
	)

	@Query("UPDATE favourites SET sort_key = :sortKey WHERE category_id = :categoryId AND manga_id = :mangaId")
	abstract suspend fun setSortKey(
		categoryId: Long,
		mangaId: Long,
		sortKey: Int,
	)

	private fun getOrderBy(sortOrder: ListSortOrder) =
		when (sortOrder) {
			ListSortOrder.RATING -> "manga.rating DESC"
			ListSortOrder.NEWEST -> "favourites.sort_key ASC, favourites.created_at DESC"
			ListSortOrder.OLDEST -> "favourites.created_at ASC"
			ListSortOrder.ALPHABETIC -> "manga.title ASC"
			ListSortOrder.ALPHABETIC_REVERSE -> "manga.title DESC"
			ListSortOrder.NEW_CHAPTERS -> "IFNULL((SELECT chapters_new FROM tracks WHERE tracks.manga_id = manga.manga_id), 0) DESC"
			ListSortOrder.PROGRESS -> "IFNULL((SELECT percent FROM history WHERE history.manga_id = manga.manga_id), 0) DESC"
			ListSortOrder.UNREAD -> "IFNULL((SELECT percent FROM history WHERE history.manga_id = manga.manga_id), 0) ASC"
			ListSortOrder.LAST_READ -> "IFNULL((SELECT updated_at FROM history WHERE history.manga_id = manga.manga_id), 0) DESC"
			ListSortOrder.LONG_AGO_READ -> "IFNULL((SELECT updated_at FROM history WHERE history.manga_id = manga.manga_id), 0) ASC"
			ListSortOrder.UPDATED -> "IFNULL((SELECT last_chapter_date FROM tracks WHERE tracks.manga_id = manga.manga_id), 0) DESC"
			else -> throw IllegalArgumentException("Sort order $sortOrder is not supported")
		}

	private fun getScopeCondition(
		scope: FavouriteScope,
		rules: SmartFolderRules?,
	): String =
		when (scope) {
			FavouriteScope.All -> {
				"favourites.category_id = $ALL_FAVORITES_CATEGORY_ID"
			}

			is FavouriteScope.Category -> {
				"favourites.category_id = ${scope.id} AND EXISTS(SELECT 1 FROM favourite_categories WHERE favourite_categories.category_id = ${scope.id} AND favourite_categories.deleted_at = 0)"
			}

			is FavouriteScope.SmartFolder -> {
				rules?.let { "favourites.category_id = $ALL_FAVORITES_CATEGORY_ID AND ${it.toSqlCondition()}" } ?: "0"
			}
		}

	private fun SmartFolderRules.toSqlCondition(): String {
		val conditions = ArrayList<String>(5)
		if (sources.isNotEmpty()) {
			conditions += "manga.source IN (${sources.joinToString { sqlEscapeString(it) }})"
		}
		if (categoryIds.isNotEmpty()) {
			val ids = categoryIds.joinToString()
			conditions +=
				"((SELECT COUNT(*) FROM favourite_categories WHERE category_id IN ($ids) AND deleted_at = 0) = ${categoryIds.size} " +
				"AND EXISTS(SELECT 1 FROM favourites AS category_favourite WHERE category_favourite.manga_id = favourites.manga_id " +
				"AND category_favourite.category_id IN ($ids) AND category_favourite.deleted_at = 0))"
		}
		if (tagIds.isNotEmpty()) {
			val ids = tagIds.joinToString()
			conditions +=
				"EXISTS(SELECT 1 FROM manga_tags WHERE manga_tags.manga_id = favourites.manga_id AND manga_tags.tag_id IN ($ids))"
		}
		when (content) {
			SmartFolderContent.ANY -> Unit
			SmartFolderContent.SFW -> conditions += "manga.nsfw = 0"
			SmartFolderContent.NSFW -> conditions += "manga.nsfw = 1"
		}
		when (device) {
			SmartFolderDevice.ANY -> Unit
			SmartFolderDevice.ON_DEVICE -> conditions += "EXISTS(SELECT 1 FROM local_index WHERE local_index.manga_id = favourites.manga_id)"
			SmartFolderDevice.NOT_ON_DEVICE -> conditions += "NOT EXISTS(SELECT 1 FROM local_index WHERE local_index.manga_id = favourites.manga_id)"
		}
		return conditions.joinToString(separator = " AND ").ifEmpty { "0" }
	}

	private fun getStageCondition(stage: FavouriteStage): String =
		getStageCondition(
			stage = stage,
			fields =
				LifecycleFields(
					hasHistory = "EXISTS(SELECT 1 FROM history WHERE history.manga_id = favourites.manga_id AND history.deleted_at = 0)",
					percent = "(SELECT percent FROM history WHERE history.manga_id = favourites.manga_id AND history.deleted_at = 0 LIMIT 1)",
					newChapters = "COALESCE((SELECT chapters_new FROM tracks WHERE tracks.manga_id = favourites.manga_id LIMIT 1), 0)",
					state = "manga.state",
				),
		)

	private fun getStageCondition(
		stage: FavouriteStage,
		fields: LifecycleFields,
	): String =
		with(fields) {
			when (stage) {
				FavouriteStage.ALL -> "1"
				FavouriteStage.NOT_STARTED -> "NOT ($hasHistory)"
				FavouriteStage.READING -> "$hasHistory AND ($newChapters > 0 OR $percent < $COMPLETION_THRESHOLD)"
				FavouriteStage.COMPLETED -> "$hasHistory AND $newChapters <= 0 AND $percent >= $COMPLETION_THRESHOLD AND $state = 'FINISHED'"
				FavouriteStage.WAITING -> "$hasHistory AND $newChapters <= 0 AND $percent >= $COMPLETION_THRESHOLD AND $state IN ('ONGOING', 'PAUSED', 'UPCOMING')"
				FavouriteStage.NEEDS_REVIEW -> "$hasHistory AND $newChapters <= 0 AND $percent >= $COMPLETION_THRESHOLD AND ($state IS NULL OR $state NOT IN ('FINISHED', 'ONGOING', 'PAUSED', 'UPCOMING'))"
			}
		}

	private data class LifecycleFields(
		val hasHistory: String,
		val percent: String,
		val newChapters: String,
		val state: String,
	)

	override fun getCondition(option: ListFilterOption): String? =
		when (option) {
			ListFilterOption.Macro.COMPLETED -> "EXISTS(SELECT * FROM history WHERE history.manga_id = favourites.manga_id AND history.percent >= $COMPLETION_THRESHOLD)"
			ListFilterOption.Macro.NEW_CHAPTERS -> "(SELECT chapters_new FROM tracks WHERE tracks.manga_id = favourites.manga_id) > 0"
			ListFilterOption.Macro.NSFW -> "manga.nsfw = 1"
			is ListFilterOption.Tag -> "EXISTS(SELECT * FROM manga_tags WHERE favourites.manga_id = manga_tags.manga_id AND tag_id = ${option.tagId})"
			ListFilterOption.Downloaded -> "EXISTS(SELECT * FROM local_index WHERE local_index.manga_id = favourites.manga_id)"
			is ListFilterOption.Source -> "manga.source = ${sqlEscapeString(option.mangaSource.name)}"
			else -> null
		}
}
