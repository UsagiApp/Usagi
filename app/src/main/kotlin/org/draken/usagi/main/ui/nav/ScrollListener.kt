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
import androidx.core.view.isGone
import androidx.core.view.isVisible
import org.draken.usagi.R

class ScrollListener
	@JvmOverloads
	constructor(
		context: Context? = null,
		attrs: AttributeSet? = null,
	) : CoordinatorLayout.Behavior<View>(context, attrs) {
		var isPinned: Boolean = false
		var isHidden = false
			private set

		var exWidth = 0
		private var animator: ValueAnimator? = null
		private var animatorY: ViewPropertyAnimator? = null
		private var fabAnimator: ViewPropertyAnimator? = null
		private var isActive = false

		override fun onStartNestedScroll(
			coordinatorLayout: CoordinatorLayout,
			child: View,
			directTargetChild: View,
			target: View,
			axes: Int,
			type: Int,
		): Boolean = !isPinned && axes == View.SCROLL_AXIS_VERTICAL

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
				coordinatorLayout,
				child,
				target,
				dxConsumed,
				dyConsumed,
				dxUnconsumed,
				dyUnconsumed,
				type,
				consumed,
			)
			if (dyConsumed > 0) {
				slideDown(child)
			} else if (dyConsumed < 0) {
				slideUp(child)
			}
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
				animatorY =
					child
						.animate()
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
			animatorY =
				child
					.animate()
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
			isActive = false
			animatorY?.cancel()
			animatorY = null
			isHidden = false
			val navBar = child.findViewById<View>(R.id.floatingNav)
			if (navBar != null) {
				navBar.visibility = View.VISIBLE
				navBar.alpha = 1f
				navBar.translationX = 0f
				val layoutParams = navBar.layoutParams
				val w = if (exWidth > 0) exWidth else ViewGroup.LayoutParams.WRAP_CONTENT
				if (layoutParams.width != w) {
					layoutParams.width = w
					navBar.layoutParams = layoutParams
				}
			}
			child.findViewById<View>(R.id.fabFloating)?.translationX = 0f
			child.translationY = 0f
			update(child)
		}

		fun update(child: View) {
			val fab = child.findViewById<View>(R.id.fabFloating) ?: return
			val navBar = child.findViewById<View>(R.id.floatingNav)
			if (isActive) return
			if (fab.tag as? Boolean != true) {
				animateFab(fab, false)
				return
			}
			val isNavBarHidden = isHidden || navBar?.isVisible == false
			val sortSide =
				if (isNavBarHidden) {
					false
				} else {
					navBar != null && sortSide(child, navBar, fab)
				}
			val show = isNavBarHidden || sortSide
			if (show) {
				animateFab(fab, true)
				fab.translationX = 0f
				(fab.layoutParams as? ViewGroup.MarginLayoutParams)?.run {
					val density = child.resources.displayMetrics.density
					val targetMargin = if (isNavBarHidden) 0 else (8 * density).toInt()
					if (marginStart != targetMargin) {
						marginStart = targetMargin
						fab.layoutParams = this
					}
				}
			} else if (navBar != null) {
				fabAnimator?.cancel()
				fabAnimator = null
				prepareCompactFab(fab, navBar, child)
			}
		}

		private fun animateFab(
			fab: View,
			show: Boolean,
		) {
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
			fabAnimator =
				fab
					.animate()
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

		private fun hideAction(
			navBar: View,
			fab: View,
			container: View,
		) {
			animator?.cancel()
			fabAnimator?.cancel()
			fabAnimator = null
			isActive = true
			if (exWidth <= 0) exWidth = navBar.width
			val compact = !sortSide(container, navBar, fab)
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
			fab.alpha = if (compact) 0f else 1f
			val fabWidth = measure(fab, container)
			navBar.translationX = 0f
			fab.translationX = 0f
			(fab.layoutParams as? ViewGroup.MarginLayoutParams)?.run {
				val targetMargin = if (compact) -fabWidth else 0
				if (marginStart != targetMargin) {
					marginStart = targetMargin
					fab.layoutParams = this
				}
			}
			val layout = navBar.layoutParams
			animator =
				ValueAnimator.ofFloat(0f, 1f).apply {
					duration = 200
					interpolator = DecelerateInterpolator()
					addUpdateListener { animator ->
						val p = animator.animatedValue as Float
						layout.width = (w - (w * p)).toInt()
						navBar.layoutParams = layout
						navBar.alpha = a * (1f - p)
						if (compact) {
							(fab.layoutParams as? ViewGroup.MarginLayoutParams)?.run {
								marginStart = (-(fabWidth * (1f - p))).toInt()
								fab.layoutParams = this
							}
							fab.alpha = p
						}
					}
					addListener(
						object : AnimatorListenerAdapter() {
							override fun onAnimationEnd(animation: Animator) {
								if (compact) {
									layout.width = 0
									navBar.layoutParams = layout
									navBar.alpha = 0f
								} else {
									navBar.visibility = View.GONE
								}
								navBar.translationX = 0f
								fab.translationX = 0f
								fab.alpha = 1f
								(fab.layoutParams as? ViewGroup.MarginLayoutParams)?.run {
									if (marginStart != 0) {
										marginStart = 0
										fab.layoutParams = this
									}
								}
								group?.post { group.layoutTransition = transition }
								group?.clipChildren = clipChild
								(navBar as? ViewGroup)?.clipChildren = cardClip
								animator = null
								isActive = false
							}
						},
					)
					start()
				}
		}

		private fun measure(
			view: View,
			container: View,
		): Int {
			if (view.width > 0) return view.width
			if (view.measuredWidth > 0) return view.measuredWidth
			view.measure(
				View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
				View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
			)
			return view.measuredWidth.takeIf { it > 0 } ?: (48 * container.resources.displayMetrics.density).toInt()
		}

		private fun prepareCompactFab(
			fab: View,
			navBar: View,
			container: View,
		) {
			fab.visibility = View.INVISIBLE
			fab.alpha = 0f
			fab.translationX = 0f
			(fab.layoutParams as? ViewGroup.MarginLayoutParams)?.run {
				val targetMargin = -measure(fab, container)
				if (marginStart != targetMargin) {
					marginStart = targetMargin
					fab.layoutParams = this
				}
			}
			navBar.translationX = 0f
		}

		private fun sortSide(
			child: View,
			navBar: View,
			fab: View,
		): Boolean {
			val width = (child.parent as? View)?.width ?: 0
			if (width <= 0) return true
			val density = child.resources.displayMetrics.density
			val nav =
				if (exWidth > 0) {
					exWidth
				} else {
					navBar.width.takeIf { it > 0 } ?: (230 * density).toInt()
				}
			val target = (8 * density).toInt()
			val startM = maxOf((fab.layoutParams as? ViewGroup.MarginLayoutParams)?.marginStart ?: target, target)
			return width >= nav + measure(fab, child) + startM
		}

		private fun animate(
			navBar: View,
			fab: View,
			container: View,
		) {
			animator?.cancel()
			fabAnimator?.cancel()
			fabAnimator = null
			isActive = true
			if (exWidth <= 0) {
				navBar.measure(
					View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
					View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
				)
				exWidth = navBar.measuredWidth
			}
			val compact = !sortSide(container, navBar, fab)
			val group = container as? ViewGroup
			val transition = group?.layoutTransition
			group?.layoutTransition = null
			val clipChild = group?.clipChildren ?: false
			val cardClip = (navBar as? ViewGroup)?.clipChildren ?: false
			group?.clipChildren = true
			(navBar as? ViewGroup)?.clipChildren = true
			navBar.visibility = View.VISIBLE
			val fabWidth = measure(fab, container)
			navBar.translationX = 0f
			(fab.layoutParams as? ViewGroup.MarginLayoutParams)?.run {
				val m = if (compact) 0 else (8 * container.resources.displayMetrics.density).toInt()
				if (marginStart != m) {
					marginStart = m
					fab.layoutParams = this
				}
			}
			val w = navBar.width
			val alpha = navBar.alpha
			val ex = exWidth
			val layout = navBar.layoutParams
			animator =
				ValueAnimator.ofFloat(0f, 1f).apply {
					duration = 200
					interpolator = DecelerateInterpolator()
					addUpdateListener { a ->
						val p = a.animatedValue as Float
						layout.width = w + ((ex - w) * p).toInt()
						navBar.layoutParams = layout
						navBar.alpha = alpha + ((1f - alpha) * p)
						if (compact) {
							(fab.layoutParams as? ViewGroup.MarginLayoutParams)?.run {
								marginStart = (-(fabWidth * p)).toInt()
								fab.layoutParams = this
							}
							fab.alpha = 1f - p
						}
					}
					addListener(
						object : AnimatorListenerAdapter() {
							override fun onAnimationEnd(animation: Animator) {
								layout.width = ViewGroup.LayoutParams.WRAP_CONTENT
								navBar.layoutParams = layout
								navBar.alpha = 1f
								navBar.translationX = 0f
								if (compact) {
									prepareCompactFab(fab, navBar, container)
								}
								fab.translationX = 0f
								group?.post { group.layoutTransition = transition }
								group?.clipChildren = clipChild
								(navBar as? ViewGroup)?.clipChildren = cardClip
								animator = null
								isActive = false
								update(container)
							}
						},
					)
					start()
				}
		}
	}
