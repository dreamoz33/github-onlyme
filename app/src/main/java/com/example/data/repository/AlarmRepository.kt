package com.example.data.repository

import android.content.Context
import com.example.data.alarm.AlarmScheduler
import com.example.data.local.AlarmDao
import com.example.data.model.Alarm
import kotlinx.coroutines.flow.Flow

class AlarmRepository(
    private val alarmDao: AlarmDao,
    context: Context
) {
    private val scheduler = AlarmScheduler(context)

    val allAlarms: Flow<List<Alarm>> = alarmDao.getAllAlarms()

    suspend fun getAlarmById(id: Int): Alarm? {
        return alarmDao.getAlarmById(id)
    }

    suspend fun insertAlarm(alarm: Alarm): Long {
        val id = alarmDao.insertAlarm(alarm)
        val createdAlarm = alarm.copy(id = id.toInt())
        if (createdAlarm.isEnabled) {
            scheduler.schedule(createdAlarm)
        } else {
            scheduler.cancel(createdAlarm)
        }
        return id
    }

    suspend fun updateAlarm(alarm: Alarm) {
        alarmDao.updateAlarm(alarm)
        if (alarm.isEnabled) {
            scheduler.schedule(alarm)
        } else {
            scheduler.cancel(alarm)
        }
    }

    suspend fun deleteAlarm(alarm: Alarm) {
        scheduler.cancel(alarm)
        alarmDao.deleteAlarm(alarm)
    }

    suspend fun toggleAlarm(alarm: Alarm) {
        val updated = alarm.copy(isEnabled = !alarm.isEnabled)
        updateAlarm(updated)
    }

    suspend fun dismissEarly(alarm: Alarm) {
        scheduler.cancel(alarm)
        if (alarm.repeatDays.isEmpty()) {
            val updated = alarm.copy(isEnabled = false)
            alarmDao.updateAlarm(updated)
        } else {
            alarmDao.updateAlarm(alarm)
            scheduler.schedule(alarm, forceTomorrow = true)
        }
    }
}
