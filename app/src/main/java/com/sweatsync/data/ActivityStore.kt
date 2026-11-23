package com.sweatsync.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.sweatsync.ble.MachineType

/** Local history. Only the values shown on screen are stored, never the .fit file. */
class ActivityStore(context: Context) : SQLiteOpenHelper(context.applicationContext, "activities.db", null, 1) {

    data class Item(
        val id: Long,
        val type: MachineType,
        val startMs: Long,
        val durationSec: Long,
        val distanceM: Double,
        val kcal: Double,
        val avgSpeedKmh: Double
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE activities (id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT NOT NULL, " +
                "start_ms INTEGER NOT NULL, duration_sec INTEGER NOT NULL, distance_m REAL NOT NULL, " +
                "kcal REAL NOT NULL, avg_speed REAL NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun insert(i: Item): Long {
        val v = ContentValues().apply {
            put("type", i.type.name)
            put("start_ms", i.startMs)
            put("duration_sec", i.durationSec)
            put("distance_m", i.distanceM)
            put("kcal", i.kcal)
            put("avg_speed", i.avgSpeedKmh)
        }
        return writableDatabase.insert("activities", null, v)
    }

    fun all(): List<Item> = query(null, null)

    fun delete(id: Long) {
        writableDatabase.delete("activities", "id = ?", arrayOf(id.toString()))
    }

    fun get(id: Long): Item? = query("id = ?", arrayOf(id.toString())).firstOrNull()

    private fun query(where: String?, args: Array<String>?): List<Item> {
        val out = ArrayList<Item>()
        readableDatabase.query("activities", null, where, args, null, null, "start_ms DESC").use { c ->
            while (c.moveToNext()) {
                out += Item(
                    c.getLong(c.getColumnIndexOrThrow("id")),
                    MachineType.values().firstOrNull { it.name == c.getString(c.getColumnIndexOrThrow("type")) }
                        ?: MachineType.TREADMILL,
                    c.getLong(c.getColumnIndexOrThrow("start_ms")),
                    c.getLong(c.getColumnIndexOrThrow("duration_sec")),
                    c.getDouble(c.getColumnIndexOrThrow("distance_m")),
                    c.getDouble(c.getColumnIndexOrThrow("kcal")),
                    c.getDouble(c.getColumnIndexOrThrow("avg_speed"))
                )
            }
        }
        return out
    }
}
