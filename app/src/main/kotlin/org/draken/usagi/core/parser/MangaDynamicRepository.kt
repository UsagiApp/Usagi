package org.draken.usagi.core.parser

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.draken.usagi.R
import org.draken.usagi.core.exceptions.PluginLoadException
import org.draken.usagi.core.model.MangaSourceRegistry
import org.draken.usagi.core.model.PluginMangaSource
import org.draken.tsukimix.core.parser.tachiyomi.model.TachiyomiMangaSource as External
import tsuki.MangaLoaderContext
import tsuki.MangaParser
import tsuki.model.MangaSource
import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MangaDynamicRepository @Inject constructor(
	@ApplicationContext private val context: Context,
) {
	private val cL = ConcurrentHashMap<String, ClassLoader>()
	private val methodMap = ConcurrentHashMap<String, Method>()
	private val methodCache = ConcurrentHashMap<Pair<Method, Class<*>>, Method>()

	@Throws(Exception::class)
	fun load(pluginDir: File): List<PluginLoadException> {
		val dir = context.codeCacheDir.absolutePath
		val parent = context.classLoader
		val sources = mutableListOf<MangaSource>()
		val methods = mutableMapOf<String, Method>()
		val loaders = mutableMapOf<String, ClassLoader>()
		val e = mutableListOf<PluginLoadException>()

		if (!pluginDir.exists()) pluginDir.mkdirs()
		val cache = context.codeCacheDir
		cache.listFiles { it.name.startsWith("load_") && it.extension == "jar" }?.forEach {
			it.setWritable(true, true)
			it.delete()
		}

		val jarFiles = (pluginDir.listFiles { it.extension == "jar" } ?: emptyArray()).sortedBy { it.name }
		for (jar in jarFiles) {
			jar.setReadOnly()
			val temp = File(cache, "load_${jar.name.removeSuffix(".jar")}_${jar.lastModified()}.jar")
			try {
				jar.copyTo(temp, true)
				temp.setReadOnly()
			} catch (_: Throwable) { temp.delete() }
			val load = if (temp.exists()) temp else jar
			val cl = PluginClassLoader(load.absolutePath, dir, null, parent)
			try {
				val (factory, enumC, ctxC) = try {
					// New plugins use tsuki.* package
					Triple(
						cl.loadClass("tsuki.MangaParserFactoryKt"),
						cl.loadClass("tsuki.model.MangaParserSource"),
						cl.loadClass("tsuki.MangaLoaderContext")
					)
				} catch (_: ClassNotFoundException) {
					// Oldest plugins use org.koitharu.kotatsu.parsers.*
					Triple(
						cl.loadClass("org.koitharu.kotatsu.parsers.MangaParserFactoryKt"),
						cl.loadClass("org.koitharu.kotatsu.parsers.model.MangaParserSource"),
						cl.loadClass("org.koitharu.kotatsu.parsers.MangaLoaderContext")
					)
				}
				val parser = factory.getMethod("newParser", enumC, ctxC)
				enumC.enumConstants?.forEach { c ->
					if (c is MangaSource) {
						val w = PluginMangaSource(c, jar.name)
						sources.add(w)
						methods[w.name] = parser
					}
				}
				loaders[jar.name] = cl
			} catch (t: Throwable) {
				e.add(PluginLoadException(jar.name, t))
			}
		}
		methodMap.clear()
		methodCache.clear()
		cL.clear()
		methodMap.putAll(methods)
		cL.putAll(loaders)
		MangaSourceRegistry.publish(MangaSourceRegistry.sources.filterIsInstance<External>() + sources)
		return e
	}

	fun deletePlugin(name: String) {
		val dir = PluginFileLoader.pluginsDir(context)
		this.delete(name)
		load(dir)
	}

	fun delete(name: String) {
		val file = File(getDir(), name)
		if (file.exists()) {
			file.setWritable(true, true)
			file.delete()
		}
	}

	fun get(): List<String> =
		PluginFileLoader.pluginsDir(context).listFiles { it.extension == "jar" }?.map { it.name } ?: emptyList()

	fun getDir() = PluginFileLoader.pluginsDir(context)

	/** Should not crash under any circumstances */
	@Throws(IOException::class)
	fun create(source: MangaSource, loaderContext: MangaLoaderContext): MangaParser {
		val ps = resolve(source)
			?: throw IOException(context.getString(R.string.unsupported_source))
		val cl = cL[ps.jarName]
		val factoryMethod = methodMap[ps.name]
		if (cl == null || factoryMethod == null) throw IOException(context.getString(R.string.load_failed))
		val enumC = runCatching { cl.loadClass("tsuki.model.MangaParserSource") }
			.getOrElse { cl.loadClass("org.koitharu.kotatsu.parsers.model.MangaParserSource") }
		val constant = enumC.enumConstants?.firstOrNull { (it as MangaSource).name == ps.sourceName }
			?: throw IOException(context.getString(R.string.unsupported_source))
		val delegate = try {
			factoryMethod.invoke(null, constant, loaderContext)
		} catch (e: InvocationTargetException) {
			throw IOException(context.getString(R.string.error_occurred)
				+ ": ${e.targetException.message}", e.targetException)
		} catch (e: IOException) { throw e } catch (e: Throwable) {
			throw IOException(context.getString(R.string.error_occurred) + ": ${e.message}", e)
		}
		return Proxy.newProxyInstance(MangaParser::class.java.classLoader, arrayOf(MangaParser::class.java)) { _, m, a ->
			when (m.name) {
				"toString" -> "PluginParser[${ps.name}]"
				"hashCode" -> delegate.hashCode()
				"equals" -> delegate == a?.firstOrNull()
				else -> {
					val args = a ?: emptyArray()
					try {
						val dm = methodCache.getOrPut(Pair(m, delegate.javaClass)) {
							findMethod(delegate.javaClass, m.name, m.parameterTypes)
						}
						dm.invoke(delegate, *args)
					} catch (e: InvocationTargetException) {
						throw e.targetException
					}
				}
			}
		} as MangaParser
	}

	private fun resolve(source: MangaSource): PluginMangaSource? {
		(source as? PluginMangaSource)?.let { return it }
		val sourceClassLoader = source.javaClass.classLoader
		val jarName = cL.entries.firstOrNull { it.value == sourceClassLoader }?.key
		val snap = MangaSourceRegistry.snapshot
		if (jarName != null) {
			val matching = snap.sources.firstOrNull {
				it is PluginMangaSource && it.jarName == jarName && it.sourceName == source.name
			} as? PluginMangaSource
			if (matching != null) return matching
		}
		return MangaSourceRegistry.resolveByName(source.name) as? PluginMangaSource
	}

	private fun findMethod(target: Class<*>, name: String, paramTypes: Array<Class<*>>): Method {
		runCatching { return target.getMethod(name, *paramTypes) }
		val c = target.methods.filter { it.name == name && it.parameterCount == paramTypes.size }
		return when (c.size) {
			0 -> throw NoSuchMethodException(context.getString(R.string.operation_not_supported))
			1 -> c[0]
			else -> c.firstOrNull { matchesParams(it.parameterTypes, paramTypes) } ?: c[0]
		}
	}

	private fun matchesParams(a: Array<Class<*>>, b: Array<Class<*>>): Boolean {
		if (a.size != b.size) return false
		for (i in a.indices) if (a[i].name != b[i].name) return false
		return true
	}
}
