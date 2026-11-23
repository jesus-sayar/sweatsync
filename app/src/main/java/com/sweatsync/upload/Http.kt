package com.sweatsync.upload

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

class HttpResponse(val code: Int, val text: String, private val headers: Map<String, List<String>>) {
    fun header(name: String): List<String> = headers[name].orEmpty()
}

/** A multipart/form-data request body. */
class Multipart(val contentType: String, val bytes: ByteArray)

/** The little HTTP toolbox shared by the upload targets. Blocking: call it from a background thread. */
object Http {

    private const val CONNECT_TIMEOUT_MS = 20_000
    private const val READ_TIMEOUT_MS = 30_000

    fun request(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        followRedirects: Boolean = true
    ): HttpResponse {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = followRedirects
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..399) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            return HttpResponse(code, text, connection.headerFields.filterKeys { it != null })
        } finally {
            connection.disconnect()
        }
    }

    fun formEncode(params: Map<String, String>) =
        params.entries.joinToString("&") { "${urlEncode(it.key)}=${urlEncode(it.value)}" }

    fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

    /** A multipart body with some text [fields] and one file part. */
    fun multipart(fileField: String, fileName: String, file: ByteArray, fields: Map<String, String> = emptyMap()): Multipart {
        val boundary = "----" + UUID.randomUUID().toString().replace("-", "")
        val out = ByteArrayOutputStream()
        fields.forEach { (name, value) ->
            out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
        }
        out.write(
            ("--$boundary\r\nContent-Disposition: form-data; name=\"$fileField\"; filename=\"$fileName\"\r\n" +
                "Content-Type: application/octet-stream\r\n\r\n").toByteArray()
        )
        out.write(file)
        out.write("\r\n--$boundary--\r\n".toByteArray())
        return Multipart("multipart/form-data; boundary=$boundary", out.toByteArray())
    }
}
