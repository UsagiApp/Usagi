package org.draken.usagi.reader.ui.pager.curl

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Draws a folded page with plain Canvas ops (clip + matrix): no shaders, no bitmaps, API 21+.
 *
 * The sheet is held at the grab point and dragged to the tip. The crease is the perpendicular bisector of that
 * segment: the grab side of the sheet is mirrored across it and drawn on top (flap), the rest stays flat.
 */
class PageCurlRenderer(
	context: Context,
) {
	private val density = context.resources.displayMetrics.density
	private val reflect = Matrix()
	private val values = FloatArray(9)
	private val front = Path()
	private val grab = Path()
	private val flap = Path()
	private val corners = FloatArray(8)
	private val poly = FloatArray(16)
	private val dropPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
	private val tintPaint = Paint()
	private val baseShadow = gradient(0x66000000, 0x00000000)
	private val creaseShade = gradient(0x52000000, 0x00000000)
	private val flapShade = gradient(0x55000000, 0x2EFFFFFF, 0x00FFFFFF)

	// crease unit normal (grab -> tip) and midpoint
	private var nx = 0f
	private var ny = 0f
	private var mx = 0f
	private var my = 0f

	/** @param fade 1 = full shading, 0 = none (sheet landed, so the last frame is exact) */
	fun draw(
		canvas: Canvas,
		width: Int,
		height: Int,
		sheet: RectF,
		gx: Float,
		gy: Float,
		tx: Float,
		ty: Float,
		fade: Float,
		scene: PageCurlScene,
	) {
		canvas.save()
		canvas.clipRect(0, 0, width, height)
		scene.drawBase(canvas)
		val len = hypot(tx - gx, ty - gy)
		if (len < MIN_LENGTH) {
			scene.drawFront(canvas)
		} else {
			nx = (tx - gx) / len
			ny = (ty - gy) / len
			mx = (gx + tx) / 2f
			my = (gy + ty) / 2f
			fold(canvas, sheet, len, fade, scene)
		}
		canvas.restore()
	}

	private fun fold(
		canvas: Canvas,
		sheet: RectF,
		len: Float,
		fade: Float,
		scene: PageCurlScene,
	) {
		val diag = hypot(sheet.width(), sheet.height())
		// shading grows with the lift instead of popping in at full strength
		val ramp = (len / (LIFT_RAMP * diag)).coerceIn(0f, 1f)
		val shade = fade * ramp * ramp * (3f - 2f * ramp)
		val soft = shade > 0.01f
		val thin = scene.creaseShadowOnly
		sheet.toCorners()
		val hasFront = clip(front, 1f)
		val hasFlap = clip(grab, -1f)

		if (soft && hasFlap) band(canvas, grab, baseShadow, -nx, -ny, if (thin) CREASE_W else BASE_W, shade, diag)
		if (hasFront) {
			canvas.save()
			canvas.clipPath(front)
			scene.drawFront(canvas)
			canvas.restore()
		}
		if (!hasFlap) return

		setReflect()
		grab.transform(reflect, flap)
		if (soft && !thin) dropShadow(canvas, shade)
		canvas.save()
		canvas.concat(reflect)
		canvas.clipPath(grab)
		scene.drawBack(canvas)
		if (Color.alpha(scene.backTint) != 0) {
			tintPaint.color = scene.backTint
			canvas.drawRect(sheet, tintPaint)
		}
		canvas.restore()
		// light falloff along the crease, suggests the paper bending
		if (soft) band(canvas, flap, if (thin) creaseShade else flapShade, nx, ny, if (thin) CREASE_W else FLAP_W, shade, diag)
	}

	/** Faint offset copies of the flap approximate a soft drop shadow. */
	private fun dropShadow(
		canvas: Canvas,
		shade: Float,
	) {
		dropPaint.alpha = (DROP_ALPHA * shade).toInt()
		for (i in 1..DROP_LAYERS) {
			canvas.save()
			canvas.translate(1.2f * density * i, 1.8f * density * i)
			canvas.drawPath(flap, dropPaint)
			canvas.restore()
		}
	}

	/** Gradient strip along the crease, running towards (dx, dy), clipped to [area]. */
	private fun band(
		canvas: Canvas,
		area: Path,
		shade: GradientDrawable,
		dx: Float,
		dy: Float,
		width: Float,
		strength: Float,
		diag: Float,
	) {
		val half = (diag * 2f).toInt()
		canvas.save()
		canvas.clipPath(area)
		canvas.translate(mx, my)
		canvas.rotate(atan2(dy, dx) * DEGREES)
		shade.alpha = (strength * 255f).toInt()
		shade.setBounds(0, -half, (diag * width).toInt().coerceAtLeast(1), half)
		shade.draw(canvas)
		canvas.restore()
	}

	/** Mirror across the crease: p' = p - 2 * dot(p - m, n) * n. */
	private fun setReflect() {
		val k = 2f * (mx * nx + my * ny)
		values[0] = 1f - 2f * nx * nx
		values[1] = -2f * nx * ny
		values[2] = k * nx
		values[3] = values[1]
		values[4] = 1f - 2f * ny * ny
		values[5] = k * ny
		values[6] = 0f
		values[7] = 0f
		values[8] = 1f
		reflect.setValues(values)
	}

	private fun RectF.toCorners() {
		corners[0] = left
		corners[1] = top
		corners[2] = right
		corners[3] = top
		corners[4] = right
		corners[5] = bottom
		corners[6] = left
		corners[7] = bottom
	}

	/** Sutherland-Hodgman against the crease half-plane, keeps points with side * dot(p - m, n) >= 0. */
	private fun clip(
		path: Path,
		side: Float,
	): Boolean {
		var n = 0
		for (i in 0 until 4) {
			val j = (i + 1) % 4
			val ax = corners[i * 2]
			val ay = corners[i * 2 + 1]
			val bx = corners[j * 2]
			val by = corners[j * 2 + 1]
			val sa = side * ((ax - mx) * nx + (ay - my) * ny)
			val sb = side * ((bx - mx) * nx + (by - my) * ny)
			if (sa < 0f && sb < 0f) continue
			if ((sa >= 0f) != (sb >= 0f)) {
				val t = sa / (sa - sb)
				poly[n * 2] = ax + (bx - ax) * t
				poly[n * 2 + 1] = ay + (by - ay) * t
				n++
			}
			if (sb >= 0f) {
				poly[n * 2] = bx
				poly[n * 2 + 1] = by
				n++
			}
		}
		path.reset()
		if (n < 3) return false
		path.moveTo(poly[0], poly[1])
		for (i in 1 until n) path.lineTo(poly[i * 2], poly[i * 2 + 1])
		path.close()
		return true
	}

	private fun gradient(vararg colors: Int) = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, colors)

	private companion object {
		const val MIN_LENGTH = 1.5f
		const val DEGREES = 57.29578f

		/** Tip distance (fraction of the sheet diagonal) at which shading reaches full strength. */
		const val LIFT_RAMP = 0.12f
		const val BASE_W = 0.05f
		const val FLAP_W = 0.10f

		/** Width of the single shadow line along the crease. */
		const val CREASE_W = 0.022f
		const val DROP_LAYERS = 6
		const val DROP_ALPHA = 20f
	}
}
