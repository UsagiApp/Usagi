package org.draken.usagi.filter.ui.external

import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.draken.usagi.core.ui.BaseViewModel
import org.draken.usagi.filter.ui.FilterCoordinator
import org.draken.usagi.filter.ui.external.model.FilterItem

@HiltViewModel(assistedFactory = FilterViewModel.Factory::class)
class FilterViewModel @AssistedInject constructor(
	@Assisted private val filter: FilterCoordinator,
	@Assisted private val isEmbed: Boolean,
) : BaseViewModel(), FilterListener {

	private var working: FilterList = FilterList()
	private var defaults: FilterList = FilterList()
	private var sortPath: String? = null
	private val pathMap = HashMap<String, Filter<*>>()
	private val expandedPaths = HashSet<String>()

	private val itemsFlow = MutableStateFlow<List<FilterItem>>(emptyList())
	private val loadedFlow = MutableStateFlow(false)

	val items: StateFlow<List<FilterItem>> = itemsFlow

	val isEmptyState: StateFlow<Boolean> = combine(loadedFlow, itemsFlow) { loaded, list ->
		loaded && list.isEmpty()
	}.stateIn(viewModelScope, SharingStarted.Eagerly, false)

	init {
		load(resetExpanded = true)
	}

	private fun load(resetExpanded: Boolean) = launchLoadingJob(Dispatchers.Default) {
		val wf = filter.loadWorkingFilters()
		working = wf.working
		defaults = wf.defaults
		sortPath = FilterMapper.findSortFilter(working)?.path
		if (resetExpanded) {
			expandedPaths.clear()
			collectInit(working.toList(), prefix = "")
		}
		rebuild()
		loadedFlow.value = true
	}

	fun reset() {
		filter.reset()
		load(resetExpanded = true)
	}

	override fun onCheckBoxClick(item: FilterItem.CheckBox) {
		val f = pathMap[item.path] as? Filter.CheckBox ?: return
		f.state = !f.state
		apply()
	}

	override fun onCheckBoxChipClick(checkBoxPath: String) {
		val f = pathMap[checkBoxPath] as? Filter.CheckBox ?: return
		f.state = !f.state
		apply()
	}

	override fun onTriStateClick(item: FilterItem.TriState) {
		val f = pathMap[item.path] as? Filter.TriState ?: return
		f.state = when (f.state) {
			Filter.TriState.STATE_IGNORE -> Filter.TriState.STATE_INCLUDE
			Filter.TriState.STATE_INCLUDE -> Filter.TriState.STATE_EXCLUDE
			else -> Filter.TriState.STATE_IGNORE
		}
		apply()
	}

	override fun onTextChanged(item: FilterItem.Text, value: String) {
		val f = pathMap[item.path] as? Filter.Text ?: return
		if (f.state == value) return
		f.state = value
		apply()
	}

	override fun onSelectChanged(item: FilterItem.Select, index: Int) {
		val f = pathMap[item.path] as? Filter.Select<*> ?: return
		if (index !in f.values.indices || f.state == index) return
		f.state = index
		apply()
	}

	override fun onExpandClick(item: FilterItem.ExpandableHeader) {
		if (!expandedPaths.add(item.path)) expandedPaths.remove(item.path)
		rebuild()
	}

	override fun onSortOptionClick(item: FilterItem.SortOption) {
		val f = pathMap[item.path] as? Filter.Sort ?: return
		val current = f.state
		val ascending = if (current?.index == item.optionIndex) { !current.ascending } else {
			current?.ascending ?: true
		}
		f.state = Filter.Sort.Selection(item.optionIndex, ascending)
		apply()
	}

	private fun apply() {
		filter.applyDynamicFilters(working, defaults)
		rebuild()
	}

	private fun rebuild() {
		pathMap.clear()
		val result = ArrayList<FilterItem>()
		build(working.toList(), prefix = "", depth = 0, out = result)
		result.removeSeparators()
		itemsFlow.value = result
	}

	private fun build(filters: List<Filter<*>>, prefix: String, depth: Int, out: MutableList<FilterItem>) {
		filters.forEachIndexed { i, f ->
			val path = if (prefix.isEmpty()) i.toString() else "$prefix.$i"
			if (isHidden(f, path)) return@forEachIndexed
			pathMap[path] = f
			when (f) {
				is Filter.Header -> if (f.name.isNotEmpty()) out += FilterItem.Header(path, depth, f.name)
				is Filter.Separator -> out += FilterItem.Separator(path, depth)
				is Filter.CheckBox -> out += FilterItem.CheckBox(path, depth, f.name, f.state)
				is Filter.TriState -> out += FilterItem.TriState(path, depth, f.name, f.state)
				is Filter.Text -> out += FilterItem.Text(path, depth, f.name, f.state)
				is Filter.Select<*> -> out += FilterItem.Select(
					path = path,
					depth = depth,
					title = f.name,
					options = f.values.map { it.toString() },
					selectedIndex = f.state,
				)

				is Filter.Sort -> {
					val selection = f.state
					val summary = selection?.let { "${f.values.getOrNull(it.index)} ${if (it.ascending) "↑" else "↓"}" }
					val expanded = path in expandedPaths
					out += FilterItem.ExpandableHeader(path, depth, f.name, expanded, summary)
					if (expanded) {
						f.values.forEachIndexed { optionIndex, label ->
							val isAscending = if (selection?.index == optionIndex) selection.ascending else null
							out += FilterItem.SortOption(path, depth + 1, optionIndex, label, isAscending)
						}
					}
				}

				is Filter.Group<*> -> {
					val children = f.state.filterIsInstance<Filter<*>>()
					if (!hasControls(children, path)) return@forEachIndexed
					val active = activeCount(children, path)
					val expanded = path in expandedPaths
					out += FilterItem.ExpandableHeader(
						path = path,
						depth = depth,
						title = f.name,
						isExpanded = expanded,
						activeSummary = if (active > 0) active.toString() else null,
					)
					if (expanded) {
						// A small group of plain on/off checkboxes renders as a wrapping chip row instead of
						// one row per option. Large taxonomies (e.g. thousands of publishers) stay a scrollable
						// list — ChipGroup doesn't recycle, so that many chips would freeze the UI.
						if (children.isNotEmpty() &&
							children.size <= MAX_CHIP_GROUP_SIZE &&
							children.all { it is Filter.CheckBox }
						) {
							val chips = children.mapIndexed { childIndex, child ->
								val childPath = "$path.$childIndex"
								pathMap[childPath] = child
								val checkBox = child as Filter.CheckBox
								FilterItem.CheckBoxChips.Chip(childPath, checkBox.name, checkBox.state)
							}
							out += FilterItem.CheckBoxChips(path, depth + 1, chips)
						} else { build(children, path, depth + 1, out) }
					}
				}
			}
		}
	}

	private fun collectInit(filters: List<Filter<*>>, prefix: String) {
		filters.forEachIndexed { i, f ->
			val path = if (prefix.isEmpty()) i.toString() else "$prefix.$i"
			if (isHidden(f, path)) return@forEachIndexed
			when (f) {
				is Filter.Sort -> if (f.state != null) expandedPaths.add(path)
				is Filter.Group<*> -> {
					val children = f.state.filterIsInstance<Filter<*>>()
					if (activeCount(children, path) > 0) expandedPaths.add(path)
					collectInit(children, path)
				}
				else -> Unit
			}
		}
	}

	private fun activeCount(filters: List<Filter<*>>, prefix: String): Int = filters.withIndex().count { (index, f) ->
		val path = "$prefix.$index"
		if (isHidden(f, path)) { false } else {
			when (f) {
				is Filter.CheckBox -> f.state
				is Filter.TriState -> f.state != Filter.TriState.STATE_IGNORE
				is Filter.Text -> f.state.isNotEmpty()
				is Filter.Select<*> -> f.state != 0
				else -> false
			}
		}
	}

	private fun hasControls(filters: List<Filter<*>>, prefix: String): Boolean {
		filters.forEachIndexed { index, f ->
			val path = "$prefix.$index"
			if (isHidden(f, path)) return@forEachIndexed
			when (f) {
				is Filter.Header, is Filter.Separator -> Unit
				is Filter.Group<*> -> {
					if (hasControls(f.state.filterIsInstance<Filter<*>>(), path)) {
						return true
					}
				}
				else -> return true
			}
		}
		return false
	}

	private fun isHidden(filter: Filter<*>, path: String): Boolean =
		if (!isEmbed) { filter is Filter.Sort || path == sortPath } else false

	private fun MutableList<FilterItem>.removeSeparators() {
		while (firstOrNull() is FilterItem.Separator) {
			removeAt(0)
		}
	}

	@AssistedFactory
	interface Factory {
		fun create(filter: FilterCoordinator, isEmbed: Boolean): FilterViewModel
	}

	private companion object {
		// Above this many checkbox options a group stays a (recycling) list rather than a chip row.
		const val MAX_CHIP_GROUP_SIZE = 64
	}
}
