package org.draken.usagi.settings.about

import android.os.Bundle
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import androidx.activity.viewModels
import androidx.core.text.buildSpannedString
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import io.noties.markwon.Markwon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.draken.usagi.R
import org.draken.usagi.core.github.AppVersion
import org.draken.usagi.core.nav.router
import org.draken.usagi.core.ui.BaseActivity
import org.draken.usagi.core.util.FileSize
import org.draken.usagi.core.util.ext.consumeAllSystemBarsInsets
import org.draken.usagi.core.util.ext.getDisplayMessage
import org.draken.usagi.core.util.ext.observe
import org.draken.usagi.core.util.ext.observeEvent
import org.draken.usagi.core.util.ext.showOrHide
import org.draken.usagi.core.util.ext.systemBarsInsets
import org.draken.usagi.core.util.ext.textAndVisible
import org.draken.usagi.databinding.ActivityAppUpdateBinding

@AndroidEntryPoint
class AppUpdateActivity :
	BaseActivity<ActivityAppUpdateBinding>(),
	View.OnClickListener {
	private val viewModel: AppUpdateViewModel by viewModels()

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(ActivityAppUpdateBinding.inflate(layoutInflater))
		viewModel.nextVersion.observe(this, ::onNextVersionChanged)
		viewBinding.buttonCancel.setOnClickListener(this)
		viewBinding.buttonUpdate.setOnClickListener(this)
		viewModel.isLoading.observe(this, ::onProgressChanged)
		viewModel.onError.observeEvent(this, ::onError)
	}

	override fun onApplyWindowInsets(
		v: View,
		insets: WindowInsetsCompat,
	): WindowInsetsCompat {
		val barsInsets = insets.systemBarsInsets
		viewBinding.root.updatePadding(top = barsInsets.top)
		viewBinding.dockedToolbarChild.updateLayoutParams<MarginLayoutParams> {
			leftMargin = barsInsets.left
			rightMargin = barsInsets.right
			bottomMargin = barsInsets.bottom
		}
		viewBinding.scrollView.updatePadding(
			left = barsInsets.left,
			right = barsInsets.right,
		)
		return insets.consumeAllSystemBarsInsets()
	}

	override fun onClick(v: View) {
		when (v.id) {
			R.id.button_cancel -> finishAfterTransition()
			R.id.button_update -> openInBrowser()
		}
	}

	private suspend fun onNextVersionChanged(version: AppVersion?) {
		viewBinding.buttonUpdate.isEnabled = version != null && !viewModel.isLoading.value
		if (version == null) {
			viewBinding.textViewContent.setText(R.string.loading_)
			return
		}
		val markwon = Markwon.create(this)
		val message =
			withContext(Dispatchers.Default) {
				buildSpannedString {
					append(getString(R.string.new_version_s, version.name))
					appendLine()
					append(getString(R.string.size_s, FileSize.BYTES.format(this@AppUpdateActivity, version.apkSize)))
					appendLine()
					appendLine()
					append(markwon.toMarkdown(version.description))
				}
			}
		markwon.setParsedMarkdown(viewBinding.textViewContent, message)
	}

	private fun openInBrowser() {
		val latestVersion = viewModel.nextVersion.value ?: return
		if (!router.openExternalBrowser(latestVersion.url, getString(R.string.open_in_browser))) {
			Snackbar.make(viewBinding.scrollView, R.string.operation_not_supported, Snackbar.LENGTH_SHORT).show()
		}
	}

	private fun onProgressChanged(isLoading: Boolean) {
		val indicator = viewBinding.progressBar
		indicator.isIndeterminate = true
		indicator.showOrHide(isLoading)
		viewBinding.buttonUpdate.isEnabled = !isLoading && viewModel.nextVersion.value != null
	}

	private fun onError(e: Throwable) {
		viewBinding.textViewError.textAndVisible = e.getDisplayMessage(resources)
	}
}
