package org.draken.usagi.scrobbling.discord.data

import android.content.Context
import android.util.Base64
import dagger.Reusable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody.Builder
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.internal.closeQuietly
import org.draken.usagi.R
import org.draken.usagi.core.network.BaseHttpClient
import org.draken.usagi.core.network.CommonHeaders
import org.draken.usagi.core.prefs.AppSettings
import org.draken.usagi.core.util.ext.ensureSuccess
import tsuki.util.await
import tsuki.util.parseRaw
import tsuki.util.runCatchingCancellable
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject

private const val SCHEME_MP = "mp:"
private const val REDIRECT_URI = "usagi://discord-auth"
private const val CATBOX_URI = "https://catbox.moe/user/api.php"
private const val UGUU_URI = "https://uguu.se/upload.php?output=text"

@Reusable
class DiscordRepository
	@Inject
	constructor(
		@ApplicationContext context: Context,
		private val settings: AppSettings,
		@BaseHttpClient private val httpClient: OkHttpClient,
	) {
		private val appId = context.getString(R.string.discord_app_id)

		suspend fun getMediaProxyUrl(file: File): String? {
			val fileBody = file.asRequestBody("image/*".toMediaTypeOrNull())
			val url =
				runCatchingCancellable {
					val req =
						Request
							.Builder()
							.url(UGUU_URI)
							.post(
								MultipartBody
									.Builder()
									.setType(MultipartBody.FORM)
									.addFormDataPart("files[]", "${file.name}.png", fileBody)
									.build(),
							).build()
					httpClient.newCall(req).await().use { if (it.isSuccessful) it.parseRaw().trim() else null }
				}.getOrNull()
			if (!url.isNullOrBlank()) return url
			val requestBody =
				MultipartBody
					.Builder()
					.setType(MultipartBody.FORM)
					.addFormDataPart("reqtype", "fileupload")
					.addFormDataPart("fileToUpload", file.name, fileBody)
					.build()
			val request =
				Request
					.Builder()
					.url(CATBOX_URI)
					.post(requestBody)
					.build()
			var response: okhttp3.Response? = null
			return try {
				response = httpClient.newCall(request).await()
				if (response.isSuccessful) response.parseRaw().trim() else null
			} catch (e: CancellationException) {
				throw e
			} catch (_: Exception) {
				null
			} finally {
				response?.closeQuietly()
			}
		}

		fun isMediaProxyUrl(url: String) = url.startsWith(SCHEME_MP)

		suspend fun checkToken(token: String): String {
			val request =
				Request
					.Builder()
					.url("https://discord.com/api/v10/users/@me")
					.header(CommonHeaders.AUTHORIZATION, token)
					.get()
					.build()
			val response = httpClient.newCall(request).await().ensureSuccess()
			val raw =
				try {
					response.parseRaw()
				} finally {
					response.closeQuietly()
				}
			val json = Json.parseToJsonElement(raw).jsonObject
			val globalName = json["global_name"]?.jsonPrimitive?.content
			val username = json["username"]?.jsonPrimitive?.content
			return globalName ?: username ?: ""
		}

		val oauthUrl: String
			get() {
				val verifier = UUID.randomUUID().toString() + UUID.randomUUID().toString()
				settings.discordCodeVerifier = verifier
				val challenge = generateCodeChallenge(verifier)
				val state = UUID.randomUUID().toString()
				return "discord://action/oauth2/authorize?client_id=$appId" +
					"&scope=openid%20sdk.social_layer_presence" +
					"&response_type=code" +
					"&state=$state" +
					"&code_challenge=$challenge" +
					"&code_challenge_method=S256" +
					"&redirect_uri=$REDIRECT_URI"
			}

		val oauthFallbackUrl: String
			get() =
				"https://discord.com/oauth2/authorize?client_id=$appId" +
					"&scope=openid%20sdk.social_layer_presence" +
					"&response_type=code&redirect_uri=$REDIRECT_URI" +
					"&code_challenge=${generateCodeChallenge(settings.discordCodeVerifier.orEmpty())}" +
					"&code_challenge_method=S256"

		suspend fun authorize(code: String) {
			val verifier = settings.discordCodeVerifier ?: throw IllegalStateException("Code verifier is missing")
			val request =
				Request
					.Builder()
					.url("https://discord.com/api/v10/oauth2/token")
					.post(
						Builder()
							.add("client_id", appId)
							.add("grant_type", "authorization_code")
							.add("code", code)
							.add("redirect_uri", REDIRECT_URI)
							.add("code_verifier", verifier)
							.build(),
					).build()

			val response = httpClient.newCall(request).await().ensureSuccess()
			val raw =
				try {
					response.parseRaw()
				} finally {
					response.closeQuietly()
				}
			val json = Json.parseToJsonElement(raw).jsonObject
			val accessToken = json["access_token"]?.jsonPrimitive?.content
			val tokenType = json["token_type"]?.jsonPrimitive?.content ?: "Bearer"
			val refreshToken = json["refresh_token"]?.jsonPrimitive?.content
			settings.discordToken = "$tokenType $accessToken"
			settings.discordRefreshToken = refreshToken
			settings.discordCodeVerifier = null
		}

		suspend fun refreshToken() {
			val refreshToken = settings.discordRefreshToken ?: throw IllegalStateException("Refresh token is missing")
			val request =
				Request
					.Builder()
					.url("https://discord.com/api/v10/oauth2/token")
					.post(
						Builder()
							.add("client_id", appId)
							.add("grant_type", "refresh_token")
							.add("refresh_token", refreshToken)
							.build(),
					).build()

			val response = httpClient.newCall(request).await().ensureSuccess()
			val raw =
				try {
					response.parseRaw()
				} finally {
					response.closeQuietly()
				}
			val json = Json.parseToJsonElement(raw).jsonObject
			val accessToken = json["access_token"]?.jsonPrimitive?.content
			val tokenType = json["token_type"]?.jsonPrimitive?.content ?: "Bearer"
			val newRefreshToken = json["refresh_token"]?.jsonPrimitive?.content

			settings.discordToken = "$tokenType $accessToken"
			settings.discordRefreshToken = newRefreshToken
		}

		private fun generateCodeChallenge(verifier: String): String {
			val bytes = verifier.toByteArray(Charsets.US_ASCII)
			val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
			return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
		}
	}
