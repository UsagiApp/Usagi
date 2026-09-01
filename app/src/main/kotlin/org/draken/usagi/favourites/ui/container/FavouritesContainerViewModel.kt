package org.draken.usagi.favourites.ui.container

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.draken.usagi.R
import org.draken.usagi.core.model.FavouriteCategory
import org.draken.usagi.core.ui.BaseViewModel
import org.draken.usagi.core.ui.util.ReversibleAction
import org.draken.usagi.core.ui.util.ReversibleHandle
import org.draken.usagi.core.util.ext.MutableEventFlow
import org.draken.usagi.core.util.ext.call
import org.draken.usagi.favourites.domain.FavouriteScope
import org.draken.usagi.favourites.domain.FavouritesRepository
import org.draken.usagi.favourites.domain.SmartFolder
import org.draken.usagi.favourites.domain.SmartFolderRulesResult
import org.draken.usagi.favourites.domain.SmartFoldersRepository
import javax.inject.Inject

@HiltViewModel
class FavouritesContainerViewModel
	@Inject
	constructor(
		private val favouritesRepository: FavouritesRepository,
		private val smartFoldersRepository: SmartFoldersRepository,
	) : BaseViewModel() {
		val onActionDone = MutableEventFlow<ReversibleAction>()

		private val categoriesStateFlow =
			favouritesRepository
				.observeCategoriesForLibrary()
				.withErrorHandling()
				.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, null)

		private val smartFoldersStateFlow =
			smartFoldersRepository
				.observeAll()
				.withErrorHandling()
				.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, null)

		val categories =
			combine(
				categoriesStateFlow.filterNotNull(),
				smartFoldersStateFlow.filterNotNull(),
			) { categories, smartFolders ->
				buildFavouriteTabs(categories, smartFolders)
			}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, emptyList())

		fun hideCategory(categoryId: Long) {
			launchJob(Dispatchers.Default) {
				favouritesRepository.updateCategory(categoryId, isVisibleInLibrary = false)
				val reverse =
					ReversibleHandle {
						favouritesRepository.updateCategory(categoryId, isVisibleInLibrary = true)
					}
				onActionDone.call(ReversibleAction(R.string.category_hidden_done, reverse))
			}
		}

		fun deleteCategory(categoryId: Long) {
			launchJob(Dispatchers.Default) {
				favouritesRepository.removeCategories(setOf(categoryId))
			}
		}

		fun deleteSmartFolder(folderId: Long) {
			launchJob(Dispatchers.Default) {
				smartFoldersRepository.delete(folderId)
			}
		}
	}

internal fun buildFavouriteTabs(
	categories: List<FavouriteCategory>,
	smartFolders: List<SmartFolder>,
): List<FavouriteTabModel> =
	buildList(categories.size + smartFolders.size + 1) {
		add(FavouriteTabModel(FavouriteScope.All, null))
		categories.mapTo(this) { category ->
			FavouriteTabModel(FavouriteScope.Category(category.id), category.title)
		}
		smartFolders.mapTo(this) { folder ->
			val successfulRules = folder.rules as? SmartFolderRulesResult.Success
			FavouriteTabModel(
				scope = FavouriteScope.SmartFolder(folder.id),
				title = folder.title,
				rules = successfulRules?.rules,
				rulesError = (folder.rules as? SmartFolderRulesResult.Error)?.reason,
			)
		}
	}
