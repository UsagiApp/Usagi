package org.draken.usagi.core.parser.external

import android.content.Context
import tsuki.model.ContentType
import tsuki.model.MangaSource

data class ExternalMangaSource(
	val packageName: String,
	val authority: String,
) : MangaSource {

	override val name: String
		get() = "content:$packageName/$authority"

    override val title: String
        get() = name

    override val locale: String
        get() = ""

    override val contentType: ContentType
        get() = ContentType.OTHER

    override val isBroken: Boolean
        get() = false

	private var cachedName: String? = null

	fun isAvailable(context: Context): Boolean {
		return context.packageManager.resolveContentProvider(authority, 0)?.isEnabled == true
	}

	fun resolveName(context: Context): String {
		cachedName?.let {
			return it
		}
		val pm = context.packageManager
		val info = pm.resolveContentProvider(authority, 0)
		return info?.loadLabel(pm)?.toString()?.also {
			cachedName = it
		} ?: authority
	}
}
