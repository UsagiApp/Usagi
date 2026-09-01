package org.draken.usagi.favourites.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

@Serializable
data class SmartFolderRules(
	val version: Int = CURRENT_VERSION,
	val sources: Set<String> = emptySet(),
	val categoryIds: Set<Long> = emptySet(),
	val tagIds: Set<Long> = emptySet(),
	val content: SmartFolderContent = SmartFolderContent.ANY,
	val device: SmartFolderDevice = SmartFolderDevice.ANY,
) {
	companion object {
		const val CURRENT_VERSION = 1
	}
}

@Serializable
enum class SmartFolderContent {
	ANY,
	SFW,
	NSFW,
}

@Serializable
enum class SmartFolderDevice {
	ANY,
	ON_DEVICE,
	NOT_ON_DEVICE,
}

sealed interface SmartFolderRulesResult {
	data class Success(
		val rules: SmartFolderRules,
	) : SmartFolderRulesResult

	data class Error(
		val reason: SmartFolderRulesError,
		val rules: SmartFolderRules? = null,
	) : SmartFolderRulesResult
}

enum class SmartFolderRulesError {
	CORRUPTED,
	UNSUPPORTED_VERSION,
	MISSING_CATEGORY,
}

object SmartFolderRulesCodec {
	private val json = Json

	fun encode(rules: SmartFolderRules): String = json.encodeToString(rules)

	fun decode(payload: String): SmartFolderRulesResult =
		try {
			validate(json.decodeFromString(payload))
		} catch (_: SerializationException) {
			SmartFolderRulesResult.Error(SmartFolderRulesError.CORRUPTED)
		}

	fun validate(rules: SmartFolderRules): SmartFolderRulesResult =
		if (rules.version != SmartFolderRules.CURRENT_VERSION) {
			SmartFolderRulesResult.Error(SmartFolderRulesError.UNSUPPORTED_VERSION)
		} else {
			SmartFolderRulesResult.Success(rules)
		}
}
