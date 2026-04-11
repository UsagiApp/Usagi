package org.draken.usagi.settings.sources.manage.plugins

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.withContext
import org.draken.usagi.R
import org.draken.usagi.core.util.ext.setTextAndVisible
import org.draken.usagi.databinding.ItemEmptyHintBinding
import org.draken.usagi.databinding.ItemSourceConfigBinding

class PluginsManageAdapter(
	private val onDeleteClick: (PluginManageItem.Plugin) -> Unit,
	private val onUpdateClick: (PluginManageItem.Plugin) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>(), FlowCollector<List<PluginManageItem>> {

	private var items = emptyList<PluginManageItem>()

	override suspend fun emit(value: List<PluginManageItem>) {
		val oldItems = items
		val diffResult = withContext(Dispatchers.Default) {
			DiffUtil.calculateDiff(object : DiffUtil.Callback() {
				override fun getOldListSize() = oldItems.size
				override fun getNewListSize() = value.size
				override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean {
					val old = oldItems[oldPos]
					val new = value[newPos]
					return when (old) {
						is PluginManageItem.Plugin if new is PluginManageItem.Plugin ->
							old.jarName == new.jarName

						is PluginManageItem.Placeholder if new is PluginManageItem.Placeholder ->
							old.titleResId == new.titleResId && old.summaryResId == new.summaryResId

						else -> false
					}
				}

				override fun areContentsTheSame(oldPos: Int, newPos: Int) =
					oldItems[oldPos] == value[newPos]
			})
		}
		items = value
		diffResult.dispatchUpdatesTo(this)
	}

	override fun getItemViewType(position: Int) = when (items[position]) {
		is PluginManageItem.Plugin -> VIEW_TYPE_PLUGIN
		is PluginManageItem.Placeholder -> VIEW_TYPE_PLACEHOLDER
	}

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
		val inflater = LayoutInflater.from(parent.context)
		return if (viewType == VIEW_TYPE_PLACEHOLDER) {
			PlaceholderViewHolder(ItemEmptyHintBinding.inflate(inflater, parent, false))
		} else {
			PluginViewHolder(
				ItemSourceConfigBinding.inflate(inflater, parent, false),
				onDeleteClick,
				onUpdateClick,
			)
		}
	}

	override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
		when (val item = items[position]) {
			is PluginManageItem.Placeholder -> (holder as PlaceholderViewHolder).bind(item)
			is PluginManageItem.Plugin -> (holder as PluginViewHolder).bind(item)
		}
	}

	override fun getItemCount() = items.size

	class PlaceholderViewHolder(
		private val binding: ItemEmptyHintBinding,
	) : RecyclerView.ViewHolder(binding.root) {

		init {
			binding.icon.setImageResource(R.drawable.ic_empty_feed)
		}

		fun bind(item: PluginManageItem.Placeholder) {
			binding.textPrimary.setText(item.titleResId)
			binding.textSecondary.setTextAndVisible(item.summaryResId ?: 0)
		}
	}

	private companion object {
		const val VIEW_TYPE_PLUGIN = 0
		const val VIEW_TYPE_PLACEHOLDER = 1
	}
}
