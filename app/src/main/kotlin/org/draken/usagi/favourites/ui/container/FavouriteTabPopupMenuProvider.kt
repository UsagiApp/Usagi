package org.draken.usagi.favourites.ui.container

import android.content.Context
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.core.view.MenuProvider
import org.draken.usagi.R
import org.draken.usagi.core.nav.AppRouter
import org.draken.usagi.core.ui.dialog.buildAlertDialog
import org.draken.usagi.favourites.domain.FavouriteScope

class FavouriteTabPopupMenuProvider(
	private val context: Context,
	private val router: AppRouter,
	private val viewModel: FavouritesContainerViewModel,
	private val tab: FavouriteTabModel,
) : MenuProvider {
	override fun onCreateMenu(
		menu: Menu,
		menuInflater: MenuInflater,
	) {
		val menuResId =
			when (tab.scope) {
				FavouriteScope.All -> R.menu.popup_fav_tab_all
				is FavouriteScope.Category -> R.menu.popup_fav_tab
				is FavouriteScope.SmartFolder -> R.menu.popup_fav_tab_smart
			}
		menuInflater.inflate(menuResId, menu)
	}

	override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
		when (menuItem.itemId) {
			R.id.action_hide -> hide()
			R.id.action_edit -> edit()
			R.id.action_delete -> confirmDelete()
			R.id.action_manage -> router.openFavoriteCategories()
			R.id.action_manage_smart_folders -> router.openSmartFolders()
			else -> return false
		}
		return true
	}

	private fun confirmDelete() {
		val isSmartFolder = tab.scope is FavouriteScope.SmartFolder
		buildAlertDialog(context, isCentered = true) {
			setMessage(if (isSmartFolder) R.string.smart_folder_delete_confirm else R.string.categories_delete_confirm)
			setTitle(if (isSmartFolder) R.string.delete_smart_folder else R.string.remove_category)
			setIcon(R.drawable.ic_delete)
			setNegativeButton(android.R.string.cancel, null)
			setPositiveButton(if (isSmartFolder) R.string.delete else R.string.remove) { _, _ -> delete() }
		}.show()
	}

	private fun hide() {
		when (val scope = tab.scope) {
			FavouriteScope.All -> Unit
			is FavouriteScope.Category -> viewModel.hideCategory(scope.id)
			is FavouriteScope.SmartFolder -> Unit
		}
	}

	private fun edit() {
		when (val scope = tab.scope) {
			FavouriteScope.All -> Unit
			is FavouriteScope.Category -> router.openFavoriteCategoryEdit(scope.id)
			is FavouriteScope.SmartFolder -> router.openSmartFolderEdit(scope.id)
		}
	}

	private fun delete() {
		when (val scope = tab.scope) {
			FavouriteScope.All -> Unit
			is FavouriteScope.Category -> viewModel.deleteCategory(scope.id)
			is FavouriteScope.SmartFolder -> viewModel.deleteSmartFolder(scope.id)
		}
	}
}
