package org.draken.usagi.settings

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.TwoStatePreference
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.draken.usagi.R
import org.draken.usagi.core.nav.router
import org.draken.usagi.core.os.AppShortcutManager
import org.draken.usagi.core.prefs.AppSettings
import org.draken.usagi.core.prefs.ColorScheme
import org.draken.usagi.core.prefs.ListMode
import org.draken.usagi.core.prefs.PaletteStyle
import org.draken.usagi.core.prefs.ProgressIndicatorMode
import org.draken.usagi.core.prefs.ScreenshotsPolicy
import org.draken.usagi.core.prefs.SearchSuggestionType
import org.draken.usagi.core.ui.BasePreferenceFragment
import org.draken.usagi.core.ui.util.ActivityRecreationHandle
import org.draken.usagi.core.util.LocaleComparator
import org.draken.usagi.core.util.ext.getLocalesConfig
import org.draken.usagi.core.util.ext.printStackTraceDebug
import org.draken.usagi.core.util.ext.setDefaultValueCompat
import org.draken.usagi.core.util.ext.sortedWithSafe
import org.draken.usagi.core.util.ext.toList
import org.draken.usagi.core.util.ext.viewLifecycleScope
import org.draken.usagi.settings.protect.ProtectSetupActivity
import org.draken.usagi.settings.utils.ActivityListPreference
import org.draken.usagi.settings.utils.GetThemePreviewHistoryUseCase
import org.draken.usagi.settings.utils.MultiSummaryProvider
import org.draken.usagi.settings.utils.PercentSummaryProvider
import org.draken.usagi.settings.utils.SliderPreference
import org.draken.usagi.settings.utils.ThemeChooserPreference
import org.draken.usagi.settings.utils.ThemeColorPreference
import org.draken.usagi.settings.utils.ThemePreviewDrawable
import tsuki.util.mapToSet
import tsuki.util.names
import tsuki.util.runCatchingCancellable
import tsuki.util.toTitleCase
import javax.inject.Inject

@AndroidEntryPoint
class AppearanceSettingsFragment :
	BasePreferenceFragment(R.string.appearance),
	SharedPreferences.OnSharedPreferenceChangeListener {
	@Inject
	lateinit var activityRecreationHandle: ActivityRecreationHandle

	@Inject
	lateinit var appShortcutManager: AppShortcutManager

	@Inject
	lateinit var getThemePreviewHistory: GetThemePreviewHistoryUseCase

	override fun onCreatePreferences(
		savedInstanceState: Bundle?,
		rootKey: String?,
	) {
		addPreferencesFromResource(R.xml.pref_appearance)
		findPreference<SliderPreference>(AppSettings.KEY_GRID_SIZE)?.summaryProvider = PercentSummaryProvider()
		findPreference<ListPreference>(AppSettings.KEY_PALETTE_STYLE)?.run {
			entryValues = PaletteStyle.entries.names()
			setDefaultValueCompat(PaletteStyle.SYSTEM.name)
			isVisible = settings.colorScheme == ColorScheme.MONET || settings.colorScheme == ColorScheme.CUSTOM
		}
		findPreference<ThemeColorPreference>(AppSettings.KEY_CUSTOM_THEME_COLOR)?.run {
			color = settings.customThemeColor
			isVisible = settings.colorScheme == ColorScheme.CUSTOM
		}
		findPreference<ThemeChooserPreference>(AppSettings.KEY_COLOR_THEME)?.run {
			paletteStyle = settings.paletteStyle
			customColor = settings.customThemeColor
		}
		findPreference<ListPreference>(AppSettings.KEY_LIST_MODE)?.run {
			entryValues = ListMode.entries.names()
			setDefaultValueCompat(ListMode.GRID.name)
		}
		findPreference<ListPreference>(AppSettings.KEY_PROGRESS_INDICATORS)?.run {
			entryValues = ProgressIndicatorMode.entries.names()
			setDefaultValueCompat(ProgressIndicatorMode.PERCENT_READ.name)
		}
		findPreference<ActivityListPreference>(AppSettings.KEY_APP_LOCALE)?.run {
			initLocalePicker(this)
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
				activityIntent =
					Intent(
						Settings.ACTION_APP_LOCALE_SETTINGS,
						Uri.fromParts("package", context.packageName, null),
					)
			}
			summaryProvider =
				Preference.SummaryProvider<ActivityListPreference> {
					val locale = AppCompatDelegate.getApplicationLocales().get(0)
					locale?.getDisplayName(locale)?.toTitleCase(locale) ?: getString(R.string.follow_system)
				}
			setDefaultValueCompat("")
		}
		findPreference<MultiSelectListPreference>(AppSettings.KEY_MANGA_LIST_BADGES)?.run {
			summaryProvider = MultiSummaryProvider(R.string.none)
		}
		findPreference<Preference>(AppSettings.KEY_SHORTCUTS)?.isVisible =
			appShortcutManager.isDynamicShortcutsAvailable()
		findPreference<TwoStatePreference>(AppSettings.KEY_PROTECT_APP)
			?.isChecked = !settings.appPassword.isNullOrEmpty()
		findPreference<ListPreference>(AppSettings.KEY_SCREENSHOTS_POLICY)?.run {
			entryValues = ScreenshotsPolicy.entries.names()
			setDefaultValueCompat(ScreenshotsPolicy.ALLOW.name)
		}
		findPreference<MultiSelectListPreference>(AppSettings.KEY_SEARCH_SUGGESTION_TYPES)?.let { pref ->
			pref.entryValues = SearchSuggestionType.entries.names()
			pref.entries = SearchSuggestionType.entries.map { pref.context.getString(it.titleResId) }.toTypedArray()
			pref.summaryProvider = MultiSummaryProvider(R.string.none)
			pref.values = settings.searchSuggestionTypes.mapToSet { it.name }
		}
		bindNavSummary()
	}

	override fun onViewCreated(
		view: View,
		savedInstanceState: Bundle?,
	) {
		super.onViewCreated(view, savedInstanceState)
		settings.subscribe(this)
		view.rootView.doOnLayout { bindPreviewConfig() }
		viewLifecycleScope.launch {
			val history =
				runCatchingCancellable {
					getThemePreviewHistory()
				}.onFailure {
					it.printStackTraceDebug()
				}.getOrNull() ?: return@launch
			findPreference<ThemeChooserPreference>(AppSettings.KEY_COLOR_THEME)?.history = history
		}
	}

	override fun onDestroyView() {
		settings.unsubscribe(this)
		super.onDestroyView()
	}

	override fun onSharedPreferenceChanged(
		prefs: SharedPreferences?,
		key: String?,
	) {
		when (key) {
			AppSettings.KEY_GRID_SIZE,
			AppSettings.KEY_LIST_MODE,
			AppSettings.KEY_LIST_MODE_HISTORY,
			AppSettings.KEY_QUICK_FILTER,
			AppSettings.KEY_NAV_MAIN,
			-> {
				bindPreviewConfig()
			}

			AppSettings.KEY_THEME -> {
				activityRecreationHandle.setNightMode(settings.theme)
			}

			AppSettings.KEY_COLOR_THEME,
			AppSettings.KEY_PALETTE_STYLE,
			AppSettings.KEY_CUSTOM_THEME_COLOR,
			-> {
				activityRecreationHandle.recreateAll()
			}

			AppSettings.KEY_THEME_AMOLED,
			AppSettings.KEY_EXPRESSIVE_COMPONENTS,
			-> {
				activityRecreationHandle.recreateAll(waitForAnimations = true)
			}

			AppSettings.KEY_APP_LOCALE -> {
				AppCompatDelegate.setApplicationLocales(settings.appLocales)
			}

			AppSettings.KEY_NAV_MAIN -> {
				bindNavSummary()
			}

			AppSettings.KEY_APP_PASSWORD -> {
				findPreference<TwoStatePreference>(AppSettings.KEY_PROTECT_APP)
					?.isChecked = !settings.appPassword.isNullOrEmpty()
			}
		}
	}

	override fun onPreferenceTreeClick(preference: Preference): Boolean {
		return when (preference.key) {
			AppSettings.KEY_CUSTOM_THEME_COLOR -> {
				router.showThemeColorDialog()
				true
			}

			AppSettings.KEY_PROTECT_APP -> {
				val pref = (preference as? TwoStatePreference ?: return false)
				if (pref.isChecked) {
					pref.isChecked = false
					startActivity(Intent(preference.context, ProtectSetupActivity::class.java))
				} else {
					settings.appPassword = null
				}
				true
			}

			else -> {
				super.onPreferenceTreeClick(preference)
			}
		}
	}

	private fun initLocalePicker(preference: ListPreference) {
		val locales =
			preference.context
				.getLocalesConfig()
				.toList()
				.sortedWithSafe(LocaleComparator())
		preference.entries =
			Array(locales.size + 1) { i ->
				if (i == 0) {
					getString(R.string.follow_system)
				} else {
					val lc = locales[i - 1]
					lc.getDisplayName(lc).toTitleCase(lc)
				}
			}
		preference.entryValues =
			Array(locales.size + 1) { i ->
				if (i == 0) {
					""
				} else {
					locales[i - 1].toLanguageTag()
				}
			}
	}

	private fun bindNavSummary() {
		val pref = findPreference<Preference>(AppSettings.KEY_NAV_MAIN) ?: return
		pref.summary =
			settings.mainNavItems.joinToString {
				getString(it.title)
			}
	}

	private fun bindPreviewConfig() {
		val root = view?.rootView ?: return
		if (root.width == 0 || root.height == 0) {
			return
		}
		val density = resources.displayMetrics.density
		val insets = ViewCompat.getRootWindowInsets(root)
		val statusBar = insets?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: 0
		val bottomInset = insets?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
		findPreference<ThemeChooserPreference>(AppSettings.KEY_COLOR_THEME)?.previewConfig =
			ThemePreviewDrawable.Config(
				width = minOf(root.width, root.height) / density,
				height = maxOf(root.width, root.height) / density,
				statusBar = statusBar / density,
				bottomInset = bottomInset / density,
				showQuickFilters = settings.isQuickFilterEnabled,
				navItems = settings.mainNavItems,
				listMode = settings.historyListMode,
				gridScale = settings.gridSize / 100f,
			)
	}
}
