package org.draken.usagi.list.ui.size

import android.view.View
import android.widget.TextView
import androidx.core.view.updateLayoutParams
import androidx.core.widget.TextViewCompat
import org.draken.usagi.R

class StaticItemSizeResolver(
	override val cellWidth: Int,
) : ItemSizeResolver {
	private var widthThreshold: Int = -1
	private var textAppearanceResId = R.style.TextAppearance_Usagi_GridTitle

	override fun attachToView(
		view: View,
		textView: TextView?,
	) {
		if (widthThreshold == -1) {
			widthThreshold = view.resources.getDimensionPixelSize(R.dimen.small_grid_width)
			textAppearanceResId =
				if (cellWidth < widthThreshold) {
					R.style.TextAppearance_Usagi_GridTitle_Small
				} else {
					R.style.TextAppearance_Usagi_GridTitle
				}
		}
		if (textView != null) {
			TextViewCompat.setTextAppearance(textView, textAppearanceResId)
		}
		view.updateLayoutParams {
			width = cellWidth
		}
	}
}
