package org.draken.usagi.favourites.ui.smartfolders.edit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.draken.usagi.core.nav.AppRouter
import org.draken.usagi.core.ui.BaseViewModel
import org.draken.usagi.core.util.ext.MutableEventFlow
import org.draken.usagi.core.util.ext.call
import org.draken.usagi.favourites.domain.FavouritesRepository
import org.draken.usagi.favourites.domain.SmartFolder
import org.draken.usagi.favourites.domain.SmartFolderRules
import org.draken.usagi.favourites.domain.SmartFoldersRepository
import org.draken.usagi.list.domain.ListSortOrder
import javax.inject.Inject

@HiltViewModel
class SmartFolderEditViewModel
	@Inject
	constructor(
		savedStateHandle: SavedStateHandle,
		private val smartFoldersRepository: SmartFoldersRepository,
		favouritesRepository: FavouritesRepository,
	) : BaseViewModel() {
		private val folderId = savedStateHandle[AppRouter.KEY_ID] ?: NO_ID

		val onSaved = MutableEventFlow<Unit>()
		val folder = MutableStateFlow<SmartFolder?>(null)
		val categories =
			favouritesRepository
				.observeCategories()
				.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, emptyList())
		val sources =
			flow { emit(favouritesRepository.findPopularSources(0L, 100)) }
				.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, emptyList())
		val tags =
			flow { emit(favouritesRepository.findPopularTags(100)) }
				.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, emptyList())

		init {
			if (folderId != NO_ID) {
				launchLoadingJob(Dispatchers.Default) {
					folder.value = requireNotNull(smartFoldersRepository.find(folderId))
				}
			}
		}

		fun save(
			title: String,
			listOrder: ListSortOrder,
			rules: SmartFolderRules,
		) {
			launchLoadingJob(Dispatchers.Default) {
				if (folderId == NO_ID) {
					smartFoldersRepository.create(title, listOrder, rules)
				} else {
					smartFoldersRepository.update(folderId, title, listOrder, rules)
				}
				onSaved.call(Unit)
			}
		}

		companion object {
			const val NO_ID = -1L
		}
	}
