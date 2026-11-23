package com.sweatsync.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch
import com.sweatsync.R
import com.sweatsync.data.Prefs
import com.sweatsync.upload.StravaUploader
import com.sweatsync.upload.UploadManager

/** One connected platform: switch its sync on or off, or disconnect it. */
class PlatformActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_platform)
        val prefs = Prefs(this)
        val target = UploadManager(this).targets.firstOrNull { it.id == intent.getStringExtra(EXTRA_ID) }
        if (target == null) {
            finish()
            return
        }

        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            title = target.label
            setNavigationOnClickListener { finish() }
        }
        findViewById<MaterialSwitch>(R.id.swSync).apply {
            isChecked = prefs.isUploadEnabled(target.id)
            setOnCheckedChangeListener { _, enabled -> prefs.setUploadEnabled(target.id, enabled) }
        }
        findViewById<android.view.View>(R.id.btnDisconnect).setOnClickListener {
            target.logout()
            if (target is StravaUploader) StravaLoginActivity.clearWebSession()
            finish()
        }
    }

    companion object {
        const val EXTRA_ID = "id"
    }
}
