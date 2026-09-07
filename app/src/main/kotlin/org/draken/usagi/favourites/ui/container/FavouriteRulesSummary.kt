package org.draken.usagi.favourites.ui.container

internal fun buildFavouriteRulesSummary(
	invalidRulesLabel: CharSequence?,
	persistentSummary: CharSequence?,
	selectedFilterTitles: List<CharSequence>,
	overflowFilterSummary: CharSequence,
): CharSequence? {
	if (invalidRulesLabel != null) return invalidRulesLabel
	val transientSummary =
		when {
			selectedFilterTitles.isEmpty() -> null
			selectedFilterTitles.size <= 3 -> selectedFilterTitles.joinToString(" · ")
			else -> overflowFilterSummary
		}
	return listOfNotNull(
		persistentSummary?.takeUnless(CharSequence::isEmpty),
		transientSummary,
	).joinToString(" · ").takeIf(String::isNotEmpty)
}
