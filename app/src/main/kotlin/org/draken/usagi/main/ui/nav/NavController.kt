package org.draken.usagi.main.ui.nav

import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Color
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.annotation.IdRes
import androidx.annotation.OptIn
import androidx.appcompat.R as appcompatR
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.transition.TransitionManager
import com.google.android.material.badge.BadgeDrawable
import com.google.android.material.badge.BadgeUtils
import com.google.android.material.badge.ExperimentalBadgeUtils
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import org.draken.usagi.R
import org.draken.usagi.core.prefs.AppSettings
import com.google.android.material.R as materialR
import org.draken.usagi.core.util.ext.setTooltipCompat
import org.draken.usagi.main.ui.MainNavigationDelegate
import kotlin.collections.iterator

@OptIn(ExperimentalBadgeUtils::class)
class NavController(
	private val settings: AppSettings,
	private val onItemSelected: (Int) -> Boolean,
	private val onItemReselected: () -> Unit,
) {

	private var floatingContainer: LinearLayout? = null
	private val floatingButtonIdToItemId = mutableMapOf<Int, Int>()
	private val floatingItemIdToButtonId = mutableMapOf<Int, Int>()
	private val floatingBadges = mutableMapOf<Int, BadgeDrawable>()
	private var floatingSelectedItemId: Int = 0
	private val counters = mutableMapOf<Int, Int>()
	private val itemsVisibility = mutableMapOf<Int, Boolean>()

	fun attach(container: LinearLayout) {
		detach()
		floatingContainer = container
		populate(container)
		if (floatingSelectedItemId != 0) {
			val selected = floatingSelectedItemId
			floatingSelectedItemId = 0
			setItem(selected)
		}
		sync()
		for ((id, counter) in counters) {
			setCounter(id, counter)
		}
	}

	fun detach() {
		val container = floatingContainer ?: return
		for ((itemId, badge) in floatingBadges) {
			val buttonId = floatingItemIdToButtonId[itemId] ?: continue
			val wrapper = container.findViewById<FrameLayout>(buttonId) ?: continue
			val button = wrapper.getChildAt(0) ?: continue
			BadgeUtils.detachBadgeDrawable(badge, button)
		}
		floatingBadges.clear()
		floatingButtonIdToItemId.clear()
		floatingItemIdToButtonId.clear()
		floatingContainer = null
	}

	fun setItem(@IdRes itemId: Int) {
		if (floatingSelectedItemId == itemId) return
		floatingSelectedItemId = itemId
		updateState()
	}

	fun setCounter(@IdRes itemId: Int, counter: Int) {
		counters[itemId] = counter
		val container = floatingContainer ?: return
		val wrapperId = floatingItemIdToButtonId[itemId] ?: return
		val wrapper = container.findViewById<FrameLayout>(wrapperId) ?: return
		val button = wrapper.getChildAt(0) as? MaterialButton ?: return
		if (counter == 0) {
			floatingBadges[itemId]?.isVisible = false
		} else {
			val badge = floatingBadges.getOrPut(itemId) {
				BadgeDrawable.create(container.context).also {
					it.horizontalOffset = (10 * container.context.resources.displayMetrics.density).toInt()
					it.verticalOffset = (10 * container.context.resources.displayMetrics.density).toInt()
					if (button.isLaidOut && !button.isLayoutRequested) {
						BadgeUtils.attachBadgeDrawable(it, button, wrapper)
					} else {
						button.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
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
								wrapper.removeOnLayoutChangeListener(this)
								BadgeUtils.attachBadgeDrawable(it, button, wrapper)
							}
						})
					}
				}
			}
			if (counter < 0) { badge.clearNumber() } else badge.number = counter
			badge.isVisible = true
		}
	}

	fun setItemVisibility(@IdRes itemId: Int, isVisible: Boolean) {
		itemsVisibility[itemId] = isVisible
		val buttonId = floatingItemIdToButtonId[itemId] ?: return
		floatingContainer?.findViewById<FrameLayout>(buttonId)?.isVisible = isVisible
	}

	fun setLabel() {
		val container = floatingContainer ?: return
		populate(container)
	}

	private fun sync() {
		val container = floatingContainer ?: return
		for ((itemId, wrapperId) in floatingItemIdToButtonId) {
			val isVisible = itemsVisibility[itemId] ?: true
			container.findViewById<FrameLayout>(wrapperId)?.isVisible = isVisible
		}
	}

	private fun populate(container: LinearLayout) {
		container.clipChildren = false
		container.clipToPadding = false
		container.removeAllViews()
		floatingButtonIdToItemId.clear()
		floatingItemIdToButtonId.clear()
		val items = settings.mainNavItems.filter { it.isAvailable(settings) }.take(
			MainNavigationDelegate.MAX_FLOAT_ITEM_COUNT)
		val context = container.context
		val density = context.resources.displayMetrics.density
		val buttonHeightPx = (32 * density).toInt()
		val marginHorizontalPx = (4 * density).toInt()
		val isLabel = settings.isNavLabelsVisible
		container.layoutParams = container.layoutParams.apply {
			width = if (isLabel) (230 * density).toInt() else LinearLayout.LayoutParams.WRAP_CONTENT
		}
		for (item in items) {
			val wrapper = FrameLayout(context).apply {
				clipChildren = false
				clipToPadding = false
				id = View.generateViewId()
				layoutParams = if (isLabel) {
					LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
						leftMargin = marginHorizontalPx
						rightMargin = marginHorizontalPx
					}
				} else {
					LinearLayout.LayoutParams(buttonHeightPx, buttonHeightPx).apply {
						leftMargin = marginHorizontalPx
						rightMargin = marginHorizontalPx
					}
				}
			}
			floatingButtonIdToItemId[wrapper.id] = item.id
			floatingItemIdToButtonId[item.id] = wrapper.id
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
					buttonHeightPx,
					Gravity.CENTER,
				)
				cornerRadius = (16 * density).toInt()
				insetTop = 0
				insetBottom = 0
				iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
				minimumHeight = 0
				minimumWidth = 0
				isCheckable = true
				maxLines = 1
				ellipsize = TextUtils.TruncateAt.END
				setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
				setOnClickListener { onFloatingItemClicked(wrapper.id) }
			}
			wrapper.addView(button)
			container.addView(wrapper)
		}
		updateState()
	}

	private fun onFloatingItemClicked(wrapperId: Int) {
		val itemId = floatingButtonIdToItemId[wrapperId] ?: return
		if (itemId == floatingSelectedItemId) {
			onItemReselected()
			return
		}
		if (onItemSelected(itemId)) setItem(itemId)
	}

	private fun updateState() {
		val container = floatingContainer ?: return
		val context = container.context
		val bgColor = MaterialColors.getColor(context, materialR.attr.colorSecondaryContainer, 0)
		val textColor = MaterialColors.getColor(context, materialR.attr.colorOnSecondaryContainer, 0)
		val iconColor = MaterialColors.getColor(context, materialR.attr.colorOnSurfaceVariant, 0)
		val defaultTextColor = MaterialColors.getColor(context, android.R.attr.textColorPrimary, Color.BLACK)
		val density = context.resources.displayMetrics.density
		val selected = (4 * density).toInt()
		val unselected = (6 * density).toInt()
		val isLabeled = settings.isNavLabelsVisible
		TransitionManager.beginDelayedTransition(container)
		for (i in 0 until container.childCount) {
			val wrapper = container.getChildAt(i) as? FrameLayout ?: continue
			val button = wrapper.getChildAt(0) as? MaterialButton ?: continue
			val itemId = floatingButtonIdToItemId[wrapper.id] ?: continue
			val isSelected = itemId == floatingSelectedItemId
			val wasSelected = button.isSelected
			if (isSelected != wasSelected) {
				animate(button, isSelected, bgColor, defaultTextColor, textColor, iconColor)
			} else {
				applyColors(button, isSelected, bgColor, defaultTextColor, textColor, iconColor)
			}
			@IdRes val iconRes = (button.getTag(R.id.nav_feed) as? Int) ?: 0
			if (isLabeled) {
				if (isSelected) {
					button.text = button.contentDescription
					if (iconRes != 0 && !wasSelected) {
						button.isChecked = false
						button.setIconResource(iconRes)
						button.post { button.isChecked = true }
					} else if (!button.isChecked) button.isChecked = true
					button.iconPadding = (4 * density).toInt()
					button.setPadding(selected, 0, selected, 0)
				} else {
					button.isChecked = false
					button.text = button.contentDescription
					button.setIconResource(0)
					button.iconPadding = 0
					button.setPadding(unselected, 0, unselected, 0)
				}
			} else {
				button.text = null
				button.iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
				button.iconPadding = 0
				button.setPadding(0, 0, 0, 0)
				if (iconRes != 0) {
					if (isSelected) {
						if (!wasSelected) {
							button.isChecked = false
							button.setIconResource(iconRes)
							button.post { button.isChecked = true }
						} else if (!button.isChecked) button.isChecked = true
					} else {
						button.isChecked = false
						button.setIconResource(iconRes)
					}
				}
			}
			button.isSelected = isSelected
		}
	}

	private fun animate(
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
}
