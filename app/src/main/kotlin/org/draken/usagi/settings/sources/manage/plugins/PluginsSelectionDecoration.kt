package org.draken.usagi.settings.sources.manage.plugins

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import androidx.appcompat.R
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import org.draken.usagi.core.ui.list.decor.AbstractSelectionItemDecoration
import org.draken.usagi.core.util.ext.getItem
import org.draken.usagi.core.util.ext.getThemeColor
import org.draken.usagi.settings.sources.manage.plugins.model.PluginManageItem

class PluginsSelectionDecoration(context: Context) : AbstractSelectionItemDecoration() {
	private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
	private val strokeColor = context.getThemeColor(R.attr.colorPrimary, Color.RED)
	private val fillColor = ColorUtils.blendARGB(
		strokeColor,
		context.getThemeColor(com.google.android.material.R.attr.colorSurface),
		0.8f
	).let {
		ColorUtils.setAlphaComponent(it, 0x74)
	}
	private val defaultRadius = context.resources.getDimension(org.draken.usagi.R.dimen.list_selector_corner)

	init {
		hasBackground = false
		hasForeground = true
		isIncludeDecorAndMargins = false
		paint.strokeWidth = context.resources.getDimension(org.draken.usagi.R.dimen.selection_stroke_width)
	}

	override fun getItemId(parent: RecyclerView, child: View): Long {
		val holder = parent.getChildViewHolder(child) ?: return RecyclerView.NO_ID
		val item = holder.getItem(PluginManageItem.Plugin::class.java) ?: return RecyclerView.NO_ID
		return item.name.hashCode().toLong()
	}

	override fun onDrawForeground(
        canvas: Canvas,
        parent: RecyclerView,
        child: View,
        bounds: RectF,
        state: RecyclerView.State,
	) {
		paint.color = fillColor
		paint.style = Paint.Style.FILL
		canvas.drawRoundRect(bounds, defaultRadius, defaultRadius, paint)
		paint.color = strokeColor
		paint.style = Paint.Style.STROKE
		canvas.drawRoundRect(bounds, defaultRadius, defaultRadius, paint)
	}
}
