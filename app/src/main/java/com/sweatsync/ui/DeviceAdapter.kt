package com.sweatsync.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.sweatsync.R
import com.sweatsync.ble.MachineDevice
import com.sweatsync.ble.MachineType

/** One machine in the list. [type] is what we know about it (advertised, or remembered from the last time). */
data class DeviceRow(val device: MachineDevice, val type: MachineType?, val isLastUsed: Boolean)

/**
 * The machine list. Rows are diffed, so a signal-strength refresh rebinds a row instead of recreating it,
 * which keeps taps from being lost while the scan updates the list.
 */
class DeviceAdapter(private val onPick: (MachineDevice) -> Unit) :
    ListAdapter<DeviceRow, DeviceAdapter.Holder>(Diff) {

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.imgType)
        val name: TextView = view.findViewById(R.id.tvName)
        val sub: TextView = view.findViewById(R.id.tvSub)
        val state: TextView = view.findViewById(R.id.tvState)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_device, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = getItem(position)
        val context = holder.itemView.context
        holder.icon.setImageResource(row.type.iconRes)
        holder.name.text = row.device.name
        holder.sub.text = listOfNotNull(
            row.type?.let { context.getString(it.nameRes) },
            context.getString(signalRes(row.device.rssi)),
        ).joinToString(" · ")
        holder.state.text = if (row.isLastUsed) context.getString(R.string.last_used) else ""
        holder.itemView.setOnClickListener { onPick(row.device) }
    }

    private fun signalRes(rssi: Int) = when {
        rssi >= STRONG_DBM -> R.string.signal_strong
        rssi >= MEDIUM_DBM -> R.string.signal_medium
        else -> R.string.signal_weak
    }

    private object Diff : DiffUtil.ItemCallback<DeviceRow>() {
        override fun areItemsTheSame(a: DeviceRow, b: DeviceRow) = a.device.address == b.device.address

        // lastSeenMs changes on every advertisement and is not shown, so it is left out of the comparison.
        override fun areContentsTheSame(a: DeviceRow, b: DeviceRow) =
            a.device.copy(lastSeenMs = 0) == b.device.copy(lastSeenMs = 0) && a.type == b.type && a.isLastUsed == b.isLastUsed
    }

    private companion object {
        const val STRONG_DBM = -65
        const val MEDIUM_DBM = -80
    }
}
