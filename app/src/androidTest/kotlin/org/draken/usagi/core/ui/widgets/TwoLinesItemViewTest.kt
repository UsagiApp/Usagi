package org.draken.usagi.core.ui.widgets

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.draken.usagi.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class TwoLinesItemViewTest {
	@Test
	fun explicitHorizontalPaddingSurvivesCustomBackgroundInitialization() {
		val applicationContext = ApplicationProvider.getApplicationContext<Context>()
		val context = ContextThemeWrapper(applicationContext, R.style.Theme_Usagi)
		val item = LayoutInflater.from(context).inflate(R.layout.item_favourite_smart_folder_management, null) as TwoLinesItemView
		val expectedPadding = (30 * context.resources.displayMetrics.density).roundToInt()

		assertEquals(expectedPadding, item.paddingLeft)
		assertEquals(expectedPadding, item.paddingRight)
	}
}
