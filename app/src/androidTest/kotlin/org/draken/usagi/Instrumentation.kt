package org.draken.usagi

import android.app.Instrumentation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

suspend fun Instrumentation.awaitForIdle() = suspendCoroutine<Unit> { cont ->
	waitForIdle { cont.resume(Unit) }
}
