package org.draken.usagi.settings.sources.catalog

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.combine
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
	private var fadingAppbarMediator: FadingAppbarMediator? = null
	private var navBarBottomInset = 0

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
				onExtensionClick = { item, _ -> viewModel.openSource(item) },
				onExtensionInstall = { item, _ -> install(item) },
				onExtensionUninstall = { item, _ -> uninstall(item) },
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
		viewModel.onActionError.observeEvent(this) { resId ->
			showSnackbar(getString(resId), Snackbar.LENGTH_LONG)
		}
		viewModel.onOpenSource.observeEvent(this) { source ->
			router.openList(source, null, null)
		}
		combine(viewModel.appliedFilter, viewModel.hasNewSources, viewModel.contentTypes, ::Triple).observe(this) {
			updateFilters(it.first, it.second, it.third)
		}
		addMenuProvider(SourcesCatalogMenuProvider(this, viewModel), this, androidx.lifecycle.Lifecycle.State.RESUMED)
	}

	override fun onResume() {
		super.onResume()
		viewModel.refreshExtensionRuntime()
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
		)
		return insets
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

	private fun install(item: SourceCatalogItem.Extension) {
		if (item.isLoaded && !item.hasUpdate) return
		viewModel.install(item)
	}

	private fun uninstall(item: SourceCatalogItem.Extension) {
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
			viewModel.uninstall(item)
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
		if (hasNewSources || viewModel.isScopedMode) {
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
		currentSnackbar = sb
		sb.show()
	}

	private fun showLocalesMenu(anchor: View) {
		val locales = viewModel.sortLocales()
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
		const val EXTRA_REPOSITORY_URL = SourcesCatalogViewModel.EXTRA_REPOSITORY_URL
		const val EXTRA_REPOSITORY_NAME = SourcesCatalogViewModel.EXTRA_REPOSITORY_NAME
	}
}
