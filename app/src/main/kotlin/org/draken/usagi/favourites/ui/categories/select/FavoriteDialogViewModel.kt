package org.draken.usagi.favourites.ui.categories.select

import androidx.collection.MutableLongObjectMap
import androidx.collection.MutableLongSet
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.google.android.material.checkbox.MaterialCheckBox
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.draken.usagi.R
import org.draken.usagi.alternatives.domain.MigrateUseCase
import org.draken.usagi.core.db.MangaDatabase
import org.draken.usagi.core.model.FavouriteCategory
import org.draken.usagi.core.model.ids
import org.draken.usagi.core.model.parcelable.ParcelableManga
import org.draken.usagi.core.nav.AppRouter
import org.draken.usagi.core.prefs.AppSettings
import org.draken.usagi.core.prefs.observeAsFlow
import org.draken.usagi.core.ui.BaseViewModel
import org.draken.usagi.core.util.ext.MutableEventFlow
import org.draken.usagi.core.util.ext.call
import org.draken.usagi.core.util.ext.require
import org.draken.usagi.favourites.domain.FavouritesRepository
import org.draken.usagi.favourites.ui.categories.select.model.MangaCategoryItem
import org.draken.usagi.list.ui.model.EmptyState
import org.draken.usagi.list.ui.model.ListModel
import org.draken.usagi.list.ui.model.LoadingState
import tsuki.model.Manga
import tsuki.util.runCatchingCancellable
import javax.inject.Inject

@HiltViewModel
class FavoriteDialogViewModel
	@Inject
	constructor(
		savedStateHandle: SavedStateHandle,
		private val favouritesRepository: FavouritesRepository,
		settings: AppSettings,
		private val migrator: MigrateUseCase,
		private val db: MangaDatabase,
	) : BaseViewModel() {
		val manga =
			savedStateHandle.require<List<ParcelableManga>>(AppRouter.KEY_MANGA_LIST).map {
				it.manga
			}

		val onDuplicate = MutableEventFlow<Pair<Manga, Long>>()
		val onMigrated = MutableEventFlow<Manga>()
		private val refreshTrigger = MutableStateFlow(Any())
		private val checkMap = MutableStateFlow<Map<Long, Boolean>>(emptyMap())
		val content =
			combine(
				favouritesRepository.observeCategories(),
				refreshTrigger,
				settings.observeAsFlow(AppSettings.KEY_TRACKER_ENABLED) { isTrackerEnabled },
				checkMap,
			) { categories, _, tracker, map -> mapList(categories, tracker, map) }
				.withErrorHandling()
				.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, listOf(LoadingState))

		fun setChecked(
			categoryId: Long,
			isChecked: Boolean,
			force: Boolean = false,
		) {
			checkMap.value += categoryId to isChecked
			launchJob(Dispatchers.Default) {
				if (isChecked && !force) {
					manga.firstOrNull()?.let { m ->
						val norm: (String) -> String = { s -> s.filter { it.isLetterOrDigit() }.lowercase() }
						val titles =
							buildList {
								if (m.title.isNotBlank()) add(norm(m.title))
								m.altTitles.forEach { if (it.isNotBlank()) add(norm(it)) }
							}.filter { it.isNotBlank() }
						val dup =
							db
								.getScrobblingDao()
								.find(m.id)
								.firstNotNullOfOrNull {
									db.getScrobblingDao().findMangaId(it.scrobbler, it.targetId, m.id)
								}?.let { id -> favouritesRepository.getManga(categoryId).firstOrNull { it.id == id } }
								?: favouritesRepository.getManga(categoryId).firstOrNull { f ->
									if (f.id == m.id) {
										false
									} else if (f.title.isNotBlank() && norm(f.title).let { n -> n.isNotBlank() && titles.contains(n) }) {
										true
									} else {
										f.altTitles.any { a -> a.isNotBlank() && norm(a).let { n -> n.isNotBlank() && titles.contains(n) } }
									}
								}
						if (dup != null) {
							checkMap.value -= categoryId
							return@launchJob onDuplicate.call(dup to categoryId)
						}
					}
				}
				if (isChecked) {
					favouritesRepository.addToCategory(categoryId, manga)
				} else {
					favouritesRepository.removeFromCategory(categoryId, manga.ids())
				}
				checkMap.value -= categoryId
				refreshTrigger.value = Any()
			}
		}

		fun migrate(dup: Manga) =
			launchJob(Dispatchers.Default) {
				manga.firstOrNull()?.let { runCatchingCancellable { migrator(it, dup) } }
				onMigrated.call(dup)
			}

		private suspend fun mapList(
			categories: List<FavouriteCategory>,
			tracker: Boolean,
			map: Map<Long, Boolean>,
		): List<ListModel> {
			if (categories.isEmpty()) {
				return listOf(
					EmptyState(
						icon = 0,
						textPrimary = R.string.empty_favourite_categories,
						textSecondary = 0,
						actionStringRes = 0,
					),
				)
			}
			val cats = MutableLongObjectMap<MutableLongSet>(categories.size)
			categories.forEach { cats[it.id] = MutableLongSet(manga.size) }
			for (m in manga) {
				val ids = favouritesRepository.getCategoriesIds(m.id)
				ids.forEach { id -> cats[id]?.add(m.id) }
			}
			return categories.map { cat ->
				val state =
					map[cat.id]?.let {
						if (it) MaterialCheckBox.STATE_CHECKED else MaterialCheckBox.STATE_UNCHECKED
					} ?: when (cats[cat.id]?.size ?: 0) {
						0 -> MaterialCheckBox.STATE_UNCHECKED
						manga.size -> MaterialCheckBox.STATE_CHECKED
						else -> MaterialCheckBox.STATE_INDETERMINATE
					}
				MangaCategoryItem(
					category = cat,
					checkedState = state,
					isTrackerEnabled = tracker,
				)
			}
		}
	}
