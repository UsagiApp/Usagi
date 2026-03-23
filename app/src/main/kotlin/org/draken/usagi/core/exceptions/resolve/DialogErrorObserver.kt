package org.draken.usagi.core.exceptions.resolve

import android.content.DialogInterface
import android.view.View
import androidx.core.util.Consumer
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.draken.usagi.R
import org.draken.usagi.core.util.ext.getDisplayMessage
import org.draken.usagi.core.util.ext.isSerializable
import org.koitharu.kotatsu.parsers.exception.InputRequiredException
import org.koitharu.kotatsu.parsers.exception.ParseException

class DialogErrorObserver(
	host: View,
	fragment: Fragment?,
	resolver: ExceptionResolver?,
	private val onResolved: Consumer<Boolean>?,
) : ErrorObserver(host, fragment, resolver, onResolved) {

	constructor(
		host: View,
		fragment: Fragment?,
	) : this(host, fragment, null, null)

	override suspend fun emit(value: Throwable) {
		if (value is InputRequiredException && canResolve(value)) {
			val resolved = resolveAndWait(value)
			if (resolved) {
				onResolved?.accept(true)
				return
			}
			showErrorDialog(value, true)
			return
		}
		showErrorDialog(value, false)
	}

	private fun showErrorDialog(value: Throwable, isRetry: Boolean) {
		val listener = DialogListener(value)
		val dialogBuilder = MaterialAlertDialogBuilder(activity ?: host.context)
			.setMessage(value.getDisplayMessage(host.context.resources))
			.setNegativeButton(R.string.close, listener)
			.setOnCancelListener(listener)
		when {
			isRetry -> dialogBuilder.setPositiveButton(android.R.string.ok, listener)
			canResolve(value) -> dialogBuilder.setPositiveButton(ExceptionResolver.getResolveStringId(value), listener)
			value is ParseException -> {
				val router = router()
				if (router != null && value.isSerializable()) {
					dialogBuilder.setPositiveButton(R.string.details) { _, _ -> router.showErrorDialog(value) }
				}
			}
		}
		val dialog = dialogBuilder.create()
		if (activity != null) {
			dialog.setOwnerActivity(activity)
		}
		dialog.show()
	}

	private inner class DialogListener(
		private val error: Throwable,
	) : DialogInterface.OnClickListener, DialogInterface.OnCancelListener {

		override fun onClick(dialog: DialogInterface?, which: Int) {
			when (which) {
				DialogInterface.BUTTON_NEGATIVE -> onResolved?.accept(false)
				DialogInterface.BUTTON_POSITIVE -> resolve(error)
			}
		}

		override fun onCancel(dialog: DialogInterface?) {
			onResolved?.accept(false)
		}
	}
}
