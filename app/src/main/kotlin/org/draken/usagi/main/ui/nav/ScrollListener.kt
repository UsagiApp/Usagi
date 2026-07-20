package org.draken.usagi.main.ui.nav

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.view.ViewPropertyAnimator
import android.view.animation.DecelerateInterpolator
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.isVisible
import org.draken.usagi.R
import androidx.core.view.isGone

class ScrollListener @JvmOverloads constructor(
    context: Context? = null,
    attrs: AttributeSet? = null,
) : CoordinatorLayout.Behavior<View>(context, attrs) {

	var isPinned: Boolean = false
	var isHidden = false
		private set

	private var expandedWidth = 0
	private var animator: ValueAnimator? = null
	private var animatorY: ViewPropertyAnimator? = null
	private var fabAnimator: ViewPropertyAnimator? = null

	override fun onStartNestedScroll(
        coordinatorLayout: CoordinatorLayout,
        child: View,
        directTargetChild: View,
        target: View,
        axes: Int,
        type: Int,
	): Boolean { return !isPinned && axes == View.SCROLL_AXIS_VERTICAL }

	override fun onNestedScroll(
        coordinatorLayout: CoordinatorLayout,
        child: View,
        target: View,
        dxConsumed: Int,
        dyConsumed: Int,
        dxUnconsumed: Int,
        dyUnconsumed: Int,
        type: Int,
        consumed: IntArray,
	) {
		super.onNestedScroll(
			coordinatorLayout, child, target, dxConsumed,
			dyConsumed, dxUnconsumed, dyUnconsumed, type, consumed,
		)
		if (dyConsumed > 0) slideDown(child) else if (dyConsumed < 0) slideUp(child)
	}

	fun slideDown(child: View) {
		if (isHidden) return
		isHidden = true
		val fab = child.findViewById<View>(R.id.fabFloating)
		val navBar = child.findViewById<View>(R.id.floatingNav)
		val isFab = fab?.tag as? Boolean == true
		if (fab != null && isFab && navBar != null) {
			hideAction(navBar, fab, child)
		} else {
			animatorY?.cancel()
			val bottom = (child.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0
			val targetY = child.height.toFloat() + bottom.toFloat()
			animatorY = child.animate()
				.translationY(targetY)
				.setInterpolator(DecelerateInterpolator())
				.setDuration(200)
				.setListener(
					object : AnimatorListenerAdapter() {
						override fun onAnimationEnd(animation: Animator) {
							animatorY = null
						}
					},
				)
			animatorY?.start()
		}
	}

	fun slideUp(child: View) {
		if (!isHidden) return
		isHidden = false
		val fab = child.findViewById<View>(R.id.fabFloating)
		val navBar = child.findViewById<View>(R.id.floatingNav)
		val isFab = fab?.tag as? Boolean == true
		animatorY?.cancel()
		animatorY = child.animate()
			.translationY(0f)
			.setInterpolator(DecelerateInterpolator())
			.setDuration(200)
			.setListener(
				object : AnimatorListenerAdapter() {
					override fun onAnimationEnd(animation: Animator) {
						animatorY = null
					}
				},
			)
		animatorY?.start()
		if (fab != null && isFab && navBar != null) animate(navBar, fab, child)
	}

	fun reset(child: View) {
		animator?.cancel()
		animator = null
		animatorY?.cancel()
		animatorY = null
		isHidden = false
		val navBar = child.findViewById<View>(R.id.floatingNav)
		if (navBar != null) {
			navBar.visibility = View.VISIBLE
			navBar.alpha = 1f
			val layoutParams = navBar.layoutParams
			if (layoutParams.width != ViewGroup.LayoutParams.WRAP_CONTENT) {
				layoutParams.width = ViewGroup.LayoutParams.WRAP_CONTENT
				navBar.layoutParams = layoutParams
			}
		}
		child.translationY = 0f
		update(child)
	}

	fun update(child: View) {
		val fab = child.findViewById<View>(R.id.fabFloating) ?: return
		val navBar = child.findViewById<View>(R.id.floatingNav)
		if (fab.tag as? Boolean != true) {
			animateFab(fab, false)
			return
		}
		val isNavBarHidden = isHidden || navBar?.isVisible == false
		val fitsSideBySide = if (isNavBarHidden) false else {
			val w = (child.parent as? View)?.width ?: 0
			val density = child.resources.displayMetrics.density
			val nav = if (expandedWidth > 0) expandedWidth else (230 * density).toInt()
			w !in 1..<(nav + (56 * density).toInt())
		}
		val show = isNavBarHidden || fitsSideBySide
		animateFab(fab, show)
		if (show) {
			fab.translationX = 0f
			(fab.layoutParams as? ViewGroup.MarginLayoutParams)?.run {
				val density = child.resources.displayMetrics.density
				val targetMargin = if (isNavBarHidden) 0 else (8 * density).toInt()
				if (marginStart != targetMargin) {
					marginStart = targetMargin
					fab.layoutParams = this
				}
			}
		}
	}

	private fun animateFab(fab: View, show: Boolean) {
		val state = if (show) "showing" else "hiding"
		if (fab.getTag(R.id.fabFloating) == state) return
		if (show && fab.isVisible && fab.alpha == 1f) return
		if (!show && fab.isGone) {
			fab.translationX = 0f
			return
		}
		fab.setTag(R.id.fabFloating, state)
		fabAnimator?.cancel()
		if (show) {
			fab.visibility = View.VISIBLE
			fab.alpha = 0f
		}
		fabAnimator = fab.animate()
			.alpha(if (show) 1f else 0f)
			.setStartDelay(if (show) 250 else 0)
			.setDuration(200)
			.setInterpolator(DecelerateInterpolator())
			.setListener(
				object : AnimatorListenerAdapter() {
					override fun onAnimationEnd(animation: Animator) {
						if (!show) {
							fab.visibility = View.GONE
							fab.translationX = 0f
						}
						fab.setTag(R.id.fabFloating, null)
						fabAnimator = null
					}
				},
			)
		fabAnimator?.start()
	}

	private fun hideAction(navBar: View, fab: View, container: View) {
		animator?.cancel()
		if (expandedWidth <= 0) expandedWidth = navBar.width
		val w = navBar.width
		val a = navBar.alpha
		val group = container as? ViewGroup
		val transition = group?.layoutTransition
		group?.layoutTransition = null
		val clipChild = group?.clipChildren ?: false
		val cardClip = (navBar as? ViewGroup)?.clipChildren ?: false
		group?.clipChildren = true
		(navBar as? ViewGroup)?.clipChildren = true
		fab.visibility = View.VISIBLE
		fab.alpha = 1f
		fab.translationX = 0f
		(fab.layoutParams as? ViewGroup.MarginLayoutParams)?.run {
			if (marginStart != 0) {
				marginStart = 0
				fab.layoutParams = this
			}
		}
		val layout = navBar.layoutParams
		animator = ValueAnimator.ofFloat(0f, 1f).apply {
			duration = 200
			interpolator = DecelerateInterpolator()
			addUpdateListener { animator ->
				val p = animator.animatedValue as Float
				layout.width = (w - (w * p)).toInt()
				navBar.layoutParams = layout
				navBar.alpha = a * (1f - p)
			}
			addListener(
				object : AnimatorListenerAdapter() {
					override fun onAnimationEnd(animation: Animator) {
						navBar.visibility = View.GONE
						group?.post { group.layoutTransition = transition }
						group?.clipChildren = clipChild
						(navBar as? ViewGroup)?.clipChildren = cardClip
						animator = null
					}
				},
			)
			start()
		}
	}

	private fun animate(navBar: View, fab: View, container: View) {
		animator?.cancel()
		if (expandedWidth <= 0) {
			navBar.measure(
				View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
				View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
			)
			expandedWidth = navBar.measuredWidth
		}
		val group = container as? ViewGroup
		val transition = group?.layoutTransition
		group?.layoutTransition = null
		val clipChild = group?.clipChildren ?: false
		val cardClip = (navBar as? ViewGroup)?.clipChildren ?: false
		group?.clipChildren = true
		(navBar as? ViewGroup)?.clipChildren = true
		navBar.visibility = View.VISIBLE
		(fab.layoutParams as? ViewGroup.MarginLayoutParams)?.run {
			val m = (8 * container.resources.displayMetrics.density).toInt()
			if (marginStart != m) {
				marginStart = m
				fab.layoutParams = this
			}
		}
		val w = navBar.width
		val alpha = navBar.alpha
		val ex = expandedWidth
		val layout = navBar.layoutParams
		animator = ValueAnimator.ofFloat(0f, 1f).apply {
			duration = 200
			interpolator = DecelerateInterpolator()
			addUpdateListener { a ->
				val p = a.animatedValue as Float
				layout.width = w + ((ex - w) * p).toInt()
				navBar.layoutParams = layout
				navBar.alpha = alpha + ((1f - alpha) * p)
			}
			addListener(
				object : AnimatorListenerAdapter() {
					override fun onAnimationEnd(animation: Animator) {
						layout.width = ViewGroup.LayoutParams.WRAP_CONTENT
						navBar.layoutParams = layout
						navBar.alpha = 1f
						group?.post { group.layoutTransition = transition }
						group?.clipChildren = clipChild
						(navBar as? ViewGroup)?.clipChildren = cardClip
						animator = null
						update(container)
					}
				},
			)
			start()
		}
	}
}
