package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "alarms")
data class Alarm(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val hour: Int,
    val minute: Int,
    val label: String,
    val isEnabled: Boolean = true,
    val repeatDaysString: String = "", // e.g. "1,2,3" (1=Monday, ..., 7=Sunday) or empty for once
    val skipOnHolidays: Boolean = false,
    val onlyOnHolidays: Boolean = false,
    val customToneUri: String? = null,
    val customToneName: String? = null,
    val snoozeEnabled: Boolean = false,
    val snoozeInterval: Int = 5, // in minutes
    val snoozeRepeats: Int = 3, // number of times
    val remainingSnoozes: Int = 0,
    val preReminderEnabled: Boolean = false,
    val preReminderMinutes: Int = 5, // in minutes
    val customOrder: Int = 0
) {
    val repeatDays: List<Int>
        get() = if (repeatDaysString.isEmpty()) {
            emptyList()
        } else {
            repeatDaysString.split(",").mapNotNull { it.toIntOrNull() }
        }
}
