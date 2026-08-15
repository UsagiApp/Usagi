package org.draken.usagi.settings.sources.catalog

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Environment
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.activity.viewModels
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.graphics.Insets
import androidx.core.net.toUri
import androidx.core.os.ConfigurationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.draken.usagi.R
import org.draken.usagi.core.model.titleResId
import org.draken.usagi.core.nav.router
import org.draken.usagi.core.ui.BaseActivity
import org.draken.usagi.core.ui.list.OnListItemClickListener
import org.draken.usagi.core.ui.util.FadingAppbarMediator
import org.draken.usagi.core.ui.util.ReversibleActionObserver
import org.draken.usagi.core.ui.widgets.ChipsView
import org.draken.usagi.core.ui.widgets.ChipsView.ChipModel
import org.draken.usagi.core.util.ext.getDisplayName
import org.draken.usagi.core.util.ext.observe
import org.draken.usagi.core.util.ext.observeEvent
import org.draken.usagi.core.util.ext.toLocale
import org.draken.usagi.databinding.ActivitySourcesCatalogBinding
import org.draken.usagi.list.ui.adapter.TypedListSpacingDecoration
import org.draken.usagi.main.ui.owners.AppBarOwner
import tsuki.model.ContentType
import java.util.Locale

@AndroidEntryPoint
class SourcesCatalogActivity :
	BaseActivity<ActivitySourcesCatalogBinding>(),
	OnListItemClickListener<SourceCatalogItem.Source>,
	AppBarOwner,
	MenuItem.OnActionExpandListener,
	ChipsView.OnChipClickListener {
	override val appBar: AppBarLayout
		get() = viewBinding.appbar

	private val viewModel by viewModels<SourcesCatalogViewModel>()
	private val downloadManager by lazy { getSystemService(DOWNLOAD_SERVICE) as DownloadManager }
	private val sideloadDownloadIds = mutableSetOf<Long>()
	private var navigationBarBottomInset = 0
	private val downloadReceiver =
		object : BroadcastReceiver() {
			override fun onReceive(
				context: Context,
				intent: Intent,
			) {
				if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
				val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, 0L)
				if (downloadId != 0L && sideloadDownloadIds.remove(downloadId)) {
					openDownloadedApk(downloadId)
				}
			}
		}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(ActivitySourcesCatalogBinding.inflate(layoutInflater))
		setDisplayHomeAsUp(isEnabled = true, showUpAsClose = false)
		val sourcesAdapter =
			SourcesCatalogAdapter(
				nativeListener = this,
				onTachiyomiClick = { _, _ -> showExternalSourceUnsupported() },
				onTachiyomiInstall = { item, _ -> installTachiyomi(item) },
				onTachiyomiSideload = ::showTachiyomiSideloadMenu,
			)

		with(viewBinding.recyclerView) {
			setHasFixedSize(true)
			addItemDecoration(TypedListSpacingDecoration(context, false))
			adapter = sourcesAdapter
		}
		viewBinding.chipsFilter.onChipClickListener = this
		FadingAppbarMediator(viewBinding.appbar, viewBinding.toolbar).bind()
		viewModel.content.observe(this, sourcesAdapter)
		ContextCompat.registerReceiver(
			this,
			downloadReceiver,
			IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
			ContextCompat.RECEIVER_EXPORTED,
		)
		viewModel.onActionDone.observeEvent(
			this,
			ReversibleActionObserver(viewBinding.recyclerView),
		)
		combine(viewModel.appliedFilter, viewModel.hasNewSources, viewModel.contentTypes, ::Triple).observe(this) {
			updateFilers(it.first, it.second, it.third)
		}
		addMenuProvider(SourcesCatalogMenuProvider(this, viewModel, this))
	}

	override fun onDestroy() {
		unregisterReceiver(downloadReceiver)
		viewBinding.recyclerView.adapter = null
		super.onDestroy()
	}

	override fun onApplyWindowInsets(
		v: View,
		insets: WindowInsetsCompat,
	): WindowInsetsCompat {
		val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
		navigationBarBottomInset = bars.bottom
		viewBinding.recyclerView.updatePadding(
			left = bars.left,
			right = bars.right,
			bottom = bars.bottom,
		)
		viewBinding.appbar.updatePadding(
			left = bars.left,
			right = bars.right,
			top = bars.top,
		)
		return WindowInsetsCompat
			.Builder(insets)
			.setInsets(WindowInsetsCompat.Type.systemBars(), Insets.NONE)
			.build()
	}

	override fun onChipClick(
		chip: Chip,
		data: Any?,
	) {
		when (data) {
			is ContentType -> viewModel.setContentType(data, !chip.isChecked)
			is Boolean -> viewModel.setNewOnly(!chip.isChecked)
			"plugins" -> showPluginsMenu(chip)
			else -> showLocalesMenu(chip)
		}
	}

	override fun onItemClick(
		item: SourceCatalogItem.Source,
		view: View,
	) {
		router.openList(item.source, null, null)
	}

	override fun onItemLongClick(
		item: SourceCatalogItem.Source,
		view: View,
	): Boolean {
		viewModel.addSource(item.source)
		return false
	}

	private fun installTachiyomi(item: SourceCatalogItem.Tachiyomi) {
		if (item.isLoaded && !item.hasUpdate) return
		lifecycleScope.launch {
			val success = viewModel.installTachiyomi(item)
			if (success) {
				showInsetSnackbar(getString(R.string.tachiyomi_catalog_loaded), Snackbar.LENGTH_LONG)
			} else {
				showTachiyomiError(viewModel.tachiyomiInstallError())
			}
		}
	}

	private fun showExternalSourceUnsupported() {
		showInsetSnackbar(getString(R.string.external_source_view_unsupported), Snackbar.LENGTH_LONG)
	}

	private fun showTachiyomiSideloadMenu(
		item: SourceCatalogItem.Tachiyomi,
		anchor: View,
	) {
		val menu = PopupMenu(this, anchor)
		menu.menu.add(Menu.NONE, Menu.NONE, 0, R.string.import_by_sideload)
		menu.setOnMenuItemClickListener {
			downloadTachiyomiApk(item)
			true
		}
		menu.show()
	}

	private fun downloadTachiyomiApk(item: SourceCatalogItem.Tachiyomi) {
		val apkUrl =
			item.artifact.apkUrl
				?.trim()
				?.takeIf { it.isNotEmpty() }
		if (apkUrl == null) {
			showInsetSnackbar(getString(R.string.tachiyomi_apk_unavailable), Snackbar.LENGTH_LONG)
			return
		}

		val uri = apkUrl.toUri()
		val fileName =
			uri.lastPathSegment
				?.takeIf { it.endsWith(".apk", ignoreCase = true) }
				?: "${item.artifact.packageName}-${item.artifact.versionName ?: item.artifact.versionCode ?: "latest"}.apk"
		val request =
			DownloadManager
				.Request(uri)
				.setTitle(getString(R.string.sideload_download_title, item.displayName))
				.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
				.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
				.setMimeType(APK_MIME_TYPE)
		val downloadId = downloadManager.enqueue(request)
		sideloadDownloadIds += downloadId
		showInsetSnackbar(getString(R.string.tachiyomi_apk_download_started), Snackbar.LENGTH_SHORT)
	}

	@Suppress("DEPRECATION")
	private fun openDownloadedApk(downloadId: Long) {
		val apkUri = downloadManager.getUriForDownloadedFile(downloadId)
		if (apkUri == null) {
			showInsetSnackbar(getString(R.string.tachiyomi_apk_download_failed), Snackbar.LENGTH_LONG)
			return
		}
		val installerIntent =
			Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
				flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
				setDataAndType(apkUri, APK_MIME_TYPE)
				putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
			}
		runCatching { startActivity(installerIntent) }
			.onFailure { error ->
				if (error is ActivityNotFoundException) {
					showInsetSnackbar(getString(R.string.tachiyomi_apk_download_failed), Snackbar.LENGTH_LONG)
				}
			}
	}

	override fun onMenuItemActionExpand(item: MenuItem): Boolean {
		val sq =
			(item.actionView as? SearchView)
				?.query
				?.trim()
				?.toString()
				.orEmpty()
		viewModel.performSearch(sq)
		return true
	}

	override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
		viewModel.performSearch(null)
		return true
	}

	private fun updateFilers(
		appliedFilter: SourcesCatalogFilter,
		hasNewSources: Boolean,
		contentTypes: List<ContentType>,
	) {
		val chips = ArrayList<ChipModel>(contentTypes.size + 3)
		chips +=
			ChipModel(
				title = appliedFilter.plugin?.let { key -> viewModel.plugins.firstOrNull { it.key == key }?.label ?: key.removeSuffix(".jar") } ?: getString(R.string.any),
				icon = R.drawable.ic_services,
				isDropdown = true,
				data = "plugins",
			)
		chips +=
			ChipModel(
				title = localeDisplayName(appliedFilter.locale),
				icon = R.drawable.ic_language,
				isDropdown = true,
			)
		if (hasNewSources) {
			chips +=
				ChipModel(
					title = getString(R.string._new),
					icon = R.drawable.ic_updated,
					isChecked = appliedFilter.isNewOnly,
					data = true,
				)
		}
		contentTypes.mapTo(chips) { type ->
			ChipModel(
				title = getString(type.titleResId),
				isChecked = type in appliedFilter.types,
				data = type,
			)
		}
		viewBinding.chipsFilter.setChips(chips)
	}

	private fun showTachiyomiError(detail: String?) {
		val text = listOfNotNull(getString(R.string.tachiyomi_catalog_failed), detail?.takeIf { it.isNotBlank() }).joinToString(": ")
		val snackbar = Snackbar.make(viewBinding.recyclerView, text, Snackbar.LENGTH_LONG)
		snackbar.setAction(R.string.copy_error) {
			val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
			clipboard.setPrimaryClip(ClipData.newPlainText("Usagi error", text))
			showInsetSnackbar(getString(R.string.error_copied), Snackbar.LENGTH_SHORT)
		}
		showInsetSnackbar(snackbar)
	}

	private fun showInsetSnackbar(
		message: CharSequence,
		duration: Int,
	) {
		showInsetSnackbar(Snackbar.make(viewBinding.recyclerView, message, duration))
	}

	private fun showInsetSnackbar(snackbar: Snackbar) {
		val view = snackbar.view
		val params = view.layoutParams as? ViewGroup.MarginLayoutParams
		if (params != null) {
			params.bottomMargin += navigationBarBottomInset
			view.layoutParams = params
		}
		snackbar.show()
	}

	private fun showLocalesMenu(anchor: View) {
		val locales = viewModel.locales.sortedWith(compareBy { localeDisplayName(it) })
		val menu = PopupMenu(this, anchor)
		for ((i, locale) in locales.withIndex()) {
			menu.menu.add(Menu.NONE, Menu.NONE, i, localeDisplayName(locale))
		}
		menu.setOnMenuItemClickListener {
			viewModel.setLocale(locales.getOrNull(it.order))
			true
		}
		menu.show()
	}

	private fun localeDisplayName(value: String?): String {
		val code = value?.trim().orEmpty()
		if (code.isEmpty()) return getString(R.string.all_languages)
		if (code.equals("all", true)) return getString(R.string.various_languages)

		val locale = code.replace('_', '-').toLocale()
		val displayLocale = ConfigurationCompat.getLocales(resources.configuration)[0] ?: Locale.getDefault()
		val localizedName = locale.getDisplayLanguage(displayLocale).trim()
		if (!localizedName.isLocaleCode(locale)) return localizedName

		val englishName = locale.getDisplayLanguage(Locale.ENGLISH).trim()
		return englishName.takeUnless { it.isLocaleCode(locale) } ?: getString(R.string.unknown_language)
	}

	private fun String.isLocaleCode(locale: Locale): Boolean {
		val normalized = trim().lowercase(Locale.ROOT)
		return normalized.isEmpty() || normalized == locale.language.lowercase(Locale.ROOT) || normalized == locale.toLanguageTag().lowercase(Locale.ROOT)
	}

	private fun showPluginsMenu(anchor: View) {
		val menu = PopupMenu(this, anchor)
		menu.menu.add(Menu.NONE, Menu.NONE, 0, getString(R.string.any))
		for ((i, plugin) in viewModel.plugins.withIndex()) {
			menu.menu.add(Menu.NONE, Menu.NONE, i + 1, plugin.label)
		}
		menu.setOnMenuItemClickListener {
			val p = if (it.order == 0) null else viewModel.plugins[it.order - 1].key
			viewModel.setPlugin(p)

			true
		}
		menu.show()
	}

	private companion object {
		const val APK_MIME_TYPE = "application/vnd.android.package-archive"
	}
}
