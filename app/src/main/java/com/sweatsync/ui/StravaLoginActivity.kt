package com.sweatsync.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.sweatsync.upload.StravaUploader

/**
 * Shows Strava's own sign-in page so captcha and two-step verification work.
 * When the dashboard loads, the session cookies are saved and this screen closes with RESULT_OK.
 */
class StravaLoginActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private var done = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = StravaUploader.USER_AGENT
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) = checkSignedIn(url)
                override fun onPageFinished(view: WebView, url: String) = checkSignedIn(url)
                override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) = checkSignedIn(url)
            }
        }
        setContentView(web)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })
        web.loadUrl("${StravaUploader.BASE}/login")
    }

    private fun checkSignedIn(url: String) {
        if (done) return
        val uri = Uri.parse(url)
        val onDashboard = uri.host?.endsWith("strava.com") == true && uri.path?.startsWith("/dashboard") == true
        if (!onDashboard) return
        val cookies = CookieManager.getInstance().getCookie(StravaUploader.BASE) ?: return
        if (!cookies.contains(StravaUploader.SESSION_COOKIE)) return
        done = true
        CookieManager.getInstance().flush()
        StravaUploader(this).saveSession(cookies)
        setResult(RESULT_OK)
        finish()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    companion object {
        /** Forgets the browser session too, so the next sign-in starts clean. */
        fun clearWebSession() {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        }
    }
}
