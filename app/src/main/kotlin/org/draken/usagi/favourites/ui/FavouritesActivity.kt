package org.draken.usagi.favourites.ui

import android.os.Bundle
import org.draken.usagi.core.nav.AppRouter
import org.draken.usagi.core.ui.FragmentContainerActivity
import org.draken.usagi.favourites.ui.list.FavouritesListFragment

class FavouritesActivity : FragmentContainerActivity(FavouritesListFragment::class.java) {

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		val categoryTitle = intent.getStringExtra(AppRouter.KEY_TITLE)
		if (categoryTitle != null) {
			title = categoryTitle
		}
	}
}
