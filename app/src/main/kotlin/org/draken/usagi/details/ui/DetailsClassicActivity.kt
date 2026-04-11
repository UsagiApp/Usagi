package org.draken.usagi.details.ui

import android.app.assist.AssistContent
import android.content.Context
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.widget.PopupMenu
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.graphics.ColorUtils
import androidx.core.text.buildSpannedString
import androidx.core.text.method.LinkMovementMethodCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.view.updatePaddingRelative
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.transition.TransitionManager
import coil3.ImageLoader
import coil3.request.Disposable
import coil3.request.ImageRequest
import coil3.request.allowRgb565
import coil3.request.crossfade
import coil3.request.lifecycle
import coil3.request.transformations
import coil3.size.Precision
import coil3.transform.RoundedCornersTransformation
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.chip.Chip
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import org.draken.usagi.R
import org.draken.usagi.bookmarks.domain.Bookmark
import org.draken.usagi.core.image.CoilMemoryCacheKey
import org.draken.usagi.core.model.FavouriteCategory
import org.draken.usagi.core.model.LocalMangaSource
import org.draken.usagi.core.model.UnknownMangaSource
import org.draken.usagi.core.model.getSummary
import org.draken.usagi.core.model.getTitle
import org.draken.usagi.core.model.iconResId
import org.draken.usagi.core.model.titleResId
import org.draken.usagi.core.nav.ReaderIntent
import org.draken.usagi.core.nav.router
import org.draken.usagi.core.os.AppShortcutManager
import org.draken.usagi.core.parser.favicon.faviconUri
import org.draken.usagi.core.prefs.AppSettings
import org.draken.usagi.core.ui.BaseActivity
import org.draken.usagi.core.ui.BaseListAdapter
import org.draken.usagi.core.ui.image.ChipIconTarget
import org.draken.usagi.core.ui.image.FaviconDrawable
import org.draken.usagi.core.ui.list.OnListItemClickListener
import org.draken.usagi.core.ui.sheet.BottomSheetCollapseCallback
import org.draken.usagi.core.ui.util.MenuInvalidator
import org.draken.usagi.core.ui.util.ReversibleActionObserver
import org.draken.usagi.core.ui.widgets.ChipsView
import org.draken.usagi.core.util.FileSize
import org.draken.usagi.core.util.ext.consume
import org.draken.usagi.core.util.ext.enqueueWith
import org.draken.usagi.core.util.ext.getQuantityStringSafe
import org.draken.usagi.core.util.ext.isAnimationsEnabled
import org.draken.usagi.core.util.ext.isTextTruncated
import org.draken.usagi.core.util.ext.joinToStringWithLimit
import org.draken.usagi.core.util.ext.mangaSourceExtra
import org.draken.usagi.core.util.ext.observe
import org.draken.usagi.core.util.ext.observeEvent
import org.draken.usagi.core.util.ext.parentView
import org.draken.usagi.core.util.ext.setTooltipCompat
import org.draken.usagi.core.util.ext.textAndVisible
import org.draken.usagi.core.util.ext.toUriOrNull
import org.draken.usagi.databinding.ActivityDetailsClassicBinding
import org.draken.usagi.details.data.MangaDetails
import org.draken.usagi.details.service.MangaPrefetchService
import org.draken.usagi.details.ui.model.ChapterListItem
import org.draken.usagi.details.ui.model.HistoryInfo
import org.draken.usagi.details.ui.scrobbling.ScrobblingItemDecoration
import org.draken.usagi.details.ui.scrobbling.ScrollingInfoAdapter
import org.draken.usagi.download.ui.worker.DownloadStartedObserver
import org.draken.usagi.list.ui.adapter.ListItemType
import org.draken.usagi.list.ui.adapter.mangaGridItemAD
import org.draken.usagi.list.ui.model.ListModel
import org.draken.usagi.list.ui.model.MangaListModel
import org.draken.usagi.list.ui.size.StaticItemSizeResolver
import org.draken.usagi.main.ui.owners.BottomSheetOwner
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.util.ifNullOrEmpty
import javax.inject.Inject
import com.google.android.material.R as materialR

@AndroidEntryPoint
class DetailsClassicActivity :
	BaseActivity<ActivityDetailsClassicBinding>(),
	View.OnClickListener,
	View.OnLongClickListener,
	PopupMenu.OnMenuItemClickListener,
	View.OnLayoutChangeListener,
	ViewTreeObserver.OnDrawListener,
	ChipsView.OnChipClickListener,
	OnListItemClickListener<Bookmark>,
	SwipeRefreshLayout.OnRefreshListener,
	BottomSheetOwner {

	@Inject
	lateinit var shortcutManager: AppShortcutManager

	@Inject
	lateinit var coil: ImageLoader

	@Inject
	lateinit var settings: AppSettings

	private val viewModel: DetailsViewModel by viewModels()
	private lateinit var menuProvider: DetailsMenuProvider
	private lateinit var backdropController: BackdropController
	private var statusBarInset: Int = 0
	private var faviconDisposable: Disposable? = null

	override val bottomSheet: View
		get() = viewBinding.containerBottomSheet

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(ActivityDetailsClassicBinding.inflate(layoutInflater))
		enableEdgeToEdge()
		WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false
		setDisplayHomeAsUp(isEnabled = true, showUpAsClose = false)
		supportActionBar?.setDisplayShowTitleEnabled(false)
		backdropController = BackdropController(
			backdrop = viewBinding.backdrop,
			backdropGradient = viewBinding.backdropGradient,
			backdropTopGradient = viewBinding.backdropTopGradient,
			context = this,
			imageLoader = coil,
			lifecycle = this,
			settings = settings,
		)

		with(viewBinding) {
			buttonRead.setOnClickListener(this@DetailsClassicActivity)
			buttonRead.setOnLongClickListener(this@DetailsClassicActivity)
			buttonDownload.setOnClickListener(this@DetailsClassicActivity)
			infoLayout.chipBranch.setOnClickListener(this@DetailsClassicActivity)
			infoLayout.chipSize.setOnClickListener(this@DetailsClassicActivity)
			infoLayout.chipSource.setOnClickListener(this@DetailsClassicActivity)
			infoLayout.chipFavorite.setOnClickListener(this@DetailsClassicActivity)
			infoLayout.chipAuthor.setOnClickListener(this@DetailsClassicActivity)
			infoLayout.chipTime.setOnClickListener(this@DetailsClassicActivity)
			imageViewCover.setOnClickListener(this@DetailsClassicActivity)
			backdropClickArea.setOnClickListener(this@DetailsClassicActivity)
			buttonDescriptionMore.setOnClickListener(this@DetailsClassicActivity)
			buttonScrobblingMore.setOnClickListener(this@DetailsClassicActivity)
			buttonRelatedMore.setOnClickListener(this@DetailsClassicActivity)
			textViewDescription.addOnLayoutChangeListener(this@DetailsClassicActivity)
			textViewDescription.viewTreeObserver.addOnDrawListener(this@DetailsClassicActivity)
			textViewDescription.movementMethod = LinkMovementMethodCompat.getInstance()
			chipsTags.onChipClickListener = this@DetailsClassicActivity
			if (settings.isDescriptionExpanded) {
				textViewDescription.maxLines = Int.MAX_VALUE - 1
			}
			TitleExpandListener(textViewTitle).attach()
			swipeRefreshLayout.setOnRefreshListener(this@DetailsClassicActivity)
			scrollView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
				if (settings.isBackdropEnabled) {
					viewBinding.backdropContainer.translationY = -scrollY.toFloat()
				}
				updateAppBarScrim(scrollY)
				val loc = IntArray(2)
				textViewTitle.getLocationOnScreen(loc)
				val titleBottom = loc[1] + textViewTitle.height
				appbar.getLocationOnScreen(loc)
				val appBarBottom = loc[1] + appbar.height
				supportActionBar?.setDisplayShowTitleEnabled(titleBottom < appBarBottom)
			}
			containerBottomSheet.let { sheet ->
				val behavior = (sheet.layoutParams as? CoordinatorLayout.LayoutParams)?.behavior as? BottomSheetBehavior<*>
				if (behavior != null) {
					onBackPressedDispatcher.addCallback(BottomSheetCollapseCallback(sheet, behavior))
				}
			}
		}

		val appRouter = router
		viewModel.mangaDetails.filterNotNull().observe(this, ::onMangaUpdated)
		viewModel.coverUrl.observe(this, ::loadCover)
		viewModel.backdropUrl.observe(this, ::loadLargeCover)
		viewModel.onMangaRemoved.observeEvent(this, ::onMangaRemoved)
		viewModel.onError
			.filterNot { appRouter.isChapterPagesSheetShown() }
			.observeEvent(
				this,
				DetailsErrorObserver(
					activity = this,
					snackbarHost = viewBinding.scrollView,
					bottomSheet = viewBinding.containerBottomSheet,
					viewModel = viewModel,
					resolver = exceptionResolver,
				),
			)
		viewModel.onActionDone
			.filterNot { appRouter.isChapterPagesSheetShown() }
			.observeEvent(this, ReversibleActionObserver(viewBinding.scrollView))
		combine(viewModel.historyInfo, viewModel.isLoading, ::Pair).observe(this) {
			onHistoryChanged(it.first, it.second)
		}
		viewModel.isLoading.observe(this, ::onLoadingStateChanged)
		viewModel.scrobblingInfo.observe(this, ::onScrobblingInfoChanged)
		viewModel.localSize.observe(this, ::onLocalSizeChanged)
		viewModel.relatedManga.observe(this, ::onRelatedMangaChanged)
		viewModel.favouriteCategories.observe(this, ::onFavoritesChanged)
		val menuInvalidator = MenuInvalidator(this)
		viewModel.isStatsAvailable.observe(this, menuInvalidator)
		viewModel.remoteManga.observe(this, menuInvalidator)
		viewModel.selectedBranch.observe(this) {
			viewBinding.infoLayout.chipBranch.text = it.ifNullOrEmpty { getString(R.string.system_default) }
		}
		viewModel.branches.observe(this) {
			viewBinding.infoLayout.chipBranch.isVisible = it.size > 1 || !it.firstOrNull()?.name.isNullOrEmpty()
			viewBinding.infoLayout.chipBranch.isCloseIconVisible = it.size > 1
		}
		viewModel.tags.observe(this, ::onTagsChanged)
		viewModel.chapters.observe(this, PrefetchObserver(this))
		viewModel.onDownloadStarted
			.filterNot { appRouter.isChapterPagesSheetShown() }
			.observeEvent(this, DownloadStartedObserver(viewBinding.scrollView))

		menuProvider = DetailsMenuProvider(
			activity = this,
			viewModel = viewModel,
			snackbarHost = viewBinding.scrollView,
			appShortcutManager = shortcutManager,
		)
		addMenuProvider(menuProvider)
	}

	override fun onDestroy() {
		faviconDisposable?.dispose()
		super.onDestroy()
	}

	override fun onProvideAssistContent(outContent: AssistContent) {
		super.onProvideAssistContent(outContent)
		viewModel.getMangaOrNull()?.publicUrl?.toUriOrNull()?.let { outContent.webUri = it }
	}

	override fun isNsfwContent(): Flow<Boolean> = viewModel.manga.map { it?.contentRating == ContentRating.ADULT }

	override fun onClick(v: View) {
		when (v.id) {
			R.id.button_read -> openReader(isIncognitoMode = false)
			R.id.chip_branch -> showBranchPopupMenu(v)
			R.id.button_download -> {
				val manga = viewModel.getMangaOrNull() ?: return
				router.showDownloadDialog(manga, viewBinding.scrollView)
			}

			R.id.chip_author -> {
				val manga = viewModel.getMangaOrNull() ?: return
				val author = manga.authors.firstOrNull() ?: return
				router.showAuthorDialog(author, manga.source)
			}

			R.id.chip_source -> {
				val manga = viewModel.getMangaOrNull() ?: return
				router.openList(manga.source, null, null)
			}

			R.id.chip_size -> {
				val manga = viewModel.getMangaOrNull() ?: return
				router.showLocalInfoDialog(manga)
			}

			R.id.chip_favorite -> {
				val manga = viewModel.getMangaOrNull() ?: return
				router.showFavoriteDialog(manga)
			}

			R.id.chip_time -> {
				if (viewModel.isStatsAvailable.value) {
					val manga = viewModel.getMangaOrNull() ?: return
					router.showStatisticSheet(manga)
				}
			}

			R.id.imageView_cover -> {
				val manga = viewModel.getMangaOrNull() ?: return
				val url = viewModel.coverUrl.value ?: return
				router.openImage(
					url = url,
					source = manga.source,
					anchor = v,
					preview = CoilMemoryCacheKey.from(viewBinding.imageViewCover),
				)
			}

			R.id.backdrop_click_area -> {
				val manga = viewModel.getMangaOrNull() ?: return
				val url = viewModel.backdropUrl.value ?: return
				router.openImage(
					url = url,
					source = manga.source,
					anchor = v,
					preview = CoilMemoryCacheKey.from(viewBinding.backdrop),
				)
			}

			R.id.button_description_more -> {
				val tv = viewBinding.textViewDescription
				if (tv.context.isAnimationsEnabled) {
					tv.parentView?.let { parent ->
						TransitionManager.beginDelayedTransition(parent)
					}
				}
				if (tv.maxLines in 1 until Integer.MAX_VALUE) {
					tv.maxLines = Integer.MAX_VALUE
				} else {
					tv.maxLines = resources.getInteger(R.integer.details_description_lines)
				}
			}

			R.id.button_scrobbling_more -> {
				val manga = viewModel.getMangaOrNull() ?: return
				router.showScrobblingSelectorSheet(
					manga = manga,
					scrobblerService = viewModel.scrobblingInfo.value.firstOrNull()?.scrobbler,
				)
			}

			R.id.button_related_more -> {
				val manga = viewModel.getMangaOrNull() ?: return
				router.openRelated(manga)
			}
		}
	}

    override fun onLongClick(v: View): Boolean = when (v.id) {
		R.id.button_read -> {
			val menu = PopupMenu(v.context, v)
			menu.inflate(R.menu.popup_read)
			menu.menu.findItem(R.id.action_forget)?.isVisible = viewModel.historyInfo.value.run {
				!isIncognitoMode && history != null
			}
			menu.setOnMenuItemClickListener(this)
			menu.setForceShowIcon(true)
			menu.show()
			true
		}

		else -> false
	}

	override fun onMenuItemClick(item: MenuItem): Boolean {
		return when (item.itemId) {
			R.id.action_incognito -> {
				openReader(isIncognitoMode = true)
				true
			}

			R.id.action_forget -> {
				viewModel.removeFromHistory()
				true
			}

			else -> false
		}
	}

	override fun onChipClick(chip: Chip, data: Any?) {
		val tag = data as? MangaTag ?: return
		router.showTagDialog(tag)
	}

	override fun onItemClick(item: Bookmark, view: View) {
		router.openReader(
			ReaderIntent.Builder(view.context)
				.bookmark(item)
				.incognito()
				.build(),
			view,
		)
		Toast.makeText(view.context, R.string.incognito_mode, Toast.LENGTH_SHORT).show()
	}

	override fun onRefresh() {
		viewModel.reload()
	}

	override fun onDraw() {
		viewBinding.run {
			buttonDescriptionMore.isVisible = textViewDescription.maxLines == Int.MAX_VALUE ||
				textViewDescription.isTextTruncated
		}
	}

	override fun onLayoutChange(
		v: View?,
		left: Int,
		top: Int,
		right: Int,
		bottom: Int,
		oldLeft: Int,
		oldTop: Int,
		oldRight: Int,
		oldBottom: Int
	) {
		viewBinding.run {
			buttonDescriptionMore.isVisible = textViewDescription.isTextTruncated
		}
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val typeMask = WindowInsetsCompat.Type.systemBars()
		val barsInsets = insets.getInsets(typeMask)
		statusBarInset = barsInsets.top
		viewBinding.root.updatePadding(
			left = barsInsets.left,
			right = barsInsets.right,
		)
		viewBinding.appbar.updatePadding(top = barsInsets.top)
		viewBinding.swipeRefreshLayout.setProgressViewOffset(false, barsInsets.top, barsInsets.top + 180)
		val tv = android.util.TypedValue()
		theme.resolveAttribute(android.R.attr.actionBarSize, tv, true)
		val actionBarSize = android.util.TypedValue.complexToDimensionPixelSize(tv.data, resources.displayMetrics)
		if (viewBinding.cardChapters != null) {
			viewBinding.cardChapters?.updateLayoutParams<ViewGroup.MarginLayoutParams> {
				topMargin = barsInsets.top + resources.getDimensionPixelOffset(R.dimen.grid_spacing_outer)
				marginEnd = barsInsets.right + resources.getDimensionPixelOffset(R.dimen.side_card_offset)
				bottomMargin = barsInsets.bottom + resources.getDimensionPixelOffset(R.dimen.side_card_offset)
			}
			viewBinding.scrollView.updatePaddingRelative(
				top = actionBarSize + barsInsets.top,
				bottom = barsInsets.bottom,
				start = barsInsets.left,
			)
			viewBinding.appbar.updatePaddingRelative(
				start = barsInsets.left,
			)
			if (!settings.isBackdropEnabled) {
				viewBinding.contentContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
					topMargin = resources.getDimensionPixelOffset(R.dimen.margin_normal)
				}
			}
			return insets.consume(v, typeMask, bottom = true, end = true)
		} else {
			viewBinding.navbarDim?.updateLayoutParams { height = barsInsets.bottom }
			viewBinding.scrollView.updatePadding(
				top = actionBarSize + barsInsets.top,
				bottom = barsInsets.bottom + resources.getDimensionPixelOffset(R.dimen.details_bs_peek_height),
			)
			if (!settings.isBackdropEnabled) {
				viewBinding.contentContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
					topMargin = resources.getDimensionPixelOffset(R.dimen.margin_normal)
				}
			}
			return insets
		}
	}

	private fun getSurfaceColor(): Int {
		val ta = theme.obtainStyledAttributes(intArrayOf(android.R.attr.colorBackground))
		return try { ta.getColor(0, 0) } finally { ta.recycle() }
	}

	private fun updateAppBarScrim(scrollY: Int) {
		val alpha = if (!settings.isBackdropEnabled) 255 else {
			val threshold = resources.displayMetrics.density * SCRIM_SCROLL_THRESHOLD_DP
			(scrollY / threshold).coerceIn(0f, 1f).times(255).toInt()
		}
		viewBinding.appbar.setBackgroundColor(ColorUtils.setAlphaComponent(getSurfaceColor(), alpha))
	}

	private fun loadLargeCover(imageUrl: String?) {
		if (settings.isBackdropEnabled) {
			backdropController.load(imageUrl)
		} else {
			viewBinding.backdropContainer.isGone = true
			val isTablet = viewBinding.cardChapters != null
			viewBinding.contentContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
				topMargin = if (isTablet) resources.getDimensionPixelOffset(R.dimen.margin_normal) else statusBarInset
			}
			viewBinding.appbar.setBackgroundColor(getSurfaceColor())
		}
	}

	private fun onFavoritesChanged(categories: Set<FavouriteCategory>) {
		val chip = viewBinding.infoLayout.chipFavorite
		chip.setChipIconResource(if (categories.isEmpty()) R.drawable.ic_heart_outline else R.drawable.ic_heart)
		chip.text = if (categories.isEmpty()) {
			getString(R.string.add_to_favourites)
		} else {
			categories.joinToStringWithLimit(this, FAV_LABEL_LIMIT) { it.title }
		}
	}

	private fun onLocalSizeChanged(size: Long) {
		val chip = viewBinding.infoLayout.chipSize
		if (size == 0L) {
			chip.isVisible = false
		} else {
			chip.text = FileSize.BYTES.format(chip.context, size)
			chip.isVisible = true
		}
	}

	private fun onRelatedMangaChanged(related: List<MangaListModel>) {
		if (related.isEmpty()) {
			viewBinding.groupRelated.isVisible = false
			return
		}
		val rv = viewBinding.recyclerViewRelated

		@Suppress("UNCHECKED_CAST")
		val adapter = (rv.adapter as? BaseListAdapter<ListModel>) ?: BaseListAdapter<ListModel>()
			.addDelegate(
				ListItemType.MANGA_GRID,
				mangaGridItemAD(
					sizeResolver = StaticItemSizeResolver(resources.getDimensionPixelSize(R.dimen.smaller_grid_width)),
				) { item, _ ->
					router.openDetails(item.toMangaWithOverride())
				},
			).also { rv.adapter = it }
		adapter.items = related
		viewBinding.groupRelated.isVisible = true
		viewBinding.buttonRelatedMore.isVisible = true
	}

	private fun onLoadingStateChanged(isLoading: Boolean) {
		viewBinding.swipeRefreshLayout.isRefreshing = isLoading
	}

	private fun onScrobblingInfoChanged(scrobblings: List<org.draken.usagi.scrobbling.common.domain.model.ScrobblingInfo>) {
		var adapter = viewBinding.recyclerViewScrobbling.adapter as? ScrollingInfoAdapter
		viewBinding.groupScrobbling.isGone = scrobblings.isEmpty()
		if (adapter != null) {
			adapter.items = scrobblings
		} else {
			adapter = ScrollingInfoAdapter(router)
			adapter.items = scrobblings
			viewBinding.recyclerViewScrobbling.adapter = adapter
			viewBinding.recyclerViewScrobbling.addItemDecoration(ScrobblingItemDecoration())
		}
	}

	private fun onMangaUpdated(details: MangaDetails) {
		val manga = details.toManga()
		with(viewBinding) {
			textViewTitle.text = manga.title
			textViewSubtitle.textAndVisible = manga.altTitles.joinToString("\n")
			textViewNsfw16.isVisible = manga.contentRating == ContentRating.SUGGESTIVE
			textViewNsfw18.isVisible = manga.contentRating == ContentRating.ADULT
			textViewDescription.setTextSafely(details.description.ifNullOrEmpty { getString(R.string.no_description) })
			ratingBar.isVisible = manga.hasRating
			if (manga.hasRating) {
				ratingBar.rating = manga.rating * ratingBar.numStars
			}
			manga.state?.let { state ->
				textViewState.textAndVisible = resources.getString(state.titleResId)
				imageViewState.setImageResource(state.iconResId)
				imageViewState.isVisible = true
			} ?: run {
				textViewState.isVisible = false
				imageViewState.isVisible = false
			}
		}
		with(viewBinding.infoLayout) {
			chipAuthor.textAndVisible = manga.authors.firstOrNull()
			chipAuthor.isVisible = !chipAuthor.text.isNullOrEmpty()
			chipTime.textAndVisible = null
			chipTime.isVisible = false
			if (manga.source == LocalMangaSource || manga.source == UnknownMangaSource) {
				chipSource.isVisible = false
			} else {
				chipSource.text = manga.source.getTitle(this@DetailsClassicActivity)
				chipSource.isVisible = true
				chipSource.setTooltipCompat(manga.source.getSummary(this@DetailsClassicActivity))
				val faviconPlaceholderFactory = FaviconDrawable.Factory(R.style.FaviconDrawable_Chip)
				faviconDisposable?.dispose()
				faviconDisposable = ImageRequest.Builder(this@DetailsClassicActivity)
					.data(manga.source.faviconUri())
					.lifecycle(this@DetailsClassicActivity)
					.crossfade(false)
					.precision(Precision.EXACT)
					.size(resources.getDimensionPixelSize(materialR.dimen.m3_chip_icon_size))
					.target(ChipIconTarget(chipSource))
					.placeholder(faviconPlaceholderFactory)
					.error(faviconPlaceholderFactory)
					.fallback(faviconPlaceholderFactory)
					.mangaSourceExtra(manga.source)
					.transformations(RoundedCornersTransformation(resources.getDimension(R.dimen.chip_icon_corner)))
					.allowRgb565(true)
					.enqueueWith(coil)
			}
			chipBranch.isVisible = viewModel.branches.value.size > 1 ||
				!viewModel.branches.value.firstOrNull()?.name.isNullOrEmpty()
		}
		title = manga.title
		invalidateOptionsMenu()
	}

	private fun onMangaRemoved(manga: Manga) {
		Toast.makeText(
			this,
			getString(R.string._s_deleted_from_local_storage, manga.title),
			Toast.LENGTH_SHORT,
		).show()
		finishAfterTransition()
	}

	private fun onHistoryChanged(info: HistoryInfo, isLoading: Boolean) = with(viewBinding) {
		buttonRead.setTitle(if (info.canContinue) R.string._continue else R.string.read)
		buttonRead.subtitle = when {
			isLoading -> getString(R.string.loading_)
			info.isIncognitoMode -> getString(R.string.incognito_mode)
			info.isChapterMissing -> getString(R.string.chapter_is_missing)
			info.currentChapter >= 0 -> getString(
				R.string.chapter_d_of_d,
				info.currentChapter + 1,
				info.totalChapters,
			)

			info.totalChapters == 0 -> getString(R.string.no_chapters)
			info.totalChapters == -1 -> getString(R.string.error_occurred)
			else -> resources.getQuantityStringSafe(R.plurals.chapters, info.totalChapters, info.totalChapters)
		}
		val isFirstCall = buttonRead.tag == null
		buttonRead.tag = Unit
		buttonRead.setProgress(info.percent.coerceIn(0f, 1f), !isFirstCall)
		buttonDownload.isEnabled = info.isValid && info.canDownload
		buttonRead.isEnabled = info.isValid

        // Estimated time
        val timeText = info.estimatedTime?.formatShort(resources)
        infoLayout.chipTime.text = timeText
        infoLayout.chipTime.isVisible = !timeText.isNullOrEmpty()
	}

	private fun onTagsChanged(tags: Collection<ChipsView.ChipModel>) {
		viewBinding.chipsTags.isVisible = tags.isNotEmpty()
		viewBinding.chipsTags.setChips(tags)
	}

	private fun showBranchPopupMenu(v: View) {
		val branches = viewModel.branches.value
		if (branches.size <= 1) {
			return
		}
		val menu = PopupMenu(v.context, v)
		for ((i, branch) in branches.withIndex()) {
			val title = buildSpannedString {
				append(branch.name ?: getString(R.string.system_default))
				append(' ')
				append(' ')
				append(branch.count.toString())
			}
			val item = menu.menu.add(R.id.group_branches, Menu.NONE, i, title)
			item.isCheckable = true
			item.isChecked = branch.isSelected
		}
		menu.menu.setGroupCheckable(R.id.group_branches, true, true)
		menu.setOnMenuItemClickListener {
			viewModel.setSelectedBranch(branches.getOrNull(it.order)?.name)
			true
		}
		menu.show()
	}

	private fun openReader(isIncognitoMode: Boolean) {
		val manga = viewModel.getMangaOrNull() ?: return
		if (viewModel.historyInfo.value.isChapterMissing) {
			Toast.makeText(this, R.string.chapter_is_missing, Toast.LENGTH_SHORT).show()
		} else {
			val builder = ReaderIntent.Builder(this)
				.manga(manga)
				.branch(viewModel.selectedBranchValue)
			if (isIncognitoMode) {
				builder.incognito()
			}
			router.openReader(builder.build())
			if (isIncognitoMode) {
				Toast.makeText(this, R.string.incognito_mode, Toast.LENGTH_SHORT).show()
			}
		}
	}

	private fun loadCover(imageUrl: String?) {
		viewBinding.imageViewCover.setImageAsync(imageUrl, viewModel.getMangaOrNull())
	}

	private class PrefetchObserver(
		private val context: Context,
	) : kotlinx.coroutines.flow.FlowCollector<List<ChapterListItem>?> {

		private var isCalled = false

		override suspend fun emit(value: List<ChapterListItem>?) {
			if (value.isNullOrEmpty()) {
				return
			}
			if (!isCalled) {
				isCalled = true
				val item = value.find { it.isCurrent } ?: value.first()
				MangaPrefetchService.prefetchPages(context, item.chapter)
			}
		}
	}

	companion object {
		private const val FAV_LABEL_LIMIT = 16
		private const val SCRIM_SCROLL_THRESHOLD_DP = 160f
	}
}

