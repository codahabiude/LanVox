package com.example.wifip2pchat

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Room Database holding locally persisted LANVOX chat history.
 */
@Database(entities = [ChatMessageEntity::class], version = 1, exportSchema = false)
abstract class LanvoxDatabase : RoomDatabase() {

    abstract fun chatMessageDao(): ChatMessageDao

    companion object {
        private const val DB_NAME = "lanvox_room_chat.db"

        @Volatile
        private var instance: LanvoxDatabase? = null

        fun get(context: Context): LanvoxDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    LanvoxDatabase::class.java,
                    DB_NAME
                ).build().also { instance = it }
            }
    }
}
