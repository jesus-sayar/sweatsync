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
import com.sweatsync.data.ActivityStore
import com.sweatsync.util.Formats

/** The history list, as Strava feed cards: machine and date, an automatic name, then distance, time and average. */
class ActivityAdapter(private val onPick: (ActivityStore.Item) -> Unit) :
    ListAdapter<ActivityStore.Item, ActivityAdapter.Holder>(Diff) {

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.imgType)
        val machine: TextView = view.findViewById(R.id.tvMachine)
        val whenText: TextView = view.findViewById(R.id.tvWhen)
        val title: TextView = view.findViewById(R.id.tvTitle)
        val distance: View = view.findViewById(R.id.statDistance)
        val time: View = view.findViewById(R.id.statTime)
        val average: View = view.findViewById(R.id.statAverage)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_activity, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = getItem(position)
        val context = holder.itemView.context
        holder.icon.setImageResource(item.type.iconRes)
        holder.machine.setText(item.type.nameRes)
        holder.whenText.text = item.whenText(context)
        holder.title.text = item.title(context)
        StatGrid.bind(holder.distance, StatSpec("distance", R.string.label_distance, R.string.unit_km, Formats.kilometres(item.distanceM)))
        StatGrid.bind(holder.time, StatSpec("time", R.string.time, null, Formats.duration(item.durationSec)))
        StatGrid.bind(holder.average, item.averageSpec(short = true))
        holder.itemView.setOnClickListener { onPick(item) }
    }

    private object Diff : DiffUtil.ItemCallback<ActivityStore.Item>() {
        override fun areItemsTheSame(a: ActivityStore.Item, b: ActivityStore.Item) = a.id == b.id
        override fun areContentsTheSame(a: ActivityStore.Item, b: ActivityStore.Item) = a == b
    }
}
