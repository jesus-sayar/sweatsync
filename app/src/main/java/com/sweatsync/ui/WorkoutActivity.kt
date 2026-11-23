package com.sweatsync.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.View.MeasureSpec
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.sweatsync.R
import com.sweatsync.ble.MachineType
import com.sweatsync.data.ActivityStore
import com.sweatsync.data.Prefs
import com.sweatsync.service.ConnectionError
import com.sweatsync.service.MachineClient
import com.sweatsync.service.MachineService
import com.sweatsync.service.MachineState
import com.sweatsync.service.Phase
import com.sweatsync.upload.UploadCenter
import com.sweatsync.util.Downloads
import com.sweatsync.util.Formats
import kotlin.math.roundToInt

/**
 * Live metrics of the connected machine with Start, Pause, Resume and Finish, laid out like Strava's recording
 * screen: time, then the main metric (pace on a treadmill, speed on a bike), then the rest. Shows only what the
 * machine reports. The big numbers shrink just enough for every metric to fit on screen without scrolling.
 */
class WorkoutActivity : AppCompatActivity() {

    private lateinit var client: MachineClient
    private lateinit var prefs: Prefs
    private lateinit var toolbar: MaterialToolbar
    private lateinit var tvType: TextView
    private lateinit var tvState: TextView
    private lateinit var tvTime: TextView
    private lateinit var viewRec: View
    private lateinit var recBlink: Blink
    private lateinit var tvPrimaryLabel: TextView
    private lateinit var tvPrimary: TextView
    private lateinit var tvPrimaryUnit: TextView
    private lateinit var statRows: LinearLayout
    private lateinit var btnMain: MaterialButton
    private lateinit var rowPaused: View
    private lateinit var scroll: View
    private lateinit var content: View

    // Sizes from the layout, in px: fitToScreen scales them down from here.
    private var baseTimeHeight = 0
    private var baseTimeMinText = 0
    private var baseTimeMaxText = 0
    private var basePrimaryText = 0f
    private var baseMetricText = 0f
    private var fittedHeight = 0

    private var state = MachineState()
    private var stats: Map<String, TextView> = emptyMap()
    private var shownKeys: List<String> = emptyList()
    private var reportedError: ConnectionError? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_workout)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = Prefs(this)
        toolbar = findViewById(R.id.toolbar)
        tvType = findViewById(R.id.tvType)
        tvState = findViewById(R.id.tvState)
        tvTime = findViewById(R.id.tvTime)
        viewRec = findViewById(R.id.viewRec)
        recBlink = Blink(viewRec)
        tvPrimaryLabel = findViewById(R.id.tvPrimaryLabel)
        tvPrimary = findViewById(R.id.tvPrimary)
        tvPrimaryUnit = findViewById(R.id.tvPrimaryUnit)
        statRows = findViewById(R.id.metrics)
        btnMain = findViewById(R.id.btnMain)
        rowPaused = findViewById(R.id.rowPaused)
        scroll = findViewById(R.id.scroll)
        content = findViewById(R.id.content)
        baseTimeHeight = tvTime.layoutParams.height
        baseTimeMinText = tvTime.autoSizeMinTextSize
        baseTimeMaxText = tvTime.autoSizeMaxTextSize
        basePrimaryText = tvPrimary.textSize
        scroll.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            if (bottom - top != fittedHeight) fitToScreen()
        }

        client = MachineClient(this, lifecycle, ::render)
        toolbar.setNavigationOnClickListener { leave() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leave()
        })
        btnMain.setOnClickListener {
            val service = client.service ?: return@setOnClickListener
            if (state.session != null) service.pauseSession() else service.startSession()
        }
        findViewById<View>(R.id.btnResume).setOnClickListener { client.service?.resumeSession() }
        findViewById<View>(R.id.btnFinish).setOnClickListener { client.service?.let(::showLeaveSheet) }
    }

    override fun onDestroy() {
        recBlink.blinking = false
        super.onDestroy()
    }

    // ---- Leaving and finishing ----------------------------------------------------------------

    /** Back arrow or gesture: with an activity under way, ask what to do (like Finish); otherwise disconnect and return to the list. */
    private fun leave() {
        val service = client.service
        if (service != null && state.session != null) {
            showLeaveSheet(service)
        } else {
            service?.disconnect()
            finish()
        }
    }

    /** Save, discard, or cancel and stay. Opened by Finish and by back once an activity is under way. */
    private fun showLeaveSheet(service: MachineService) {
        val sheet = BottomSheetDialog(this)
        val view = LayoutInflater.from(this).inflate(R.layout.sheet_leave, findViewById(android.R.id.content), false)
        view.findViewById<View>(R.id.btnSave).setOnClickListener {
            sheet.dismiss()
            finishAndSave(service)
        }
        view.findViewById<View>(R.id.btnDiscard).setOnClickListener {
            sheet.dismiss()
            service.discardSession()
            finish()
        }
        view.findViewById<View>(R.id.btnKeep).setOnClickListener { sheet.dismiss() }
        sheet.setContentView(view)
        sheet.show()
    }

    /** Saves the activity (history + .fit in Downloads), uploads it to the platforms that are ON, and opens its detail. */
    private fun finishAndSave(service: MachineService) {
        val summary = service.finishSession()
        if (summary == null) {
            Toast.makeText(this, R.string.too_short, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        if (!Downloads.save(this, summary.fileName, summary.fit)) {
            Toast.makeText(this, R.string.save_failed, Toast.LENGTH_LONG).show()
        }
        val id = ActivityStore(this).insert(
            ActivityStore.Item(0, summary.type, summary.startMs, summary.durationSec, summary.distanceM, summary.kcal, summary.avgSpeedKmh)
        )
        UploadCenter.start(this, id, summary.fit, summary.fileName)
        startActivity(Intent(this, ActivityDetailActivity::class.java).putExtra(ActivityDetailActivity.EXTRA_ID, id))
        finish()
    }

    // ---- Rendering ----------------------------------------------------------------------------

    private fun render(newState: MachineState) {
        state = newState
        val session = state.session
        toolbar.title = state.deviceName.ifEmpty { prefs.lastName.orEmpty() }
        tvType.setCompoundDrawablesRelativeWithIntrinsicBounds(state.type.iconRes, 0, 0, 0)
        tvType.setText(state.type.nameRes)
        tvState.setText(
            when {
                session?.paused == true -> R.string.state_paused
                session != null -> R.string.state_recording
                state.phase == Phase.CONNECTED -> R.string.state_ready
                state.phase == Phase.CONNECTING -> R.string.state_connecting
                else -> R.string.status_disconnected
            }
        )
        updateRecDot(recording = session != null && !session.paused, live = state.phase == Phase.CONNECTED || session != null)
        tvTime.text = Formats.clock(session?.elapsedSec ?: 0)

        val speed = state.live.speedKmh
        if (state.type == MachineType.BIKE) {
            showPrimary(R.string.label_speed, Formats.speed(speed), R.string.unit_kmh)
        } else {
            showPrimary(R.string.label_pace, Formats.pace(speed), R.string.unit_pace)
        }
        ensureStats()
        stats["distance"]?.text = Formats.kilometres(session?.distanceM ?: 0.0)
        stats["speed"]?.text = Formats.speed(speed)
        stats["cadence"]?.text = (state.live.cadenceRpm ?: 0).toString()
        stats["power"]?.text = (state.live.powerW ?: 0).toString()
        stats["hr"]?.text = (state.live.heartRate ?: 0).toString()

        // Stopped: Start. Recording: Pause. Paused: Resume + Finish.
        val recordingNow = session != null
        rowPaused.visibility = if (session?.paused == true) View.VISIBLE else View.GONE
        btnMain.visibility = if (session?.paused == true) View.GONE else View.VISIBLE
        btnMain.setText(if (recordingNow) R.string.pause else R.string.start)
        btnMain.setIconResource(if (recordingNow) R.drawable.ic_pause else R.drawable.ic_play)
        btnMain.isEnabled = recordingNow || state.phase == Phase.CONNECTED

        reportError(state.error)
    }

    private fun showPrimary(label: Int, value: String, unit: Int) {
        tvPrimaryLabel.setText(label)
        tvPrimary.text = value
        tvPrimaryUnit.setText(unit)
    }

    private fun reportError(error: ConnectionError?) {
        if (error == reportedError) return
        reportedError = error
        val message = when (error) {
            ConnectionError.BLUETOOTH_OFF -> R.string.status_bt_off
            ConnectionError.NO_FTMS -> R.string.status_no_ftms
            ConnectionError.CONNECT_FAILED -> R.string.status_connect_failed
            ConnectionError.LINK_LOST -> R.string.status_disconnected
            null -> return
        }
        Snackbar.make(findViewById(android.R.id.content), message, Snackbar.LENGTH_LONG).show()
    }

    /** Red blinking dot while recording; a green one when connected (ready or paused); grey while connecting or lost. */
    private fun updateRecDot(recording: Boolean, live: Boolean) {
        recBlink.blinking = recording
        val color = when {
            recording -> R.color.error
            live -> R.color.ok
            else -> R.color.muted
        }
        viewRec.backgroundTintList = ColorStateList.valueOf(getColor(color))
    }

    /** Rebuilds the grid only when the set of metrics changes (machine type, or a new sensor appears). */
    private fun ensureStats() {
        val live = state.live
        val keys = listOf("distance") +
            (if (state.type == MachineType.BIKE) emptyList() else listOf("speed")) +
            listOfNotNull(
                "cadence".takeIf { live.cadenceRpm != null },
                "power".takeIf { live.powerW != null },
                "hr".takeIf { live.heartRate != null },
            )
        if (keys == shownKeys) return
        shownKeys = keys
        val specs = keys.map { key ->
            val (label, unit) = labelAndUnit(key)
            StatSpec(key, label, unit)
        }
        stats = StatGrid.build(statRows, specs, COLUMNS, StatStyle.LIVE)
        if (baseMetricText == 0f) stats.values.firstOrNull()?.let { baseMetricText = it.textSize }
        fitToScreen()
    }

    // ---- Fitting on screen ----------------------------------------------------------------------

    /**
     * Shrinks the big numbers (time, main metric, the grid's values) by one common factor so that everything fits
     * the visible height. Their heights scale linearly with the text size, so one measure at full size is enough.
     */
    private fun fitToScreen() {
        scroll.post {
            val available = scroll.height
            if (available == 0) return@post
            fittedHeight = available
            applyScale(1f)
            content.measure(
                MeasureSpec.makeMeasureSpec(scroll.width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            )
            val overflow = content.measuredHeight - available
            if (overflow <= 0) return@post
            val rows = (stats.size + COLUMNS - 1) / COLUMNS
            val numbers = tvTime.measuredHeight + tvPrimary.measuredHeight +
                rows * (stats.values.firstOrNull()?.measuredHeight ?: 0)
            applyScale(((numbers - overflow - SAFETY_PX).toFloat() / numbers).coerceAtLeast(MIN_SCALE))
        }
    }

    private fun applyScale(scale: Float) {
        tvTime.layoutParams.height = (baseTimeHeight * scale).roundToInt()
        tvTime.setAutoSizeTextTypeUniformWithConfiguration(
            (baseTimeMinText * scale).roundToInt(), (baseTimeMaxText * scale).roundToInt(), 1, TypedValue.COMPLEX_UNIT_PX,
        )
        tvTime.requestLayout()
        tvPrimary.setTextSize(TypedValue.COMPLEX_UNIT_PX, basePrimaryText * scale)
        stats.values.forEach { it.setTextSize(TypedValue.COMPLEX_UNIT_PX, baseMetricText * scale) }
    }

    private fun labelAndUnit(key: String) = when (key) {
        "distance" -> R.string.label_distance to R.string.unit_km
        "speed" -> R.string.label_speed to R.string.unit_kmh
        "cadence" -> R.string.label_cadence to R.string.unit_rpm
        "power" -> R.string.label_power to R.string.unit_w
        else -> R.string.label_hr to R.string.unit_bpm
    }

    private companion object {
        const val COLUMNS = 2
        /** Below this the numbers would get too small: the screen scrolls instead. */
        const val MIN_SCALE = 0.6f
        /** Absorbs rounding of the scaled sizes. */
        const val SAFETY_PX = 4
    }
}
