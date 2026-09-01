package org.draken.usagi.favourites.ui.smartfolders

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import org.draken.usagi.R
import org.draken.usagi.databinding.ItemSmartFolderBinding
import org.draken.usagi.favourites.domain.SmartFolder
import org.draken.usagi.favourites.domain.SmartFolderRulesResult
import java.util.Collections

class SmartFoldersAdapter(
	private val listener: Listener,
) : RecyclerView.Adapter<SmartFoldersAdapter.ViewHolder>() {
	private val items = mutableListOf<SmartFolder>()

	fun setItems(folders: List<SmartFolder>) {
		val previous = items.toList()
		val diff =
			DiffUtil.calculateDiff(
				object : DiffUtil.Callback() {
					override fun getOldListSize(): Int = previous.size

					override fun getNewListSize(): Int = folders.size

					override fun areItemsTheSame(
						oldItemPosition: Int,
						newItemPosition: Int,
					): Boolean = previous[oldItemPosition].id == folders[newItemPosition].id

					override fun areContentsTheSame(
						oldItemPosition: Int,
						newItemPosition: Int,
					): Boolean = previous[oldItemPosition] == folders[newItemPosition]
				},
			)
		items.clear()
		items += folders
		diff.dispatchUpdatesTo(this)
	}

	fun move(
		from: Int,
		to: Int,
	): Boolean {
		if (from !in items.indices || to !in items.indices) return false
		Collections.swap(items, from, to)
		notifyItemMoved(from, to)
		return true
	}

	fun snapshot(): List<SmartFolder> = items.toList()

	override fun onCreateViewHolder(
		parent: ViewGroup,
		viewType: Int,
	): ViewHolder =
		ViewHolder(
			ItemSmartFolderBinding.inflate(LayoutInflater.from(parent.context), parent, false),
		)

	override fun onBindViewHolder(
		holder: ViewHolder,
		position: Int,
	) = holder.bind(items[position])

	override fun getItemCount(): Int = items.size

	inner class ViewHolder(
		private val binding: ItemSmartFolderBinding,
	) : RecyclerView.ViewHolder(binding.root) {
		@SuppressLint("ClickableViewAccessibility")
		fun bind(folder: SmartFolder) {
			binding.textViewTitle.text = folder.title
			binding.textViewSubtitle.text = folder.summary()
			binding.root.setOnClickListener { listener.onEdit(folder) }
			binding.imageViewEdit.setOnClickListener { listener.onEdit(folder) }
			binding.imageViewDelete.setOnClickListener { listener.onDelete(folder) }
			binding.imageViewHandle.setOnTouchListener { _, event ->
				if (event.actionMasked == MotionEvent.ACTION_DOWN) {
					listener.onStartDrag(this)
				}
				false
			}
		}

		private fun SmartFolder.summary(): String =
			when (val result = rules) {
				is SmartFolderRulesResult.Error -> binding.root.context.getString(R.string.smart_folder_invalid_rules_short)
				is SmartFolderRulesResult.Success -> result.rules.formatSummary(binding.root.context)
			}
	}

	interface Listener {
		fun onEdit(folder: SmartFolder)

		fun onDelete(folder: SmartFolder)

		fun onStartDrag(holder: RecyclerView.ViewHolder)
	}
}
