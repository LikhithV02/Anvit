package com.anvit.localai.data.db

import androidx.room.Room
import androidx.room.RoomDatabase
import com.anvit.localai.AnvitContextHolder

actual fun getDatabaseBuilder(): RoomDatabase.Builder<AnvitDatabase> {
    val context = AnvitContextHolder.appContext
    val dbFile = context.getDatabasePath("anvit_database")
    return Room.databaseBuilder<AnvitDatabase>(
        context = context,
        name = dbFile.absolutePath
    ).addMigrations(*ALL_MIGRATIONS)
}
