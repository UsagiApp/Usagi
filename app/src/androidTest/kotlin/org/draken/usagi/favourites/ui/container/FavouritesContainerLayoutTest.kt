package org.draken.usagi.favourites.ui.container

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.chip.ChipGroup
import com.google.android.material.tabs.TabLayout
import org.draken.usagi.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FavouritesContainerLayoutTest {
	@Test
	fun folderAndStageNavigationUseTheFullAvailableHeaderWidth() {
		val applicationContext = ApplicationProvider.getApplicationContext<Context>()
		val context = ContextThemeWrapper(applicationContext, R.style.Theme_Usagi)
		val root = LayoutInflater.from(context).inflate(R.layout.fragment_favourites_container, null)
		val width = (360 * context.resources.displayMetrics.density).toInt()
		val widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
		val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
		root.measure(widthSpec, heightSpec)

		val tabs = root.findViewById<TabLayout>(R.id.tabs)
		val tabsRow = tabs.parent as View
		assertEquals(tabsRow.measuredWidth, tabs.measuredWidth)

		val stageChips = root.findViewById<ChipGroup>(R.id.stage_chips)
		val stageScroller = stageChips.parent as View
		val controls = root.findViewById<View>(R.id.organizer_controls)
		val availableControlsWidth = controls.measuredWidth - controls.paddingLeft - controls.paddingRight
		assertEquals(availableControlsWidth, stageScroller.measuredWidth)
	}
}
