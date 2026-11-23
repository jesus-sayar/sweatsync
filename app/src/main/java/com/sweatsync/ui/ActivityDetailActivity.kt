package com.sweatsync.ui

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.sweatsync.R
import com.sweatsync.data.ActivityStore
import com.sweatsync.upload.UploadCenter
import com.sweatsync.upload.UploadResult
import com.sweatsync.util.Formats

/** Detail of one saved activity. Right after finishing, also shows the progress of the upload. */
class ActivityDetailActivity : AppCompatActivity() {

    private var activityId = -1L
    private lateinit var uploadCard: View
    private lateinit var tvUpload: TextView
    private lateinit var btnRetry: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detail)
        activityId = intent.getLongExtra(EXTRA_ID, -1)
        val item = ActivityStore(this).get(activityId)
        if (item == null) {
            finish()
            return
        }
        uploadCard = findViewById(R.id.uploadCard)
        tvUpload = findViewById(R.id.tvUpload)
        btnRetry = findViewById<MaterialButton>(R.id.btnRetry).apply { setOnClickListener { UploadCenter.retry(this@ActivityDetailActivity) } }

        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            setTitle(R.string.activity_title)
            setNavigationOnClickListener { finish() }
            inflateMenu(R.menu.detail_menu)
            setOnMenuItemClickListener {
                if (it.itemId == R.id.actionDelete) confirmDelete()
                true
            }
        }
        bindSummary(item)
    }

    private fun bindSummary(item: ActivityStore.Item) {
        findViewById<ImageView>(R.id.imgType).setImageResource(item.type.iconRes)
        findViewById<TextView>(R.id.tvMachine).setText(item.type.nameRes)
        findViewById<TextView>(R.id.tvWhen).text = item.whenText(this)
        findViewById<TextView>(R.id.tvTitle).text = item.title(this)
        StatGrid.build(
            findViewById(R.id.grid),
            listOf(
                StatSpec("distance", R.string.detail_distance, R.string.unit_km, Formats.kilometres(item.distanceM)),
                StatSpec("duration", R.string.detail_duration, null, Formats.duration(item.durationSec)),
                item.averageSpec(short = false),
                StatSpec("kcal", R.string.detail_kcal, R.string.unit_kcal, Math.round(item.kcal).toString()),
            ),
            columns = 2,
            style = StatStyle.SUMMARY,
        )
    }

    private fun confirmDelete() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_title)
            .setMessage(R.string.delete_message)
            .setPositiveButton(R.string.delete) { _, _ ->
                ActivityStore(this).delete(activityId)
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        UploadCenter.listener = ::renderUpload
        renderUpload()
    }

    override fun onPause() {
        UploadCenter.listener = null
        super.onPause()
    }

    private fun renderUpload() {
        // Only the activity that was just finished has an upload to report.
        if (UploadCenter.activityId != activityId) {
            uploadCard.visibility = View.GONE
            return
        }
        if (!UploadCenter.inProgress && UploadCenter.lines.isEmpty()) {
            uploadCard.visibility = View.GONE
            return
        }
        uploadCard.visibility = View.VISIBLE
        if (UploadCenter.inProgress) tvUpload.setText(R.string.uploading)
        else tvUpload.text = UploadCenter.lines.values.joinToString("\n") { describe(it) }
        btnRetry.visibility = if (!UploadCenter.inProgress && UploadCenter.failed().isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun describe(line: UploadCenter.Line): String = when (val result = line.result) {
        is UploadResult.Ok -> getString(R.string.upload_ok, line.label)
        is UploadResult.Duplicate -> getString(R.string.upload_duplicate, line.label)
        is UploadResult.NeedLogin, is UploadResult.NeedMfa -> getString(R.string.upload_login, line.label)
        is UploadResult.Error -> getString(R.string.upload_error, line.label, result.message)
    }

    companion object {
        const val EXTRA_ID = "id"
    }
}
