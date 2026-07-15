package org.draken.usagi.core

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.annotation.WorkerThread
import androidx.appcompat.app.AppCompatDelegate
import androidx.hilt.work.HiltWorkerFactory
import androidx.room.InvalidationTracker
import androidx.work.Configuration
import eu.kanade.tachiyomi.AppInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.internal.platform.PlatformRegistry
import org.conscrypt.Conscrypt
import org.draken.tsukimix.core.parser.tachiyomi.model.TachiyomiMangaSource as External
import org.draken.usagi.BuildConfig
import org.draken.usagi.core.db.MangaDatabase
import org.draken.usagi.core.model.MangaSourceRegistry
import org.draken.usagi.core.os.AppValidator
import org.draken.usagi.core.model.PluginKeyResolver
import org.draken.usagi.core.parser.MangaDynamicRepository
import org.draken.usagi.filter.data.SavedFiltersRepository
import org.draken.usagi.core.prefs.AppSettings
import org.draken.usagi.core.ui.GlobalExceptionHandler
import org.draken.usagi.core.util.ext.processLifecycleScope
import org.draken.usagi.local.data.LocalStorageChanges
import org.draken.usagi.local.data.index.LocalMangaIndex
import org.draken.usagi.local.domain.model.LocalManga
import org.draken.usagi.settings.work.WorkScheduleManager
import org.draken.tsukimix.core.parser.tachiyomi.TachiyomiExtensionManager as ExternalManager
import java.security.Security
import javax.inject.Inject
import javax.inject.Provider

open class BaseApp : Application(), Configuration.Provider {

	@Inject
	lateinit var databaseObserversProvider: Provider<Set<@JvmSuppressWildcards InvalidationTracker.Observer>>

	@Inject
	lateinit var activityLifecycleCallbacks: Set<@JvmSuppressWildcards ActivityLifecycleCallbacks>

	@Inject
	lateinit var database: Provider<MangaDatabase>

	@Inject
	lateinit var settings: AppSettings

	@Inject
	lateinit var workerFactory: HiltWorkerFactory

	@Inject
	lateinit var appValidator: AppValidator

	@Inject
	lateinit var workScheduleManager: WorkScheduleManager

	@Inject
	lateinit var savedFiltersRepository: SavedFiltersRepository

	@Inject
	lateinit var localMangaIndexProvider: Provider<LocalMangaIndex>

	@Inject
	lateinit var mangaDynamicRepository: MangaDynamicRepository

	@Inject
	lateinit var pluginKeyResolver: PluginKeyResolver

	@Inject
	lateinit var externalManager: ExternalManager

	@Inject
	@LocalStorageChanges
	lateinit var localStorageChanges: MutableSharedFlow<LocalManga?>

	override val workManagerConfiguration: Configuration
		get() = Configuration.Builder()
			.setWorkerFactory(workerFactory)
			.build()

	override fun onCreate() {
		super.onCreate()
		AppInfo.initialize(BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME)
		PlatformRegistry.applicationContext = this // TODO replace with OkHttp.initialize
		Thread.setDefaultUncaughtExceptionHandler(
			GlobalExceptionHandler(this, settings, Thread.getDefaultUncaughtExceptionHandler())
		)
		AppCompatDelegate.setDefaultNightMode(settings.theme)
		// TLS 1.3 support for Android < 10
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
			Security.insertProviderAt(Conscrypt.newProvider(), 1)
		}
		setupActivityLifecycleCallbacks()
		processLifecycleScope.launch(Dispatchers.Default) {
			setupDatabaseObservers()
			localStorageChanges.collect(localMangaIndexProvider.get())
		}
		processLifecycleScope.launch(Dispatchers.Default) {
			externalManager.sources.collect { wrapped ->
				val exist = MangaSourceRegistry.sources.filterNot { it is External }
				MangaSourceRegistry.publish(exist + wrapped)
			}
		}

		processLifecycleScope.launch(Dispatchers.IO) {
			mangaDynamicRepository.load(mangaDynamicRepository.getDir())
			externalManager.ensureReady()
			withContext(Dispatchers.Default) {
				pluginKeyResolver.normalize(database.get(), savedFiltersRepository)
			}
		}
		workScheduleManager.init()
	}

	override fun attachBaseContext(base: Context) {
		super.attachBaseContext(base)
	}

	@WorkerThread
	private fun setupDatabaseObservers() {
		val tracker = database.get().invalidationTracker
		databaseObserversProvider.get().forEach {
			tracker.addObserver(it)
		}
	}

	private fun setupActivityLifecycleCallbacks() {
		activityLifecycleCallbacks.forEach {
			registerActivityLifecycleCallbacks(it)
		}
	}
}
