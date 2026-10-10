package com.example.llama

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID

data class ChatRecord(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long
)

data class ChatMessageRecord(
    val id: String,
    val chatId: String,
    val role: String,
    val content: String,
    val createdAt: Long,
    val position: Long
) {
    val isUser: Boolean get() = role == ROLE_USER

    companion object {
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
        const val ROLE_SYSTEM = "system"
    }
}

data class ImportedMessage(val role: String, val content: String)

/**
 * Small private SQLite database for persistent LocalMind chats and turns.
 * All methods are synchronized because the same helper is used by UI and generation work.
 */
class ChatDatabase(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE chats (" +
                "id TEXT PRIMARY KEY NOT NULL, " +
                "title TEXT NOT NULL, " +
                "created_at INTEGER NOT NULL, " +
                "updated_at INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE messages (" +
                "id TEXT PRIMARY KEY NOT NULL, " +
                "chat_id TEXT NOT NULL, " +
                "role TEXT NOT NULL, " +
                "content TEXT NOT NULL, " +
                "created_at INTEGER NOT NULL, " +
                "position INTEGER NOT NULL, " +
                "FOREIGN KEY(chat_id) REFERENCES chats(id) ON DELETE CASCADE)"
        )
        db.execSQL("CREATE INDEX index_messages_chat_position ON messages(chat_id, position)")
        db.execSQL("CREATE INDEX index_chats_updated_at ON chats(updated_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Schema changes in future versions must be migrated here.
    }

    @Synchronized
    fun listChats(query: String = ""): List<ChatRecord> {
        val db = readableDatabase
        val normalized = query.trim()
        val cursor = if (normalized.isEmpty()) {
            db.query("chats", null, null, null, null, null, "updated_at DESC, created_at DESC")
        } else {
            val pattern = "%$normalized%"
            db.rawQuery(
                "SELECT c.id, c.title, c.created_at, c.updated_at FROM chats c " +
                    "WHERE c.title LIKE ? OR EXISTS (" +
                    "SELECT 1 FROM messages m WHERE m.chat_id = c.id AND m.content LIKE ?) " +
                    "ORDER BY c.updated_at DESC, c.created_at DESC",
                arrayOf(pattern, pattern)
            )
        }
        return cursor.use { c -> buildList(c) { getChatRecord(c) } }
    }

    @Synchronized
    fun getChat(chatId: String): ChatRecord? {
        val cursor = readableDatabase.query(
            "chats", null, "id = ?", arrayOf(chatId), null, null, null, "1"
        )
        return cursor.use { c -> if (c.moveToFirst()) getChatRecord(c) else null }
    }

    @Synchronized
    fun createChat(title: String = "New chat"): ChatRecord {
        val now = System.currentTimeMillis()
        val chat = ChatRecord(UUID.randomUUID().toString(), title, now, now)
        val values = ContentValues().apply {
            put("id", chat.id)
            put("title", chat.title)
            put("created_at", chat.createdAt)
            put("updated_at", chat.updatedAt)
        }
        writableDatabase.insertOrThrow("chats", null, values)
        return chat
    }

    @Synchronized
    fun renameChat(chatId: String, title: String): Boolean {
        val safeTitle = title.trim().ifBlank { "New chat" }.take(80)
        val values = ContentValues().apply {
            put("title", safeTitle)
            put("updated_at", System.currentTimeMillis())
        }
        return writableDatabase.update("chats", values, "id = ?", arrayOf(chatId)) > 0
    }

    @Synchronized
    fun maybeAutoTitle(chatId: String, firstUserMessage: String) {
        val current = getChat(chatId) ?: return
        if (current.title != "New chat") return
        val existingUser = readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM messages WHERE chat_id = ? AND role = ?",
            arrayOf(chatId, ChatMessageRecord.ROLE_USER)
        ).use { c -> c.moveToFirst() && c.getInt(0) > 0 }
        if (existingUser) return
        val generated = firstUserMessage.replace(Regex("\\s+"), " ").trim().take(36)
            .ifBlank { "New chat" }
        renameChat(chatId, generated)
    }

    @Synchronized
    fun insertMessage(
        chatId: String,
        role: String,
        content: String,
        messageId: String = UUID.randomUUID().toString()
    ): ChatMessageRecord {
        require(role == ChatMessageRecord.ROLE_USER ||
            role == ChatMessageRecord.ROLE_ASSISTANT ||
            role == ChatMessageRecord.ROLE_SYSTEM) { "Unsupported message role: $role" }
        if (role == ChatMessageRecord.ROLE_USER) maybeAutoTitle(chatId, content)

        val db = writableDatabase
        val position = db.rawQuery(
            "SELECT COALESCE(MAX(position), -1) + 1 FROM messages WHERE chat_id = ?",
            arrayOf(chatId)
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
        val now = System.currentTimeMillis()
        val record = ChatMessageRecord(messageId, chatId, role, content, now, position)
        val values = ContentValues().apply {
            put("id", record.id)
            put("chat_id", record.chatId)
            put("role", record.role)
            put("content", record.content)
            put("created_at", record.createdAt)
            put("position", record.position)
        }
        db.insertOrThrow("messages", null, values)
        touchChat(chatId)
        return record
    }

    @Synchronized
    fun getMessages(chatId: String): List<ChatMessageRecord> {
        val cursor = readableDatabase.query(
            "messages", null, "chat_id = ?", arrayOf(chatId), null, null, "position ASC"
        )
        return cursor.use { c -> buildList(c) { getMessageRecord(c) } }
    }

    @Synchronized
    fun updateMessage(messageId: String, content: String): Boolean {
        val values = ContentValues().apply { put("content", content) }
        val db = writableDatabase
        val chatId = db.rawQuery(
            "SELECT chat_id FROM messages WHERE id = ?", arrayOf(messageId)
        ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
        val updated = db.update("messages", values, "id = ?", arrayOf(messageId)) > 0
        if (updated && chatId != null) touchChat(chatId)
        return updated
    }

    @Synchronized
    fun deleteMessage(messageId: String): Boolean {
        val db = writableDatabase
        val chatId = db.rawQuery(
            "SELECT chat_id FROM messages WHERE id = ?", arrayOf(messageId)
        ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
        val deleted = db.delete("messages", "id = ?", arrayOf(messageId)) > 0
        if (deleted && chatId != null) touchChat(chatId)
        return deleted
    }

    @Synchronized
    fun deleteMessagesFrom(chatId: String, position: Long) {
        writableDatabase.delete(
            "messages", "chat_id = ? AND position >= ?", arrayOf(chatId, position.toString())
        )
        touchChat(chatId)
    }

    @Synchronized
    fun deleteChat(chatId: String): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("messages", "chat_id = ?", arrayOf(chatId))
            val deleted = db.delete("chats", "id = ?", arrayOf(chatId)) > 0
            db.setTransactionSuccessful()
            return deleted
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Import one chat as a new independent conversation. IDs are regenerated to
     * avoid collisions with chats already on the device. Chat and messages commit
     * atomically so an interrupted restore cannot leave a half-imported chat.
     */
    @Synchronized
    fun importChat(title: String, importedMessages: List<ImportedMessage>): ChatRecord {
        require(importedMessages.size <= MAX_IMPORTED_MESSAGES) { "Too many messages in one chat." }
        importedMessages.forEach { message ->
            require(message.role == ChatMessageRecord.ROLE_USER ||
                message.role == ChatMessageRecord.ROLE_ASSISTANT ||
                message.role == ChatMessageRecord.ROLE_SYSTEM) { "Invalid message role in backup." }
        }

        val now = System.currentTimeMillis()
        val safeTitle = title.trim().ifBlank { "Imported chat" }.take(80)
        val chat = ChatRecord(UUID.randomUUID().toString(), safeTitle, now, now)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val chatValues = ContentValues().apply {
                put("id", chat.id)
                put("title", chat.title)
                put("created_at", chat.createdAt)
                put("updated_at", chat.updatedAt)
            }
            db.insertOrThrow("chats", null, chatValues)
            importedMessages.forEachIndexed { index, message ->
                val values = ContentValues().apply {
                    put("id", UUID.randomUUID().toString())
                    put("chat_id", chat.id)
                    put("role", message.role)
                    put("content", message.content)
                    put("created_at", now + index)
                    put("position", index.toLong())
                }
                db.insertOrThrow("messages", null, values)
            }
            db.setTransactionSuccessful()
            return chat
        } finally {
            db.endTransaction()
        }
    }

    private fun touchChat(chatId: String) {
        val values = ContentValues().apply { put("updated_at", System.currentTimeMillis()) }
        writableDatabase.update("chats", values, "id = ?", arrayOf(chatId))
    }

    private fun getChatRecord(c: Cursor) = ChatRecord(
        id = c.getString(c.getColumnIndexOrThrow("id")),
        title = c.getString(c.getColumnIndexOrThrow("title")),
        createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
        updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at"))
    )

    private fun getMessageRecord(c: Cursor) = ChatMessageRecord(
        id = c.getString(c.getColumnIndexOrThrow("id")),
        chatId = c.getString(c.getColumnIndexOrThrow("chat_id")),
        role = c.getString(c.getColumnIndexOrThrow("role")),
        content = c.getString(c.getColumnIndexOrThrow("content")),
        createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
        position = c.getLong(c.getColumnIndexOrThrow("position"))
    )

    private inline fun <T> buildList(cursor: Cursor, transform: (Cursor) -> T): List<T> {
        val result = ArrayList<T>(cursor.count)
        while (cursor.moveToNext()) result.add(transform(cursor))
        return result
    }

    companion object {
        private const val DATABASE_NAME = "localmind_chats.db"
        private const val DATABASE_VERSION = 1
        private const val MAX_IMPORTED_MESSAGES = 10_000
    }
}
