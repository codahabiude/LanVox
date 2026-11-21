package com.example.wifip2pchat

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * One row in the chats list — a conversation with a single contact device.
 */
data class Conversation(
    val peerId: String,
    val peerName: String?,
    val lastText: String,
    val lastIsSentByMe: Boolean,
    val lastTimestamp: Long,
    val messageCount: Int
)

/**
 * Local chat persistence for LANVOX with per-contact threads.
 *
 * Every text message (sent or received) is written to a private SQLite
 * database on the phone's internal storage:
 *
 *     /data/data/com.example.lanvox/databases/lanvox_chat.db
 *
 * Each message is tagged with [peer_id] — the remote device's WiFi Direct
 * MAC address — so every contact gets its own separate conversation,
 * WhatsApp-style. [peer_name] stores the human-readable device name.
 *
 * The database is app-private (no permission needed) and is included in
 * Android backups automatically since the manifest has allowBackup=true.
 *
 * Schema (v2):
 *   messages(
 *     _id       INTEGER PRIMARY KEY AUTOINCREMENT,
 *     text      TEXT    NOT NULL,   -- message body
 *     is_sent   INTEGER NOT NULL,   -- 1 = outgoing, 0 = incoming
 *     timestamp INTEGER NOT NULL,   -- epoch millis
 *     peer      TEXT,               -- (v1, deprecated) group-owner IP
 *     peer_id   TEXT,               -- stable remote device MAC
 *     peer_name TEXT                -- remote device display name
 *   )
 */
class ChatDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val TAG = "ChatDatabase"
        private const val DB_NAME = "lanvox_chat.db"
        private const val DB_VERSION = 2
        private const val TABLE = "messages"

        // How many messages are loaded into a chat screen at startup
        const val HISTORY_LIMIT = 200

        @Volatile
        private var instance: ChatDatabase? = null

        /** Thread-safe singleton accessor. */
        fun get(context: Context): ChatDatabase =
            instance ?: synchronized(this) {
                instance ?: ChatDatabase(context.applicationContext).also { instance = it }
            }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
                _id       INTEGER PRIMARY KEY AUTOINCREMENT,
                text      TEXT    NOT NULL,
                is_sent   INTEGER NOT NULL,
                timestamp INTEGER NOT NULL,
                peer      TEXT,
                peer_id   TEXT,
                peer_name TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_messages_ts ON $TABLE(timestamp)")
        db.execSQL("CREATE INDEX idx_messages_peer ON $TABLE(peer_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // v1 stored messages without peer identity — add the columns and
            // backfill peer_id from the legacy group-owner IP so nothing is lost.
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN peer_id TEXT")
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN peer_name TEXT")
            db.execSQL("UPDATE $TABLE SET peer_id = peer WHERE peer_id IS NULL")
        }
    }

    // ======================== Write ========================

    /**
     * Insert one message tagged with the contact's identity.
     * Safe to call from any thread via the IO dispatcher.
     */
    fun insertMessage(message: ChatMessage, peerId: String?, peerName: String?): Long {
        val values = ContentValues().apply {
            put("text", message.text)
            put("is_sent", if (message.isSentByMe) 1 else 0)
            put("timestamp", message.timestamp)
            put("peer_id", peerId)
            put("peer_name", peerName)
        }
        return writableDatabase.insert(TABLE, null, values)
    }

    /** Delete one contact's whole conversation ("long-press → delete"). */
    fun clearConversation(peerId: String) {
        writableDatabase.delete(TABLE, "peer_id = ?", arrayOf(peerId))
    }

    /** Delete every stored message across all contacts ("clear history"). */
    fun clearAll() {
        writableDatabase.delete(TABLE, null, null)
    }

    // ======================== Read ========================

    /**
     * Load the most recent [limit] messages of ONE contact, in chat order
     * (oldest first) so they can feed straight into the RecyclerView.
     */
    fun loadHistory(peerId: String?, limit: Int = HISTORY_LIMIT): List<ChatMessage> {
        if (peerId.isNullOrBlank()) return emptyList()
        val out = mutableListOf<ChatMessage>()
        readableDatabase.rawQuery(
            "SELECT text, is_sent, timestamp FROM $TABLE " +
                "WHERE peer_id = ? ORDER BY timestamp DESC, _id DESC LIMIT ?",
            arrayOf(peerId, limit.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                out += ChatMessage(
                    text = cursor.getString(0),
                    isSentByMe = cursor.getInt(1) == 1,
                    timestamp = cursor.getLong(2)
                )
            }
        }
        out.reverse() // newest-last for the chat view
        return out
    }

    /**
     * All conversations for the chats list, newest activity first.
     * Derived from the messages table — no separate contacts table needed.
     */
    fun getConversations(): List<Conversation> {
        val out = mutableListOf<Conversation>()
        readableDatabase.rawQuery(
            """
            SELECT m.peer_id,
                (SELECT m2.peer_name FROM $TABLE m2
                  WHERE m2.peer_id = m.peer_id AND m2.peer_name IS NOT NULL
                  ORDER BY m2.timestamp DESC, m2._id DESC LIMIT 1),
                (SELECT m2.text FROM $TABLE m2
                  WHERE m2.peer_id = m.peer_id
                  ORDER BY m2.timestamp DESC, m2._id DESC LIMIT 1),
                (SELECT m2.is_sent FROM $TABLE m2
                  WHERE m2.peer_id = m.peer_id
                  ORDER BY m2.timestamp DESC, m2._id DESC LIMIT 1),
                MAX(m.timestamp),
                COUNT(*)
            FROM $TABLE m
            WHERE m.peer_id IS NOT NULL AND m.peer_id != ''
            GROUP BY m.peer_id
            ORDER BY MAX(m.timestamp) DESC
            """.trimIndent(),
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                out += Conversation(
                    peerId = cursor.getString(0),
                    peerName = cursor.getString(1),
                    lastText = cursor.getString(2) ?: "",
                    lastIsSentByMe = cursor.getInt(3) == 1,
                    lastTimestamp = cursor.getLong(4),
                    messageCount = cursor.getInt(5)
                )
            }
        }
        return out
    }

    /** Latest known display name for a peer (null if never chatted). */
    fun peerNameFor(peerId: String?): String? {
        if (peerId.isNullOrBlank()) return null
        return readableDatabase.rawQuery(
            "SELECT peer_name FROM $TABLE WHERE peer_id = ? AND peer_name IS NOT NULL " +
                "ORDER BY timestamp DESC, _id DESC LIMIT 1",
            arrayOf(peerId)
        ).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }

    /**
     * Total stored messages. Pass a [peerId] to count one conversation,
     * or null to count everything (for the storage indicator pill).
     */
    fun count(peerId: String? = null): Int =
        readableDatabase.rawQuery(
            if (peerId == null) "SELECT COUNT(*) FROM $TABLE"
            else "SELECT COUNT(*) FROM $TABLE WHERE peer_id = ?",
            if (peerId == null) null else arrayOf(peerId)
        ).use { c ->
            c.moveToFirst()
            c.getInt(0)
        }
}
