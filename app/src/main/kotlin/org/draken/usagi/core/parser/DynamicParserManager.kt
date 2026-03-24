package org.draken.usagi.core.parser

import android.content.Context
import dalvik.system.DexClassLoader
import org.draken.usagi.core.model.MangaSourceRegistry
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
    parent: ClassLoader
) : DexClassLoader(dexPath, optimizedDirectory, librarySearchPath, parent) {
    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        if (name == "org.koitharu.kotatsu.parsers.util.LinkResolver" ||
            name.startsWith("org.koitharu.kotatsu.parsers.util.LinkResolver$") ||
            name == "org.koitharu.kotatsu.parsers.MangaLoaderContext" ||
            (name.startsWith("org.koitharu.kotatsu.parsers.model.")
				&& name != "org.koitharu.kotatsu.parsers.model.MangaParserSource") ||
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
                // Ignore and fall through to super
            }
        }
        return super.loadClass(name, resolve)
    }
}

object DynamicParserManager {
    private val classLoaders = mutableMapOf<String, ClassLoader>()
    private val newParserMethods = mutableMapOf<String, Method>()
    private val methodCache = ConcurrentHashMap<Method, Method>()

    @Throws(Exception::class)
    fun loadParsersFromDirectory(context: Context, pluginDir: File) {
        val cacheDir = context.codeCacheDir.absolutePath
        val parentClassLoader = context.classLoader

        val newSources = mutableListOf<MangaSource>()
        val newMethods = mutableMapOf<String, Method>()
        val newClassLoaders = mutableMapOf<String, ClassLoader>()

        if (!pluginDir.exists()) pluginDir.mkdirs()

        val jarFiles = pluginDir.listFiles { file -> file.extension == "jar" } ?: emptyArray()

        for (jarFile in jarFiles) {
            val dexClassLoader = PluginClassLoader(
                jarFile.absolutePath,
                cacheDir,
                null,
                parentClassLoader
            )

            try {
                val factoryClass = dexClassLoader.loadClass("org.koitharu.kotatsu.parsers.MangaParserFactoryKt")
                val enumClass = dexClassLoader.loadClass("org.koitharu.kotatsu.parsers.model.MangaParserSource")

                val enumConstants = enumClass.enumConstants
                if (enumConstants != null) {
                    val mangaLoaderContextClass = dexClassLoader.loadClass("org.koitharu.kotatsu.parsers.MangaLoaderContext")
                    val newParserMethod = factoryClass.getMethod("newParser", enumClass, mangaLoaderContextClass)

                    for (constant in enumConstants) {
                        if (constant is MangaSource) {
                            val wrappedSource = org.draken.usagi.core.model.PluginMangaSource(constant, jarFile.name)
                            newSources.add(wrappedSource)
                            newMethods[constant.name + ":" + jarFile.name] = newParserMethod
                        }
                    }
                }
                newClassLoaders[jarFile.name] = dexClassLoader
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        MangaSourceRegistry.sources.clear()
        newParserMethods.clear()
        methodCache.clear()
        classLoaders.clear()
        
        MangaSourceRegistry.sources.addAll(newSources)
        newParserMethods.putAll(newMethods)
        classLoaders.putAll(newClassLoaders)

        MangaSourceRegistry.incrementVersion()
        MangaSourceRegistry.updates.tryEmit(Unit)
    }

    fun deletePlugin(context: Context, jarName: String) {
        val pluginDir = File(context.filesDir, "plugins")
        val jarFile = File(pluginDir, jarName)
        if (jarFile.exists()) {
            jarFile.delete()
        }
        loadParsersFromDirectory(context, pluginDir)
    }

    fun getInstalledPlugins(context: Context): List<String> {
        val pluginDir = File(context.filesDir, "plugins")
        return pluginDir.listFiles { file -> file.extension == "jar" }?.map { it.name } ?: emptyList()
    }

    fun createParser(source: MangaSource, context: MangaLoaderContext): MangaParser {
        val pluginSource = (source as? org.draken.usagi.core.model.PluginMangaSource)
            ?: MangaSourceRegistry.sources.firstOrNull { 
                it is org.draken.usagi.core.model.PluginMangaSource && it.delegate.name == source.name 
            } as? org.draken.usagi.core.model.PluginMangaSource
            ?: throw IllegalArgumentException("No plugin found for source: ${source.name}")

        val cl = classLoaders[pluginSource.jarName] ?: throw IllegalStateException("Parser JAR not loaded for ${pluginSource.jarName}.")
        val method = newParserMethods[pluginSource.delegate.name + ":" + pluginSource.jarName] 
            ?: throw IllegalArgumentException("Unknown parser source: ${source.name}")

        val enumClass = cl.loadClass("org.koitharu.kotatsu.parsers.model.MangaParserSource")
        val fallbackConstant = enumClass.enumConstants?.firstOrNull { (it as MangaSource).name == pluginSource.delegate.name }
            ?: throw IllegalArgumentException("Parser source missing in plugin JAR: ${pluginSource.delegate.name}")
        val pluginParser = method.invoke(null, fallbackConstant, context)
            ?: throw IllegalStateException("Parser loaded as null")

        return Proxy.newProxyInstance(
            MangaParser::class.java.classLoader,
            arrayOf(MangaParser::class.java)
		) { _, invokedMethod, args ->
			val methodArgs = args ?: emptyArray()
			try {
                val delegateMethod = methodCache.getOrPut(invokedMethod) {
                    pluginParser.javaClass.getMethod(invokedMethod.name, *invokedMethod.parameterTypes)
                }
				delegateMethod.invoke(pluginParser, *methodArgs)
			} catch (e: java.lang.reflect.InvocationTargetException) {
				throw e.targetException
			}
		} as MangaParser
    }
}
