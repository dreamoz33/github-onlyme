package com.example

import android.content.Context
import android.content.ClipboardManager
import android.content.ClipData
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.media.RingtoneManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.activity.compose.BackHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.model.Alarm
import com.example.data.model.Holiday
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.AlarmViewModel
import com.example.ui.viewmodel.BypassedLog
import com.example.ui.viewmodel.SyncState
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    private val viewModel: AlarmViewModel by lazy {
        AlarmViewModel.Factory(applicationContext as android.app.Application).create(AlarmViewModel::class.java)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Turn screen on, show when locked, and keep screen on for Alarms
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Handle possible alarm trigger intent
        handleAlarmIntent(intent)

        enableEdgeToEdge()
        setContent {
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            val isDark = when (themeMode) {
                "dark" -> true
                "light" -> false
                else -> androidx.compose.foundation.isSystemInDarkTheme()
            }
            MyApplicationTheme(darkTheme = isDark) {
                MainAppScreen(viewModel = viewModel)
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAlarmIntent(intent)
    }

    private fun handleAlarmIntent(intent: android.content.Intent?) {
        if (intent != null && intent.hasExtra("ALARM_ID")) {
            val id = intent.getIntExtra("ALARM_ID", -1)
            val label = intent.getStringExtra("ALARM_LABEL") ?: "알람"
            val time = intent.getStringExtra("ALARM_TIME") ?: "--:--"
            val toneUri = intent.getStringExtra("CUSTOM_TONE_URI")
            if (id != -1) {
                viewModel.triggerRinging(id, label, time, toneUri)
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (viewModel.activeRingingAlarm.value != null) {
            // Volume keys, Back key, and other physical key downs during ringing trigger a 5-minute snooze
            viewModel.snoozeRinging(5)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}

@Composable
fun MainAppScreen(viewModel: AlarmViewModel) {
    val context = LocalContext.current

    val alarms by viewModel.alarms.collectAsStateWithLifecycle()
    val holidays by viewModel.holidays.collectAsStateWithLifecycle()
    val syncState by viewModel.syncState.collectAsStateWithLifecycle()
    val activeAlarm by viewModel.activeRingingAlarm.collectAsStateWithLifecycle()
    val bypassedLogs by viewModel.bypassedLogs.collectAsStateWithLifecycle()
    val govtApiKey by viewModel.govtApiKey.collectAsStateWithLifecycle()

    var showEditDialog by remember { mutableStateOf<Alarm?>(null) }
    var currentTab by remember { mutableIntStateOf(0) } // 0 = Alarms, 1 = Holiday Sync, 2 = Logs

    // Request poster permissions on Android 13+
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // Bedside Cosmic Header & Clock
            val currentThemeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            BedsideHeader(
                themeMode = currentThemeMode,
                onThemeChange = { viewModel.setThemeMode(it) }
            )

            // Tabs Selector
            TabRowSection(currentTab = currentTab, onTabSelected = { currentTab = it })

            // Content Area based on current tab with swipe gestures and polished horizontal navigation transitions
            var dragAmountCumulative by remember { mutableStateOf(0f) }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .pointerInput(currentTab) {
                        detectHorizontalDragGestures(
                            onDragStart = { dragAmountCumulative = 0f },
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                dragAmountCumulative += dragAmount
                            },
                            onDragEnd = {
                                if (dragAmountCumulative > 120f) {
                                    // Swipe Right (Move to left tab)
                                    if (currentTab > 0) {
                                        currentTab--
                                    }
                                } else if (dragAmountCumulative < -120f) {
                                    // Swipe Left (Move to right tab)
                                    if (currentTab < 2) {
                                        currentTab++
                                    }
                                }
                            }
                        )
                    }
            ) {
                AnimatedContent(
                    targetState = currentTab,
                    transitionSpec = {
                        if (targetState > initialState) {
                            // Slide from right to left
                            (slideInHorizontally { width -> width } + fadeIn()).togetherWith(
                                slideOutHorizontally { width -> -width } + fadeOut()
                            )
                        } else {
                            // Slide from left to right
                            (slideInHorizontally { width -> -width } + fadeIn()).togetherWith(
                                slideOutHorizontally { width -> width } + fadeOut()
                            )
                        }
                    },
                    label = "TabTransition"
                ) { targetPage ->
                    when (targetPage) {
                        0 -> AlarmsListScreen(
                            alarms = alarms,
                            onToggleEnabled = { viewModel.toggleAlarm(it) },
                            onEditAlarm = { showEditDialog = it },
                            onDeleteAlarm = { viewModel.deleteAlarm(it) },
                            onTestAlarm = { viewModel.testTriggerAlarmNow(it) },
                            onAddAlarm = { 
                                val defaultUri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)?.toString()
                                showEditDialog = Alarm(
                                    hour = 7, 
                                    minute = 0, 
                                    label = "새 알람", 
                                    customToneUri = defaultUri, 
                                    customToneName = "기본 알람 벨소리"
                                ) 
                            },
                            onReorderAlarms = { viewModel.updateAlarmsOrder(it) },
                            viewModel = viewModel
                        )
                        1 -> HolidaySyncScreen(
                            holidays = holidays,
                            syncState = syncState,
                            govtApiKey = govtApiKey,
                            onSaveGovtApiKey = { viewModel.saveGovtApiKey(it) },
                            onSyncHolidays = { year, govtKey -> viewModel.syncHolidays(year, govtKey) },
                            onAddHoliday = { viewModel.addHoliday(it) },
                            onDeleteHoliday = { viewModel.deleteHoliday(it) },
                            onDeleteYearAll = { year -> viewModel.deleteHolidaysBySelectedYear(year) }
                        )
                        2 -> ExecutionLogsScreen(bypassedLogs = bypassedLogs)
                    }
                }
            }
            
            // Bottom Safe padding for navigation gesture bars
            Spacer(modifier = Modifier.navigationBarsPadding())
        }

        // Floating Action Button anchored to the viewport bottom-end, active only on Tab 0 (Alarms)
        if (currentTab == 0) {
            FloatingActionButton(
                onClick = { 
                    val defaultUri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)?.toString()
                    showEditDialog = Alarm(
                        hour = 7, 
                        minute = 0, 
                        label = "새 알람", 
                        customToneUri = defaultUri, 
                        customToneName = "기본 알람 벨소리"
                    ) 
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(24.dp)
                    .navigationBarsPadding()
                    .testTag("add_alarm_button")
            ) {
                Icon(Icons.Default.Add, contentDescription = "알람 추가")
            }
        }

        // Add / Edit Alarm Dialog overlay
        showEditDialog?.let { alarm ->
            AlarmEditDialog(
                alarm = alarm,
                viewModel = viewModel,
                onDismiss = { showEditDialog = null },
                onSave = { h, m, label, isEnabled, days, skip, only, toneUri, toneName, snoozeE, snoozeI, snoozeR, preE, preM ->
                    viewModel.saveAlarm(
                        id = alarm.id,
                        hour = h,
                        minute = m,
                        label = label,
                        isEnabled = isEnabled,
                        repeatDays = days,
                        skipOnHolidays = skip,
                        onlyOnHolidays = only,
                        customToneUri = toneUri,
                        customToneName = toneName,
                        snoozeEnabled = snoozeE,
                        snoozeInterval = snoozeI,
                        snoozeRepeats = snoozeR,
                        preReminderEnabled = preE,
                        preReminderMinutes = preM
                    )
                    showEditDialog = null
                }
            )
        }

        // Active Ringing Full-screen Screen overlay
        activeAlarm?.let { ringingState ->
            ActiveAlarmOverlay(
                activeAlarm = ringingState,
                onDismiss = { viewModel.dismissRinging() },
                onSnooze = { customMinutes -> viewModel.snoozeRinging(customMinutes) }
            )
        }
    }
}

@Composable
fun BedsideHeader(
    themeMode: String,
    onThemeChange: (String) -> Unit
) {
    var currentTimeStr by remember { mutableStateOf("") }
    var currentDateStr by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val sdfTime = SimpleDateFormat("HH:mm:ss", Locale.KOREAN)
        val sdfDate = SimpleDateFormat("YYYY년 M월 d일 (E)", Locale.KOREAN)
        while (true) {
            val now = Date()
            currentTimeStr = sdfTime.format(now)
            currentDateStr = sdfDate.format(now)
            kotlinx.coroutines.delay(1000)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                        MaterialTheme.colorScheme.background.copy(alpha = 0.2f)
                    )
                ),
                shape = RoundedCornerShape(24.dp)
            )
            .border(
                width = 1.dp,
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f),
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                    )
                ),
                shape = RoundedCornerShape(24.dp)
            )
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = currentDateStr,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.secondary,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = currentTimeStr,
                style = MaterialTheme.typography.headlineLarge.copy(fontSize = 38.sp),
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.padding(top = 4.dp)
            ) {
                Icon(
                    imageOf = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "스마트 공휴일 자동 동기화 켜짐",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
        }

        // Theme Selector Capsule at Top-End
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .background(
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(12.dp)
                )
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(12.dp)
                )
                .padding(2.dp),
            horizontalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            listOf("light" to "☀️", "dark" to "🌙", "system" to "⚙️").forEach { (mode, emoji) ->
                val isSelected = themeMode == mode
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                            else Color.Transparent
                        )
                        .clickable { onThemeChange(mode) }
                        .testTag("theme_mode_$mode"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = emoji,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp)
                    )
                }
            }
        }
    }
}

@Composable
fun TabRowSection(currentTab: Int, onTabSelected: (Int) -> Unit) {
    TabRow(
        selectedTabIndex = currentTab,
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.primary,
        indicator = { tabPositions ->
            TabRowDefaults.SecondaryIndicator(
                modifier = Modifier.tabIndicatorOffset(tabPositions[currentTab]),
                color = MaterialTheme.colorScheme.primary
            )
        },
        divider = {}
    ) {
        Tab(
            selected = currentTab == 0,
            onClick = { onTabSelected(0) },
            text = { Text("알람", fontWeight = if (currentTab == 0) FontWeight.Bold else FontWeight.Medium) },
            icon = { Icon(Icons.Default.Notifications, contentDescription = null) },
            selectedContentColor = MaterialTheme.colorScheme.primary,
            unselectedContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
        )
        Tab(
            selected = currentTab == 1,
            onClick = { onTabSelected(1) },
            text = { Text("공휴일 수집", fontWeight = if (currentTab == 1) FontWeight.Bold else FontWeight.Medium) },
            icon = { Icon(Icons.Default.List, contentDescription = null) },
            selectedContentColor = MaterialTheme.colorScheme.primary,
            unselectedContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
        )
        Tab(
            selected = currentTab == 2,
            onClick = { onTabSelected(2) },
            text = { Text("실행 로그", fontWeight = if (currentTab == 2) FontWeight.Bold else FontWeight.Medium) },
            icon = { Icon(Icons.Default.CheckCircle, contentDescription = null) },
            selectedContentColor = MaterialTheme.colorScheme.primary,
            unselectedContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
        )
    }
}

// Icon adapter safely mapping icons to standard vector layouts
@Composable
fun Icon(imageOf: androidx.compose.ui.graphics.vector.ImageVector, contentDescription: String?, tint: Color = LocalContentColor.current, modifier: Modifier = Modifier) {
    androidx.compose.material3.Icon(
        imageVector = imageOf,
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier
    )
}

// Tab 1: Alarms List
@Composable
fun AlarmsListScreen(
    alarms: List<Alarm>,
    onToggleEnabled: (Alarm) -> Unit,
    onEditAlarm: (Alarm) -> Unit,
    onDeleteAlarm: (Alarm) -> Unit,
    onTestAlarm: (Alarm) -> Unit,
    onAddAlarm: () -> Unit,
    onReorderAlarms: (List<Alarm>) -> Unit,
    viewModel: AlarmViewModel
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        if (alarms.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "😴",
                    style = MaterialTheme.typography.displayLarge.copy(fontSize = 54.sp),
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "등록된 알람이 없습니다",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "우측 하단의 '+' 버튼을 눌러 공휴일에 맞춰 스마트하게 작동하는 알람을 등록해 조율해 보세요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(24.dp))
                AlarmBackupRestoreCard(viewModel = viewModel)
            }
        } else {
            val listState = androidx.compose.foundation.lazy.rememberLazyListState()
            var draggedItemIndex by remember { mutableStateOf<Int?>(null) }
            var dragOffset by remember { mutableStateOf(0f) }
            val alarmsListState = remember(alarms) { mutableStateListOf<Alarm>().apply { clear(); addAll(alarms) } }
            val density = LocalDensity.current

            androidx.compose.foundation.lazy.LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 100.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(alarmsListState.size, key = { alarmsListState[it].id }) { idx ->
                    val alarm = alarmsListState[idx]
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer {
                                translationY = if (draggedItemIndex == idx) dragOffset else 0f
                                scaleX = if (draggedItemIndex == idx) 1.04f else 1.0f
                                scaleY = if (draggedItemIndex == idx) 1.04f else 1.0f
                                shadowElevation = if (draggedItemIndex == idx) 12f else 0f
                            }
                            .pointerInput(idx) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        draggedItemIndex = idx
                                        dragOffset = 0f
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        dragOffset += dragAmount.y

                                        val fromIndex = draggedItemIndex
                                        if (fromIndex != null) {
                                            val itemHeightPx = with(density) { 140.dp.toPx() } // dynamic height approximation with padding
                                            val targetIndex = if (dragOffset > itemHeightPx / 2) {
                                                fromIndex + 1
                                            } else if (dragOffset < -itemHeightPx / 2) {
                                                fromIndex - 1
                                            } else {
                                                fromIndex
                                            }

                                            if (targetIndex in alarmsListState.indices && targetIndex != fromIndex) {
                                                val movingItem = alarmsListState.removeAt(fromIndex)
                                                alarmsListState.add(targetIndex, movingItem)
                                                draggedItemIndex = targetIndex
                                                dragOffset = if (targetIndex > fromIndex) {
                                                    dragOffset - itemHeightPx
                                                } else {
                                                    dragOffset + itemHeightPx
                                                }
                                            }
                                        }
                                    },
                                    onDragEnd = {
                                        draggedItemIndex = null
                                        dragOffset = 0f
                                        onReorderAlarms(alarmsListState.toList())
                                    },
                                    onDragCancel = {
                                        draggedItemIndex = null
                                        dragOffset = 0f
                                    }
                                )
                            }
                    ) {
                        AlarmListItem(
                            alarm = alarm,
                            onToggleEnabled = { onToggleEnabled(alarm) },
                            onEdit = { onEditAlarm(alarm) },
                            onDelete = { onDeleteAlarm(alarm) },
                            onTest = { onTestAlarm(alarm) }
                        )
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(12.dp))
                    AlarmBackupRestoreCard(viewModel = viewModel)
                }
            }
        }
    }
}

@Composable
fun AlarmBackupRestoreCard(
    viewModel: AlarmViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val backupFiles by viewModel.backupFileList.collectAsStateWithLifecycle()
    var showManualTextRestoreDialog by remember { mutableStateOf(false) }
    var pastedJsonText by remember { mutableStateOf("") }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val jsonText = viewModel.generateClipboardBackup()
                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(jsonText.toByteArray())
                }
                android.widget.Toast.makeText(context, "백업 파일이 기기에 성공적으로 저장되었습니다.", android.widget.Toast.LENGTH_SHORT).show()
                viewModel.updateBackupFileList()
            } catch (e: Exception) {
                android.widget.Toast.makeText(context, "파일 저장 실패: ${e.localizedMessage}", android.widget.Toast.LENGTH_SHORT).show()
                android.util.Log.e("AlarmBackup", "Export failed", e)
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    val jsonText = inputStream.bufferedReader().use { it.readText() }
                    val success = viewModel.restoreFromJsonString(jsonText)
                    if (success) {
                        android.widget.Toast.makeText(context, "알람 불러오기 및 복구가 완료되었습니다.", android.widget.Toast.LENGTH_SHORT).show()
                        viewModel.updateBackupFileList()
                    } else {
                        android.widget.Toast.makeText(context, "잘못된 백업 데이터 파일 포맷입니다.", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                android.widget.Toast.makeText(context, "파일 열기 실패: ${e.localizedMessage}", android.widget.Toast.LENGTH_SHORT).show()
                android.util.Log.e("AlarmBackup", "Import failed", e)
            }
        }
    }
    
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(20.dp),
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                shape = RoundedCornerShape(20.dp)
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageOf = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "알람 데이터 백업 및 복구",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "알람 설정을 로컬 백업 파일이나 텍스트로 보관하세요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Primary Backup Buttons Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        val success = viewModel.makeLocalBackup()
                        if (success) {
                            android.widget.Toast.makeText(context, "새로운 로컬 백업 파일이 생성되었습니다.", android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            android.widget.Toast.makeText(context, "로컬 백업 생성 실패", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(imageOf = Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("로컬 백업", fontWeight = FontWeight.Bold)
                }
                
                Button(
                    onClick = {
                        val jsonText = viewModel.generateClipboardBackup()
                        if (jsonText.isNotEmpty()) {
                            val clipBoard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("AlarmBackup", jsonText)
                            clipBoard.setPrimaryClip(clip)
                            android.widget.Toast.makeText(context, "백업 데이터가 클립보드에 복사되었습니다.", android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            android.widget.Toast.makeText(context, "백업 데이터 생성 실패", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(imageOf = Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("텍스트 복사", fontWeight = FontWeight.Bold)
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Manual Paste Button
            OutlinedButton(
                onClick = {
                    pastedJsonText = ""
                    showManualTextRestoreDialog = true
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(12.dp)
                    ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(imageOf = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("복사한 텍스트로 직접 복구", fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(14.dp))
            Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "기기 파일로 직접 백업 및 복구",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.secondary
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        try {
                            exportLauncher.launch("alarm_backup.json")
                        } catch (e: Exception) {
                            android.widget.Toast.makeText(context, "작동 실패: ${e.localizedMessage}", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(imageOf = Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("파일로 저장 (내보내기)", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }

                Button(
                    onClick = {
                        try {
                            importLauncher.launch(arrayOf("application/json", "application/octet-stream", "*/*"))
                        } catch (e: Exception) {
                            android.widget.Toast.makeText(context, "작동 실패: ${e.localizedMessage}", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(imageOf = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("파일 불러오기 (가져오기)", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
            
            if (backupFiles.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                Spacer(modifier = Modifier.height(12.dp))
                
                Text(
                    text = "저장된 백업 파일 목록",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.secondary
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Display files in backups directory
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    backupFiles.take(5).forEach { filename ->
                        // Format the timestamp-based filename for readable display: alarm_backup_20260613_143015.json
                        val displayName = try {
                            val rawParts = filename.removePrefix("alarm_backup_").removeSuffix(".json").split("_")
                            if (rawParts.size >= 2) {
                                val d = rawParts[0] // yyyyMMdd
                                val t = rawParts[1] // HHmmss
                                "${d.substring(0, 4)}년 ${d.substring(4, 6)}월 ${d.substring(6, 8)}일 ${t.substring(0, 2)}:${t.substring(2, 4)}:${t.substring(4,6)} 백업"
                            } else {
                                filename
                            }
                        } catch (e: Exception) {
                            filename
                        }
                        
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = displayName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                IconButton(
                                    onClick = {
                                        val success = viewModel.restoreAlarmsFromLocalFile(filename)
                                        if (success) {
                                            android.widget.Toast.makeText(context, "알람 복구가 완료되었습니다.", android.widget.Toast.LENGTH_SHORT).show()
                                        } else {
                                            android.widget.Toast.makeText(context, "복구 실패", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageOf = Icons.Default.Refresh,
                                        contentDescription = "복구",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                
                                IconButton(
                                    onClick = {
                                        viewModel.deleteBackupFile(filename)
                                        android.widget.Toast.makeText(context, "백업 파일이 삭제되었습니다.", android.widget.Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageOf = Icons.Default.Delete,
                                        contentDescription = "삭제",
                                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    
    // Paste Text Restore Dialog
    if (showManualTextRestoreDialog) {
        Dialog(onDismissRequest = { showManualTextRestoreDialog = false }) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "백업 텍스트 직접 복구",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "이전에 복사했던 백업 JSON 텍스트 데이터를 아래에 붙여넣으세요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    OutlinedTextField(
                        value = pastedJsonText,
                        onValueChange = { pastedJsonText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp),
                        placeholder = { Text(" 여기에 백업 데이터 텍스트를 붙여넣기하세요...", fontSize = 12.sp) },
                        maxLines = 10,
                        textStyle = MaterialTheme.typography.bodySmall,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
                        )
                    )
                    
                    Spacer(modifier = Modifier.height(20.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showManualTextRestoreDialog = false },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("취소")
                        }
                        
                        Button(
                            onClick = {
                                if (pastedJsonText.isBlank()) {
                                    android.widget.Toast.makeText(context, "복구 데이터가 비어 있습니다.", android.widget.Toast.LENGTH_SHORT).show()
                                } else {
                                    val success = viewModel.restoreFromJsonString(pastedJsonText)
                                    if (success) {
                                        android.widget.Toast.makeText(context, "알람 복구가 완료되었습니다.", android.widget.Toast.LENGTH_SHORT).show()
                                        showManualTextRestoreDialog = false
                                    } else {
                                        android.widget.Toast.makeText(context, "잘못된 백업 데이터 포맷입니다.", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            modifier = Modifier.weight(1.5f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Text("알람 목록 복구", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AlarmListItem(
    alarm: Alarm,
    onToggleEnabled: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTest: () -> Unit
) {
    val repeatDays = alarm.repeatDays

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
            .testTag("alarm_item_card_${alarm.id}"),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Label Title at the top
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left side: Alarm Label/Name
                Text(
                    text = alarm.label.ifBlank { "일반 알람" },
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = if (alarm.isEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(end = 12.dp)
                )

                // Right side: Compact, highly visible, switch-like status capsule
                val isDarkTheme = MaterialTheme.colorScheme.background == Color(0xFF000000)

                val statusText = when {
                    alarm.onlyOnHolidays -> "공휴일 전용"
                    alarm.skipOnHolidays -> "공휴일 제외"
                    else -> "상시 작동"
                }

                val statusContainerColor = if (isDarkTheme) {
                    if (alarm.isEnabled) Color.White else Color.White.copy(alpha = 0.3f)
                } else {
                    if (alarm.isEnabled) Color(0xFF4D49FF) else Color(0xFFE5E7EB)
                }

                val statusContentColor = if (isDarkTheme) {
                    if (alarm.isEnabled) Color.Black else Color.Black.copy(alpha = 0.6f)
                } else {
                    if (alarm.isEnabled) Color.White else Color(0xFF9CA3AF)
                }

                val indicatorColor = if (isDarkTheme) {
                    if (!alarm.isEnabled) {
                        Color.Black.copy(alpha = 0.3f)
                    } else {
                        when {
                            alarm.onlyOnHolidays -> Color(0xFF4D49FF)
                            alarm.skipOnHolidays -> Color(0xFFFF5252)
                            else -> Color(0xFF10B981)
                        }
                    }
                } else {
                    if (!alarm.isEnabled) {
                        Color(0xFFD1D5DB)
                    } else {
                        when {
                            alarm.onlyOnHolidays -> Color(0xFF34D399)
                            alarm.skipOnHolidays -> Color(0xFFFCA5A5)
                            else -> Color.White
                        }
                    }
                }

                Surface(
                    color = statusContainerColor,
                    contentColor = statusContentColor,
                    shape = RoundedCornerShape(100.dp),
                    border = if (!alarm.isEnabled) null else when {
                        isDarkTheme -> null
                        alarm.onlyOnHolidays -> null
                        alarm.skipOnHolidays -> null
                        else -> androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                    }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(indicatorColor, CircleShape)
                        )
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.ExtraBold),
                            letterSpacing = 0.2.sp
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))

            // Main Row: Time & Days & Switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Time on Left (오전/오후 + HH:MM)
                val amPmStr = if (alarm.hour < 12) "오전" else "오후"
                val displayHour = when {
                    alarm.hour == 0 -> 12
                    alarm.hour > 12 -> alarm.hour - 12
                    else -> alarm.hour
                }
                val formattedTime = String.format(Locale.KOREAN, "%d:%02d", displayHour, alarm.minute)

                Row(
                    verticalAlignment = Alignment.Bottom,
                    modifier = Modifier.weight(1.3f)
                ) {
                    Text(
                        text = amPmStr,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
                        color = if (alarm.isEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.padding(bottom = 3.dp, end = 4.dp)
                    )
                    Text(
                        text = formattedTime,
                        style = MaterialTheme.typography.headlineLarge.copy(fontSize = 32.sp, fontWeight = FontWeight.Bold),
                        color = if (alarm.isEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }

                // Days selection dots in the middle
                Box(
                    modifier = Modifier.weight(1.4f),
                    contentAlignment = Alignment.Center
                ) {
                    val dayList = listOf(
                        7 to "일",
                        1 to "월",
                        2 to "화",
                        3 to "수",
                        4 to "목",
                        5 to "금",
                        6 to "토"
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        dayList.forEach { (dayNum, dayChar) ->
                            val isActive = repeatDays.contains(dayNum)
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(4.dp)
                                        .background(
                                            color = if (isActive && alarm.isEnabled) MaterialTheme.colorScheme.primary else Color.Transparent,
                                            shape = CircleShape
                                        )
                                )
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(
                                    text = dayChar,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isActive && alarm.isEnabled) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                                    }
                                )
                            }
                        }
                    }
                }

                // Toggle Switch on Right
                Switch(
                    checked = alarm.isEnabled,
                    onCheckedChange = { onToggleEnabled() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        uncheckedThumbColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                        uncheckedTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                    ),
                    modifier = Modifier.testTag("alarm_toggle_${alarm.id}")
                )
            }

            // Options description (snooze & pre-reminders)
            if (alarm.preReminderEnabled || alarm.snoozeEnabled) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (alarm.preReminderEnabled) {
                            Surface(
                                color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = "미리알림 ${alarm.preReminderMinutes}분 전",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        if (alarm.snoozeEnabled) {
                            Surface(
                                color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = "다시 울림 ${alarm.snoozeInterval}분 (${alarm.snoozeRepeats}회)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            Spacer(modifier = Modifier.height(8.dp))

            // Unified Sound Name / Icon and action buttons on a single row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f).padding(end = 8.dp)
                ) {
                    Icon(
                        imageOf = Icons.Default.Notifications,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (!alarm.customToneName.isNullOrBlank()) alarm.customToneName else "기본 알람 벨소리",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Test Trigger button
                    IconButton(onClick = onTest, modifier = Modifier.size(36.dp)) {
                        Icon(
                            imageOf = Icons.Default.PlayArrow,
                            contentDescription = "테스트 실행",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    // Delete button
                    IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                        Icon(
                            imageOf = Icons.Default.Delete,
                            contentDescription = "삭제하기",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun getRepeatDaysLabel(days: List<Int>): String {
    if (days.isEmpty()) return "한 번만 재생"
    if (days.size == 7) return "매일 재생"
    val dayNames = listOf("월", "화", "수", "목", "금", "토", "일")
    val stringBuilder = StringBuilder()
    stringBuilder.append("반복: ")
    val sortedDays = days.sortedWith(compareBy { if (it == 7) 0 else it })
    for (i in sortedDays) {
        stringBuilder.append(dayNames[i - 1]).append(" ")
    }
    return stringBuilder.toString()
}

// Tab 2: Holiday Sync Management
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HolidaySyncScreen(
    holidays: List<Holiday>,
    syncState: SyncState,
    govtApiKey: String,
    onSaveGovtApiKey: (String) -> Unit,
    onSyncHolidays: (Int, String?) -> Unit,
    onAddHoliday: (Holiday) -> Unit,
    onDeleteHoliday: (Holiday) -> Unit,
    onDeleteYearAll: (Int) -> Unit
) {
    var serviceKeyInput by remember(govtApiKey) { mutableStateOf(govtApiKey) }
    var isEditingKey by remember(govtApiKey) { mutableStateOf(govtApiKey.isBlank()) }
    var selectedYear by remember { mutableIntStateOf(Calendar.getInstance().get(Calendar.YEAR)) }
    val currentYear = Calendar.getInstance().get(Calendar.YEAR)
    var isEditingYear by remember { mutableStateOf(false) }
    var yearInputText by remember { mutableStateOf(selectedYear.toString()) }
    var showAddHolidayDialog by remember { mutableStateOf(false) }
    val filteredHolidays = holidays.filter { it.year == selectedYear }

    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    text = "스마트 공휴일 연동 엔진",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "대한민국 공휴일 및 대체공휴일은 매년 정부령에 맞춰 변경될 수 있습니다. 아래를 설정하여 자동으로 최신 정보를 동기화하세요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                
                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "동기화 연도 설정 (1900년 - 3000년)",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.secondary
                )
                Spacer(modifier = Modifier.height(8.dp))
                
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isEditingYear) {
                        Box(
                            modifier = Modifier
                                .width(200.dp)
                                .height(140.dp)
                                .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            androidx.compose.foundation.text.BasicTextField(
                                value = yearInputText,
                                onValueChange = { newVal ->
                                    val filtered = newVal.filter { it.isDigit() }.take(4)
                                    yearInputText = filtered
                                    val parsed = filtered.toIntOrNull()
                                    if (parsed != null && parsed in 1900..3000) {
                                        selectedYear = parsed
                                    }
                                },
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Number,
                                    imeAction = ImeAction.Done
                                ),
                                keyboardActions = KeyboardActions(
                                    onDone = {
                                        if (yearInputText.isEmpty()) {
                                            yearInputText = selectedYear.toString()
                                        } else {
                                            val parsed = yearInputText.toIntOrNull()
                                            if (parsed != null && parsed in 1900..3000) {
                                                selectedYear = parsed
                                            } else {
                                                selectedYear = selectedYear.coerceIn(1900, 3000)
                                            }
                                            yearInputText = selectedYear.toString()
                                        }
                                        isEditingYear = false
                                    }
                                ),
                                textStyle = MaterialTheme.typography.headlineMedium.copy(
                                    fontSize = 28.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    textAlign = TextAlign.Center
                                ),
                                modifier = Modifier.fillMaxWidth().testTag("holiday_year_text_input")
                            )
                        }
                    } else {
                        WheelPicker(
                            selectedValue = selectedYear,
                            range = (1900..3000).toList(),
                            onValueChange = {
                                selectedYear = it
                                yearInputText = it.toString()
                            },
                            itemWidth = 120.dp,
                            formatter = { "${it}년" },
                            modifier = Modifier.testTag("holiday_year_wheel_picker"),
                            buttonPosition = "LEFT_RIGHT"
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 올해로 이동 (Move to This Year)
                    TextButton(
                        onClick = {
                            selectedYear = currentYear
                            yearInputText = currentYear.toString()
                        },
                        modifier = Modifier.testTag("holiday_year_current_button")
                    ) {
                        Icon(
                            imageOf = Icons.Default.DateRange,
                            contentDescription = "올해로 이동",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            "올해로 설정 (${currentYear}년)",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    TextButton(
                        onClick = {
                            if (!isEditingYear) {
                                yearInputText = selectedYear.toString()
                                isEditingYear = true
                            } else {
                                if (yearInputText.isEmpty()) {
                                    yearInputText = selectedYear.toString()
                                } else {
                                    val parsed = yearInputText.toIntOrNull()
                                    if (parsed != null && parsed in 1900..3000) {
                                        selectedYear = parsed
                                    } else {
                                        selectedYear = selectedYear.coerceIn(1900, 3000)
                                    }
                                    yearInputText = selectedYear.toString()
                                }
                                isEditingYear = false
                            }
                        },
                        modifier = Modifier.testTag("holiday_year_toggle_button")
                    ) {
                        Icon(
                            imageOf = if (isEditingYear) Icons.Default.Check else Icons.Default.Edit,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            if (isEditingYear) "스크롤 선택" else "직접 입력",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "정부 공공데이터 인증키 (선택)",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = serviceKeyInput,
                        onValueChange = { serviceKeyInput = it },
                        placeholder = { Text("인증키 불필요 (기본 동기화 및 AI 연동 우선)", fontSize = 13.sp) },
                        singleLine = true,
                        enabled = isEditingKey,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.secondary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f),
                            disabledBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                            disabledTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            if (isEditingKey) {
                                onSaveGovtApiKey(serviceKeyInput)
                                isEditingKey = false
                                onSyncHolidays(selectedYear, serviceKeyInput)
                            } else {
                                isEditingKey = true
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isEditingKey) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(if (isEditingKey) "적용" else "수정", fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "* 적용 버튼 클릭 시 키가 저장되고 해당 연도의 공휴일을 즉시 수집합니다. 공백 제출 시: 로컬 공식 오프라인 계산식 및 Gemini AI 스마트 연계로 수집됩니다.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Sync action button
                Button(
                    onClick = { onSyncHolidays(selectedYear, serviceKeyInput) },
                    enabled = syncState !is SyncState.Loading,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (syncState is SyncState.Loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("온라인 동기화 중...")
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("자동 공휴일 동기화 시작")
                    }
                }

                // Sync Outcome status bar
                when (syncState) {
                    is SyncState.Success -> {
                        Spacer(modifier = Modifier.height(12.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "동기화 성공! 수집원: ${syncState.source} (${syncState.count}개 공휴일 등록완료)",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.padding(12.dp),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                    is SyncState.Error -> {
                        Spacer(modifier = Modifier.height(12.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "오류 발생: ${syncState.message}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(12.dp),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                    else -> {}
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${selectedYear}년도 등록된 전체 공휴일 목록 (${filteredHolidays.size}개)",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.weight(1f)
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (filteredHolidays.isNotEmpty()) {
                    TextButton(
                        onClick = { onDeleteYearAll(selectedYear) },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.height(36.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "전체 삭제", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("전체 삭제", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                    }
                }
                Button(
                    onClick = { showAddHolidayDialog = true },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer),
                    modifier = Modifier.height(36.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("직접 추가", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                }
            }
        }

        if (filteredHolidays.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "해당 연도의 공휴일 정보가 아직 로드되지 않았습니다. 위의 동기화 버튼을 누르시면 자동 기입됩니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    textAlign = TextAlign.Center
                )
            }
        } else {
            filteredHolidays.forEach { holiday ->
                HolidayItemRow(
                    holiday = holiday,
                    onDelete = { onDeleteHoliday(holiday) }
                )
            }
        }
    }

    if (showAddHolidayDialog) {
        var customName by remember { mutableStateOf("") }
        var customMonth by remember { mutableIntStateOf(Calendar.getInstance().get(Calendar.MONTH) + 1) }
        var customDay by remember { mutableIntStateOf(Calendar.getInstance().get(Calendar.DAY_OF_MONTH)) }
        var selectedType by remember { mutableStateOf("법정공휴일") }

        AlertDialog(
            onDismissRequest = { showAddHolidayDialog = false },
            title = { Text("공휴일 직접 추가", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "직접 지정한 날짜가 공휴일로 등록되어 해당 날에 알람이 자동으로 제외(Bypass)됩니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )

                    OutlinedTextField(
                        value = customName,
                        onValueChange = { customName = it },
                        label = { Text("공휴일 이름") },
                        placeholder = { Text("예: 창립기념일, 개인 휴가") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text(
                        text = "공휴일 종류 설정",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.secondary
                    )

                    androidx.compose.foundation.layout.FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val holidayTypes = listOf("법정공휴일", "대체공휴일", "임시공휴일", "개인공휴일", "기타")
                        holidayTypes.forEach { type ->
                            val isSelected = selectedType == type
                            val isAlternative = type == "대체공휴일"
                            
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (isSelected) MaterialTheme.colorScheme.primary 
                                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                                    )
                                    .clickable { selectedType = type }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = type,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary 
                                            else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }

                    Text(
                        text = "날짜 설정 (${selectedYear}년)",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.secondary
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Month Selector
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("월", style = MaterialTheme.typography.labelSmall)
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                IconButton(
                                    onClick = { customMonth = if (customMonth > 1) customMonth - 1 else 12 },
                                    modifier = Modifier.size(32.dp).background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                                ) {
                                    Box(modifier = Modifier.size(10.dp, 2.dp).background(MaterialTheme.colorScheme.primary))
                                }
                                Text(
                                    text = "${customMonth}월",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp),
                                    textAlign = TextAlign.Center
                                )
                                IconButton(
                                    onClick = { customMonth = if (customMonth < 12) customMonth + 1 else 1 },
                                    modifier = Modifier.size(32.dp).background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }

                        // Day Selector
                        val maxDays = when (customMonth) {
                            2 -> if ((selectedYear % 4 == 0 && selectedYear % 100 != 0) || (selectedYear % 400 == 0)) 29 else 28
                            4, 6, 9, 11 -> 30
                            else -> 31
                        }

                        // Coerce day if exceeding maxDays
                        LaunchedEffect(customMonth) {
                            if (customDay > maxDays) {
                                customDay = maxDays
                            }
                        }

                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("일", style = MaterialTheme.typography.labelSmall)
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                IconButton(
                                    onClick = { customDay = if (customDay > 1) customDay - 1 else maxDays },
                                    modifier = Modifier.size(32.dp).background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                                ) {
                                    Box(modifier = Modifier.size(10.dp, 2.dp).background(MaterialTheme.colorScheme.primary))
                                }
                                Text(
                                    text = "${customDay}일",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp),
                                    textAlign = TextAlign.Center
                                )
                                IconButton(
                                    onClick = { customDay = if (customDay < maxDays) customDay + 1 else 1 },
                                    modifier = Modifier.size(32.dp).background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = customName.isNotBlank(),
                    onClick = {
                        val formattedDate = String.format(Locale.KOREAN, "%d-%02d-%02d", selectedYear, customMonth, customDay)
                        onAddHoliday(Holiday(
                            date = formattedDate,
                            name = customName,
                            isAlternative = selectedType == "대체공휴일",
                            year = selectedYear,
                            type = selectedType
                        ))
                        showAddHolidayDialog = false
                    }
                ) {
                    Text("추가", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddHolidayDialog = false }) {
                    Text("취소")
                }
            }
        )
    }
}

@Composable
fun HolidayItemRow(
    holiday: Holiday,
    onDelete: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = holiday.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(2.dp))
                val dayOfWeekLabel = remember(holiday.date) {
                    try {
                        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.KOREAN)
                        val date = sdf.parse(holiday.date)
                        if (date != null) {
                            val cal = java.util.Calendar.getInstance().apply { time = date }
                            when (cal.get(java.util.Calendar.DAY_OF_WEEK)) {
                                java.util.Calendar.SUNDAY -> " (일)"
                                java.util.Calendar.MONDAY -> " (월)"
                                java.util.Calendar.TUESDAY -> " (화)"
                                java.util.Calendar.WEDNESDAY -> " (수)"
                                java.util.Calendar.THURSDAY -> " (목)"
                                java.util.Calendar.FRIDAY -> " (금)"
                                java.util.Calendar.SATURDAY -> " (토)"
                                else -> ""
                            }
                        } else ""
                    } catch (e: Exception) {
                        ""
                    }
                }
                Text(
                    text = "${holiday.date}$dayOfWeekLabel",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            val displayType = if (holiday.type == "법정공휴일" && holiday.isAlternative) "대체공휴일" else holiday.type
            val badgeContainerColor = when (displayType) {
                "대체공휴일" -> MaterialTheme.colorScheme.primary
                "법정공휴일" -> MaterialTheme.colorScheme.secondary
                "임시공휴일" -> MaterialTheme.colorScheme.tertiary
                "개인공휴일" -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.outline
            }
            val badgeContentColor = when (displayType) {
                "대체공휴일" -> MaterialTheme.colorScheme.onPrimary
                "법정공휴일" -> MaterialTheme.colorScheme.onSecondary
                "임시공휴일" -> MaterialTheme.colorScheme.onTertiary
                "개인공휴일" -> MaterialTheme.colorScheme.onError
                else -> MaterialTheme.colorScheme.onSurface
            }
            
            Surface(
                color = badgeContainerColor,
                shape = RoundedCornerShape(6.dp)
            ) {
                Text(
                    text = displayType,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = badgeContentColor,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
            
            Spacer(modifier = Modifier.width(8.dp))
            
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "삭제",
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

// Tab 3: Execution Log Tracker
@Composable
fun ExecutionLogsScreen(bypassedLogs: List<BypassedLog>) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .verticalScroll(scrollState)
    ) {
        Text(
            text = "실시간 알람 바이패스 이력",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "공휴일 제외 기능 활성화 시, 설정한 날이 공휴일 및 대체공휴일에 닿았을 때 자동으로 알람을 비가동(Bypass)한 로그 정보입니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Spacer(modifier = Modifier.height(16.dp))

        if (bypassedLogs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageOf = Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary.copy(alpha = 0.3f),
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "기록된 바이패스 건이 없습니다",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                bypassedLogs.forEach { log ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .background(
                                        MaterialTheme.colorScheme.error.copy(alpha = 0.15f),
                                        CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageOf = Icons.Default.Notifications,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "알람[${log.label}] 바이패스 완료",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "사유: ${log.reason}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                            Text(
                                text = SimpleDateFormat("HH:mm", Locale.KOREAN).format(Date(log.timestamp)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                            )
                        }
                    }
                }
            }
        }
    }
}

// Edit Dialog Content
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AlarmEditDialog(
    alarm: Alarm,
    viewModel: AlarmViewModel,
    onDismiss: () -> Unit,
    onSave: (Int, Int, String, Boolean, List<Int>, Boolean, Boolean, String?, String?, Boolean, Int, Int, Boolean, Int) -> Unit
) {
    val context = LocalContext.current

    var selectedHour by remember { mutableIntStateOf(alarm.hour) }
    var selectedMinute by remember { mutableIntStateOf(alarm.minute) }
    
    var isEditingHour by remember { mutableStateOf(false) }
    var isEditingMinute by remember { mutableStateOf(false) }
    var hourInputText by remember { mutableStateOf(String.format(Locale.KOREAN, "%02d", alarm.hour)) }
    var minuteInputText by remember { mutableStateOf(String.format(Locale.KOREAN, "%02d", alarm.minute)) }

    androidx.compose.runtime.LaunchedEffect(selectedHour) {
        hourInputText = String.format(Locale.KOREAN, "%02d", selectedHour)
    }
    androidx.compose.runtime.LaunchedEffect(selectedMinute) {
        minuteInputText = String.format(Locale.KOREAN, "%02d", selectedMinute)
    }

    var labelInput by remember { mutableStateOf(alarm.label) }
    var isEnabled by remember { mutableStateOf(alarm.isEnabled) }
    var skipOnHolidays by remember { mutableStateOf(alarm.skipOnHolidays) }
    var onlyOnHolidays by remember { mutableStateOf(alarm.onlyOnHolidays) }
    
    var snoozeEnabled by remember { mutableStateOf(alarm.snoozeEnabled) }
    var snoozeInterval by remember { mutableIntStateOf(alarm.snoozeInterval) }
    var snoozeRepeats by remember { mutableIntStateOf(alarm.snoozeRepeats) }

    var preReminderEnabled by remember { mutableStateOf(alarm.preReminderEnabled) }
    var preReminderMinutes by remember { mutableIntStateOf(alarm.preReminderMinutes) }

    val initialDays = alarm.repeatDays
    var selectedDays by remember { mutableStateOf(initialDays) }

    val resolvedDefaultUri = remember { android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)?.toString() }
    var customToneUri by remember { mutableStateOf(alarm.customToneUri.takeIf { !it.isNullOrBlank() } ?: resolvedDefaultUri) }
    var customToneName by remember { mutableStateOf(alarm.customToneName.takeIf { !it.isNullOrBlank() } ?: "기본 알람 벨소리") }

    var showSystemSoundPicker by remember { mutableStateOf(false) }
    var isGloballyPreviewing by remember { mutableStateOf(false) }

    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            viewModel.stopSoundPreview()
        }
    }

    // Audio file picking launcher
    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val name = getFileName(context, uri)
            val savedLocation = viewModel.saveCustomAudioLocal(uri, name)
            if (savedLocation != null) {
                customToneUri = savedLocation.first
                customToneName = savedLocation.second
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f), RoundedCornerShape(24.dp))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = if (alarm.id == 0) "새 알람 만들기" else "알람 설정 편집",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                Spacer(modifier = Modifier.height(16.dp))

                // Time picker row (Elegant Custom Hours/Minutes Tuner - Scroll Wheel Mode)
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Hour Wheel Picker
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("시간", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                            Spacer(modifier = Modifier.height(4.dp))
                            
                            if (isEditingHour) {
                                Box(
                                    modifier = Modifier
                                        .width(75.dp)
                                        .height(140.dp)
                                        .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), RoundedCornerShape(12.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    androidx.compose.foundation.text.BasicTextField(
                                        value = hourInputText,
                                        onValueChange = { newVal ->
                                            val filtered = newVal.filter { it.isDigit() }.take(2)
                                            hourInputText = filtered
                                            val parsed = filtered.toIntOrNull()
                                            if (parsed != null && parsed in 0..23) {
                                                selectedHour = parsed
                                            }
                                        },
                                        keyboardOptions = KeyboardOptions(
                                            keyboardType = KeyboardType.Number,
                                            imeAction = ImeAction.Done
                                        ),
                                        keyboardActions = KeyboardActions(
                                            onDone = {
                                                if (hourInputText.isEmpty()) {
                                                    hourInputText = String.format(Locale.KOREAN, "%02d", selectedHour)
                                                } else {
                                                    selectedHour = hourInputText.toIntOrNull()?.coerceIn(0, 23) ?: selectedHour
                                                    hourInputText = String.format(Locale.KOREAN, "%02d", selectedHour)
                                                }
                                                isEditingHour = false
                                                isEditingMinute = false
                                            }
                                        ),
                                        textStyle = MaterialTheme.typography.headlineLarge.copy(
                                            fontSize = 32.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary,
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                        ),
                                        modifier = Modifier.fillMaxWidth().testTag("hour_text_input")
                                    )
                                }
                            } else {
                                WheelPicker(
                                    selectedValue = selectedHour,
                                    range = (0..23).toList(),
                                    onValueChange = { selectedHour = it },
                                    modifier = Modifier.testTag("hour_wheel_picker"),
                                    buttonPosition = "LEFT"
                                )
                            }
                        }
                        
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = ":",
                            style = MaterialTheme.typography.headlineLarge.copy(fontSize = 32.sp),
                            color = MaterialTheme.colorScheme.secondary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        
                        // Minute Wheel Picker
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("분", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                            Spacer(modifier = Modifier.height(4.dp))
                            
                            if (isEditingMinute) {
                                Box(
                                    modifier = Modifier
                                        .width(75.dp)
                                        .height(140.dp)
                                        .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), RoundedCornerShape(12.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    androidx.compose.foundation.text.BasicTextField(
                                        value = minuteInputText,
                                        onValueChange = { newVal ->
                                            val filtered = newVal.filter { it.isDigit() }.take(2)
                                            minuteInputText = filtered
                                            val parsed = filtered.toIntOrNull()
                                            if (parsed != null && parsed in 0..59) {
                                                selectedMinute = parsed
                                            }
                                        },
                                        keyboardOptions = KeyboardOptions(
                                            keyboardType = KeyboardType.Number,
                                            imeAction = ImeAction.Done
                                        ),
                                        keyboardActions = KeyboardActions(
                                            onDone = {
                                                if (minuteInputText.isEmpty()) {
                                                    minuteInputText = String.format(Locale.KOREAN, "%02d", selectedMinute)
                                                } else {
                                                    selectedMinute = minuteInputText.toIntOrNull()?.coerceIn(0, 59) ?: selectedMinute
                                                    minuteInputText = String.format(Locale.KOREAN, "%02d", selectedMinute)
                                                }
                                                isEditingHour = false
                                                isEditingMinute = false
                                            }
                                        ),
                                        textStyle = MaterialTheme.typography.headlineLarge.copy(
                                            fontSize = 32.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary,
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                        ),
                                        modifier = Modifier.fillMaxWidth().testTag("minute_text_input")
                                    )
                                }
                            } else {
                                WheelPicker(
                                    selectedValue = selectedMinute,
                                    range = (0..59).toList(),
                                    onValueChange = { selectedMinute = it },
                                    modifier = Modifier.testTag("minute_wheel_picker"),
                                    buttonPosition = "RIGHT"
                                )
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(10.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        TextButton(
                            onClick = {
                                if (!isEditingHour && !isEditingMinute) {
                                    hourInputText = String.format(Locale.KOREAN, "%02d", selectedHour)
                                    minuteInputText = String.format(Locale.KOREAN, "%02d", selectedMinute)
                                    isEditingHour = true
                                    isEditingMinute = true
                                } else {
                                    isEditingHour = false
                                    isEditingMinute = false
                                }
                            },
                            modifier = Modifier.testTag("toggle_input_mode_button")
                        ) {
                            Icon(
                                imageVector = if (isEditingHour) Icons.Default.Check else Icons.Default.Edit,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                if (isEditingHour) "드래그(스크롤) 선택 모드로 변경" else "숫자 키패드로 직접 입력하기",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
 
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "💡 숫자 열을 위아래로 드래그하여 시·분을 맞추거나, 직접 입력 모드를 켜서 입력할 수 있습니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.8f),
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
 
                Spacer(modifier = Modifier.height(16.dp))

                // Label Input text field
                OutlinedTextField(
                    value = labelInput,
                    onValueChange = { labelInput = it },
                    label = { Text("알람 이름 (레이블)") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.secondary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Weekday selection row
                Text(
                    text = "요일별 반복 설정 (선택)",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(bottom = 6.dp)
                )

                val dayShortNames = listOf("월", "화", "수", "목", "금", "토", "일")
                val renderOrder = listOf(7, 1, 2, 3, 4, 5, 6)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    for (i in renderOrder) {
                        val isDaySelected = selectedDays.contains(i)
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isDaySelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(
                                        alpha = 0.08f
                                    )
                                )
                                .clickable {
                                    selectedDays = if (isDaySelected) {
                                        selectedDays.filter { day -> day != i }
                                    } else {
                                        selectedDays + i
                                    }
                                }
                                .testTag("edit_weekday_pill_$i"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = dayShortNames[i - 1],
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = if (isDaySelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Custom Audio File Selector
                Text(
                    text = "알람음 음악 및 테스트 설정",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(bottom = 6.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.CenterStart,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 12.dp)
                        ) {
                            Text(
                                text = customToneName ?: "기본 알람 벨소리",
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = if (customToneName != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { showSystemSoundPicker = true },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            modifier = Modifier.weight(1f).height(44.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.List,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "기본 소리 목록",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Button(
                            onClick = { audioPickerLauncher.launch("audio/*") },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            modifier = Modifier.weight(1f).height(44.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "기기 파일 추가",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Text(
                        text = "🔊 선택된 알람소리 미리듣기 테스트",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.primary,
                            letterSpacing = 0.5.sp
                        ),
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
                    )

                    Button(
                        onClick = {
                            if (isGloballyPreviewing) {
                                viewModel.stopSoundPreview()
                                isGloballyPreviewing = false
                            } else {
                                val targetUri = customToneUri ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)?.toString()
                                if (targetUri != null) {
                                    viewModel.playSoundPreview(targetUri)
                                    isGloballyPreviewing = true
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isGloballyPreviewing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = if (isGloballyPreviewing) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onTertiaryContainer
                        ),
                        border = androidx.compose.foundation.BorderStroke(1.2.dp, if (isGloballyPreviewing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Icon(
                            imageVector = if (isGloballyPreviewing) Icons.Default.Close else Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = if (isGloballyPreviewing) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.tertiary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isGloballyPreviewing) "알람음 테스트 정지 (Stop)" else "알람음 테스트 시작 (미리듣기)",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 0.25.sp
                        )
                    }
                }

                if (showSystemSoundPicker) {
                    SystemSoundPickerDialog(
                        viewModel = viewModel,
                        initialToneUri = customToneUri,
                        onDismissRequest = {
                            showSystemSoundPicker = false
                            isGloballyPreviewing = false
                        },
                        onSoundSelected = { selectedUri, selectedName ->
                            customToneUri = selectedUri
                            customToneName = selectedName
                            showSystemSoundPicker = false
                        }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Holiday Operations Options Box
                Text(
                    text = "대한민국 공휴일 대응 스마트 조건식",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(bottom = 6.dp)
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f),
                            RoundedCornerShape(12.dp)
                        )
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Regular Mode (Ring Always)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = !skipOnHolidays && !onlyOnHolidays,
                            onClick = {
                                skipOnHolidays = false
                                onlyOnHolidays = false
                            },
                            colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary)
                        )
                        Column {
                            Text("법정 공휴일 공통 상시 실행", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                            Text("휴일 여부와 무관하게 매번 작동합니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                    }

                    // Option 1: Skip on Holidays
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = skipOnHolidays,
                            onClick = {
                                skipOnHolidays = true
                                onlyOnHolidays = false
                            },
                            colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary)
                        )
                        Column {
                            Text("공휴일 / 대체공휴일 제외 (울리지 않음)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                            Text("대한민국 빨간 장날과 대체공휴일 당일엔 알람 비가동.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                    }

                    // Option 2: Active on Holidays Only
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = onlyOnHolidays,
                            onClick = {
                                skipOnHolidays = false
                                onlyOnHolidays = true
                            },
                            colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary)
                        )
                        Column {
                            Text("공휴일 대체공휴일만 작동 (특정일 전용)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Text("대한민국 공공 공휴일과 대체공휴일에만 한정 작동합니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Snooze options
                Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "다시 울림 (스누즈) 설정",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f),
                            RoundedCornerShape(12.dp)
                        )
                        .padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { snoozeEnabled = !snoozeEnabled }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("다시 울림 예약 작동", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                            Text("알람 미해제 시 설정된 간격으로 다시 울립니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                        Switch(
                            checked = snoozeEnabled,
                            onCheckedChange = { snoozeEnabled = it },
                            modifier = Modifier.testTag("snooze_enabled_switch")
                        )
                    }

                    if (snoozeEnabled) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                        Spacer(modifier = Modifier.height(12.dp))

                        Text("다시 울림 간격", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(5, 10, 15, 20).forEach { mins ->
                                val isSelected = snoozeInterval == mins
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(36.dp)
                                        .clip(RoundedCornerShape(18.dp))
                                        .background(if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                                        .clickable { snoozeInterval = mins }
                                        .testTag("snooze_interval_$mins"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "${mins}분",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Text("반복 횟수", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(1, 2, 3, 5).forEach { reps ->
                                val isSelected = snoozeRepeats == reps
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(36.dp)
                                        .clip(RoundedCornerShape(18.dp))
                                        .background(if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                                        .clickable { snoozeRepeats = reps }
                                        .testTag("snooze_repeats_$reps"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "${reps}회",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Pre-reminder (미리알림) settings card
                Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Notifications,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "미리알림 설정",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f),
                            RoundedCornerShape(12.dp)
                        )
                        .padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { preReminderEnabled = !preReminderEnabled }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("미리알림 작동", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                            Text("알람 지정 시간 전에 설정된 시간으로 미세 진동 브리핑을 보냅니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f))
                        }
                        Switch(
                            checked = preReminderEnabled,
                            onCheckedChange = { preReminderEnabled = it },
                            modifier = Modifier.testTag("pre_reminder_enabled_switch")
                        )
                    }

                    if (preReminderEnabled) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                        Spacer(modifier = Modifier.height(12.dp))

                        Text("미리알림 전송 시점 설정", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(3, 5, 10, 15).forEach { mins ->
                                val isSelected = preReminderMinutes == mins
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(36.dp)
                                        .clip(RoundedCornerShape(18.dp))
                                        .background(if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                                        .clickable { preReminderMinutes = mins }
                                        .testTag("pre_reminder_minutes_$mins"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "${mins}분 전",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Final actions row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("취소")
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Button(
                        onClick = {
                            onSave(
                                selectedHour,
                                selectedMinute,
                                labelInput,
                                isEnabled,
                                selectedDays,
                                skipOnHolidays,
                                onlyOnHolidays,
                                customToneUri,
                                customToneName,
                                snoozeEnabled,
                                snoozeInterval,
                                snoozeRepeats,
                                preReminderEnabled,
                                preReminderMinutes
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.weight(1.3f)
                    ) {
                        Text("설정 저장")
                    }
                }
            }
        }
    }
}

// Fullscreen Alarm Trigger Overlay
@Composable
fun ActiveAlarmOverlay(
    activeAlarm: com.example.ui.viewmodel.ActiveAlarmState,
    onDismiss: () -> Unit,
    onSnooze: (Int) -> Unit
) {
    // Elegant, adjustable snooze minutes state (rounded to 5-minute intervals, max 60 minutes)
    var snoozeIntervalMinutes by remember { 
        val rawVal = if (activeAlarm.snoozeInterval > 0) activeAlarm.snoozeInterval else 5
        val initial = (((rawVal + 2) / 5) * 5).coerceIn(5, 60)
        mutableStateOf(initial)
    }

    // Intercept back gesture/back button specifically to snooze!
    BackHandler(enabled = true) {
        onSnooze(snoozeIntervalMinutes)
    }

    val coroutineScope = rememberCoroutineScope()
    // Animators for 2D position handling of the center drag handle
    val dragX = remember { Animatable(0f) }
    val dragY = remember { Animatable(0f) }
    var isDragging by remember { mutableStateOf(false) }
    var isDismissed by remember { mutableStateOf(false) }

    val density = LocalDensity.current
    val thresholdDp = 145.dp
    val thresholdPx = with(density) { thresholdDp.toPx() }

    val infiniteTransition = rememberInfiniteTransition()
    
    // Rapid rotation oscillation for mechanical vibrating cue
    val shakeRotation by infiniteTransition.animateFloat(
        initialValue = -10f,
        targetValue = 10f,
        animationSpec = infiniteRepeatable(
            animation = tween(75, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    // Rapid translation jittering
    val shakeTranslationX by infiniteTransition.animateFloat(
        initialValue = -4f,
        targetValue = 4f,
        animationSpec = infiniteRepeatable(
            animation = tween(55, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        )
    )
    val shakeTranslationY by infiniteTransition.animateFloat(
        initialValue = -3f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(65, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    // Breathing glow animation for the track boundary ring
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        )
    )

    val currentDistance = kotlin.math.sqrt(dragX.value * dragX.value + dragY.value * dragY.value)
    val dragFraction = (currentDistance / thresholdPx).coerceIn(0f, 1f)

    // Get current calendar Date formatted as in the provided screenshot (M월 d일 E요일)
    val calendar = remember { java.util.Calendar.getInstance() }
    val dateString = remember(calendar) {
        java.text.SimpleDateFormat("M월 d일 E요일", java.util.Locale.KOREAN).format(calendar.time)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF000000)) // Pitch Black background
            .clickable(enabled = false) {}, // Intercept touch events
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            
            // 1. Top Section: Ultra Clean Clock Display (Visual representation matching screenshot)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 48.dp)
            ) {
                // Giant Digital Time
                Text(
                    text = activeAlarm.time,
                    style = MaterialTheme.typography.headlineLarge.copy(
                        fontSize = 82.sp,
                        fontWeight = FontWeight.Light,
                        letterSpacing = (-1).sp
                    ),
                    color = Color.White,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Korean Lunar/Calendar Date (6월 13일 토요일)
                Text(
                    text = dateString,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    color = Color.White.copy(alpha = 0.9f),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Alarm Label
                Text(
                    text = activeAlarm.label.ifBlank { "알람" },
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Normal
                    ),
                    color = Color.White.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )
            }

            // 2. Middle Section: Outward Swipable Center Circle
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                // Big Guide track ring background (Translucent gray zone)
                Box(
                    modifier = Modifier
                        .size(175.dp * pulseScale)
                        .background(
                            color = Color(0xFF1E1E21).copy(alpha = 0.5f + (0.3f * dragFraction)),
                            shape = CircleShape
                        )
                        .border(
                            width = 1.dp,
                            color = Color.White.copy(alpha = 0.12f + (0.28f * dragFraction)),
                            shape = CircleShape
                        )
                )

                // Handle is dragged but central connecting trace line is removed for a cleaner look as requested


                // Swipable Alarm Central Handle with white X interior
                val dragTranslationX = dragX.value
                val dragTranslationY = dragY.value
                val densityCoeff = with(density) { 1.dp.toPx() }

                Box(
                    modifier = Modifier
                        .offset(
                            x = with(density) { dragTranslationX.toDp() },
                            y = with(density) { dragTranslationY.toDp() }
                        )
                        .graphicsLayer {
                            // Ringing vibration applied entirely via RenderThread when NOT dragged
                            rotationZ = if (isDragging) 0f else shakeRotation
                            translationX = if (isDragging) 0f else (shakeTranslationX * densityCoeff)
                            translationY = if (isDragging) 0f else (shakeTranslationY * densityCoeff)
                        }
                        .size(105.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF2C2C2E)) // Premium gray handle background
                        .border(
                            width = 1.5.dp,
                            color = Color.White.copy(alpha = 0.85f),
                            shape = CircleShape
                        )
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { isDragging = true },
                                onDragEnd = {
                                    isDragging = false
                                    val dist = kotlin.math.sqrt(dragX.value * dragX.value + dragY.value * dragY.value)
                                    if (dist >= thresholdPx) {
                                        if (!isDismissed) {
                                            isDismissed = true
                                            onDismiss()
                                        }
                                    } else {
                                        // Snap back home
                                        coroutineScope.launch {
                                            dragX.animateTo(0f, spring(stiffness = Spring.StiffnessMedium))
                                        }
                                        coroutineScope.launch {
                                            dragY.animateTo(0f, spring(stiffness = Spring.StiffnessMedium))
                                        }
                                    }
                                },
                                onDragCancel = {
                                    isDragging = false
                                    coroutineScope.launch {
                                        dragX.animateTo(0f, spring(stiffness = Spring.StiffnessMedium))
                                    }
                                    coroutineScope.launch {
                                        dragY.animateTo(0f, spring(stiffness = Spring.StiffnessMedium))
                                    }
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    coroutineScope.launch {
                                        val nextX = dragX.value + dragAmount.x
                                        val nextY = dragY.value + dragAmount.y
                                        dragX.snapTo(nextX)
                                        dragY.snapTo(nextY)

                                        val currentDist = kotlin.math.sqrt(nextX * nextX + nextY * nextY)
                                        if (currentDist >= thresholdPx && !isDismissed) {
                                            isDismissed = true
                                            onDismiss()
                                        }
                                    }
                                }
                            )
                        }
                        .testTag("swipe_to_dismiss_handle"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "알람 해제",
                        tint = Color.White,
                        modifier = Modifier.size(34.dp)
                    )
                }
            }

            // 3. Bottom Section: Interactive Snooze Controls matching bottom of screenshot
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp)
            ) {
                // Instructive touch tag
                Text(
                    text = "원 바깥방향으로 밀어서 알람 해제",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Normal
                    ),
                    color = Color.White.copy(alpha = 0.5f),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Bottom actions row: (-) ... [Snooze Capsule] ... (+)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Dec Button (-) using vector-equivalent line to ensure weight balance
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF242426))
                            .clickable {
                                snoozeIntervalMinutes = (snoozeIntervalMinutes - 5).coerceAtLeast(5)
                            }
                            .testTag("snooze_dec_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(16.dp, 2.dp)
                                .background(Color.White)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    // Snooze Capsule Button (Responsive width via weight)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp)
                            .clip(RoundedCornerShape(28.dp))
                            .background(Color(0xFF242426))
                            .clickable {
                                onSnooze(snoozeIntervalMinutes)
                            }
                            .testTag("snooze_ringing_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${snoozeIntervalMinutes}분 후 다시 알림",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    // Inc Button (+) using vector icon to match dec button weight
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF242426))
                            .clickable {
                                snoozeIntervalMinutes = (snoozeIntervalMinutes + 5).coerceAtMost(60)
                            }
                            .testTag("snooze_inc_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageOf = Icons.Default.Add,
                            contentDescription = "시간 증가",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }
    }
}

// Retrieve file name helper for content providers
private fun getFileName(context: Context, uri: Uri): String {
    var result: String? = null
    if (uri.scheme == "content") {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        try {
            if (cursor != null && cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index != -1) {
                    result = cursor.getString(index)
                }
            }
        } finally {
            cursor?.close()
        }
    }
    if (result == null) {
        result = uri.path
        val cut = result?.lastIndexOf('/')
        if (cut != null && cut != -1) {
            result = result.substring(cut + 1)
        }
    }
    return result ?: "custom_bell.mp3"
}

// Dialog for choosing built-in system sound
@Composable
fun SystemSoundPickerDialog(
    viewModel: com.example.ui.viewmodel.AlarmViewModel,
    initialToneUri: String?,
    onDismissRequest: () -> Unit,
    onSoundSelected: (String, String) -> Unit
) {
    val systemSounds = androidx.compose.runtime.remember { viewModel.getSystemAlarms() }
    var selectedUri by androidx.compose.runtime.remember { 
        androidx.compose.runtime.mutableStateOf(initialToneUri ?: systemSounds.firstOrNull()?.second ?: "") 
    }
    var playingUri by androidx.compose.runtime.remember { 
        androidx.compose.runtime.mutableStateOf<String?>(null) 
    }

    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            viewModel.stopSoundPreview()
        }
    }

    AlertDialog(
        onDismissRequest = {
            viewModel.stopSoundPreview()
            onDismissRequest()
        },
        title = {
            Text("기본 알람 소리 선택", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 350.dp)) {
                Text(
                    "설정할 사운드를 누르면 재생을 통해 미리 들어볼 수 있습니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(systemSounds) { (name, uri) ->
                        val isSelected = selectedUri == uri
                        val isPlaying = playingUri == uri
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                                    else Color.Transparent
                                )
                                .clickable {
                                    selectedUri = uri
                                    playingUri = uri
                                    viewModel.playSoundPreview(uri)
                                }
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = {
                                    selectedUri = uri
                                    playingUri = uri
                                    viewModel.playSoundPreview(uri)
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = {
                                    selectedUri = uri
                                    if (isPlaying) {
                                        playingUri = null
                                        viewModel.stopSoundPreview()
                                    } else {
                                        playingUri = uri
                                        viewModel.playSoundPreview(uri)
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Default.Close else Icons.Default.PlayArrow,
                                    contentDescription = "재생 정지",
                                    tint = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val matching = systemSounds.firstOrNull { it.second == selectedUri }
                    val finalName = matching?.first ?: "기본 알람"
                    onSoundSelected(selectedUri, finalName)
                    viewModel.stopSoundPreview()
                }
            ) {
                Text("선택")
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    viewModel.stopSoundPreview()
                    onDismissRequest()
                }
            ) {
                Text("취소")
            }
        }
    )
}

// Vertical scrolling wheel picker for tactile time/year adjustments with quick step buttons
@Composable
fun WheelPicker(
    selectedValue: Int,
    range: List<Int>,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    itemWidth: androidx.compose.ui.unit.Dp = 75.dp,
    formatter: (Int) -> String = { String.format(Locale.KOREAN, "%02d", it) },
    buttonsVertical: Boolean = false,
    buttonPosition: String = "NONE" // "LEFT", "RIGHT", "TOP_BOTTOM", "NONE"
) {
    val lazyListState = androidx.compose.foundation.lazy.rememberLazyListState(
        initialFirstVisibleItemIndex = range.indexOf(selectedValue).coerceAtLeast(0)
    )

    // Sync state if selectedValue changes externally
    androidx.compose.runtime.LaunchedEffect(selectedValue) {
        val targetIdx = range.indexOf(selectedValue)
        if (targetIdx >= 0 && lazyListState.firstVisibleItemIndex != targetIdx) {
            lazyListState.animateScrollToItem(targetIdx)
        }
    }

    // Capture user drag scroll end to snap and select nearest item
    androidx.compose.runtime.LaunchedEffect(lazyListState.isScrollInProgress) {
        if (!lazyListState.isScrollInProgress) {
            val centerIdx = lazyListState.firstVisibleItemIndex
            if (centerIdx in range.indices) {
                onValueChange(range[centerIdx])
            }
        }
    }

    val sideButtonsColumn = @Composable {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.height(140.dp) // customized height for tight alignment
        ) {
            // Plus Button
            androidx.compose.material3.IconButton(
                onClick = {
                    val currentIdx = range.indexOf(selectedValue)
                    if (currentIdx < range.size - 1) {
                        onValueChange(range[currentIdx + 1])
                    } else {
                        onValueChange(range.first()) // Wrap around
                    }
                },
                modifier = Modifier
                    .size(36.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.50f), RoundedCornerShape(10.dp))
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "증가",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Minus Button
            androidx.compose.material3.IconButton(
                onClick = {
                    val currentIdx = range.indexOf(selectedValue)
                    if (currentIdx > 0) {
                        onValueChange(range[currentIdx - 1])
                    } else {
                        onValueChange(range.last()) // Wrap around
                    }
                },
                modifier = Modifier
                    .size(36.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.50f), RoundedCornerShape(10.dp))
            ) {
                Box(
                    modifier = Modifier.size(18.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .width(12.dp)
                            .height(2.5.dp)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }
        }
    }

    val centerWheelBox = @Composable {
        Box(
            modifier = Modifier
                .height(130.dp)
                .width(itemWidth),
            contentAlignment = Alignment.Center
        ) {
            // Highlighting center selection indicator
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                    .border(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            )

            androidx.compose.foundation.lazy.LazyColumn(
                state = lazyListState,
                contentPadding = PaddingValues(vertical = 43.dp), // exact mathematically computed alignment padding
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxSize()
            ) {
                items(range.size) { idx ->
                    val valNum = range[idx]
                    val isSelected = selectedValue == valNum
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .clickable { onValueChange(valNum) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = formatter(valNum),
                            style = if (isSelected) {
                                MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 24.sp
                                )
                            } else {
                                MaterialTheme.typography.bodyMedium.copy(
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f) // Increased contrast!
                                )
                            },
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }
        }
    }

    if (buttonPosition == "LEFT" || buttonPosition == "RIGHT" || buttonPosition == "LEFT_RIGHT") {
        Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (buttonPosition == "LEFT") {
                sideButtonsColumn()
                Spacer(modifier = Modifier.width(6.dp))
            } else if (buttonPosition == "LEFT_RIGHT") {
                // Minus on Left
                androidx.compose.material3.IconButton(
                    onClick = {
                        val currentIdx = range.indexOf(selectedValue)
                        if (currentIdx > 0) {
                            onValueChange(range[currentIdx - 1])
                        } else {
                            onValueChange(range.last())
                        }
                    },
                    modifier = Modifier
                        .size(36.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.50f), RoundedCornerShape(10.dp))
                ) {
                    Box(
                        modifier = Modifier.size(18.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .width(12.dp)
                                .height(2.5.dp)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(16.dp)) // spread out slightly as requested!
            }
            
            centerWheelBox()

            if (buttonPosition == "RIGHT") {
                Spacer(modifier = Modifier.width(6.dp))
                sideButtonsColumn()
            } else if (buttonPosition == "LEFT_RIGHT") {
                Spacer(modifier = Modifier.width(16.dp)) // spread out slightly as requested!
                // Plus on Right
                androidx.compose.material3.IconButton(
                    onClick = {
                        val currentIdx = range.indexOf(selectedValue)
                        if (currentIdx < range.size - 1) {
                            onValueChange(range[currentIdx + 1])
                        } else {
                            onValueChange(range.first())
                        }
                    },
                    modifier = Modifier
                        .size(36.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.50f), RoundedCornerShape(10.dp))
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "증가",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    } else if (buttonsVertical || buttonPosition == "TOP_BOTTOM") {
        Column(
            modifier = modifier,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Plus Button (Up)
            androidx.compose.material3.IconButton(
                onClick = {
                    val currentIdx = range.indexOf(selectedValue)
                    if (currentIdx < range.size - 1) {
                        onValueChange(range[currentIdx + 1])
                    } else {
                        onValueChange(range.first()) // Wrap around
                    }
                },
                modifier = Modifier
                    .size(32.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.40f), RoundedCornerShape(8.dp))
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "증가",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            centerWheelBox()

            Spacer(modifier = Modifier.height(6.dp))

            // Minus Button (Down)
            androidx.compose.material3.IconButton(
                onClick = {
                    val currentIdx = range.indexOf(selectedValue)
                    if (currentIdx > 0) {
                        onValueChange(range[currentIdx - 1])
                    } else {
                        onValueChange(range.last()) // Wrap around
                    }
                },
                modifier = Modifier
                    .size(32.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.40f), RoundedCornerShape(8.dp))
            ) {
                Box(
                    modifier = Modifier.size(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .width(11.dp)
                            .height(2.dp)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }
        }
    } else {
        Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            // Minus Button for step-down adjustment
            androidx.compose.material3.IconButton(
                onClick = {
                    val currentIdx = range.indexOf(selectedValue)
                    if (currentIdx > 0) {
                        onValueChange(range[currentIdx - 1])
                    } else {
                        onValueChange(range.last()) // Wrap around
                    }
                },
                modifier = Modifier
                    .size(32.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.40f), RoundedCornerShape(8.dp))
            ) {
                Box(
                    modifier = Modifier.size(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .width(11.dp)
                            .height(2.dp)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }

            Spacer(modifier = Modifier.width(6.dp))

            centerWheelBox()

            Spacer(modifier = Modifier.width(6.dp))

            // Plus Button for step-up adjustment
            androidx.compose.material3.IconButton(
                onClick = {
                    val currentIdx = range.indexOf(selectedValue)
                    if (currentIdx < range.size - 1) {
                        onValueChange(range[currentIdx + 1])
                    } else {
                        onValueChange(range.first()) // Wrap around
                    }
                },
                modifier = Modifier
                    .size(32.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.40f), RoundedCornerShape(8.dp))
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "증가",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

