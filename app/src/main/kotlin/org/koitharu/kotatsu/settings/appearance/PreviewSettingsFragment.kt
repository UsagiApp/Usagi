package org.koitharu.kotatsu.settings.appearance

import android.os.Bundle
import androidx.preference.ListPreference
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.DetailsUiMode
import org.koitharu.kotatsu.core.ui.BasePreferenceFragment
import org.koitharu.kotatsu.core.util.ext.setDefaultValueCompat
import org.koitharu.kotatsu.parsers.util.names
import org.koitharu.kotatsu.settings.utils.PercentSummaryProvider
import org.koitharu.kotatsu.settings.utils.SliderPreference

@AndroidEntryPoint
class PreviewSettingsFragment :
    BasePreferenceFragment(R.string.details_appearance) {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.pref_details_appearance)

        findPreference<ListPreference>(AppSettings.KEY_DETAILS_UI)?.run {
            entryValues = DetailsUiMode.entries.names()
            setDefaultValueCompat(DetailsUiMode.MODERN.name)
        }

        findPreference<SliderPreference>(AppSettings.KEY_DETAILS_BACKDROP_BLUR_AMOUNT)
            ?.summaryProvider = PercentSummaryProvider()
    }
}
