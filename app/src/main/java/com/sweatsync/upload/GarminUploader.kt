package com.sweatsync.upload

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.CookieHandler
import java.net.CookieManager

/**
 * UNOFFICIAL upload to Garmin Connect, mimicking the mobile app login.
 * It may break if Garmin changes the flow. Only the refresh token is stored, never the password.
 */
class GarminUploader(context: Context) : UploadTarget {

    private val prefs = context.applicationContext.getSharedPreferences("garmin", Context.MODE_PRIVATE)

    private var mfaMethod = "email"
    private var mfaServiceUrl = IOS_SERVICE
    private var loginParams = emptyMap<String, String>()

    override val id = "garmin"
    override val label = "Garmin"

    fun hasSession() = prefs.getString(KEY_REFRESH, null) != null

    override fun isLinked() = hasSession()

    override fun logout() = prefs.edit().clear().apply()

    fun login(email: String, password: String): UploadResult = guard {
        CookieHandler.setDefault(CookieManager())
        val signin = "$SSO/mobile/sso/en_US/sign-in"
        val params = mapOf("clientId" to IOS_CLIENT, "service" to IOS_SERVICE)
        val page = Http.request("GET", "$signin?${Http.formEncode(params)}", mapOf(
            "User-Agent" to IOS_UA,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9"
        ))
        if (page.code == 429 || page.code == 403) return@guard blocked(page.code)
        Thread.sleep(2500)

        loginParams = mapOf("clientId" to IOS_CLIENT, "locale" to "en-US", "service" to IOS_SERVICE)
        mfaHeaders = mapOf(
            "User-Agent" to IOS_UA,
            "Accept" to "application/json, text/plain, */*",
            "Accept-Language" to "en-US,en;q=0.9",
            "Content-Type" to "application/json",
            "Origin" to SSO,
            "Referer" to "$signin?${Http.formEncode(params)}"
        )
        val body = JSONObject()
            .put("username", email).put("password", password)
            .put("rememberMe", true).put("captchaToken", "").toString()
        val r = Http.request("POST", "$SSO/mobile/api/login?${Http.formEncode(loginParams)}", mfaHeaders, body.toByteArray())
        handleSsoHttpResponseonse(r)
    }

    fun submitMfa(code: String): UploadResult = guard {
        val body = JSONObject()
            .put("mfaMethod", mfaMethod).put("mfaVerificationCode", code)
            .put("rememberMyBrowser", true).put("reconsentList", JSONArray())
            .put("mfaSetup", false).toString()
        val r = Http.request("POST", "$SSO/mobile/api/mfa/verifyCode?${Http.formEncode(loginParams)}", mfaHeaders, body.toByteArray())
        handleSsoHttpResponseonse(r)
    }

    private var mfaHeaders = emptyMap<String, String>()

    private fun handleSsoHttpResponseonse(r: HttpResponse): UploadResult {
        if (r.code == 429 || r.code == 403) return blocked(r.code)
        val json = try { JSONObject(r.text) } catch (e: Exception) {
            return UploadResult.Error("Unexpected response from Garmin (HTTP ${r.code})")
        }
        return when (json.optJSONObject("responseStatus")?.optString("type")) {
            "SUCCESSFUL" -> exchangeTicket(json.getString("serviceTicketId"), mfaServiceUrl)
            "MFA_REQUIRED" -> {
                mfaMethod = json.optJSONObject("customerMfaInfo")?.optString("mfaLastMethodUsed", "email") ?: "email"
                UploadResult.NeedMfa
            }
            "INVALID_USERNAME_PASSWORD" -> UploadResult.Error("Wrong email or password")
            "CAPTCHA_REQUIRED" -> UploadResult.Error("Garmin asks for a captcha; try again later or use the website")
            else -> UploadResult.Error("Garmin rejected the login")
        }
    }

    private fun exchangeTicket(ticket: String, serviceUrl: String): UploadResult {
        for (clientId in DI_CLIENT_IDS) {
            val r = diPost(clientId, mapOf(
                "client_id" to clientId,
                "service_ticket" to ticket,
                "grant_type" to DI_GRANT,
                "service_url" to serviceUrl
            ))
            if (r.code == 429) return blocked(429)
            if (r.code !in 200..299) continue
            val json = try { JSONObject(r.text) } catch (e: Exception) { continue }
            val refresh = json.optString("refresh_token")
            if (refresh.isEmpty()) continue
            prefs.edit()
                .putString(KEY_REFRESH, refresh)
                .putString(KEY_CLIENT, jwtClientId(json.optString("access_token")) ?: clientId)
                .apply()
            return UploadResult.Ok
        }
        return UploadResult.Error("Garmin rejected the token exchange")
    }

    /** Returns the access token, or null if the user must sign in again. */
    private fun accessToken(): Pair<String?, UploadResult?> {
        val refresh = prefs.getString(KEY_REFRESH, null)
        val clientId = prefs.getString(KEY_CLIENT, null)
        if (refresh == null || clientId == null) return Pair(null, UploadResult.NeedLogin)
        val r = diPost(clientId, mapOf(
            "grant_type" to "refresh_token",
            "client_id" to clientId,
            "refresh_token" to refresh
        ))
        if (r.code == 429) return Pair(null, blocked(429))
        if (r.code !in 200..299) {
            logout()
            return Pair(null, UploadResult.NeedLogin)
        }
        val json = JSONObject(r.text)
        json.optString("refresh_token").takeIf { it.isNotEmpty() }?.let {
            prefs.edit().putString(KEY_REFRESH, it).apply()
        }
        return Pair(json.getString("access_token"), null)
    }

    override fun upload(fit: ByteArray, fileName: String): UploadResult = guard {
        val (token, err) = accessToken()
        if (token == null) return@guard err!!

        val body = Http.multipart("file", fileName, fit)

        val r = Http.request("POST", "$API/upload-service/upload/fit", nativeHeaders(mapOf(
            "Authorization" to "Bearer $token",
            "Content-Type" to body.contentType,
            "NK" to "NT",
            "origin" to SSO,
            "User-Agent" to "GCM-iOS-5.7.2.1"
        )), body.bytes)
        when {
            r.code == 409 -> UploadResult.Duplicate
            r.code in 200..299 -> UploadResult.Ok
            r.code == 401 -> { logout(); UploadResult.NeedLogin }
            r.code == 429 -> blocked(429)
            else -> UploadResult.Error("Garmin answered HTTP ${r.code}")
        }
    }

    private fun blocked(code: Int) =
        UploadResult.Error("Garmin blocked the request (HTTP $code). Wait a few minutes or use the website.")

    private fun guard(block: () -> UploadResult): UploadResult = try {
        block()
    } catch (e: Exception) {
        UploadResult.Error("Could not reach Garmin: ${e.message}")
    }

    private fun diPost(clientId: String, form: Map<String, String>): HttpResponse {
        val basic = Base64.encodeToString("$clientId:".toByteArray(), Base64.NO_WRAP)
        return Http.request("POST", DI_TOKEN_URL, nativeHeaders(mapOf(
            "Authorization" to "Basic $basic",
            "Accept" to "application/json,text/html;q=0.9,*/*;q=0.8",
            "Content-Type" to "application/x-www-form-urlencoded",
            "Cache-Control" to "no-cache"
        )), Http.formEncode(form).toByteArray())
    }

    private fun nativeHeaders(extra: Map<String, String>) = mapOf(
        "User-Agent" to "GCM-Android-5.23",
        "X-Garmin-User-Agent" to "com.garmin.android.apps.connectmobile/5.23; ; Google/sdk_gphone64_arm64/google; Android/33; Dalvik/2.1.0",
        "X-Garmin-Paired-App-Version" to "10861",
        "X-Garmin-Client-Platform" to "Android",
        "X-App-Ver" to "10861",
        "X-Lang" to "en",
        "X-GCExperience" to "GC5",
        "Accept-Language" to "en-US,en;q=0.9"
    ) + extra

    private fun jwtClientId(jwt: String): String? = try {
        val payload = String(Base64.decode(jwt.split(".")[1], Base64.URL_SAFE))
        JSONObject(payload).optString("client_id").takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val SSO = "https://sso.garmin.com"
        const val API = "https://connectapi.garmin.com"
        const val IOS_CLIENT = "GCM_IOS_DARK"
        const val IOS_SERVICE = "https://mobile.integration.garmin.com/gcm/ios"
        const val IOS_UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_7 like Mac OS X) " +
            "AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148"
        const val DI_TOKEN_URL = "https://diauth.garmin.com/di-oauth2-service/oauth/token"
        const val DI_GRANT = "https://connectapi.garmin.com/di-oauth2-service/oauth/grant/service_ticket"
        val DI_CLIENT_IDS = listOf(
            "GARMIN_CONNECT_MOBILE_ANDROID_DI_2025Q2",
            "GARMIN_CONNECT_MOBILE_ANDROID_DI_2024Q4",
            "GARMIN_CONNECT_MOBILE_ANDROID_DI",
            "GARMIN_CONNECT_MOBILE_IOS_DI"
        )
        const val KEY_REFRESH = "refresh"
        const val KEY_CLIENT = "client"
    }
}
