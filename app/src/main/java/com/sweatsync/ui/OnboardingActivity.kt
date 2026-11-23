package com.sweatsync.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.sweatsync.R
import com.sweatsync.data.Prefs
import com.sweatsync.util.Formats

/** First-run screen: weight and date of birth, needed to estimate the calories burned. Blocks the app until filled. */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var etWeight: EditText
    private lateinit var btnContinue: MaterialButton
    private var birthMillis = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)
        etWeight = findViewById(R.id.etWeight)
        btnContinue = findViewById(R.id.btnContinue)
        val etBirth = findViewById<EditText>(R.id.etBirth)

        etWeight.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = refresh()
        })
        etBirth.setOnClickListener {
            Dates.pickBirth(this, birthMillis) {
                birthMillis = it
                etBirth.setText(Formats.birthDate(it))
                refresh()
            }
        }
        btnContinue.setOnClickListener {
            Prefs(this).saveProfile(weight()!!, birthMillis)
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
        refresh()
    }

    private fun weight(): Float? =
        etWeight.text.toString().replace(',', '.').toFloatOrNull()?.takeIf { it in MIN_WEIGHT_KG..MAX_WEIGHT_KG }

    private fun refresh() {
        btnContinue.isEnabled = weight() != null && birthMillis != 0L
    }

    companion object {
        const val MIN_WEIGHT_KG = 20f
        const val MAX_WEIGHT_KG = 300f
    }
}
