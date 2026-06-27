package org.draken.usagi.settings.sources.manage.plugins

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.view.ActionMode
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.draken.usagi.R
import org.draken.usagi.core.ui.BaseFragment
import org.draken.usagi.core.ui.dialog.buildAlertDialog
import org.draken.usagi.core.ui.dialog.setEditText
import org.draken.usagi.core.ui.util.RecyclerViewOwner
import org.draken.usagi.core.util.ext.addMenuProvider
import org.draken.usagi.core.util.ext.container
import org.draken.usagi.core.util.ext.end
import org.draken.usagi.core.util.ext.start
import org.draken.usagi.core.parser.PluginFileLoader
import org.draken.usagi.databinding.DialogImportBinding
import org.draken.usagi.databinding.FragmentSettingsSourcesBinding
import org.draken.usagi.main.ui.owners.AppBarOwner
import org.draken.usagi.settings.sources.manage.plugins.model.PluginManageItem
import kotlin.coroutines.resume

@AndroidEntryPoint
class PluginsManageFragment :
	BaseFragment<FragmentSettingsSourcesBinding>(),
	RecyclerViewOwner {
	private val viewModel by viewModels<PluginsManageViewModel>()
	private var pluginsAdapter: PluginManageAdapter? = null
	private var selectionDecoration: PluginsSelectionDecoration? = null
	private var actionMode: ActionMode? = null

	private val launcher = registerForActivityResult(
		ActivityResultContracts.OpenDocument()
	) { uri ->
		if (uri != null && isAdded) {
			viewModel.importPlugin(
				uri = uri,
				getOriginalName = { DocumentFile.fromSingleUri(requireContext().applicationContext, it)?.name },
				askName = { askText(R.string.set_plugin_name, it, R.string.plugin_name) },
				askOverwrite = ::askOverwrite,
				onResult = ::showImportResult
			)
		}
	}

	override val recyclerView: RecyclerView?
		get() = viewBinding?.recyclerView

	override fun onCreateViewBinding(
		inflater: LayoutInflater,
		container: ViewGroup?,
	): FragmentSettingsSourcesBinding {
		return FragmentSettingsSourcesBinding.inflate(inflater, container, false)
	}

	@SuppressLint("NotifyDataSetChanged")
	override fun onViewBindingCreated(binding: FragmentSettingsSourcesBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		pluginsAdapter = PluginManageAdapter(
			onRenameClick = ::onRenameClick,
			onUpdateClick = ::onUpdateClick,
			onLongClick = ::onLongClick,
			onClick = ::onClick,
			isSelected = { item -> viewModel.isSelected(item.name) }
		)
		selectionDecoration = PluginsSelectionDecoration(requireContext()).also {
			binding.recyclerView.addItemDecoration(it)
		}
		with(binding.recyclerView) {
			setHasFixedSize(true)
			layoutManager = LinearLayoutManager(context)
			adapter = pluginsAdapter
		}

		viewLifecycleOwner.lifecycleScope.launch {
			viewModel.content.collect { pluginsAdapter?.emit(it) }
		}

		viewLifecycleOwner.lifecycleScope.launch {
			viewModel.selectedPlugins.collect { selected ->
				selectionDecoration?.clearSelection()
				selected.forEach { jarName ->
					selectionDecoration?.setItemIsChecked(jarName.hashCode().toLong(), true)
				}
				updateActionModeTitle()
				pluginsAdapter?.notifyDataSetChanged()
			}
		}

		addMenuProvider(
			PluginsMenuProvider(
				appBarOwner = activity as? AppBarOwner,
				onImportClick = ::showImportDialog,
				onSearchQueryChanged = viewModel::setQuery,
			),
		)
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val barsInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
		val isTablet = !resources.getBoolean(R.bool.is_tablet)
		val isMaster = container?.id == R.id.container_master
		v.setPaddingRelative(
			if (isTablet && !isMaster) 0 else barsInsets.start(v),
			0,
			if (isTablet && isMaster) 0 else barsInsets.end(v),
			barsInsets.bottom,
		)
		return WindowInsetsCompat.Builder(insets)
			.setInsets(WindowInsetsCompat.Type.systemBars(), Insets.NONE)
			.build()
	}

	override fun onResume() {
		super.onResume()
		activity?.setTitle(R.string.manage_plugins)
		viewModel.runAutoUpdate()
		viewModel.refresh()
	}

	override fun onDestroyView() {
		pluginsAdapter = null
		selectionDecoration = null
		actionMode?.finish()
		super.onDestroyView()
	}

	private fun showImportDialog() {
		val binding = DialogImportBinding.inflate(layoutInflater)
		binding.buttonFile.title = getString(R.string.load_from_storage)
		binding.buttonFile.subtitle = getString(R.string.load_storage_summary)
		binding.buttonFile.setIconResource(R.drawable.ic_storage)
		binding.buttonDir.title = getString(R.string.import_from_github)
		binding.buttonDir.subtitle = getString(R.string.import_github_summary)
		binding.buttonDir.setIconResource(R.drawable.ic_open_external)
		val dialog = buildAlertDialog(requireContext()) {
			setTitle(R.string._import)
			setView(binding.root)
			setNegativeButton(android.R.string.cancel, null)
		}
		binding.buttonFile.setOnClickListener {
			dialog.dismiss()
			launcher.launch(SUPPORTED_MIME_TYPES)
		}
		binding.buttonDir.setOnClickListener {
			dialog.dismiss()
			viewModel.importGithubPlugin(
				askInput = { askText(R.string.import_from_github, "", null) },
				askOverwrite = ::askOverwrite,
				onResult = ::showImportResult
			)
		}
		dialog.show()
	}

	private fun onRenameClick(item: PluginManageItem.Plugin) {
		viewLifecycleOwner.lifecycleScope.launch {
			val newName = askText(R.string.rename, item.displayName, R.string.plugin_name)
			if (!newName.isNullOrBlank()) {
				val success = viewModel.rename(item, newName)
				val binding = viewBinding ?: return@launch
				Snackbar.make(
					binding.recyclerView,
					if (success) R.string.load_success else R.string.load_failed,
					Snackbar.LENGTH_SHORT,
				).show()
			}
		}
	}

	private fun onClick(item: PluginManageItem.Plugin) {
		if (actionMode != null) {
			viewModel.toggleSelection(item.name)
		}
	}

	private fun onLongClick(item: PluginManageItem.Plugin) {
		startSelection(item)
	}

	private fun startSelection(item: PluginManageItem.Plugin) {
		if (actionMode == null) {
			actionMode = (activity as? androidx.appcompat.app.AppCompatActivity)?.startSupportActionMode(ActionModeCallback())
		}
		viewModel.toggleSelection(item.name)
	}

	private fun updateActionModeTitle() {
		val count = viewModel.selectedPlugins.value.size
		if (count == 0) {
			actionMode?.finish()
		} else {
			actionMode?.title = count.toString()
		}
	}

	private fun showDeleteSelectedConfirm() {
		val count = viewModel.selectedPlugins.value.size
		val itemsText = resources.getQuantityString(R.plurals.items, count, count)
		buildAlertDialog(requireContext()) {
			setTitle(R.string.delete_plugin)
			setMessage(getString(R.string.confirm_delete_plugin, itemsText))
			setNegativeButton(android.R.string.cancel, null)
			setPositiveButton(R.string.delete) { _, _ ->
				viewLifecycleOwner.lifecycleScope.launch {
					val success = viewModel.delete()
					val binding = viewBinding ?: return@launch
					Snackbar.make(
						binding.recyclerView,
						if (success) R.string.removal_completed else R.string.load_failed,
						Snackbar.LENGTH_SHORT,
					).show()
					actionMode?.finish()
				}
			}
		}.show()
	}

	private fun onUpdateClick(item: PluginManageItem.Plugin) {
		viewLifecycleOwner.lifecycleScope.launch {
			val success = viewModel.updatePlugin(item)
			val binding = viewBinding ?: return@launch
			Snackbar.make(
				binding.recyclerView,
				if (success) R.string.load_success else R.string.load_failed,
				Snackbar.LENGTH_SHORT,
			).show()
		}
	}

	private suspend fun askOverwrite(fileName: String): Boolean = withContext(Dispatchers.Main) {
		suspendCancellableCoroutine { cont ->
			val dialog = buildAlertDialog(requireContext(), true) {
				setIcon(R.drawable.ic_replace)
				setTitle(R.string.overwrite_plugin)
				setMessage(getString(R.string.overwrite_plugin_summary, fileName))
				setNegativeButton(android.R.string.cancel) { _, _ ->
					if (cont.isActive) cont.resume(false)
				}
				setPositiveButton(R.string.overwrite) { _, _ ->
					if (cont.isActive) cont.resume(true)
				}
			}
			dialog.setOnCancelListener {
				if (cont.isActive) cont.resume(false)
			}
			dialog.show()
		}
	}

	private suspend fun askText(
		titleRes: Int,
		defaultValue: String,
		hintRes: Int?,
	): String? = withContext(Dispatchers.Main) {
		suspendCancellableCoroutine { cont ->
			lateinit var input: android.widget.EditText
			val dialog = buildAlertDialog(requireContext()) {
				input = setEditText(InputType.TYPE_CLASS_TEXT, singleLine = true)
				input.setText(defaultValue)
				if (hintRes != null) {
					input.hint = getString(hintRes)
				}
				setTitle(titleRes)
				setNegativeButton(android.R.string.cancel) { _, _ ->
					if (cont.isActive) cont.resume(null)
				}
				setPositiveButton(android.R.string.ok) { _, _ ->
					if (cont.isActive) cont.resume(input.text?.toString())
				}
			}
			dialog.setOnCancelListener {
				if (cont.isActive) cont.resume(null)
			}
			dialog.show()
		}
	}

	private fun showImportResult(isSuccess: Boolean) {
		val binding = viewBinding ?: return
		Snackbar.make(
			binding.recyclerView,
			if (isSuccess) R.string.load_success else R.string.load_failed,
			Snackbar.LENGTH_LONG,
		).show()
	}

	private inner class ActionModeCallback : ActionMode.Callback {
		override fun onCreateActionMode(mode: ActionMode, menu: android.view.Menu): Boolean {
			menu.add(0, R.id.action_remove, 0, R.string.delete).apply {
				setIcon(R.drawable.ic_delete)
				setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
			}
			return true
		}

		override fun onPrepareActionMode(mode: ActionMode, menu: android.view.Menu): Boolean = false

		override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
			return when (item.itemId) {
				R.id.action_remove -> {
					showDeleteSelectedConfirm()
					true
				}
				else -> false
			}
		}

		@SuppressLint("NotifyDataSetChanged")
		override fun onDestroyActionMode(mode: ActionMode) {
			viewModel.clearSelection()
			actionMode = null
			pluginsAdapter?.notifyDataSetChanged()
		}
	}

	private companion object {
		val SUPPORTED_MIME_TYPES = PluginFileLoader.SUPPORTED_MIME_TYPES
	}
}
