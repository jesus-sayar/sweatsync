package com.sweatsync.ui

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.sweatsync.R
import com.sweatsync.data.Prefs
import com.sweatsync.upload.GarminUploader
import com.sweatsync.upload.UploadManager
import com.sweatsync.upload.UploadResult
import com.sweatsync.upload.UploadTarget
import com.sweatsync.util.Background
import com.sweatsync.util.Formats

/** The Settings tab: personal data and the Garmin / Strava connections. */
class SettingsFragment : Fragment(R.layout.fragment_settings) {

    private class PlatformRow(val root: View) {
        val name: TextView = root.findViewById(R.id.tvName)
        val state: TextView = root.findViewById(R.id.tvState)
        val connect: MaterialButton = root.findViewById(R.id.btnLink)
        val chevron: ImageView = root.findViewById(R.id.imgChevron)
    }

    private lateinit var prefs: Prefs
    private lateinit var garmin: GarminUploader
    private lateinit var strava: UploadTarget
    private lateinit var garminRow: PlatformRow
    private lateinit var stravaRow: PlatformRow
    private lateinit var tvWeight: TextView
    private lateinit var tvBirth: TextView
    private lateinit var tvStatus: TextView

    private val stravaLogin = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (view != null) render()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val context = requireContext()
        prefs = Prefs(context)
        val targets = UploadManager(context).targets
        garmin = targets.filterIsInstance<GarminUploader>().first()
        strava = targets.first { it !== garmin }
        tvWeight = view.findViewById(R.id.tvWeight)
        tvBirth = view.findViewById(R.id.tvBirth)
        tvStatus = view.findViewById(R.id.tvStatus)
        garminRow = PlatformRow(view.findViewById(R.id.rowGarmin)).also { it.name.setText(R.string.garmin_name) }
        stravaRow = PlatformRow(view.findViewById(R.id.rowStrava)).also { it.name.setText(R.string.strava_name) }

        view.findViewById<View>(R.id.rowWeight).setOnClickListener { editWeight() }
        view.findViewById<View>(R.id.rowBirth).setOnClickListener {
            Dates.pickBirth(requireActivity() as AppCompatActivity, prefs.birthMillis) {
                prefs.saveProfile(prefs.weightKg, it)
                render()
            }
        }
        garminRow.connect.setOnClickListener { askGarminLogin() }
        stravaRow.connect.setOnClickListener { stravaLogin.launch(Intent(context, StravaLoginActivity::class.java)) }
        garminRow.root.setOnClickListener { openPlatform(garmin) }
        stravaRow.root.setOnClickListener { openPlatform(strava) }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        tvWeight.text = getString(R.string.fmt_weight, Formats.weight(prefs.weightKg))
        tvBirth.text = Formats.birthDate(prefs.birthMillis)
        bind(garminRow, garmin)
        bind(stravaRow, strava)
    }

    private fun bind(row: PlatformRow, target: UploadTarget) {
        val linked = target.isLinked()
        row.state.visibility = if (linked) View.VISIBLE else View.GONE
        row.chevron.visibility = if (linked) View.VISIBLE else View.GONE
        row.connect.visibility = if (linked) View.GONE else View.VISIBLE
        row.root.isClickable = linked
    }

    private fun openPlatform(target: UploadTarget) {
        if (!target.isLinked()) return
        startActivity(Intent(requireContext(), PlatformActivity::class.java).putExtra(PlatformActivity.EXTRA_ID, target.id))
    }

    // ---- Personal data ------------------------------------------------------------------------

    private fun editWeight() {
        val view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_input, null)
        view.findViewById<TextInputLayout>(R.id.til).apply {
            hint = getString(R.string.weight)
            suffixText = getString(R.string.kg)
        }
        val input = view.findViewById<TextInputEditText>(R.id.et).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(Formats.weight(prefs.weightKg))
            setSelection(text?.length ?: 0)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.weight_title)
            .setView(view)
            .setPositiveButton(R.string.send) { _, _ ->
                val kg = input.text.toString().replace(',', '.').toFloatOrNull()
                if (kg != null && kg in OnboardingActivity.MIN_WEIGHT_KG..OnboardingActivity.MAX_WEIGHT_KG) {
                    prefs.saveProfile(kg, prefs.birthMillis)
                    render()
                } else tvStatus.setText(R.string.invalid_weight)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---- Garmin sign-in -----------------------------------------------------------------------

    private fun askGarminLogin() {
        val view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_garmin_login, null)
        val email = view.findViewById<TextInputEditText>(R.id.etEmail)
        val password = view.findViewById<TextInputEditText>(R.id.etPass)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.garmin_login_title)
            .setMessage(R.string.garmin_login_note)
            .setView(view)
            .setPositiveButton(R.string.sign_in) { _, _ ->
                runGarmin { garmin.login(email.text.toString().trim(), password.text.toString()) }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun askGarminMfa() {
        val view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_input, null)
        view.findViewById<TextInputLayout>(R.id.til).hint = getString(R.string.mfa_hint)
        val code = view.findViewById<TextInputEditText>(R.id.et).apply { inputType = InputType.TYPE_CLASS_NUMBER }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.mfa_title)
            .setMessage(R.string.mfa_note)
            .setView(view)
            .setPositiveButton(R.string.send) { _, _ -> runGarmin { garmin.submitMfa(code.text.toString().trim()) } }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun runGarmin(action: () -> UploadResult) {
        tvStatus.setText(R.string.signing_in)
        val activity = requireActivity()
        Background.io.execute {
            val result = action()
            activity.runOnUiThread {
                if (view == null) return@runOnUiThread
                tvStatus.text = ""
                when (result) {
                    is UploadResult.NeedMfa -> askGarminMfa()
                    is UploadResult.Error -> showSignInError(result.message)
                    else -> render()
                }
            }
        }
    }

    private fun showSignInError(message: String) {
        tvStatus.text = message
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.sign_in_failed)
            .setMessage(message)
            .setPositiveButton(R.string.close, null)
            .show()
    }
}
