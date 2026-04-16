package com.anvit.localai.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.anvit.localai.data.db.entities.*

@Database(
    entities = [
        DocumentEntity::class,
        ChunkEntity::class,
        ChunkFtsEntity::class,
        ChatMessageEntity::class,
        ChatSessionEntity::class,
        CollectionEntity::class
    ],
    version = 8,
    exportSchema = false
)
abstract class AnvitDatabase : RoomDatabase() {
    abstract fun documentDao(): DocumentDao
    abstract fun chatDao(): ChatDao
    abstract fun chatSessionDao(): ChatSessionDao
    abstract fun collectionDao(): CollectionDao

    companion object {
        @Volatile
        private var INSTANCE: AnvitDatabase? = null

        /** v1 → v2: Add thinkingContent to chat_messages */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE chat_messages ADD COLUMN thinkingContent TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        /**
         * v2 → v3: Introduce chat sessions.
         * - Create chat_sessions table
         * - Add sessionId to chat_messages (all existing rows assigned to a "Legacy" session)
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                val legacySessionId = "legacy-session"
                val now = System.currentTimeMillis()

                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS chat_sessions (
                        id TEXT NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        messageCount INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    "INSERT OR IGNORE INTO chat_sessions (id, title, createdAt, updatedAt, messageCount) " +
                    "VALUES ('$legacySessionId', 'Previous Conversation', $now, $now, 0)"
                )
                database.execSQL(
                    "ALTER TABLE chat_messages ADD COLUMN sessionId TEXT NOT NULL DEFAULT '$legacySessionId'"
                )
            }
        }

        /**
         * v3 → v4: Introduce vector DB collections.
         * - Create collections table with a default "General" collection
         * - Add collectionId to documents and chunks
         * - Rebuild FTS index to account for new chunks column
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                val now = System.currentTimeMillis()

                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS collections (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        description TEXT NOT NULL DEFAULT '',
                        createdAt INTEGER NOT NULL,
                        isDefault INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    "INSERT OR IGNORE INTO collections (id, name, description, createdAt, isDefault) " +
                    "VALUES ('default-collection', 'General', 'Default document collection', $now, 1)"
                )
                database.execSQL(
                    "ALTER TABLE documents ADD COLUMN collectionId TEXT NOT NULL DEFAULT 'default-collection'"
                )
                database.execSQL(
                    "ALTER TABLE chunks ADD COLUMN collectionId TEXT NOT NULL DEFAULT 'default-collection'"
                )
                // Rebuild FTS index to include the new collectionId column in content table
                database.execSQL("DROP TABLE IF EXISTS chunks_fts")
                database.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS chunks_fts USING fts4(content=`chunks`, `content`)"
                )
                database.execSQL("INSERT INTO chunks_fts(chunks_fts) VALUES('rebuild')")
            }
        }

        /** v4 → v5: Add imagePath column to chat_messages for image attachment feature. */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE chat_messages ADD COLUMN imagePath TEXT"
                )
            }
        }

        /** v5 → v6: Add usedSources column to chat_messages */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE chat_messages ADD COLUMN usedSources TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        /** v6 → v7: Add audioPath column to chat_messages for audio attachment feature. */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE chat_messages ADD COLUMN audioPath TEXT"
                )
            }
        }

        /** v7 → v8: Add isTranscribed flag to distinguish transcribed-from-audio vs typed text. */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE chat_messages ADD COLUMN isTranscribed INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        fun getInstance(context: Context): AnvitDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AnvitDatabase::class.java,
                    "anvit_database"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
