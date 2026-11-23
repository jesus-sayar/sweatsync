package com.sweatsync.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.sweatsync.R
import com.sweatsync.data.Prefs

/**
 * The only screen with the bottom navigation bar. Home, Activities and Settings are fragments swapped
 * inside it, so the bar and the content can never disagree.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Personal data is required before anything else (it is needed to estimate calories).
        if (!Prefs(this).hasProfile) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_main)
        if (savedInstanceState == null) show(HomeFragment())
        findViewById<BottomNavigationView>(R.id.bottomNav).setOnItemSelectedListener { item ->
            val current = supportFragmentManager.findFragmentById(R.id.content)
            when (item.itemId) {
                R.id.tabHome -> if (current !is HomeFragment) show(HomeFragment())
                R.id.tabHistory -> if (current !is HistoryFragment) show(HistoryFragment())
                R.id.tabSettings -> if (current !is SettingsFragment) show(SettingsFragment())
            }
            true
        }
    }

    private fun show(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .replace(R.id.content, fragment)
            .commit()
    }
}
