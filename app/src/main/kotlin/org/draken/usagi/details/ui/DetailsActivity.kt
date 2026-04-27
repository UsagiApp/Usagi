package org.draken.usagi.details.ui

import android.annotation.SuppressLint
import android.app.assist.AssistContent
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.text.SpannedString
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.graphics.ColorUtils
import androidx.core.text.buildSpannedString
import androidx.core.text.inSpans
import androidx.core.text.method.LinkMovementMethodCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.view.updatePaddingRelative
import androidx.core.widget.NestedScrollView
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
import com.google.android.material.shape.MaterialShapeDrawable
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
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
import org.draken.usagi.core.model.titleResId
import org.draken.usagi.core.nav.ReaderIntent
import org.draken.usagi.core.nav.router
import org.draken.usagi.core.os.AppShortcutManager
import org.draken.usagi.core.parser.favicon.faviconUri
import org.draken.usagi.core.prefs.AppSettings
import org.draken.usagi.core.ui.BaseActivity
import org.draken.usagi.core.ui.BaseListAdapter
import org.draken.usagi.core.ui.dialog.buildAlertDialog
import org.draken.usagi.core.ui.image.FaviconDrawable
import org.draken.usagi.core.ui.image.TextDrawable
import org.draken.usagi.core.ui.image.TextViewTarget
import org.draken.usagi.core.ui.list.OnListItemClickListener
import org.draken.usagi.core.ui.sheet.BottomSheetCollapseCallback
import org.draken.usagi.core.ui.util.MenuInvalidator
import org.draken.usagi.core.ui.util.ReversibleActionObserver
import org.draken.usagi.core.ui.widgets.ChipsView
import org.draken.usagi.core.util.FileSize
import org.draken.usagi.core.util.LocaleUtils
import org.draken.usagi.core.util.ext.consume
import org.draken.usagi.core.util.ext.copyToClipboard
import org.draken.usagi.core.util.ext.drawableStart
import org.draken.usagi.core.util.ext.end
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
import org.draken.usagi.core.util.ext.start
import org.draken.usagi.core.util.ext.textAndVisible
import org.draken.usagi.core.util.ext.toUriOrNull
import org.draken.usagi.databinding.ActivityDetailsBinding
import org.draken.usagi.databinding.LayoutDetailsTableBinding
import org.draken.usagi.details.data.MangaDetails
import org.draken.usagi.details.data.ReadingTime
import org.draken.usagi.details.service.MangaPrefetchService
import org.draken.usagi.details.ui.model.ChapterListItem
import org.draken.usagi.details.ui.model.HistoryInfo
import org.draken.usagi.details.ui.scrobbling.ScrobblingItemDecoration
import org.draken.usagi.details.ui.scrobbling.ScrollingInfoAdapter
import org.draken.usagi.download.ui.worker.DownloadStartedObserver
import org.draken.usagi.list.domain.ReadingProgress
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
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.toTitleCase
import org.draken.usagi.scrobbling.common.domain.model.ScrobblingInfo
import javax.inject.Inject
import kotlin.math.roundToInt
import com.google.android.material.R as materialR

@AndroidEntryPoint
class DetailsActivity :
	BaseActivity<ActivityDetailsBinding>(),
	View.OnClickListener,
	View.OnLayoutChangeListener,
	ViewTreeObserver.OnDrawListener,
	ChipsView.OnChipClickListener,
	OnListItemClickListener<Bookmark>,
	SwipeRefreshLayout.OnRefreshListener,
	AuthorSpan.OnAuthorClickListener,
	BottomSheetOwner {

	@Inject lateinit var shortcutManager: AppShortcutManager
	@Inject lateinit var coil: ImageLoader
	@Inject lateinit var settings: AppSettings

	private val viewModel: DetailsViewModel by viewModels()
	private lateinit var menuProvider: DetailsMenuProvider
	private lateinit var infoBinding: LayoutDetailsTableBinding
	private lateinit var backdropController: BackdropController
	private var statusBarInset: Int = 0
	private var faviconDisposable: Disposable? = null

	override val bottomSheet: View?
		get() = viewBinding.containerBottomSheet

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(ActivityDetailsBinding.inflate(layoutInflater))
		infoBinding = LayoutDetailsTableBinding.bind(viewBinding.root)
		WindowCompat.setDecorFitsSystemWindows(window, false)
		enableEdgeToEdge()
		WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false
		backdropController = BackdropController(
			backdrop = viewBinding.backdrop,
			backdropGradient = viewBinding.backdropGradient,
			backdropTopGradient = viewBinding.backdropTopGradient,
			context = this,
			imageLoader = coil,
			lifecycle = this,
			settings = settings,
		)
		viewBinding.scrollView.setOnScrollChangeListener(
			NestedScrollView.OnScrollChangeListener { _, _, scrollY, _, _ ->
				if (settings.isBackdropEnabled) {
					viewBinding.backdropContainer.translationY = -scrollY.toFloat()
				}
				updateAppBarScrim(scrollY)
				val titleView = viewBinding.textViewTitle
				val loc = IntArray(2)
				titleView.getLocationOnScreen(loc)
				val titleBottom = loc[1] + titleView.height
				viewBinding.appbar.getLocationOnScreen(loc)
				val appBarBottom = loc[1] + viewBinding.appbar.height
				supportActionBar?.setDisplayShowTitleEnabled(titleBottom < appBarBottom)
			},
		)
		setDisplayHomeAsUp(isEnabled = true, showUpAsClose = false)
		supportActionBar?.setDisplayShowTitleEnabled(false)
		viewBinding.chipFavorite.setOnClickListener(this)
		infoBinding.textViewLocal.setOnClickListener(this)
		infoBinding.textViewSource.setOnClickListener(this)
		viewBinding.imageViewCover.setOnClickListener(this)
		viewBinding.backdropClickArea.setOnClickListener(this)
		viewBinding.textViewTitle.setOnClickListener(this)
		viewBinding.buttonDescriptionMore.setOnClickListener(this)
		viewBinding.buttonScrobblingMore.setOnClickListener(this)
		viewBinding.buttonRelatedMore.setOnClickListener(this)
		viewBinding.textViewDescription.addOnLayoutChangeListener(this)
		viewBinding.swipeRefreshLayout.setOnRefreshListener(this)
		viewBinding.textViewDescription.viewTreeObserver.addOnDrawListener(this)
		infoBinding.textViewAuthor.movementMethod = LinkMovementMethodCompat.getInstance()
		viewBinding.textViewDescription.movementMethod = LinkMovementMethodCompat.getInstance()
		viewBinding.chipsTags.onChipClickListener = this
		if (settings.isDescriptionExpanded) {
			viewBinding.textViewDescription.maxLines = Int.MAX_VALUE - 1
		}
		viewBinding.containerBottomSheet?.let { sheet ->
			sheet.setOnClickListener(this)
			sheet.addOnLayoutChangeListener(this)
			onBackPressedDispatcher.addCallback(BottomSheetCollapseCallback(sheet))
			BottomSheetBehavior.from(sheet).addBottomSheetCallback(
				DetailsBottomSheetCallback(viewBinding.swipeRefreshLayout, checkNotNull(viewBinding.navbarDim)),
			)
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

	override fun onProvideAssistContent(outContent: AssistContent) {
		super.onProvideAssistContent(outContent)
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
			viewModel.getMangaOrNull()?.publicUrl?.toUriOrNull()?.let { outContent.webUri = it }
		}
	}

	override fun onDestroy() {
		faviconDisposable?.dispose()
		faviconDisposable = null
		super.onDestroy()
	}

	override fun isNsfwContent(): Flow<Boolean> = viewModel.manga.map { it?.contentRating == ContentRating.ADULT }

	override fun onClick(v: View) {
		when (v.id) {
			R.id.textView_source -> {
				val manga = viewModel.getMangaOrNull() ?: return
				router.openList(manga.source, null, null)
			}
			R.id.textView_local -> {
				val manga = viewModel.getMangaOrNull() ?: return
				router.showLocalInfoDialog(manga)
			}
			R.id.chip_favorite -> {
				val manga = viewModel.getMangaOrNull() ?: return
				router.showFavoriteDialog(manga)
			}
			R.id.imageView_cover -> {
				val manga = viewModel.getMangaOrNull() ?: return
				router.openImage(
					url = viewModel.coverUrl.value ?: return,
					source = manga.source,
					preview = CoilMemoryCacheKey.from(viewBinding.imageViewCover),
					anchor = v,
				)
			}
			R.id.backdrop_click_area -> {
				val manga = viewModel.getMangaOrNull() ?: return
				router.openImage(
					url = viewModel.backdropUrl.value ?: return,
					source = manga.source,
					preview = CoilMemoryCacheKey.from(viewBinding.backdrop),
					anchor = v,
				)
			}
			R.id.button_description_more -> {
				val tv = viewBinding.textViewDescription
				if (tv.context.isAnimationsEnabled) {
					tv.parentView?.let { TransitionManager.beginDelayedTransition(it) }
				}
				tv.maxLines = if (tv.maxLines in 1 until Integer.MAX_VALUE) {
					Integer.MAX_VALUE
				} else {
					resources.getInteger(R.integer.details_description_lines)
				}
			}
			R.id.button_scrobbling_more -> {
				router.showScrobblingSelectorSheet(
					manga = viewModel.getMangaOrNull() ?: return,
					scrobblerService = viewModel.scrobblingInfo.value.firstOrNull()?.scrobbler,
				)
			}
			R.id.button_related_more -> {
				val manga = viewModel.getMangaOrNull() ?: return
				router.openRelated(manga)
			}
			R.id.textView_title -> {
				val title = viewModel.getMangaOrNull()?.title?.nullIfEmpty() ?: return
				buildAlertDialog(this) {
					setMessage(title)
					setNegativeButton(R.string.close, null)
					setPositiveButton(androidx.preference.R.string.copy) { _, _ ->
						copyToClipboard(getString(R.string.content_type_manga), title)
					}
				}.show()
			}
		}
	}

	override fun onAuthorClick(author: String) {
		router.showAuthorDialog(author, viewModel.getMangaOrNull()?.source ?: return)
	}

	override fun onChipClick(chip: Chip, data: Any?) {
		val tag = data as? MangaTag ?: return
		router.showTagDialog(tag)
	}

	override fun onItemClick(item: Bookmark, view: View) {
		router.openReader(ReaderIntent.Builder(view.context).bookmark(item).incognito().build())
		Toast.makeText(view.context, R.string.incognito_mode, Toast.LENGTH_SHORT).show()
	}

	override fun onRefresh() = viewModel.reload()

	override fun onDraw() {
		viewBinding.buttonDescriptionMore.isVisible = viewBinding.textViewDescription.maxLines == Int.MAX_VALUE ||
			viewBinding.textViewDescription.isTextTruncated
	}

	override fun onLayoutChange(
		v: View?, left: Int, top: Int, right: Int, bottom: Int,
		oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int,
	) {
		viewBinding.containerBottomSheet?.let { sheet ->
			val peekHeight = BottomSheetBehavior.from(sheet).peekHeight
			if (viewBinding.scrollView.paddingBottom != peekHeight) {
				viewBinding.scrollView.updatePadding(bottom = peekHeight)
			}
		}
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val typeMask = WindowInsetsCompat.Type.systemBars()
		val barsInsets = insets.getInsets(typeMask)
		statusBarInset = barsInsets.top
		if (viewBinding.cardChapters != null) {
			viewBinding.appbar.updatePadding(top = barsInsets.top)
			viewBinding.cardChapters?.updateLayoutParams<ViewGroup.MarginLayoutParams> {
				marginEnd = barsInsets.end(v) + resources.getDimensionPixelOffset(R.dimen.side_card_offset)
				bottomMargin = barsInsets.bottom + resources.getDimensionPixelOffset(R.dimen.side_card_offset)
			}
			val tv = android.util.TypedValue()
			theme.resolveAttribute(android.R.attr.actionBarSize, tv, true)
			val actionBarSize = android.util.TypedValue.complexToDimensionPixelSize(tv.data, resources.displayMetrics)
			viewBinding.scrollView.updatePaddingRelative(
				top = actionBarSize + barsInsets.top,
				bottom = barsInsets.bottom,
				start = barsInsets.start(v),
			)
			viewBinding.swipeRefreshLayout.setProgressViewOffset(false, barsInsets.top, barsInsets.top + 180)
			viewBinding.appbar.updatePaddingRelative(start = barsInsets.start(v))
			if (!settings.isBackdropEnabled) {
				viewBinding.contentContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
					topMargin = resources.getDimensionPixelOffset(R.dimen.margin_normal)
				}
			}
			return insets.consume(v, typeMask, bottom = true, end = true)
		} else {
			viewBinding.navbarDim?.updateLayoutParams { height = barsInsets.bottom }
			viewBinding.appbar.updatePadding(top = barsInsets.top)
			viewBinding.swipeRefreshLayout.setProgressViewOffset(false, barsInsets.top, barsInsets.top + 180)
			if (!settings.isBackdropEnabled) {
				viewBinding.contentContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
					topMargin = barsInsets.top
				}
			}
			return insets
		}
	}

	private fun getSurfaceColor(): Int {
		val ta = theme.obtainStyledAttributes(intArrayOf(android.R.attr.colorBackground))
		return try { ta.getColor(0, 0) } finally { ta.recycle() }
	}

	private fun onFavoritesChanged(categories: Set<FavouriteCategory>) {
		val chip = viewBinding.chipFavorite
		chip.background.unwrapToMaterialShape()?.let { shape ->
			val current = shape.fillColor?.defaultColor ?: return@let
			shape.fillColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(current, 150))
		}
		chip.setChipIconResource(if (categories.isEmpty()) R.drawable.ic_heart_outline else R.drawable.ic_heart)
		chip.text = categories.takeIf { it.isNotEmpty() }
			?.joinToStringWithLimit(this, FAV_LABEL_LIMIT) { it.title }
			?: getString(R.string.add_to_favourites)
	}

	private fun onLocalSizeChanged(size: Long) {
		val visible = size != 0L
		infoBinding.textViewLocal.isVisible = visible
		infoBinding.textViewLocalLabel.isVisible = visible
		if (visible) infoBinding.textViewLocal.text = FileSize.BYTES.format(this, size)
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
				) { item, _ -> router.openDetails(item.toMangaWithOverride()) },
			).also { rv.adapter = it }
		adapter.items = related
		viewBinding.groupRelated.isVisible = true
	}

	private fun onLoadingStateChanged(isLoading: Boolean) {
		viewBinding.swipeRefreshLayout.isRefreshing = isLoading
	}

	private fun onScrobblingInfoChanged(scrobblings: List<ScrobblingInfo>) {
		viewBinding.groupScrobbling.isGone = scrobblings.isEmpty()
		val adapter = viewBinding.recyclerViewScrobbling.adapter as? ScrollingInfoAdapter
			?: ScrollingInfoAdapter(router).also { newAdapter ->
				viewBinding.recyclerViewScrobbling.adapter = newAdapter
				viewBinding.recyclerViewScrobbling.addItemDecoration(ScrobblingItemDecoration())
			}
		adapter.items = scrobblings
	}

	private fun onMangaUpdated(details: MangaDetails) {
		val manga = details.toManga()
		with(viewBinding) {
			textViewTitle.text = manga.title
			textViewSubtitle.textAndVisible = manga.altTitles.joinToString("\n")
			textViewNsfw16.isVisible = manga.contentRating == ContentRating.SUGGESTIVE
			textViewNsfw18.isVisible = manga.contentRating == ContentRating.ADULT
			textViewDescription.setTextSafely(details.description.ifNullOrEmpty { getString(R.string.no_description) })
		}
		with(infoBinding) {
			val translation = details.getLocale()
			textViewTranslation.textAndVisible = translation?.getDisplayLanguage(translation)?.toTitleCase(translation)
			textViewTranslation.drawableStart = translation?.let { LocaleUtils.getEmojiFlag(it) }
				?.let { TextDrawable.compound(textViewTranslation, it) }
			textViewTranslationLabel.isVisible = textViewTranslation.isVisible
			textViewAuthor.textAndVisible = manga.getAuthorsString()
			textViewAuthorLabel.isVisible = textViewAuthor.isVisible
			if (manga.hasRating) {
				ratingBarRating.rating = manga.rating * ratingBarRating.numStars
				ratingBarRating.isVisible = true
				textViewRatingLabel.isVisible = true
			} else {
				ratingBarRating.isVisible = false
				textViewRatingLabel.isVisible = false
			}
			manga.state?.let { state ->
				textViewState.textAndVisible = resources.getString(state.titleResId)
				textViewStateLabel.isVisible = textViewState.isVisible
			} ?: run {
				textViewState.isVisible = false
				textViewStateLabel.isVisible = false
			}
			if (manga.source == LocalMangaSource || manga.source == UnknownMangaSource) {
				textViewSource.isVisible = false
				textViewSourceLabel.isVisible = false
			} else {
				textViewSource.textAndVisible = manga.source.getTitle(this@DetailsActivity)
				textViewSource.setTooltipCompat(manga.source.getSummary(this@DetailsActivity))
				textViewSourceLabel.isVisible = textViewSource.isVisible == true
			}
			val faviconPlaceholderFactory = FaviconDrawable.Factory(R.style.FaviconDrawable_Chip)
			faviconDisposable?.dispose()
			faviconDisposable = ImageRequest.Builder(this@DetailsActivity)
				.data(manga.source.faviconUri())
				.lifecycle(this@DetailsActivity)
				.crossfade(false)
				.precision(Precision.EXACT)
				.size(resources.getDimensionPixelSize(materialR.dimen.m3_chip_icon_size))
				.target(TextViewTarget(textViewSource, Gravity.START))
				.placeholder(faviconPlaceholderFactory)
				.error(faviconPlaceholderFactory)
				.fallback(faviconPlaceholderFactory)
				.mangaSourceExtra(manga.source)
				.transformations(RoundedCornersTransformation(resources.getDimension(R.dimen.chip_icon_corner)))
				.allowRgb565(true)
				.enqueueWith(coil)
		}
		title = manga.title
		invalidateOptionsMenu()
	}

	private fun onMangaRemoved(manga: Manga) {
		Toast.makeText(this, getString(R.string._s_deleted_from_local_storage, manga.title), Toast.LENGTH_SHORT).show()
		finishAfterTransition()
	}

	private fun onHistoryChanged(info: HistoryInfo, isLoading: Boolean) = with(infoBinding) {
		textViewChapters.text = when {
			isLoading -> getString(R.string.loading_)
			info.currentChapter >= 0 -> getString(
				R.string.chapter_d_of_d,
				info.currentChapter + 1,
				info.totalChapters,
			).withEstimatedTime(info.estimatedTime)
			info.totalChapters == 0 -> getString(R.string.no_chapters)
			info.totalChapters == -1 -> getString(R.string.error_occurred)
			else -> resources.getQuantityStringSafe(R.plurals.chapters, info.totalChapters, info.totalChapters)
				.withEstimatedTime(info.estimatedTime)
		}
		textViewProgress.textAndVisible = if (info.percent <= 0f) {
			null
		} else {
			val displayPercent = if (ReadingProgress.isCompleted(info.percent)) 100 else (info.percent * 100f).toInt()
			getString(R.string.percent_string_pattern, displayPercent.toString())
		}
		progress.setProgressCompat((progress.max * info.percent.coerceIn(0f, 1f)).roundToInt(), true)
		val hasHistory = info.history != null
		textViewProgressLabel.isVisible = hasHistory
		textViewProgress.isVisible = hasHistory
		progress.isVisible = hasHistory
	}

	private fun onTagsChanged(tags: Collection<ChipsView.ChipModel>) {
		viewBinding.chipsTags.isVisible = tags.isNotEmpty()
		viewBinding.chipsTags.setChips(tags)
	}

	private fun loadCover(imageUrl: String?) {
		viewBinding.imageViewCover.setImageAsync(imageUrl, viewModel.getMangaOrNull())
	}

	private fun loadLargeCover(imageUrl: String?) {
		if (settings.isBackdropEnabled) {
			backdropController.load(imageUrl)
		} else {
			viewBinding.backdropContainer.isGone = true
			val isTablet = viewBinding.cardChapters != null
			viewBinding.contentContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
				topMargin = if (isTablet) 0 else statusBarInset
			}
			viewBinding.appbar.setBackgroundColor(getSurfaceColor())
		}
	}

	private fun updateAppBarScrim(scrollY: Int) {
		val alpha = if (!settings.isBackdropEnabled) 255 else {
			val threshold = resources.displayMetrics.density * SCRIM_SCROLL_THRESHOLD_DP
			(scrollY / threshold).coerceIn(0f, 1f).times(255).toInt()
		}
		viewBinding.appbar.setBackgroundColor(ColorUtils.setAlphaComponent(getSurfaceColor(), alpha))
	}

	private fun String.withEstimatedTime(time: ReadingTime?): String {
		time ?: return this
		return getString(R.string.chapters_time_pattern, this, time.formatShort(resources))
	}

	@SuppressLint("UseCompatLoadingForDrawables")
	private fun Drawable.unwrapToMaterialShape(): MaterialShapeDrawable? = when (this) {
		is MaterialShapeDrawable -> this
		is InsetDrawable -> drawable?.unwrapToMaterialShape()
		is RippleDrawable -> getDrawable(0)?.unwrapToMaterialShape()
		else -> null
	}

	private fun Manga.getAuthorsString(): SpannedString? {
		if (authors.isEmpty()) return null
		return buildSpannedString {
			authors.forEach { a ->
				if (a.isNotEmpty()) {
					if (isNotEmpty()) append(", ")
					inSpans(AuthorSpan(this@DetailsActivity)) { append(a) }
				}
			}
		}.nullIfEmpty()
	}

	private class PrefetchObserver(private val context: Context) : FlowCollector<List<ChapterListItem>?> {
		private var isCalled = false
		override suspend fun emit(value: List<ChapterListItem>?) {
			if (value.isNullOrEmpty() || isCalled) return
			isCalled = true
			val item = value.find { it.isCurrent } ?: value.first()
			MangaPrefetchService.prefetchPages(context, item.chapter)
		}
	}

	companion object {
		private const val FAV_LABEL_LIMIT = 16
		private const val SCRIM_SCROLL_THRESHOLD_DP = 160f
	}
}
