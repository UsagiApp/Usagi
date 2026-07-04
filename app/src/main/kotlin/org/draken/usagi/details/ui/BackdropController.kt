package org.draken.usagi.details.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.View
import android.widget.ImageView
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.scale
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import coil3.ImageLoader
import coil3.asDrawable
import coil3.request.Disposable
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.allowRgb565
import coil3.request.crossfade
import coil3.request.lifecycle
import coil3.size.Precision
import org.draken.usagi.core.image.CoilImageView
import org.draken.usagi.core.prefs.AppSettings
import org.draken.usagi.core.util.ext.mangaSourceExtra
import tsuki.model.MangaSource

class BackdropController(
	private var backdrop: ImageView?,
	private var backdropGradient: View?,
	private var backdropTopGradient: View?,
	private var coverView: CoilImageView?,
	private val imageLoader: ImageLoader,
	private val lifecycle: LifecycleOwner,
	private val settings: AppSettings,
) : DefaultLifecycleObserver {
	private var currentDisposable: Disposable? = null
	private val placeholderDrawable: Drawable?
		get() = coverView?.placeholderDrawable?.constantState?.newDrawable()?.mutate()
	private val errorDrawable: Drawable?
		get() = coverView?.errorDrawable?.constantState?.newDrawable()?.mutate()
	private val fallbackDrawable: Drawable?
		get() = coverView?.fallbackDrawable?.constantState?.newDrawable()?.mutate()

	init {
		val context = backdrop?.context
		if (context != null) {
			val bgColor = context.obtainStyledAttributes(intArrayOf(android.R.attr.colorBackground)).run {
				getColor(0, Color.WHITE).also { recycle() }
			}
			applyGradients(bgColor)
		}
		lifecycle.lifecycle.addObserver(this)
	}

	fun load(imageUrl: String?, source: MangaSource? = null) {
		val view = backdrop ?: return
		currentDisposable?.dispose()
		val request = ImageRequest.Builder(view.context)
			.data(imageUrl?.takeIf { it.isNotBlank() })
			.mangaSourceExtra(source)
			.lifecycle(lifecycle)
			.crossfade(true)
			.allowHardware(false)
			.allowRgb565(true)
			.precision(Precision.INEXACT)
			.target(
				onStart = {
					val placeholder = placeholderDrawable ?: return@target
					placeholder.setBounds(0, 0, view.width.coerceAtLeast(0), view.height.coerceAtLeast(0))
					view.animate().cancel()
					view.alpha = 1f
					view.setImageDrawable(placeholder)
					applyBlur(view)
				},
				onSuccess = { image ->
					view.scaleX = 1f
					view.scaleY = 1.1f
					view.translationY = -view.height * 0.08f
					view.animate().cancel()
					view.alpha = 0f
					view.setImageDrawable(image.asDrawable(view.context.resources))
					applyBlur(view)
					view.animate()
						.alpha(1f)
						.setDuration(CROSSFADE_DURATION_MS)
						.setInterpolator(android.view.animation.DecelerateInterpolator())
						.start()
				},
				onError = {
					val err = errorDrawable ?: fallbackDrawable ?: return@target
					err.setBounds(0, 0, view.width.coerceAtLeast(0), view.height.coerceAtLeast(0))
					view.animate().cancel()
					view.alpha = 1f
					view.setImageDrawable(err)
					applyBlur(view)
				}
			).build()
		currentDisposable = imageLoader.enqueue(request)
	}

	override fun onDestroy(owner: LifecycleOwner) {
		currentDisposable?.dispose()
		currentDisposable = null
		backdrop = null
		backdropGradient = null
		backdropTopGradient = null
		coverView = null
		owner.lifecycle.removeObserver(this)
	}

	private fun applyGradients(surfaceColor: Int) {
		fun alpha(a: Int) = ColorUtils.setAlphaComponent(surfaceColor, a)
		backdropGradient?.background = GradientDrawable(
			GradientDrawable.Orientation.TOP_BOTTOM,
			intArrayOf(
				Color.TRANSPARENT,
				alpha(25), alpha(50), alpha(100),
				alpha(160), alpha(210), alpha(240),
				alpha(248), alpha(253), surfaceColor,
			),
		)
		backdropTopGradient?.background = GradientDrawable(
			GradientDrawable.Orientation.TOP_BOTTOM,
			intArrayOf(
				surfaceColor,
				alpha(240), alpha(200), alpha(140),
				alpha(80), alpha(30), Color.TRANSPARENT,
			),
		)
	}

	@Suppress("DEPRECATION")
	private fun applyBlur(view: ImageView) {
		val amount = settings.backdropBlurAmount
		if (amount <= 0) {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) view.setRenderEffect(null)
			return
		}
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			val radius = blurRadius(amount, MAX_BLUR_RADIUS_API31)
			view.setRenderEffect(
				RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.MIRROR),
			)
			return
		}

		val bitmap = drawableToBitmap(view.drawable ?: return, view)
		val scaled = bitmap.scale(
			(bitmap.width * BLUR_SCALE_FACTOR).toInt().coerceAtLeast(1),
			(bitmap.height * BLUR_SCALE_FACTOR).toInt().coerceAtLeast(1),
		)
		if (bitmap !== scaled) bitmap.recycle()
		val rsRadius = blurRadius(amount, MAX_BLUR_RADIUS_RS).coerceIn(1f, MAX_BLUR_RADIUS_RS)
		android.renderscript.RenderScript.create(view.context).also { rs ->
			val input = android.renderscript.Allocation.createFromBitmap(rs, scaled)
			val output = android.renderscript.Allocation.createTyped(rs, input.type)
			android.renderscript.ScriptIntrinsicBlur.create(rs, android.renderscript.Element.U8_4(rs)).apply {
				setRadius(rsRadius)
				setInput(input)
				forEach(output)
			}
			output.copyTo(scaled)
			rs.destroy()
		}
		view.setImageBitmap(scaled)
	}

	private fun drawableToBitmap(drawable: Drawable, view: ImageView): Bitmap {
		if (drawable is android.graphics.drawable.BitmapDrawable) {
			return drawable.bitmap.copy(Bitmap.Config.ARGB_8888, true)
		}
		val w = drawable.intrinsicWidth.takeIf { it > 0 }
			?: view.width.takeIf { it > 0 } ?: view.resources.displayMetrics.widthPixels
		val h = drawable.intrinsicHeight.takeIf { it > 0 }
			?: view.height.takeIf { it > 0 } ?: view.resources.displayMetrics.heightPixels
		return drawable.toBitmap(w, h, Bitmap.Config.ARGB_8888)
	}

	companion object {
		private const val CROSSFADE_DURATION_MS = 400L
		private const val BLUR_SCALE_FACTOR = 0.4f
		private const val MAX_BLUR_RADIUS_API31 = 25f
		private const val MAX_BLUR_RADIUS_RS = 25f

		fun blurRadius(amount: Int, maxRadius: Float): Float =
			(amount / 100f) * maxRadius
	}
}
