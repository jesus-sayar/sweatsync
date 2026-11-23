package com.sweatsync.data

import android.content.Context
import com.sweatsync.ble.MachineType

/** App preferences: the user's profile, the last paired machine and which platforms receive uploads. */
class Prefs(context: Context) {

    private val store = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    val weightKg: Float get() = store.getFloat(KEY_WEIGHT, 0f)
    val birthMillis: Long get() = store.getLong(KEY_BIRTH, 0L)

    /** True once the onboarding data (needed to estimate calories) has been entered. */
    val hasProfile: Boolean get() = weightKg > 0f && birthMillis != 0L

    fun saveProfile(weightKg: Float, birthMillis: Long) {
        store.edit().putFloat(KEY_WEIGHT, weightKg).putLong(KEY_BIRTH, birthMillis).apply()
    }

    val lastAddress: String? get() = store.getString(KEY_LAST_ADDRESS, null)
    val lastName: String? get() = store.getString(KEY_LAST_NAME, null)
    val lastType: MachineType? get() = store.getString(KEY_LAST_TYPE, null)?.let { stored ->
        MachineType.values().firstOrNull { it.name == stored }
    }

    fun saveLastDevice(address: String, name: String, type: MachineType?) {
        store.edit().putString(KEY_LAST_ADDRESS, address).putString(KEY_LAST_NAME, name)
            .putString(KEY_LAST_TYPE, type?.name).apply()
    }

    fun saveLastType(type: MachineType) {
        store.edit().putString(KEY_LAST_TYPE, type.name).apply()
    }

    fun isUploadEnabled(targetId: String) = store.getBoolean(uploadKey(targetId), true)

    fun setUploadEnabled(targetId: String, enabled: Boolean) {
        store.edit().putBoolean(uploadKey(targetId), enabled).apply()
    }

    private fun uploadKey(targetId: String) = "upload_$targetId"

    private companion object {
        const val FILE = "sweatsync"
        const val KEY_WEIGHT = "weight_kg"
        const val KEY_BIRTH = "birth_ms"
        const val KEY_LAST_ADDRESS = "last_address"
        const val KEY_LAST_NAME = "last_name"
        const val KEY_LAST_TYPE = "last_type"
    }
}
