package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "holidays")
data class Holiday(
    @PrimaryKey val date: String, // "YYYY-MM-DD"
    val name: String,
    val isAlternative: Boolean = false,
    val year: Int,
    val type: String = "법정공휴일"
)
