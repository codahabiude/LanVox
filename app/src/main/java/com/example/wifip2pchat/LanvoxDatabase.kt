package com.example.wifip2pchat

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room Database holding locally persisted LANVOX chat history.
 */
@Database(entities = [ChatMessageEntity::class], version = 5, exportSchema = false)
abstract class LanvoxDatabase : RoomDatabase() {

    abstract fun chatMessageDao(): ChatMessageDao

    companion object {
        private const val DB_NAME = "lanvox_room_chat.db"

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN attachment_name TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN attachment_mime TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN attachment_size INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN attachment_path TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN attachment_state TEXT NOT NULL DEFAULT 'NONE'")
                db.execSQL("ALTER TABLE messages ADD COLUMN transfer_id TEXT")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN message_id TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_msg_id ON messages(message_id)")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN reply_to_id TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN reply_to_sender TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN reply_to_text TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN reactions TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN is_starred INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN is_pinned INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN edited_at INTEGER")
                db.execSQL("ALTER TABLE messages ADD COLUMN expires_at INTEGER")
                db.execSQL("ALTER TABLE messages ADD COLUMN message_type TEXT NOT NULL DEFAULT 'STANDARD'")
            }
        }

        @Volatile
        private var instance: LanvoxDatabase? = null

        fun get(context: Context): LanvoxDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    LanvoxDatabase::class.java,
                    DB_NAME
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .build()
                    .also { instance = it }
            }
    }
}
