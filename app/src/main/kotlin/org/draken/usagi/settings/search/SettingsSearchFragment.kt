package org.draken.usagi.settings.search

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.AsyncListDiffer.ListListener
import androidx.recyclerview.widget.LinearLayoutManager
import dagger.hilt.android.AndroidEntryPoint
import org.draken.usagi.core.ui.BaseFragment
import org.draken.usagi.core.ui.BaseListAdapter
import org.draken.usagi.core.ui.list.OnListItemClickListener
import org.draken.usagi.core.util.ext.consumeAll
import org.draken.usagi.core.util.ext.observe
import org.draken.usagi.databinding.FragmentSearchSuggestionBinding
import org.draken.usagi.list.ui.adapter.ListItemType

@AndroidEntryPoint
class SettingsSearchFragment : BaseFragment<FragmentSearchSuggestionBinding>(),
	OnListItemClickListener<SettingsItem>,
	ListListener<SettingsItem> {

	private val viewModel: SettingsSearchViewModel by activityViewModels()

	override fun onCreateViewBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentSearchSuggestionBinding {
		return FragmentSearchSuggestionBinding.inflate(inflater, container, false)
	}

	override fun onViewBindingCreated(binding: FragmentSearchSuggestionBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		val adapter = BaseListAdapter<SettingsItem>()
			.addDelegate(ListItemType.NAV_ITEM, settingsItemAD(this))
		adapter.addListListener(this)
		binding.root.adapter = adapter
		binding.root.setHasFixedSize(true)
		viewModel.content.observe(viewLifecycleOwner, adapter)
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val type = WindowInsetsCompat.Type.ime() or WindowInsetsCompat.Type.systemBars()
		val barsInsets = insets.getInsets(type)
		v.setPadding(
			barsInsets.left,
			0,
			barsInsets.right,
			barsInsets.bottom,
		)
		return insets.consumeAll(type)
	}

	override fun onItemClick(item: SettingsItem, view: View) = viewModel.navigateToPreference(item)

	override fun onCurrentListChanged(
		previousList: List<SettingsItem?>,
		currentList: List<SettingsItem?>
	) {
		if (currentList.size != previousList.size) {
			(viewBinding?.root?.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(0, 0)
		}
	}
}
