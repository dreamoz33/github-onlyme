package com.example.data.local

import androidx.room.*
import com.example.data.model.Holiday
import kotlinx.coroutines.flow.Flow

@Dao
interface HolidayDao {
    @Query("SELECT * FROM holidays ORDER BY date ASC")
    fun getAllHolidays(): Flow<List<Holiday>>

    @Query("SELECT * FROM holidays WHERE year = :year ORDER BY date ASC")
    suspend fun getHolidaysByYear(year: Int): List<Holiday>

    @Query("SELECT EXISTS(SELECT 1 FROM holidays WHERE date = :date)")
    suspend fun isHoliday(date: String): Boolean

    @Query("SELECT * FROM holidays WHERE date = :date LIMIT 1")
    suspend fun getHolidayByDate(date: String): Holiday?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHolidays(holidays: List<Holiday>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHoliday(holiday: Holiday)

    @Delete
    suspend fun deleteHoliday(holiday: Holiday)

    @Query("DELETE FROM holidays WHERE year = :year")
    suspend fun deleteHolidaysByYear(year: Int)
}
