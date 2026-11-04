package ru.daybook.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "entries")
data class DaybookEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val title: String,
    val body: String,
    val created: Long,
    val updated: Long,
    val due: String? = null,
    val priority: String? = null,
    val done: Boolean = false
)
