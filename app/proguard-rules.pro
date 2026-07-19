-optimizationpasses 8
-dontobfuscate
-allowaccessmodification
-keepclassmembers class kotlin.jvm.internal.Intrinsics {
	public static void checkExpressionValueIsNotNull(...);
	public static void checkNotNullExpressionValue(...);
	public static void checkReturnedValueIsNotNull(...);
	public static void checkFieldIsNotNull(...);
	public static void checkParameterIsNotNull(...);
	public static void checkNotNullParameter(...);
}

-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn com.google.j2objc.annotations.**
-dontwarn com.google.re2j.**
-dontwarn coil3.PlatformContext

-keep class org.draken.usagi.settings.NotificationSettingsLegacyFragment
-keep class org.draken.usagi.settings.about.changelog.ChangelogFragment

-keep class org.draken.usagi.core.exceptions.* { *; }
-keep class org.draken.usagi.core.prefs.ScreenshotsPolicy { *; }
-keep class org.draken.usagi.backups.ui.periodical.PeriodicalBackupSettingsFragment { *; }

# General (for plugins / exts)
-keep class androidx.collection.Scatter* { public protected *; }
-keep class kotlinx.coroutines.** { public protected *; }
-keep class kotlinx.serialization.** { public protected *; }
-keep class kotlin.** { public protected *; }
-keep class org.json.** { public protected *; }
-keep class org.json.JSONArray { public protected *; }
-keep class org.jsoup.Jsoup { public *; }
-keep class org.jsoup.nodes.** { public protected *; }
-keep class org.jsoup.select.** { public protected *; }
-keep class org.jsoup.parser.** { public protected *; }
-keep class org.jsoup.Connection { public *; }
-keep class org.jsoup.Connection$* { public *; }
-keep class org.jsoup.helper.** { public protected *; }
-keep class okio.** { public protected *; }
-keep class okhttp3.** { public protected *; }

# For Tsuki dependency and Kotatsu parsers
-keep class tsuki.** { public protected *; }
-keep class org.koitharu.kotatsu.parsers.** { public protected *; }
-keep class * implements tsuki.model.MangaSource { public protected *; }
-keep class * implements tsuki.MangaParser { public protected *; }
-keep class * extends tsuki.MangaLoaderContext { public protected *; }

# For TsukiMix dependency, optimization is needed if possible
-keep class androidx.preference.PreferenceCategory { public protected *; }
-keep class androidx.preference.Preference { public protected *; }
-keep class androidx.preference.PreferenceScreen { public protected *; }
-keep class androidx.preference.PreferenceGroup { public protected *; }
-keep class androidx.preference.PreferenceManager { public protected *; }
-keep class androidx.preference.EditTextPreference { public protected *; }
-keep class androidx.preference.ListPreference { public protected *; }
-keep class androidx.preference.SwitchPreference { public protected *; }
-keep class androidx.preference.SwitchPreferenceCompat { public protected *; }
-keep class androidx.preference.CheckBoxPreference { public protected *; }
-keep class androidx.preference.MultiSelectListPreference { public protected *; }
-keep class androidx.preference.TwoStatePreference { public protected *; }
-keep class eu.kanade.tachiyomi.** { public protected *; }
-keep class keiyoushi.** { public protected *; }
-keep class rx.Observable { public protected *; }
-keep class rx.Single { public protected *; }
-keep class rx.Completable { public protected *; }
-keep class rx.Subscriber { public protected *; }
-keep class rx.Observer { public protected *; }
-keep class rx.Subscription { public protected *; }
-keep class rx.Scheduler { public protected *; }
-keep class rx.functions.Action { public protected *; }
-keep class rx.functions.Action0 { public protected *; }
-keep class rx.functions.Action1 { public protected *; }
-keep class rx.functions.Action2 { public protected *; }
-keep class rx.functions.Func0 { public protected *; }
-keep class rx.functions.Func1 { public protected *; }
-keep class rx.functions.Func2 { public protected *; }
-keep class rx.functions.Func3 { public protected *; }
-keep class rx.functions.Func4 { public protected *; }
-keep class rx.functions.Func5 { public protected *; }
-keep class rx.functions.Func6 { public protected *; }
-keep class rx.functions.Func7 { public protected *; }
-keep class rx.functions.Func8 { public protected *; }
-keep class rx.functions.Func9 { public protected *; }
-keep class uy.kohesive.injekt.** { public protected *; }
-keep class * extends uy.kohesive.injekt.api.TypeReference { *; }
-keep class * extends uy.kohesive.injekt.api.FullTypeReference { *; }
