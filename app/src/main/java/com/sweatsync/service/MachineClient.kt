package com.sweatsync.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner

/**
 * Binds a screen to [MachineService] for as long as the screen is visible and delivers [MachineState] updates.
 * [service] is available once connected, to send commands.
 */
class MachineClient(
    private val context: Context,
    lifecycle: Lifecycle,
    private val onState: (MachineState) -> Unit
) : DefaultLifecycleObserver {

    var service: MachineService? = null
        private set

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val bound = (binder as MachineService.LocalBinder).service
            service = bound
            bound.addListener(onState)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
        }
    }

    init {
        lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        context.bindService(Intent(context, MachineService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop(owner: LifecycleOwner) {
        service?.removeListener(onState)
        service = null
        context.unbindService(connection)
    }
}
