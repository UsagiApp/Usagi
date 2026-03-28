package org.draken.usagi.core.parser

import android.content.Context
import dalvik.system.DexClassLoader
import org.draken.usagi.R
import org.draken.usagi.core.model.MangaSourceRegistry
import org.draken.usagi.core.model.PluginMangaSource
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaParser
import org.koitharu.kotatsu.parsers.model.MangaSource
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Proxy
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
    private val methodCache = ConcurrentHashMap<Pair<Method, Class<*>>, Method>()

    @Throws(Exception::class)
    fun loadParsersFromDirectory(context: Context, pluginDir: File) {
        val cacheDir = context.codeCacheDir.absolutePath
        val parent = context.classLoader
        val sources = mutableListOf<MangaSource>()
        val methods = mutableMapOf<String, Method>()
        val loaders = mutableMapOf<String, ClassLoader>()
        if (!pluginDir.exists()) pluginDir.mkdirs()
        for (jar in pluginDir.listFiles { it.extension == "jar" } ?: emptyArray()) {
            jar.setReadOnly()
            val cl = PluginClassLoader(jar.absolutePath, cacheDir, null, parent)
            try {
                val factory = cl.loadClass("org.koitharu.kotatsu.parsers.MangaParserFactoryKt")
                val enumC = cl.loadClass("org.koitharu.kotatsu.parsers.model.MangaParserSource")
                val ctxC = cl.loadClass("org.koitharu.kotatsu.parsers.MangaLoaderContext")
                val newParser = factory.getMethod("newParser", enumC, ctxC)
                enumC.enumConstants?.forEach { c ->
                    if (c is MangaSource) {
                        val w = PluginMangaSource(c, jar.name)
                        sources.add(w)
                        methods[w.name] = newParser
                    }
                }
                loaders[jar.name] = cl
            } catch (_: Exception) {
            }
        }
        MangaSourceRegistry.sources.clear()
        newParserMethods.clear()
        methodCache.clear()
        classLoaders.clear()
        MangaSourceRegistry.sources.addAll(sources)
        newParserMethods.putAll(methods)
        classLoaders.putAll(loaders)
        MangaSourceRegistry.incrementVersion()
        MangaSourceRegistry.updates.tryEmit(Unit)
    }

    fun deletePlugin(context: Context, jarName: String) {
        val dir = PluginFileLoader.pluginsDir(context)
        File(dir, jarName).takeIf { it.exists() }?.delete()
        loadParsersFromDirectory(context, dir)
    }

    fun getInstalledPlugins(context: Context): List<String> =
        PluginFileLoader.pluginsDir(context).listFiles { it.extension == "jar" }?.map { it.name } ?: emptyList()

    fun createParser(source: MangaSource, loaderContext: MangaLoaderContext, appContext: Context): MangaParser {
        val ctx = appContext.applicationContext
        val ps = resolvePluginSource(source)
            ?: throw IllegalArgumentException(ctx.getString(R.string.plugin_not_found, source.name))
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
        val c = target.methods.filter { it.name == name && it.parameterCount == paramTypes.size }
        return when (c.size) {
            0 -> throw NoSuchMethodException(
                appContext.getString(R.string.no_compatible_method, name, paramTypes.joinToString { it.name }),
            )
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
