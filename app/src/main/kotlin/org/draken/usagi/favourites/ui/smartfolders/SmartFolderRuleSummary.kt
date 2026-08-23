package org.draken.usagi.favourites.ui.smartfolders

import android.content.Context
import org.draken.usagi.R
import org.draken.usagi.favourites.domain.SmartFolderContent
import org.draken.usagi.favourites.domain.SmartFolderDevice
import org.draken.usagi.favourites.domain.SmartFolderRules

data class SmartFolderRuleSummary(
	val parts: List<Part>,
) {
	sealed interface Part {
		data class Count(
			val dimension: Dimension,
			val count: Int,
		) : Part

		data class Content(
			val value: SmartFolderContent,
		) : Part

		data class Device(
			val value: SmartFolderDevice,
		) : Part
	}

	enum class Dimension {
		SOURCES,
		CATEGORIES,
		TAGS,
	}

	companion object {
		fun from(rules: SmartFolderRules): SmartFolderRuleSummary =
			SmartFolderRuleSummary(
				buildList {
					if (rules.sources.isNotEmpty()) add(Part.Count(Dimension.SOURCES, rules.sources.size))
					if (rules.categoryIds.isNotEmpty()) add(Part.Count(Dimension.CATEGORIES, rules.categoryIds.size))
					if (rules.tagIds.isNotEmpty()) add(Part.Count(Dimension.TAGS, rules.tagIds.size))
					if (rules.content != SmartFolderContent.ANY) add(Part.Content(rules.content))
					if (rules.device != SmartFolderDevice.ANY) add(Part.Device(rules.device))
				},
			)
	}
}

fun SmartFolderRules.formatSummary(context: Context): String =
	SmartFolderRuleSummary
		.from(this)
		.parts
		.joinToString(" · ") { part ->
			when (part) {
				is SmartFolderRuleSummary.Part.Count -> {
					val dimension =
						when (part.dimension) {
							SmartFolderRuleSummary.Dimension.SOURCES -> R.string.smart_folder_sources
							SmartFolderRuleSummary.Dimension.CATEGORIES -> R.string.categories
							SmartFolderRuleSummary.Dimension.TAGS -> R.string.genres
						}
					context.getString(dimension) + " · " + context.resources.getQuantityString(R.plurals.items, part.count, part.count)
				}

				is SmartFolderRuleSummary.Part.Content -> {
					context.getString(if (part.value == SmartFolderContent.SFW) R.string.sfw else R.string.nsfw)
				}

				is SmartFolderRuleSummary.Part.Device -> {
					context.getString(
						if (part.value == SmartFolderDevice.ON_DEVICE) {
							R.string.on_device
						} else {
							R.string.smart_folder_not_on_device
						},
					)
				}
			}
		}.ifEmpty { context.getString(R.string.any) }
