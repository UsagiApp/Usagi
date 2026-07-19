package org.draken.usagi.filter.ui.external

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import tsuki.model.MangaListFilter
import tsuki.model.MangaSource
import tsuki.model.MangaTag

/**
 * Round-trips a Mihon [FilterList] through Usagi's [MangaListFilter].
 *
 * Kotatsu's filter model is fixed (sort/genres/state/…) while Mihon sources expose an arbitrary,
 * per-source list of [Filter]s. The only free-form field on [MangaListFilter] is [MangaListFilter.tags],
 * so the full dynamic state is **encoded into a set of [MangaTag]s** and decoded back when a search runs.
 *
 * Each filter is identified by its **index path** within the filter tree (e.g. `2`, `5.1`), which is
 * stable for a given source/version and avoids name collisions or escaping problems. The key format is:
 *
 * ```
 * <type>@<path>=<value>
 * ```
 *
 * where `type` is one of [TYPE_CHECKBOX], [TYPE_TRISTATE], [TYPE_SELECT], [TYPE_SORT], [TYPE_TEXT].
 * Only filters that differ from their default state are encoded, so [MangaListFilter.isEmpty] and
 * [MangaListFilter.hasNonSearchOptions] stay accurate (driving the "filter applied" badge & reset state).
 *
 * The source's sort filter — a [Filter.Sort] **or** a [Filter.Select] named like a sort — is always
 * encoded with the [TYPE_SORT] prefix so the toolbar can surface it separately from real filters.
 */
object FilterMapper {

	private const val TYPE_CHECKBOX = "cb"
	private const val TYPE_TRISTATE = "tri"
	private const val TYPE_SELECT = "sel"
	private const val TYPE_SORT = "srt"
	private const val TYPE_TEXT = "txt"

	/** Key prefix of an encoded sort selection, used to surface the active sort on the toolbar button. */
	const val SORT_KEY_PREFIX = "$TYPE_SORT@"

	private const val ARROW_ASC = "↑"
	private const val ARROW_DESC = "↓"

	/**
	 * Encodes the (mutated) [working] filter list into a set of [MangaTag]s, comparing against
	 * [defaults] so only changed filters are emitted.
	 */
	fun encode(working: FilterList, defaults: FilterList, source: MangaSource): Set<MangaTag> {
		val tags = LinkedHashSet<MangaTag>()
		val sortPath = findSortFilter(defaults)?.path
		encodeInto(working.toList(), defaults.toList(), prefix = "", sortPath = sortPath, source = source, out = tags)
		return tags
	}

	private fun encodeInto(
		working: List<Filter<*>>, defaults: List<Filter<*>>, prefix: String,
		sortPath: String?, source: MangaSource, out: MutableSet<MangaTag>,
	) {
		working.forEachIndexed { index, filter ->
			val path = if (prefix.isEmpty()) index.toString() else "$prefix.$index"
			val default = defaults.getOrNull(index)
			when (filter) {
				is Filter.Header, is Filter.Separator -> Unit

				is Filter.CheckBox -> {
					val def = (default as? Filter.CheckBox)?.state ?: false
					if (filter.state != def) {
						out += tag(
							source = source,
							key = "$TYPE_CHECKBOX@$path=${if (filter.state) "1" else "0"}",
							title = if (filter.state) filter.name else "${filter.name} (off)",
						)
					}
				}

				is Filter.TriState -> {
					val def = (default as? Filter.TriState)?.state ?: Filter.TriState.STATE_IGNORE
					if (filter.state != def) {
						out += tag(
							source = source,
							key = "$TYPE_TRISTATE@$path=${filter.state}",
							title = when (filter.state) {
								Filter.TriState.STATE_EXCLUDE -> "−${filter.name}"
								Filter.TriState.STATE_IGNORE -> "${filter.name} (ignored)"
								else -> filter.name
							},
						)
					}
				}

				is Filter.Select<*> -> {
					val def = (default as? Filter.Select<*>)?.state ?: 0
					if (filter.state != def) {
						// The sort Select is tagged with the sort prefix so the toolbar can find it.
						val type = if (path == sortPath) TYPE_SORT else TYPE_SELECT
						out += tag(
							source = source,
							key = "$type@$path=${filter.state}",
							title = "${filter.name}: ${filter.values.getOrNull(filter.state)}",
						)
					}
				}

				is Filter.Sort -> {
					val selection = filter.state
					val def = (default as? Filter.Sort)?.state
					if (selection != def) {
						val encodedSelection = selection?.let {
							"${it.index}:${if (it.ascending) "a" else "d"}"
						} ?: "none"
						val title = selection?.let {
							val arrow = if (it.ascending) ARROW_ASC else ARROW_DESC
							"${filter.name}: ${filter.values.getOrNull(it.index)} $arrow"
						} ?: "${filter.name} (none)"
						out += tag(
							source = source,
							key = "$TYPE_SORT@$path=$encodedSelection",
							title = title,
						)
					}
				}

				is Filter.Text -> {
					val def = (default as? Filter.Text)?.state.orEmpty()
					if (filter.state != def) {
						out += tag(
							source = source,
							key = "$TYPE_TEXT@$path=${filter.state}",
							title = if (filter.state.isEmpty()) "${filter.name} (empty)" else "${filter.name}: ${filter.state}",
						)
					}
				}

				is Filter.Group<*> -> {
					val workingChildren = filter.state.filterIsInstance<Filter<*>>()
					val defaultChildren = (default as? Filter.Group<*>)?.state?.filterIsInstance<Filter<*>>().orEmpty()
					encodeInto(workingChildren, defaultChildren, path, sortPath, source, out)
				}
			}
		}
	}

	/**
	 * Applies the encoded state from [filter] back onto [target] (a freshly built default filter list).
	 */
	fun decode(target: FilterList, filter: MangaListFilter) {
		if (filter.tags.isEmpty()) return
		val byPath = HashMap<String, Encoded>(filter.tags.size)
		for (tag in filter.tags) { parseKey(tag.key)?.let { byPath[it.path] = it } }
		if (byPath.isEmpty()) return
		decodeInto(target.toList(), prefix = "", byPath = byPath)
	}

	private fun decodeInto(filters: List<Filter<*>>, prefix: String, byPath: Map<String, Encoded>) {
		filters.forEachIndexed { index, filter ->
			val path = if (prefix.isEmpty()) index.toString() else "$prefix.$index"
			if (filter is Filter.Group<*>) {
				decodeInto(filter.state.filterIsInstance<Filter<*>>(), path, byPath)
				return@forEachIndexed
			}
			val encoded = byPath[path] ?: return@forEachIndexed
			applyEncoded(filter, encoded)
		}
	}

	private fun applyEncoded(filter: Filter<*>, encoded: Encoded) {
		when (filter) {
			is Filter.CheckBox -> if (encoded.type == TYPE_CHECKBOX) {
				filter.state = encoded.value == "1"
			}

			is Filter.TriState -> if (encoded.type == TYPE_TRISTATE) {
				filter.state = encoded.value.toIntOrNull() ?: Filter.TriState.STATE_IGNORE
			}

			is Filter.Select<*> -> if (encoded.type == TYPE_SELECT || encoded.type == TYPE_SORT) {
				// A sort Select is stored as "sort", a regular one as "select"; both decode to an index.
				val idx = encoded.value.substringBefore(':').toIntOrNull() ?: return
				if (idx in filter.values.indices) filter.state = idx
			}

			is Filter.Sort -> if (encoded.type == TYPE_SORT) {
				if (encoded.value == "none") {
					filter.state = null
					return
				}
				val parts = encoded.value.split(':')
				val idx = parts.getOrNull(0)?.toIntOrNull() ?: return
				val ascending = parts.getOrNull(1) == "a"
				if (idx in filter.values.indices) filter.state = Filter.Sort.Selection(idx, ascending)
			}

			is Filter.Text -> if (encoded.type == TYPE_TEXT) {
				// Text filters (author/title/year/domain inputs) must survive the UI round-trip.
				// Dropping them makes the visible filter look applied while the source receives "".
				filter.state = encoded.value
			}

			else -> Unit
		}
	}

	/**
	 * Finds the source's sort filter together with its index path, used by the compact sort picker.
	 * Prefers a real [Filter.Sort]; otherwise falls back to a [Filter.Select] whose name looks like a
	 * sort ("Sort", "Order by", …). Returns null when there is none (callers then fall back to the
	 * built-in [tsuki.model.SortOrder]s).
	 */
	fun findSortFilter(filters: FilterList): SortRef? {
		val list = filters.toList()
		return findSort(list, prefix = "") ?: findSortSelect(list, prefix = "")
	}

	private fun findSort(filters: List<Filter<*>>, prefix: String): SortRef? {
		filters.forEachIndexed { index, filter ->
			val path = if (prefix.isEmpty()) index.toString() else "$prefix.$index"
			when (filter) {
				is Filter.Sort -> return SortRef.OfSort(path, filter)
				is Filter.Group<*> -> findSort(filter.state.filterIsInstance<Filter<*>>(), path)?.let { return it }
				else -> Unit
			}
		}
		return null
	}

	private fun findSortSelect(filters: List<Filter<*>>, prefix: String): SortRef? {
		filters.forEachIndexed { index, filter ->
			val path = if (prefix.isEmpty()) index.toString() else "$prefix.$index"
			when (filter) {
				is Filter.Select<*> -> if (isSortName(filter.name)) return SortRef.OfSelect(path, filter)
				is Filter.Group<*> -> findSortSelect(filter.state.filterIsInstance<Filter<*>>(), path)?.let { return it }
				else -> Unit
			}
		}
		return null
	}

	private fun isSortName(name: String): Boolean {
		val n = name.lowercase()
		return n.contains("sort") || n.contains("order")
	}

	private fun parseKey(key: String): Encoded? {
		val atIndex = key.indexOf('@')
		if (atIndex <= 0) return null
		val type = key.substring(0, atIndex)
		val rest = key.substring(atIndex + 1)
		val eqIndex = rest.indexOf('=')
		if (eqIndex < 0) return null
		val path = rest.substring(0, eqIndex)
		val value = rest.substring(eqIndex + 1)
		return Encoded(type = type, path = path, value = value)
	}

	private fun tag(source: MangaSource, key: String, title: String) = MangaTag(title, key, source)

	private data class Encoded(val type: String, val path: String, val value: String)

	/** A located sort control: either a real [Filter.Sort] or a [Filter.Select] used as a sort. */
	sealed class SortRef(val path: String) {
		class OfSort(path: String, val filter: Filter.Sort) : SortRef(path)
		class OfSelect(path: String, val filter: Filter.Select<*>) : SortRef(path)
	}
}
