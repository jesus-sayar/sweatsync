package com.sweatsync.ui

import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.DateValidatorPointBackward
import com.google.android.material.datepicker.MaterialDatePicker
import java.util.Calendar
import java.util.TimeZone

object Dates {

    /** 01/01/1975: where the birth-date picker opens until the user chooses a real date. */
    const val DEFAULT_BIRTH = 157_766_400_000L

    /** Material date picker for a birth date. Calls [onPicked] with local noon of the chosen day. */
    fun pickBirth(activity: AppCompatActivity, currentMs: Long, onPicked: (Long) -> Unit) {
        val picker = MaterialDatePicker.Builder.datePicker()
            .setSelection(localToUtc(if (currentMs != 0L) currentMs else DEFAULT_BIRTH))
            .setCalendarConstraints(CalendarConstraints.Builder().setValidator(DateValidatorPointBackward.now()).build())
            .build()
        picker.addOnPositiveButtonClickListener { onPicked(utcToLocalNoon(it)) }
        picker.show(activity.supportFragmentManager, "birth")
    }

    /** The picker works in UTC midnight; we store local noon so the day never shifts with the time zone. */
    private fun utcToLocalNoon(utc: Long): Long {
        val day = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utc }
        return Calendar.getInstance().apply {
            clear()
            set(day.get(Calendar.YEAR), day.get(Calendar.MONTH), day.get(Calendar.DAY_OF_MONTH), 12, 0, 0)
        }.timeInMillis
    }

    private fun localToUtc(local: Long): Long {
        val day = Calendar.getInstance().apply { timeInMillis = local }
        return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(day.get(Calendar.YEAR), day.get(Calendar.MONTH), day.get(Calendar.DAY_OF_MONTH))
        }.timeInMillis
    }
}
