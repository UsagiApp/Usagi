package org.draken.usagi.favourites.ui.smartfolders

import org.draken.usagi.favourites.domain.SmartFolderContent
import org.draken.usagi.favourites.domain.SmartFolderDevice
import org.draken.usagi.favourites.domain.SmartFolderRules
import org.junit.Assert.assertEquals
import org.junit.Test

class SmartFolderRuleSummaryTest {
	@Test
	fun `empty rules have no summary parts`() {
		assertEquals(emptyList<SmartFolderRuleSummary.Part>(), SmartFolderRuleSummary.from(SmartFolderRules()).parts)
	}

	@Test
	fun `summary includes every effective rule dimension`() {
		val summary =
			SmartFolderRuleSummary.from(
				SmartFolderRules(
					sources = setOf("A", "B"),
					categoryIds = setOf(1L),
					tagIds = setOf(10L, 20L, 30L),
					content = SmartFolderContent.NSFW,
					device = SmartFolderDevice.ON_DEVICE,
				),
			)

		assertEquals(
			listOf(
				SmartFolderRuleSummary.Part.Count(SmartFolderRuleSummary.Dimension.SOURCES, 2),
				SmartFolderRuleSummary.Part.Count(SmartFolderRuleSummary.Dimension.CATEGORIES, 1),
				SmartFolderRuleSummary.Part.Count(SmartFolderRuleSummary.Dimension.TAGS, 3),
				SmartFolderRuleSummary.Part.Content(SmartFolderContent.NSFW),
				SmartFolderRuleSummary.Part.Device(SmartFolderDevice.ON_DEVICE),
			),
			summary.parts,
		)
	}
}
