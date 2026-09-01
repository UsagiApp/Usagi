package org.draken.usagi.core.ui.dialog

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.draken.usagi.R
import org.draken.usagi.databinding.DialogSearchableSelectionBinding
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchableSelectionDialogLayoutTest {
	@Test
	fun shortListShrinksWhileLongListRemainsBounded() {
		val applicationContext = ApplicationProvider.getApplicationContext<Context>()
		val context = ContextThemeWrapper(applicationContext, R.style.Theme_Usagi)
		val binding = DialogSearchableSelectionBinding.inflate(LayoutInflater.from(context))
		val maximumHeight = (320 * context.resources.displayMetrics.density).toInt()

		binding.listView.adapter = ArrayAdapter(context, android.R.layout.simple_list_item_1, listOf("Only source"))
		measure(binding.root, context)
		val shortHeight = binding.listView.measuredHeight
		assertTrue("One item must not reserve the full dialog list height", shortHeight < maximumHeight)

		binding.listView.adapter =
			ArrayAdapter(
				context,
				android.R.layout.simple_list_item_1,
				List(100) { index -> "Item $index" },
			)
		measure(binding.root, context)
		val longHeight = binding.listView.measuredHeight
		assertTrue("Long lists must remain scrollable within the dialog", longHeight <= maximumHeight)
		assertTrue("Long lists must have more room than a one-item list", longHeight > shortHeight)
	}

	private fun measure(
		root: View,
		context: Context,
	) {
		val width = (360 * context.resources.displayMetrics.density).toInt()
		root.measure(
			View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
			View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
		)
	}
}
