package `in`.iambhvsh.theia.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Message(
    val id: Long = 0,
    val sessionId: String,
    val role: String, // "system", "user", "assistant", "tool"
    val content: String?,
    val toolCallsJson: String? = null,
    val toolCallId: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

class History(private val helper: DatabaseHelper) {
    fun insert(message: Message): Long {
        val db = helper.writableDatabase
        val values = ContentValues().apply {
            put("sessionId", message.sessionId)
            put("role", message.role)
            put("content", message.content)
            put("toolCallsJson", message.toolCallsJson)
            put("toolCallId", message.toolCallId)
            put("timestamp", message.timestamp)
        }
        return db.insertWithOnConflict("messages", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getSession(sessionId: String, limit: Int = 10): List<Message> {
        val db = helper.readableDatabase
        val sql = "SELECT * FROM (SELECT * FROM messages WHERE sessionId = ? ORDER BY timestamp DESC, id DESC LIMIT ?) ORDER BY timestamp ASC, id ASC"
        val cursor = db.rawQuery(sql, arrayOf(sessionId, limit.toString()))
        val list = ArrayList<Message>()
        cursor.use { c ->
            val idCol = findCol(c, "id")
            val sessCol = findCol(c, "sessionId", "session_id")
            val roleCol = findCol(c, "role")
            val contentCol = findCol(c, "content")
            val tcCol = findCol(c, "toolCallsJson", "tool_calls_json")
            val tIdCol = findCol(c, "toolCallId", "tool_call_id")
            val timeCol = findCol(c, "timestamp")

            while (c.moveToNext()) {
                list.add(
                    Message(
                        id = if (idCol >= 0) c.getLong(idCol) else 0L,
                        sessionId = if (sessCol >= 0) c.getString(sessCol) else "",
                        role = if (roleCol >= 0) c.getString(roleCol) else "user",
                        content = if (contentCol >= 0 && !c.isNull(contentCol)) c.getString(contentCol) else null,
                        toolCallsJson = if (tcCol >= 0 && !c.isNull(tcCol)) c.getString(tcCol) else null,
                        toolCallId = if (tIdCol >= 0 && !c.isNull(tIdCol)) c.getString(tIdCol) else null,
                        timestamp = if (timeCol >= 0) c.getLong(timeCol) else System.currentTimeMillis()
                    )
                )
            }
        }
        return list
    }

    fun getRecent(limit: Int): List<Message> {
        val db = helper.readableDatabase
        val sql = "SELECT * FROM (SELECT * FROM messages ORDER BY timestamp DESC, id DESC LIMIT ?) ORDER BY timestamp ASC, id ASC"
        val cursor = db.rawQuery(sql, arrayOf(limit.toString()))
        val list = ArrayList<Message>()
        cursor.use { c ->
            val idCol = findCol(c, "id")
            val sessCol = findCol(c, "sessionId", "session_id")
            val roleCol = findCol(c, "role")
            val contentCol = findCol(c, "content")
            val tcCol = findCol(c, "toolCallsJson", "tool_calls_json")
            val tIdCol = findCol(c, "toolCallId", "tool_call_id")
            val timeCol = findCol(c, "timestamp")

            while (c.moveToNext()) {
                list.add(
                    Message(
                        id = if (idCol >= 0) c.getLong(idCol) else 0L,
                        sessionId = if (sessCol >= 0) c.getString(sessCol) else "",
                        role = if (roleCol >= 0) c.getString(roleCol) else "user",
                        content = if (contentCol >= 0 && !c.isNull(contentCol)) c.getString(contentCol) else null,
                        toolCallsJson = if (tcCol >= 0 && !c.isNull(tcCol)) c.getString(tcCol) else null,
                        toolCallId = if (tIdCol >= 0 && !c.isNull(tIdCol)) c.getString(tIdCol) else null,
                        timestamp = if (timeCol >= 0) c.getLong(timeCol) else System.currentTimeMillis()
                    )
                )
            }
        }
        return list
    }

    private fun findCol(c: Cursor, vararg names: String): Int {
        for (name in names) {
            val idx = c.getColumnIndex(name)
            if (idx >= 0) return idx
        }
        return -1
    }

    fun clearSession(sessionId: String) {
        val db = helper.writableDatabase
        db.delete("messages", "sessionId = ?", arrayOf(sessionId))
    }

    fun clearOldSessions(currentSessionId: String) {
        val db = helper.writableDatabase
        db.delete("messages", "sessionId != ?", arrayOf(currentSessionId))
    }

    fun clear() {
        val db = helper.writableDatabase
        db.delete("messages", null, null)
    }

    fun clearAll() {
        clear()
    }
}

class DatabaseHelper(ctx: Context) : SQLiteOpenHelper(ctx, "theia.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        createTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS messages")
        db.execSQL("DROP TABLE IF EXISTS chat_messages")
        createTables(db)
    }

    private fun createTables(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                sessionId TEXT NOT NULL,
                role TEXT NOT NULL,
                content TEXT,
                toolCallsJson TEXT,
                toolCallId TEXT,
                timestamp INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_timestamp ON messages(timestamp)")
    }
}

class Database private constructor(ctx: Context) {
    private val helper = DatabaseHelper(ctx.applicationContext)
    private val historyInstance = History(helper)

    fun history(): History = historyInstance

    companion object {
        @Volatile
        private var instance: Database? = null

        fun get(ctx: Context): Database =
            instance ?: synchronized(this) {
                instance ?: Database(ctx).also { instance = it }
            }
    }
}
