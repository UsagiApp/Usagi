package org.draken.usagi.core.model

import android.content.Context
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.style.ImageSpan
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.text.inSpans
import org.draken.usagi.R
import org.draken.usagi.core.parser.external.ExternalMangaSource
import org.draken.usagi.core.util.ext.getDisplayName
import org.draken.usagi.core.util.ext.toLocale
import org.draken.usagi.core.util.ext.toLocaleOrNull
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.util.splitTwoParts
import java.util.Locale

data class PluginMangaSource(val delegate: MangaSource, val jarName: String) : MangaSource {
    /** Unique name combining jar and source for DB/registry identification */
    override val name: String
        get() = "$jarName:${delegate.name}"

    /** Original source name from the plugin enum */
    val sourceName: String
        get() = delegate.name

    val displayName: String
        get() = "${delegate.name} [$jarName]"

    override val locale: String
        get() = try { delegate.javaClass.getMethod("getLocale").invoke(delegate) as? String ?: "" } catch (_: Exception) { "" }

    override val contentType: ContentType
        get() = try { delegate.javaClass.getMethod("getContentType").invoke(delegate) as? ContentType ?: ContentType.MANGA } catch (_: Exception) { ContentType.MANGA }

    override val title: String
        get() = try { delegate.javaClass.getMethod("getTitle").invoke(delegate) as? String ?: name } catch (_: Exception) { name }

    override val isBroken: Boolean
        get() = try { delegate.javaClass.getMethod("isBroken").invoke(delegate) as? Boolean ?: false } catch (_: Exception) { false }
}

data object LocalMangaSource : MangaSource {
	override val name = "LOCAL"
}

data object UnknownMangaSource : MangaSource {
	override val name = "UNKNOWN"
}

data object TestMangaSource : MangaSource {
	override val name = "TEST"
}

fun MangaSource(name: String?): MangaSource {
	when (name ?: return UnknownMangaSource) {
		UnknownMangaSource.name -> return UnknownMangaSource
		LocalMangaSource.name -> return LocalMangaSource
		TestMangaSource.name -> return TestMangaSource
	}
	if (name.startsWith("content:")) {
		val parts = name.substringAfter(':').splitTwoParts('/') ?: return UnknownMangaSource
		return ExternalMangaSource(packageName = parts.first, authority = parts.second)
	}
	// Exact match on compound name (e.g., "1.jar:MANGADEX")
	MangaSourceRegistry.sources.forEach {
		if (it.name == name) return it
	}
	// Fallback: match old-format pure source name (e.g., "MANGADEX") against sourceName
	MangaSourceRegistry.sources.forEach {
		if (it is PluginMangaSource && it.sourceName == name) return it
	}
	return UnknownMangaSource
}

fun Collection<String>.toMangaSources() = map(::MangaSource)

fun MangaSource.isNsfw(): Boolean = contentType == ContentType.HENTAI

@get:StringRes
val ContentType.titleResId
	get() = when (this) {
		ContentType.MANGA -> R.string.content_type_manga
		ContentType.HENTAI -> R.string.content_type_hentai
		ContentType.COMICS -> R.string.content_type_comics
		ContentType.OTHER -> R.string.content_type_other
		ContentType.MANHWA -> R.string.content_type_manhwa
		ContentType.MANHUA -> R.string.content_type_manhua
		ContentType.NOVEL -> R.string.content_type_novel
		ContentType.ONE_SHOT -> R.string.content_type_one_shot
		ContentType.DOUJINSHI -> R.string.content_type_doujinshi
		ContentType.IMAGE_SET -> R.string.content_type_image_set
		ContentType.ARTIST_CG -> R.string.content_type_artist_cg
		ContentType.GAME_CG -> R.string.content_type_game_cg
	}

tailrec fun MangaSource.unwrap(): MangaSource = when (this) {
    is MangaSourceInfo -> mangaSource.unwrap()
    is PluginMangaSource -> delegate.unwrap()
    else -> this
}

fun MangaSource.getLocale(): Locale? = locale.toLocaleOrNull()

fun MangaSource.getSummary(context: Context): String? {
	val baseSummary = when {
		this is MangaSourceInfo && mangaSource is ExternalMangaSource -> context.getString(R.string.external_source)
		this is ExternalMangaSource -> context.getString(R.string.external_source)
		this === LocalMangaSource || this === TestMangaSource || this === UnknownMangaSource -> null
		else -> {
			val type = context.getString(contentType.titleResId)
			val loc = locale.toLocale().getDisplayName(context)
			context.getString(R.string.source_summary_pattern, type, loc)
		}
	}
	val pluginSource = when (this) {
		is PluginMangaSource -> this
		is MangaSourceInfo -> mangaSource as? PluginMangaSource
		else -> null
	}
	return if (pluginSource != null && baseSummary != null) {
		"$baseSummary • ${pluginSource.jarName}"
	} else if (pluginSource != null) {
		pluginSource.jarName
	} else {
		baseSummary
	}
}

fun MangaSource.getTitle(context: Context): String = when {
	this === LocalMangaSource -> context.getString(R.string.local_storage)
	this === TestMangaSource -> context.getString(R.string.test_parser)
	this is ExternalMangaSource -> this.resolveName(context)
	this is MangaSourceInfo && mangaSource is ExternalMangaSource -> (mangaSource as ExternalMangaSource).resolveName(context)
	this === UnknownMangaSource -> context.getString(R.string.unknown)
	else -> title
}

fun SpannableStringBuilder.appendIcon(textView: TextView, @DrawableRes resId: Int): SpannableStringBuilder {
	val icon = ContextCompat.getDrawable(textView.context, resId) ?: return this
	icon.setTintList(textView.textColors)
	val size = textView.lineHeight
	icon.setBounds(0, 0, size, size)
	val alignment = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
		ImageSpan.ALIGN_CENTER
	} else {
		ImageSpan.ALIGN_BOTTOM
	}
	return inSpans(ImageSpan(icon, alignment)) { append(' ') }
}
