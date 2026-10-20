package org.draken.usagi.details.ui.pager

import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import org.draken.usagi.R
import org.draken.usagi.details.ui.pager.bookmarks.BookmarksFragment
import org.draken.usagi.details.ui.pager.chapters.ChaptersFragment
import org.draken.usagi.details.ui.pager.pages.PagesFragment

class ChaptersPagesAdapter(
	fragment: Fragment,
	val isPagesTabEnabled: Boolean,
	private val isClassicUi: Boolean,
	val isChaptersTabEnabled: Boolean = true,
) : FragmentStateAdapter(fragment),
	TabLayoutMediator.TabConfigurationStrategy {
	private val tabIds =
		buildList {
			if (isChaptersTabEnabled) add(ChaptersPagesSheet.TAB_CHAPTERS)
			if (isPagesTabEnabled) add(ChaptersPagesSheet.TAB_PAGES)
			add(ChaptersPagesSheet.TAB_BOOKMARKS)
		}

	override fun getItemCount(): Int = tabIds.size

	override fun createFragment(position: Int): Fragment =
		when (tabIds[position]) {
			ChaptersPagesSheet.TAB_CHAPTERS -> ChaptersFragment()
			ChaptersPagesSheet.TAB_PAGES -> PagesFragment()
			ChaptersPagesSheet.TAB_BOOKMARKS -> BookmarksFragment()
			else -> error("Invalid tab ${tabIds[position]}")
		}

	override fun onConfigureTab(
		tab: TabLayout.Tab,
		position: Int,
	) {
		when (tabIds[position]) {
			ChaptersPagesSheet.TAB_CHAPTERS -> {
				if (isClassicUi) {
					tab.setText(R.string.chapters)
				} else {
					tab.setIcon(R.drawable.ic_list)
				}
			}

			ChaptersPagesSheet.TAB_PAGES -> {
				if (isClassicUi) {
					tab.setText(R.string.pages)
				} else {
					tab.setIcon(R.drawable.ic_grid)
				}
			}

			ChaptersPagesSheet.TAB_BOOKMARKS -> {
				if (isClassicUi) {
					tab.setText(R.string.bookmarks)
				} else {
					tab.setIcon(R.drawable.ic_bookmark)
				}
			}
		}
	}

	fun indexOfTab(tabId: Int): Int = tabIds.indexOf(tabId).takeIf { it >= 0 } ?: 0

	fun tabIdAt(position: Int): Int = tabIds[position]
}
