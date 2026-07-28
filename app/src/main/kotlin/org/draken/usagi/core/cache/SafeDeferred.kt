package org.draken.usagi.core.cache

import kotlinx.coroutines.Deferred

class SafeDeferred<T>(
	private val delegate: Deferred<Result<T>>,
) {
	suspend fun await(): T = delegate.await().getOrThrow()

	suspend fun awaitOrNull(): T? = delegate.await().getOrNull()

	fun cancel() {
		delegate.cancel()
	}
}
