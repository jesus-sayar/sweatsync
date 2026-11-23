package com.sweatsync.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.UUID

/**
 * The GATT connection to one machine. It subscribes to the treadmill or bike data characteristic and reports
 * everything on the main thread. [hasLink] stays true until the link is really closed, so owners can wait for it.
 */
@SuppressLint("MissingPermission")
class MachineGatt(context: Context, private val listener: Listener) {

    enum class Failure { BLUETOOTH_OFF, NO_FTMS }

    interface Listener {
        fun onReady(type: MachineType)
        fun onData(data: MachineData)

        /** The link is gone. [failure] explains why when it was not a plain disconnect. */
        fun onClosed(failure: Failure?)
    }

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val adapter = appContext.getSystemService(BluetoothManager::class.java).adapter
    private var gatt: BluetoothGatt? = null
    private var pendingFailure: Failure? = null

    val hasLink: Boolean get() = gatt != null

    private val forceClose = Runnable { closeNow(pendingFailure) }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> g.discoverServices()
                BluetoothProfile.STATE_DISCONNECTED -> handler.post { if (gatt === g) closeNow(pendingFailure) else g.close() }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(FtmsUuids.SERVICE)
            val treadmill = service?.getCharacteristic(FtmsUuids.TREADMILL_DATA)
            val bike = service?.getCharacteristic(FtmsUuids.BIKE_DATA)
            val characteristic = treadmill ?: bike
            if (characteristic == null) {
                handler.post {
                    pendingFailure = Failure.NO_FTMS
                    disconnect()
                }
                return
            }
            val detected = if (treadmill != null) MachineType.TREADMILL else MachineType.BIKE
            subscribe(g, characteristic)
            handler.post { listener.onReady(detected) }
        }

        @Deprecated("Used on Android < 13")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            dispatch(ch.uuid, ch.value)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) =
            dispatch(ch.uuid, value)
    }

    fun connect(address: String) {
        if (adapter == null || !adapter.isEnabled) {
            listener.onClosed(Failure.BLUETOOTH_OFF)
            return
        }
        release()
        pendingFailure = null
        gatt = adapter.getRemoteDevice(address).connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    /** Asks the link to close; [Listener.onClosed] follows. A fallback guarantees it even if the stack stays silent. */
    fun disconnect() {
        val g = gatt ?: return
        g.disconnect()
        handler.removeCallbacks(forceClose)
        handler.postDelayed(forceClose, FORCE_CLOSE_MS)
    }

    /** Drops everything without notifying. Used when the owner is going away. */
    fun release() {
        handler.removeCallbacks(forceClose)
        gatt?.close()
        gatt = null
    }

    private fun closeNow(failure: Failure?) {
        handler.removeCallbacks(forceClose)
        gatt?.close()
        gatt = null
        pendingFailure = null
        listener.onClosed(failure)
    }

    private fun subscribe(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        g.setCharacteristicNotification(characteristic, true)
        val cccd = characteristic.getDescriptor(FtmsUuids.CCCD) ?: return
        @Suppress("DEPRECATION")
        run {
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            g.writeDescriptor(cccd)
        }
    }

    private fun dispatch(uuid: UUID, bytes: ByteArray?) {
        val parsed = when (uuid) {
            FtmsUuids.TREADMILL_DATA -> FtmsParser.parseTreadmill(bytes ?: return)
            FtmsUuids.BIKE_DATA -> FtmsParser.parseBike(bytes ?: return)
            else -> null
        } ?: return
        handler.post { listener.onData(parsed) }
    }

    private companion object {
        const val FORCE_CLOSE_MS = 3_000L
    }
}
