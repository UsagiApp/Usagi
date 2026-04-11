package org.draken.usagi.settings.sources.manage.plugins

import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.appcompat.widget.SearchView
import androidx.core.view.MenuProvider
import org.draken.usagi.R
import org.draken.usagi.main.ui.owners.AppBarOwner

class PluginsMenuProvider(
	private val appBarOwner: AppBarOwner?,
	private val onImportClick: () -> Unit,
	private val onSearchQueryChanged: (String?) -> Unit,
) : MenuProvider, MenuItem.OnActionExpandListener, SearchView.OnQueryTextListener {

	override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
		menuInflater.inflate(R.menu.opt_sources, menu)
		menu.findItem(R.id.action_catalog)?.setTitle(R.string._import)
		menu.findItem(R.id.action_no_nsfw)?.isVisible = false
		menu.findItem(R.id.action_disable_all)?.isVisible = false
		val searchMenuItem = menu.findItem(R.id.action_search)
		searchMenuItem.setOnActionExpandListener(this)
		val searchView = searchMenuItem.actionView as SearchView
		searchView.setOnQueryTextListener(this)
		searchView.setIconifiedByDefault(false)
		searchView.queryHint = searchMenuItem.title
	}

	override fun onMenuItemSelected(menuItem: MenuItem): Boolean = when (menuItem.itemId) {
		R.id.action_catalog -> {
			onImportClick()
			true
		}
		else -> false
	}

	override fun onMenuItemActionExpand(item: MenuItem): Boolean {
		appBarOwner?.appBar?.setExpanded(false, true)
		return true
	}

	override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
		(item.actionView as SearchView).setQuery("", false)
		return true
	}

	override fun onQueryTextSubmit(query: String?): Boolean = false

	override fun onQueryTextChange(newText: String?): Boolean {
		onSearchQueryChanged(newText)
		return true
	}
}
