package org.draken.usagi.list.ui.size

import android.view.View
import android.widget.TextView

interface ItemSizeResolver {
	val cellWidth: Int

	fun attachToView(
		view: View,
		textView: TextView?,
	)
}
