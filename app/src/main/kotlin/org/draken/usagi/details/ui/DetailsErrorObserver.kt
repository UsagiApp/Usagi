package org.draken.usagi.details.ui

import com.google.android.material.snackbar.Snackbar
import org.draken.usagi.R
import org.draken.usagi.core.exceptions.UnsupportedSourceException
import org.draken.usagi.core.exceptions.resolve.ErrorObserver
import org.draken.usagi.core.exceptions.resolve.ExceptionResolver
import org.draken.usagi.core.util.ext.getDisplayMessage
import org.draken.usagi.core.util.ext.isNetworkError
import org.draken.usagi.core.util.ext.isSerializable
import org.koitharu.kotatsu.parsers.exception.NotFoundException
import org.koitharu.kotatsu.parsers.exception.ParseException

class DetailsErrorObserver(
	override val activity: androidx.fragment.app.FragmentActivity,
	private val snackbarHost: android.view.View,
	private val bottomSheet: android.view.View?,
	private val viewModel: DetailsViewModel,
	resolver: ExceptionResolver?,
) : ErrorObserver(
	snackbarHost, null, resolver,
	{ isResolved ->
		if (isResolved) {
			viewModel.reload()
		}
	},
) {

	override suspend fun emit(value: Throwable) {
		val snackbar = Snackbar.make(host, value.getDisplayMessage(host.context.resources), Snackbar.LENGTH_SHORT)
		snackbar.setAnchorView(bottomSheet)
		if (value is NotFoundException || value is UnsupportedSourceException) {
			snackbar.duration = Snackbar.LENGTH_INDEFINITE
		}
		when {
			canResolve(value) -> {
				snackbar.setAction(ExceptionResolver.getResolveStringId(value)) {
					resolve(value)
				}
			}

			value is ParseException -> {
				val router = router()
				if (router != null && value.isSerializable()) {
					snackbar.setAction(R.string.details) {
						router.showErrorDialog(value)
					}
				}
			}

			value.isNetworkError() -> {
				snackbar.setAction(R.string.try_again) {
					viewModel.reload()
				}
			}
		}
		snackbar.show()
	}
}
