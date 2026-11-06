package org.draken.usagi.reader.ui.pager.curl

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Page turn that follows the finger, drawn as an overlay on [host] (API 21+, see [PageCurlRenderer]).
 * The real pages are only touched when a turn is committed.
 *
 * The sheet is the page turning forward (hinged left / top, peeled from the right / bottom edge). A drag towards
 * the previous page plays the turn of the previous page in reverse. It is held by a corner of its free edge, the
 * one that follows the vertical direction of the pull (down = top corner, up = bottom corner), and the tip never
 * leaves a 45 degree cone around the paging axis, so the flap grows gradually from the corner.
 *
 * `turnP` runs from 0 (sheet flat) to 2 * length (sheet landed on the other side).
 */
class PageCurlController(
	private val host: ViewGroup,
	private val touchRv: RecyclerView,
	private val scene: PageCurlScene,
	private val isEnabled: () -> Boolean,
) : RecyclerView.OnItemTouchListener {
	private val density = host.resources.displayMetrics.density

	// below the host's own scroll slop, so the host never starts sliding the pages first
	private val slop = ViewConfiguration.get(host.context).scaledTouchSlop * 0.8f
	private val renderer = PageCurlRenderer(host.context)
	private val sheet = RectF()
	private val finishFallback = Runnable { if (phase != CurlPhase.IDLE) clear() }

	private var phase = CurlPhase.IDLE
	private var dir = 0
	private var gx = 0f
	private var gy = 0f
	private var tx = 0f
	private var ty = 0f
	private var turnP = 0f
	private var lateral = 0f

	// held corner: true = low side of the lateral axis (top / left), false = high side (bottom / right)
	private var nearCorner = false
	private var downX = 0f
	private var downY = 0f
	private var refX = 0f
	private var refY = 0f
	private var pointerId = -1
	private var multiTouch = false
	private var swallow = false
	private var velocity: VelocityTracker? = null
	private var animator: ValueAnimator? = null
	private var preDraw: ViewTreeObserver.OnPreDrawListener? = null

	private val overlay =
		object : Drawable() {
			override fun draw(canvas: Canvas) {
				if (phase == CurlPhase.IDLE) return
				renderer.draw(canvas, host.width, host.height, sheet, gx, gy, tx, ty, fade(), scene)
			}

			override fun setAlpha(alpha: Int) = Unit

			override fun setColorFilter(colorFilter: ColorFilter?) = Unit

			@Deprecated("Deprecated in Java")
			override fun getOpacity() = PixelFormat.TRANSLUCENT
		}

	fun attach() = touchRv.addOnItemTouchListener(this)

	fun detach() {
		touchRv.removeOnItemTouchListener(this)
		animator?.cancel()
		animator = null
		velocity?.recycle()
		velocity = null
		clear()
	}

	/**
	 * Programmatic turn (taps, keys), lifted from the bottom corner.
	 * @return false when the host has to handle it (disabled, first / last page)
	 */
	fun turn(direction: Int): Boolean {
		if (!isEnabled()) return false
		// already running: ignore instead of letting the host slide the pages
		if (phase != CurlPhase.IDLE) return true
		when (scene.prepare(direction, sheet)) {
			CurlAvailability.NONE -> return false
			CurlAvailability.BUSY -> return true
			CurlAvailability.OK -> Unit
		}
		begin(direction)
		show()
		val len = length()
		animate(AUTO_TURN_MS, AccelerateDecelerateInterpolator(), true) { u ->
			// forward goes 0 -> 2L, backward is the same motion in reverse
			val v = if (dir > 0) u else 1f - u
			val p = 2f * len * v
			applyPose(p, -0.35f * p * (1f - v))
		}
		return true
	}

	override fun onInterceptTouchEvent(
		rv: RecyclerView,
		e: MotionEvent,
	): Boolean {
		when (e.actionMasked) {
			MotionEvent.ACTION_DOWN -> {
				downX = e.x
				downY = e.y
				pointerId = e.getPointerId(0)
				multiTouch = false
				swallow = false
			}

			MotionEvent.ACTION_POINTER_DOWN -> {
				multiTouch = true
			}

			MotionEvent.ACTION_MOVE -> {
				if (phase == CurlPhase.IDLE && !swallow && !multiTouch && isEnabled()) return tryStart(rv, e)
			}
		}
		return phase != CurlPhase.IDLE || swallow
	}

	override fun onTouchEvent(
		rv: RecyclerView,
		e: MotionEvent,
	) {
		if (swallow) {
			if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) swallow = false
			return
		}
		if (phase != CurlPhase.DRAGGING) return
		velocity?.addMovement(e)
		val i = e.findPointerIndex(pointerId)
		when (e.actionMasked) {
			MotionEvent.ACTION_MOVE -> {
				if (i >= 0) moveTip(e.getX(i), e.getY(i))
			}

			MotionEvent.ACTION_UP -> {
				if (i >= 0) moveTip(e.getX(i), e.getY(i))
				endDrag(false)
			}

			MotionEvent.ACTION_POINTER_UP -> {
				if (e.getPointerId(e.actionIndex) == pointerId) endDrag(false)
			}

			MotionEvent.ACTION_CANCEL -> {
				endDrag(true)
			}
		}
	}

	override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) = Unit

	private fun tryStart(
		rv: RecyclerView,
		e: MotionEvent,
	): Boolean {
		val direction = pickDirection(e)
		if (direction == 0) return false
		when (scene.prepare(direction, sheet)) {
			CurlAvailability.NONE -> {
				return false
			}

			CurlAvailability.BUSY -> {
				swallow = true
				rv.parent?.requestDisallowInterceptTouchEvent(true)
				return true
			}

			CurlAvailability.OK -> {
				Unit
			}
		}
		begin(direction)
		refX = e.x
		refY = e.y
		velocity = VelocityTracker.obtain().also { it.addMovement(e) }
		phase = CurlPhase.DRAGGING
		show()
		rv.parent?.requestDisallowInterceptTouchEvent(true)
		return true
	}

	/**
	 * +1 / -1 = turn towards the next / previous page, 0 = not ours. Travel along the paging axis past the slop is
	 * always ours (the host would slide the pages), a lateral pull only when it starts near an edge: the page is held
	 * by that corner and the side the finger went down on says which page it is.
	 */
	private fun pickDirection(e: MotionEvent): Int {
		val vertical = scene.isVertical
		val along = if (vertical) e.y - downY else e.x - downX
		val across = if (vertical) e.x - downX else e.y - downY
		val acrossAt = if (vertical) downX else downY
		val acrossSize = (if (vertical) host.width else host.height).toFloat()
		val zone = acrossSize * EDGE_ZONE
		val nearEdge = acrossAt <= zone || acrossAt >= acrossSize - zone
		val byCorner = abs(across) > abs(along) && nearEdge && abs(across) >= slop
		return when {
			byCorner -> {
				val alongAt = if (vertical) downY else downX
				if (alongAt < (if (vertical) host.height else host.width) / 2f) -1 else 1
			}

			abs(along) < slop -> {
				0
			}

			along < 0f -> {
				1
			}

			else -> {
				-1
			}
		}
	}

	private fun begin(direction: Int) {
		dir = direction
		nearCorner = false
		applyPose(if (dir > 0) 0f else 2f * length(), 0f)
	}

	private fun moveTip(
		x: Float,
		y: Float,
	) {
		val vertical = scene.isVertical
		val along = if (vertical) y - refY else x - refX
		val across = if (vertical) x - refX else y - refY
		// forward: tip follows the finger 1:1, backward: the same motion in reverse scaled by the scene's gain
		val toward = max(if (dir > 0) -along else scene.backwardGain * along, 0f)
		// pulling mostly sideways keeps the crease straight, along the edge lifts the corner diagonally
		val q = max(toward, abs(across))
		val eased = easeIn(q)
		val scale = if (q > 0f) eased / q else 0f
		applyPose(if (dir > 0) eased else 2f * length() - eased, across * scale)
		overlay.invalidateSelf()
	}

	/** Sets turn and sideways offset, picks the held corner from the offset sign and derives grab point and tip. */
	private fun applyPose(
		p: Float,
		sideways: Float,
	) {
		turnP = p.coerceIn(0f, 2f * length())
		if (sideways > SIDE_EPS) {
			nearCorner = true
		} else if (sideways < -SIDE_EPS) {
			nearCorner = false
		}
		val limit = maxSideways(turnP)
		lateral = if (nearCorner) sideways.coerceIn(0f, limit) else sideways.coerceIn(-limit, 0f)
		if (scene.isVertical) {
			gx = if (nearCorner) sheet.left else sheet.right
			gy = sheet.bottom
			tx = gx + lateral
			ty = gy - turnP
		} else {
			gx = sheet.right
			gy = if (nearCorner) sheet.top else sheet.bottom
			tx = gx - turnP
			ty = gy + lateral
		}
	}

	/**
	 * How far the tip may leave the paging axis. With a spine the crease must meet the free edge before the spine,
	 * which also makes it parallel to the spine exactly when the sheet has landed.
	 */
	private fun maxSideways(p: Float): Float {
		val len = length()
		val cone = if (dir > 0) p else 2f * len - p
		if (!scene.isAttachedToSpine) return cone
		return min(cone, sqrt(max(2f * p * (len - p / 2f), 0f)) * SPINE_MARGIN)
	}

	/** Zero speed at 0, identity once [EASE_IN_DP] has been travelled. */
	private fun easeIn(x: Float): Float {
		val r = EASE_IN_DP * density
		if (x <= 0f) return 0f
		if (x >= r) return x
		val t = x / r
		return x * t * t * (3f - 2f * t)
	}

	private fun endDrag(cancelled: Boolean) {
		val tracker = velocity
		tracker?.computeCurrentVelocity(1000)
		val speed = (if (scene.isVertical) tracker?.yVelocity else tracker?.xVelocity) ?: 0f
		tracker?.recycle()
		velocity = null
		val towardsEnd = -speed * dir
		val fling = FLING_DP * density
		val commit = !cancelled && towardsEnd > -fling && (towardsEnd > fling || travel() > COMMIT_TRAVEL)
		val len = length()
		val fromP = turnP
		val fromSide = lateral
		val toP = if (commit == (dir > 0)) 2f * len else 0f
		val ms = (140f + 240f * abs(toP - fromP) / (2f * len)).toLong()
		animate(ms, DecelerateInterpolator(1.4f), commit) { u ->
			applyPose(fromP + (toP - fromP) * u, fromSide * (1f - u))
		}
	}

	private fun animate(
		duration: Long,
		interpolator: TimeInterpolator,
		commit: Boolean,
		update: (Float) -> Unit,
	) {
		phase = CurlPhase.ANIMATING
		animator?.cancel()
		animator =
			ValueAnimator.ofFloat(0f, 1f).apply {
				this.duration = duration
				this.interpolator = interpolator
				addUpdateListener {
					update(it.animatedValue as Float)
					overlay.invalidateSelf()
				}
				addListener(
					object : AnimatorListenerAdapter() {
						private var cancelled = false

						override fun onAnimationCancel(animation: Animator) {
							cancelled = true
						}

						override fun onAnimationEnd(animation: Animator) {
							if (!cancelled) finish(commit)
						}
					},
				)
				start()
			}
	}

	private fun finish(commit: Boolean) {
		animator = null
		if (!commit) return clear()
		// keep the last frame until the real pager has laid out the new page
		scene.commit(dir)
		val listener =
			object : ViewTreeObserver.OnPreDrawListener {
				override fun onPreDraw(): Boolean {
					clear()
					return true
				}
			}
		preDraw = listener
		host.viewTreeObserver.addOnPreDrawListener(listener)
		// never stay locked if no frame gets drawn (host hidden, detached...)
		host.postDelayed(finishFallback, FINISH_TIMEOUT_MS)
		host.invalidate()
	}

	private fun show() {
		overlay.setBounds(0, 0, host.width, host.height)
		host.overlay.add(overlay)
		overlay.invalidateSelf()
	}

	private fun clear() {
		host.removeCallbacks(finishFallback)
		preDraw?.let { host.viewTreeObserver.removeOnPreDrawListener(it) }
		preDraw = null
		host.overlay.remove(overlay)
		scene.release()
		phase = CurlPhase.IDLE
	}

	private fun length() = if (scene.isVertical) sheet.height() else sheet.width()

	/** Finger travel in sheet lengths, the same for both directions. */
	private fun travel(): Float {
		val len = length()
		return if (dir > 0) turnP / len else (2f * len - turnP) / (scene.backwardGain * len)
	}

	/** 1 in the air, 0 once landed so the last frame is exact. */
	private fun fade() = 1f - ((turnP / (2f * length()) - 0.85f) / 0.15f).coerceIn(0f, 1f)

	private companion object {
		const val EDGE_ZONE = 0.3f
		const val SPINE_MARGIN = 0.95f
		const val SIDE_EPS = 0.5f
		const val COMMIT_TRAVEL = 0.3f
		const val FLING_DP = 700f
		const val EASE_IN_DP = 28f
		const val AUTO_TURN_MS = 420L
		const val FINISH_TIMEOUT_MS = 400L
	}
}
