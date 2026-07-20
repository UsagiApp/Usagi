package org.draken.usagi.main.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import androidx.activity.viewModels
import androidx.appcompat.view.ActionMode
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.toColorInt
import androidx.core.view.MenuProvider
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.children
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import androidx.recyclerview.widget.ItemTouchHelper
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.AppBarLayout.LayoutParams.SCROLL_FLAG_ENTER_ALWAYS
import com.google.android.material.appbar.AppBarLayout.LayoutParams.SCROLL_FLAG_NO_SCROLL
import com.google.android.material.appbar.AppBarLayout.LayoutParams.SCROLL_FLAG_SCROLL
import com.google.android.material.appbar.AppBarLayout.LayoutParams.SCROLL_FLAG_SNAP
import com.google.android.material.color.MaterialColors
import com.google.android.material.search.SearchView
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.draken.usagi.R
import org.draken.usagi.backups.ui.periodical.PeriodicalBackupService
import org.draken.usagi.browser.AdListUpdateService
import org.draken.usagi.core.exceptions.resolve.SnackbarErrorObserver
import org.draken.usagi.core.github.AppVersion
import org.draken.usagi.core.nav.router
import org.draken.usagi.core.os.VoiceInputContract
import org.draken.usagi.core.prefs.AppSettings
import org.draken.usagi.core.prefs.NavItem
import org.draken.usagi.core.ui.BaseActivity
import org.draken.usagi.core.ui.AppCrashActivity
import org.draken.usagi.core.ui.dialog.BigButtonsAlertDialog
import org.draken.usagi.core.ui.util.FadingAppbarMediator
import org.draken.usagi.core.ui.util.MenuInvalidator
import org.draken.usagi.core.ui.widgets.SlidingBottomNavigationView
import org.draken.usagi.core.util.ext.consume
import org.draken.usagi.core.util.ext.end
import org.draken.usagi.core.util.ext.observe
import org.draken.usagi.core.util.ext.observeEvent
import org.draken.usagi.core.util.ext.setContentDescriptionAndTooltip
import org.draken.usagi.core.util.ext.start
import org.draken.usagi.databinding.ActivityMainBinding
import org.draken.usagi.details.service.MangaPrefetchService
import org.draken.usagi.favourites.ui.container.FavouritesContainerFragment
import org.draken.usagi.history.ui.HistoryListFragment
import org.draken.usagi.local.ui.LocalIndexUpdateService
import org.draken.usagi.local.ui.LocalStorageCleanupWorker
import org.draken.usagi.main.ui.nav.ScrollListener
import org.draken.usagi.main.ui.owners.AppBarOwner
import org.draken.usagi.main.ui.owners.BottomNavOwner
import org.draken.usagi.remotelist.ui.MangaSearchMenuProvider
import org.draken.usagi.search.ui.suggestion.SearchSuggestionItemCallback
import org.draken.usagi.search.ui.suggestion.SearchSuggestionListenerImpl
import org.draken.usagi.search.ui.suggestion.SearchSuggestionMenuProvider
import org.draken.usagi.search.ui.suggestion.SearchSuggestionViewModel
import org.draken.usagi.search.ui.suggestion.adapter.SearchSuggestionAdapter
import tsuki.model.Manga
import javax.inject.Inject
import androidx.appcompat.R as appcompatR
import com.google.android.material.R as materialR

@AndroidEntryPoint
class MainActivity : BaseActivity<ActivityMainBinding>(), AppBarOwner, BottomNavOwner,
	View.OnClickListener,
	SearchSuggestionItemCallback.SuggestionItemListener,
	MainNavigationDelegate.OnFragmentChangedListener,
	View.OnLayoutChangeListener,
	SearchView.TransitionListener {

	@Inject
	lateinit var settings: AppSettings

	private val viewModel by viewModels<MainViewModel>()
	private val searchSuggestionViewModel by viewModels<SearchSuggestionViewModel>()
	private val voiceInputLauncher = registerForActivityResult(VoiceInputContract()) { result ->
		if (result != null) {
			viewBinding.searchView.setText(result)
		}
	}
	private lateinit var navigationDelegate: MainNavigationDelegate
	private lateinit var fadingAppbarMediator: FadingAppbarMediator
	private var isFloatNav = false

	override val appBar: AppBarLayout
		get() = viewBinding.appbar

	override val bottomNav: SlidingBottomNavigationView?
		get() = viewBinding.bottomNav

	override fun onCreate(savedInstanceState: Bundle?) {
		sendBroadcast(Intent(AppCrashActivity.ACTION_FINISH_CRASH).setPackage(packageName))
		super.onCreate(savedInstanceState)
		setContentView(ActivityMainBinding.inflate(layoutInflater))
		setSupportActionBar(viewBinding.searchBar)

		viewBinding.fab?.setOnClickListener(this)
		viewBinding.fabFloating?.setOnClickListener(this)
		viewBinding.fabFloating?.setContentDescriptionAndTooltip(R.string._continue)
		viewBinding.navRail?.headerView?.findViewById<View>(R.id.railFab)?.setOnClickListener(this)
		fadingAppbarMediator =
			FadingAppbarMediator(viewBinding.appbar, viewBinding.layoutSearch ?: viewBinding.searchBar)

		navigationDelegate = MainNavigationDelegate(
			navBar = checkNotNull(bottomNav ?: viewBinding.navRail),
			fragmentManager = supportFragmentManager,
			settings = settings,
		)
		navigationDelegate.addOnFragmentChangedListener(this)
		isFloatNav = settings.isFloatingNav
		viewBinding.bottomNav?.isVisible = !isFloatNav
		viewBinding.floatingNavContainer?.isVisible = isFloatNav
		viewBinding.floatingNavContainer?.layoutTransition?.let { transition ->
			transition.setAnimator(android.animation.LayoutTransition.APPEARING, null)
			transition.setAnimator(android.animation.LayoutTransition.DISAPPEARING, null)
		}
		val floatingContent = viewBinding.floatingNavContent
		if (isFloatNav && floatingContent != null) navigationDelegate.attach(floatingContent)
		navigationDelegate.onCreate(this, savedInstanceState)
		supportFragmentManager.executePendingTransactions()
		viewBinding.textViewTitle?.let { tv ->
			navigationDelegate.observeTitle().observe(this) { tv.text = it }
		}

		addMenuProvider(MainMenuProvider(router, viewModel))

		val exitCallback = ExitCallback(this, viewBinding.container)
		onBackPressedDispatcher.addCallback(exitCallback)
		onBackPressedDispatcher.addCallback(navigationDelegate)

		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !resources.getBoolean(R.bool.is_predictive_back_enabled)) {
			val legacySearchCallback = SearchViewLegacyBackCallback(viewBinding.searchView)
			viewBinding.searchView.addTransitionListener(legacySearchCallback)
			onBackPressedDispatcher.addCallback(legacySearchCallback)
		}

		if (savedInstanceState == null) {
			onFirstStart()
		}

		viewModel.onOpenReader.observeEvent(this, this::onOpenReader)
		viewModel.onError.observeEvent(this, SnackbarErrorObserver(viewBinding.container, null))
		viewModel.isLoading.observe(this, this::onLoadingStateChanged)
		viewModel.isResumeEnabled.observe(this, this::onResumeEnabledChanged)
		viewModel.feedCounter.observe(this, ::onFeedCounterChanged)
		viewModel.appUpdate.observe(this, MenuInvalidator(this))
		viewModel.appUpdate.observe(this, ::showUpdateDialog)
		viewModel.onFirstStart.observeEvent(this) { router.showWelcomeSheet() }
		viewModel.isBottomNavPinned.observe(this, ::setNavbarPinned)
		viewModel.isFloatingNav.observe(this, ::setFloatNav)
		searchSuggestionViewModel.isIncognitoModeEnabled.observe(this, this::onIncognitoModeChanged)
		viewBinding.bottomNav?.addOnLayoutChangeListener(this)
		viewBinding.floatingNavContainer?.addOnLayoutChangeListener(this)
		if (isDarkAmoledTheme()) {
			viewBinding.bottomNav?.apply {
				val primary = MaterialColors.getColor(this, appcompatR.attr.colorPrimary, Color.WHITE)
				setBackgroundColor(Color.BLACK)
				itemTextColor = ColorStateList(
					arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf()),
					intArrayOf(primary, "#99FFFFFF".toColorInt()),
				).also { itemIconTintList = it }
				itemActiveIndicatorColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(primary, 38))
			}
		}
		viewBinding.searchView.addTransitionListener(this)
		viewBinding.searchView.addTransitionListener(exitCallback)
		initSearch()
	}

	override fun onRestoreInstanceState(savedInstanceState: Bundle) {
		super.onRestoreInstanceState(savedInstanceState)
		adjustSearchUI(viewBinding.searchView.isShowing)
		navigationDelegate.syncSelectedItem()
	}

	override fun onFragmentChanged(fragment: Fragment, fromUser: Boolean) {
		adjustFabVisibility(topFragment = fragment)
		adjustAppbar(topFragment = fragment)
		if (fromUser) {
			actionModeDelegate.finishActionMode()
			viewBinding.appbar.setExpanded(true)
		}
	}

	override fun addMenuProvider(provider: MenuProvider, owner: LifecycleOwner, state: Lifecycle.State) {
		if (provider !is MangaSearchMenuProvider) {
			super.addMenuProvider(provider, owner, state)
		}
	}

	override fun onClick(v: View) {
		when (v.id) {
			R.id.fab, R.id.railFab, R.id.fabFloating -> viewModel.openLastReader()
		}
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val typeMask = WindowInsetsCompat.Type.systemBars()
		val barsInsets = insets.getInsets(typeMask)
		val searchBarDefaultMargin = resources.getDimensionPixelOffset(materialR.dimen.m3_searchbar_margin_horizontal)
		viewBinding.searchBar.updateLayoutParams<MarginLayoutParams> {
			marginEnd = searchBarDefaultMargin + barsInsets.end(v)
			marginStart = if (viewBinding.navRail != null) {
				searchBarDefaultMargin
			} else {
				searchBarDefaultMargin + barsInsets.start(v)
			}
		}
		viewBinding.bottomNav?.updatePadding(
			left = barsInsets.left,
			right = barsInsets.right,
			bottom = barsInsets.bottom,
		)
		viewBinding.navRail?.updateLayoutParams<MarginLayoutParams> {
			marginStart = barsInsets.start(v)
			topMargin = barsInsets.top
			bottomMargin = barsInsets.bottom
		}
		viewBinding.floatingNavContainer?.updateLayoutParams<MarginLayoutParams> {
			bottomMargin = barsInsets.bottom + resources.getDimensionPixelOffset(R.dimen.margin_normal) + resources.getDimensionPixelOffset(R.dimen.margin_small)
		}
		updateMargin()
		return insets.consume(v, typeMask, start = viewBinding.navRail != null).also {
			handleSearchSuggestionsInsets(it)
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
		oldBottom: Int,
	) {
		if (left != oldLeft || right != oldRight || top != oldTop || bottom != oldBottom) {
			if (v === viewBinding.bottomNav) updateMargin()
			adjustFabVisibility()
		}
	}

	override fun onStateChanged(
		searchView: SearchView,
		previousState: SearchView.TransitionState,
		newState: SearchView.TransitionState,
	) {
		val wasOpened = previousState >= SearchView.TransitionState.SHOWING
		val isOpened = newState >= SearchView.TransitionState.SHOWING
		if (isOpened != wasOpened) {
			adjustSearchUI(isOpened)
		}
	}

	override fun onRemoveQuery(query: String) {
		searchSuggestionViewModel.deleteQuery(query)
	}

	override fun onSupportActionModeStarted(mode: ActionMode) {
		super.onSupportActionModeStarted(mode)
		adjustFabVisibility()
		bottomNav?.hide()
		viewBinding.floatingNavContainer?.isVisible = false
		(viewBinding.layoutSearch ?: viewBinding.searchBar).isInvisible = true
		updateMargin()
	}

	override fun onSupportActionModeFinished(mode: ActionMode) {
		super.onSupportActionModeFinished(mode)
		adjustFabVisibility()
		if (isFloatNav) { bottomNav?.hide() } else bottomNav?.show()
		viewBinding.floatingNavContainer?.isVisible = isFloatNav
		(viewBinding.layoutSearch ?: viewBinding.searchBar).isInvisible = false
		updateMargin()
	}

	override fun onResume() {
		super.onResume()
		supportFragmentManager.executePendingTransactions()
		adjustFabVisibility()
	}

	private fun onOpenReader(manga: Manga) {
		val fab = (if (isFloatNav) viewBinding.fabFloating else viewBinding.fab) ?: viewBinding.navRail?.headerView
		router.openReader(manga, fab)
	}

	private fun onFeedCounterChanged(counter: Int) {
		navigationDelegate.setCounter(NavItem.FEED, counter)
	}

	private fun onIncognitoModeChanged(isIncognito: Boolean) {
		var options = viewBinding.searchView.getEditText().imeOptions
		options = if (isIncognito) {
			options or EditorInfoCompat.IME_FLAG_NO_PERSONALIZED_LEARNING
		} else {
			options and EditorInfoCompat.IME_FLAG_NO_PERSONALIZED_LEARNING.inv()
		}
		viewBinding.searchView.getEditText().imeOptions = options
		invalidateOptionsMenu()
	}

	private fun onLoadingStateChanged(isLoading: Boolean) {
		val fab = (if (isFloatNav) viewBinding.fabFloating else viewBinding.fab)
			?: viewBinding.navRail?.headerView
			?: return
		fab.isEnabled = !isLoading
	}

	private fun onResumeEnabledChanged(isEnabled: Boolean) {
		adjustFabVisibility(isResumeEnabled = isEnabled)
	}

	private fun onFirstStart() = try {
		lifecycleScope.launch(Dispatchers.Main) {
			withContext(Dispatchers.Default) {
				LocalStorageCleanupWorker.enqueue(applicationContext)
			}
			withResumed {
				MangaPrefetchService.prefetchLast(this@MainActivity)
				requestNotificationsPermission()
				startService(Intent(this@MainActivity, LocalIndexUpdateService::class.java))
				startService(Intent(this@MainActivity, PeriodicalBackupService::class.java))
				if (settings.isAdBlockEnabled) {
					startService(Intent(this@MainActivity, AdListUpdateService::class.java))
				}
				viewModel.runAutoUpdate()
			}
		}
	} catch (e: IllegalStateException) {
		throw e
	}

	private fun showUpdateDialog(version: AppVersion?) {
		if (version == null) return
		if (!settings.isUpdateReminderEnabled) return
		if (viewModel.isUpdateDialogShown) return
		viewModel.isUpdateDialogShown = true
		if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
		BigButtonsAlertDialog.Builder(this)
			.setIcon(R.drawable.ic_app_update)
			.setTitle(R.string.update_available_message)
			.setPositiveButton(R.string.update) { _, _ ->
				router.openAppUpdate()
			}
			.setNegativeButton(android.R.string.cancel, null)
			.create()
			.show()
	}

	private fun adjustAppbar(topFragment: Fragment) {
		if (topFragment is FavouritesContainerFragment) {
			viewBinding.appbar.fitsSystemWindows = true
			fadingAppbarMediator.bind()
		} else {
			viewBinding.appbar.fitsSystemWindows = false
			fadingAppbarMediator.unbind()
		}
	}

	private fun adjustFabVisibility(
		isResumeEnabled: Boolean = viewModel.isResumeEnabled.value,
		topFragment: Fragment? = navigationDelegate.primaryFragment,
		isSearchOpened: Boolean = viewBinding.searchView.isShowing,
	) {
		navigationDelegate.navRailHeader?.railFab?.isVisible = isResumeEnabled
		val shouldShow = isResumeEnabled && !actionModeDelegate.isActionModeStarted && !isSearchOpened && topFragment is HistoryListFragment
		if (isFloatNav) {
			viewBinding.fab?.isVisible = false
			val fab = viewBinding.fabFloating
			val container = viewBinding.floatingNavContainer
			if (container != null && fab != null) {
				fab.tag = shouldShow
				val lp = container.layoutParams as? CoordinatorLayout.LayoutParams
				val behavior = lp?.behavior as? ScrollListener
				behavior?.update(container)
				if (!shouldShow) behavior?.reset(container)
			}
		} else {
			val fab = viewBinding.fabFloating
			if (fab != null) {
				fab.tag = false
				fab.visibility = View.GONE
				fab.translationX = 0f
			}
			viewBinding.fab?.isVisible = shouldShow
		}
	}

	private fun adjustSearchUI(isOpened: Boolean) {
		val appBarScrollFlags = if (isOpened) {
			SCROLL_FLAG_NO_SCROLL
		} else {
			SCROLL_FLAG_SCROLL or SCROLL_FLAG_ENTER_ALWAYS or SCROLL_FLAG_SNAP
		}
		viewBinding.insetsHolder.updateLayoutParams<AppBarLayout.LayoutParams> {
			scrollFlags = appBarScrollFlags
		}
		adjustFabVisibility(isSearchOpened = isOpened)
		if (isFloatNav) { bottomNav?.hide() } else bottomNav?.showOrHide(!isOpened)
		viewBinding.floatingNavContainer?.isVisible = !isOpened && isFloatNav
		updateMargin()
	}

	private fun requestNotificationsPermission() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(
				this,
				Manifest.permission.POST_NOTIFICATIONS,
			) != PERMISSION_GRANTED
		) {
			ActivityCompat.requestPermissions(
				this,
				arrayOf(Manifest.permission.POST_NOTIFICATIONS),
				1,
			)
		}
	}

	private fun handleSearchSuggestionsInsets(insets: WindowInsetsCompat) {
		val typeMask = WindowInsetsCompat.Type.ime() or WindowInsetsCompat.Type.systemBars()
		val barsInsets = insets.getInsets(typeMask)
		viewBinding.recyclerViewSearch.setPadding(barsInsets.left, 0, barsInsets.right, barsInsets.bottom)
	}

	private fun initSearch() {
		val listener = SearchSuggestionListenerImpl(router, viewBinding.searchView, searchSuggestionViewModel)
		val adapter = SearchSuggestionAdapter(listener)
		viewBinding.searchView.toolbar.addMenuProvider(
			SearchSuggestionMenuProvider(this, voiceInputLauncher, searchSuggestionViewModel),
		)
		viewBinding.searchView.editText.addTextChangedListener(listener)
		viewBinding.recyclerViewSearch.adapter = adapter
		viewBinding.searchView.editText.setOnEditorActionListener(listener)

		viewBinding.searchView.observeState()
			.map { it >= SearchView.TransitionState.SHOWING }
			.distinctUntilChanged()
			.flatMapLatest { isShowing ->
				if (isShowing) {
					searchSuggestionViewModel.suggestion
				} else {
					emptyFlow()
				}
			}.observe(this, adapter)
		searchSuggestionViewModel.onError.observeEvent(
			this,
			SnackbarErrorObserver(viewBinding.recyclerViewSearch, null),
		)
		ItemTouchHelper(SearchSuggestionItemCallback(this))
			.attachToRecyclerView(viewBinding.recyclerViewSearch)
	}

	private fun setNavbarPinned(isPinned: Boolean) {
		val bottomNavBar = viewBinding.bottomNav
		bottomNavBar?.isPinned = isPinned
		val container = viewBinding.floatingNavContainer
		if (container != null) {
			val lp = container.layoutParams as? CoordinatorLayout.LayoutParams
			val behavior = lp?.behavior as? ScrollListener
			behavior?.isPinned = isPinned
			if (isPinned) behavior?.slideUp(container)
		}
		for (view in viewBinding.appbar.children) {
			val lp = view.layoutParams as? AppBarLayout.LayoutParams ?: continue
			val scrollFlags = if (isPinned) {
				lp.scrollFlags and SCROLL_FLAG_SCROLL.inv()
			} else {
				lp.scrollFlags or SCROLL_FLAG_SCROLL
			}
			if (scrollFlags != lp.scrollFlags) {
				lp.scrollFlags = scrollFlags
				view.layoutParams = lp
			}
		}
		updateMargin()
	}

	private fun setFloatNav(isFloating: Boolean) {
		if (isFloatNav == isFloating) return
		isFloatNav = isFloating
		val bottomNav = viewBinding.bottomNav
		val container = viewBinding.floatingNavContainer
		val content = viewBinding.floatingNavContent
		if (isFloating && container != null && content != null) {
			bottomNav?.isVisible = false
			container.isVisible = true
			navigationDelegate.attach(content)
		} else {
			container?.isVisible = false
			bottomNav?.isVisible = true
			bottomNav?.translationY = 0f
			bottomNav?.show()
			navigationDelegate.detach()
		}
		setNavbarPinned(settings.isNavBarPinned)
		adjustFabVisibility()
		updateMargin()
	}

	private fun updateMargin() {
		if (isFloatNav) {
			with(viewBinding.container) {
				val params = layoutParams as MarginLayoutParams
				if (params.bottomMargin != 0) {
					params.bottomMargin = 0
					layoutParams = params
				}
			}
			return
		}
		val bottomNavBar = viewBinding.bottomNav ?: return
		val newMargin = if (bottomNavBar.isPinned && bottomNavBar.isShownOrShowing) bottomNavBar.height else 0
		with(viewBinding.container) {
			val params = layoutParams as MarginLayoutParams
			if (params.bottomMargin != newMargin) {
				params.bottomMargin = newMargin
				layoutParams = params
			}
		}
	}

	private fun SearchView.observeState() = callbackFlow {
		val listener = SearchView.TransitionListener { _, _, state ->
			trySendBlocking(state)
		}
		addTransitionListener(listener)
		awaitClose { removeTransitionListener(listener) }
	}
}
