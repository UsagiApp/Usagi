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
import org.draken.tsukimix.core.parser.tachiyomi.DirectTachiyomiExtensionManager
import org.draken.tsukimix.core.parser.tachiyomi.DirectTachiyomiInstalled
import org.draken.usagi.R
import org.draken.usagi.core.parser.external.ExternalMangaSource
import org.draken.usagi.core.util.ext.getDisplayName
import org.draken.usagi.core.util.ext.toLocale
import org.draken.usagi.core.util.ext.toLocaleOrNull
import tsuki.model.ContentType
import tsuki.model.MangaSource
import tsuki.util.splitTwoParts
import java.net.URI
import java.util.Locale
import org.draken.tsukimix.core.parser.tachiyomi.model.TachiyomiMangaSource as ExternalSource

data class PluginMangaSource(
	val delegate: MangaSource,
	val jarName: String,
) : MangaSource {
	override val name: String
		get() = "$jarName:${delegate.name}"

	val sourceName: String
		get() = delegate.name

	override val locale: String
		get() = delegate.locale

	override val contentType: ContentType
		get() = delegate.contentType

	override val title: String
		get() = delegate.title

	override val isBroken: Boolean
		get() = delegate.isBroken
}

/**
 * Keeps the external plugin label for sources loaded from direct Tachiyomi DEX artifacts.
 *
 * Direct artifacts preserve their repository URL in installation metadata, whereas the source
 * model itself only exposes the individual extension label. The map lets shared source UI show
 * the repository/plugin identity consistently without changing source IDs or parser behavior.
 */
object DirectTachiyomiPluginMetadata {
	@Volatile
	private var namesByPackage: Map<String, String> = emptyMap()

	fun update(
		installed: Collection<DirectTachiyomiInstalled>,
		repositoryName: (String) -> String?,
	) {
		namesByPackage =
			buildMap {
				installed.forEach { record ->
					val name = repositoryName(record.repositoryUrl)?.trim()?.takeIf { it.isNotBlank() } ?: deriveName(record.repositoryUrl)
					if (name != null) put(record.packageName, name)
				}
			}
	}

	fun get(packageName: String): String? = namesByPackage[packageName]

	private fun deriveName(repositoryUrl: String): String? =
		runCatching {
			val uri = URI(repositoryUrl)
			val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
			when {
				host.endsWith(".github.io") -> {
					host.removeSuffix(".github.io")
				}

				else -> {
					uri.path
						.trim('/')
						.substringBefore('/')
						.takeIf { it.isNotBlank() }
						?: host.takeIf { it.isNotBlank() }
				}
			}
		}.getOrNull()
}

data object LocalMangaSource : MangaSource {
	override val name = "LOCAL"
}

data object UnknownMangaSource : MangaSource {
	override val name = "UNKNOWN"
}

data class UnresolvedMangaSource(
	override val name: String,
) : MangaSource {
	override val locale: String get() = ""
	override val contentType: ContentType get() = ContentType.OTHER
	override val title: String get() = name
	override val isBroken: Boolean get() = true
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
	} else if (name.startsWith("EXTERNAL_")) {
		DirectTachiyomiExtensionManager.getByName(name)?.let { return it }
		org.draken.tsukimix.core.parser.tachiyomi.TachiyomiExtensionManager
			.getByName(name)
			?.let { return it } // tachi
	}
	MangaSourceRegistry.resolveByName(name)?.let { return it }
	// Backward compatibility for loaded database items saved as '1.jar:MANGADEX'
	if (name.contains(':')) {
		val raw = name.substringAfter(":")
		MangaSourceRegistry.resolveByName(raw)?.let { return it }
	}
	return UnresolvedMangaSource(name)
}

fun String.toBackupSourceName(): String =
	when (val src = MangaSource(this)) {
		is PluginMangaSource -> src.sourceName
		is UnresolvedMangaSource -> if (this.contains(':') && !this.startsWith("content:")) this.substringAfter(':') else this
		else -> this
	}

fun Collection<String>.toMangaSources() = map(::MangaSource)

fun MangaSource.isNsfw(): Boolean = contentType == ContentType.HENTAI

fun MangaSource.resolve(): MangaSource =
	if (this is UnresolvedMangaSource) {
		val resolved = MangaSource(this.name)
		if (resolved !is UnresolvedMangaSource) resolved else this
	} else {
		this
	}

@get:StringRes
val ContentType.titleResId
	get() =
		when (this) {
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

tailrec fun MangaSource.unwrap(): MangaSource =
	when (this) {
		is MangaSourceInfo -> mangaSource.unwrap()
		is PluginMangaSource -> delegate.unwrap()
		is ExternalSource -> this
		else -> this
	}

fun MangaSource.getLocale(): Locale? = locale.toLocaleOrNull()

fun MangaSource.isExternalSource(): Boolean =
	when (val source = unwrap()) {
		is ExternalMangaSource, is ExternalSource -> true
		else -> false
	}

fun MangaSource.externalPackageName(): String? =
	when (val source = unwrap()) {
		is ExternalMangaSource -> source.packageName
		is ExternalSource -> source.pkgName
		else -> null
	}

fun MangaSource.getSummary(context: Context): String? {
	val source = unwrap()
	val baseSummary =
		when (source) {
			is ExternalSource -> {
				val rating = context.getString(if (source.isNsfw) R.string.rating_adult else R.string.rating_safe)
				val language =
					if (source.locale.equals("all", ignoreCase = true)) {
						context.getString(R.string.various_languages)
					} else {
						source.locale.toLocaleOrNull().getDisplayName(context)
					}
				val extension =
					if (source.isPreInstalledApk) {
						context.getString(R.string.tachiyomi_apk)
					} else {
						DirectTachiyomiPluginMetadata.get(source.pkgName)
							?: source.extensionName
							?: context.getString(R.string.external_source)
					}

				"$rating, $language • ${context.getString(R.string.external_source)} ($extension)"
			}

			is ExternalMangaSource -> {
				"${context.getString(R.string.external_source)} (${source.resolveName(context)})"
			}

			else -> {
				when {
					this === LocalMangaSource || this === TestMangaSource || this === UnknownMangaSource -> {
						null
					}

					else -> {
						val type = context.getString(contentType.titleResId)
						val loc = locale.toLocale().getDisplayName(context)
						context.getString(R.string.source_summary_pattern, type, loc)
					}
				}
			}
		}
	val pluginSource =
		when (this) {
			is PluginMangaSource -> this
			is MangaSourceInfo -> mangaSource as? PluginMangaSource
			else -> null
		}
	return if (pluginSource != null && baseSummary != null) {
		"$baseSummary • ${pluginSource.jarName}"
	} else {
		pluginSource?.jarName ?: baseSummary
	}
}

fun MangaSource.getTitle(context: Context): String =
	when {
		this === LocalMangaSource -> context.getString(R.string.local_storage)
		this === TestMangaSource -> context.getString(R.string.test_parser)
		this is ExternalMangaSource -> this.resolveName(context)
		this is MangaSourceInfo && mangaSource is ExternalMangaSource -> mangaSource.resolveName(context)
		this is ExternalSource -> this.displayName
		this === UnknownMangaSource -> context.getString(R.string.unknown)
		else -> title
	}

fun SpannableStringBuilder.appendIcon(
	textView: TextView,
	@DrawableRes resId: Int,
): SpannableStringBuilder {
	val icon = ContextCompat.getDrawable(textView.context, resId) ?: return this
	icon.setTintList(textView.textColors)
	val size = textView.lineHeight
	icon.setBounds(0, 0, size, size)
	val alignment =
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			ImageSpan.ALIGN_CENTER
		} else {
			ImageSpan.ALIGN_BOTTOM
		}
	return inSpans(ImageSpan(icon, alignment)) { append(' ') }
}
