package com.sweatsync.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Runtime permissions the app needs for Bluetooth and the foreground notification. */
object Perms {

    private fun required() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun notifications() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            arrayOf(Manifest.permission.POST_NOTIFICATIONS)
        else emptyArray()

    fun hasBluetooth(c: Context) = required().all { c.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /** Everything to ask for in one go. */
    fun toRequest(): Array<String> = required() + notifications()
}
