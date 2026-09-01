package org.draken.usagi.favourites.ui

import android.os.Bundle
import org.draken.usagi.core.nav.AppRouter
import org.draken.usagi.core.ui.FragmentContainerActivity
import org.draken.usagi.favourites.ui.list.FavouritesListFragment
import org.draken.usagi.favourites.ui.list.FavouritesListFragment.Companion.KEY_SCOPE_TYPE
import org.draken.usagi.favourites.ui.list.FavouritesListFragment.Companion.SCOPE_ALL
import org.draken.usagi.favourites.ui.list.FavouritesListFragment.Companion.SCOPE_CATEGORY

class FavouritesActivity : FragmentContainerActivity(FavouritesListFragment::class.java) {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		val categoryTitle = intent.getStringExtra(AppRouter.KEY_TITLE)
		if (categoryTitle != null) {
			title = categoryTitle
		}
	}

	override fun getFragmentExtras(): Bundle =
		(intent.extras ?: Bundle()).apply {
			putString(
				KEY_SCOPE_TYPE,
				if (containsKey(AppRouter.KEY_ID)) SCOPE_CATEGORY else SCOPE_ALL,
			)
		}
}
