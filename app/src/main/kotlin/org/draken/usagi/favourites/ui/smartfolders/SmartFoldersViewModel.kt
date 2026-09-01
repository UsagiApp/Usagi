package org.draken.usagi.favourites.ui.smartfolders

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.draken.usagi.core.ui.BaseViewModel
import org.draken.usagi.favourites.domain.SmartFolder
import org.draken.usagi.favourites.domain.SmartFoldersRepository
import javax.inject.Inject

@HiltViewModel
class SmartFoldersViewModel
	@Inject
	constructor(
		private val repository: SmartFoldersRepository,
	) : BaseViewModel() {
		val folders =
			repository
				.observeAll()
				.withErrorHandling()
				.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, emptyList())

		fun delete(folder: SmartFolder) {
			launchJob(Dispatchers.Default) {
				repository.delete(folder.id)
			}
		}

		fun saveOrder(folders: List<SmartFolder>) {
			launchJob(Dispatchers.Default) {
				repository.reorder(folders.map(SmartFolder::id))
			}
		}
	}
