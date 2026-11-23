package com.sweatsync.upload

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.sweatsync.util.Background

/**
 * Keeps the state of the upload that follows a finished activity, so the detail screen can show progress
 * and results even though the recording screen has already closed.
 */
object UploadCenter {

    class Line(val label: String, val result: UploadResult)

    private val main = Handler(Looper.getMainLooper())
    private var fit: ByteArray? = null
    private var fileName = ""

    var activityId = -1L
        private set
    var inProgress = false
        private set
    val lines = LinkedHashMap<String, Line>()

    /** Called on the main thread whenever [inProgress] or [lines] change. */
    var listener: (() -> Unit)? = null

    fun start(context: Context, id: Long, fitBytes: ByteArray, name: String) {
        activityId = id
        fit = fitBytes
        fileName = name
        lines.clear()
        val manager = UploadManager(context.applicationContext)
        run(manager, manager.active())
    }

    /** Ids of the targets that failed in the last attempt, so they can be retried. */
    fun failed(): List<String> = lines.filterValues {
        it.result !is UploadResult.Ok && it.result !is UploadResult.Duplicate
    }.keys.toList()

    fun retry(context: Context) {
        val manager = UploadManager(context.applicationContext)
        val ids = failed()
        run(manager, manager.targets.filter { it.id in ids })
    }

    private fun run(manager: UploadManager, targets: List<UploadTarget>) {
        val data = fit ?: return
        if (targets.isEmpty()) {
            inProgress = false
            listener?.invoke()
            return
        }
        inProgress = true
        listener?.invoke()
        Background.io.execute {
            val results = manager.upload(data, fileName, targets)
            main.post {
                results.forEach { (target, result) -> lines[target.id] = Line(target.label, result) }
                inProgress = false
                listener?.invoke()
            }
        }
    }
}
