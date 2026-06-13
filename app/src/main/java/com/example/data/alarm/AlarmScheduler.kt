package com.example.data.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.data.model.Alarm
import java.util.*

class AlarmScheduler(private val context: Context) {
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun schedule(alarm: Alarm, forceTomorrow: Boolean = false) {
        if (!alarm.isEnabled) {
            cancel(alarm)
            return
        }

        val triggerTimeMs = calculateNextTriggerTime(alarm.hour, alarm.minute, alarm.repeatDays, forceTomorrow)
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = "com.example.ACTION_ALARM_TRIGGER"
            putExtra("ALARM_ID", alarm.id)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            alarm.id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerTimeMs,
                        pendingIntent
                    )
                } else {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerTimeMs,
                        pendingIntent
                    )
                }
            } else {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTimeMs,
                    pendingIntent
                )
            }
            Log.d("AlarmScheduler", "Alarm ${alarm.id} scheduled for ${Date(triggerTimeMs)}")

            // Schedule Pre-Reminder if enabled
            if (alarm.preReminderEnabled) {
                val preTriggerMs = triggerTimeMs - (alarm.preReminderMinutes * 60 * 1000)
                if (preTriggerMs > System.currentTimeMillis()) {
                    val preIntent = Intent(context, AlarmReceiver::class.java).apply {
                        action = "com.example.ACTION_ALARM_TRIGGER"
                        putExtra("ALARM_ID", alarm.id)
                        putExtra("IS_PRE_REMINDER", true)
                    }
                    val prePendingIntent = PendingIntent.getBroadcast(
                        context,
                        alarm.id + 200000,
                        preIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        if (alarmManager.canScheduleExactAlarms()) {
                            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, preTriggerMs, prePendingIntent)
                        } else {
                            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, preTriggerMs, prePendingIntent)
                        }
                    } else {
                        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, preTriggerMs, prePendingIntent)
                    }
                    Log.d("AlarmScheduler", "Pre-reminder for alarm ${alarm.id} scheduled for ${Date(preTriggerMs)}")
                }
            } else {
                cancelPreReminderOnly(alarm.id)
            }
        } catch (e: Exception) {
            Log.e("AlarmScheduler", "Failed to schedule alarm", e)
        }
    }

    fun cancel(alarm: Alarm) {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = "com.example.ACTION_ALARM_TRIGGER"
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            alarm.id,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
            Log.d("AlarmScheduler", "Alarm ${alarm.id} cancelled")
        }
        cancelPreReminderOnly(alarm.id)
        cancelSnooze(alarm.id)
    }

    private fun cancelPreReminderOnly(alarmId: Int) {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = "com.example.ACTION_ALARM_TRIGGER"
        }
        val prePendingIntent = PendingIntent.getBroadcast(
            context,
            alarmId + 200000,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (prePendingIntent != null) {
            alarmManager.cancel(prePendingIntent)
            prePendingIntent.cancel()
            Log.d("AlarmScheduler", "Pre-reminder for alarm $alarmId cancelled")
        }
    }

    fun scheduleSnooze(alarmId: Int, intervalMinutes: Int) {
        val triggerTimeMs = System.currentTimeMillis() + (intervalMinutes * 60 * 1000)
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = "com.example.ACTION_ALARM_TRIGGER"
            putExtra("ALARM_ID", alarmId)
            putExtra("IS_SNOOZE_TRIGGER", true)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            alarmId + 100000,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerTimeMs,
                        pendingIntent
                    )
                } else {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerTimeMs,
                        pendingIntent
                    )
                }
            } else {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTimeMs,
                    pendingIntent
                )
            }
            Log.d("AlarmScheduler", "Alarm $alarmId SNOOZE scheduled for ${Date(triggerTimeMs)}")
        } catch (e: Exception) {
            Log.e("AlarmScheduler", "Failed to schedule snooze", e)
        }
    }

    fun cancelSnooze(alarmId: Int) {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = "com.example.ACTION_ALARM_TRIGGER"
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            alarmId + 100000,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
            Log.d("AlarmScheduler", "Snooze for alarm $alarmId cancelled")
        }
    }

    private fun calculateNextTriggerTime(hour: Int, minute: Int, repeatDays: List<Int>, forceTomorrow: Boolean = false): Long {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val now = Calendar.getInstance()
        if (forceTomorrow) {
            now.add(Calendar.DAY_OF_YEAR, 1)
            if (calendar.before(now)) {
                calendar.add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        if (repeatDays.isEmpty()) {
            // Non-repeating alarm (Once)
            if (calendar.before(now)) {
                calendar.add(Calendar.DAY_OF_YEAR, 1)
            }
            return calendar.timeInMillis
        } else {
            // Repeating alarm
            var minDiffDays = 8
            val currentDayOfWeek = convertCalendarDayToCustomDay(now.get(Calendar.DAY_OF_WEEK))

            for (day in repeatDays) {
                var diff = day - currentDayOfWeek
                if (diff < 0) {
                    diff += 7
                } else if (diff == 0) {
                    // Same day of week, check if time has already passed
                    val todayAlarm = Calendar.getInstance().apply {
                        timeInMillis = calendar.timeInMillis
                    }
                    if (todayAlarm.before(now)) {
                        diff = 7 // Scheduled for next week
                    }
                }
                if (diff < minDiffDays) {
                    minDiffDays = diff
                }
            }

            calendar.add(Calendar.DAY_OF_YEAR, minDiffDays)
            return calendar.timeInMillis
        }
    }

    /**
     * Converts Calendar.DAY_OF_WEEK:
     * SUNDAY(1) -> 7
     * MONDAY(2) -> 1
     * ...
     * SATURDAY(7) -> 6
     */
    private fun convertCalendarDayToCustomDay(calendarDay: Int): Int {
        return when (calendarDay) {
            Calendar.MONDAY -> 1
            Calendar.TUESDAY -> 2
            Calendar.WEDNESDAY -> 3
            Calendar.THURSDAY -> 4
            Calendar.FRIDAY -> 5
            Calendar.SATURDAY -> 6
            Calendar.SUNDAY -> 7
            else -> 1
        }
    }

    fun scheduleNextAutoSync(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(ctx, AlarmReceiver::class.java).apply {
            action = "com.example.ACTION_AUTO_HOLIDAY_SYNC"
        }
        val pendingIntent = PendingIntent.getBroadcast(
            ctx,
            999999,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val triggerTimeMs = calculateNextHolidaySyncTime()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (am.canScheduleExactAlarms()) {
                    am.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerTimeMs,
                        pendingIntent
                    )
                } else {
                    am.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerTimeMs,
                        pendingIntent
                    )
                }
            } else {
                am.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTimeMs,
                    pendingIntent
                )
            }
            Log.d("AlarmScheduler", "Next automatic holiday sync scheduled for: ${Date(triggerTimeMs)}")
        } catch (e: Exception) {
            Log.e("AlarmScheduler", "Failed to schedule next auto sync", e)
        }
    }

    private fun calculateNextHolidaySyncTime(): Long {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val candidates = mutableListOf<Calendar>()

        // Candidate 1: 1st of this month
        val c1 = (calendar.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, 1) }
        candidates.add(c1)

        // Candidate 2: 15th of this month
        val c2 = (calendar.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, 15) }
        candidates.add(c2)

        // Candidate 3: 1st of next month
        val c3 = (calendar.clone() as Calendar).apply {
            add(Calendar.MONTH, 1)
            set(Calendar.DAY_OF_MONTH, 1)
        }
        candidates.add(c3)

        // Candidate 4: 15th of next month
        val c4 = (calendar.clone() as Calendar).apply {
            add(Calendar.MONTH, 1)
            set(Calendar.DAY_OF_MONTH, 15)
        }
        candidates.add(c4)

        val now = System.currentTimeMillis()
        val futureCandidates = candidates.filter { it.timeInMillis > now }

        val nextCalendar = futureCandidates.minByOrNull { it.timeInMillis } ?: c3
        return nextCalendar.timeInMillis
    }
}
