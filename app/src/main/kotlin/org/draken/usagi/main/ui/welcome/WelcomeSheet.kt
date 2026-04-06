package org.draken.usagi.main.ui.welcome

import android.Manifest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import org.draken.usagi.R
import org.draken.usagi.core.nav.router
import org.draken.usagi.core.ui.sheet.BaseAdaptiveSheet
import org.draken.usagi.core.util.ext.consume
import org.draken.usagi.core.util.ext.tryLaunch
import org.draken.usagi.databinding.SheetWelcomeBinding

@AndroidEntryPoint
class WelcomeSheet : BaseAdaptiveSheet<SheetWelcomeBinding>(), View.OnClickListener,
	ActivityResultCallback<Uri?> {

	private val backupSelectCall = registerForActivityResult(
		ActivityResultContracts.OpenDocument(),
		this,
	)

	private val notificationPermissionCall = registerForActivityResult(
		ActivityResultContracts.RequestPermission(),
	) { /* result handled silently — user can change later in system settings */ }

	override fun onCreateViewBinding(inflater: LayoutInflater, container: ViewGroup?): SheetWelcomeBinding {
		return SheetWelcomeBinding.inflate(inflater, container, false)
	}

	override fun onViewBindingCreated(binding: SheetWelcomeBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		binding.chipBackup.setOnClickListener(this)
		binding.chipSync.setOnClickListener(this)

		// Request notification permission as soon as Welcome appears (Android 13+)
		requestNotificationPermission()
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val typeMask = WindowInsetsCompat.Type.systemBars()
		viewBinding?.scrollView?.updatePadding(
			bottom = insets.getInsets(typeMask).bottom,
		)
		return insets.consume(v, typeMask, bottom = true)
	}

	override fun onClick(v: View) {
		when (v.id) {
			R.id.chip_backup -> {
				if (!backupSelectCall.tryLaunch(arrayOf("*/*"))) {
					Snackbar.make(v, R.string.operation_not_supported, Snackbar.LENGTH_SHORT).show()
				}
			}

			R.id.chip_sync -> {
				val am = android.accounts.AccountManager.get(v.context)
				val accountType = getString(R.string.account_type_sync)
				am.addAccount(accountType, accountType, null, null, requireActivity(), null, null)
			}
		}
	}

	override fun onActivityResult(result: Uri?) {
		if (result != null) {
			router.showBackupRestoreDialog(result)
		}
	}

	private fun requestNotificationPermission() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			notificationPermissionCall.launch(Manifest.permission.POST_NOTIFICATIONS)
		}
	}
}
