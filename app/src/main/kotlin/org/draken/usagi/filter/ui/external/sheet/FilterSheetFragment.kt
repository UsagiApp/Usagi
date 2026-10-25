package org.draken.usagi.filter.ui.external.sheet

import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.google.android.material.shape.MaterialShapeDrawable
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.withCreationCallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import org.draken.usagi.R
import org.draken.usagi.core.ui.sheet.AdaptiveSheetBehavior.Companion.STATE_DRAGGING
import org.draken.usagi.core.ui.sheet.AdaptiveSheetBehavior.Companion.STATE_EXPANDED
import org.draken.usagi.core.ui.sheet.AdaptiveSheetBehavior.Companion.STATE_SETTLING
import org.draken.usagi.core.ui.sheet.AdaptiveSheetCallback
import org.draken.usagi.core.ui.sheet.BaseAdaptiveSheet
import org.draken.usagi.core.ui.widgets.ChipsView
import org.draken.usagi.core.util.ext.consume
import org.draken.usagi.core.util.ext.getThemeColor
import org.draken.usagi.core.util.ext.observe
import org.draken.usagi.databinding.SheetOptionsBinding
import org.draken.usagi.filter.data.PersistableFilter
import org.draken.usagi.filter.ui.FilterCoordinator
import org.draken.usagi.filter.ui.external.FilterAdapter
import org.draken.usagi.filter.ui.external.FilterViewModel
import org.draken.usagi.filter.ui.model.FilterProperty
import org.draken.usagi.filter.ui.saved.SavedFilterActions
import com.google.android.material.R as materialR

@AndroidEntryPoint
class FilterSheetFragment :
	BaseAdaptiveSheet<SheetOptionsBinding>(),
	AdaptiveSheetCallback,
	ChipsView.OnChipClickListener,
	ChipsView.OnChipLongClickListener,
	ChipsView.OnChipCloseClickListener {
	private val viewModel by viewModels<FilterViewModel>(
		extrasProducer = {
			defaultViewModelCreationExtras.withCreationCallback<FilterViewModel.Factory> { factory ->
				factory.create(FilterCoordinator.require(this), dialog == null)
			}
		},
	)

	private lateinit var savedFilterActions: SavedFilterActions
	private var systemBarsBottom = 0

	override fun onCreateViewBinding(
		inflater: LayoutInflater,
		container: ViewGroup?,
	): SheetOptionsBinding = SheetOptionsBinding.inflate(inflater, container, false)

	override fun onViewBindingCreated(
		binding: SheetOptionsBinding,
		savedInstanceState: Bundle?,
	) {
		super.onViewBindingCreated(binding, savedInstanceState)
		if (dialog == null) binding.adjustForEmbeddedLayout()
		val coordinator = FilterCoordinator.require(this)
		savedFilterActions = SavedFilterActions(requireContext(), coordinator)
		val adapter = FilterAdapter(viewModel)
		binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
		binding.recyclerView.adapter = adapter
		binding.buttonReset.setOnClickListener { viewModel.reset() }
		binding.buttonSave.setOnClickListener {
			// Commit any pending text input before capturing the current filter state.
			binding.recyclerView.findFocus()?.clearFocus()
			savedFilterActions.showSaveDialog()
		}
		binding.chipsSavedFilters.onChipClickListener = this
		binding.chipsSavedFilters.onChipLongClickListener = this
		binding.chipsSavedFilters.onChipCloseClickListener = this
		coordinator.savedFilters.observe(viewLifecycleOwner, ::onSavedFiltersChanged)
		combine(
			coordinator.observe().map { coordinator.isSaveableFilter(it.listFilter) }.distinctUntilChanged(),
			coordinator.savedFilters.map { it.selectedItems.isEmpty() }.distinctUntilChanged(),
			Boolean::and,
		).flowOn(Dispatchers.Default)
			.observe(viewLifecycleOwner) {
				binding.buttonSave.isEnabled = it
			}
		binding.buttonDone.setOnClickListener {
			// Commit any pending text input before closing: dismissing the sheet tears the
			// view down immediately, so the field's focus-loss callback never runs and the
			// typed value would be lost. Clearing focus fires the commit synchronously first.
			binding.recyclerView.findFocus()?.clearFocus()
			dismiss()
		}
		viewModel.items.observe(viewLifecycleOwner, adapter)
		viewModel.isLoading.observe(viewLifecycleOwner) { binding.progressBar.isVisible = it }
		viewModel.isEmptyState.observe(viewLifecycleOwner) { binding.textViewHolder.isVisible = it }
		addSheetCallback(this, viewLifecycleOwner)
		binding.layoutBottom.doOnLayout {
			dialog?.findViewById<View>(materialR.id.design_bottom_sheet)?.let { s -> updateLayout(s) }
		}
	}

	override fun onStart() {
		super.onStart()
		setHalfExpanded()
		(dialog as? com.google.android.material.bottomsheet.BottomSheetDialog)?.behavior?.skipCollapsed = false
	}

	override fun onStateChanged(
		sheet: View,
		newState: Int,
	) {
		updateLayout(sheet)
		if (newState == STATE_DRAGGING || newState == STATE_SETTLING) return
		// Snap the drag handle to its resting state for programmatic moves; manual drags drive it via onSlide.
		viewBinding?.headerBar?.setProgress(if (newState == STATE_EXPANDED) 1f else 0f)
	}

	override fun onSlide(
		sheet: View,
		slideOffset: Float,
	) {
		updateLayout(sheet)
		// Melt the drag handle away over the top stretch of the drag so reaching full screen is one
		// seamless motion rather than the handle snapping out once expanded.
		val binding = viewBinding ?: return
		val progress = (slideOffset - DRAG_HANDLE_COLLAPSE_START) / (1f - DRAG_HANDLE_COLLAPSE_START)
		binding.headerBar.setProgress(progress)
	}

	private fun updateLayout(sheet: View) {
		val binding = viewBinding ?: return
		val top = sheet.top
		binding.layoutBottom.translationY = -top.toFloat()
		val surfaceColor = getSheetSurfaceColor(sheet)
		binding.layoutBottom.setBackgroundColor(surfaceColor)
		val basePadding = resources.getDimensionPixelOffset(R.dimen.margin_small)
		val buttonsHeight = binding.layoutBottom.height
		binding.recyclerView.updatePadding(bottom = basePadding + systemBarsBottom + buttonsHeight + top)
	}

	private fun getSheetSurfaceColor(sheet: View): Int {
		val color =
			when (val background = sheet.background) {
				is MaterialShapeDrawable -> background.fillColor?.defaultColor
				is ColorDrawable -> background.color
				else -> null
			}
		return color ?: requireContext().getThemeColor(android.R.attr.colorBackground)
	}

	private fun SheetOptionsBinding.adjustForEmbeddedLayout() {
		buttonDone.isVisible = false
		getRoot().layoutParams?.height = ViewGroup.LayoutParams.MATCH_PARENT
		buttonReset.updateLayoutParams<LinearLayout.LayoutParams> {
			weight = 0f
			width = LinearLayout.LayoutParams.WRAP_CONTENT
			gravity = Gravity.END or Gravity.CENTER_VERTICAL
		}
		buttonSave.updateLayoutParams<LinearLayout.LayoutParams> {
			weight = 0f
			width = LinearLayout.LayoutParams.WRAP_CONTENT
			gravity = Gravity.END or Gravity.CENTER_VERTICAL
		}
	}

	private fun onSavedFiltersChanged(value: FilterProperty<PersistableFilter>) {
		val b = viewBinding ?: return
		b.layoutSavedFilters.isVisible = !value.isEmpty()
		if (value.isEmpty()) {
			b.chipsSavedFilters.setChips(emptyList())
			return
		}
		b.chipsSavedFilters.setChips(
			value.availableItems.map { f ->
				ChipsView.ChipModel(
					title = f.name,
					isChecked = f in value.selectedItems,
					data = f,
					isDropdown = true,
				)
			},
		)
	}

	override fun onChipClick(
		chip: Chip,
		data: Any?,
	) {
		if (data is PersistableFilter) {
			viewBinding?.recyclerView?.findFocus()?.clearFocus()
			viewModel.applySavedFilter(data)
		}
	}

	override fun onChipLongClick(
		chip: Chip,
		data: Any?,
	): Boolean =
		when (data) {
			is PersistableFilter -> {
				savedFilterActions.showMenu(chip, data)
				true
			}

			else -> {
				false
			}
		}

	override fun onChipCloseClick(
		chip: Chip,
		data: Any?,
	) {
		if (data is PersistableFilter) {
			savedFilterActions.showMenu(chip, data)
		}
	}

	override fun onApplyWindowInsets(
		v: View,
		insets: WindowInsetsCompat,
	): WindowInsetsCompat {
		val typeMask = WindowInsetsCompat.Type.systemBars()
		val barsInsets = insets.getInsets(typeMask)
		systemBarsBottom = barsInsets.bottom
		viewBinding?.recyclerView?.updatePadding(
			left = barsInsets.left,
			right = barsInsets.right,
		)
		// The action buttons now sit at the bottom, so the navigation-bar inset must keep them clear.
		// Preserve the layout's own vertical breathing room on top of the system inset.
		val basePadding = resources.getDimensionPixelOffset(R.dimen.margin_small)
		viewBinding?.layoutBottom?.updatePadding(bottom = basePadding + barsInsets.bottom)
		dialog?.findViewById<View>(materialR.id.design_bottom_sheet)?.let { s -> updateLayout(s) } ?: run {
			// Embedded layout fallback
			viewBinding?.run {
				val surfaceColor = requireContext().getThemeColor(android.R.attr.colorBackground)
				layoutBottom.setBackgroundColor(surfaceColor)
				recyclerView.updatePadding(bottom = basePadding + barsInsets.bottom + layoutBottom.height)
			}
		}
		return insets.consume(v, typeMask, bottom = true)
	}

	private companion object {
		// Slide offset (0 = half, 1 = full screen) at which the drag handle starts collapsing. Kept above
		// the half-expanded resting offset so the handle stays full at the centre position.
		const val DRAG_HANDLE_COLLAPSE_START = 0.65f
	}
}
