package com.anvit.localai.data.db

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSUserDomainMask

@OptIn(ExperimentalForeignApi::class)
actual fun getDatabaseBuilder(): RoomDatabase.Builder<AnvitDatabase> {
    val docsDir = NSFileManager.defaultManager.URLForDirectory(
        directory     = NSDocumentDirectory,
        inDomain      = NSUserDomainMask,
        appropriateForURL = null,
        create        = true,
        error         = null
    )
    val dbPath = docsDir?.path + "/anvit_database"
    return Room.databaseBuilder<AnvitDatabase>(name = dbPath)
        .setDriver(BundledSQLiteDriver())
        .addMigrations(*ALL_MIGRATIONS)
}
