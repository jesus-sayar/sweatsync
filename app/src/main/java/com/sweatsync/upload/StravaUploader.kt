package com.sweatsync.upload

import android.content.Context
import org.json.JSONArray

/**
 * UNOFFICIAL upload to Strava through its website, using the session the user created by signing in
 * (see StravaLoginActivity). Strava does not document these endpoints, so this may break at any time.
 * Only the session cookies are stored, never the password.
 */
class StravaUploader(context: Context) : UploadTarget {

    private val prefs = context.applicationContext.getSharedPreferences("strava", Context.MODE_PRIVATE)

    override val id = "strava"
    override val label = "Strava"

    override fun isLinked() = cookies().containsKey(SESSION_COOKIE)

    fun saveSession(cookieHeader: String) {
        prefs.edit().putString(KEY_COOKIES, cookieHeader).apply()
    }

    override fun logout() = prefs.edit().clear().apply()

    override fun upload(fit: ByteArray, fileName: String): UploadResult = try {
        doUpload(fit, fileName)
    } catch (e: Exception) {
        UploadResult.Error("Could not reach Strava: ${e.message}")
    }

    private fun doUpload(fit: ByteArray, fileName: String): UploadResult {
        if (!isLinked()) return UploadResult.NeedLogin

        val page = request("GET", "$BASE/upload/select", mapOf("Accept" to "text/html"))
        if (page.code == 429) return blocked()
        if (page.code in 300..399 || page.code == 401 || page.code == 403) return expired()
        val csrf = CSRF.find(page.text)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }
        if (page.code !in 200..299 || csrf.isNullOrEmpty()) return expired()

        val body = Http.multipart("files[]", fileName, fit, mapOf("_method" to "post", "authenticity_token" to csrf))
        val xhr = mapOf(
            "Accept" to "application/json, text/javascript, */*; q=0.01",
            "X-CSRF-Token" to csrf,
            "X-Requested-With" to "XMLHttpRequest",
            "Origin" to BASE,
            "Referer" to "$BASE/upload/select"
        )
        val upload = request("POST", "$BASE/upload/files", xhr + ("Content-Type" to body.contentType), body.bytes)
        when {
            upload.code == 429 -> return blocked()
            upload.code == 401 || upload.code == 403 || upload.code == 422 || upload.code in 300..399 -> return expired()
            upload.code !in 200..299 -> return UploadResult.Error("Strava answered HTTP ${upload.code}: ${snippet(upload.text)}")
        }
        val uploadId = try {
            JSONArray(upload.text).getJSONObject(0).get("id").toString()
        } catch (e: Exception) {
            return UploadResult.Error("Unexpected response from Strava: ${snippet(upload.text)}")
        }
        return waitForProcessing(uploadId, xhr)
    }

    /** Polls Strava until the activity exists, the upload fails, or the wait runs out. */
    private fun waitForProcessing(uploadId: String, headers: Map<String, String>): UploadResult {
        var last = ""
        repeat(POLLS) {
            Thread.sleep(POLL_INTERVAL_MS)
            val r = request("GET", "$BASE/upload/progress.json?ids%5B%5D=$uploadId", headers)
            last = "HTTP ${r.code} ${snippet(r.text)}"
            if (r.code !in 200..299) return@repeat
            val item = try { JSONArray(r.text).getJSONObject(0) } catch (e: Exception) { return@repeat }
            val workflow = item.optString("workflow")
            // Strava reports the new activity as a nested object ("activity": {"id": ...}) once it is done.
            val activityId = item.optJSONObject("activity")?.opt("id")?.toString() ?: item.opt("activity_id")?.toString()
            if (workflow == "success" || (!activityId.isNullOrEmpty() && activityId != "null" && activityId != "0")) {
                return UploadResult.Ok
            }
            val error = if (item.isNull("error")) "" else item.optString("error")
            if (error.isNotEmpty() || workflow == "error" || workflow == "failed") {
                val text = error.ifEmpty { "Strava could not process the file ($workflow)" }
                return if (text.contains("duplicate", ignoreCase = true)) UploadResult.Duplicate else UploadResult.Error(text)
            }
        }
        return UploadResult.Error("Strava did not confirm the upload ($last)")
    }

    private fun expired(): UploadResult {
        logout()
        return UploadResult.NeedLogin
    }

    private fun blocked() = UploadResult.Error("Strava blocked the request. Wait a few minutes and try again.")

    private fun snippet(text: String) = text.replace(WHITESPACE, " ").take(SNIPPET_CHARS)

    // ---- Session cookies ----------------------------------------------------------------------

    private fun cookies(): MutableMap<String, String> {
        val map = LinkedHashMap<String, String>()
        prefs.getString(KEY_COOKIES, null)?.split(";")?.forEach {
            val i = it.indexOf('=')
            if (i > 0) map[it.substring(0, i).trim()] = it.substring(i + 1).trim()
        }
        return map
    }

    /** A request carrying the session; cookies Strava rotates are kept so the session stays fresh. */
    private fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray? = null): HttpResponse {
        val jar = cookies()
        val response = Http.request(
            method, url,
            mapOf("User-Agent" to USER_AGENT, "Cookie" to jar.entries.joinToString("; ") { "${it.key}=${it.value}" }) + headers,
            body,
            followRedirects = false
        )
        response.header("Set-Cookie").forEach { line ->
            val pair = line.substringBefore(';')
            val i = pair.indexOf('=')
            if (i > 0) jar[pair.substring(0, i).trim()] = pair.substring(i + 1).trim()
        }
        saveSession(jar.entries.joinToString("; ") { "${it.key}=${it.value}" })
        return response
    }

    companion object {
        const val BASE = "https://www.strava.com"
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        const val SESSION_COOKIE = "_strava4_session"
        private const val KEY_COOKIES = "cookies"
        private const val POLLS = 20
        private const val POLL_INTERVAL_MS = 2_000L
        private const val SNIPPET_CHARS = 300
        private val WHITESPACE = Regex("\\s+")
        private val CSRF = Regex(
            """name="csrf-token"[^>]*content="([^"]*)"|content="([^"]*)"[^>]*name="csrf-token""""
        )
    }
}
