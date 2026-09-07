package org.draken.usagi.favourites.ui.selection

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatCheckedTextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.draken.usagi.R
import org.draken.usagi.core.ui.dialog.SearchableSelectionItem
import org.draken.usagi.core.ui.dialog.SearchableSelectionState
import org.draken.usagi.databinding.DialogFavouriteSearchableSelectionBinding
import kotlin.math.roundToInt

object FavouriteSearchableSelectionDialog {
	private const val FOCUS_ANIMATION_DURATION = 150L
	private const val SELECTION_FILL_ANIMATION_DURATION = 180L
	private const val SELECTED_FILL_ALPHA = 0.3f
	private const val UNFOCUSED_STROKE_DP = 1f
	private const val FOCUSED_STROKE_DP = 2f

	fun <T> show(
		context: Context,
		@StringRes titleResId: Int,
		items: List<SearchableSelectionItem<T>>,
		selected: Set<T>,
		onApply: (Set<T>) -> Unit,
	) {
		val binding = DialogFavouriteSearchableSelectionBinding.inflate(LayoutInflater.from(context))
		val state = SearchableSelectionState(items, selected)
		val adapter = SelectionAdapter(context, state)
		val density = context.resources.displayMetrics.density
		val outlineColor = MaterialColors.getColor(binding.searchContainer, com.google.android.material.R.attr.colorOutlineVariant)
		val primaryColor = MaterialColors.getColor(binding.searchContainer, androidx.appcompat.R.attr.colorPrimary)
		val colorEvaluator = ArgbEvaluator()
		var currentStrokeColor = outlineColor
		var focusAnimator: ValueAnimator? = null

		fun render(query: String) {
			adapter.submitItems(state.filtered(query))
		}

		binding.listView.adapter = adapter
		binding.listView.setOnItemClickListener { _, view, position, _ ->
			val item = adapter.getItem(position)
			val isSelected = !state.isSelected(item.id)
			state.setSelected(item.id, isSelected)
			(view as? AppCompatCheckedTextView)?.renderSelection(isSelected, animate = true)
		}
		binding.editSearch.doAfterTextChanged { text -> render(text?.toString().orEmpty()) }
		binding.editSearch.setOnFocusChangeListener { _, hasFocus ->
			val startColor = currentStrokeColor
			val targetColor = if (hasFocus) primaryColor else outlineColor
			val startWidth = binding.searchContainer.strokeWidth
			val targetWidth =
				((if (hasFocus) FOCUSED_STROKE_DP else UNFOCUSED_STROKE_DP) * density).roundToInt()
			focusAnimator?.cancel()
			focusAnimator =
				ValueAnimator.ofFloat(0f, 1f).apply {
					duration = FOCUS_ANIMATION_DURATION
					addUpdateListener { animator ->
						val fraction = animator.animatedFraction
						currentStrokeColor = colorEvaluator.evaluate(fraction, startColor, targetColor) as Int
						binding.searchContainer.setStrokeColor(ColorStateList.valueOf(currentStrokeColor))
						binding.searchContainer.strokeWidth =
							(startWidth + (targetWidth - startWidth) * fraction).roundToInt()
					}
					start()
				}
		}
		render("")

		val dialog =
			MaterialAlertDialogBuilder(context)
				.setTitle(titleResId)
				.setView(binding.root)
				.setNeutralButton(R.string.clear, null)
				.setNegativeButton(android.R.string.cancel, null)
				.setPositiveButton(R.string.apply, null)
				.create()
		dialog.setOnShowListener {
			dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
				state.clear()
				render(
					binding.editSearch.text
						?.toString()
						.orEmpty(),
				)
			}
			dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
				onApply(state.selection())
				dialog.dismiss()
			}
		}
		dialog.show()
	}

	private class SelectionAdapter<T>(
		private val context: Context,
		private val state: SearchableSelectionState<T>,
	) : BaseAdapter() {
		private var items = emptyList<SearchableSelectionItem<T>>()

		fun submitItems(items: List<SearchableSelectionItem<T>>) {
			this.items = items
			notifyDataSetChanged()
		}

		override fun getCount(): Int = items.size

		override fun getItem(position: Int): SearchableSelectionItem<T> = items[position]

		override fun getItemId(position: Int): Long = position.toLong()

		override fun getView(
			position: Int,
			convertView: View?,
			parent: ViewGroup,
		): View {
			val view =
				convertView as? AppCompatCheckedTextView
					?: LayoutInflater.from(context).inflate(R.layout.item_favourite_searchable_selection, parent, false) as AppCompatCheckedTextView
			val item = getItem(position)
			view.text = item.title
			view.renderSelection(state.isSelected(item.id), animate = false)
			return view
		}
	}

	private fun AppCompatCheckedTextView.renderSelection(
		isSelected: Boolean,
		animate: Boolean,
	) {
		val wasSelected = isChecked
		val runningAnimator = tag as? ValueAnimator
		isChecked = isSelected

		val fill = (background as? RippleDrawable)?.getDrawable(0) as? GradientDrawable ?: return
		val selectedColor =
			ColorUtils.setAlphaComponent(
				ContextCompat.getColor(context, R.color.usagi_primary),
				(SELECTED_FILL_ALPHA * 255).roundToInt(),
			)
		val startColor =
			(runningAnimator?.animatedValue as? Int)
				?: if (wasSelected) selectedColor else Color.TRANSPARENT
		val endColor = if (isSelected) selectedColor else Color.TRANSPARENT

		runningAnimator?.cancel()
		if (!animate || startColor == endColor) {
			fill.setColor(endColor)
			tag = null
			return
		}

		tag =
			ValueAnimator.ofObject(ArgbEvaluator(), startColor, endColor).apply {
				duration = SELECTION_FILL_ANIMATION_DURATION
				addUpdateListener { animator -> fill.setColor(animator.animatedValue as Int) }
				start()
			}
	}
}
