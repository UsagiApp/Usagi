package org.draken.usagi.reader.ui.tapgrid

import android.view.GestureDetector
import android.view.InputDevice.SOURCE_MOUSE
import android.view.MotionEvent
import android.view.MotionEvent.ACTION_CANCEL
import android.view.MotionEvent.ACTION_DOWN
import android.view.MotionEvent.ACTION_UP
import android.view.MotionEvent.TOOL_TYPE_MOUSE
import android.view.View
import android.view.ViewConfiguration
import org.draken.usagi.reader.domain.TapGridArea
import kotlin.math.roundToInt

class TapGridDispatcher(
	private val rootView: View,
	private val listener: OnGridTouchListener,
) : GestureDetector.SimpleOnGestureListener() {

	@Suppress("UsePropertyAccessSyntax")
	private val detector = GestureDetector(rootView.context, this).apply {
		setIsLongpressEnabled(true)
		setOnDoubleTapListener(this@TapGridDispatcher)
	}

	private val layoutChangeListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
		active = false
	}
	init {
		rootView.addOnLayoutChangeListener(layoutChangeListener)
	}

	private val rootLoc = IntArray(2)
	private val touchSlopSq = ViewConfiguration.get(rootView.context).scaledTouchSlop.let { it * it }
	private var active = false
	private var downX = 0f
	private var downY = 0f

	fun dispatchTouchEvent(e: MotionEvent) {
		val mouse = e.getToolType(0) == TOOL_TYPE_MOUSE && e.source and SOURCE_MOUSE != 0
		when (e.actionMasked) {
			ACTION_DOWN -> {
				active = listener.onProcessTouch(e.rawX.toInt(), e.rawY.toInt())
				if (mouse) {
					downX = e.rawX
					downY = e.rawY
					return
				}
			}
			ACTION_UP -> if (mouse && active) {
				val dx = e.rawX - downX
				val dy = e.rawY - downY
				if (dx * dx + dy * dy <= touchSlopSq) tap(e.rawX, e.rawY)
				return
			}
			ACTION_CANCEL -> {
				active = false
				if (mouse) return
			}
		}
		detector.onTouchEvent(e)
	}

	override fun onSingleTapConfirmed(e: MotionEvent) =
		if (!active) true else tap(e.rawX, e.rawY)

	override fun onDoubleTapEvent(e: MotionEvent): Boolean {
		active = false // ignore long press after double tap
		return super.onDoubleTapEvent(e)
	}

	override fun onLongPress(e: MotionEvent) {
		if (active) getArea(e.rawX, e.rawY)?.let(listener::onGridLongTouch)
	}

	private fun tap(rawX: Float, rawY: Float): Boolean {
		val area = getArea(rawX, rawY) ?: return false
		return listener.onGridTouch(area)
	}

	private fun getArea(rawX: Float, rawY: Float): TapGridArea? {
		val w = rootView.width
		val h = rootView.height
		if (w <= 0 || h <= 0) return null
		rootView.getLocationOnScreen(rootLoc)
		val xi = ((rawX - rootLoc[0]) * 2f / w).roundToInt()
		val yi = ((rawY - rootLoc[1]) * 2f / h).roundToInt()
		return GRID.getOrNull(xi)?.getOrNull(yi)
	}

	interface OnGridTouchListener {
		fun onGridTouch(area: TapGridArea): Boolean
		fun onGridLongTouch(area: TapGridArea)
		fun onProcessTouch(rawX: Int, rawY: Int): Boolean
	}

	private companion object {
		private val GRID = listOf(
			listOf(TapGridArea.TOP_LEFT, TapGridArea.CENTER_LEFT, TapGridArea.BOTTOM_LEFT),
			listOf(TapGridArea.TOP_CENTER, TapGridArea.CENTER, TapGridArea.BOTTOM_CENTER),
			listOf(TapGridArea.TOP_RIGHT, TapGridArea.CENTER_RIGHT, TapGridArea.BOTTOM_RIGHT),
		)
	}
}
