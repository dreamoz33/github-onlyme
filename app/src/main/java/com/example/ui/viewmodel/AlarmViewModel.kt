package com.example.ui.viewmodel

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.data.alarm.AlarmService
import com.example.data.alarm.AlarmScheduler
import com.example.data.local.AppDatabase
import com.example.data.model.Alarm
import com.example.data.model.Holiday
import com.example.data.repository.AlarmRepository
import com.example.data.repository.HolidayRepository
import com.example.data.repository.SyncResult
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import java.io.File
import java.util.*
import android.media.RingtoneManager
import android.media.MediaPlayer
import android.media.AudioAttributes
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat

sealed interface SyncState {
    object Idle : SyncState
    object Loading : SyncState
    data class Success(val source: String, val count: Int) : SyncState
    data class Error(val message: String) : SyncState
}

data class ActiveAlarmState(
    val id: Int,
    val label: String,
    val time: String,
    val toneName: String,
    val snoozeEnabled: Boolean = false,
    val snoozeInterval: Int = 5,
    val snoozeRepeats: Int = 3,
    val remainingSnoozes: Int = 0
)

data class BypassedLog(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val reason: String,
    val timestamp: Long = System.currentTimeMillis()
)

class AlarmViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AppDatabase.getDatabase(application)
    private val alarmRepository = AlarmRepository(database.alarmDao(), application)
    private val holidayRepository = HolidayRepository(database.holidayDao())

    private val sharedPrefs = application.getSharedPreferences("alarm_app_prefs", Context.MODE_PRIVATE)

    private val _useCustomOrder = MutableStateFlow(sharedPrefs.getBoolean("use_custom_order", false))
    val useCustomOrder: StateFlow<Boolean> = _useCustomOrder

    val alarms: StateFlow<List<Alarm>> = combine(
        alarmRepository.allAlarms,
        _useCustomOrder
    ) { alarmList, useCustom ->
        if (useCustom) {
            alarmList.sortedBy { it.customOrder }
        } else {
            alarmList.sortedWith(compareBy({ it.hour }, { it.minute }))
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val holidays: StateFlow<List<Holiday>> = holidayRepository.allHolidays
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _syncState = MutableStateFlow<SyncState>(
        if (sharedPrefs.getBoolean("holiday_sync_success", false)) {
            SyncState.Success(
                sharedPrefs.getString("holiday_sync_source", "온라인") ?: "온라인",
                sharedPrefs.getInt("holiday_sync_count", 0)
            )
        } else {
            SyncState.Idle
        }
    )
    val syncState: StateFlow<SyncState> = _syncState

    private val _activeRingingAlarm = MutableStateFlow<ActiveAlarmState?>(null)
    val activeRingingAlarm: StateFlow<ActiveAlarmState?> = _activeRingingAlarm

    private val _bypassedLogs = MutableStateFlow<List<BypassedLog>>(emptyList())
    val bypassedLogs: StateFlow<List<BypassedLog>> = _bypassedLogs

    // Theme Preference State Management
    private val _themeMode = MutableStateFlow(sharedPrefs.getString("theme_mode", "system") ?: "system")
    val themeMode: StateFlow<String> = _themeMode

    fun setThemeMode(mode: String) {
        sharedPrefs.edit().putString("theme_mode", mode).apply()
        _themeMode.value = mode
    }

    // Government API Key State Management
    private val _govtApiKey = MutableStateFlow(sharedPrefs.getString("govt_api_key", "") ?: "")
    val govtApiKey: StateFlow<String> = _govtApiKey

    fun saveGovtApiKey(key: String) {
        sharedPrefs.edit().putString("govt_api_key", key).apply()
        _govtApiKey.value = key
    }

    // --- Timer State Management ---
    private val _timerRemainingSeconds = MutableStateFlow(0L)
    val timerRemainingSeconds: StateFlow<Long> = _timerRemainingSeconds

    private val _timerTotalSeconds = MutableStateFlow(0L)
    val timerTotalSeconds: StateFlow<Long> = _timerTotalSeconds

    private val _timerIsRunning = MutableStateFlow(false)
    val timerIsRunning: StateFlow<Boolean> = _timerIsRunning

    private var timerJob: kotlinx.coroutines.Job? = null
    private var isTimerAlarmTriggered = false

    private fun scheduleBackgroundTimer(context: Context, triggerTimeMs: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, com.example.data.alarm.AlarmReceiver::class.java).apply {
            action = "com.example.ACTION_TIMER_TRIGGER"
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            999111,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
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
            Log.d("AlarmViewModel", "Background timer scheduled for ${Date(triggerTimeMs)}")
        } catch (e: Exception) {
            Log.e("AlarmViewModel", "Failed to schedule background timer", e)
        }
    }

    private fun cancelBackgroundTimer(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, com.example.data.alarm.AlarmReceiver::class.java).apply {
            action = "com.example.ACTION_TIMER_TRIGGER"
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            999111,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pendingIntent != null) {
            am.cancel(pendingIntent)
            pendingIntent.cancel()
            Log.d("AlarmViewModel", "Background timer cancelled")
        }
    }

    fun startTimer(durationSeconds: Long) {
        timerJob?.cancel()
        isTimerAlarmTriggered = false
        val context = getApplication<Application>()
        val endTimeMs = System.currentTimeMillis() + durationSeconds * 1000L
        
        sharedPrefs.edit()
            .putLong("timer_total_seconds", durationSeconds)
            .putLong("timer_end_time_ms", endTimeMs)
            .putLong("timer_paused_remaining_seconds", 0L)
            .apply()
            
        scheduleBackgroundTimer(context, endTimeMs)

        _timerTotalSeconds.value = durationSeconds
        _timerRemainingSeconds.value = durationSeconds
        _timerIsRunning.value = true
        runTimerLoop()
    }

    fun pauseTimer() {
        timerJob?.cancel()
        val context = getApplication<Application>()
        cancelBackgroundTimer(context)
        
        val remaining = _timerRemainingSeconds.value
        sharedPrefs.edit()
            .putLong("timer_end_time_ms", 0L)
            .putLong("timer_paused_remaining_seconds", remaining)
            .apply()

        _timerIsRunning.value = false
    }

    fun resumeTimer() {
        val remaining = _timerRemainingSeconds.value
        if (remaining > 0L) {
            timerJob?.cancel()
            isTimerAlarmTriggered = false
            val context = getApplication<Application>()
            val endTimeMs = System.currentTimeMillis() + remaining * 1000L
            
            sharedPrefs.edit()
                .putLong("timer_end_time_ms", endTimeMs)
                .putLong("timer_paused_remaining_seconds", 0L)
                .apply()
                
            scheduleBackgroundTimer(context, endTimeMs)

            _timerIsRunning.value = true
            runTimerLoop()
        }
    }

    private fun ensureAlarmServicePlaying() {
        val context = getApplication<Application>()
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
            Log.e("AlarmViewModel", "Failed to start AlarmService for timer", e)
        }
    }

    fun resetTimer() {
        timerJob?.cancel()
        isTimerAlarmTriggered = false
        val context = getApplication<Application>()
        cancelBackgroundTimer(context)
        
        // Send dismiss action to stop the looping alarm sound and vibration in AlarmService
        val dismissIntent = Intent(context, AlarmService::class.java).apply {
            action = AlarmService.ACTION_DISMISS
        }
        try {
            context.startService(dismissIntent)
        } catch (e: Exception) {
            Log.e("AlarmViewModel", "Failed to stop AlarmService on reset", e)
        }
        
        sharedPrefs.edit()
            .putLong("timer_total_seconds", 0L)
            .putLong("timer_end_time_ms", 0L)
            .putLong("timer_paused_remaining_seconds", 0L)
            .apply()

        _timerIsRunning.value = false
        _timerRemainingSeconds.value = 0L
        _timerTotalSeconds.value = 0L
    }

    private fun runTimerLoop() {
        timerJob = viewModelScope.launch {
            while (_timerIsRunning.value) {
                val tEndTime = sharedPrefs.getLong("timer_end_time_ms", 0L)
                if (tEndTime > 0L) {
                    val now = System.currentTimeMillis()
                    val remaining = (tEndTime - now) / 1000L
                    _timerRemainingSeconds.value = remaining
                    
                    if (remaining <= 0L && !isTimerAlarmTriggered) {
                        isTimerAlarmTriggered = true
                        ensureAlarmServicePlaying()
                    }
                } else {
                    break
                }
                delay(1000)
            }
        }
    }

    private fun triggerTimerFinishedNotification() {
        val context = getApplication<Application>()
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
                    Log.e("AlarmViewModel", "Error setting channel sound", e)
                }
            }
            notificationManager.createNotificationChannel(channel)
        }

        val defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val notification = NotificationCompat.Builder(context, "timer_channel")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("⏰ 타이머 종료!")
            .setContentText("설정한 타이머 시간이 완료되었습니다.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setSound(defaultSoundUri)
            .setVibrate(longArrayOf(0, 500, 250, 500, 250, 500))
            .build()

        notificationManager.notify(999, notification)

        // Explicitly play ringtone
        try {
            val ringtone = RingtoneManager.getRingtone(context, defaultSoundUri)
            ringtone?.play()
        } catch (e: Exception) {
            Log.e("AlarmViewModel", "Failed to play default ringtone", e)
        }

        // Explicitly vibrate
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
            Log.e("AlarmViewModel", "Failed to vibrate", e)
        }
    }

    // Last Sync Date State Management for Holiday Collection Records
    private val _lastSyncDateStr = MutableStateFlow(sharedPrefs.getString("last_holiday_sync_date", "") ?: "")
    val lastSyncDateStr: StateFlow<String> = _lastSyncDateStr

    fun updateLastSyncDate(timestamp: Long) {
        val sdf = SimpleDateFormat("yyyy-MM-dd(E) HH:mm:ss", Locale.KOREAN)
        val dateStr = sdf.format(Date(timestamp))
        sharedPrefs.edit().putString("last_holiday_sync_date", dateStr).apply()
        _lastSyncDateStr.value = dateStr
    }

    // --- Alarm Backup & Restore Support Slate Flow ---
    val backupFileList = MutableStateFlow<List<String>>(emptyList())

    // Broadcaster to intercept play events dynamically inside the app view
    private val appReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                "com.example.ACTION_SHOW_ALARM_SCREEN" -> {
                    val id = intent.getIntExtra("ALARM_ID", -1)
                    val label = intent.getStringExtra("ALARM_LABEL") ?: "알람"
                    val time = intent.getStringExtra("ALARM_TIME") ?: "--:--"
                    val toneName = intent.getStringExtra("CUSTOM_TONE_NAME") ?: "기본 알람음"
                    
                    if (id != -1) {
                        viewModelScope.launch {
                            val alarm = alarmRepository.getAlarmById(id)
                            _activeRingingAlarm.value = ActiveAlarmState(
                                id = id,
                                label = label,
                                time = time,
                                toneName = toneName,
                                snoozeEnabled = alarm?.snoozeEnabled ?: false,
                                snoozeInterval = alarm?.snoozeInterval ?: 5,
                                snoozeRepeats = alarm?.snoozeRepeats ?: 3,
                                remainingSnoozes = alarm?.remainingSnoozes ?: 0
                            )
                        }
                    }
                }
                "com.example.ACTION_DISMISS_ALARM_RINGING" -> {
                    _activeRingingAlarm.value = null
                }
                "com.example.ACTION_ALARM_BYPASSED" -> {
                    val label = intent.getStringExtra("ALARM_LABEL") ?: "알람"
                    val reason = intent.getStringExtra("BYPASS_REASON") ?: "공휴일 제외"
                    addBypassLog(label, reason)
                }
                "com.example.ACTION_AUTO_SYNC_COMPLETED" -> {
                    Log.d("AlarmViewModel", "Automatic scheduled holiday sync finished successfully.")
                    // Reload the saved last sync date in StateFlow
                    _lastSyncDateStr.value = sharedPrefs.getString("last_holiday_sync_date", "") ?: ""
                }
                "com.example.ACTION_TIMER_TRIGGER" -> {
                    // Do nothing here, as the timer must keep running in negative territory until dismissed
                }
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction("com.example.ACTION_SHOW_ALARM_SCREEN")
            addAction("com.example.ACTION_DISMISS_ALARM_RINGING")
            addAction("com.example.ACTION_ALARM_BYPASSED")
            addAction("com.example.ACTION_AUTO_SYNC_COMPLETED")
            addAction("com.example.ACTION_TIMER_TRIGGER")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            application.registerReceiver(appReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            application.registerReceiver(appReceiver, filter)
        }

        // Initialize Timer State from Background / SharedPreferences
        val tTotal = sharedPrefs.getLong("timer_total_seconds", 0L)
        val tEndTime = sharedPrefs.getLong("timer_end_time_ms", 0L)
        val tPausedRemaining = sharedPrefs.getLong("timer_paused_remaining_seconds", 0L)

        if (tEndTime > 0L) {
            val now = System.currentTimeMillis()
            val remaining = (tEndTime - now) / 1000L
            _timerTotalSeconds.value = tTotal
            _timerRemainingSeconds.value = remaining
            _timerIsRunning.value = true
            isTimerAlarmTriggered = remaining <= 0L
            runTimerLoop()
        } else if (tPausedRemaining > 0L) {
            _timerTotalSeconds.value = tTotal
            _timerRemainingSeconds.value = tPausedRemaining
            _timerIsRunning.value = false
        }

        // Initialize local holidays so the database is populated out of the box
        initialLocalSync()

        // Sync list of files inside the backups folder initially
        updateBackupFileList()

        // Dynamically trigger auto sync scheduling on app load to ensure there is always a running schedule active
        AlarmScheduler(application).scheduleNextAutoSync(application)
    }

    private fun initialLocalSync() {
        viewModelScope.launch {
            val currentYear = Calendar.getInstance().get(Calendar.YEAR)
            val existing = holidayRepository.getHolidaysForYear(currentYear)
            if (existing.isEmpty()) {
                val syncResult = holidayRepository.syncHolidays(currentYear, null, null)
                if (syncResult is SyncResult.Success) {
                    updateLastSyncDate(System.currentTimeMillis())
                }
            }
        }
    }

    fun triggerRinging(id: Int, label: String, time: String, toneUriStr: String? = null) {
        if (id != -1) {
            viewModelScope.launch {
                val alarm = alarmRepository.getAlarmById(id)
                val toneName = if (toneUriStr.isNullOrBlank()) {
                    "기본 알람 벨소리"
                } else {
                    alarm?.customToneName ?: "기기 파일 알람음"
                }
                _activeRingingAlarm.value = ActiveAlarmState(
                    id = id,
                    label = label,
                    time = time,
                    toneName = toneName,
                    snoozeEnabled = alarm?.snoozeEnabled ?: false,
                    snoozeInterval = alarm?.snoozeInterval ?: 5,
                    snoozeRepeats = alarm?.snoozeRepeats ?: 3,
                    remainingSnoozes = alarm?.remainingSnoozes ?: 0
                )
            }
        }
    }

    fun syncHolidays(year: Int, userGovtKey: String?) {
        viewModelScope.launch {
            _syncState.value = SyncState.Loading
            
            // Resolve key to use: user entered or saved key
            val keyToCheck = if (!userGovtKey.isNullOrBlank()) userGovtKey else _govtApiKey.value
            val resolvedKey = if (keyToCheck.isNotBlank()) keyToCheck else null
            
            // Try fetching Gemini Key from secure Build Config
            val geminiKey = try {
                val key = BuildConfig.GEMINI_API_KEY
                if (key != "MY_GEMINI_API_KEY") key else null
            } catch (e: Exception) {
                null
            }

            Log.d("AlarmViewModel", "Initiating holiday sync for year=$year with resolved keys")

            val result = holidayRepository.syncHolidays(year, resolvedKey, geminiKey)
            when (result) {
                is SyncResult.Success -> {
                    sharedPrefs.edit()
                        .putBoolean("holiday_sync_success", true)
                        .putString("holiday_sync_source", result.source)
                        .putInt("holiday_sync_count", result.holidaysCount)
                        .apply()
                    _syncState.value = SyncState.Success(result.source, result.holidaysCount)
                    updateLastSyncDate(System.currentTimeMillis())
                }
                is SyncResult.Error -> {
                    _syncState.value = SyncState.Error(result.message)
                }
            }
        }
    }

    fun addHoliday(holiday: Holiday) {
        viewModelScope.launch {
            holidayRepository.insertHoliday(holiday)
        }
    }

    fun deleteHoliday(holiday: Holiday) {
        viewModelScope.launch {
            holidayRepository.deleteHoliday(holiday)
        }
    }

    fun deleteHolidaysBySelectedYear(year: Int) {
        viewModelScope.launch {
            holidayRepository.deleteHolidaysByYear(year)
        }
    }

    fun addBypassLog(label: String, reason: String) {
        val current = _bypassedLogs.value.toMutableList()
        current.add(0, BypassedLog(label = label, reason = reason))
        _bypassedLogs.value = current.take(30) // Cap log size
    }

    fun dismissRinging() {
        val active = _activeRingingAlarm.value
        _activeRingingAlarm.value = null
        val context = getApplication<Application>()
        val serviceIntent = Intent(context, AlarmService::class.java).apply {
            action = AlarmService.ACTION_DISMISS
        }
        context.startService(serviceIntent)

        if (active != null) {
            viewModelScope.launch {
                val scheduler = AlarmScheduler(context)
                scheduler.cancelSnooze(active.id)
                val alarm = alarmRepository.getAlarmById(active.id)
                if (alarm != null) {
                    alarmRepository.updateAlarm(alarm.copy(remainingSnoozes = 0))
                }
            }
        }
    }

    fun snoozeRinging(customInterval: Int? = null) {
        val active = _activeRingingAlarm.value ?: return
        _activeRingingAlarm.value = null
        val context = getApplication<Application>()
        val serviceIntent = Intent(context, AlarmService::class.java).apply {
            action = AlarmService.ACTION_DISMISS
        }
        context.startService(serviceIntent)

        viewModelScope.launch {
            val alarm = alarmRepository.getAlarmById(active.id)
            if (alarm != null) {
                val intervalToUse = customInterval ?: if (alarm.snoozeEnabled) alarm.snoozeInterval else 5
                
                viewModelScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "${intervalToUse}분 후 다시 울립니다.", android.widget.Toast.LENGTH_SHORT).show()
                }

                if (alarm.snoozeEnabled) {
                    val currentRem = alarm.remainingSnoozes
                    if (currentRem > 0) {
                        val nextRem = currentRem - 1
                        val updatedAlarm = alarm.copy(remainingSnoozes = nextRem)
                        alarmRepository.updateAlarm(updatedAlarm)

                        val scheduler = AlarmScheduler(context)
                        scheduler.scheduleSnooze(alarm.id, intervalToUse)

                        Log.d("AlarmViewModel", "Alarm ${alarm.id} snoozed. Remaining repeats: $nextRem")
                        addBypassLog(alarm.label, "다시 울림 등록 (${intervalToUse}분 뒤 울림, 남은 횟수: ${nextRem}회)")
                    } else {
                        // Remaining repeats exhausted, but side keys override it and force snooze
                        val scheduler = AlarmScheduler(context)
                        scheduler.scheduleSnooze(alarm.id, intervalToUse)
                        addBypassLog(alarm.label, "다시 울림 강제 등록 (횟수 초과 상태, ${intervalToUse}분 뒤 울림)")
                    }
                } else {
                    // Snooze disabled in settings, but side keys override with the interval
                    val scheduler = AlarmScheduler(context)
                    scheduler.scheduleSnooze(alarm.id, intervalToUse)
                    addBypassLog(alarm.label, "다시 울림 강제 등록 (설정 미사용 상태, ${intervalToUse}분 뒤 울림)")
                }
            }
        }
    }

    fun saveAlarm(
        id: Int,
        hour: Int,
        minute: Int,
        label: String,
        isEnabled: Boolean,
        repeatDays: List<Int>,
        skipOnHolidays: Boolean,
        onlyOnHolidays: Boolean,
        customToneUri: String?,
        customToneName: String?,
        snoozeEnabled: Boolean,
        snoozeInterval: Int,
        snoozeRepeats: Int,
        preReminderEnabled: Boolean = false,
        preReminderMinutes: Int = 5
    ) {
        viewModelScope.launch {
            val repeatStr = repeatDays.sorted().joinToString(",")
            
            val currentList = alarms.value
            val existingAlarm = if (id != 0) currentList.find { it.id == id } else null
            val orderToUse = existingAlarm?.customOrder ?: (currentList.minOfOrNull { it.customOrder }?.minus(1) ?: 0)

            val alarm = Alarm(
                id = if (id == 0) 0 else id,
                hour = hour,
                minute = minute,
                label = label,
                isEnabled = isEnabled,
                repeatDaysString = repeatStr,
                skipOnHolidays = skipOnHolidays,
                onlyOnHolidays = onlyOnHolidays,
                customToneUri = customToneUri,
                customToneName = customToneName,
                snoozeEnabled = snoozeEnabled,
                snoozeInterval = snoozeInterval,
                snoozeRepeats = snoozeRepeats,
                remainingSnoozes = 0,
                preReminderEnabled = preReminderEnabled,
                preReminderMinutes = preReminderMinutes,
                customOrder = orderToUse
            )

            if (id == 0) {
                alarmRepository.insertAlarm(alarm)
            } else {
                alarmRepository.updateAlarm(alarm)
            }
        }
    }

    fun deleteAlarm(alarm: Alarm) {
        viewModelScope.launch {
            alarmRepository.deleteAlarm(alarm)
        }
    }

    fun toggleAlarm(alarm: Alarm) {
        viewModelScope.launch {
            alarmRepository.toggleAlarm(alarm)
        }
    }

    fun updateAlarmsOrder(alarms: List<Alarm>) {
        viewModelScope.launch {
            alarms.forEachIndexed { index, alarm ->
                if (alarm.customOrder != index) {
                    alarmRepository.updateAlarm(alarm.copy(customOrder = index))
                }
            }
            sharedPrefs.edit().putBoolean("use_custom_order", true).apply()
            _useCustomOrder.value = true
        }
    }

    fun resetAlarmsOrder() {
        viewModelScope.launch {
            sharedPrefs.edit().putBoolean("use_custom_order", false).apply()
            _useCustomOrder.value = false
            val sortedAlarms = alarms.value.sortedWith(compareBy({ it.hour }, { it.minute }))
            sortedAlarms.forEachIndexed { index, alarm ->
                if (alarm.customOrder != index) {
                    alarmRepository.updateAlarm(alarm.copy(customOrder = index))
                }
            }
        }
    }

    /**
     * Copy selected custom audio file descriptor bytes locally to ensure the alarm service
     * has persistent, reboot-proof readability permission.
     */
    fun saveCustomAudioLocal(uri: Uri, fileName: String): Pair<String, String>? {
        val context = getApplication<Application>()
        return try {
            val destDir = File(context.filesDir, "custom_tones")
            if (!destDir.exists()) {
                destDir.mkdirs()
            }
            
            // Unique file generation
            val ext = fileName.substringAfterLast(".", "mp3")
            val destFile = File(destDir, "tone_${System.currentTimeMillis()}.$ext")
            
            context.contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            
            Log.d("AlarmViewModel", "Audio copied to local storage: ${destFile.absolutePath}")
            Pair(destFile.absolutePath, fileName)
        } catch (e: Exception) {
            Log.e("AlarmViewModel", "Error copying audio descriptor bytes", e)
            null
        }
    }

    fun testTriggerAlarmNow(alarm: Alarm) {
        // Quick active testing mode to immediately show how it works
        viewModelScope.launch {
            val context = getApplication<Application>()
            val activeIntent = Intent("com.example.ACTION_SHOW_ALARM_SCREEN").apply {
                putExtra("ALARM_ID", alarm.id)
                putExtra("ALARM_LABEL", alarm.label)
                putExtra("ALARM_TIME", String.format(Locale.KOREAN, "%02d:%02d", alarm.hour, alarm.minute))
                putExtra("CUSTOM_TONE_NAME", alarm.customToneName ?: "기본 알람음")
                setPackage(context.packageName)
            }
            context.sendBroadcast(activeIntent)

            // Ring matching service with custom tone config
            val serviceIntent = Intent(context, AlarmService::class.java).apply {
                putExtra("ALARM_ID", alarm.id)
                putExtra("ALARM_LABEL", alarm.label.ifBlank { "알람" })
                putExtra("ALARM_TIME", String.format(Locale.KOREAN, "%02d:%02d", alarm.hour, alarm.minute))
                putExtra("CUSTOM_TONE_URI", alarm.customToneUri)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }

    private var previewMediaPlayer: MediaPlayer? = null

    fun getSystemAlarms(): List<Pair<String, String>> {
        val list = mutableListOf<Pair<String, String>>()
        val context = getApplication<Application>()
        try {
            val defaultAlarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            if (defaultAlarmUri != null) {
                list.add("기본 알람 벨소리 (Default)" to defaultAlarmUri.toString())
            }

            val manager = RingtoneManager(context)
            manager.setType(RingtoneManager.TYPE_ALARM)
            val cursor = manager.cursor
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    val title = cursor.getString(RingtoneManager.TITLE_COLUMN_INDEX)
                    val uri = manager.getRingtoneUri(cursor.position)
                    if (uri != null) {
                        list.add(title to uri.toString())
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("AlarmViewModel", "Error loading alarm type tones", e)
        }

        try {
            val manager = RingtoneManager(context)
            manager.setType(RingtoneManager.TYPE_RINGTONE)
            val cursor = manager.cursor
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    val title = cursor.getString(RingtoneManager.TITLE_COLUMN_INDEX)
                    val uri = manager.getRingtoneUri(cursor.position)
                    if (uri != null) {
                        list.add(title to uri.toString())
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("AlarmViewModel", "Error loading ringtone type tones", e)
        }

        try {
            val manager = RingtoneManager(context)
            manager.setType(RingtoneManager.TYPE_NOTIFICATION)
            val cursor = manager.cursor
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    val title = cursor.getString(RingtoneManager.TITLE_COLUMN_INDEX)
                    val uri = manager.getRingtoneUri(cursor.position)
                    if (uri != null) {
                        list.add(title to uri.toString())
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("AlarmViewModel", "Error loading notification type tones", e)
        }

        return list.distinctBy { it.second }
    }

    fun playSoundPreview(uriStr: String) {
        stopSoundPreview()
        try {
            val context = getApplication<Application>()
            previewMediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setDataSource(context, Uri.parse(uriStr))
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.e("AlarmViewModel", "Failed to play preview: $uriStr", e)
        }
    }

    fun stopSoundPreview() {
        try {
            previewMediaPlayer?.let {
                if (it.isPlaying) {
                    it.stop()
                }
                it.release()
            }
        } catch (e: Exception) {
            Log.e("AlarmViewModel", "Failed to release preview player", e)
        } finally {
            previewMediaPlayer = null
        }
    }

    // --- Alarm Backup & Restore Support ---

    fun updateBackupFileList() {
        val context = getApplication<Application>()
        val backupDir = File(context.filesDir, "backups")
        if (!backupDir.exists()) {
            backupDir.mkdirs()
        }
        val files = backupDir.listFiles() ?: emptyArray()
        backupFileList.value = files
            .filter { it.name.endsWith(".json") }
            .map { it.name }
            .sortedDescending()
    }

    fun deleteBackupFile(filename: String) {
        val context = getApplication<Application>()
        val file = File(File(context.filesDir, "backups"), filename)
        if (file.exists()) {
            file.delete()
        }
        updateBackupFileList()
    }

    fun makeLocalBackup(): Boolean {
        return try {
            val alarmsList = alarms.value
            val array = JSONArray()
            for (alarm in alarmsList) {
                val obj = JSONObject().apply {
                    put("hour", alarm.hour)
                    put("minute", alarm.minute)
                    put("label", alarm.label)
                    put("isEnabled", alarm.isEnabled)
                    put("repeatDaysString", alarm.repeatDaysString)
                    put("skipOnHolidays", alarm.skipOnHolidays)
                    put("onlyOnHolidays", alarm.onlyOnHolidays)
                    put("customToneUri", alarm.customToneUri ?: JSONObject.NULL)
                    put("customToneName", alarm.customToneName ?: JSONObject.NULL)
                    put("snoozeEnabled", alarm.snoozeEnabled)
                    put("snoozeInterval", alarm.snoozeInterval)
                    put("snoozeRepeats", alarm.snoozeRepeats)
                    put("preReminderEnabled", alarm.preReminderEnabled)
                    put("preReminderMinutes", alarm.preReminderMinutes)
                    put("customOrder", alarm.customOrder)
                }
                array.put(obj)
            }

            val context = getApplication<Application>()
            val backupDir = File(context.filesDir, "backups")
            if (!backupDir.exists()) {
                backupDir.mkdirs()
            }
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.KOREAN).format(Date())
            val backupFile = File(backupDir, "alarm_backup_$timeStamp.json")
            backupFile.writeText(array.toString(4))
            
            Log.d("AlarmViewModel", "Alarms backed up to ${backupFile.absolutePath}")
            updateBackupFileList()
            true
        } catch (e: Exception) {
            Log.e("AlarmViewModel", "Failed to create local backup", e)
            false
        }
    }

    fun restoreAlarmsFromLocalFile(filename: String): Boolean {
        return try {
            val context = getApplication<Application>()
            val file = File(File(context.filesDir, "backups"), filename)
            if (!file.exists()) return false
            val jsonStr = file.readText()
            restoreFromJsonString(jsonStr)
        } catch (e: Exception) {
            Log.e("AlarmViewModel", "Failed to restore from file: $filename", e)
            false
        }
    }

    fun generateClipboardBackup(): String {
        return try {
            val alarmsList = alarms.value
            val array = JSONArray()
            for (alarm in alarmsList) {
                val obj = JSONObject().apply {
                    put("hour", alarm.hour)
                    put("minute", alarm.minute)
                    put("label", alarm.label)
                    put("isEnabled", alarm.isEnabled)
                    put("repeatDaysString", alarm.repeatDaysString)
                    put("skipOnHolidays", alarm.skipOnHolidays)
                    put("onlyOnHolidays", alarm.onlyOnHolidays)
                    put("customToneUri", alarm.customToneUri ?: JSONObject.NULL)
                    put("customToneName", alarm.customToneName ?: JSONObject.NULL)
                    put("snoozeEnabled", alarm.snoozeEnabled)
                    put("snoozeInterval", alarm.snoozeInterval)
                    put("snoozeRepeats", alarm.snoozeRepeats)
                    put("preReminderEnabled", alarm.preReminderEnabled)
                    put("preReminderMinutes", alarm.preReminderMinutes)
                    put("customOrder", alarm.customOrder)
                }
                array.put(obj)
            }
            array.toString()
        } catch (e: Exception) {
            Log.e("AlarmViewModel", "Failed to generate clipboard string", e)
            ""
        }
    }

    fun restoreFromJsonString(jsonStr: String): Boolean {
        return try {
            val array = JSONArray(jsonStr)
            viewModelScope.launch {
                val context = getApplication<Application>()
                val scheduler = AlarmScheduler(context)
                
                // Clear all existing scheduled instances
                val existingAlarms = alarms.value
                existingAlarms.forEach { scheduler.cancel(it) }
                
                // Read from parsed data and replace current table list or insert
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val alarmObj = Alarm(
                        id = 0, // insert as fresh auto-generated Primary Key ids
                        hour = obj.getInt("hour"),
                        minute = obj.getInt("minute"),
                        label = obj.optString("label", "알람"),
                        isEnabled = obj.optBoolean("isEnabled", true),
                        repeatDaysString = obj.optString("repeatDaysString", ""),
                        skipOnHolidays = obj.optBoolean("skipOnHolidays", false),
                        onlyOnHolidays = obj.optBoolean("onlyOnHolidays", false),
                        customToneUri = if (obj.isNull("customToneUri")) null else obj.optString("customToneUri"),
                        customToneName = if (obj.isNull("customToneName")) null else obj.optString("customToneName"),
                        snoozeEnabled = obj.optBoolean("snoozeEnabled", false),
                        snoozeInterval = obj.optInt("snoozeInterval", 5),
                        snoozeRepeats = obj.optInt("snoozeRepeats", 3),
                        remainingSnoozes = 0,
                        preReminderEnabled = obj.optBoolean("preReminderEnabled", false),
                        preReminderMinutes = obj.optInt("preReminderMinutes", 5),
                        customOrder = obj.optInt("customOrder", 0)
                    )
                    alarmRepository.insertAlarm(alarmObj)
                }
            }
            true
        } catch (e: Exception) {
            Log.e("AlarmViewModel", "Failed to restore json backup", e)
            false
        }
    }

    override fun onCleared() {
        try {
            stopSoundPreview()
        } catch (e: Exception) {}
        try {
            getApplication<Application>().unregisterReceiver(appReceiver)
        } catch (e: Exception) {
            // Safe unregister
        }
        super.onCleared()
    }

    @Suppress("UNCHECKED_CAST")
    class Factory(private val application: Application) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(AlarmViewModel::class.java)) {
                return AlarmViewModel(application) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
