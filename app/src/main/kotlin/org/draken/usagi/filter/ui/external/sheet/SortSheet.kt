package org.draken.usagi.filter.ui.external.sheet

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isInvisible
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.withCreationCallback
import org.draken.usagi.R
import org.draken.usagi.core.ui.BaseListAdapter
import org.draken.usagi.core.ui.sheet.BaseAdaptiveSheet
import org.draken.usagi.core.util.ext.consumeAll
import org.draken.usagi.core.util.ext.observe
import org.draken.usagi.databinding.ItemSortOptionBinding
import org.draken.usagi.databinding.SheetSortBinding
import org.draken.usagi.filter.ui.FilterCoordinator
import org.draken.usagi.filter.ui.external.SortViewModel
import org.draken.usagi.filter.ui.external.model.SortOption
import org.draken.usagi.list.ui.adapter.ListItemType

@AndroidEntryPoint
class SortSheet : BaseAdaptiveSheet<SheetSortBinding>() {

	private val viewModel by viewModels<SortViewModel>(
		extrasProducer = {
			defaultViewModelCreationExtras.withCreationCallback<SortViewModel.Factory> { factory ->
				factory.create(FilterCoordinator.require(this))
			}
		},
	)

	override fun onCreateViewBinding(inflater: LayoutInflater, container: ViewGroup?): SheetSortBinding {
		return SheetSortBinding.inflate(inflater, container, false)
	}

	override fun onViewBindingCreated(binding: SheetSortBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		val adapter = BaseListAdapter<SortOption>()
			.addDelegate(ListItemType.SORT_OPTION, sortOptionDelegate(viewModel::onOptionClick))
		binding.recyclerView.layoutManager = LinearLayoutManager(binding.root.context)
		binding.recyclerView.adapter = adapter
		viewModel.content.observe(viewLifecycleOwner, adapter)
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val typeMask = WindowInsetsCompat.Type.systemBars()
		val barsInsets = insets.getInsets(typeMask)
		viewBinding?.recyclerView?.setPadding(barsInsets.left, 0, barsInsets.right, barsInsets.bottom)
		return insets.consumeAll(typeMask)
	}

	private fun sortOptionDelegate(
		onClick: (SortOption) -> Unit,
	) = adapterDelegateViewBinding<SortOption, SortOption, ItemSortOptionBinding>(
		{ inflater, parent -> ItemSortOptionBinding.inflate(inflater, parent, false) },
	) {
		binding.layoutRoot.setOnClickListener { onClick(item) }
		bind {
			binding.textViewTitle.text = item.title
			when (item.indicator) {
				SortOption.Indicator.NONE -> binding.imageViewArrow.isInvisible = true
				SortOption.Indicator.ASCENDING -> {
					binding.imageViewArrow.isInvisible = false
					binding.imageViewArrow.setImageResource(R.drawable.ic_arrow_up)
					binding.imageViewArrow.rotation = 0f
				}

				SortOption.Indicator.DESCENDING -> {
					binding.imageViewArrow.isInvisible = false
					binding.imageViewArrow.setImageResource(R.drawable.ic_arrow_up)
					binding.imageViewArrow.rotation = 180f
				}

				SortOption.Indicator.SELECTED -> {
					binding.imageViewArrow.isInvisible = false
					binding.imageViewArrow.setImageResource(R.drawable.ic_check)
					binding.imageViewArrow.rotation = 0f
				}
			}
		}
	}
}
