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
    private val methodCache = ConcurrentHashMap<Pair<Method, Class<*>>, Method>()

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
			// Fix A14+ storage compatibility
            jarFile.setReadOnly()

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
                            newMethods[wrappedSource.name] = newParserMethod
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
                it is org.draken.usagi.core.model.PluginMangaSource && (it.name == source.name || it.sourceName == source.name)
            } as? org.draken.usagi.core.model.PluginMangaSource
            ?: throw IllegalArgumentException("No plugin found for source: ${source.name}")

        val cl = classLoaders[pluginSource.jarName] ?: throw IllegalStateException("Parser JAR not loaded for ${pluginSource.jarName}.")
        val method = newParserMethods[pluginSource.name]
            ?: throw IllegalArgumentException("Unknown parser source: ${source.name}")

        val enumClass = cl.loadClass("org.koitharu.kotatsu.parsers.model.MangaParserSource")
        val fallbackConstant = enumClass.enumConstants?.firstOrNull { (it as MangaSource).name == pluginSource.sourceName }
            ?: throw IllegalArgumentException("Parser source missing in plugin JAR: ${pluginSource.sourceName}")
        val pluginParser = method.invoke(null, fallbackConstant, context)
            ?: throw IllegalStateException("Parser loaded as null")

        return Proxy.newProxyInstance(
            MangaParser::class.java.classLoader,
            arrayOf(MangaParser::class.java)
		) { _, invokedMethod, args ->
			// Handle Object methods directly
			when (invokedMethod.name) {
				"toString" -> return@newProxyInstance "PluginParser[${pluginSource.name}]"
				"hashCode" -> return@newProxyInstance pluginParser.hashCode()
				"equals" -> return@newProxyInstance (pluginParser == args?.firstOrNull())
			}
			val methodArgs = args ?: arrayOfNulls(0)
			try {
				val delegateMethod = methodCache.getOrPut(Pair(invokedMethod, pluginParser.javaClass)) {
                    findCompatibleMethod(pluginParser.javaClass, invokedMethod.name, invokedMethod.parameterTypes)
                }
				delegateMethod.invoke(pluginParser, *methodArgs)
			} catch (e: java.lang.reflect.InvocationTargetException) {
				throw e.targetException
			}
		} as MangaParser
    }

    /**
     * Find a method on the target class by name and parameter count,
     * handling cross-classloader type mismatches.
     */
    private fun findCompatibleMethod(targetClass: Class<*>, name: String, paramTypes: Array<Class<*>>): Method {
        // Try exact match first (works for methods with primitive/standard types)
        try {
            return targetClass.getMethod(name, *paramTypes)
        } catch (_: NoSuchMethodException) {
            // Fall through to fuzzy matching
        }
        // Fuzzy match: find by name + parameter count
        val candidates = targetClass.methods.filter {
            it.name == name && it.parameterCount == paramTypes.size
        }
        if (candidates.size == 1) {
            return candidates[0]
        }
        // Multiple overloads: try matching by parameter type names
        for (candidate in candidates) {
            val candidateTypes = candidate.parameterTypes
            var match = true
            for (i in paramTypes.indices) {
                if (candidateTypes[i].name != paramTypes[i].name) {
                    match = false
                    break
                }
            }
            if (match) return candidate
        }
        // Last resort: return first candidate
        return candidates.firstOrNull()
            ?: throw NoSuchMethodException("No compatible method found: $name(${paramTypes.joinToString { it.name }})")
    }
}
