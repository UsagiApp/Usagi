package org.draken.usagi.settings.sources.manage

import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import org.draken.usagi.R
import org.draken.usagi.core.db.MangaDatabase
import org.draken.usagi.core.db.removeObserverAsync
import org.draken.usagi.core.prefs.AppSettings
import org.draken.usagi.core.ui.BaseViewModel
import org.draken.usagi.core.ui.util.ReversibleAction
import org.draken.usagi.core.util.ext.MutableEventFlow
import org.draken.usagi.core.util.ext.call
import org.draken.usagi.explore.data.MangaSourcesRepository
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.util.move
import org.draken.usagi.settings.sources.model.SourceConfigItem
import javax.inject.Inject

@HiltViewModel
class SourcesManageViewModel @Inject constructor(
	private val database: MangaDatabase,
	private val settings: AppSettings,
	private val repository: MangaSourcesRepository,
	private val listProducer: SourcesListProducer,
) : BaseViewModel() {

	val content = listProducer.list
	val onActionDone = MutableEventFlow<ReversibleAction>()
	private var commitJob: Job? = null

	init {
		launchJob(Dispatchers.Default) {
			database.invalidationTracker.addObserver(listProducer)
		}
	}

	override fun onCleared() {
		super.onCleared()
		database.invalidationTracker.removeObserverAsync(listProducer)
	}

	fun saveSourcesOrder(snapshot: List<SourceConfigItem>) {
		val prevJob = commitJob
		commitJob = launchJob(Dispatchers.Default) {
			prevJob?.cancelAndJoin()
			val newSourcesList = snapshot.mapNotNull { x ->
				if (x is SourceConfigItem.SourceItem && x.isDraggable) {
					x.source
				} else {
					null
				}
			}
			repository.setPositions(newSourcesList)
		}
	}

	fun canReorder(oldPos: Int, newPos: Int): Boolean {
		val snapshot = content.value
		val oldPosItem = snapshot.getOrNull(oldPos) as? SourceConfigItem.SourceItem ?: return false
		val newPosItem = snapshot.getOrNull(newPos) as? SourceConfigItem.SourceItem ?: return false
		return oldPosItem.isEnabled && newPosItem.isEnabled && oldPosItem.isPinned == newPosItem.isPinned
	}

	fun setEnabled(source: MangaSource, isEnabled: Boolean) {
		launchJob(Dispatchers.Default) {
			val rollback = repository.setSourcesEnabled(setOf(source), isEnabled)
			if (!isEnabled) {
				onActionDone.call(ReversibleAction(R.string.source_disabled, rollback))
			}
		}
	}

	fun setPinned(source: MangaSource, isPinned: Boolean) {
		launchJob(Dispatchers.Default) {
			val rollback = repository.setIsPinned(setOf(source), isPinned)
			val message = if (isPinned) R.string.source_pinned else R.string.source_unpinned
			onActionDone.call(ReversibleAction(message, rollback))
		}
	}

	fun bringToTop(source: MangaSource) {
		val snapshot = content.value
		launchJob(Dispatchers.Default) {
			var oldPos = -1
			var newPos = -1
			for ((i, x) in snapshot.withIndex()) {
				if (x !is SourceConfigItem.SourceItem) {
					continue
				}
				if (newPos == -1) {
					newPos = i
				}
				if (x.source == source) {
					oldPos = i
					break
				}
			}
			@Suppress("KotlinConstantConditions")
			if (oldPos != -1 && newPos != -1) {
				reorderSources(oldPos, newPos)
				val revert = ReversibleAction(R.string.moved_to_top) {
					reorderSources(newPos, oldPos)
				}
				commitJob?.join()
				onActionDone.call(revert)
			}
		}
	}

	fun disableAll() {
		launchJob(Dispatchers.Default) {
			repository.disableAllSources()
		}
	}

	fun performSearch(query: String?) {
		listProducer.setQuery(query?.trim().orEmpty())
	}

	fun onTipClosed(item: SourceConfigItem.Tip) {
		launchJob(Dispatchers.Default) {
			settings.closeTip(item.key)
		}
	}

	private fun reorderSources(oldPos: Int, newPos: Int) {
		val snapshot = content.value.toMutableList()
		if ((snapshot[oldPos] as? SourceConfigItem.SourceItem)?.isDraggable != true) {
			return
		}
		if ((snapshot[newPos] as? SourceConfigItem.SourceItem)?.isDraggable != true) {
			return
		}
		snapshot.move(oldPos, newPos)
		saveSourcesOrder(snapshot)
	}
}
