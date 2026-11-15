package org.draken.usagi.settings.utils

import android.content.Context
import android.content.res.TypedArray
import android.graphics.Color
import android.os.Build
import android.os.Parcel
import android.os.Parcelable
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.ColorInt
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePaddingRelative
import androidx.customview.view.AbsSavedState
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.draken.usagi.R
import org.draken.usagi.core.prefs.ColorScheme
import org.draken.usagi.core.prefs.PaletteStyle
import org.draken.usagi.core.ui.util.createPaletteContext
import org.draken.usagi.core.ui.util.getPaletteSeed
import org.draken.usagi.databinding.ItemColorSchemeBinding
import org.draken.usagi.databinding.PreferenceThemeBinding
import com.google.android.material.R as materialR

class ThemeChooserPreference
	@JvmOverloads
	constructor(
		context: Context,
		attrs: AttributeSet? = null,
		defStyleAttr: Int = R.attr.themeChooserPreferenceStyle,
		defStyleRes: Int = R.style.Preference_ThemeChooser,
	) : Preference(context, attrs, defStyleAttr, defStyleRes) {
		private val entries = ColorScheme.getAvailableList()
		private var currentValue: ColorScheme = ColorScheme.default
		private var scrollPosition = RecyclerView.NO_POSITION
		private var scrollOffset = 0
		private val adapter = ColorSchemeAdapter()
		private val scrollListener =
			object : RecyclerView.OnScrollListener() {
				override fun onScrolled(
					recyclerView: RecyclerView,
					dx: Int,
					dy: Int,
				) {
					val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
					scrollPosition = layoutManager.findFirstVisibleItemPosition()
					val view = layoutManager.findViewByPosition(scrollPosition) ?: return
					scrollOffset =
						if (recyclerView.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
							recyclerView.width - recyclerView.paddingRight - view.right
						} else {
							view.left - recyclerView.paddingLeft
						}
				}
			}

		var paletteStyle: PaletteStyle = PaletteStyle.SYSTEM

		@ColorInt
		var customColor: Int = Color.TRANSPARENT

		var previewConfig = ThemePreviewDrawable.Config()
			set(value) {
				if (field != value) {
					field = value
					adapter.notifyItemRangeChanged(0, entries.size)
				}
			}

		var history: List<ThemePreviewDrawable.Group> = emptyList()
			set(value) {
				field = value
				adapter.notifyItemRangeChanged(0, entries.size)
			}

		var value: String
			get() = currentValue.name
			set(value) = setValueInternal(value, notifyChanged = true)

		override fun onBindViewHolder(holder: PreferenceViewHolder) {
			super.onBindViewHolder(holder)
			val recyclerView = PreferenceThemeBinding.bind(holder.itemView).recyclerView
			if (recyclerView.adapter === adapter) {
				return
			}
			recyclerView.itemAnimator = null
			recyclerView.adapter = adapter
			recyclerView.clearOnScrollListeners()
			recyclerView.addOnScrollListener(scrollListener)
			val layoutManager = recyclerView.layoutManager as LinearLayoutManager
			if (scrollPosition != RecyclerView.NO_POSITION) {
				layoutManager.scrollToPositionWithOffset(scrollPosition, scrollOffset)
			} else {
				layoutManager.scrollToPosition(entries.indexOf(currentValue).coerceAtLeast(0))
			}
		}

		override fun onSetInitialValue(defaultValue: Any?) {
			value =
				getPersistedString(
					when (defaultValue) {
						is String -> ColorScheme.safeValueOf(defaultValue) ?: ColorScheme.default
						is ColorScheme -> defaultValue
						else -> ColorScheme.default
					}.name,
				)
		}

		override fun onGetDefaultValue(
			a: TypedArray,
			index: Int,
		): Any = a.getInt(index, 0)

		override fun onSaveInstanceState(): Parcelable? {
			val superState = super.onSaveInstanceState() ?: return null
			return SavedState(
				superState = superState,
				scrollPosition = scrollPosition,
				scrollOffset = scrollOffset,
			)
		}

		override fun onRestoreInstanceState(state: Parcelable?) {
			if (state !is SavedState) {
				super.onRestoreInstanceState(state)
				return
			}
			super.onRestoreInstanceState(state.superState)
			scrollPosition = state.scrollPosition
			scrollOffset = state.scrollOffset
		}

		private fun setValueInternal(
			enumName: String,
			notifyChanged: Boolean,
		) {
			val newValue = ColorScheme.safeValueOf(enumName) ?: return
			if (newValue != currentValue) {
				val oldValue = currentValue
				currentValue = newValue
				adapter.notifyItemChanged(entries.indexOf(oldValue))
				adapter.notifyItemChanged(entries.indexOf(newValue))
				persistString(newValue.name)
				if (notifyChanged) {
					notifyChanged()
				}
			}
		}

		private class SavedState : AbsSavedState {
			val scrollPosition: Int
			val scrollOffset: Int

			constructor(
				superState: Parcelable,
				scrollPosition: Int,
				scrollOffset: Int,
			) : super(superState) {
				this.scrollPosition = scrollPosition
				this.scrollOffset = scrollOffset
			}

			constructor(source: Parcel, classLoader: ClassLoader?) : super(source, classLoader) {
				scrollPosition = source.readInt()
				scrollOffset = source.readInt()
			}

			override fun writeToParcel(
				out: Parcel,
				flags: Int,
			) {
				super.writeToParcel(out, flags)
				out.writeInt(scrollPosition)
				out.writeInt(scrollOffset)
			}

			companion object {
				@Suppress("unused")
				@JvmField
				val CREATOR: Parcelable.Creator<SavedState> =
					object : Parcelable.Creator<SavedState> {
						override fun createFromParcel(`in`: Parcel) = SavedState(`in`, SavedState::class.java.classLoader)

						override fun newArray(size: Int): Array<SavedState?> = arrayOfNulls(size)
					}
			}
		}

		private inner class ColorSchemeAdapter : RecyclerView.Adapter<ColorSchemeHolder>() {
			private val itemClickListener =
				View.OnClickListener {
					val tag = it.tag as? ColorScheme ?: return@OnClickListener
					setValueInternal(tag.name, true)
				}

			override fun getItemCount(): Int = entries.size

			override fun getItemViewType(position: Int): Int = position

			override fun onCreateViewHolder(
				parent: ViewGroup,
				viewType: Int,
			): ColorSchemeHolder {
				val theme = entries[viewType]
				val seed = parent.context.getPaletteSeed(theme, paletteStyle, customColor)
				val themedContext =
					if (seed != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
						parent.context.createPaletteContext(theme.styleResId, seed, paletteStyle)
					} else {
						ContextThemeWrapper(parent.context, theme.styleResId)
					}
				val item = ItemColorSchemeBinding.inflate(LayoutInflater.from(themedContext), parent, false)
				if (viewType == 0) {
					item.root.updatePaddingRelative(start = 0)
				}
				item.textViewTitle.setText(theme.titleResId)
				item.root.tag = theme
				item.card.tag = theme
				item.root.setOnClickListener(itemClickListener)
				item.card.setOnClickListener(itemClickListener)
				return ColorSchemeHolder(item, ThemePreviewDrawable(themedContext))
			}

			override fun onBindViewHolder(
				holder: ColorSchemeHolder,
				position: Int,
			) {
				val item = holder.binding
				holder.preview.setConfig(previewConfig)
				holder.preview.setHistory(history)
				val card = item.card
				val height = (card.layoutParams.width * previewConfig.height / previewConfig.width).toInt()
				if (card.layoutParams.height != height) {
					card.updateLayoutParams { this.height = height }
				}
				val isSelected = entries[position] == currentValue
				item.card.isChecked = isSelected
				item.card.strokeWidth =
					if (isSelected) {
						item.root.resources.getDimensionPixelSize(
							materialR.dimen.m3_comp_outlined_card_outline_width,
						)
					} else {
						0
					}
				item.imageViewCheck.isVisible = isSelected
			}
		}

		private class ColorSchemeHolder(
			val binding: ItemColorSchemeBinding,
			val preview: ThemePreviewDrawable,
		) : RecyclerView.ViewHolder(binding.root) {
			init {
				binding.preview.background = preview
			}
		}
	}
