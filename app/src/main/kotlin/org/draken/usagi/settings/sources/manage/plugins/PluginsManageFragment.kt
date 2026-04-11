package org.draken.usagi.settings.sources.manage.plugins

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.draken.usagi.R
import org.draken.usagi.core.ui.BaseFragment
import org.draken.usagi.core.ui.dialog.buildAlertDialog
import org.draken.usagi.core.ui.dialog.setEditText
import org.draken.usagi.core.ui.util.RecyclerViewOwner
import org.draken.usagi.core.util.ext.addMenuProvider
import org.draken.usagi.core.util.ext.container
import org.draken.usagi.core.util.ext.end
import org.draken.usagi.core.util.ext.observe
import org.draken.usagi.core.util.ext.start
import org.draken.usagi.databinding.DialogImportBinding
import org.draken.usagi.databinding.FragmentSettingsSourcesBinding
import org.draken.usagi.main.ui.owners.AppBarOwner
import kotlin.coroutines.resume

@AndroidEntryPoint
class PluginsManageFragment :
	BaseFragment<FragmentSettingsSourcesBinding>(),
	RecyclerViewOwner {

	private val viewModel by viewModels<PluginsManageViewModel>()
	private var pluginsAdapter: PluginsManageAdapter? = null

	private val importJarLauncher = registerForActivityResult(
		ActivityResultContracts.OpenDocument()
	) { uri ->
		if (uri != null && isAdded) {
			viewLifecycleOwner.lifecycleScope.launch {
				importFromJar(uri)
			}
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

	override fun onViewBindingCreated(binding: FragmentSettingsSourcesBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		pluginsAdapter = PluginsManageAdapter(::onDeleteClick, ::onUpdateClick)
		with(binding.recyclerView) {
			setHasFixedSize(true)
			layoutManager = LinearLayoutManager(context)
			adapter = pluginsAdapter
		}
		viewModel.content.observe(viewLifecycleOwner, checkNotNull(pluginsAdapter))
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
		viewModel.refresh()
	}

	override fun onDestroyView() {
		pluginsAdapter = null
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
			importJarLauncher.launch(SUPPORTED_MIME_TYPES)
		}
		binding.buttonDir.setOnClickListener {
			dialog.dismiss()
			viewLifecycleOwner.lifecycleScope.launch { importFromGithub() }
		}
		dialog.show()
	}

	private suspend fun importFromJar(uri: android.net.Uri) {
		val appCtx = requireContext().applicationContext
		val originalName = DocumentFile.fromSingleUri(appCtx, uri)?.name
			?: "plugin_${System.currentTimeMillis()}.jar"
		val pluginName = askText(
			titleRes = R.string.set_plugin_name,
			defaultValue = originalName.removeSuffix(".jar"),
			hintRes = R.string.plugin_name,
		)?.trim().orEmpty()

		if (pluginName.isBlank()) return
		val fileName = viewModel.sanitizeJarFileName(pluginName)
		if (viewModel.isInstalled(fileName) && !askOverwrite(fileName)) {
			return
		}
		val success = viewModel.importFromUri(uri, fileName)
		showImportResult(success)
	}

	private suspend fun importFromGithub() {
		val input = askText(R.string.import_from_github, "", null)
			?.trim()?.takeIf { it.isNotBlank() } ?: return

		val release = viewModel.resolveGithubRelease(input)
		if (release == null) {
			showImportResult(false)
			return
		}

		val fileName = viewModel.sanitizeJarFileName(release.fileName)
		if (viewModel.isInstalled(fileName) && !askOverwrite(fileName)) return

		val success = viewModel.importFromGithub(release, fileName)
		showImportResult(success)
	}

	private fun onDeleteClick(item: PluginManageItem.Plugin) {
		buildAlertDialog(requireContext()) {
			setTitle(R.string.delete_plugin)
			setMessage(getString(R.string.confirm_delete_plugin, item.jarName))
			setNegativeButton(android.R.string.cancel, null)
			setPositiveButton(R.string.delete) { _, _ ->
				viewLifecycleOwner.lifecycleScope.launch {
					val success = viewModel.deletePlugin(item)
					val binding = viewBinding ?: return@launch
					Snackbar.make(
						binding.recyclerView,
						if (success) getString(R.string.deleted_plugin, item.jarName)
							else getString(R.string.load_failed),
						Snackbar.LENGTH_SHORT,
					).show()
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

	private suspend fun askOverwrite(fileName: String): Boolean = suspendCancellableCoroutine { cont ->
		val dialog = buildAlertDialog(requireContext()) {
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

	private suspend fun askText(
		titleRes: Int,
		defaultValue: String,
		hintRes: Int?,
	): String? = suspendCancellableCoroutine { cont ->
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

	private fun showImportResult(isSuccess: Boolean) {
		val binding = viewBinding ?: return
		Snackbar.make(
			binding.recyclerView,
			if (isSuccess) R.string.load_success else R.string.load_failed,
			Snackbar.LENGTH_LONG,
		).show()
	}

	private companion object {
		val SUPPORTED_MIME_TYPES = arrayOf(
			"application/java-archive",
			"application/x-java-archive",
			"application/vnd.android.package-archive",
			"application/octet-stream",
		)
	}
}
