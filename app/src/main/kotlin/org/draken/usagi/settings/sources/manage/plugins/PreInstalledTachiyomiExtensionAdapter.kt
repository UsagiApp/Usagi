package org.draken.usagi.settings.sources.manage.plugins

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import org.draken.usagi.R
import org.draken.usagi.databinding.ItemTachiyomiPreinstalledExtensionBinding
import org.draken.usagi.settings.sources.manage.plugins.model.PluginManageItem

class PreInstalledTachiyomiExtensionAdapter(
	private val onToggleVisibility: (PluginManageItem.InstalledTachiyomiExtension) -> Unit,
	private val onUninstall: (PluginManageItem.InstalledTachiyomiExtension) -> Unit,
) : RecyclerView.Adapter<PreInstalledTachiyomiExtensionAdapter.ViewHolder>() {
	private var items = emptyList<PluginManageItem.InstalledTachiyomiExtension>()

	fun submit(items: List<PluginManageItem.InstalledTachiyomiExtension>) {
		this.items = items
		notifyDataSetChanged()
	}

	override fun onCreateViewHolder(
		parent: ViewGroup,
		viewType: Int,
	): ViewHolder =
		ViewHolder(
			ItemTachiyomiPreinstalledExtensionBinding.inflate(
				LayoutInflater.from(parent.context),
				parent,
				false,
			),
		)

	override fun onBindViewHolder(
		holder: ViewHolder,
		position: Int,
	) = holder.bind(items[position])

	override fun getItemCount(): Int = items.size

	inner class ViewHolder(
		private val binding: ItemTachiyomiPreinstalledExtensionBinding,
	) : RecyclerView.ViewHolder(binding.root) {
		fun bind(item: PluginManageItem.InstalledTachiyomiExtension) {
			val context = binding.root.context
			val fallback = ContextCompat.getDrawable(context, R.drawable.ic_tachiyomi_extension_package)
			binding.imageViewIcon.setImageDrawable(
				runCatching { context.packageManager.getApplicationIcon(item.packageName) }.getOrNull() ?: fallback,
			)
			binding.textViewTitle.text = item.displayName
			binding.textViewDescription.text =
				buildList {
					item.versionName?.takeIf { it.isNotBlank() }?.let { add("v$it") }
					if (item.sourceCount > 0) {
						add(
							context.resources.getQuantityString(
								R.plurals.tachiyomi_preinstalled_source_count,
								item.sourceCount,
								item.sourceCount,
							),
						)
					}
					item.loadError?.takeIf { it.isNotBlank() }?.let(::add)
				}.joinToString(" • ")

			val canToggleVisibility = item.sourceCount > 0
			binding.imageViewToggle.isEnabled = canToggleVisibility
			binding.imageViewToggle.alpha = if (canToggleVisibility) 1F else 0.38F
			binding.imageViewToggle.setImageResource(if (item.isVisibleInExplore) R.drawable.ic_eye_off else R.drawable.ic_eye)
			binding.imageViewToggle.contentDescription =
				context.getString(if (item.isVisibleInExplore) R.string.hide_from_explore else R.string.show_in_explore)
			binding.imageViewToggle.setOnClickListener {
				if (canToggleVisibility) onToggleVisibility(item)
			}
			binding.imageViewUninstall.setOnClickListener { onUninstall(item) }
		}
	}
}
