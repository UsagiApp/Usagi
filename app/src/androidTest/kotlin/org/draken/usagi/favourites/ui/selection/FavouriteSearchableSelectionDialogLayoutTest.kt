package org.draken.usagi.favourites.ui.selection

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import androidx.appcompat.widget.AppCompatCheckedTextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.draken.usagi.R
import org.draken.usagi.databinding.DialogFavouriteSearchableSelectionBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FavouriteSearchableSelectionDialogLayoutTest {
	@Test
	fun searchAndSelectionRowsUseRoundedFeatureControls() {
		val applicationContext = ApplicationProvider.getApplicationContext<Context>()
		val context = ContextThemeWrapper(applicationContext, R.style.Theme_Usagi)
		val binding = DialogFavouriteSearchableSelectionBinding.inflate(LayoutInflater.from(context))
		val density = context.resources.displayMetrics.density
		val expectedRadius = 24 * density

		assertEquals(expectedRadius, binding.searchContainer.radius, 0.5f)
		assertEquals((density + 0.5f).toInt(), binding.searchContainer.strokeWidth)
		assertEquals(0, (binding.listView.selector as ColorDrawable).color)

		val row =
			LayoutInflater.from(context).inflate(
				R.layout.item_favourite_searchable_selection,
				binding.listView,
				false,
			)
		assertTrue(row is AppCompatCheckedTextView)
		assertTrue(row.background is RippleDrawable)
		val checkedRow = row as AppCompatCheckedTextView
		val startDrawable = checkedRow.compoundDrawablesRelative.first()
		val startPadding = checkedRow.paddingStart
		checkedRow.isChecked = true
		assertTrue(startDrawable === checkedRow.compoundDrawablesRelative.first())
		assertEquals(startPadding, checkedRow.paddingStart)
		assertTrue(checkedRow.isChecked)
	}
}
