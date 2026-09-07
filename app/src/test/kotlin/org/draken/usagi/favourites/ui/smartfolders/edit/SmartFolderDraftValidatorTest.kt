package org.draken.usagi.favourites.ui.smartfolders.edit

import org.draken.usagi.favourites.domain.SmartFolderContent
import org.draken.usagi.favourites.domain.SmartFolderRules
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartFolderDraftValidatorTest {
	@Test
	fun `blank folder name cannot be saved even when a rule is selected`() {
		assertFalse(
			SmartFolderDraftValidator.canSave(
				title = "   ",
				rules = SmartFolderRules(sources = setOf("MANGADEX")),
			),
		)
	}

	@Test
	fun `folder without a rule can be saved when it has a name`() {
		assertTrue(SmartFolderDraftValidator.canSave(title = "Reading", rules = SmartFolderRules()))
	}

	@Test
	fun `trimmed name and one effective rule enable saving`() {
		assertTrue(
			SmartFolderDraftValidator.canSave(
				title = "  NSFW  ",
				rules = SmartFolderRules(content = SmartFolderContent.NSFW),
			),
		)
	}
}
