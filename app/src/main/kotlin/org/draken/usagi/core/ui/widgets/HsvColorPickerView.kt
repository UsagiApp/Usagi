package org.draken.usagi.core.ui.widgets

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.annotation.ColorInt
import org.draken.usagi.R

class HsvColorPickerView
	@JvmOverloads
	constructor(
		context: Context,
		attrs: AttributeSet? = null,
	) : View(context, attrs) {
		private val hsv = floatArrayOf(0f, 1f, 1f)
		private val squareRect = RectF()
		private val hueRect = RectF()
		private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
		private val thumbPaint =
			Paint(Paint.ANTI_ALIAS_FLAG).apply {
				style = Paint.Style.STROKE
				color = Color.WHITE
				strokeWidth = resources.getDimension(R.dimen.color_picker_thumb_stroke)
			}
		private val thumbOutlinePaint =
			Paint(thumbPaint).apply {
				color = Color.BLACK
				alpha = 0x60
				strokeWidth = thumbPaint.strokeWidth * 2f
			}
		private val thumbRadius = resources.getDimension(R.dimen.color_picker_thumb_radius)
		private val hueHeight = resources.getDimension(R.dimen.color_picker_hue_height)
		private val cornerRadius = resources.getDimension(R.dimen.color_picker_corner)
		private var saturationShader: Shader? = null
		private var valueShader: Shader? = null
		private var hueShader: Shader? = null
		private var dragTarget: RectF? = null

		var onColorChangeListener: ((Int) -> Unit)? = null

		@get:ColorInt
		var color: Int
			get() = Color.HSVToColor(hsv)
			set(value) {
				Color.colorToHSV(value, hsv)
				updateSaturationShader()
				invalidate()
			}

		override fun onSizeChanged(
			w: Int,
			h: Int,
			oldw: Int,
			oldh: Int,
		) {
			super.onSizeChanged(w, h, oldw, oldh)
			val inset = thumbRadius
			hueRect.set(inset, h - inset - hueHeight, w - inset, h - inset)
			squareRect.set(inset, inset, w - inset, hueRect.top - thumbRadius * 2f)
			valueShader =
				LinearGradient(0f, squareRect.top, 0f, squareRect.bottom, Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
			val hues = IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f)) }
			hueShader = LinearGradient(hueRect.left, 0f, hueRect.right, 0f, hues, null, Shader.TileMode.CLAMP)
			updateSaturationShader()
		}

		override fun onDraw(canvas: Canvas) {
			super.onDraw(canvas)
			paint.shader = saturationShader
			canvas.drawRoundRect(squareRect, cornerRadius, cornerRadius, paint)
			paint.shader = valueShader
			canvas.drawRoundRect(squareRect, cornerRadius, cornerRadius, paint)
			paint.shader = hueShader
			canvas.drawRoundRect(hueRect, cornerRadius, cornerRadius, paint)
			val squareX = squareRect.left + hsv[1] * squareRect.width()
			drawThumb(canvas, squareX, squareRect.top + (1f - hsv[2]) * squareRect.height())
			drawThumb(canvas, hueRect.left + hsv[0] / 360f * hueRect.width(), hueRect.centerY())
		}

		@SuppressLint("ClickableViewAccessibility")
		override fun onTouchEvent(event: MotionEvent): Boolean {
			when (event.actionMasked) {
				MotionEvent.ACTION_DOWN -> {
					dragTarget =
						when {
							event.y >= hueRect.top - thumbRadius -> hueRect
							event.y <= squareRect.bottom + thumbRadius -> squareRect
							else -> null
						}
					if (dragTarget == null) {
						return false
					}
					parent?.requestDisallowInterceptTouchEvent(true)
					onDrag(event)
				}

				MotionEvent.ACTION_MOVE -> {
					onDrag(event)
				}

				MotionEvent.ACTION_UP,
				MotionEvent.ACTION_CANCEL,
				-> {
					dragTarget = null
				}
			}
			return true
		}

		private fun onDrag(event: MotionEvent) {
			val target = dragTarget ?: return
			val x = ((event.x - target.left) / target.width()).coerceIn(0f, 1f)
			if (target === hueRect) {
				hsv[0] = x * 360f
				updateSaturationShader()
			} else {
				hsv[1] = x
				hsv[2] = 1f - ((event.y - target.top) / target.height()).coerceIn(0f, 1f)
			}
			invalidate()
			onColorChangeListener?.invoke(color)
		}

		private fun updateSaturationShader() {
			if (squareRect.isEmpty) {
				return
			}
			val hue = Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f))
			saturationShader =
				LinearGradient(squareRect.left, 0f, squareRect.right, 0f, Color.WHITE, hue, Shader.TileMode.CLAMP)
		}

		private fun drawThumb(
			canvas: Canvas,
			x: Float,
			y: Float,
		) {
			canvas.drawCircle(x, y, thumbRadius, thumbOutlinePaint)
			canvas.drawCircle(x, y, thumbRadius, thumbPaint)
		}
	}
