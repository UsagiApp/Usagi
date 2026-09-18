package org.draken.usagi.alternatives.ui

import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.widget.SearchView
import androidx.core.view.MenuProvider
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import coil3.ImageLoader
import dagger.hilt.android.AndroidEntryPoint
import org.draken.usagi.R
import org.draken.usagi.core.exceptions.resolve.SnackbarErrorObserver
import org.draken.usagi.core.model.getTitle
import org.draken.usagi.core.nav.router
import org.draken.usagi.core.ui.BaseActivity
import org.draken.usagi.core.ui.BaseListAdapter
import org.draken.usagi.core.ui.dialog.buildAlertDialog
import org.draken.usagi.core.ui.list.OnListItemClickListener
import org.draken.usagi.core.util.ext.consumeAllSystemBarsInsets
import org.draken.usagi.core.util.ext.observe
import org.draken.usagi.core.util.ext.observeEvent
import org.draken.usagi.core.util.ext.systemBarsInsets
import org.draken.usagi.databinding.ActivityAlternativesBinding
import org.draken.usagi.list.ui.adapter.ListItemType
import org.draken.usagi.list.ui.adapter.ListStateHolderListener
import org.draken.usagi.list.ui.adapter.TypedListSpacingDecoration
import org.draken.usagi.list.ui.adapter.buttonFooterAD
import org.draken.usagi.list.ui.adapter.emptyStateListAD
import org.draken.usagi.list.ui.adapter.loadingFooterAD
import org.draken.usagi.list.ui.adapter.loadingStateAD
import org.draken.usagi.list.ui.model.ListModel
import tsuki.model.Manga
import javax.inject.Inject

@AndroidEntryPoint
class AlternativesActivity :
	BaseActivity<ActivityAlternativesBinding>(),
	ListStateHolderListener,
	OnListItemClickListener<MangaAlternativeModel> {
	@Inject
	lateinit var coil: ImageLoader

	private val viewModel by viewModels<AlternativesViewModel>()

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(ActivityAlternativesBinding.inflate(layoutInflater))
		supportActionBar?.run {
			setDisplayHomeAsUpEnabled(true)
			subtitle = viewModel.manga.title
		}
		val listAdapter =
			BaseListAdapter<ListModel>()
				.addDelegate(ListItemType.MANGA_LIST_DETAILED, alternativeAD(coil, this, this))
				.addDelegate(ListItemType.STATE_EMPTY, emptyStateListAD(null))
				.addDelegate(ListItemType.FOOTER_LOADING, loadingFooterAD())
				.addDelegate(ListItemType.STATE_LOADING, loadingStateAD())
				.addDelegate(ListItemType.FOOTER_BUTTON, buttonFooterAD(this))
		with(viewBinding.recyclerView) {
			setHasFixedSize(true)
			addItemDecoration(TypedListSpacingDecoration(context, addHorizontalPadding = false))
			adapter = listAdapter
		}

		viewModel.onError.observeEvent(this, SnackbarErrorObserver(viewBinding.recyclerView, null))
		viewModel.list.observe(this, listAdapter)
		viewModel.onMigrated.observeEvent(this) {
			Toast.makeText(this, R.string.migration_completed, Toast.LENGTH_SHORT).show()
			router.openDetails(it)
			finishAfterTransition()
		}
		addMenuProvider(
			object : MenuProvider {
				override fun onCreateMenu(
					menu: Menu,
					menuInflater: MenuInflater,
				) {
					menuInflater.inflate(R.menu.opt_search, menu)
					val menuItem = menu.findItem(R.id.action_search)
					val searchView = menuItem?.actionView as? SearchView
					searchView?.queryHint = getString(R.string.search_manga)
					searchView?.setOnQueryTextListener(
						object : SearchView.OnQueryTextListener {
							override fun onQueryTextSubmit(query: String?): Boolean {
								viewModel.search(query)
								return true
							}

							override fun onQueryTextChange(newText: String?): Boolean {
								if (newText.isNullOrBlank()) {
									viewModel.search(null)
								}
								return true
							}
						},
					)
				}

				override fun onMenuItemSelected(menuItem: MenuItem): Boolean = false
			},
		)
	}

	override fun onApplyWindowInsets(
		v: View,
		insets: WindowInsetsCompat,
	): WindowInsetsCompat {
		val barsInsets = insets.systemBarsInsets
		viewBinding.recyclerView.updatePadding(
			left = barsInsets.left,
			right = barsInsets.right,
			bottom = barsInsets.bottom,
		)
		viewBinding.appbar.updatePadding(
			left = barsInsets.left,
			right = barsInsets.right,
			top = barsInsets.top,
		)
		return insets.consumeAllSystemBarsInsets()
	}

	override fun onItemClick(
		item: MangaAlternativeModel,
		view: View,
	) {
		when (view.id) {
			R.id.chip_source -> router.openSearch(item.manga.source, viewModel.manga.title)
			R.id.button_migrate -> confirmMigration(item.manga)
			else -> router.openDetails(item.manga)
		}
	}

	override fun onRetryClick(error: Throwable) = viewModel.retry()

	override fun onEmptyActionClick() = Unit

	override fun onFooterButtonClick() = viewModel.continueSearch()

	private fun confirmMigration(target: Manga) {
		buildAlertDialog(this, isCentered = true) {
			setIcon(R.drawable.ic_replace)
			setTitle(R.string.manga_migration)
			setMessage(
				getString(
					R.string.migrate_confirmation,
					viewModel.manga.title,
					viewModel.manga.source.getTitle(context),
					target.title,
					target.source.getTitle(context),
				),
			)
			setNegativeButton(android.R.string.cancel, null)
			setPositiveButton(R.string.migrate) { _, _ ->
				viewModel.migrate(target)
			}
		}.show()
	}
}
