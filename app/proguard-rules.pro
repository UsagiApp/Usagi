-optimizationpasses 8
-dontobfuscate
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
-keep class org.jsoup.parser.Tag
-keep class org.jsoup.internal.StringUtil

-keep class org.acra.security.NoKeyStoreFactory { *; }
-keep class org.acra.config.DefaultRetryPolicy { *; }
-keep class org.acra.attachment.DefaultAttachmentProvider { *; }
-keep class org.acra.sender.JobSenderService

# For core-exts dependency, optimization is needed if possible
-keep class org.koitharu.kotatsu.parsers.** { *; }
-keep class * extends org.koitharu.kotatsu.parsers.MangaLoaderContext { *; }
