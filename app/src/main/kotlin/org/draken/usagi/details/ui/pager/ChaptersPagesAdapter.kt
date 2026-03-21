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
) : FragmentStateAdapter(fragment),
	TabLayoutMediator.TabConfigurationStrategy {

	override fun getItemCount(): Int = if (isPagesTabEnabled) 3 else 2

	override fun createFragment(position: Int): Fragment = when (position) {
		0 -> ChaptersFragment()
		1 -> if (isPagesTabEnabled) PagesFragment() else BookmarksFragment()
		2 -> BookmarksFragment()
		else -> throw IllegalArgumentException("Invalid position $position")
	}

	override fun onConfigureTab(tab: TabLayout.Tab, position: Int) {
		if (isClassicUi) {
			tab.setText(
				when (position) {
					0 -> R.string.chapters
					1 -> if (isPagesTabEnabled) R.string.pages else R.string.bookmarks
					2 -> R.string.bookmarks
					else -> 0
				},
			)
		} else {
			tab.setIcon(
				when (position) {
					0 -> R.drawable.ic_list
					1 -> if (isPagesTabEnabled) R.drawable.ic_grid else R.drawable.ic_bookmark
					2 -> R.drawable.ic_bookmark
					else -> 0
				},
			)
		}
	}
}
