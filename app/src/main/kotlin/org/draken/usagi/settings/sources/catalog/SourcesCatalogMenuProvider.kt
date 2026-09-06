package org.draken.usagi.settings.sources.catalog

import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.appcompat.widget.SearchView
import androidx.core.view.MenuProvider
import org.draken.usagi.R
import org.draken.usagi.main.ui.owners.AppBarOwner

class SourcesCatalogMenuProvider(
	private val appBarOwner: AppBarOwner?,
	private val viewModel: SourcesCatalogViewModel,
) : MenuProvider,
	MenuItem.OnActionExpandListener,
	SearchView.OnQueryTextListener {
	override fun onCreateMenu(
		menu: Menu,
		menuInflater: MenuInflater,
	) {
		menuInflater.inflate(R.menu.opt_sources_catalog, menu)
		val searchMenuItem = menu.findItem(R.id.action_search)
		searchMenuItem.setOnActionExpandListener(this)
		val searchView = searchMenuItem.actionView as SearchView
		searchView.setOnQueryTextListener(this)
		searchView.setIconifiedByDefault(false)
		searchView.queryHint = searchMenuItem.title
	}

	override fun onMenuItemSelected(menuItem: MenuItem): Boolean = false

	override fun onMenuItemActionExpand(item: MenuItem): Boolean {
		appBarOwner?.appBar?.setExpanded(true, true)
		val query =
			(item.actionView as? SearchView)
				?.query
				?.trim()
				?.toString()
				.orEmpty()
		viewModel.performSearch(query)
		return true
	}

	override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
		(item.actionView as? SearchView)?.setQuery("", false)
		viewModel.performSearch(null)
		return true
	}

	override fun onQueryTextSubmit(query: String?): Boolean = false

	override fun onQueryTextChange(newText: String?): Boolean {
		viewModel.performSearch(newText?.trim().orEmpty())
		return true
	}
}
