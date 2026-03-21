package org.draken.usagi.settings.appearance

import android.os.Bundle
import androidx.preference.ListPreference
import dagger.hilt.android.AndroidEntryPoint
import org.draken.usagi.R
import org.draken.usagi.core.prefs.AppSettings
import org.draken.usagi.core.prefs.DetailsUiMode
import org.draken.usagi.core.ui.BasePreferenceFragment
import org.draken.usagi.core.util.ext.setDefaultValueCompat
import org.koitharu.kotatsu.parsers.util.names
import org.draken.usagi.settings.utils.PercentSummaryProvider
import org.draken.usagi.settings.utils.SliderPreference

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
