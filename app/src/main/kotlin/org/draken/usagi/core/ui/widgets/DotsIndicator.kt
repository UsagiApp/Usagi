package org.draken.usagi.core.ui.widgets

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.withStyledAttributes
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.AdapterDataObserver
import org.draken.usagi.R
import org.draken.usagi.core.util.ext.getThemeColorStateList
import org.draken.usagi.core.util.ext.measureDimension
import org.draken.usagi.core.util.ext.resolveDp
import tsuki.util.toIntUp
import com.google.android.material.R as materialR
import androidx.core.view.isEmpty

class DotsIndicator @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
	defStyleAttr: Int = R.attr.dotIndicatorStyle,
) : View(context, attrs, defStyleAttr) {

	private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
	private var indicatorSize = context.resources.resolveDp(12f)
	private var dotSpacing = 0f
	private var smallDotScale = 0.33f
	private var smallDotAlpha = 0.6f
	private var positionOffset: Float = 0f
	private var position: Int = 0
	private var dotsColor: ColorStateList = ColorStateList.valueOf(Color.DKGRAY)
	private val inset = context.resources.resolveDp(1f)

	var max: Int = 6
		set(value) {
			if (field != value) {
				field = value
				requestLayout()
				invalidate()
			}
		}
	var progress: Int
		get() = position
		set(value) {
			if (position != value) {
				position = value
				invalidate()
			}
		}

	init {
		paint.style = Paint.Style.FILL
		context.withStyledAttributes(attrs, R.styleable.DotsIndicator, defStyleAttr) {
			dotsColor = getColorStateList(R.styleable.DotsIndicator_dotColor)
				?: context.getThemeColorStateList(materialR.attr.colorOnBackground)
					?: dotsColor
			paint.color = dotsColor.getColorForState(drawableState, dotsColor.defaultColor)
			indicatorSize = getDimension(R.styleable.DotsIndicator_dotSize, indicatorSize)
			dotSpacing = getDimension(R.styleable.DotsIndicator_dotSpacing, dotSpacing)
			smallDotScale = getFloat(R.styleable.DotsIndicator_dotScale, smallDotScale).coerceIn(0f, 1f)
			smallDotAlpha = getFloat(R.styleable.DotsIndicator_dotAlpha, smallDotAlpha).coerceIn(0f, 1f)
			max = getInt(R.styleable.DotsIndicator_android_max, max)
			position = getInt(R.styleable.DotsIndicator_android_progress, position)
		}
	}

	override fun onDraw(canvas: Canvas) {
		super.onDraw(canvas)
		val dotSize = getDotSize()
		val y = paddingTop + (height - paddingTop - paddingBottom) / 2f
		var x = paddingLeft + dotSize / 2f
		val radius = dotSize / 2f - inset
		val spacing = (width - paddingLeft - paddingRight) / max.toFloat() - dotSize
		x += spacing / 2f
		for (i in 0 until max) {
			val scale = when (i) {
				position -> (1f - smallDotScale) * (1f - positionOffset) + smallDotScale
				position + 1 -> (1f - smallDotScale) * positionOffset + smallDotScale
				else -> smallDotScale
			}
			paint.alpha = (255 * when (i) {
				position -> (1f - smallDotAlpha) * (1f - positionOffset) + smallDotAlpha
				position + 1 -> (1f - smallDotAlpha) * positionOffset + smallDotAlpha
				else -> smallDotAlpha
			}).toInt()
			canvas.drawCircle(x, y, radius * scale, paint)
			x += spacing + dotSize
		}
	}

	override fun drawableStateChanged() {
		if (dotsColor.isStateful) {
			paint.color = dotsColor.getColorForState(drawableState, dotsColor.defaultColor)
		}
		super.drawableStateChanged()
	}

	override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
		val dotSize = getDotSize()
		val desiredHeight = (dotSize + paddingTop + paddingBottom).toIntUp()
		val desiredWidth = ((dotSize + dotSpacing) * max).toIntUp() + paddingLeft + paddingRight
		setMeasuredDimension(
			measureDimension(desiredWidth, widthMeasureSpec),
			measureDimension(desiredHeight, heightMeasureSpec),
		)
	}

	fun bindToRecyclerView(recyclerView: RecyclerView) {
		val adapter = recyclerView.adapter ?: return
		adapter.registerAdapterDataObserver(AdapterObserver(adapter).also { max = adapter.itemCount })
		recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
			override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) = updateScrollPosition(rv)
		})
	}

	private fun updateScrollPosition(rv: RecyclerView) {
		if (rv.isEmpty()) return
		val cx = rv.width / 2f
		fun View.mid() = left + width / 2f
		val child = (0 until rv.childCount).map { rv.getChildAt(it) }
		val (l, r) = child.partition { it.mid() <= cx }
		val lv = l.maxByOrNull { it.mid() }
		val rV = r.minByOrNull { it.mid() }
		val lp = lv?.let { rv.getChildAdapterPosition(it) } ?: -1
		val rp = rV?.let { rv.getChildAdapterPosition(it) } ?: -1
		position = if (lp >= 0) lp else rp
		positionOffset = if (lp >= 0 && rp >= 0 && rV!!.mid() > lv!!.mid())
			(cx - lv.mid()) / (rV.mid() - lv.mid()) else 0f
		if (position >= 0) invalidate()
	}

	private fun getDotSize() = if (indicatorSize <= 0) {
		(height - paddingTop - paddingBottom).toFloat()
	} else {
		indicatorSize
	}

	private inner class AdapterObserver(
		private val adapter: RecyclerView.Adapter<*>,
	) : AdapterDataObserver() {

		override fun onChanged() {
			super.onChanged()
			max = adapter.itemCount
		}

		override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
			super.onItemRangeInserted(positionStart, itemCount)
			max = adapter.itemCount
		}

		override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
			super.onItemRangeRemoved(positionStart, itemCount)
			max = adapter.itemCount
		}
	}
}
