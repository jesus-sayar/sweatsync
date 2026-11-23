package com.sweatsync.ui

import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.sweatsync.R

class StatSpec(val key: String, val label: Int, val unit: Int?, val value: String = "")

/** LIVE: the recording screen (caption, big number, unit below). SUMMARY: a finished activity (label, value + unit). */
enum class StatStyle(val layout: Int) {
    LIVE(R.layout.item_stat_live),
    SUMMARY(R.layout.item_stat_summary),
}

/** Lays statistics out in rows, Strava style: no boxes, just type, with hairlines between live rows. */
object StatGrid {

    /**
     * Fills [container] with rows of [columns] cells; a shorter last row spreads its cells over the whole width.
     * Returns the value views by key so they can be updated in place.
     */
    fun build(container: LinearLayout, specs: List<StatSpec>, columns: Int, style: StatStyle): Map<String, TextView> {
        container.removeAllViews()
        val context = container.context
        val inflater = LayoutInflater.from(context)
        val rowGap = context.resources.getDimensionPixelSize(R.dimen.stat_row_gap)
        val values = LinkedHashMap<String, TextView>()
        specs.chunked(columns).forEachIndexed { index, rowSpecs ->
            if (index > 0 && style == StatStyle.LIVE) {
                container.addView(
                    View(context).apply { setBackgroundColor(context.getColor(R.color.outline)) },
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.resources.getDimensionPixelSize(R.dimen.hairline))
                )
            }
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                if (index > 0 && style == StatStyle.SUMMARY) setPadding(0, rowGap, 0, 0)
            }
            rowSpecs.forEach { spec ->
                val cell = inflater.inflate(style.layout, row, false)
                values[spec.key] = bind(cell, spec)
                row.addView(cell)
            }
            container.addView(row)
        }
        return values
    }

    /** Fills one cell inflated from a [StatStyle] layout and returns its value view. */
    fun bind(cell: View, spec: StatSpec): TextView {
        cell.findViewById<TextView>(R.id.tvLabel).setText(spec.label)
        cell.findViewById<TextView>(R.id.tvUnit).apply {
            visibility = if (spec.unit != null) View.VISIBLE else View.GONE
            spec.unit?.let(::setText)
        }
        return cell.findViewById<TextView>(R.id.tvValue).apply { text = spec.value }
    }
}
