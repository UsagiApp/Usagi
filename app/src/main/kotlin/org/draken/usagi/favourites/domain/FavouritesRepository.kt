package org.draken.usagi.favourites.domain

import androidx.room.withTransaction
import dagger.Reusable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import org.draken.usagi.core.db.MangaDatabase
import org.draken.usagi.core.db.TABLE_FAVOURITES
import org.draken.usagi.core.db.TABLE_FAVOURITE_CATEGORIES
import org.draken.usagi.core.db.entity.toEntities
import org.draken.usagi.core.db.entity.toEntity
import org.draken.usagi.core.db.entity.toMangaList
import org.draken.usagi.core.db.entity.toMangaTagsList
import org.draken.usagi.core.model.FavouriteCategory
import org.draken.usagi.core.model.toMangaSources
import org.draken.usagi.core.ui.util.ReversibleHandle
import org.draken.usagi.core.util.ext.mapItems
import org.draken.usagi.favourites.data.ALL_FAVORITES_CATEGORY_ID
import org.draken.usagi.favourites.data.FavouriteCategoryEntity
import org.draken.usagi.favourites.data.FavouriteEntity
import org.draken.usagi.favourites.data.FavouriteStageCounts
import org.draken.usagi.favourites.data.toFavouriteCategory
import org.draken.usagi.favourites.data.toMangaList
import org.draken.usagi.favourites.domain.model.Cover
import org.draken.usagi.list.domain.ListFilterOption
import org.draken.usagi.list.domain.ListSortOrder
import org.draken.usagi.search.domain.SearchKind
import tsuki.model.Manga
import tsuki.model.MangaSource
import tsuki.model.MangaTag
import tsuki.util.levenshteinDistance
import javax.inject.Inject

@Reusable
class FavouritesRepository
	@Inject
	constructor(
		private val db: MangaDatabase,
		private val localObserver: LocalFavoritesObserver,
		private val smartFoldersRepository: SmartFoldersRepository,
	) {
		suspend fun getAllManga(): List<Manga> {
			val entities = db.getFavouritesDao().findAll()
			return entities.toMangaList()
		}

		suspend fun getLastManga(limit: Int): List<Manga> {
			val entities = db.getFavouritesDao().findLast(limit)
			return entities.toMangaList()
		}

		suspend fun search(
			query: String,
			kind: SearchKind,
			limit: Int,
		): List<Manga> {
			val dao = db.getFavouritesDao()
			val q = "%$query%"
			val entities =
				when (kind) {
					SearchKind.SIMPLE,
					SearchKind.TITLE,
					-> dao.searchByTitle(q, limit).sortedBy { it.manga.title.levenshteinDistance(query) }

					SearchKind.AUTHOR -> dao.searchByAuthor(q, limit)

					SearchKind.TAG -> dao.searchByTag(q, limit)
				}
			return entities.toMangaList()
		}

		fun observeAll(
			order: ListSortOrder,
			filterOptions: Set<ListFilterOption>,
			limit: Int,
		): Flow<List<Manga>> {
			if (ListFilterOption.Downloaded in filterOptions) {
				return localObserver.observeAll(order, filterOptions, limit)
			}
			return db
				.getFavouritesDao()
				.observeAll(order, filterOptions, limit)
				.map { it.toMangaList() }
		}

		suspend fun getManga(categoryId: Long): List<Manga> {
			val entities = db.getFavouritesDao().findAll(categoryId)
			return entities.toMangaList()
		}

		fun observeAll(
			categoryId: Long,
			order: ListSortOrder,
			filterOptions: Set<ListFilterOption>,
			limit: Int,
		): Flow<List<Manga>> {
			if (ListFilterOption.Downloaded in filterOptions) {
				return localObserver.observeAll(categoryId, order, filterOptions, limit)
			}
			return db
				.getFavouritesDao()
				.observeAll(categoryId, order, filterOptions, limit)
				.map { it.toMangaList() }
		}

		fun observeAll(
			scope: FavouriteScope,
			stage: FavouriteStage,
			order: ListSortOrder,
			filterOptions: Set<ListFilterOption>,
			limit: Int,
		): Flow<List<Manga>> =
			observeRules(scope).flatMapLatest { rules ->
				if (ListFilterOption.Downloaded in filterOptions || rules?.device == SmartFolderDevice.ON_DEVICE) {
					localObserver.observeAll(scope, stage, rules, order, filterOptions, limit)
				} else {
					db
						.getFavouritesDao()
						.observeAll(scope, stage, rules, order, filterOptions, limit)
						.map { it.toMangaList() }
				}
			}

		fun observeStageCounts(scope: FavouriteScope): Flow<FavouriteStageCounts> =
			observeRules(scope).flatMapLatest { rules ->
				db.getFavouritesDao().observeStageCounts(scope, rules)
			}

		suspend fun getOrganizerRefreshCandidates(
			scope: FavouriteScope,
			limit: Int,
		): List<Manga> {
			val result = LinkedHashMap<Long, Manga>(limit)
			for (stage in FavouriteStage.entries.filter(FavouriteStage::requiresSourceRefresh)) {
				val remaining = limit - result.size
				if (remaining <= 0) break
				observeAll(
					scope = scope,
					stage = stage,
					order = ListSortOrder.NEWEST,
					filterOptions = emptySet(),
					limit = remaining,
				).first().forEach { manga -> result.putIfAbsent(manga.id, manga) }
			}
			return result.values.toList()
		}

		fun observeSmartFolder(id: Long): Flow<SmartFolder?> = smartFoldersRepository.observe(id)

		suspend fun setSmartFolderOrder(
			id: Long,
			order: ListSortOrder,
		) = smartFoldersRepository.setListOrder(id, order)

		fun observeAll(
			categoryId: Long,
			filterOptions: Set<ListFilterOption>,
			limit: Int,
		): Flow<List<Manga>> =
			observeOrder(categoryId)
				.flatMapLatest { order -> observeAll(categoryId, order, filterOptions, limit) }

		fun observeMangaCount(): Flow<Int> =
			db
				.getFavouritesDao()
				.observeMangaCount()
				.distinctUntilChanged()

		fun observeCategories(): Flow<List<FavouriteCategory>> =
			db
				.getFavouriteCategoriesDao()
				.observeAll()
				.mapItems {
					it.toFavouriteCategory()
				}.distinctUntilChanged()

		fun observeCategoriesForLibrary(): Flow<List<FavouriteCategory>> =
			db
				.getFavouriteCategoriesDao()
				.observeAllVisible()
				.mapItems {
					it.toFavouriteCategory()
				}.distinctUntilChanged()

		fun observeCategoriesWithCovers(): Flow<Map<FavouriteCategory, List<Cover>>> =
			db.invalidationTracker
				.createFlow(
					TABLE_FAVOURITES,
					TABLE_FAVOURITE_CATEGORIES,
					emitInitialState = true,
				).mapLatest {
					db.withTransaction {
						val categories = db.getFavouriteCategoriesDao().findAll()
						val res = LinkedHashMap<FavouriteCategory, List<Cover>>(categories.size)
						for (entity in categories) {
							val cat = entity.toFavouriteCategory()
							res[cat] =
								db.getFavouritesDao().findCovers(
									categoryId = cat.id,
									order = cat.order,
								)
						}
						res
					}
				}.distinctUntilChanged()

		suspend fun getAllFavoritesCovers(
			order: ListSortOrder,
			limit: Int,
		): List<Cover> = db.getFavouritesDao().findCovers(order, limit)

		fun observeCategory(id: Long): Flow<FavouriteCategory?> =
			db
				.getFavouriteCategoriesDao()
				.observe(id)
				.map { it?.toFavouriteCategory() }

		fun observeCategories(mangaId: Long): Flow<Set<FavouriteCategory>> =
			db.getFavouritesDao().observeCategories(mangaId).map {
				it.mapTo(LinkedHashSet(it.size)) { x -> x.toFavouriteCategory() }
			}

		fun observeIsFavorite(mangaId: Long): Flow<Boolean> = db.getFavouritesDao().observeIsFavorite(mangaId)

		suspend fun getCategory(id: Long): FavouriteCategory = db.getFavouriteCategoriesDao().find(id.toInt()).toFavouriteCategory()

		suspend fun isFavorite(mangaId: Long): Boolean = db.getFavouritesDao().findCategoriesCount(mangaId) != 0

		suspend fun getCategoriesIds(mangaId: Long): Set<Long> = db.getFavouritesDao().findCategoriesIds(mangaId).toSet()

		suspend fun findPopularSources(
			categoryId: Long,
			limit: Int,
		): List<MangaSource> =
			db
				.getFavouritesDao()
				.run {
					if (categoryId == 0L) {
						findPopularSources(limit)
					} else {
						findPopularSources(categoryId, limit)
					}
				}.toMangaSources()

		suspend fun findPopularTags(limit: Int): List<MangaTag> = db.getTagsDao().findPopularTags(limit).toMangaTagsList()

		suspend fun createCategory(
			title: String,
			sortOrder: ListSortOrder,
			isTrackerEnabled: Boolean,
			isVisibleOnShelf: Boolean,
		): FavouriteCategory {
			val entity =
				FavouriteCategoryEntity(
					title = title,
					createdAt = System.currentTimeMillis(),
					sortKey = db.getFavouriteCategoriesDao().getNextSortKey(),
					categoryId = 0,
					order = sortOrder.name,
					track = isTrackerEnabled,
					deletedAt = 0L,
					isVisibleInLibrary = isVisibleOnShelf,
				)
			val id = db.getFavouriteCategoriesDao().insert(entity)
			val category = entity.toFavouriteCategory(id)
			return category
		}

		suspend fun updateCategory(
			id: Long,
			title: String,
			sortOrder: ListSortOrder,
			isTrackerEnabled: Boolean,
			isVisibleOnShelf: Boolean,
		) {
			db.getFavouriteCategoriesDao().update(id, title, sortOrder.name, isTrackerEnabled, isVisibleOnShelf)
		}

		suspend fun updateCategory(
			id: Long,
			isVisibleInLibrary: Boolean,
		) {
			db.getFavouriteCategoriesDao().updateVisibility(id, isVisibleInLibrary)
		}

		suspend fun updateCategoryTracking(
			id: Long,
			isTrackingEnabled: Boolean,
		) {
			db.getFavouriteCategoriesDao().updateTracking(id, isTrackingEnabled)
		}

		suspend fun removeCategories(ids: Collection<Long>) {
			db.withTransaction {
				for (id in ids) {
					val dao = db.getFavouritesDao()
					val mangaIds = dao.findAllIds(id).toList()
					dao.deleteAll(id)
					dao.recoverAllFavorites(mangaIds)
					db.getFavouriteCategoriesDao().delete(id)
				}
				db.getChaptersDao().gc()
			}
		}

		suspend fun setCategoryOrder(
			id: Long,
			order: ListSortOrder,
		) {
			db.getFavouriteCategoriesDao().updateOrder(id, order.name)
		}

		suspend fun reorderCategories(orderedIds: List<Long>) {
			val dao = db.getFavouriteCategoriesDao()
			db.withTransaction {
				for ((i, id) in orderedIds.withIndex()) {
					dao.updateSortKey(id, i)
				}
			}
		}

		suspend fun reorderManga(
			categoryId: Long,
			orderedMangaIds: List<Long>,
		) {
			val dao = db.getFavouritesDao()
			db.withTransaction {
				(orderedMangaIds + dao.findAllIds(categoryId).filterNot(orderedMangaIds::contains))
					.forEachIndexed { i, id -> dao.setSortKey(categoryId, id, i) }
			}
		}

		suspend fun addToCategory(
			categoryId: Long,
			mangas: Collection<Manga>,
		) {
			db.withTransaction {
				for (manga in mangas) {
					val tags = manga.tags.toEntities()
					db.getTagsDao().upsert(tags)
					db.getMangaDao().upsert(manga.toEntity(), tags)
					val entity =
						FavouriteEntity(
							mangaId = manga.id,
							categoryId = categoryId,
							createdAt = System.currentTimeMillis(),
							sortKey = 0,
							deletedAt = 0L,
							isPinned = false,
						)
					db.getFavouritesDao().insert(entity)
				}
			}
		}

		suspend fun addToFavourites(mangas: Collection<Manga>) {
			addToCategory(ALL_FAVORITES_CATEGORY_ID, mangas)
		}

		suspend fun removeFromFavourites(ids: Collection<Long>): ReversibleHandle {
			db.withTransaction {
				for (id in ids) {
					db.getFavouritesDao().delete(mangaId = id)
				}
				db.getChaptersDao().gc()
			}
			return ReversibleHandle { recoverToFavourites(ids) }
		}

		suspend fun removeFromCategory(
			categoryId: Long,
			ids: Collection<Long>,
		): ReversibleHandle {
			db.withTransaction {
				for (id in ids) {
					db.getFavouritesDao().delete(categoryId = categoryId, mangaId = id)
				}
				db.getChaptersDao().gc()
			}
			return ReversibleHandle { recoverToCategory(categoryId, ids) }
		}

		private fun observeOrder(categoryId: Long): Flow<ListSortOrder> =
			db
				.getFavouriteCategoriesDao()
				.observe(categoryId)
				.filterNotNull()
				.map { x -> ListSortOrder(x.order, ListSortOrder.NEWEST) }
				.distinctUntilChanged()

		private fun observeRules(scope: FavouriteScope): Flow<SmartFolderRules?> =
			when (scope) {
				FavouriteScope.All,
				is FavouriteScope.Category,
				-> {
					flowOf(null)
				}

				is FavouriteScope.SmartFolder -> {
					smartFoldersRepository.observe(scope.id).map { folder ->
						when (val result = requireNotNull(folder) { "Smart folder ${scope.id} does not exist" }.rules) {
							is SmartFolderRulesResult.Success -> result.rules
							is SmartFolderRulesResult.Error -> throw SmartFolderRulesException(result.reason)
						}
					}
				}
			}

		suspend fun getMostUpdatedCategories(limit: Int): List<FavouriteCategory> =
			db.getFavouriteCategoriesDao().getMostUpdatedCategories(limit).map {
				it.toFavouriteCategory()
			}

		private suspend fun recoverToFavourites(ids: Collection<Long>) {
			db.withTransaction {
				for (id in ids) {
					db.getFavouritesDao().recover(mangaId = id)
				}
			}
		}

		private suspend fun recoverToCategory(
			categoryId: Long,
			ids: Collection<Long>,
		) {
			db.withTransaction {
				for (id in ids) {
					db.getFavouritesDao().recover(mangaId = id, categoryId = categoryId)
				}
			}
		}
	}
