package com.example.data.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import com.example.AlarmAlertActivity
import com.example.data.local.AppDatabase
import com.example.data.repository.AlarmRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

class AlarmService : Service() {
    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var mediaSession: MediaSession? = null
    private var currentAlarmId: Int = -1

    companion object {
        const val CHANNEL_ID = "ALARM_FOREGROUND_SERVICE_CHANNEL"
        const val NOTIFICATION_ID = 9999
        const val ACTION_DISMISS = "com.example.ACTION_DISMISS_ALARM"
        const val ACTION_SNOOZE = "com.example.ACTION_SNOOZE_ALARM"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d("AlarmService", "onStartCommand action: $action")

        if (action == ACTION_DISMISS) {
            stopAlarm()
            stopSelf()
            return START_NOT_STICKY
        }

        if (action == ACTION_SNOOZE) {
            triggerSnooze()
            return START_NOT_STICKY
        }

        val alarmId = intent?.getIntExtra("ALARM_ID", -1) ?: -1
        currentAlarmId = alarmId
        
        val label = intent?.getStringExtra("ALARM_LABEL") ?: "알람"
        val timeStr = intent?.getStringExtra("ALARM_TIME") ?: "--:--"
        val customToneUriStr = intent?.getStringExtra("CUSTOM_TONE_URI")

        startForeground(NOTIFICATION_ID, buildNotification(alarmId, label, timeStr, customToneUriStr))
        
        // Launch AlarmAlertActivity directly from service to guarantee immediate pop-up
        val alertIntent = Intent(this, AlarmAlertActivity::class.java).apply {
            setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("ALARM_ID", alarmId)
            putExtra("ALARM_LABEL", label)
            putExtra("ALARM_TIME", timeStr)
            putExtra("CUSTOM_TONE_URI", customToneUriStr)
        }
        try {
            startActivity(alertIntent)
        } catch (e: Exception) {
            Log.e("AlarmService", "Failed to directly launch AlarmAlertActivity from AlarmService", e)
        }
        
        playAlarmSound(customToneUriStr)
        startVibrator()
        setupMediaSession()

        return START_STICKY
    }

    override fun onDestroy() {
        stopAlarm()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startVibrator() {
        try {
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }

            val pattern = longArrayOf(0, 1000, 1000, 1000) // Vibrate pattern: 1s rattle, 1s sleep
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            vibrator?.let {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    it.vibrate(
                        android.os.VibrationEffect.createWaveform(pattern, 0),
                        audioAttributes
                    )
                } else {
                    @Suppress("DEPRECATION")
                    it.vibrate(pattern, 0)
                }
            }
        } catch (e: Exception) {
            Log.e("AlarmService", "Vibrator start failed", e)
        }
    }

    private fun playAlarmSound(customToneUriStr: String?) {
        stopAudio()
        
        try {
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                isLooping = true
            }

            var loaded = false
            if (!customToneUriStr.isNullOrBlank() && customToneUriStr.contains("://")) {
                try {
                    mediaPlayer?.setDataSource(this, Uri.parse(customToneUriStr))
                    loaded = true
                } catch (e: Exception) {
                    Log.e("AlarmService", "Failed to load custom tone Uri: $customToneUriStr, resetting", e)
                    mediaPlayer?.reset()
                    mediaPlayer?.apply {
                        setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ALARM)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build()
                        )
                        isLooping = true
                    }
                }
            } else if (!customToneUriStr.isNullOrBlank()) {
                try {
                    val file = File(customToneUriStr)
                    if (file.exists()) {
                        mediaPlayer?.setDataSource(file.absolutePath)
                        loaded = true
                    }
                } catch (e: Exception) {
                    Log.e("AlarmService", "Failed to load custom tone file path: $customToneUriStr, resetting", e)
                    mediaPlayer?.reset()
                    mediaPlayer?.apply {
                        setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ALARM)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build()
                        )
                        isLooping = true
                    }
                }
            }

            if (!loaded) {
                try {
                    val systemAlarmUri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
                    if (systemAlarmUri != null) {
                        mediaPlayer?.setDataSource(this, systemAlarmUri)
                        loaded = true
                    }
                } catch (e: Exception) {
                    Log.e("AlarmService", "Failed to load default alarm alert Uri, resetting player", e)
                    mediaPlayer?.reset()
                    mediaPlayer?.apply {
                        setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ALARM)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build()
                        )
                        isLooping = true
                    }
                }

                if (!loaded) {
                    try {
                        val backupUri = android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI
                            ?: android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE)
                        if (backupUri != null) {
                            mediaPlayer?.setDataSource(this, backupUri)
                            loaded = true
                        }
                    } catch (e2: Exception) {
                        Log.e("AlarmService", "Failed to load backup alert Uri", e2)
                    }
                }
            }

            mediaPlayer?.prepare()
            mediaPlayer?.start()
        } catch (e: Exception) {
            Log.e("AlarmService", "Failed to setup and start media player", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "알람 벨소리 및 진동 서비스",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "알람 작동 시 벨소리를 원활하게 재생하기 위한 포그라운드 채널"
                enableVibration(true)
                setSound(null, null) // Audio is handled independently by MediaPlayer
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(alarmId: Int, label: String, timeStr: String, customToneUriStr: String?): Notification {
        val dismissIntent = Intent(this, AlarmService::class.java).apply {
            action = ACTION_DISMISS
        }
        val dismissPendingIntent = PendingIntent.getService(
            this,
            alarmId + 600000,
            dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val snoozeIntent = Intent(this, AlarmService::class.java).apply {
            action = ACTION_SNOOZE
        }
        val snoozePendingIntent = PendingIntent.getService(
            this,
            alarmId + 700000,
            snoozeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val alertIntent = Intent(this, AlarmAlertActivity::class.java).apply {
            setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("ALARM_ID", alarmId)
            putExtra("ALARM_LABEL", label)
            putExtra("ALARM_TIME", timeStr)
            putExtra("CUSTOM_TONE_URI", customToneUriStr)
        }

        val contentPendingIntent = PendingIntent.getActivity(
            this,
            alarmId + 800000,
            alertIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("알람이 울리는 중: $timeStr")
            .setContentText(label)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(contentPendingIntent, true)
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                android.R.drawable.ic_lock_idle_alarm,
                "5분 다시 울림 (Snooze)",
                snoozePendingIntent
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "알람 해제 (Dismiss)",
                dismissPendingIntent
            )
            .build()
    }

    private fun setupMediaSession() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                mediaSession = MediaSession(this, "CosmicAlarmMediaSession").apply {
                    setCallback(object : MediaSession.Callback() {
                        override fun onPause() {
                            super.onPause()
                            triggerSnooze()
                        }

                        override fun onStop() {
                            super.onStop()
                            triggerSnooze()
                        }

                        override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                            val keyEvent = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                            if (keyEvent != null && keyEvent.action == KeyEvent.ACTION_DOWN) {
                                val keyCode = keyEvent.keyCode
                                // Exclude Galaxy Watch bezel rotation and volume keys
                                if (keyCode == 260 || keyCode == 261 || // KEYCODE_NAVIGATE_PREVIOUS / KEYCODE_NAVIGATE_NEXT
                                    keyCode == 262 || keyCode == 263 || // KEYCODE_NAVIGATE_IN / KEYCODE_NAVIGATE_OUT
                                    keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
                                    keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
                                    keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) {
                                    return false
                                }
                                triggerSnooze()
                                return true
                            }
                            return super.onMediaButtonEvent(mediaButtonIntent)
                        }
                    })
                    val state = PlaybackState.Builder()
                        .setActions(
                            PlaybackState.ACTION_PLAY or
                            PlaybackState.ACTION_PAUSE or
                            PlaybackState.ACTION_STOP
                        )
                        .setState(PlaybackState.STATE_PLAYING, 0, 1.0f)
                        .build()
                    setPlaybackState(state)
                    isActive = true
                }
            } catch (e: Exception) {
                Log.e("AlarmService", "Failed to setup MediaSession", e)
            }
        }
    }

    private fun triggerSnooze() {
        Log.d("AlarmService", "Wearable/media button clicked: triggering 5-minute snooze")
        val alarmId = currentAlarmId
        if (alarmId == -1 || alarmId == -999) {
            stopAlarm()
            stopSelf()
            return
        }

        val context = this
        stopAlarm()
        stopSelf()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val database = AppDatabase.getDatabase(context)
                val alarmDao = database.alarmDao()
                val alarmRepository = AlarmRepository(alarmDao, context)
                val alarm = alarmDao.getAlarmById(alarmId)
                if (alarm != null) {
                    val intervalToUse = 5 // "기본 5분 스누즈가 동작하게 해줘"
                    
                    CoroutineScope(Dispatchers.Main).launch {
                        android.widget.Toast.makeText(
                            context,
                            "5분 후 다시 울립니다.",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }

                    if (alarm.snoozeEnabled) {
                        val currentRem = alarm.remainingSnoozes
                        if (currentRem > 0) {
                            val nextRem = currentRem - 1
                            val updatedAlarm = alarm.copy(remainingSnoozes = nextRem)
                            alarmRepository.updateAlarm(updatedAlarm)

                            val scheduler = AlarmScheduler(context)
                            scheduler.scheduleSnooze(alarm.id, intervalToUse)
                            Log.d("AlarmService", "Alarm ${alarm.id} snoozed. Remaining repeats: $nextRem")
                        } else {
                            val scheduler = AlarmScheduler(context)
                            scheduler.scheduleSnooze(alarm.id, intervalToUse)
                        }
                    } else {
                        val scheduler = AlarmScheduler(context)
                        scheduler.scheduleSnooze(alarm.id, intervalToUse)
                    }

                    // Send local broadcast to dismiss overlays
                    val dismissOverlayIntent = Intent("com.example.ACTION_DISMISS_ALARM_RINGING").apply {
                        putExtra("ALARM_ID", alarmId)
                        setPackage(packageName)
                    }
                    sendBroadcast(dismissOverlayIntent)
                }
            } catch (e: Exception) {
                Log.e("AlarmService", "Error during wearable key trigger snooze", e)
            }
        }
    }

    private fun stopAlarm() {
        stopAudio()
        stopVibrator()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mediaSession?.isActive = false
                mediaSession?.release()
            }
        } catch (e: Exception) {
            Log.e("AlarmService", "MediaSession release failed", e)
        } finally {
            mediaSession = null
        }
    }

    private fun stopAudio() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.stop()
                }
                it.release()
            }
        } catch (e: Exception) {
            Log.e("AlarmService", "Failed to release MediaPlayer", e)
        } finally {
            mediaPlayer = null
        }
    }

    private fun stopVibrator() {
        try {
            vibrator?.cancel()
        } catch (e: Exception) {
            Log.e("AlarmService", "Failed to stop vibrator", e)
        } finally {
            vibrator = null
        }
    }
}
