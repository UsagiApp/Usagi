package org.draken.usagi.core.util

import kotlinx.coroutines.CoroutineExceptionHandler
import org.draken.usagi.core.util.ext.printStackTraceDebug
import org.draken.usagi.core.util.ext.report
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

class AcraCoroutineErrorHandler : AbstractCoroutineContextElement(CoroutineExceptionHandler),
	CoroutineExceptionHandler {

	override fun handleException(context: CoroutineContext, exception: Throwable) {
		exception.printStackTraceDebug()
		exception.report()
	}
}
