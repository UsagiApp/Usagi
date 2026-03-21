package org.draken.usagi.widget.shelf

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.widget.RemoteViewsService
import coil3.ImageLoader
import dagger.Lazy
import dagger.hilt.android.AndroidEntryPoint
import org.draken.usagi.core.prefs.AppSettings
import org.draken.usagi.favourites.domain.FavouritesRepository
import javax.inject.Inject

@AndroidEntryPoint
class ShelfWidgetService : RemoteViewsService() {

	@Inject
	lateinit var favouritesRepository: FavouritesRepository

	@Inject
	lateinit var settings: AppSettings

	@Inject
	lateinit var coilLazy: Lazy<ImageLoader>

	override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
		val widgetId = intent.getIntExtra(
			AppWidgetManager.EXTRA_APPWIDGET_ID,
			AppWidgetManager.INVALID_APPWIDGET_ID,
		)
		return ShelfListFactory(applicationContext, favouritesRepository, coilLazy, settings, widgetId)
	}
}
