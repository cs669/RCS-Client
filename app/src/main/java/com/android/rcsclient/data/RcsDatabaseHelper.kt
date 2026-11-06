package com.android.rcsclient.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.android.rcsclient.model.RcsConversation
import com.android.rcsclient.model.RcsMessage
import com.google.gson.Gson

/**
 * Local SQLite persistence for RCS/SMS/MMS conversations and messages.
 */
class RcsDatabaseHelper(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    private val gson = Gson()

    companion object {
        private const val DATABASE_NAME = "pulse_rcs_client.db"
        private const val DATABASE_VERSION = 2
        private const val TABLE_CONVERSATIONS = "conversations"
        private const val TABLE_MESSAGES = "messages"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_CONVERSATIONS (
                id TEXT PRIMARY KEY,
                updated_at INTEGER NOT NULL,
                json_payload TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE $TABLE_MESSAGES (
                id TEXT PRIMARY KEY,
                conversation_id TEXT NOT NULL,
                timestamp_ms INTEGER NOT NULL,
                json_payload TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_messages_conv ON $TABLE_MESSAGES(conversation_id, timestamp_ms)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_MESSAGES")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_CONVERSATIONS")
        onCreate(db)
    }

    fun loadAllConversations(): List<RcsConversation> {
        val result = mutableListOf<RcsConversation>()
        val db = readableDatabase
        db.query(
            TABLE_CONVERSATIONS,
            arrayOf("json_payload"),
            null,
            null,
            null,
            null,
            "updated_at DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val json = cursor.getString(0)
                runCatching {
                    gson.fromJson(json, RcsConversation::class.java)
                }.getOrNull()?.let { result.add(it) }
            }
        }
        return result
    }

    fun loadAllMessagesByConversation(): Map<String, List<RcsMessage>> {
        val result = mutableMapOf<String, MutableList<RcsMessage>>()
        val db = readableDatabase
        db.query(
            TABLE_MESSAGES,
            arrayOf("conversation_id", "json_payload"),
            null,
            null,
            null,
            null,
            "timestamp_ms ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val convId = cursor.getString(0)
                val json = cursor.getString(1)
                runCatching {
                    gson.fromJson(json, RcsMessage::class.java)
                }.getOrNull()?.let { msg ->
                    result.getOrPut(convId) { mutableListOf() }.add(msg)
                }
            }
        }
        return result
    }

    fun saveConversation(conversation: RcsConversation) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("id", conversation.id)
            put("updated_at", conversation.lastMessageTimestampMs)
            put("json_payload", gson.toJson(conversation))
        }
        db.insertWithOnConflict(TABLE_CONVERSATIONS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun saveMessage(message: RcsMessage) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("id", message.id)
            put("conversation_id", message.conversationId)
            put("timestamp_ms", message.timestampMs)
            put("json_payload", gson.toJson(message))
        }
        db.insertWithOnConflict(TABLE_MESSAGES, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deleteConversation(conversationId: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(TABLE_MESSAGES, "conversation_id = ?", arrayOf(conversationId))
            db.delete(TABLE_CONVERSATIONS, "id = ?", arrayOf(conversationId))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun saveAll(conversations: List<RcsConversation>, messagesMap: Map<String, List<RcsMessage>>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            conversations.forEach { conv ->
                val values = ContentValues().apply {
                    put("id", conv.id)
                    put("updated_at", conv.lastMessageTimestampMs)
                    put("json_payload", gson.toJson(conv))
                }
                db.insertWithOnConflict(TABLE_CONVERSATIONS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            messagesMap.values.flatten().forEach { msg ->
                val values = ContentValues().apply {
                    put("id", msg.id)
                    put("conversation_id", msg.conversationId)
                    put("timestamp_ms", msg.timestampMs)
                    put("json_payload", gson.toJson(msg))
                }
                db.insertWithOnConflict(TABLE_MESSAGES, null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
