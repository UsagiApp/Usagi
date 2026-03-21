package org.draken.usagi.core.network

import okhttp3.Interceptor
import okhttp3.MultipartBody
import okhttp3.Response
import okio.IOException
import org.draken.usagi.core.exceptions.WrapperIOException
import org.draken.usagi.core.network.CommonHeaders.CONTENT_ENCODING

class GZipInterceptor : Interceptor {

	override fun intercept(chain: Interceptor.Chain): Response = try {
		val request = chain.request()
		if (request.body is MultipartBody) {
			chain.proceed(request)
		} else {
			val newRequest = request.newBuilder()
			newRequest.addHeader(CONTENT_ENCODING, "gzip")
			chain.proceed(newRequest.build())
		}
	} catch (e: IOException) {
		throw e
	} catch (e: Exception) {
		throw WrapperIOException(e)
	}
}
