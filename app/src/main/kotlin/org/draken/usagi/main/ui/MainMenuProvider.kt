package org.draken.usagi.main.ui

import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.core.view.MenuProvider
import org.draken.usagi.R
import org.draken.usagi.core.nav.AppRouter

class MainMenuProvider(
	private val router: AppRouter,
	private val viewModel: MainViewModel,
) : MenuProvider {

	override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
		menuInflater.inflate(R.menu.opt_main, menu)
	}

	override fun onPrepareMenu(menu: Menu) {
		menu.findItem(R.id.action_incognito)?.isChecked =
			viewModel.isIncognitoModeEnabled.value
		val hasAppUpdate = viewModel.appUpdate.value != null
		menu.findItem(R.id.action_app_update)?.isVisible = hasAppUpdate
	}

	override fun onMenuItemSelected(menuItem: MenuItem): Boolean = when (menuItem.itemId) {
		R.id.action_settings -> {
			router.openSettings()
			true
		}

		R.id.action_incognito -> {
			viewModel.setIncognitoMode(!menuItem.isChecked)
			true
		}

		R.id.action_app_update -> {
			router.openAppUpdate()
			true
		}

		else -> false
	}
}
