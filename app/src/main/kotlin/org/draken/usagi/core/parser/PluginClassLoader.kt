package org.draken.usagi.core.parser

import dalvik.system.DexClassLoader

class PluginClassLoader(
	dexPath: String,
	optimizedDirectory: String?,
	librarySearchPath: String?,
	parent: ClassLoader,
) : DexClassLoader(dexPath, optimizedDirectory, librarySearchPath, parent) {
	override fun loadClass(name: String, resolve: Boolean): Class<*> {
		if (name == "org.koitharu.kotatsu.parsers.MangaLoaderContext" ||
			name == "org.koitharu.kotatsu.parsers.MangaParserAuthProvider" ||
			name.startsWith("org.koitharu.kotatsu.parsers.config.") ||
			name.startsWith("org.koitharu.kotatsu.parsers.exception.") ||
			(name.startsWith("org.koitharu.kotatsu.parsers.model.") &&
				name != "org.koitharu.kotatsu.parsers.model.MangaParserSource")
		) { return super.loadClass(name, resolve) }
		if (name.startsWith("org.koitharu.kotatsu.parsers.") ||
			name.startsWith("org.koitharu.kotatsu.core.parser.") ||
			name.startsWith("eu.kanade.tachiyomi.") ||
			name.startsWith("uy.kohesive.injekt.") ||
			name.startsWith("rx.") ||
			name.startsWith("keiyoushi.")
		) { return runCatching { findClass(name) }.getOrElse { super.loadClass(name, resolve) } }
		return super.loadClass(name, resolve)
	}
}
