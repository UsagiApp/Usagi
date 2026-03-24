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

class PluginClassLoader(
    dexPath: String,
    optimizedDirectory: String?,
    librarySearchPath: String?,
    parent: ClassLoader
) : DexClassLoader(dexPath, optimizedDirectory, librarySearchPath, parent) {
    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        /*
			Force the JAR to delegate these shared API classes to Usagi (parent classloader)
			This ensures instances like Manga, MangaSource, and LinkResolver are the exact same Class type
			in both the JAR and Usagi, preventing ClassCastException or IllegalArgumentException
        */
        if (name == "org.koitharu.kotatsu.parsers.util.LinkResolver" ||
            name.startsWith("org.koitharu.kotatsu.parsers.util.LinkResolver$") ||
            name == "org.koitharu.kotatsu.parsers.MangaLoaderContext" ||
            (name.startsWith("org.koitharu.kotatsu.parsers.model.")
				&& name != "org.koitharu.kotatsu.parsers.model.MangaParserSource") ||
            name.startsWith("org.koitharu.kotatsu.parsers.config.")
        ) {
            return super.loadClass(name, resolve)
        }

        /*
			Force the JAR to load these specific interfaces and internal implementations from itself
			This prevents JVM ABI crashes (like missing internal methods due to Kotlin module mangling
			or interface signature mismatches)
        */
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
    private var classLoader: ClassLoader? = null
    private val newParserMethods = mutableMapOf<String, Method>()

    @Throws(Exception::class)
    fun loadParsersFromJar(context: Context, jarFile: File) {
        val cacheDir = context.codeCacheDir.absolutePath
        val parentClassLoader = context.classLoader
        val dexClassLoader = PluginClassLoader(
            jarFile.absolutePath,
            cacheDir,
            null,
            parentClassLoader
        )

        val factoryClass = dexClassLoader.loadClass("org.koitharu.kotatsu.parsers.MangaParserFactoryKt")
        val enumClass = dexClassLoader.loadClass("org.koitharu.kotatsu.parsers.model.MangaParserSource")

        MangaSourceRegistry.sources.clear()
        newParserMethods.clear()
        val enumConstants = enumClass.enumConstants
        if (enumConstants != null) {
            val mangaLoaderContextClass = dexClassLoader.loadClass("org.koitharu.kotatsu.parsers.MangaLoaderContext")
            val newParserMethod = factoryClass.getMethod("newParser", enumClass, mangaLoaderContextClass)
            for (constant in enumConstants) {
                if (constant is MangaSource) {
                    MangaSourceRegistry.sources.add(constant)
                    newParserMethods[constant.name] = newParserMethod
                }
            }
        }
        classLoader = dexClassLoader
        MangaSourceRegistry.updates.tryEmit(Unit)
    }

    fun createParser(source: MangaSource, context: MangaLoaderContext): MangaParser {
        val cl = classLoader ?: throw IllegalStateException("No parser JAR loaded. Please upload a kotatsu-parsers DEX plugin.")
        val method = newParserMethods[source.name] ?: throw IllegalArgumentException("Unknown parser source: ${source.name}")

        // Find the actual enum constant belonging to the JAR's classloader to invoke newParser
        val enumClass = cl.loadClass("org.koitharu.kotatsu.parsers.model.MangaParserSource")
        val fallbackConstant = enumClass.enumConstants?.firstOrNull { (it as MangaSource).name == source.name }
            ?: throw IllegalArgumentException("Parser source missing in plugin JAR: ${source.name}")
        val pluginParser = method.invoke(null, fallbackConstant, context)
            ?: throw IllegalStateException("Parser loaded as null")

        // Wrap the JAR parser in a dynamic proxy that implements Usagi's MangaParser interface.
        // This bridges calls from Usagi directly into the JAR parser without triggering ClassCastExceptions.
        return Proxy.newProxyInstance(
            MangaParser::class.java.classLoader,
            arrayOf(MangaParser::class.java)
		) { _, invokedMethod, args ->
			val methodArgs = args ?: emptyArray()
			try {
				val delegateMethod = pluginParser.javaClass.getMethod(invokedMethod.name, *invokedMethod.parameterTypes)
				delegateMethod.invoke(pluginParser, *methodArgs)
			} catch (e: java.lang.reflect.InvocationTargetException) {
				throw e.targetException
			}
		} as MangaParser
    }
}
