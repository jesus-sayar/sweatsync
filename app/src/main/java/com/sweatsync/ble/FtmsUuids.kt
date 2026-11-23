package com.sweatsync.ble

import java.util.UUID

/** Bluetooth SIG identifiers of the Fitness Machine Service (FTMS). */
object FtmsUuids {
    val SERVICE: UUID = UUID.fromString("00001826-0000-1000-8000-00805f9b34fb")
    val TREADMILL_DATA: UUID = UUID.fromString("00002acd-0000-1000-8000-00805f9b34fb")
    val BIKE_DATA: UUID = UUID.fromString("00002ad2-0000-1000-8000-00805f9b34fb")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}
