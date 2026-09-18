package org.draken.usagi.settings.sources.manage.plugins

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
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
import org.draken.usagi.core.nav.router
import org.draken.usagi.core.parser.PluginFileLoader
import org.draken.usagi.core.ui.BaseFragment
import org.draken.usagi.core.ui.dialog.buildAlertDialog
import org.draken.usagi.core.ui.dialog.setEditText
import org.draken.usagi.core.ui.util.RecyclerViewOwner
import org.draken.usagi.core.util.ext.addMenuProvider
import org.draken.usagi.core.util.ext.container
import org.draken.usagi.core.util.ext.end
import org.draken.usagi.core.util.ext.observeEvent
import org.draken.usagi.core.util.ext.start
import org.draken.usagi.databinding.DialogImportBinding
import org.draken.usagi.databinding.FragmentSettingsSourcesBinding
import org.draken.usagi.main.ui.owners.AppBarOwner
import org.draken.usagi.settings.SettingsActivity
import org.draken.usagi.settings.sources.manage.plugins.model.PluginManageItem
import kotlin.coroutines.resume

@AndroidEntryPoint
class PluginsManageFragment :
	BaseFragment<FragmentSettingsSourcesBinding>(),
	RecyclerViewOwner {
	private val viewModel by viewModels<PluginsManageViewModel>()
	private var pluginsAdapter: PluginManageAdapter? = null

	private val launcher =
		registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
			if (uri != null && isAdded) {
				viewModel.importPlugin(
					uri = uri,
					getOriginalName = { DocumentFile.fromSingleUri(requireContext().applicationContext, it)?.name },
					askName = { askText(R.string.set_plugin_name, it, R.string.plugin_name) },
					askOverwrite = ::askOverwrite,
				)
			}
		}

	override val recyclerView: RecyclerView?
		get() = viewBinding?.recyclerView

	override fun onCreateViewBinding(
		inflater: LayoutInflater,
		container: ViewGroup?,
	) = FragmentSettingsSourcesBinding.inflate(inflater, container, false)

	@SuppressLint("NotifyDataSetChanged")
	override fun onViewBindingCreated(
		binding: FragmentSettingsSourcesBinding,
		savedInstanceState: Bundle?,
	) {
		super.onViewBindingCreated(binding, savedInstanceState)
		pluginsAdapter =
			PluginManageAdapter(
				onRenameClick = ::onRenameClick,
				onUpdateClick = viewModel::updatePlugin,
				onExtRenameClick = ::onExtRenameClick,
				onExtLongClick = viewModel::toggleExtensionSelection,
				onExtClick = ::onExtClick,
				onLongClick = { viewModel.toggleSelection(it.name) },
				onClick = { if (viewModel.selectedPlugins.value.isNotEmpty()) viewModel.toggleSelection(it.name) },
				isSelected = { viewModel.isSelected(it.name) },
				isExtSelected = viewModel::isExtensionSelected,
			)
		with(binding.recyclerView) {
			setHasFixedSize(true)
			layoutManager = LinearLayoutManager(context)
			adapter = pluginsAdapter
		}

		val onBack =
			object : OnBackPressedCallback(false) {
				override fun handleOnBackPressed() = viewModel.clearSelection()
			}
		requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, onBack)
		binding.fabImport.setOnClickListener { showImportDialog() }

		viewLifecycleOwner.lifecycleScope.launch { viewModel.content.collect { pluginsAdapter?.emit(it) } }
		viewModel.operationResult.observeEvent(viewLifecycleOwner) { result ->
			viewBinding?.root?.let { Snackbar.make(it, result.messageResId, Snackbar.LENGTH_SHORT).show() }
		}
		viewLifecycleOwner.lifecycleScope.launch {
			viewModel.selectedPlugins.collect { selected ->
				val isSelection = selected.isNotEmpty()
				onBack.isEnabled = isSelection
				val bar = (activity as? androidx.appcompat.app.AppCompatActivity)?.supportActionBar
				if (isSelection) {
					(activity as? SettingsActivity)?.setSectionTitle(selected.size.toString())
					bar?.setHomeAsUpIndicator(androidx.appcompat.R.drawable.abc_ic_clear_material)
				} else {
					(activity as? SettingsActivity)?.setSectionTitle(getString(R.string.manage_plugins))
					bar?.setHomeAsUpIndicator(null)
				}
				activity?.invalidateOptionsMenu()
				pluginsAdapter?.notifyDataSetChanged()
			}
		}

		addMenuProvider(
			PluginsMenuProvider(
				appBarOwner = activity as? AppBarOwner,
				isSelectionMode = { viewModel.selectedPlugins.value.isNotEmpty() },
				onClearSelection = viewModel::clearSelection,
				onDeleteClick = ::showDeleteConfirm,
				onSearchQueryChanged = viewModel::setQuery,
			),
		)
	}

	override fun onApplyWindowInsets(
		v: View,
		insets: WindowInsetsCompat,
	): WindowInsetsCompat {
		val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
		val isTablet = !resources.getBoolean(R.bool.is_tablet)
		val isMaster = container?.id == R.id.container_master
		val startPad = if (isTablet && !isMaster) 0 else bars.start(v)
		val endPad = if (isTablet && isMaster) 0 else bars.end(v)
		v.setPaddingRelative(startPad, 0, endPad, bars.bottom)
		return WindowInsetsCompat
			.Builder(insets)
			.setInsets(WindowInsetsCompat.Type.systemBars(), Insets.NONE)
			.build()
	}

	override fun onResume() {
		super.onResume()
		(activity as? SettingsActivity)?.setSectionTitle(getString(R.string.manage_plugins))
		viewModel.runAutoUpdate()
		viewModel.refresh()
	}

	override fun onDestroyView() {
		(activity as? androidx.appcompat.app.AppCompatActivity)?.supportActionBar?.setHomeAsUpIndicator(null)
		viewBinding?.recyclerView?.adapter = null
		pluginsAdapter = null
		super.onDestroyView()
	}

	private fun showImportDialog() {
		val b = DialogImportBinding.inflate(layoutInflater)
		b.buttonFile.title = getString(R.string.load_from_storage)
		b.buttonFile.subtitle = getString(R.string.load_storage_summary)
		b.buttonFile.setIconResource(R.drawable.ic_storage)
		b.buttonDir.title = getString(R.string.import_from_github)
		b.buttonDir.subtitle = getString(R.string.import_github_summary)
		b.buttonDir.setIconResource(R.drawable.ic_open_external)

		val dialog =
			buildAlertDialog(requireContext()) {
				setTitle(R.string._import)
				setView(b.root)
				setNegativeButton(android.R.string.cancel, null)
			}
		b.buttonFile.setOnClickListener {
			dialog.dismiss()
			launcher.launch(PluginFileLoader.SUPPORTED_MIME_TYPES)
		}
		b.buttonDir.setOnClickListener {
			dialog.dismiss()
			viewModel.importUrl(
				askInput = { askText(R.string.import_from_github, "", R.string.import_github_summary) },
				askOverwrite = ::askOverwrite,
			)
		}
		dialog.show()
	}

	private fun onRenameClick(item: PluginManageItem.Plugin) {
		viewLifecycleOwner.lifecycleScope.launch {
			val name = askText(R.string.rename, item.displayName, R.string.plugin_name)
			if (!name.isNullOrBlank()) viewModel.rename(item, name)
		}
	}

	private fun onExtClick(item: PluginManageItem.Extension) {
		if (viewModel.selectedPlugins.value.isNotEmpty()) {
			viewModel.toggleExtensionSelection(item)
		} else {
			router.openPluginCatalog(item.repositoryUrl, item.displayName)
		}
	}

	private fun showDeleteConfirm() {
		val count = viewModel.selectedPlugins.value.size
		val items = resources.getQuantityString(R.plurals.items, count, count)
		buildAlertDialog(requireContext()) {
			setTitle(R.string.delete_plugin)
			setMessage(getString(R.string.confirm_delete_plugin, items))
			setNegativeButton(android.R.string.cancel, null)
			setPositiveButton(R.string.delete) { _, _ -> viewModel.delete() }
		}.show()
	}

	private fun onExtRenameClick(item: PluginManageItem.Extension) {
		viewLifecycleOwner.lifecycleScope.launch {
			val name = askText(R.string.rename, item.displayName, R.string.plugin_name)
			if (!name.isNullOrBlank()) viewModel.renameExtension(item, name)
		}
	}

	private suspend fun askOverwrite(fileName: String): Boolean =
		withContext(Dispatchers.Main) {
			suspendCancellableCoroutine { cont ->
				val d =
					buildAlertDialog(requireContext(), true) {
						setIcon(R.drawable.ic_replace)
						setTitle(R.string.overwrite_plugin)
						setMessage(getString(R.string.overwrite_plugin_summary, fileName))
						setNegativeButton(android.R.string.cancel) { _, _ -> if (cont.isActive) cont.resume(false) }
						setPositiveButton(R.string.overwrite) { _, _ -> if (cont.isActive) cont.resume(true) }
					}
				d.setOnCancelListener { if (cont.isActive) cont.resume(false) }
				cont.invokeOnCancellation { d.dismiss() }
				d.show()
			}
		}

	private suspend fun askText(
		titleRes: Int,
		defaultValue: String,
		hintRes: Int?,
	): String? =
		withContext(Dispatchers.Main) {
			suspendCancellableCoroutine { cont ->
				lateinit var input: android.widget.EditText
				val d =
					buildAlertDialog(requireContext()) {
						input = setEditText(InputType.TYPE_CLASS_TEXT, singleLine = true)
						input.setText(defaultValue)
						if (hintRes != null) input.hint = getString(hintRes)
						setTitle(titleRes)
						setNegativeButton(android.R.string.cancel) { _, _ -> if (cont.isActive) cont.resume(null) }
						setPositiveButton(android.R.string.ok) { _, _ -> if (cont.isActive) cont.resume(input.text?.toString()) }
					}
				d.setOnCancelListener { if (cont.isActive) cont.resume(null) }
				d.setOnDismissListener { input.setCursorVisible(false) }
				cont.invokeOnCancellation {
					input.post {
						input.setCursorVisible(false)
						if (d.isShowing) d.dismiss()
					}
				}
				d.show()
			}
		}
}
