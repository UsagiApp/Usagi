package org.draken.usagi.settings.utils

import android.content.Context
import kotlinx.coroutines.flow.first
import org.draken.usagi.R
import org.draken.usagi.core.LocalizedAppContext
import org.draken.usagi.core.prefs.AppSettings
import org.draken.usagi.history.data.HistoryRepository
import org.draken.usagi.history.ui.toListHeader
import org.draken.usagi.history.ui.util.getDisplayText
import org.draken.usagi.list.domain.ReadingProgress
import javax.inject.Inject

class GetThemePreviewHistoryUseCase
	@Inject
	constructor(
		@LocalizedAppContext private val context: Context,
		private val historyRepository: HistoryRepository,
		private val settings: AppSettings,
	) {
		suspend operator fun invoke(): List<ThemePreviewDrawable.Group> {
			val order = settings.historySortOrder
			val isGrouped = settings.isHistoryGroupingEnabled && order.isGroupingSupported()
			val mode = settings.progressIndicatorMode
			val percentPattern = context.getString(R.string.percent_string_pattern)
			val groups = ArrayList<Pair<String?, MutableList<ThemePreviewDrawable.Item>>>()
			for ((manga, history) in historyRepository.observeAllWithHistory(order, emptySet(), LIMIT).first()) {
				val title = if (isGrouped) history.toListHeader(order)?.getText(context)?.toString() else null
				if (groups.isEmpty() || groups.last().first != title) {
					groups += title to ArrayList()
				}
				val progress = ReadingProgress.of(history.percent, history.chaptersCount, mode)
				groups.last().second +=
					ThemePreviewDrawable.Item(
						mangaId = manga.id,
						titleLength = manga.title.length,
						progress = progress?.percent ?: ReadingProgress.PROGRESS_NONE,
						progressText = progress.getDisplayText(percentPattern),
					)
			}
			return groups.map { (title, items) -> ThemePreviewDrawable.Group(title, items) }
		}

		private companion object {
			const val LIMIT = 40
		}
	}
