package ru.daybook.app.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [DaybookEntity::class], version = 1, exportSchema = false)
abstract class DaybookDatabase : RoomDatabase() {
    abstract fun daybookDao(): DaybookDao
}
