package org.draken.usagi.history.ui.util

import android.animation.Animator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Outline
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.annotation.AttrRes
import androidx.annotation.StyleRes
import org.draken.usagi.R
import org.draken.usagi.core.prefs.ProgressIndicatorMode.CHAPTERS_LEFT
import org.draken.usagi.core.prefs.ProgressIndicatorMode.CHAPTERS_READ
import org.draken.usagi.core.prefs.ProgressIndicatorMode.NONE
import org.draken.usagi.core.prefs.ProgressIndicatorMode.PERCENT_LEFT
import org.draken.usagi.core.prefs.ProgressIndicatorMode.PERCENT_READ
import org.draken.usagi.core.util.ext.getAnimationDuration
import org.draken.usagi.list.domain.ReadingProgress
import org.draken.usagi.list.domain.ReadingProgress.Companion.PROGRESS_NONE
import kotlin.math.roundToInt

class ReadingProgressView
	@JvmOverloads
	constructor(
		context: Context,
		attrs: AttributeSet? = null,
		@AttrRes defStyleAttr: Int = 0,
	) : View(context, attrs, defStyleAttr),
		ValueAnimator.AnimatorUpdateListener,
		Animator.AnimatorListener {
		private val percentPattern = context.getString(R.string.percent_string_pattern)
		private var percentAnimator: ValueAnimator? = null
		private val animationDuration = context.getAnimationDuration(android.R.integer.config_shortAnimTime)

		@StyleRes
		private val drawableStyle: Int
		private val sizeFraction: Float
		private val minSize: Int
		private val maxSize: Int

		var progress: ReadingProgress? = null
			set(value) {
				field = value
				cancelAnimation()
				getProgressDrawable().also {
					it.percent = value?.percent ?: PROGRESS_NONE
					it.text = value.getDisplayText(percentPattern)
				}
			}

		init {
			val ta = context.obtainStyledAttributes(attrs, R.styleable.ReadingProgressView, defStyleAttr, 0)
			drawableStyle = ta.getResourceId(R.styleable.ReadingProgressView_progressStyle, R.style.ProgressDrawable)
			sizeFraction = ta.getFloat(R.styleable.ReadingProgressView_sizeFraction, 0f)
			minSize = ta.getDimensionPixelSize(R.styleable.ReadingProgressView_android_minWidth, 0)
			maxSize = ta.getDimensionPixelSize(R.styleable.ReadingProgressView_android_maxWidth, Int.MAX_VALUE)
			ta.recycle()
			outlineProvider = OutlineProvider()
			if (isInEditMode) {
				progress =
					ReadingProgress(
						percent = 0.27f,
						totalChapters = 20,
						mode = PERCENT_READ,
					)
			}
		}

		override fun onMeasure(
			widthMeasureSpec: Int,
			heightMeasureSpec: Int,
		) {
			if (sizeFraction <= 0f || MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
				super.onMeasure(widthMeasureSpec, heightMeasureSpec)
				return
			}
			val lp = layoutParams as? ViewGroup.MarginLayoutParams
			val available = MeasureSpec.getSize(widthMeasureSpec) + (lp?.leftMargin ?: 0) + (lp?.rightMargin ?: 0)
			val size = (available * sizeFraction).roundToInt().coerceIn(minSize, maxSize)
			setMeasuredDimension(size, size)
		}

		override fun onDetachedFromWindow() {
			super.onDetachedFromWindow()
			percentAnimator?.run {
				if (isRunning) end()
			}
			percentAnimator = null
		}

		override fun onAnimationUpdate(animation: ValueAnimator) {
			val p = animation.animatedValue as Float
			getProgressDrawable().percent = p
		}

		override fun onAnimationStart(animation: Animator) = Unit

		override fun onAnimationEnd(animation: Animator) {
			if (percentAnimator === animation) {
				percentAnimator = null
			}
		}

		override fun onAnimationCancel(animation: Animator) = Unit

		override fun onAnimationRepeat(animation: Animator) = Unit

		fun setProgress(
			percent: Float,
			animate: Boolean,
		) {
			setProgress(
				value = ReadingProgress(percent, 1, PERCENT_READ),
				animate = animate,
			)
		}

		fun setProgress(
			value: ReadingProgress?,
			animate: Boolean,
		) {
			val currentDrawable = peekProgressDrawable()
			if (!animate || currentDrawable == null || value == null) {
				progress = value
				return
			}
			percentAnimator?.cancel()
			val currentPercent = currentDrawable.percent.coerceAtLeast(0f)
			progress = value.copy(percent = currentPercent)
			percentAnimator =
				ValueAnimator
					.ofFloat(
						currentDrawable.percent.coerceAtLeast(0f),
						value.percent,
					).apply {
						duration = animationDuration
						interpolator = AccelerateDecelerateInterpolator()
						addUpdateListener(this@ReadingProgressView)
						addListener(this@ReadingProgressView)
						start()
					}
		}

		private fun cancelAnimation() {
			percentAnimator?.cancel()
			percentAnimator = null
		}

		private fun peekProgressDrawable(): ReadingProgressDrawable? = background as? ReadingProgressDrawable

		private fun getProgressDrawable(): ReadingProgressDrawable {
			var d = peekProgressDrawable()
			if (d != null) {
				return d
			}
			d = ReadingProgressDrawable(context, drawableStyle)
			background = d
			return d
		}

		private class OutlineProvider : ViewOutlineProvider() {
			override fun getOutline(
				view: View,
				outline: Outline,
			) {
				outline.setOval(0, 0, view.width, view.height)
			}
		}
	}

fun ReadingProgress?.getDisplayText(percentPattern: String): String =
	when (this?.mode) {
		null,
		NONE,
		-> ""

		PERCENT_READ -> percentPattern.format(ReadingProgress.percentToString(percent))

		PERCENT_LEFT -> "-" + percentPattern.format(ReadingProgress.percentToString(percentLeft))

		CHAPTERS_READ -> chapters.toString()

		CHAPTERS_LEFT -> "-" + chaptersLeft.toString()
	}
