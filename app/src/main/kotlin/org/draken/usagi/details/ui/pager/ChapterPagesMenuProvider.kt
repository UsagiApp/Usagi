package org.draken.usagi.details.ui.pager

import android.text.Spannable
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.widget.SearchView
import androidx.core.view.MenuCompat
import androidx.core.view.MenuProvider
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.slider.LabelFormatter
import com.google.android.material.slider.Slider
import com.google.android.material.slider.TickVisibilityMode
import org.draken.usagi.R
import org.draken.usagi.core.prefs.AppSettings
import org.draken.usagi.core.ui.sheet.BaseAdaptiveSheet
import org.draken.usagi.core.util.ext.getThemeColor
import org.draken.usagi.core.util.ext.setValueRounded
import org.draken.usagi.core.util.progress.IntPercentLabelFormatter
import org.draken.usagi.details.ui.pager.ChaptersPagesSheet.Companion.TAB_BOOKMARKS
import org.draken.usagi.details.ui.pager.ChaptersPagesSheet.Companion.TAB_CHAPTERS
import org.draken.usagi.details.ui.pager.ChaptersPagesSheet.Companion.TAB_PAGES
import java.lang.ref.WeakReference
import com.google.android.material.R as materialR

class ChapterPagesMenuProvider(
	private val viewModel: ChaptersPagesViewModel,
	private val sheet: BaseAdaptiveSheet<*>,
	private val pager: ViewPager2,
	private val settings: AppSettings,
) : OnBackPressedCallback(false),
	MenuProvider,
	SearchView.OnQueryTextListener,
	MenuItem.OnActionExpandListener,
	Slider.OnChangeListener {
	private var expandedItemRef: WeakReference<MenuItem>? = null

	override fun onCreateMenu(
		menu: Menu,
		menuInflater: MenuInflater,
	) {
		val tab = getCurrentTab()
		when (tab) {
			TAB_CHAPTERS -> {
				menuInflater.inflate(R.menu.opt_chapters, menu)
				MenuCompat.setGroupDividerEnabled(menu, true)
				menu.findItem(R.id.action_search)?.run {
					setOnActionExpandListener(this@ChapterPagesMenuProvider)
					(actionView as? SearchView)?.setupChaptersSearchView()
				}
				menu.findItem(R.id.action_search)?.isVisible = viewModel.emptyReason.value == null
				menu.findItem(R.id.action_reversed)?.isChecked = viewModel.isChaptersReversed.value == true
				menu.findItem(R.id.action_grid_view)?.isChecked = viewModel.isChaptersInGridView.value == true
				menu.findItem(R.id.action_downloaded)?.let { menuItem ->
					menuItem.isVisible = viewModel.mangaDetails.value?.local != null
					menuItem.isChecked = viewModel.isDownloadedOnly.value == true
				}
				when (viewModel.chaptersOrder.value) {
					1 -> menu.findItem(R.id.action_sort_by_number)?.isChecked = true
					2 -> menu.findItem(R.id.action_sort_by_name)?.isChecked = true
					3 -> menu.findItem(R.id.action_sort_by_upload_date)?.isChecked = true
					else -> menu.findItem(R.id.action_sort_default)?.isChecked = true
				}
				menu.findItem(R.id.action_sort_header)?.let { item ->
					val title = item.title ?: return@let
					val span = android.text.SpannableString(title)
					val color = sheet.requireContext().getThemeColor(materialR.attr.colorOutline)
					span.setSpan(ForegroundColorSpan(color), 0, title.length, Spannable.SPAN_INCLUSIVE_INCLUSIVE)
					span.setSpan(RelativeSizeSpan(0.85f), 0, title.length, Spannable.SPAN_INCLUSIVE_INCLUSIVE)
					item.title = span
				}
			}

			TAB_PAGES, TAB_BOOKMARKS -> {
				menuInflater.inflate(R.menu.opt_pages, menu)
				menu.findItem(R.id.action_grid_size)?.run {
					setOnActionExpandListener(this@ChapterPagesMenuProvider)
					(actionView as? Slider)?.setupPagesSizeSlider()
				}
			}
		}
	}

	override fun onMenuItemSelected(menuItem: MenuItem): Boolean =
		when (menuItem.itemId) {
			R.id.action_reversed -> {
				viewModel.setChaptersReversed(!menuItem.isChecked)
				true
			}

			R.id.action_grid_view -> {
				viewModel.setChaptersInGridView(!menuItem.isChecked)
				true
			}

			R.id.action_downloaded -> {
				viewModel.isDownloadedOnly.value = !menuItem.isChecked
				true
			}

			R.id.action_sort_default -> {
				viewModel.setChaptersSortOrder(0)
				true
			}

			R.id.action_sort_by_number -> {
				viewModel.setChaptersSortOrder(1)
				true
			}

			R.id.action_sort_by_name -> {
				viewModel.setChaptersSortOrder(2)
				true
			}

			R.id.action_sort_by_upload_date -> {
				viewModel.setChaptersSortOrder(3)
				true
			}

			else -> {
				false
			}
		}

	override fun handleOnBackPressed() {
		expandedItemRef?.get()?.collapseActionView()
	}

	override fun onMenuItemActionExpand(item: MenuItem): Boolean {
		expandedItemRef = WeakReference(item)
		sheet.expandAndLock()
		isEnabled = true
		return true
	}

	override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
		expandedItemRef = null
		isEnabled = false
		(item.actionView as? SearchView)?.setQuery("", false)
		viewModel.performChapterSearch(null)
		sheet.unlock()
		return true
	}

	override fun onQueryTextSubmit(query: String?): Boolean = false

	override fun onQueryTextChange(newText: String?): Boolean {
		viewModel.performChapterSearch(newText)
		return true
	}

	override fun onValueChange(
		slider: Slider,
		value: Float,
		fromUser: Boolean,
	) {
		if (fromUser) {
			settings.gridSizePages = value.toInt()
		}
	}

	private fun SearchView.setupChaptersSearchView() {
		setOnQueryTextListener(this@ChapterPagesMenuProvider)
		setIconifiedByDefault(false)
		queryHint = context.getString(R.string.search_chapters)
	}

	private fun Slider.setupPagesSizeSlider() {
		valueFrom = 50f
		valueTo = 150f
		stepSize = 5f
		tickVisibilityMode = TickVisibilityMode.TICK_VISIBILITY_HIDDEN
		labelBehavior = LabelFormatter.LABEL_FLOATING
		setLabelFormatter(IntPercentLabelFormatter(context))
		setValueRounded(settings.gridSizePages.toFloat())
		addOnChangeListener(this@ChapterPagesMenuProvider)
	}

	private fun getCurrentTab(): Int {
		var page = pager.currentItem
		if (page > 0 && pager.adapter?.itemCount == 2) { // no Pages page
			page++ // shift
		}
		return page
	}
}
