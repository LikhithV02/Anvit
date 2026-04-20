package com.anvit.localai.data.db

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.anvit.localai.data.db.entities.*
import com.anvit.localai.utils.currentTimeMillis

@Database(
    entities = [
        DocumentEntity::class, ChunkEntity::class, ChunkFtsEntity::class,
        ChatMessageEntity::class, ChatSessionEntity::class, CollectionEntity::class
    ],
    version = 8,
    exportSchema = false
)
@ConstructedBy(AnvitDatabaseConstructor::class)
abstract class AnvitDatabase : RoomDatabase() {
    abstract fun documentDao(): DocumentDao
    abstract fun chatDao(): ChatDao
    abstract fun chatSessionDao(): ChatSessionDao
    abstract fun collectionDao(): CollectionDao
}

// Room KMP constructor marker — must be in the same file as @Database class
@Suppress("NO_ACTUAL_FOR_EXPECT")
expect object AnvitDatabaseConstructor : RoomDatabaseConstructor<AnvitDatabase>

// ── Migrations (KMP: SQLiteConnection instead of SupportSQLiteDatabase) ────────

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE chat_messages ADD COLUMN thinkingContent TEXT NOT NULL DEFAULT ''")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(connection: SQLiteConnection) {
        val now = currentTimeMillis()
        val legacyId = "legacy-session"
        connection.execSQL("""
            CREATE TABLE IF NOT EXISTS chat_sessions (
                id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL,
                createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL,
                messageCount INTEGER NOT NULL DEFAULT 0)
        """.trimIndent())
        connection.execSQL("INSERT OR IGNORE INTO chat_sessions (id,title,createdAt,updatedAt,messageCount) VALUES ('$legacyId','Previous Conversation',$now,$now,0)")
        connection.execSQL("ALTER TABLE chat_messages ADD COLUMN sessionId TEXT NOT NULL DEFAULT '$legacyId'")
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(connection: SQLiteConnection) {
        val now = currentTimeMillis()
        connection.execSQL("""
            CREATE TABLE IF NOT EXISTS collections (
                id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL,
                description TEXT NOT NULL DEFAULT '', createdAt INTEGER NOT NULL,
                isDefault INTEGER NOT NULL DEFAULT 0)
        """.trimIndent())
        connection.execSQL("INSERT OR IGNORE INTO collections (id,name,description,createdAt,isDefault) VALUES ('default-collection','General','Default document collection',$now,1)")
        connection.execSQL("ALTER TABLE documents ADD COLUMN collectionId TEXT NOT NULL DEFAULT 'default-collection'")
        connection.execSQL("ALTER TABLE chunks ADD COLUMN collectionId TEXT NOT NULL DEFAULT 'default-collection'")
        connection.execSQL("DROP TABLE IF EXISTS chunks_fts")
        connection.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS chunks_fts USING fts4(content=`chunks`,`content`)")
        connection.execSQL("INSERT INTO chunks_fts(chunks_fts) VALUES('rebuild')")
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE chat_messages ADD COLUMN imagePath TEXT")
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE chat_messages ADD COLUMN usedSources TEXT NOT NULL DEFAULT ''")
    }
}

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE chat_messages ADD COLUMN audioPath TEXT")
    }
}

val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE chat_messages ADD COLUMN isTranscribed INTEGER NOT NULL DEFAULT 0")
    }
}

val ALL_MIGRATIONS = arrayOf(
    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
    MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8
)

/** Platform-specific builder (androidMain / iosMain provide actuals). */
expect fun getDatabaseBuilder(): RoomDatabase.Builder<AnvitDatabase>
