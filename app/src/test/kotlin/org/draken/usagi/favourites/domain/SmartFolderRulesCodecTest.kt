package org.draken.usagi.favourites.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class SmartFolderRulesCodecTest {
	@Test
	fun `valid rules survive serialization round trip`() {
		val rules =
			SmartFolderRules(
				sources = setOf("MANGADEX", "MANGASEE"),
				categoryIds = setOf(1L, 2L),
				tagIds = setOf(10L, 20L),
				content = SmartFolderContent.NSFW,
				device = SmartFolderDevice.ON_DEVICE,
			)

		val result = SmartFolderRulesCodec.decode(SmartFolderRulesCodec.encode(rules))

		assertEquals(SmartFolderRulesResult.Success(rules), result)
	}

	@Test
	fun `rules require at least one effective condition`() {
		val result = SmartFolderRulesCodec.decode(SmartFolderRulesCodec.encode(SmartFolderRules()))

		assertEquals(SmartFolderRulesResult.Error(SmartFolderRulesError.NO_CONDITIONS), result)
	}

	@Test
	fun `corrupted rules return an explicit error`() {
		val result = SmartFolderRulesCodec.decode("{not-json")

		assertEquals(SmartFolderRulesResult.Error(SmartFolderRulesError.CORRUPTED), result)
	}

	@Test
	fun `unsupported rules version returns an explicit error`() {
		val payload = SmartFolderRulesCodec.encode(SmartFolderRules(version = 2, sources = setOf("MANGADEX")))

		val result = SmartFolderRulesCodec.decode(payload)

		assertEquals(SmartFolderRulesResult.Error(SmartFolderRulesError.UNSUPPORTED_VERSION), result)
	}
}
