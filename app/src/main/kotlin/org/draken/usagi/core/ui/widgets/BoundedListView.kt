package org.draken.usagi.core.ui.widgets

import android.content.Context
import android.util.AttributeSet
import android.widget.ListView
import androidx.core.content.withStyledAttributes
import org.draken.usagi.R

class BoundedListView
	@JvmOverloads
	constructor(
		context: Context,
		attrs: AttributeSet? = null,
	) : ListView(context, attrs) {
		private var maxHeight = 0

		init {
			context.withStyledAttributes(attrs, R.styleable.BoundedListView) {
				maxHeight = getDimensionPixelSize(R.styleable.BoundedListView_maxHeight, maxHeight)
			}
		}

		override fun onMeasure(
			widthMeasureSpec: Int,
			heightMeasureSpec: Int,
		) {
			val boundedHeightMeasureSpec =
				if (maxHeight == 0 || MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
					heightMeasureSpec
				} else {
					val parentLimit =
						if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.AT_MOST) {
							MeasureSpec.getSize(heightMeasureSpec)
						} else {
							Int.MAX_VALUE
						}
					MeasureSpec.makeMeasureSpec(minOf(maxHeight, parentLimit), MeasureSpec.AT_MOST)
				}
			super.onMeasure(widthMeasureSpec, boundedHeightMeasureSpec)
		}
	}
