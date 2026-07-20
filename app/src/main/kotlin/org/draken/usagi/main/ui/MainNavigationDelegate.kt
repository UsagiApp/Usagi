package org.draken.usagi.main.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.annotation.IdRes
import androidx.annotation.OptIn
import androidx.appcompat.R as appcompatR
import androidx.core.graphics.ColorUtils
import androidx.core.view.isEmpty
import androidx.core.view.isVisible
import androidx.core.view.iterator
import androidx.core.view.size
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.transition.TransitionManager
import com.google.android.material.badge.BadgeDrawable
import com.google.android.material.badge.BadgeUtils
import com.google.android.material.badge.ExperimentalBadgeUtils
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.navigation.NavigationBarView
import com.google.android.material.navigationrail.NavigationRailView
import com.google.android.material.transition.MaterialFadeThrough
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import org.draken.usagi.R
import org.draken.usagi.bookmarks.ui.AllBookmarksFragment
import org.draken.usagi.core.nav.AppRouter
import org.draken.usagi.core.nav.router
import org.draken.usagi.core.prefs.AppSettings
import org.draken.usagi.core.prefs.NavItem
import org.draken.usagi.core.ui.util.RecyclerViewOwner
import org.draken.usagi.core.ui.widgets.SlidingBottomNavigationView
import org.draken.usagi.core.util.ext.buildBundle
import org.draken.usagi.core.util.ext.setContentDescriptionAndTooltip
import org.draken.usagi.core.util.ext.setTooltipCompat
import org.draken.usagi.core.util.ext.smoothScrollToTop
import org.draken.usagi.databinding.NavigationRailFabBinding
import org.draken.usagi.explore.ui.ExploreFragment
import org.draken.usagi.favourites.ui.container.FavouritesContainerFragment
import org.draken.usagi.history.ui.HistoryListFragment
import org.draken.usagi.list.ui.config.ListConfigSection
import org.draken.usagi.local.ui.LocalListFragment
import org.draken.usagi.suggestions.ui.SuggestionsFragment
import org.draken.usagi.tracker.ui.feed.FeedFragment
import org.draken.usagi.tracker.ui.updates.UpdatesFragment
import java.util.LinkedList
import com.google.android.material.R as materialR

private const val TAG_PRIMARY = "primary"

@OptIn(ExperimentalBadgeUtils::class)
class MainNavigationDelegate(
	private val navBar: NavigationBarView,
	private val fragmentManager: FragmentManager,
	private val settings: AppSettings,
) : OnBackPressedCallback(false),
	NavigationBarView.OnItemSelectedListener,
	NavigationBarView.OnItemReselectedListener, View.OnClickListener {

	private val listeners = LinkedList<OnFragmentChangedListener>()
	val navRailHeader = (navBar as? NavigationRailView)?.headerView?.let {
		NavigationRailFabBinding.bind(it)
	}

	val primaryFragment: Fragment?
		get() = fragmentManager.findFragmentByTag(TAG_PRIMARY)

	private var floatContainer: LinearLayout? = null
	private val buttonToItem = mutableMapOf<Int, Int>()
	private val itemToButton = mutableMapOf<Int, Int>()
	private val badges = mutableMapOf<Int, BadgeDrawable>()
	private val layoutListeners = mutableMapOf<Int, View.OnLayoutChangeListener>()
	private var checkedItem: Int = 0
	private val floatCounters = mutableMapOf<Int, Int>()
	private val unhideItems = mutableMapOf<Int, Boolean>()

	init {
		navBar.setOnItemSelectedListener(this)
		navBar.setOnItemReselectedListener(this)
		navRailHeader?.run {
			root.updateLayoutParams<FrameLayout.LayoutParams> {
				gravity = Gravity.TOP or Gravity.CENTER
			}
			val horizontalPadding = (navBar as NavigationRailView).itemActiveIndicatorMarginHorizontal
			root.setPadding(horizontalPadding, 0, horizontalPadding, 0)
			buttonExpand.setOnClickListener(this@MainNavigationDelegate)
			buttonExpand.setContentDescriptionAndTooltip(R.string.expand)
			railFab.isExtended = false
			railFab.isAnimationEnabled = false
		}
	}

	override fun onNavigationItemSelected(item: MenuItem): Boolean {
		return if (onNavigationItemSelected(item.itemId)) {
			item.isChecked = true
			setItems(item.itemId)
			true
		} else {
			false
		}
	}

	override fun onNavigationItemReselected(item: MenuItem) {
		onNavigationItemReselected()
	}

	override fun onClick(v: View) {
		when (v.id) {
			R.id.button_expand -> {
				if (navBar is NavigationRailView) {
					setNavbarIsExpanded(!navBar.isExpanded)
				}
			}
		}
	}

	override fun handleOnBackPressed() {
		navBar.selectedItemId = firstItem()?.itemId ?: return
	}

	fun onCreate(lifecycleOwner: LifecycleOwner, savedInstanceState: Bundle?) {
		if (navBar.menu.isEmpty()) {
			createMenu(settings.mainNavItems, navBar.menu)
		}
		observeSettings(lifecycleOwner)
		val fragment = primaryFragment
		if (fragment != null) {
			onFragmentChanged(fragment, fromUser = false)
			val itemId = getItemId(fragment)
			if (navBar.selectedItemId != itemId) {
				navBar.selectedItemId = itemId
			}
			setItems(itemId)
		} else {
			val itemId = if (savedInstanceState == null) {
				firstItem()?.itemId ?: navBar.selectedItemId
			} else {
				navBar.selectedItemId
			}
			onNavigationItemSelected(itemId)
			setItems(itemId)
		}
	}

	fun attach(container: LinearLayout) {
		detach()
		floatContainer = container
		populate(container)
		if (checkedItem != 0) {
			val selected = checkedItem
			checkedItem = 0
			setItems(selected)
		}
		sync()
		for ((id, c) in floatCounters) { setFloatCount(id, c) }
	}

	fun detach() {
		val container = floatContainer ?: return
		for ((itemId, badge) in badges) {
			val buttonId = itemToButton[itemId] ?: continue
			val wrap = container.findViewById<FrameLayout>(buttonId) ?: continue
			val button = wrap.getChildAt(0) ?: continue
			BadgeUtils.detachBadgeDrawable(badge, button)
			layoutListeners.remove(itemId)?.let { l -> button.removeOnLayoutChangeListener(l) }
		}
		badges.clear()
		layoutListeners.clear()
		buttonToItem.clear()
		itemToButton.clear()
		floatContainer = null
	}

	fun addOnFragmentChangedListener(listener: OnFragmentChangedListener) {
		listeners.add(listener)
	}

	fun removeOnFragmentChangedListener(listener: OnFragmentChangedListener) {
		listeners.remove(listener)
	}

	fun observeTitle() = callbackFlow {
		val listener = OnFragmentChangedListener { f, _ ->
			trySendBlocking(getItemId(f))
		}
		addOnFragmentChangedListener(listener)
		awaitClose { removeOnFragmentChangedListener(listener) }
	}.map {
		navBar.menu.findItem(it)?.title
	}

	fun syncSelectedItem() {
		val fragment = primaryFragment ?: return
		onFragmentChanged(fragment, fromUser = false)
		val itemId = getItemId(fragment)
		if (navBar.selectedItemId != itemId) {
			navBar.selectedItemId = itemId
		}
		setItems(itemId)
	}

	fun setCounter(item: NavItem, counter: Int) {
		setCounter(item.id, counter)
	}

	fun setItemVisibility(@IdRes itemId: Int, isVisible: Boolean) {
		val item = navBar.menu.findItem(itemId) ?: return
		item.isVisible = isVisible
		if (item.isChecked && !isVisible) {
			navBar.selectedItemId = firstItem()?.itemId ?: return
		}
		setUnhideItem(itemId, isVisible)
	}

	internal fun onNavigationItemSelected(@IdRes itemId: Int): Boolean {
		val newFragment = when (itemId) {
			R.id.nav_history -> HistoryListFragment::class.java
			R.id.nav_favorites -> FavouritesContainerFragment::class.java
			R.id.nav_explore -> ExploreFragment::class.java
			R.id.nav_feed -> FeedFragment::class.java
			R.id.nav_local -> LocalListFragment::class.java
			R.id.nav_suggestions -> SuggestionsFragment::class.java
			R.id.nav_bookmarks -> AllBookmarksFragment::class.java
			R.id.nav_updated -> UpdatesFragment::class.java
			else -> return false
		}
		if (!setPrimaryFragment(newFragment)) {
			onNavigationItemReselected()
		}
		return true
	}

	internal fun onNavigationItemReselected() {
		val fragment = primaryFragment ?: return
		when (fragment) {
			is HistoryListFragment -> fragment.router.showListConfigSheet(ListConfigSection.History)
			is FavouritesContainerFragment -> fragment.categoryId?.let {
				fragment.router.showListConfigSheet(ListConfigSection.Favorites(it))
			}
			is RecyclerViewOwner -> fragment.recyclerView?.smoothScrollToTop()
		}
	}

	private fun setPrimaryFragment(fragmentClass: Class<out Fragment>): Boolean {
		if (fragmentManager.isStateSaved || fragmentClass.isInstance(primaryFragment)) {
			return false
		}
		val fragment = instantiateFragment(fragmentClass)
		val args = buildBundle(1) {
			putBoolean(AppRouter.KEY_IS_BOTTOMTAB, true)
		}
		fragment.enterTransition = MaterialFadeThrough()
		fragmentManager.beginTransaction()
			.setReorderingAllowed(true)
			.replace(R.id.container, fragmentClass, args, TAG_PRIMARY)
			.runOnCommit { onFragmentChanged(fragment, fromUser = true) }
			.commit()
		return true
	}

	private fun onFragmentChanged(fragment: Fragment, fromUser: Boolean) {
		isEnabled = getItemId(fragment) != firstItem()?.itemId
		listeners.forEach { it.onFragmentChanged(fragment, fromUser) }
	}

	private fun getItemId(fragment: Fragment) = when (fragment) {
		is HistoryListFragment -> R.id.nav_history
		is FavouritesContainerFragment -> R.id.nav_favorites
		is ExploreFragment -> R.id.nav_explore
		is FeedFragment -> R.id.nav_feed
		is LocalListFragment -> R.id.nav_local
		is SuggestionsFragment -> R.id.nav_suggestions
		is AllBookmarksFragment -> R.id.nav_bookmarks
		is UpdatesFragment -> R.id.nav_updated
		else -> 0
	}

	private fun instantiateFragment(fragmentClass: Class<out Fragment>): Fragment {
		val classLoader = navBar.context.classLoader
		return fragmentManager.fragmentFactory.instantiate(classLoader, fragmentClass.name)
	}

	private fun createMenu(items: List<NavItem>, menu: Menu) {
		for (item in items) {
			menu.add(Menu.NONE, item.id, Menu.NONE, item.title)
				.setIcon(item.icon)
			if (menu.size >= navBar.maxItemCount) {
				break
			}
		}
	}

	private fun firstItem(): MenuItem? {
		val menu = navBar.menu
		for (item in menu) { if (item.isVisible) return item }
		return null
	}

	private fun observeSettings(lifecycleOwner: LifecycleOwner) {
		settings.observe(AppSettings.KEY_TRACKER_ENABLED, AppSettings.KEY_SUGGESTIONS, AppSettings.KEY_NAV_LABELS)
			.onEach {
				setItemVisibility(R.id.nav_suggestions, settings.isSuggestionsEnabled)
				setItemVisibility(R.id.nav_feed, settings.isTrackerEnabled)
				setNavbarIsLabeled(settings.isNavLabelsVisible)
				floatContainer?.let { populate(it) }
			}.launchIn(lifecycleOwner.lifecycleScope)
	}

	private fun setNavbarIsLabeled(value: Boolean) {
		if (navBar is SlidingBottomNavigationView) {
			navBar.minimumHeight = navBar.resources.getDimensionPixelSize(
				if (value) {
					materialR.dimen.m3_bottom_nav_min_height
				} else {
					R.dimen.nav_bar_height_compact
				},
			)
		}
		navRailHeader?.buttonExpand?.isVisible = value
		if (!value) {
			setNavbarIsExpanded(false)
		}
		navBar.labelVisibilityMode = if (value) {
			NavigationBarView.LABEL_VISIBILITY_LABELED
		} else {
			NavigationBarView.LABEL_VISIBILITY_UNLABELED
		}
	}

	private fun setNavbarIsExpanded(value: Boolean) {
		if (navBar !is NavigationRailView) {
			return
		}
		if (value) {
			navBar.expand()
			navRailHeader?.run {
				root.updateLayoutParams<FrameLayout.LayoutParams> {
					gravity = Gravity.TOP or Gravity.START
				}
				railFab.extend()
				buttonExpand.setImageResource(R.drawable.ic_drawer_menu_open)
				buttonExpand.setContentDescriptionAndTooltip(R.string.collapse)
				val horizontalPadding = navBar.itemActiveIndicatorExpandedMarginHorizontal
				root.setPadding(horizontalPadding, 0, horizontalPadding, 0)
			}
		} else {
			navBar.collapse()
			navRailHeader?.run {
				root.updateLayoutParams<FrameLayout.LayoutParams> {
					gravity = Gravity.TOP or Gravity.CENTER
				}
				railFab.shrink()
				buttonExpand.setImageResource(R.drawable.ic_drawer_menu)
				buttonExpand.setContentDescriptionAndTooltip(R.string.expand)
				val horizontalPadding = navBar.itemActiveIndicatorMarginHorizontal
				root.setPadding(horizontalPadding, 0, horizontalPadding, 0)
			}
		}
	}

	private fun populate(container: LinearLayout) {
		container.clipChildren = false
		container.clipToPadding = false
		container.removeAllViews()
		buttonToItem.clear()
		itemToButton.clear()
		val items = settings.mainNavItems.filter { it.isAvailable(settings) }.take(MAX_FLOAT_ITEM_COUNT)
		val context = container.context
		val density = context.resources.displayMetrics.density
		val scale = getScale(context)
		val card = container.parent as? com.google.android.material.card.MaterialCardView
		if (card != null) {
			card.layoutParams = card.layoutParams.apply { height = (48 * density * scale).toInt() }
			card.radius = 24 * density * scale
		}
		val start = (10 * density * scale).toInt()
		val end = (10 * density * scale).toInt()
		val vertical = (8 * density * scale).toInt()
		container.setPadding(start, vertical, end, vertical)
		val parent = card?.parent as? ViewGroup
		val fab = parent?.findViewById<FloatingActionButton>(R.id.fabFloating)
		if (fab != null) {
			val size = (48 * density * scale).toInt()
			fab.customSize = size
			fab.layoutParams = (fab.layoutParams as? ViewGroup.MarginLayoutParams)?.apply {
				marginStart = (8 * density * scale).toInt()
			}
		}
		val height = (32 * density * scale).toInt()
		val horizontal = (4 * density * scale).toInt()
		val isLabel = settings.isNavLabelsVisible
		container.layoutParams = container.layoutParams.apply {
			width = if (isLabel) (230 * density * scale).toInt() else LinearLayout.LayoutParams.WRAP_CONTENT
		}
		for (item in items) {
			val wrapper = FrameLayout(context).apply {
				clipChildren = false
				clipToPadding = false
				id = View.generateViewId()
				layoutParams = if (isLabel) {
					LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
				} else {
					LinearLayout.LayoutParams(height, height)
				}.apply {
					leftMargin = horizontal
					rightMargin = horizontal
				}
			}
			buttonToItem[wrapper.id] = item.id
			itemToButton[item.id] = wrapper.id
			val button = object : MaterialButton(context, null, appcompatR.attr.borderlessButtonStyle) {
				override fun toggle() {
					if (!isChecked) super.toggle()
				}
			}.apply {
				id = View.generateViewId()
				setTag(R.id.nav_history, item.id)
				setTag(R.id.nav_feed, item.icon)
				contentDescription = context.getString(item.title)
				setTooltipCompat(context.getString(item.title))
				layoutParams = FrameLayout.LayoutParams(
					FrameLayout.LayoutParams.MATCH_PARENT,
					height,
					Gravity.CENTER,
				)
				cornerRadius = height / 2
				iconSize = (18 * density * scale).toInt()
				insetTop = 0
				insetBottom = 0
				iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
				minimumHeight = 0
				minimumWidth = 0
				isCheckable = true
				maxLines = 1
				ellipsize = TextUtils.TruncateAt.END
				setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f * scale)
				setOnClickListener { onFloatingItemClicked(wrapper.id) }
			}
			wrapper.addView(button)
			container.addView(wrapper)
		}
		updateState()
	}

	private fun onFloatingItemClicked(wrapperId: Int) {
		val itemId = buttonToItem[wrapperId] ?: return
		if (itemId == checkedItem) {
			onNavigationItemReselected()
			return
		}
		if (onNavigationItemSelected(itemId)) {
			if (navBar.selectedItemId != itemId) navBar.selectedItemId = itemId
			setItems(itemId)
		}
	}

	private fun setItems(@IdRes itemId: Int) {
		if (checkedItem == itemId) return
		checkedItem = itemId
		updateState()
	}

	private fun updateState() {
		val container = floatContainer ?: return
		val context = container.context
		val bg = MaterialColors.getColor(context, materialR.attr.colorSecondaryContainer, 0)
		val text = MaterialColors.getColor(context, materialR.attr.colorOnSecondaryContainer, 0)
		val icon = MaterialColors.getColor(context, materialR.attr.colorOnSurfaceVariant, 0)
		val textColor = MaterialColors.getColor(context, android.R.attr.textColorPrimary, Color.BLACK)
		val density = context.resources.displayMetrics.density
		val scale = getScale(context)
		val selected = (4 * density * scale).toInt()
		val unselected = (6 * density * scale).toInt()
		val isLabeled = settings.isNavLabelsVisible
		TransitionManager.beginDelayedTransition(container)
		for (i in 0 until container.childCount) {
			val wrap = container.getChildAt(i) as? FrameLayout ?: continue
			val button = wrap.getChildAt(0) as? MaterialButton ?: continue
			val id = buttonToItem[wrap.id] ?: continue
			val selNow = id == checkedItem
			val selBefore = button.isSelected
			if (selNow != selBefore) {
				animateBtn(button, selNow, bg, textColor, text, icon)
			} else {
				applyColors(button, selNow, bg, textColor, text, icon)
			}
			val iconRes = (button.getTag(R.id.nav_feed) as? Int) ?: 0
			button.isChecked = selNow
			if (isLabeled) {
				button.text = button.contentDescription
				button.iconPadding = if (selNow) (4 * density).toInt() else 0
				val p = if (selNow) selected else unselected
				button.setPadding(p, 0, p, 0)
				button.setIconResource(if (selNow) iconRes else 0)
			} else {
				button.text = null
				button.iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
				button.iconPadding = 0
				button.setPadding(0, 0, 0, 0)
				if (iconRes != 0) button.setIconResource(iconRes)
			}
			if (selNow && iconRes != 0 && !selBefore) {
				button.isChecked = false
				button.post { button.isChecked = true }
			}
			button.isSelected = selNow
		}
	}

	private fun animateBtn(
		button: MaterialButton, toSelected: Boolean, selectedBgColor: Int,
		defaultTextColor: Int, selectedTextColor: Int, defaultIconColor: Int,
	) {
		val fromBg = if (toSelected) Color.TRANSPARENT else selectedBgColor
		val toBg = if (toSelected) selectedBgColor else Color.TRANSPARENT
		val fromText = if (toSelected) defaultTextColor else selectedTextColor
		val toText = if (toSelected) selectedTextColor else defaultTextColor
		val fromIcon = if (toSelected) defaultIconColor else selectedTextColor
		val toIcon = if (toSelected) selectedTextColor else defaultIconColor
		ValueAnimator.ofFloat(0f, 1f).apply {
			duration = 100
			addUpdateListener { animator ->
				val f = animator.animatedValue as Float
				button.backgroundTintList = ColorStateList.valueOf(ColorUtils.blendARGB(fromBg, toBg, f))
				button.setTextColor(ColorUtils.blendARGB(fromText, toText, f))
				button.iconTint = ColorStateList.valueOf(ColorUtils.blendARGB(fromIcon, toIcon, f))
			}
			start()
		}
	}

	private fun applyColors(
		button: MaterialButton, isSelected: Boolean, selectedBgColor: Int,
		defaultTextColor: Int, selectedTextColor: Int, defaultIconColor: Int,
	) {
		if (isSelected) {
			button.backgroundTintList = ColorStateList.valueOf(selectedBgColor)
			button.setTextColor(selectedTextColor)
			button.iconTint = ColorStateList.valueOf(selectedTextColor)
		} else {
			button.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
			button.setTextColor(defaultTextColor)
			button.iconTint = ColorStateList.valueOf(defaultIconColor)
		}
	}

	private fun sync() {
		val container = floatContainer ?: return
		for ((itemId, wrapId) in itemToButton) {
			val unhide = unhideItems[itemId] ?: true
			container.findViewById<FrameLayout>(wrapId)?.isVisible = unhide
		}
	}

	private fun setUnhideItem(@IdRes itemId: Int, isVisible: Boolean) {
		unhideItems[itemId] = isVisible
		val id = itemToButton[itemId] ?: return
		floatContainer?.findViewById<FrameLayout>(id)?.isVisible = isVisible
	}

	private fun setCounter(@IdRes id: Int, counter: Int) {
		counters[id] = counter
		if (counter == 0) {
			navBar.getBadge(id)?.isVisible = false
		} else {
			navBar.getOrCreateBadge(id).apply {
				if (counter < 0) clearNumber() else number = counter
				isVisible = true
			}
		}
		setFloatCount(id, counter)
	}

	private fun setFloatCount(@IdRes itemId: Int, counter: Int) {
		floatCounters[itemId] = counter
		val container = floatContainer ?: return
		val wrapId = itemToButton[itemId] ?: return
		val wrapper = container.findViewById<FrameLayout>(wrapId) ?: return
		val button = wrapper.getChildAt(0) as? MaterialButton ?: return
		if (counter == 0) {
			badges[itemId]?.isVisible = false
		} else {
			val badge = badges.getOrPut(itemId) {
				BadgeDrawable.create(container.context).apply {
					val density = container.context.resources.displayMetrics.density
					val scale = getScale(container.context)
					horizontalOffset = (10 * density * scale).toInt()
					verticalOffset = (10 * density * scale).toInt()
					val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
						BadgeUtils.setBadgeDrawableBounds(this, button, wrapper)
					}
					button.addOnLayoutChangeListener(listener)
					layoutListeners[itemId] = listener
					BadgeUtils.attachBadgeDrawable(this, button, wrapper)
				}
			}
			if (counter < 0) badge.clearNumber() else badge.number = counter
			badge.isVisible = true
		}
	}

	private fun getScale(context: Context): Float {
		return if (context.resources.displayMetrics.densityDpi >= 500) 1.2f else 1.0f
	}

	fun interface OnFragmentChangedListener {

		fun onFragmentChanged(fragment: Fragment, fromUser: Boolean)
	}

	companion object {

		const val MAX_ITEM_COUNT = 6
		const val MAX_FLOAT_ITEM_COUNT = 3
		private val counters = mutableMapOf<Int, Int>()
	}
}
