package org.draken.usagi.favourites.ui.smartfolders.edit

import org.draken.usagi.favourites.domain.SmartFolderRules
import org.draken.usagi.favourites.domain.SmartFolderRulesCodec
import org.draken.usagi.favourites.domain.SmartFolderRulesResult

object SmartFolderDraftValidator {
	fun canSave(
		title: String,
		rules: SmartFolderRules,
	): Boolean = title.isNotBlank() && SmartFolderRulesCodec.validate(rules) is SmartFolderRulesResult.Success
}
