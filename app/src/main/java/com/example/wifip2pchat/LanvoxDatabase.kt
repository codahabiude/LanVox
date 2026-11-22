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
@Database(entities = [ChatMessageEntity::class], version = 2, exportSchema = false)
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

        @Volatile
        private var instance: LanvoxDatabase? = null

        fun get(context: Context): LanvoxDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    LanvoxDatabase::class.java,
                    DB_NAME
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
    }
}
