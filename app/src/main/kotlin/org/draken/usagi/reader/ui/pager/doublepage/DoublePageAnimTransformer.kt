package org.draken.usagi.reader.ui.pager.doublepage

import androidx.core.view.children
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.flow.StateFlow
import org.draken.usagi.core.prefs.ReaderAnimation
import org.draken.usagi.core.util.ext.resetTransformations

class DoublePageAnimTransformer(
	private val animationFlow: StateFlow<ReaderAnimation>,
) : RecyclerView.OnScrollListener() {
	override fun onScrolled(
		rv: RecyclerView,
		dx: Int,
		dy: Int,
	) {
		if (animationFlow.value != ReaderAnimation.ADVANCED) {
			rv.children.forEach { it.resetTransformations() }
			return
		}
		val lm = rv.layoutManager as? LinearLayoutManager ?: return
		val w = rv.width.toFloat()
		val firstPos = lm.findFirstVisibleItemPosition()
		val start = firstPos and 1.inv()
		val left = lm.findViewByPosition(start) ?: return
		val offset = -left.left
		if (w <= 0f || offset == 0) {
			rv.children.forEach { it.resetTransformations() }
			return
		}
		val fraction = (offset / w).coerceIn(0f, 1f)
		val half = ((w.toInt() + 1) / 2).toFloat()
		rv.children.forEach { v ->
			when (rv.getChildAdapterPosition(v)) {
				start -> {
					v.translationX = offset.toFloat()
					v.translationZ = 0f
					v.rotationY = 0f
					v.alpha = 1f
				}

				start + 1 -> {
					v.translationX = half - v.left
					v.pivotX = 0f
					v.pivotY = v.height / 2f
					v.cameraDistance = 20000f
					v.translationZ = 3f
					v.rotationY = -180f * fraction
					v.alpha = if (fraction < 0.5f) 1f else 0f
				}

				start + 2 -> {
					v.translationX = -v.left.toFloat()
					v.pivotX = v.width.toFloat()
					v.pivotY = v.height / 2f
					v.cameraDistance = 20000f
					v.translationZ = 3f
					v.rotationY = 180f * (1f - fraction)
					v.alpha = if (fraction >= 0.5f) 1f else 0f
				}

				start + 3 -> {
					v.translationX = half - v.left
					v.translationZ = 0f
					v.rotationY = 0f
					v.alpha = 1f
				}

				else -> {
					v.resetTransformations()
				}
			}
		}
	}
}
