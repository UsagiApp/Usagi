package org.draken.usagi.settings

import android.os.Bundle
import androidx.preference.Preference
import leakcanary.LeakCanary
import org.draken.usagi.UsagiApp
import org.draken.usagi.R
import org.draken.usagi.core.model.TestMangaSource
import org.draken.usagi.core.nav.router
import org.draken.usagi.core.ui.BasePreferenceFragment
import org.draken.usagi.settings.utils.SplitSwitchPreference
import org.koitharu.workinspector.WorkInspector

class DebugSettingsFragment : BasePreferenceFragment(R.string.debug), Preference.OnPreferenceChangeListener,
	Preference.OnPreferenceClickListener {

	private val application
		get() = requireContext().applicationContext as UsagiApp

	override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
		addPreferencesFromResource(R.xml.pref_debug)
		findPreference<SplitSwitchPreference>(KEY_LEAK_CANARY)?.let { pref ->
			pref.isChecked = application.isLeakCanaryEnabled
			pref.onPreferenceChangeListener = this
			pref.onContainerClickListener = this
		}
	}

	override fun onResume() {
		super.onResume()
		findPreference<SplitSwitchPreference>(KEY_LEAK_CANARY)?.isChecked = application.isLeakCanaryEnabled
	}

	override fun onPreferenceTreeClick(preference: Preference): Boolean = when (preference.key) {
		KEY_WORK_INSPECTOR -> {
			startActivity(WorkInspector.getIntent(preference.context))
			true
		}

		KEY_TEST_PARSER -> {
			router.openList(TestMangaSource, null, null)
			true
		}

		else -> super.onPreferenceTreeClick(preference)
	}

	override fun onPreferenceClick(preference: Preference): Boolean = when (preference.key) {
		KEY_LEAK_CANARY -> {
			startActivity(LeakCanary.newLeakDisplayActivityIntent())
			true
		}

		else -> super.onPreferenceTreeClick(preference)
	}

	override fun onPreferenceChange(preference: Preference, newValue: Any?): Boolean = when (preference.key) {
		KEY_LEAK_CANARY -> {
			application.isLeakCanaryEnabled = newValue as Boolean
			true
		}

		else -> false
	}

	private companion object {

		const val KEY_LEAK_CANARY = "leak_canary"
		const val KEY_WORK_INSPECTOR = "work_inspector"
		const val KEY_TEST_PARSER = "test_parser"
	}
}
