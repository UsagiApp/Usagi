package org.draken.usagi.favourites.domain

import androidx.room.withTransaction
import dagger.Reusable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import org.draken.usagi.core.db.MangaDatabase
import org.draken.usagi.favourites.data.SmartFolderEntity
import org.draken.usagi.list.domain.ListSortOrder
import javax.inject.Inject

data class SmartFolder(
	val id: Long,
	val title: String,
	val sortKey: Int,
	val listOrder: ListSortOrder,
	val rules: SmartFolderRulesResult,
	val createdAt: Long,
	val updatedAt: Long,
	val deletedAt: Long,
)

class SmartFolderRulesException(
	val rulesError: SmartFolderRulesError,
) : IllegalArgumentException("Invalid smart folder rules: $rulesError")

@Reusable
class SmartFoldersRepository
	@Inject
	constructor(
		private val db: MangaDatabase,
	) {
		fun observeAll(): Flow<List<SmartFolder>> =
			db.getSmartFoldersDao().observeAll().combine(db.getFavouriteCategoriesDao().observeAll()) { entities, categories ->
				val categoryIds = categories.mapTo(hashSetOf()) { category -> category.categoryId.toLong() }
				entities.map { entity -> entity.toDomain(categoryIds) }
			}

		fun observe(id: Long): Flow<SmartFolder?> = observeAll().map { folders -> folders.find { folder -> folder.id == id } }

		suspend fun find(id: Long): SmartFolder? {
			val categoryIds = db.getFavouriteCategoriesDao().findAll().mapTo(hashSetOf()) { category -> category.categoryId.toLong() }
			return db.getSmartFoldersDao().find(id)?.toDomain(categoryIds)
		}

		suspend fun create(
			title: String,
			listOrder: ListSortOrder,
			rules: SmartFolderRules,
		): SmartFolder {
			val validatedRules = rules.requireValidWithReferences()
			val now = System.currentTimeMillis()
			val dao = db.getSmartFoldersDao()
			val entity =
				SmartFolderEntity(
					id = 0L,
					title = title.requireTitle(),
					sortKey = (dao.getMaxSortKey() ?: -1) + 1,
					listOrder = listOrder.name,
					rules = SmartFolderRulesCodec.encode(validatedRules),
					createdAt = now,
					updatedAt = now,
					deletedAt = 0L,
				)
			val id = dao.insert(entity)
			return entity.copy(id = id).toDomain(validatedRules.categoryIds)
		}

		suspend fun update(
			id: Long,
			title: String,
			listOrder: ListSortOrder,
			rules: SmartFolderRules,
		) {
			val existing = requireNotNull(db.getSmartFoldersDao().find(id)) { "Smart folder $id does not exist" }
			val validatedRules = rules.requireValidWithReferences()
			db.getSmartFoldersDao().upsert(
				existing.copy(
					title = title.requireTitle(),
					listOrder = listOrder.name,
					rules = SmartFolderRulesCodec.encode(validatedRules),
					updatedAt = System.currentTimeMillis(),
					deletedAt = 0L,
				),
			)
		}

		suspend fun delete(id: Long) {
			val now = System.currentTimeMillis()
			db.getSmartFoldersDao().setDeletedAt(id, now)
		}

		suspend fun setListOrder(
			id: Long,
			listOrder: ListSortOrder,
		) {
			val existing = requireNotNull(db.getSmartFoldersDao().find(id)) { "Smart folder $id does not exist" }
			db.getSmartFoldersDao().upsert(
				existing.copy(
					listOrder = listOrder.name,
					updatedAt = System.currentTimeMillis(),
				),
			)
		}

		suspend fun reorder(orderedIds: List<Long>) {
			val now = System.currentTimeMillis()
			db.withTransaction {
				orderedIds.forEachIndexed { index, id ->
					db.getSmartFoldersDao().updateSortKey(id, index, now)
				}
			}
		}

		suspend fun getEntitiesForBackup(): List<SmartFolderEntity> = db.getSmartFoldersDao().findAllForBackup()

		suspend fun restore(entities: Iterable<SmartFolderEntity>) {
			db.withTransaction {
				entities.forEach { entity ->
					entity.title.requireTitle()
					ListSortOrder.valueOf(entity.listOrder)
					when (val result = SmartFolderRulesCodec.decode(entity.rules)) {
						is SmartFolderRulesResult.Error -> throw SmartFolderRulesException(result.reason)
						is SmartFolderRulesResult.Success -> db.getSmartFoldersDao().upsert(entity)
					}
				}
			}
		}

		private fun SmartFolderRules.requireValid(): SmartFolderRules =
			when (val result = SmartFolderRulesCodec.validate(this)) {
				is SmartFolderRulesResult.Success -> result.rules
				is SmartFolderRulesResult.Error -> throw SmartFolderRulesException(result.reason)
			}

		private suspend fun SmartFolderRules.requireValidWithReferences(): SmartFolderRules {
			val rules = requireValid()
			if (rules.categoryIds.isNotEmpty()) {
				val available = db.getFavouriteCategoriesDao().findAll().mapTo(hashSetOf()) { category -> category.categoryId.toLong() }
				if (!available.containsAll(rules.categoryIds)) {
					throw SmartFolderRulesException(SmartFolderRulesError.MISSING_CATEGORY)
				}
			}
			return rules
		}

		private fun String.requireTitle(): String = trim().also { require(it.isNotEmpty()) { "Smart folder title is empty" } }

		private fun SmartFolderEntity.toDomain(activeCategoryIds: Set<Long>) =
			SmartFolder(
				id = id,
				title = title,
				sortKey = sortKey,
				listOrder = ListSortOrder.valueOf(listOrder),
				rules = SmartFolderRulesCodec.decode(rules).validateReferences(activeCategoryIds),
				createdAt = createdAt,
				updatedAt = updatedAt,
				deletedAt = deletedAt,
			)

		private fun SmartFolderRulesResult.validateReferences(activeCategoryIds: Set<Long>): SmartFolderRulesResult =
			when (this) {
				is SmartFolderRulesResult.Error -> {
					this
				}

				is SmartFolderRulesResult.Success -> {
					if (activeCategoryIds.containsAll(rules.categoryIds)) {
						this
					} else {
						SmartFolderRulesResult.Error(SmartFolderRulesError.MISSING_CATEGORY, rules)
					}
				}
			}
	}
