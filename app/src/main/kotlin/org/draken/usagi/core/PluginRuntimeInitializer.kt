package org.draken.usagi.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.draken.tsukimix.core.parser.tachiyomi.TachiyomiRuntime
import org.draken.usagi.core.parser.MirrorSwitcher
import tsuki.MangaLoaderContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class PluginRuntimeInitializer
	@Inject
	constructor(
		private val tachiyomiRuntime: Provider<TachiyomiRuntime>,
		private val mangaLoaderContext: Provider<MangaLoaderContext>,
		private val mirrorSwitcher: Provider<MirrorSwitcher>,
	) {
		private val isStarted = AtomicBoolean(false)

		suspend fun initializeAfterGui() {
			if (!isStarted.compareAndSet(false, true)) return
			withContext(Dispatchers.IO) {
				runCatching { mangaLoaderContext.get() }
				runCatching { mirrorSwitcher.get() }
				runCatching { tachiyomiRuntime.get().ensureReady() }
			}
		}
	}
