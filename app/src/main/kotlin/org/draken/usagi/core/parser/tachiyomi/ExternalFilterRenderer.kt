package org.draken.usagi.core.parser.tachiyomi

import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import org.draken.usagi.R
import org.draken.usagi.core.ui.widgets.ChipsView
import org.draken.usagi.core.util.ext.setThemeTextAppearance
import org.draken.usagi.filter.ui.FilterFieldLayout
import com.google.android.material.R as materialR
import androidx.core.view.isNotEmpty
import com.google.android.material.chip.Chip

class ExternalFilterRenderer(
	private val container: LinearLayout,
	private val onChanged: (FilterList) -> Unit,
) {
	private var rendered: FilterList? = null
	private var renderToken = 0
	private val context = container.context
	private val marginSmall = context.resources.getDimensionPixelOffset(R.dimen.margin_small)
	private val marginNormal = context.resources.getDimensionPixelOffset(R.dimen.margin_normal)
	private val spinnerHeight = context.resources.getDimensionPixelOffset(R.dimen.spinner_height)

	fun render(filters: FilterList) {
		if (rendered === filters && container.isNotEmpty()) return
		rendered = filters
		val token = ++renderToken
		container.removeAllViews()
		container.post { renderChunk(filters, filters, 0, token) }
	}

	fun clear() {
		rendered = null
		renderToken++
		container.removeAllViews()
	}

	private fun renderChunk(root: FilterList, filters: List<Filter<*>>, start: Int, token: Int) {
		if (token != renderToken) return
		val end = (start + RENDER_CHUNK_SIZE).coerceAtMost(filters.size)
		for (i in start until end) { addFilter(root, filters[i], 0) }
		if (end < filters.size) container.post { renderChunk(root, filters, end, token) }
	}

	private fun addFilter(root: FilterList, filter: Filter<*>, depth: Int) {
		when (filter) {
			is Filter.Header -> addHeader(filter.name, depth)
			is Filter.Separator -> addSeparator()
			is Filter.CheckBox -> addCheckBox(root, filter, depth)
			is Filter.TriState -> addTriState(root, filter, depth)
			is Filter.Select<*> -> addSelect(root, filter, depth)
			is Filter.Text -> addText(root, filter, depth)
			is Filter.Sort -> addSort(root, filter, depth)
			is Filter.Group<*> -> addGroup(root, filter, depth)
		}
	}

	private fun addHeader(title: String, depth: Int) {
		if (title.isBlank()) return
		container.addView(
			TextView(context).apply {
				text = title
				setThemeTextAppearance(
					materialR.attr.textAppearanceTitleSmall,
					materialR.style.TextAppearance_Material3_TitleSmall,
				)
				setPadding(depthMargin(depth), marginNormal, marginSmall, marginSmall)
			},
		)
	}

	private fun addSeparator() {
		container.addView(
			View(context).apply {
				alpha = 0.25f
				setBackgroundColor(ContextCompat.getColor(context, android.R.color.darker_gray))
				layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).withMargins()
			},
		)
	}

	private fun addCheckBox(root: FilterList, filter: Filter.CheckBox, depth: Int) {
		val chips = chipsView(0, ChipsView.ChipModel(filter.name, isChecked = filter.state, data = filter)) { chip, _ ->
			filter.state = !filter.state
			(chip.parent as? ChipsView)?.setChips(listOf(filter.toChip()))
			onChanged(root)
		}
		container.addView(field(filter.name, depth, chips))
	}

	private fun addTriState(root: FilterList, filter: Filter.TriState, depth: Int) {
		lateinit var chips: ChipsView
		chips = chipsView(0, *filter.triStateChips()) { _, data ->
			filter.state = data as? Int ?: Filter.TriState.STATE_IGNORE
			chips.setChips(filter.triStateChips().toList())
			onChanged(root)
		}
		container.addView(field(filter.name, depth, chips))
	}

	private fun addSelect(root: FilterList, filter: Filter.Select<*>, depth: Int) {
		if (filter.values.isEmpty()) return
		val spinner = spinner(
			filter.values.map { it.toString() },
			filter.state.coerceIn(0, filter.values.lastIndex),
		) { position ->
			if (filter.state == position) return@spinner
			filter.state = position
			onChanged(root)
		}
		container.addView(field(filter.name, depth, card(spinner)))
	}

	private fun addText(root: FilterList, filter: Filter.Text, depth: Int) {
		val editText = TextInputEditText(context).apply {
			setSingleLine(true)
			imeOptions = EditorInfo.IME_ACTION_DONE
			setText(filter.state)
			doAfterTextChanged { text ->
				filter.state = text?.toString().orEmpty()
				onChanged(root)
			}
		}
		val input = TextInputLayout(context).apply { addView(editText) }
		container.addView(field(filter.name, depth, input))
	}

	private fun addSort(root: FilterList, filter: Filter.Sort, depth: Int) {
		if (filter.values.isEmpty()) return
		val selected = filter.state ?: Filter.Sort.Selection(0, true)
		val row = LinearLayout(context).apply {
			orientation = LinearLayout.VERTICAL
			addView(
				card(
					spinner(filter.values.toList(), selected.index.coerceIn(0, filter.values.lastIndex)) { position ->
						val ascending = filter.state?.ascending ?: selected.ascending
						if (filter.state?.index == position && filter.state?.ascending == ascending) return@spinner
						filter.state = Filter.Sort.Selection(position, ascending)
						onChanged(root)
					},
				),
			)
			lateinit var sortChips: ChipsView
			sortChips = chipsView(0, *filter.sortDirectionChips(selected)) { _, data ->
				val ascending = data as? Boolean ?: true
				val index = filter.state?.index ?: selected.index
				filter.state = Filter.Sort.Selection(index, ascending)
				sortChips.setChips(filter.sortDirectionChips(filter.state ?: selected).toList())
				onChanged(root)
			}
			addView(sortChips)
		}
		container.addView(field(filter.name, depth, row))
	}

	private fun addGroup(root: FilterList, filter: Filter.Group<*>, depth: Int) {
		val children = filter.state.filterIsInstance<Filter<*>>()
		val checkboxes = children.filterIsInstance<Filter.CheckBox>()
		if (checkboxes.isNotEmpty() && checkboxes.size == children.size) {
			addCheckBoxGroup(root, filter.name, checkboxes, depth)
			return
		}
		addHeader(filter.name, depth)
		children.forEach { child -> addFilter(root, child, depth + 1) }
	}

	private fun addCheckBoxGroup(root: FilterList, title: String, filters: List<Filter.CheckBox>, depth: Int) {
		val chips = chipsView(0, *filters.map { it.toChip() }.toTypedArray()) { chip, data ->
			val filter = data as? Filter.CheckBox ?: return@chipsView
			filter.state = !filter.state
			(chip.parent as? ChipsView)?.setChips(filters.map { it.toChip() })
			onChanged(root)
		}
		container.addView(field(title, depth, chips))
	}

	private fun field(title: String, depth: Int, control: View): FilterFieldLayout {
		return FilterFieldLayout(context).apply {
			id = View.generateViewId()
			setText(title)
			setPadding(depthMargin(depth) - marginSmall, 0, 0, 0)
			layoutParams = LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT,
			).withVerticalMargins()
			control.id = View.generateViewId()
			addView(control)
		}
	}

	private fun card(child: View): MaterialCardView {
		return MaterialCardView(context, null, materialR.attr.materialCardViewOutlinedStyle).apply {
			layoutParams = LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT,
			).apply {
				marginStart = marginSmall
				marginEnd = marginSmall
				topMargin = marginSmall
			}
			addView(child)
		}
	}

	private fun spinner(values: List<String>, selectedIndex: Int, onSelected: (Int) -> Unit): Spinner {
		return Spinner(context).apply {
			adapter = ArrayAdapter(
				context,
				android.R.layout.simple_spinner_item,
				android.R.id.text1,
				values,
			).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
			layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, spinnerHeight)
			minimumHeight = spinnerHeight
			dropDownWidth = ViewGroup.LayoutParams.MATCH_PARENT
			setPopupBackgroundResource(R.drawable.m3_spinner_popup_background)
			setPadding(8, 0, 8, 0)
			setSelection(selectedIndex, false)
			onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
				override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
					onSelected(position)
				}

				override fun onNothingSelected(parent: AdapterView<*>?) = Unit
			}
		}
	}

	private fun chipsView(
		depth: Int,
		vararg chips: ChipsView.ChipModel,
		onClick: (chip: Chip, data: Any?) -> Unit,
	): ChipsView {
		return ChipsView(context = context, chipStyleOverride = R.style.Widget_Usagi_Chip_Filter).apply {
			layoutParams = LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT,
			).apply {
				marginStart = depthMargin(depth)
				marginEnd = marginSmall
				topMargin = marginSmall
				bottomMargin = marginSmall
			}
			setChips(chips.toList())
			isSingleLine = false
			onChipClickListener = ChipsView.OnChipClickListener { chip, data -> onClick(chip, data) }
		}
	}

	private fun depthMargin(depth: Int): Int = marginSmall + depth * marginNormal

	private fun Filter.CheckBox.toChip() = ChipsView.ChipModel(
		title = name,
		isChecked = state,
		data = this,
	)

	private fun Filter.TriState.triStateChips() = arrayOf(
		ChipsView.ChipModel(title = "+", isChecked = state == Filter.TriState.STATE_INCLUDE, data = Filter.TriState.STATE_INCLUDE),
		ChipsView.ChipModel(title = "OR", isChecked = state == Filter.TriState.STATE_IGNORE, data = Filter.TriState.STATE_IGNORE),
		ChipsView.ChipModel(title = "-", isChecked = state == Filter.TriState.STATE_EXCLUDE, data = Filter.TriState.STATE_EXCLUDE),
	)

	private fun Filter.Sort.sortDirectionChips(selected: Filter.Sort.Selection) = arrayOf(
		ChipsView.ChipModel(title = "\u2191", isChecked = selected.ascending, data = true),
		ChipsView.ChipModel(title = "\u2193", isChecked = !selected.ascending, data = false),
	)

	private fun LinearLayout.LayoutParams.withMargins(): LinearLayout.LayoutParams {
		setMargins(marginSmall, marginSmall, marginSmall, marginSmall)
		return this
	}

	private fun LinearLayout.LayoutParams.withVerticalMargins(): LinearLayout.LayoutParams {
		setMargins(0, marginSmall, 0, marginSmall)
		return this
	}

	private companion object {

		const val RENDER_CHUNK_SIZE = 12
	}
}
