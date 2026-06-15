package com.example

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.AlarmViewModel
import com.example.ui.viewmodel.ActiveAlarmState

class AlarmAlertActivity : ComponentActivity() {
    private val viewModel: AlarmViewModel by lazy {
        AlarmViewModel.Factory(applicationContext as android.app.Application).create(AlarmViewModel::class.java)
    }

    private val dismissReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == "com.example.ACTION_DISMISS_ALARM_RINGING") {
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Show on lockscreen, turn screen on, keep screen awake
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            try {
                val km = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
                km.requestDismissKeyguard(this, null)
            } catch (e: Exception) {
                // Ignore keyguard request errors if secure lock is on
            }
        }
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        // Extract Intent parameters
        val alarmId = intent.getIntExtra("ALARM_ID", -1)
        val label = intent.getStringExtra("ALARM_LABEL") ?: "알람"
        val time = intent.getStringExtra("ALARM_TIME") ?: "--:--"
        val toneUri = intent.getStringExtra("CUSTOM_TONE_URI")

        if (alarmId != -1) {
            viewModel.triggerRinging(alarmId, label, time, toneUri)
        }

        // Register receiver to close this screen when watch or notification handles snooze/dismiss
        val filter = IntentFilter("com.example.ACTION_DISMISS_ALARM_RINGING")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(dismissReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(dismissReceiver, filter)
        }

        setContent {
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            val isDark = when (themeMode) {
                "dark" -> true
                "light" -> false
                else -> androidx.compose.foundation.isSystemInDarkTheme()
            }
            MyApplicationTheme(darkTheme = isDark) {
                val activeAlarm by viewModel.activeRingingAlarm.collectAsStateWithLifecycle()
                
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    val ringingState = activeAlarm ?: ActiveAlarmState(
                        id = alarmId,
                        label = label,
                        time = time,
                        toneName = "알람음"
                    )
                    
                    ActiveAlarmOverlay(
                        activeAlarm = ringingState,
                        onDismiss = {
                            viewModel.dismissRinging()
                            // Send broadcast just in case
                            val dismissOverlayIntent = Intent("com.example.ACTION_DISMISS_ALARM_RINGING").apply {
                                putExtra("ALARM_ID", alarmId)
                                setPackage(packageName)
                            }
                            sendBroadcast(dismissOverlayIntent)
                            finish()
                        },
                        onSnooze = { customMinutes ->
                            viewModel.snoozeRinging(customMinutes)
                            // Send broadcast just in case
                            val dismissOverlayIntent = Intent("com.example.ACTION_DISMISS_ALARM_RINGING").apply {
                                putExtra("ALARM_ID", alarmId)
                                setPackage(packageName)
                            }
                            sendBroadcast(dismissOverlayIntent)
                            finish()
                        }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(dismissReceiver)
        } catch (e: Exception) {
            // ignore
        }
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (viewModel.activeRingingAlarm.value != null) {
            viewModel.snoozeRinging(5)
            val dismissOverlayIntent = Intent("com.example.ACTION_DISMISS_ALARM_RINGING").apply {
                setPackage(packageName)
            }
            sendBroadcast(dismissOverlayIntent)
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
