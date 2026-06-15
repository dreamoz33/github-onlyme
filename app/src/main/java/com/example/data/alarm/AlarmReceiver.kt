package com.example.data.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.data.local.AppDatabase
import com.example.data.repository.AlarmRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import android.media.RingtoneManager
import android.media.AudioAttributes

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val alarmId = intent.getIntExtra("ALARM_ID", -1)
        val isSnoozeTrigger = intent.getBooleanExtra("IS_SNOOZE_TRIGGER", false)
        val isPreReminder = intent.getBooleanExtra("IS_PRE_REMINDER", false)

        Log.d("AlarmReceiver", "onReceive action: $action, alarmId: $alarmId, isSnoozeTrigger: $isSnoozeTrigger, isPreReminder: $isPreReminder")

        if (action == Intent.ACTION_BOOT_COMPLETED) {
            rescheduleAllAlarms(context)
            AlarmScheduler(context).scheduleNextAutoSync(context)
            return
        }

        if (action == "com.example.ACTION_AUTO_HOLIDAY_SYNC") {
            val goAsync = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val sharedPrefs = context.getSharedPreferences("alarm_app_prefs", Context.MODE_PRIVATE)
                    val govtKey = sharedPrefs.getString("govt_api_key", "").orEmpty()
                    val resolvedKey = if (govtKey.isNotBlank()) govtKey else null

                    val geminiKey = try {
                        val key = com.example.BuildConfig.GEMINI_API_KEY
                        if (key != "MY_GEMINI_API_KEY") key else null
                    } catch (e: Exception) {
                        null
                    }

                    val database = AppDatabase.getDatabase(context)
                    val holidayRepository = com.example.data.repository.HolidayRepository(database.holidayDao())
                    val currentYear = Calendar.getInstance().get(Calendar.YEAR)

                    Log.d("AlarmReceiver", "Auto holiday sync running for year $currentYear")
                    val result1 = holidayRepository.syncHolidays(currentYear, resolvedKey, geminiKey)
                    val result2 = holidayRepository.syncHolidays(currentYear + 1, resolvedKey, geminiKey)

                    // If user was using a custom government API key, check if sync failed or was forced to fallback due to error
                    if (resolvedKey != null) {
                        var failed = false
                        var errorMsg = ""

                        if (result1 is com.example.data.repository.SyncResult.Error) {
                            failed = true
                            errorMsg = result1.message
                        } else if (result1 is com.example.data.repository.SyncResult.Success && (result1.source.contains("오류") || !result1.source.contains("정부 공공데이터"))) {
                            failed = true
                            errorMsg = "올바르지 않은 API 응답값 또는 만료된 서비스 키"
                        }

                        if (result2 is com.example.data.repository.SyncResult.Error) {
                            failed = true
                            if (errorMsg.isEmpty()) errorMsg = result2.message
                        } else if (result2 is com.example.data.repository.SyncResult.Success && (result2.source.contains("오류") || !result2.source.contains("정부 공공데이터"))) {
                            failed = true
                            if (errorMsg.isEmpty()) errorMsg = "올바르지 않은 API 응답값 또는 만료된 서비스 키"
                        }

                        if (failed) {
                            Log.w("AlarmReceiver", "Public government key failed validity check. Alerting user.")
                            // Show Toast message
                            CoroutineScope(Dispatchers.Main).launch {
                                android.widget.Toast.makeText(
                                    context,
                                    "🚨 [정기 동기화] 공공데이터 인증키 오류 발생! 로컬 수집원으로 대체 완료되었습니다.",
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                            // Show system notification
                            showSyncFailureNotification(context, errorMsg)
                        }
                    }

                    // Save last sync date
                    if (result1 is com.example.data.repository.SyncResult.Success || result2 is com.example.data.repository.SyncResult.Success) {
                        try {
                            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd(E) HH:mm:ss", java.util.Locale.KOREAN)
                            val dateStr = sdf.format(java.util.Date())
                            sharedPrefs.edit().putString("last_holiday_sync_date", dateStr).apply()
                        } catch (e: Exception) {
                            Log.e("AlarmReceiver", "Failed to save last sync date", e)
                        }
                    }

                    // Reschedule for next run
                    AlarmScheduler(context).scheduleNextAutoSync(context)
                    
                    // Local broadcast to update current live UI
                    val syncSuccessBroadcast = Intent("com.example.ACTION_AUTO_SYNC_COMPLETED").apply {
                        setPackage(context.packageName)
                    }
                    context.sendBroadcast(syncSuccessBroadcast)
                } catch (e: Exception) {
                    Log.e("AlarmReceiver", "Failed automatic holiday sync", e)
                } finally {
                    goAsync.finish()
                }
            }
            return
        }

        if (action == "com.example.ACTION_TIMER_TRIGGER") {
            val serviceIntent = Intent(context, AlarmService::class.java).apply {
                putExtra("ALARM_ID", -999)
                putExtra("ALARM_TIME", "타이머")
                putExtra("ALARM_LABEL", "설정한 타이머 시간이 완료되었습니다.")
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } catch (e: Exception) {
                Log.e("AlarmReceiver", "Failed to start AlarmService for timer finished", e)
            }

            val localIntent = Intent("com.example.ACTION_TIMER_TRIGGER").apply {
                setPackage(context.packageName)
            }
            context.sendBroadcast(localIntent)
            return
        }

        if (alarmId == -1) return

        if (action == "com.example.ACTION_DISMISS_PRE_REMINDER") {
            val goAsync = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val database = AppDatabase.getDatabase(context)
                    val alarmDao = database.alarmDao()
                    val alarmRepository = AlarmRepository(alarmDao, context)
                    val alarm = alarmDao.getAlarmById(alarmId)
                    if (alarm != null) {
                        alarmRepository.dismissEarly(alarm)
                        Log.d("AlarmReceiver", "Alarm $alarmId pre-reminder dismissed early by user")
                        
                        // Send bypassed status to current UI for logging or notices
                        val inAppBypassIntent = Intent("com.example.ACTION_ALARM_BYPASSED").apply {
                            putExtra("ALARM_LABEL", alarm.label)
                            putExtra("BYPASS_REASON", "미리알림 화면에서 직접 해제함")
                            setPackage(context.packageName)
                        }
                        context.sendBroadcast(inAppBypassIntent)
                    }
                    val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    notificationManager.cancel(alarmId + 300000)
                } catch (e: Exception) {
                    Log.e("AlarmReceiver", "Error dismissing pre-reminder early", e)
                } finally {
                    goAsync.finish()
                }
            }
            return
        }

        val goAsync = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                processAlarmTrigger(context, alarmId, isSnoozeTrigger, isPreReminder)
            } catch (e: Exception) {
                Log.e("AlarmReceiver", "Error processing alarm trigger", e)
            } finally {
                goAsync.finish()
            }
        }
    }

    private suspend fun processAlarmTrigger(context: Context, alarmId: Int, isSnoozeTrigger: Boolean, isPreReminder: Boolean = false) {
        val database = AppDatabase.getDatabase(context)
        val alarmDao = database.alarmDao()
        val holidayDao = database.holidayDao()
        val alarmRepository = AlarmRepository(alarmDao, context)

        val alarm = alarmDao.getAlarmById(alarmId) ?: return
        if (!alarm.isEnabled && !isSnoozeTrigger) return

        var shouldTrigger = true
        var isTodayHoliday = false
        var holiday: com.example.data.model.Holiday? = null

        if (!isSnoozeTrigger) {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.KOREAN)
            val todayStr = sdf.format(Date())
            isTodayHoliday = holidayDao.isHoliday(todayStr)
            holiday = holidayDao.getHolidayByDate(todayStr)

            Log.d("AlarmReceiver", "Checking holiday state on trigger: date=$todayStr, isHoliday=$isTodayHoliday, name=${holiday?.name}")

            if (alarm.skipOnHolidays && isTodayHoliday) {
                shouldTrigger = false
                Log.d("AlarmReceiver", "Alarm ${alarm.id} bypassed due to: skipOnHolidays = true")
            } else if (alarm.onlyOnHolidays && !isTodayHoliday) {
                shouldTrigger = false
                Log.d("AlarmReceiver", "Alarm ${alarm.id} bypassed due to: onlyOnHolidays = true")
            }
        }

        if (shouldTrigger) {
            if (isPreReminder) {
                showPreReminderNotification(context, alarm)
                return
            }

            // Trigger Foreground Alarm Service
            val labelToUse = if (isSnoozeTrigger) "${alarm.label} (다시 울림)" else alarm.label
            val serviceIntent = Intent(context, AlarmService::class.java).apply {
                putExtra("ALARM_ID", alarm.id)
                putExtra("ALARM_LABEL", labelToUse)
                putExtra("ALARM_TIME", String.format(Locale.KOREAN, "%02d:%02d", alarm.hour, alarm.minute))
                putExtra("CUSTOM_TONE_URI", alarm.customToneUri)
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }

            // If a fresh trigger of a snooze-enabled alarm, initialize remaining snooze count in database
            if (!isSnoozeTrigger && alarm.snoozeEnabled) {
                alarmDao.updateAlarm(alarm.copy(remainingSnoozes = alarm.snoozeRepeats))
            }

            // Also broadcast locally for the live UI to intercept and display the custom overlay
            val inAppIntent = Intent("com.example.ACTION_SHOW_ALARM_SCREEN").apply {
                putExtra("ALARM_ID", alarm.id)
                putExtra("ALARM_LABEL", labelToUse)
                putExtra("ALARM_TIME", String.format(Locale.KOREAN, "%02d:%02d", alarm.hour, alarm.minute))
                putExtra("CUSTOM_TONE_NAME", alarm.customToneName ?: "기본 알람음")
                putExtra("IS_SNOOZE_TRIGGER", isSnoozeTrigger)
                setPackage(context.packageName)
            }
            context.sendBroadcast(inAppIntent)
        } else {
            // Send bypassed status to current UI for logging or notices
            val reason = if (isTodayHoliday) {
                "대체/공휴일 [${holiday?.name}]"
            } else {
                "평일인 관계로 공휴일 모드 활성화"
            }
            val inAppBypassIntent = Intent("com.example.ACTION_ALARM_BYPASSED").apply {
                putExtra("ALARM_LABEL", alarm.label)
                putExtra("BYPASS_REASON", reason)
                setPackage(context.packageName)
            }
            context.sendBroadcast(inAppBypassIntent)
        }

        // Reschedule for repeating alarms or toggle off if non-repeating (only on initial non-snooze trigger)
        if (!isSnoozeTrigger) {
            if (alarm.repeatDays.isEmpty()) {
                // Non-repeating, disable it after ringing
                alarmRepository.updateAlarm(alarm.copy(isEnabled = false))
            } else {
                // Reschedule next repeating instance
                alarmRepository.updateAlarm(alarm)
            }
        }
    }

    private fun showPreReminderNotification(context: Context, alarm: com.example.data.model.Alarm) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        // Ensure channel is created
        val channelId = "ALARM_PRE_REMINDER_CHANNEL"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "미리알림 (알람 예정 알림)",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "알람 작동 전에 미리 알려주는 알림"
            }
            notificationManager.createNotificationChannel(channel)
        }

        // Action Intent for Dismiss Early
        val dismissIntent = Intent(context, AlarmReceiver::class.java).apply {
            action = "com.example.ACTION_DISMISS_PRE_REMINDER"
            putExtra("ALARM_ID", alarm.id)
        }
        val dismissPendingIntent = PendingIntent.getBroadcast(
            context,
            alarm.id + 400000,
            dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // MainActivity Intent
        val mainActivityIntent = Intent(context, com.example.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            alarm.id + 500000,
            mainActivityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val timeStr = String.format(Locale.KOREAN, "%02d:%02d", alarm.hour, alarm.minute)
        val title = "곧 알람이 울립니다: $timeStr"
        val message = if (alarm.label.isNotBlank()) "미리알림 - ${alarm.label}" else "미리알림 - 알람 예정"

        val notification = NotificationCompat.Builder(context, channelId)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(contentPendingIntent)
            .setAutoCancel(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "미리 해제 (Dismiss early)",
                dismissPendingIntent
            )
            .build()

        notificationManager.notify(alarm.id + 300000, notification)
        Log.d("AlarmReceiver", "Pre-reminder notification posted for alarm ${alarm.id}")
    }

    private fun rescheduleAllAlarms(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            val database = AppDatabase.getDatabase(context)
            val alarmDao = database.alarmDao()
            val alarmRepository = AlarmRepository(alarmDao, context)
            
            try {
                val alarms = alarmDao.getAllAlarms().first()
                for (alarm in alarms) {
                    if (alarm.isEnabled) {
                        alarmRepository.updateAlarm(alarm)
                    }
                }
            } catch (e: Exception) {
                Log.e("AlarmReceiver", "Failed reschedule alarms on boot", e)
            }
        }
    }

    private fun showSyncFailureNotification(context: Context, errorDetail: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "HOLIDAY_SYNC_ALERT_CHANNEL"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "공휴일 동기화 알림",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "공공데이터 인증키 만료나 유효성 실패 시 알림"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val mainActivityIntent = Intent(context, com.example.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            999888,
            mainActivityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setContentTitle("공휴일 자동 동기화 오류")
            .setContentText("인증키가 유효하지 않아 자동 수집에 실패했습니다. (로컬 대체 수집 완료)")
            .setStyle(NotificationCompat.BigTextStyle().bigText("정부 공공데이터 API 인증키가 유효하지 않거나 만료되었습니다:\n$errorDetail\n\n대신 로컬 오프라인 계산 알고리즘을 이용해 안전하게 백업 및 동기화를 완료했습니다. 설정을 확인해 주세요."))
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(contentPendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(777666, notification)
    }

    private fun triggerTimerFinishedNotification(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "timer_channel",
                "타이머 알림",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "타이머 완료 시 작동하는 알림 채널입니다."
                enableVibration(true)
                try {
                    val defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                    setSound(defaultSoundUri, AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    )
                } catch (e: Exception) {
                    Log.e("AlarmReceiver", "Error setting channel sound", e)
                }
            }
            notificationManager.createNotificationChannel(channel)
        }

        val defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        
        val mainActivityIntent = Intent(context, com.example.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            999222,
            mainActivityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, "timer_channel")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("⏰ 타이머 종료!")
            .setContentText("설정한 타이머 시간이 완료되었습니다.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(contentPendingIntent)
            .setAutoCancel(true)
            .setSound(defaultSoundUri)
            .setVibrate(longArrayOf(0, 500, 250, 500, 250, 500))
            .build()

        notificationManager.notify(999, notification)

        // Play default notification ringtone
        try {
            val ringtone = RingtoneManager.getRingtone(context, defaultSoundUri)
            ringtone?.play()
        } catch (e: Exception) {
            Log.e("AlarmReceiver", "Failed to play default ringtone in background", e)
        }

        // Vibrate the device
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager
                vibratorManager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            }

            val vibratePattern = longArrayOf(0, 500, 250, 500, 250, 500)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(android.os.VibrationEffect.createWaveform(vibratePattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(vibratePattern, -1)
            }
        } catch (e: Exception) {
            Log.e("AlarmReceiver", "Failed to vibrate in background", e)
        }
    }
}
