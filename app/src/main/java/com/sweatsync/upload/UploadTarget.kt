package com.sweatsync.upload

import android.content.Context
import com.sweatsync.data.Prefs
import com.sweatsync.util.Background
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

sealed class UploadResult {
    object Ok : UploadResult()
    object Duplicate : UploadResult()
    object NeedLogin : UploadResult()
    object NeedMfa : UploadResult()
    class Error(val message: String) : UploadResult()
}

/** A place a finished activity can be uploaded to (Garmin Connect, Strava…). */
interface UploadTarget {
    val id: String
    val label: String
    fun isLinked(): Boolean

    /** Forgets the stored session. */
    fun logout()

    /** Blocking; call from a background thread. */
    fun upload(fit: ByteArray, fileName: String): UploadResult
}

/** Uploads to every target that is both linked and switched on. */
class UploadManager(context: Context) {

    private val prefs = Prefs(context)
    val targets: List<UploadTarget> = listOf(GarminUploader(context), StravaUploader(context))

    fun active(): List<UploadTarget> = targets.filter { it.isLinked() && prefs.isUploadEnabled(it.id) }

    /** Blocking; call from a background thread. Targets upload in parallel, each with a time limit. */
    fun upload(fit: ByteArray, fileName: String, to: List<UploadTarget>): List<Pair<UploadTarget, UploadResult>> {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        val jobs = to.map { target -> target to Background.io.submit<UploadResult> { target.upload(fit, fileName) } }
        return jobs.map { (target, job) ->
            val left = (deadline - System.currentTimeMillis()).coerceAtLeast(1)
            target to try {
                job.get(left, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                job.cancel(true)
                UploadResult.Error("Timed out")
            } catch (e: Exception) {
                UploadResult.Error(e.cause?.message ?: e.message ?: "Upload failed")
            }
        }
    }

    private companion object {
        const val TIMEOUT_MS = 60_000L
    }
}
