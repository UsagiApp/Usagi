package org.draken.usagi.settings.sources.catalog

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import androidx.activity.viewModels
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.graphics.Insets
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
import org.draken.usagi.core.util.ext.observe
import org.draken.usagi.core.util.ext.observeEvent
import org.draken.usagi.databinding.ActivitySourcesCatalogBinding
import org.draken.usagi.list.ui.adapter.TypedListSpacingDecoration
import org.draken.usagi.main.ui.owners.AppBarOwner
import tsuki.model.ContentType

@AndroidEntryPoint
class SourcesCatalogActivity :
	BaseActivity<ActivitySourcesCatalogBinding>(),
	OnListItemClickListener<SourceCatalogItem.Source>,
	AppBarOwner,
	ChipsView.OnChipClickListener {
	override val appBar: AppBarLayout get() = viewBinding.appbar

	private val viewModel by viewModels<SourcesCatalogViewModel>()
	private val downloadManager by lazy { getSystemService(DOWNLOAD_SERVICE) as DownloadManager }
	private val sideloadDownloadIds = mutableSetOf<Long>()
	private var fadingAppbarMediator: FadingAppbarMediator? = null
	private var navBarBottomInset = 0
	private val downloadReceiver =
		object : BroadcastReceiver() {
			override fun onReceive(
				context: Context,
				intent: Intent,
			) {
				if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
				val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, 0L)
				if (id != 0L && sideloadDownloadIds.remove(id)) openDownloadedApk(id)
			}
		}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(ActivitySourcesCatalogBinding.inflate(layoutInflater))
		setDisplayHomeAsUp(isEnabled = true, showUpAsClose = false)
		if (viewModel.isScopedMode) {
			title = intent.getStringExtra(EXTRA_REPOSITORY_NAME) ?: viewModel.scopedRepositoryUrl
		}
		val adapter =
			SourcesCatalogAdapter(
				nativeListener = this,
				onExtensionClick = { item, _ -> openSource(item) },
				onExtensionInstall = { item, _ -> installExtension(item) },
				onExtensionUninstall = { item, _ -> uninstallExtension(item) },
				onExtensionSideload = ::showSideloadMenu,
			)
		with(viewBinding.recyclerView) {
			setHasFixedSize(true)
			addItemDecoration(TypedListSpacingDecoration(context, false))
			this.adapter = adapter
		}
		viewBinding.chipsFilter.onChipClickListener = this
		fadingAppbarMediator = FadingAppbarMediator(viewBinding.appbar, viewBinding.toolbar).also { it.bind() }
		viewModel.content.observe(this, adapter)
		viewModel.onActionDone.observeEvent(this, ReversibleActionObserver(viewBinding.recyclerView))
		combine(viewModel.appliedFilter, viewModel.hasNewSources, viewModel.contentTypes, ::Triple).observe(this) {
			updateFilters(it.first, it.second, it.third)
		}
		addMenuProvider(SourcesCatalogMenuProvider(this, viewModel), this, androidx.lifecycle.Lifecycle.State.RESUMED)
	}

	override fun onStart() {
		super.onStart()
		ContextCompat.registerReceiver(
			this,
			downloadReceiver,
			IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
			ContextCompat.RECEIVER_EXPORTED,
		)
	}

	override fun onResume() {
		super.onResume()
		viewModel.refreshExtensionRuntime()
	}

	override fun onStop() {
		runCatching { unregisterReceiver(downloadReceiver) }
		super.onStop()
	}

	override fun onDestroy() {
		currentSnackbar?.dismiss()
		currentSnackbar = null
		fadingAppbarMediator?.unbind()
		fadingAppbarMediator = null
		viewBinding.chipsFilter.onChipClickListener = null
		viewBinding.recyclerView.adapter = null
		super.onDestroy()
	}

	override fun onApplyWindowInsets(
		v: View,
		insets: WindowInsetsCompat,
	): WindowInsetsCompat {
		val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
		navBarBottomInset = bars.bottom
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
	) = router.openList(item.source, null, null)

	override fun onItemLongClick(
		item: SourceCatalogItem.Source,
		view: View,
	): Boolean {
		viewModel.addSource(item.source)
		return false
	}

	private fun installExtension(item: SourceCatalogItem.Extension) {
		if (item.isLoaded && !item.hasUpdate) return
		lifecycleScope.launch {
			if (!viewModel.installExtension(item)) {
				showSnackbar(getString(R.string.load_failed), Snackbar.LENGTH_LONG)
			}
		}
	}

	private fun uninstallExtension(item: SourceCatalogItem.Extension) {
		if (item.isPreInstalledApk) {
			val action =
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
					Intent.ACTION_DELETE
				} else {
					@Suppress("DEPRECATION")
					Intent.ACTION_UNINSTALL_PACKAGE
				}
			startActivity(Intent(action, Uri.fromParts("package", item.artifact.packageName, null)))
		} else {
			lifecycleScope.launch {
				if (!viewModel.uninstallExtension(item)) {
					showSnackbar(getString(R.string.load_failed), Snackbar.LENGTH_SHORT)
				}
			}
		}
	}

	private fun openSource(item: SourceCatalogItem.Extension) {
		lifecycleScope.launch {
			val source = viewModel.openExtensionSource(item)
			if (source != null) {
				router.openList(source, null, null)
			} else {
				showSnackbar(getString(R.string.unsupported_source), Snackbar.LENGTH_SHORT)
			}
		}
	}

	private fun showSideloadMenu(
		item: SourceCatalogItem.Extension,
		anchor: View,
	) {
		PopupMenu(this, anchor)
			.apply {
				menu.add(Menu.NONE, Menu.NONE, 0, R.string.download)
				setOnMenuItemClickListener {
					downloadApk(item)
					true
				}
			}.show()
	}

	private fun downloadApk(item: SourceCatalogItem.Extension) {
		val req =
			viewModel.createDownloadRequest(item, getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)) ?: run {
				showSnackbar(getString(R.string.operation_not_supported), Snackbar.LENGTH_LONG)
				return
			}
		val id =
			runCatching { downloadManager.enqueue(req) }.getOrElse {
				showSnackbar(getString(R.string.error), Snackbar.LENGTH_LONG)
				return
			}
		sideloadDownloadIds += id
		showSnackbar(getString(R.string.download), Snackbar.LENGTH_SHORT)
	}

	@SuppressLint("RequestInstallPackagesPolicy")
	@Suppress("DEPRECATION")
	private fun openDownloadedApk(downloadId: Long) {
		val apkUri =
			downloadManager.getUriForDownloadedFile(downloadId) ?: run {
				showSnackbar(getString(R.string.error), Snackbar.LENGTH_LONG)
				return
			}
		val intent =
			Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
				flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
				setDataAndType(apkUri, APK_MIME_TYPE)
				putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
			}
		runCatching { startActivity(intent) }.onFailure {
			if (it is ActivityNotFoundException) showSnackbar(getString(R.string.error), Snackbar.LENGTH_LONG)
		}
	}

	private fun updateFilters(
		appliedFilter: SourcesCatalogFilter,
		hasNewSources: Boolean,
		contentTypes: List<ContentType>,
	) {
		val chips = ArrayList<ChipModel>(contentTypes.size + 3)
		if (!viewModel.isScopedMode) {
			chips +=
				ChipModel(
					title =
						appliedFilter.plugin?.let { key ->
							viewModel.plugins.firstOrNull { it.first == key }?.second
								?: key.removeSuffix(".jar")
						} ?: getString(R.string.any),
					icon = R.drawable.ic_services,
					isDropdown = true,
					data = "plugins",
				)
		}
		chips +=
			ChipModel(
				title = viewModel.localeDisplayName(appliedFilter.locale),
				icon = R.drawable.ic_language,
				isDropdown = true,
			)
		if (hasNewSources && !viewModel.isScopedMode) {
			chips +=
				ChipModel(
					title = getString(R.string._new),
					icon = R.drawable.ic_updated,
					isChecked = appliedFilter.isNewOnly,
					data = true,
				)
		}
		contentTypes.mapTo(chips) { type ->
			ChipModel(title = getString(type.titleResId), isChecked = type in appliedFilter.types, data = type)
		}
		viewBinding.chipsFilter.setChips(chips)
	}

	private var currentSnackbar: Snackbar? = null

	private fun showSnackbar(
		message: CharSequence,
		duration: Int,
	) {
		currentSnackbar?.dismiss()
		val sb = Snackbar.make(viewBinding.recyclerView, message, duration)
		(sb.view.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
			it.bottomMargin += navBarBottomInset
			sb.view.layoutParams = it
		}
		currentSnackbar = sb
		sb.show()
	}

	private fun showLocalesMenu(anchor: View) {
		val locales = viewModel.locales.value.sortedWith(compareBy { viewModel.localeDisplayName(it) })
		PopupMenu(this, anchor)
			.apply {
				locales.forEachIndexed { i, loc -> menu.add(Menu.NONE, Menu.NONE, i, viewModel.localeDisplayName(loc)) }
				setOnMenuItemClickListener {
					viewModel.setLocale(locales.getOrNull(it.order))
					true
				}
			}.show()
	}

	private fun showPluginsMenu(anchor: View) {
		PopupMenu(this, anchor)
			.apply {
				menu.add(Menu.NONE, Menu.NONE, 0, getString(R.string.any))
				viewModel.plugins.forEachIndexed { i, (_, label) -> menu.add(Menu.NONE, Menu.NONE, i + 1, label) }
				setOnMenuItemClickListener {
					viewModel.setPlugin(if (it.order == 0) null else viewModel.plugins[it.order - 1].first)
					true
				}
			}.show()
	}

	companion object {
		const val APK_MIME_TYPE = SourcesCatalogViewModel.APK_MIME_TYPE
		const val EXTRA_REPOSITORY_URL = SourcesCatalogViewModel.EXTRA_REPOSITORY_URL
		const val EXTRA_REPOSITORY_NAME = SourcesCatalogViewModel.EXTRA_REPOSITORY_NAME
	}
}
