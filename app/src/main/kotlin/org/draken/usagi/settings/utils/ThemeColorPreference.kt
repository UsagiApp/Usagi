package org.draken.usagi.settings.utils

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import androidx.annotation.ColorInt
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import org.draken.usagi.R
import org.draken.usagi.core.util.ext.toHexColor

class ThemeColorPreference
	@JvmOverloads
	constructor(
		context: Context,
		attrs: AttributeSet? = null,
	) : Preference(context, attrs) {
		@ColorInt
		var color: Int = Color.TRANSPARENT
			set(value) {
				if (field != value) {
					field = value
					summary = value.toHexColor()
					notifyChanged()
				}
			}

		init {
			widgetLayoutResource = R.layout.preference_widget_color
		}

		override fun onBindViewHolder(holder: PreferenceViewHolder) {
			super.onBindViewHolder(holder)
			holder.findViewById(R.id.colorView)?.setBackgroundColor(color)
		}
	}
