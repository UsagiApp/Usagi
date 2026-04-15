package org.draken.usagi.core.parser

import android.content.Context
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import dalvik.system.DexClassLoader
import eu.kanade.tachiyomi.RuntimeContext
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.ConfigurableSource
import org.draken.usagi.R
import org.draken.usagi.core.model.MangaSourceRegistry
import org.draken.usagi.core.model.PluginMangaSource
import org.draken.usagi.core.model.TachiyomiPluginSource
import org.draken.usagi.core.prefs.SourceSettings
import org.draken.usagi.core.parser.tachiyomi.TachiyomiExtensionLoader
import org.draken.usagi.core.parser.tachiyomi.TachiyomiMangaParser
import org.draken.usagi.core.parser.tachiyomi.repo.TachiyomiRepoStore
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaParser
import org.koitharu.kotatsu.parsers.model.MangaSource
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class PluginClassLoader(
	dexPath: String,
	optimizedDirectory: String?,
	librarySearchPath: String?,
	parent: ClassLoader,
) : DexClassLoader(dexPath, optimizedDirectory, librarySearchPath, parent) {
	override fun loadClass(name: String, resolve: Boolean): Class<*> {
		if (name == "org.koitharu.kotatsu.parsers.util.LinkResolver" ||
			name.startsWith("org.koitharu.kotatsu.parsers.util.LinkResolver$") ||
			name == "org.koitharu.kotatsu.parsers.MangaLoaderContext" ||
			(name.startsWith("org.koitharu.kotatsu.parsers.model.") &&
				name != "org.koitharu.kotatsu.parsers.model.MangaParserSource") ||
			name.startsWith("org.koitharu.kotatsu.parsers.config.")
		) {
			return super.loadClass(name, resolve)
		}
		if (name == "org.koitharu.kotatsu.parsers.MangaParser" ||
			name == "org.koitharu.kotatsu.parsers.model.MangaParserSource" ||
			name.startsWith("org.koitharu.kotatsu.parsers.site.") ||
			name.startsWith("org.koitharu.kotatsu.parsers.core.") ||
			name.startsWith("org.koitharu.kotatsu.parsers.util.") ||
			name.startsWith("org.koitharu.kotatsu.parsers.MangaParserFactory")
		) {
			try {
				return findClass(name)
			} catch (_: ClassNotFoundException) {
			}
		}
		return super.loadClass(name, resolve)
	}
}

object DynamicParserManager {
	private val classLoaders = mutableMapOf<String, ClassLoader>()
	private val newParserMethods = mutableMapOf<String, Method>()
	private val tachiyomiSourceMap = mutableMapOf<String, TachiyomiSourceRuntime>()
	private val methodCache = ConcurrentHashMap<Pair<Method, Class<*>>, Method>()

	private data class TachiyomiSourceRuntime(
		val source: CatalogueSource,
	)

	@Throws(Exception::class)
	fun loadParsersFromDirectory(context: Context, pluginDir: File) {
		val cacheDir = context.codeCacheDir.absolutePath
		val parent = context.classLoader
		val sources = mutableListOf<MangaSource>()
		val methods = mutableMapOf<String, Method>()
		val tachiyomiRuntimes = mutableMapOf<String, TachiyomiSourceRuntime>()
		val loaders = mutableMapOf<String, ClassLoader>()
		if (!pluginDir.exists()) pluginDir.mkdirs()

		for (pluginFile in pluginDir.listFiles().orEmpty().sortedBy { it.name.lowercase(Locale.ROOT) }) {
			when (pluginFile.extension.lowercase(Locale.ROOT)) {
				"jar" -> loadJarPlugin(pluginFile, cacheDir, parent, sources, methods, loaders)
				"apk" -> loadTachiyomiPlugin(context, pluginFile, cacheDir, parent, sources, tachiyomiRuntimes, loaders)
			}
		}
		TachiyomiRepoStore.cleanupInstalledPluginMeta(
			context = context,
			installedFiles = pluginDir.listFiles().orEmpty().mapTo(HashSet()) { it.name },
		)

		MangaSourceRegistry.sources.clear()
		newParserMethods.clear()
		tachiyomiSourceMap.clear()
		methodCache.clear()
		classLoaders.clear()

		MangaSourceRegistry.sources.addAll(sources)
		newParserMethods.putAll(methods)
		tachiyomiSourceMap.putAll(tachiyomiRuntimes)
		classLoaders.putAll(loaders)
		MangaSourceRegistry.incrementVersion()
		MangaSourceRegistry.updates.tryEmit(Unit)
	}

	fun deletePlugin(context: Context, pluginFileName: String) {
		val dir = PluginFileLoader.pluginsDir(context)
		File(dir, pluginFileName).takeIf { it.exists() }?.delete()
		TachiyomiRepoStore.removeInstalledPluginMeta(context, pluginFileName)
		TachiyomiRepoStore.cleanupInstalledPluginMeta(
			context = context,
			installedFiles = dir.listFiles().orEmpty().mapTo(HashSet()) { it.name },
		)
		loadParsersFromDirectory(context, dir)
	}

	fun getInstalledPlugins(context: Context): List<String> =
		PluginFileLoader.pluginsDir(context)
			.listFiles()
			.orEmpty()
			.filter { it.extension.lowercase(Locale.ROOT) in SUPPORTED_PLUGIN_EXTENSIONS }
			.map { it.name }

	fun createParser(source: MangaSource, loaderContext: MangaLoaderContext, appContext: Context): MangaParser {
		val ctx = appContext.applicationContext
		val ps = resolvePluginSource(source)
			?: throw IllegalArgumentException(ctx.getString(R.string.plugin_not_found, source.name))
		RuntimeContext.init(ctx)
		RuntimeContext.installNetwork(
			client = loaderContext.httpClient,
			cloudflare = loaderContext.httpClient,
			userAgent = { runCatching { loaderContext.getDefaultUserAgent() }.getOrDefault("") },
			webSessionSync = { url ->
				(loaderContext as? MangaLoaderContextImpl)?.syncWebViewSession(ps, url)
			},
		)

		tachiyomiSourceMap[ps.name]?.let { runtime ->
			return TachiyomiMangaParser(
				source = ps,
				tachiyomiSource = runtime.source,
				sourceConfig = SourceSettings(ctx, ps),
			)
		}

		val cl = classLoaders[ps.jarName]
		val factoryMethod = newParserMethods[ps.name]
		if (cl == null || factoryMethod == null) {
			throw IllegalStateException(
				if (cl == null) ctx.getString(R.string.jar_not_loaded, ps.jarName)
				else ctx.getString(R.string.unknown_source, source.name),
			)
		}
		val enumC = cl.loadClass("org.koitharu.kotatsu.parsers.model.MangaParserSource")
		val constant = enumC.enumConstants?.firstOrNull { (it as MangaSource).name == ps.sourceName }
			?: throw IllegalArgumentException(ctx.getString(R.string.missing_in_plugin, ps.sourceName))
		val delegate = factoryMethod.invoke(null, constant, loaderContext)
			?: throw IllegalStateException(ctx.getString(R.string.loaded_null))
		return Proxy.newProxyInstance(
			MangaParser::class.java.classLoader,
			arrayOf(MangaParser::class.java),
		) { _, m, a ->
			when (m.name) {
				"toString" -> "PluginParser[${ps.name}]"
				"hashCode" -> delegate.hashCode()
				"equals" -> delegate == a?.firstOrNull()
				else -> {
					val args = a ?: emptyArray()
					try {
						val dm = methodCache.getOrPut(Pair(m, delegate.javaClass)) {
							findCompatibleMethod(ctx, delegate.javaClass, m.name, m.parameterTypes)
						}
						dm.invoke(delegate, *args)
					} catch (e: java.lang.reflect.InvocationTargetException) {
						throw e.targetException
					}
				}
			}
			} as MangaParser
	}

	fun addTachiyomiExtensionPreferences(source: MangaSource, screen: PreferenceScreen): Boolean {
		val ps = resolvePluginSource(source) ?: return false
		val runtime = tachiyomiSourceMap[ps.name] ?: return false
		val configurable = runtime.source as? ConfigurableSource ?: return false
		RuntimeContext.init(screen.context.applicationContext)
		val tempScreen = screen.preferenceManager.createPreferenceScreen(screen.context)
		configurable.setupPreferenceScreen(tempScreen)
		if (tempScreen.preferenceCount == 0) return false

		val extensionCategory = PreferenceCategory(screen.context).apply {
			key = TACHIYOMI_EXTENSION_OPTIONS_KEY
			title = screen.context.getString(R.string.extension_options)
			order = EXTENSION_OPTIONS_ORDER
			isIconSpaceReserved = false
		}
		screen.addPreference(extensionCategory)

		var order = EXTENSION_OPTIONS_ORDER + 1
		while (tempScreen.preferenceCount > 0) {
			val preference = tempScreen.getPreference(0)
			tempScreen.removePreference(preference)
			preference.order = maxOf(preference.order, order)
			preference.isIconSpaceReserved = false
			extensionCategory.addPreference(preference)
			order++
		}
		return extensionCategory.preferenceCount > 0
	}

	private fun loadJarPlugin(
		jar: File,
		cacheDir: String,
		parent: ClassLoader,
		sources: MutableList<MangaSource>,
		methods: MutableMap<String, Method>,
		loaders: MutableMap<String, ClassLoader>,
	) {
		jar.setReadOnly()
		val classLoader = PluginClassLoader(jar.absolutePath, cacheDir, null, parent)
		try {
			val factory = classLoader.loadClass("org.koitharu.kotatsu.parsers.MangaParserFactoryKt")
			val enumC = classLoader.loadClass("org.koitharu.kotatsu.parsers.model.MangaParserSource")
			val ctxC = classLoader.loadClass("org.koitharu.kotatsu.parsers.MangaLoaderContext")
			val newParser = factory.getMethod("newParser", enumC, ctxC)
			enumC.enumConstants?.forEach { item ->
				if (item is MangaSource) {
					val wrapped = PluginMangaSource(item, jar.name)
					sources += wrapped
					methods[wrapped.name] = newParser
				}
			}
			loaders[jar.name] = classLoader
		} catch (_: Exception) {
		}
	}

	private fun loadTachiyomiPlugin(
		context: Context,
		apk: File,
		cacheDir: String,
		parent: ClassLoader,
		sources: MutableList<MangaSource>,
		tachiyomiRuntimes: MutableMap<String, TachiyomiSourceRuntime>,
		loaders: MutableMap<String, ClassLoader>,
	) {
		val loaded = TachiyomiExtensionLoader.loadFromApk(
			context = context,
			apk = apk,
			optimizedDirectory = cacheDir,
			parent = parent,
		) ?: return
		val meta = loaded.extensionMeta
		val repoMeta = TachiyomiRepoStore.getInstalledPluginMeta(context, apk.name)
		val pluginGroup = repoMeta?.ownerTag ?: meta.packageName
		val classLoader = loaded.classLoader
		val extensionSources = loaded.sources
		if (extensionSources.isEmpty()) return

		extensionSources.forEach { source ->
			val descriptor = TachiyomiPluginSource(
				name = source.id.toString(),
				title = source.name,
				locale = source.lang,
				sourceId = source.id,
				extensionPackageName = meta.packageName,
				extensionClassName = meta.className,
				pluginFileName = apk.name,
				isNsfwSource = meta.isNsfw,
			)
			val wrapped = PluginMangaSource(descriptor, pluginGroup)
			sources += wrapped
			tachiyomiRuntimes[wrapped.name] = TachiyomiSourceRuntime(source)
		}
		loaders[apk.name] = classLoader
	}

	private fun resolvePluginSource(source: MangaSource): PluginMangaSource? {
		(source as? PluginMangaSource)?.let { return it }
		return MangaSourceRegistry.sources.firstOrNull {
			it is PluginMangaSource && (it.name == source.name || it.sourceName == source.name)
		} as? PluginMangaSource
	}

	private fun findCompatibleMethod(
		appContext: Context,
		target: Class<*>,
		name: String,
		paramTypes: Array<Class<*>>,
	): Method {
		runCatching { return target.getMethod(name, *paramTypes) }
		val candidates = target.methods.filter { it.name == name && it.parameterCount == paramTypes.size }
		return when (candidates.size) {
			0 -> throw NoSuchMethodException(
				appContext.getString(R.string.no_compatible_method, name, paramTypes.joinToString { it.name }),
			)
			1 -> candidates[0]
			else -> candidates.firstOrNull { matchesParams(it.parameterTypes, paramTypes) } ?: candidates[0]
		}
	}

	private fun matchesParams(a: Array<Class<*>>, b: Array<Class<*>>): Boolean {
		if (a.size != b.size) return false
		for (i in a.indices) {
			if (a[i].name != b[i].name) return false
		}
		return true
	}

	private val SUPPORTED_PLUGIN_EXTENSIONS = setOf("jar", "apk")
	private const val EXTENSION_OPTIONS_ORDER = 160
	private const val TACHIYOMI_EXTENSION_OPTIONS_KEY = "tachiyomi_extension_options"
}
