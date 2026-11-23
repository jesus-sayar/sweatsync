package com.sweatsync.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import com.sweatsync.R
import com.sweatsync.ble.LiveMetrics
import com.sweatsync.ble.MachineData
import com.sweatsync.ble.MachineDevice
import com.sweatsync.ble.MachineGatt
import com.sweatsync.ble.MachineType
import com.sweatsync.data.Prefs
import com.sweatsync.session.ActivitySession
import com.sweatsync.session.SystemClockSource
import com.sweatsync.ui.MainActivity
import com.sweatsync.util.Formats

/**
 * Keeps the machine connection and the activity alive while the app is in the background.
 * It turns Bluetooth events and session changes into [MachineState] and publishes it to the bound screens.
 */
class MachineService : Service(), MachineGatt.Listener {

    inner class LocalBinder : Binder() {
        val service: MachineService get() = this@MachineService
    }

    private val binder = LocalBinder()
    private val handler = Handler(Looper.getMainLooper())
    private val listeners = ArrayList<(MachineState) -> Unit>()
    private lateinit var prefs: Prefs
    private lateinit var gatt: MachineGatt
    private var session: ActivitySession? = null
    private var userDisconnect = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var foreground = false

    var state = MachineState()
        private set

    private val ticker = object : Runnable {
        override fun run() {
            session?.tick(state.live)
            publish()
            handler.postDelayed(this, TICK_MS)
        }
    }

    // A connection attempt that never completes must not leave the screen waiting forever.
    private val connectTimeout = Runnable {
        if (state.phase == Phase.CONNECTING) {
            userDisconnect = true
            gatt.disconnect()
            publish { it.copy(phase = Phase.DISCONNECTED, error = ConnectionError.CONNECT_FAILED) }
            stopIfIdle()
        }
    }

    private val reconnect = Runnable {
        val address = prefs.lastAddress
        if (session != null && state.phase == Phase.DISCONNECTED && !userDisconnect && address != null) {
            beginConnecting()
            gatt.connect(address)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        gatt = MachineGatt(this, this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        foreground = true
        intent?.getStringExtra(EXTRA_ADDRESS)?.let { address ->
            val type = intent.getStringExtra(EXTRA_TYPE)?.let { stored -> MachineType.values().firstOrNull { it.name == stored } }
            connect(MachineDevice(address, intent.getStringExtra(EXTRA_NAME).orEmpty(), type, 0, 0))
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        releaseWakeLock()
        gatt.release()
        super.onDestroy()
    }

    // ---- Observers ----------------------------------------------------------------------------

    fun addListener(listener: (MachineState) -> Unit) {
        listeners += listener
        listener(state)
    }

    fun removeListener(listener: (MachineState) -> Unit) {
        listeners -= listener
    }

    private fun publish(change: (MachineState) -> MachineState = { it }) {
        state = change(state).copy(
            session = session?.let { SessionSnapshot(it.isPaused, it.elapsedSec(), it.distanceM, it.kcal) }
        )
        listeners.toList().forEach { it(state) }
        updateNotification()
    }

    // ---- Connection ---------------------------------------------------------------------------

    private fun connect(device: MachineDevice) {
        prefs.saveLastDevice(device.address, device.name, device.type)
        userDisconnect = false
        beginConnecting(device)
        gatt.connect(device.address)
    }

    private fun beginConnecting(device: MachineDevice? = null) {
        handler.removeCallbacks(connectTimeout)
        handler.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
        publish {
            it.copy(
                phase = Phase.CONNECTING,
                deviceName = device?.name ?: it.deviceName,
                type = device?.type ?: it.type,
                live = LiveMetrics(),
                error = null
            )
        }
    }

    /** Drops the machine on purpose. The state changes at once; the service stops once the link is really closed. */
    fun disconnect() {
        userDisconnect = true
        handler.removeCallbacks(reconnect)
        handler.removeCallbacks(connectTimeout)
        gatt.disconnect()
        publish { it.copy(phase = Phase.DISCONNECTED, live = LiveMetrics(), error = null) }
        stopIfIdle()
    }

    override fun onReady(type: MachineType) {
        handler.removeCallbacks(connectTimeout)
        prefs.saveLastType(type)
        publish { it.copy(phase = Phase.CONNECTED, type = type, error = null) }
    }

    override fun onData(data: MachineData) = publish { it.copy(live = it.live.merge(data)) }

    override fun onClosed(failure: MachineGatt.Failure?) {
        handler.removeCallbacks(connectTimeout)
        val error = when {
            failure == MachineGatt.Failure.NO_FTMS -> ConnectionError.NO_FTMS
            failure == MachineGatt.Failure.BLUETOOTH_OFF -> ConnectionError.BLUETOOTH_OFF
            userDisconnect -> state.error
            state.phase == Phase.CONNECTING -> ConnectionError.CONNECT_FAILED
            else -> ConnectionError.LINK_LOST
        }
        publish { it.copy(phase = Phase.DISCONNECTED, live = LiveMetrics(), error = error) }
        if (session != null && !userDisconnect) handler.postDelayed(reconnect, RECONNECT_MS)
        stopIfIdle()
    }

    private fun stopIfIdle() {
        if (state.phase == Phase.DISCONNECTED && session == null && !gatt.hasLink) {
            foreground = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    // ---- Activity -----------------------------------------------------------------------------

    fun startSession() {
        if (session != null || state.phase != Phase.CONNECTED) return
        session = ActivitySession(SystemClockSource, state.type, prefs.weightKg.toDouble(), state.live)
        acquireWakeLock()
        handler.postDelayed(ticker, TICK_MS)
        publish()
    }

    fun pauseSession() {
        session?.pause(state.live)
        publish()
    }

    fun resumeSession() {
        session?.resume(state.live)
        publish()
    }

    /** Ends the activity and disconnects. Returns null when it was too short to keep. */
    fun finishSession(): ActivitySession.Summary? {
        val summary = session?.finish(state.live)
        endSession()
        disconnect()
        return summary
    }

    fun discardSession() {
        endSession()
        disconnect()
    }

    private fun endSession() {
        session = null
        handler.removeCallbacks(ticker)
        releaseWakeLock()
    }

    private fun acquireWakeLock() {
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sweatsync:session")
            .apply { acquire(MAX_SESSION_MS) }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    // ---- Notification -------------------------------------------------------------------------

    private fun updateNotification() {
        if (foreground) getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val running = state.session
        val text = when {
            running != null -> {
                listOfNotNull(
                    Formats.clock(running.elapsedSec),
                    "${Formats.kilometres(running.distanceM)} ${getString(R.string.unit_km)}",
                    getString(R.string.notif_paused).takeIf { running.paused },
                ).joinToString(" · ")
            }
            state.phase == Phase.CONNECTING -> getString(R.string.notif_connecting)
            state.phase == Phase.CONNECTED -> getString(R.string.notif_connected)
            else -> getString(R.string.notif_disconnected)
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_sync)
            .setContentTitle(getString(if (running != null) R.string.notif_title_running else R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "session"
        private const val NOTIF_ID = 1
        private const val TICK_MS = 1_000L
        private const val CONNECT_TIMEOUT_MS = 20_000L
        private const val RECONNECT_MS = 4_000L
        private const val MAX_SESSION_MS = 6 * 3_600_000L
        private const val EXTRA_ADDRESS = "address"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_TYPE = "type"

        /** Starts the service (in the foreground) and connects it to [device]. */
        fun connect(context: Context, device: MachineDevice) {
            context.startForegroundService(
                Intent(context, MachineService::class.java)
                    .putExtra(EXTRA_ADDRESS, device.address)
                    .putExtra(EXTRA_NAME, device.name)
                    .putExtra(EXTRA_TYPE, device.type?.name)
            )
        }
    }
}
