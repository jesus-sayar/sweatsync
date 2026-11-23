package com.sweatsync.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.sweatsync.R

/**
 * Looks for FTMS machines for whoever called [start], until [stop]. It is independent of the connection.
 * Results are published as immutable lists on the main thread; machines that stop advertising are dropped.
 *
 * Android silently returns nothing to an app that starts more than 5 scans in 30 s, so there is one scanner for
 * the whole app and the hardware scan is restarted as little as possible: a new search keeps a running scan and
 * only forgets the results, [stop] lets the scan linger a few seconds (switching tabs back and forth does not
 * restart it), and any start that would still exceed the limit is postponed until Android allows it.
 */
@SuppressLint("MissingPermission")
class MachineScanner private constructor(context: Context) {

    enum class Problem { BLUETOOTH_OFF, LOCATION_OFF, SCAN_FAILED }

    interface Listener {
        fun onDevices(devices: List<MachineDevice>)

        /** [searching] is true while results can arrive; [problem] says why they cannot. */
        fun onScanState(searching: Boolean, problem: Problem?)
    }

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val adapter = appContext.getSystemService(BluetoothManager::class.java).adapter
    private val found = LinkedHashMap<String, MachineDevice>()
    private val recentStarts = ArrayDeque<Long>()
    private var listener: Listener? = null
    private var scanning = false
    private var receiverRegistered = false
    private var publishScheduled = false

    private val wanted get() = listener != null

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (wanted && isFtms(result)) onResult(result)
        }

        override fun onScanFailed(errorCode: Int) {
            if (errorCode == SCAN_FAILED_ALREADY_STARTED) return
            scanning = false
            listener?.onScanState(false, Problem.SCAN_FAILED)
            scheduleBegin(RETRY_MS)
        }
    }

    private val bluetoothState = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_ON -> begin()
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> onBluetoothOff()
            }
        }
    }

    private val begin = Runnable { begin() }
    private val lingerEnd = Runnable {
        stopHardwareScan()
        unregisterBluetoothState()
    }
    private val housekeeping = object : Runnable {
        override fun run() {
            prune()
            if (wanted) handler.postDelayed(this, HOUSEKEEPING_MS)
        }
    }
    private val publish = Runnable {
        publishScheduled = false
        listener?.onDevices(found.values.toList())
    }

    /** Starts a new search for [listener]: forgets what was found and reports from scratch. */
    fun start(listener: Listener) {
        val first = !wanted
        this.listener = listener
        handler.removeCallbacks(lingerEnd)
        registerBluetoothState()
        clearResults()
        if (first) handler.postDelayed(housekeeping, HOUSEKEEPING_MS)
        if (scanning) listener.onScanState(true, null) else begin()
    }

    /**
     * Ends the search of [owner] (a screen that has been replaced by a newer one does not stop the newer one's).
     * With [linger] the radio keeps scanning a few seconds in case a search starts again; without it, it stops
     * at once, e.g. to leave the radio free for connecting.
     */
    fun stop(owner: Listener, linger: Boolean = true) {
        if (listener !== owner) return
        listener = null
        handler.removeCallbacks(begin)
        handler.removeCallbacks(housekeeping)
        handler.removeCallbacks(publish)
        publishScheduled = false
        handler.removeCallbacks(lingerEnd)
        if (linger) handler.postDelayed(lingerEnd, LINGER_MS) else lingerEnd.run()
        owner.onScanState(false, null)
    }

    private fun begin() {
        handler.removeCallbacks(begin)
        val listener = listener ?: return
        if (scanning) return
        if (adapter == null || !adapter.isEnabled) return onBluetoothOff()
        if (!isLocationOnIfNeeded()) {
            listener.onScanState(false, Problem.LOCATION_OFF)
            return scheduleBegin(RETRY_MS)
        }
        val wait = waitForScanBudget()
        if (wait > 0) {
            // Still searching as far as the user is concerned: the scan resumes as soon as Android allows it.
            listener.onScanState(true, null)
            return scheduleBegin(wait)
        }
        val scanner = adapter.bluetoothLeScanner ?: return scheduleBegin(RETRY_MS)
        scanner.startScan(null, scanSettings(), callback)
        recentStarts.addLast(SystemClock.elapsedRealtime())
        scanning = true
        listener.onScanState(true, null)
    }

    /**
     * Fast and complete: low-latency mode (the screen is on) and no hardware filter, so machines that announce
     * FTMS only in the scan response or in the service data are found too. Bluetooth 5 extended adverts are
     * included where the phone supports them.
     */
    private fun scanSettings(): ScanSettings {
        val builder = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        if (adapter?.isLeExtendedAdvertisingSupported == true) builder.setLegacy(false)
        return builder.build()
    }

    private fun onBluetoothOff() {
        // The system has already dropped the scan.
        scanning = false
        handler.removeCallbacks(begin)
        if (!wanted) return
        clearResults()
        listener?.onScanState(false, Problem.BLUETOOTH_OFF)
    }

    private fun stopHardwareScan() {
        if (scanning && adapter?.isEnabled == true) adapter.bluetoothLeScanner?.stopScan(callback)
        scanning = false
    }

    private fun registerBluetoothState() {
        if (receiverRegistered) return
        ContextCompat.registerReceiver(
            appContext, bluetoothState, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
    }

    private fun unregisterBluetoothState() {
        if (!receiverRegistered) return
        appContext.unregisterReceiver(bluetoothState)
        receiverRegistered = false
    }

    private fun scheduleBegin(delayMs: Long) {
        handler.removeCallbacks(begin)
        handler.postDelayed(begin, delayMs)
    }

    /** Milliseconds until a new scan can start without hitting Android's limit (0 = now). */
    private fun waitForScanBudget(): Long {
        val now = SystemClock.elapsedRealtime()
        while (recentStarts.isNotEmpty() && now - recentStarts.first() >= SCAN_BUDGET_WINDOW_MS) recentStarts.removeFirst()
        if (recentStarts.size < SCAN_BUDGET_STARTS) return 0
        return recentStarts.first() + SCAN_BUDGET_WINDOW_MS - now + BUDGET_MARGIN_MS
    }

    /** Before Android 12, BLE scans return nothing while location services are off. */
    private fun isLocationOnIfNeeded(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return true
        return appContext.getSystemService(LocationManager::class.java)?.isLocationEnabled != false
    }

    private fun clearResults() {
        found.clear()
        handler.removeCallbacks(publish)
        publishScheduled = false
        listener?.onDevices(emptyList())
    }

    private fun onResult(result: ScanResult) {
        val address = result.device.address
        val previous = found[address]
        // Name and type can travel in different packets (advert or scan response): keep what is already known.
        val name = result.scanRecord?.deviceName ?: previous?.name ?: result.device.name
            ?: appContext.getString(R.string.unknown_machine)
        val type = machineType(result) ?: previous?.type
        found[address] = MachineDevice(address, name, type, result.rssi, SystemClock.elapsedRealtime())
        // A new machine (or a new name) shows up at once; signal refreshes are batched so the list is not redrawn nonstop.
        if (previous == null || previous.name != name || previous.type != type) {
            handler.removeCallbacks(publish)
            publish.run()
        } else if (!publishScheduled) {
            publishScheduled = true
            handler.postDelayed(publish, REFRESH_MS)
        }
    }

    private fun prune() {
        val now = SystemClock.elapsedRealtime()
        if (found.values.removeAll { now - it.lastSeenMs > STALE_MS }) listener?.onDevices(found.values.toList())
    }

    private fun isFtms(result: ScanResult): Boolean {
        val record = result.scanRecord ?: return false
        return record.serviceUuids?.any { it.uuid == FtmsUuids.SERVICE } == true ||
            record.getServiceData(ParcelUuid(FtmsUuids.SERVICE)) != null
    }

    /** FTMS advertises the machine type in its service data (flags byte + 16-bit type field). */
    private fun machineType(result: ScanResult): MachineType? {
        val data = result.scanRecord?.getServiceData(ParcelUuid(FtmsUuids.SERVICE)) ?: return null
        if (data.size < 3) return null
        val bits = (data[1].toInt() and 0xFF) or ((data[2].toInt() and 0xFF) shl 8)
        return when {
            bits and TYPE_TREADMILL != 0 -> MachineType.TREADMILL
            bits and TYPE_BIKE != 0 -> MachineType.BIKE
            else -> null
        }
    }

    companion object {
        private const val TYPE_TREADMILL = 0x01
        private const val TYPE_BIKE = 0x20
        private const val HOUSEKEEPING_MS = 1_000L
        private const val REFRESH_MS = 1_000L
        private const val RETRY_MS = 3_000L
        private const val STALE_MS = 15_000L
        private const val LINGER_MS = 8_000L
        private const val SCAN_BUDGET_STARTS = 5
        private const val SCAN_BUDGET_WINDOW_MS = 30_000L
        private const val BUDGET_MARGIN_MS = 500L

        @SuppressLint("StaticFieldLeak") // holds the application context only
        private var instance: MachineScanner? = null

        /** The app's single scanner. Main thread only. */
        fun get(context: Context): MachineScanner = instance ?: MachineScanner(context).also { instance = it }
    }
}
