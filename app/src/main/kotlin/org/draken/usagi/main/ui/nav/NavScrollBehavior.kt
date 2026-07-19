package org.draken.usagi.main.ui.nav

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import android.view.ViewPropertyAnimator
import android.view.animation.DecelerateInterpolator
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.isVisible
import org.draken.usagi.R

class NavScrollBehavior @JvmOverloads constructor(
	context: Context? = null,
	attrs: AttributeSet? = null,
) : CoordinatorLayout.Behavior<View>(context, attrs) {

	var isPinned: Boolean = false
	private var isHidden = false
	private var expandedWidth = 0
	private var animator: ValueAnimator? = null
	private var animatorY: ViewPropertyAnimator? = null

	override fun onStartNestedScroll(
		coordinatorLayout: CoordinatorLayout, child: View,
		directTargetChild: View, target: View, axes: Int, type: Int,
	): Boolean { return !isPinned && axes == View.SCROLL_AXIS_VERTICAL }

	override fun onNestedScroll(
		coordinatorLayout: CoordinatorLayout, child: View, target: View, dxConsumed: Int,
		dyConsumed: Int, dxUnconsumed: Int, dyUnconsumed: Int, type: Int, consumed: IntArray,
	) {
		super.onNestedScroll(
			coordinatorLayout, child, target, dxConsumed,
			dyConsumed, dxUnconsumed, dyUnconsumed, type, consumed,
		)
		if (dyConsumed > 0) { slideDown(child) } else if (dyConsumed < 0) slideUp(child)
	}

	fun slideDown(child: View) {
		if (isHidden) return
		isHidden = true
		val fab = child.findViewById<View>(R.id.fabFloating)
		val navBar = child.findViewById<View>(R.id.floatingNav)
		if (fab != null && fab.isVisible && navBar != null) {
			animateCollapse(navBar, child)
		} else {
			animatorY?.cancel()
			val bottomMargin = (child.layoutParams as? MarginLayoutParams)?.bottomMargin ?: 0
			val targetY = child.height.toFloat() + bottomMargin.toFloat()
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
		if (fab != null && fab.isVisible && navBar != null) animate(navBar, child)
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
	}

	private fun animateCollapse(navBar: View, container: View) {
		animator?.cancel()
		if (expandedWidth <= 0) expandedWidth = navBar.width
		val width = navBar.width
		val alpha = navBar.alpha
		val container = container as? ViewGroup
		val transition = container?.layoutTransition
		container?.layoutTransition = null
		val clipChildren = container?.clipChildren ?: false
		val cardClipChildren = (navBar as? ViewGroup)?.clipChildren ?: false
		container?.clipChildren = true
		(navBar as? ViewGroup)?.clipChildren = true
		val layoutParams = navBar.layoutParams
		animator = ValueAnimator.ofFloat(0f, 1f).apply {
			duration = 200
			interpolator = DecelerateInterpolator()
			addUpdateListener { animator ->
				val progress = animator.animatedValue as Float
				layoutParams.width = (width - (width * progress)).toInt()
				navBar.layoutParams = layoutParams
				navBar.alpha = alpha * (1f - progress)
			}
			addListener(
				object : AnimatorListenerAdapter() {
					override fun onAnimationEnd(animation: Animator) {
						navBar.visibility = View.GONE
						container?.post {
							container.layoutTransition = transition
						}
						container?.clipChildren = clipChildren
						(navBar as? ViewGroup)?.clipChildren = cardClipChildren
						animator = null
					}
				},
			)
			start()
		}
	}

	private fun animate(navBar: View, container: View) {
		animator?.cancel()
		if (expandedWidth <= 0) {
			navBar.measure(
				View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
				View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
			)
			expandedWidth = navBar.measuredWidth
		}

		val container = container as? ViewGroup
		val transition = container?.layoutTransition
		container?.layoutTransition = null
		val clipChildren = container?.clipChildren ?: false
		val cardClip = (navBar as? ViewGroup)?.clipChildren ?: false
		container?.clipChildren = true
		(navBar as? ViewGroup)?.clipChildren = true
		navBar.visibility = View.VISIBLE
		val w = navBar.width
		val alpha = navBar.alpha
		val targetWidth = expandedWidth
		val layoutParams = navBar.layoutParams
		animator = ValueAnimator.ofFloat(0f, 1f).apply {
			duration = 200
			interpolator = DecelerateInterpolator()
			addUpdateListener { a ->
				val progress = a.animatedValue as Float
				layoutParams.width = w + ((targetWidth - w) * progress).toInt()
				navBar.layoutParams = layoutParams
				navBar.alpha = alpha + ((1f - alpha) * progress)
			}
			addListener(
				object : AnimatorListenerAdapter() {
					override fun onAnimationEnd(animation: Animator) {
						layoutParams.width = ViewGroup.LayoutParams.WRAP_CONTENT
						navBar.layoutParams = layoutParams
						navBar.alpha = 1f
						container?.post { container.layoutTransition = transition }
						container?.clipChildren = clipChildren
						(navBar as? ViewGroup)?.clipChildren = cardClip
						animator = null
					}
				},
			)
			start()
		}
	}
}
