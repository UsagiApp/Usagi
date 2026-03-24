package org.koitharu.kotatsu.parsers.network.utils

import okhttp3.Call
import org.koitharu.kotatsu.parsers.exception.TooManyRequestExceptions
import java.io.IOException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

public class RateLimiter(
	private val permits: Int,
	private val periodMs: Long
) {
	private val timestamps = ArrayDeque<Long>(permits)
	private val lock = ReentrantLock(true) // fair lock

	public fun acquire(call: Call, url: String = ""): Long = lock.withLock {
		val now = System.currentTimeMillis()

		while (timestamps.isNotEmpty() && now - timestamps.first() >= periodMs) {
			timestamps.removeFirst()
		}

		if (timestamps.size >= permits) {
			if (call.isCanceled()) throw IOException("Canceled")
			val oldestRequest = timestamps.first()
			val waitTime = periodMs - (now - oldestRequest)

			if (waitTime > 0) {
				throw TooManyRequestExceptions(url, waitTime)
			}

			val current = System.currentTimeMillis()
			while (timestamps.isNotEmpty() && current - timestamps.first() >= periodMs) {
				timestamps.removeFirst()
			}
		}

		val timestamp = System.currentTimeMillis()
		timestamps.addLast(timestamp)
		return timestamp
	}

	public fun release(timestamp: Long): Boolean = lock.withLock {
		timestamps.remove(timestamp)
	}
}
