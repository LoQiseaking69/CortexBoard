package com.hermitech.cortexboard.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.LocalDate

private class CortexDb(ctx: Context) : SQLiteOpenHelper(ctx, "cortex.db", null, 2) {
    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE notes(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL, body TEXT NOT NULL DEFAULT '',
                tags TEXT NOT NULL DEFAULT ',', folder TEXT NOT NULL DEFAULT 'Inbox',
                summary TEXT NOT NULL DEFAULT '', pinned INTEGER NOT NULL DEFAULT 0,
                created INTEGER NOT NULL, updated INTEGER NOT NULL,
                organized INTEGER NOT NULL DEFAULT 0)"""
        )
        db.execSQL("CREATE UNIQUE INDEX notes_title ON notes(title COLLATE NOCASE)")
        try {
            db.execSQL("CREATE VIRTUAL TABLE notes_fts USING fts4(title, body, tags, tokenize=unicode61)")
        } catch (_: android.database.SQLException) {
            db.execSQL("CREATE VIRTUAL TABLE notes_fts USING fts4(title, body, tags)") // older SQLite builds
        }
        db.execSQL(
            """CREATE TABLE tasks(
                id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL,
                done INTEGER NOT NULL DEFAULT 0, list TEXT NOT NULL DEFAULT 'Inbox',
                priority INTEGER NOT NULL DEFAULT 0, due INTEGER,
                note_id INTEGER REFERENCES notes(id) ON DELETE SET NULL,
                created INTEGER NOT NULL)"""
        )
        db.execSQL("CREATE TABLE boards(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE COLLATE NOCASE)")
        db.execSQL(
            """CREATE TABLE cards(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                board_id INTEGER NOT NULL REFERENCES boards(id) ON DELETE CASCADE,
                col TEXT NOT NULL, title TEXT NOT NULL, body TEXT NOT NULL DEFAULT '',
                position INTEGER NOT NULL DEFAULT 0)"""
        )
        db.execSQL(
            """CREATE TABLE mcp_servers(
                id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, url TEXT NOT NULL,
                token TEXT NOT NULL DEFAULT '', enabled INTEGER NOT NULL DEFAULT 1)"""
        )
        createChat(db)
        db.execSQL("INSERT INTO boards(name) VALUES('OPS')")
        val now = System.currentTimeMillis()
        val welcome = ContentValues().apply {
            put("title", "Welcome to CortexBoard")
            put("body", WELCOME)
            put("tags", ",cortex,guide,")
            put("folder", "System")
            put("created", now); put("updated", now); put("organized", 1)
        }
        val id = db.insert("notes", null, welcome)
        db.execSQL(
            "INSERT INTO notes_fts(docid,title,body,tags) VALUES(?,?,?,?)",
            arrayOf<Any>(id, "Welcome to CortexBoard", WELCOME, "cortex guide")
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createChat(db)
    }

    private fun createChat(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS chat(
                id INTEGER PRIMARY KEY AUTOINCREMENT, role TEXT NOT NULL,
                content TEXT NOT NULL, meta TEXT NOT NULL DEFAULT '', created INTEGER NOT NULL)"""
        )
    }

    companion object {
        val WELCOME = """
            # CORTEX ONLINE

            Your vault is local-first. The on-device Qwen model is the brain; MCP is its nervous system.

            - Link thoughts with [[wikilinks]] — backlinks appear under each note.
            - Tag inline with #tags.
            - Drop raw thoughts into **⚡ capture**: the organizer files them, tags them, and pulls out tasks.
            - Ask **BRAIN** to do work: "plan my week from my open tasks", "make a board for the launch".
            - Turn on the MCP bridge in **SYS** to let external agents drive this vault.
        """.trimIndent()
    }
}

/** Single source of truth. All writes bump [version], which re-runs every observing query. */
class Store(private val context: Context) {
    private val helper = CortexDb(context.applicationContext)
    private val db: SQLiteDatabase get() = helper.writableDatabase
    private val version = MutableStateFlow(0L)

    private fun bump() { version.value = version.value + 1 }

    fun <T> observe(block: () -> T): Flow<T> = version.map { block() }
        .catch { e -> android.util.Log.e("Store", "query failed", e); com.hermitech.cortexboard.CrashGuard.record(context, "store", e) }
        .flowOn(Dispatchers.IO)

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    // ---------------------------------------------------------------- notes
    private fun Cursor.toNote() = Note(
        id = getLong(0), title = getString(1), body = getString(2),
        tags = getString(3).split(',').filter { it.isNotBlank() },
        folder = getString(4), summary = getString(5), pinned = getInt(6) == 1,
        created = getLong(7), updated = getLong(8), organized = getInt(9) == 1,
    )

    private val noteCols = "id,title,body,tags,folder,summary,pinned,created,updated,organized"

    private fun notesWhere(where: String, args: Array<String>, order: String = "pinned DESC, updated DESC", limit: Int = 500): List<Note> =
        db.rawQuery("SELECT $noteCols FROM notes WHERE $where ORDER BY $order LIMIT $limit", args).use { c ->
            buildList { while (c.moveToNext()) add(c.toNote()) }
        }

    fun notesSync(folder: String? = null, tag: String? = null, limit: Int = 500): List<Note> {
        val clauses = mutableListOf("1=1")
        val args = mutableListOf<String>()
        if (!folder.isNullOrBlank()) { clauses += "folder = ? COLLATE NOCASE"; args += folder }
        if (!tag.isNullOrBlank()) { clauses += "tags LIKE ?"; args += "%,${WikiLinks.normalizeTag(tag)},%" }
        return notesWhere(clauses.joinToString(" AND "), args.toTypedArray(), limit = limit)
    }

    fun notes(query: String, folder: String?, tag: String?): Flow<List<Note>> = observe {
        if (query.isBlank()) notesSync(folder, tag) else searchSync(query, 100).filter {
            (folder == null || it.folder.equals(folder, true)) &&
                (tag == null || it.tags.contains(WikiLinks.normalizeTag(tag)))
        }
    }

    fun searchSync(query: String, limit: Int = 20): List<Note> {
        // Only letters/digits survive: FTS4 treats '-', '"', '*', ':' etc. as query operators.
        val terms = query.replace(Regex("[^\\p{L}\\p{N}]+"), " ").split(' ').filter { it.isNotEmpty() }.take(8)
        if (terms.isEmpty()) return emptyList()
        val match = terms.joinToString(" ") { "$it*" }
        val ids = try {
            db.rawQuery(
                "SELECT docid FROM notes_fts WHERE notes_fts MATCH ? LIMIT ?", arrayOf(match, limit.toString())
            ).use { c -> buildList<Long> { while (c.moveToNext()) add(c.getLong(0)) } }
        } catch (_: android.database.sqlite.SQLiteException) {
            return emptyList()
        }
        if (ids.isEmpty()) return emptyList()
        val byId = notesWhere("id IN (${ids.joinToString(",")})", emptyArray()).associateBy { it.id }
        return ids.mapNotNull { byId[it] }
    }

    private val STOP = setOf(
        "the", "a", "an", "and", "or", "of", "to", "in", "on", "for", "with", "is", "are", "was", "be", "it", "this",
        "that", "my", "me", "i", "you", "what", "how", "about", "do", "does", "did", "can", "could", "should", "would",
        "from", "at", "by", "as", "all", "any", "some", "please", "tell", "show", "find", "make", "create", "list",
        "note", "notes", "task", "tasks", "board", "today", "now", "then", "there", "have", "has", "had", "will",
    )

    /**
     * Relevance search for natural-language questions: OR across keywords, then ranked in Kotlin
     * by title hits (x3), tag hits (x2) and body hits. Used for retrieval-augmented prompts.
     */
    fun searchAnySync(query: String, limit: Int = 4): List<Note> {
        val terms = query.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").split(' ')
            .filter { it.length > 2 && it !in STOP }.distinct().take(8)
        if (terms.isEmpty()) return emptyList()
        val match = terms.joinToString(" OR ") { "$it*" }
        val ids = try {
            db.rawQuery("SELECT docid FROM notes_fts WHERE notes_fts MATCH ? LIMIT 40", arrayOf(match))
                .use { c -> buildList<Long> { while (c.moveToNext()) add(c.getLong(0)) } }
        } catch (_: android.database.sqlite.SQLiteException) { return emptyList() }
        if (ids.isEmpty()) return emptyList()
        return notesWhere("id IN (${ids.joinToString(",")})", emptyArray()).map { n ->
            val t = n.title.lowercase(); val b = n.body.lowercase()
            n to terms.sumOf { term ->
                (if (t.contains(term)) 3 else 0) + (if (n.tags.any { it.startsWith(term) }) 2 else 0) +
                    occurrences(b, term, 5)
            }
        }.sortedByDescending { it.second }.take(limit).map { it.first }
    }

    private fun occurrences(hay: String, needle: String, cap: Int): Int {
        var n = 0; var i = hay.indexOf(needle)
        while (i >= 0 && n < cap) { n++; i = hay.indexOf(needle, i + needle.length) }
        return n
    }

    data class VaultStats(val notes: Int, val openTasks: Int, val dueToday: Int, val overdue: Int, val unsorted: Int)

    fun statsSync(): VaultStats {
        fun count(sql: String, vararg a: String) = db.rawQuery(sql, a).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        val today = LocalDate.now().toEpochDay().toString()
        return VaultStats(
            notes = count("SELECT COUNT(*) FROM notes"),
            openTasks = count("SELECT COUNT(*) FROM tasks WHERE done = 0"),
            dueToday = count("SELECT COUNT(*) FROM tasks WHERE done = 0 AND due = ?", today),
            overdue = count("SELECT COUNT(*) FROM tasks WHERE done = 0 AND due < ?", today),
            unsorted = count("SELECT COUNT(*) FROM notes WHERE organized = 0"),
        )
    }

    fun stats(): Flow<VaultStats> = observe { statsSync() }

    data class GraphData(val nodes: List<Pair<Long, String>>, val edges: List<Pair<Long, Long>>, val folders: Map<Long, String>)

    /** Wikilink graph: nodes are notes, edges are resolved [[links]]. */
    fun graph(): Flow<GraphData> = observe {
        val notes = notesSync(limit = 400)
        val byTitle = notes.associateBy { it.title.lowercase() }
        val edges = notes.flatMap { n ->
            WikiLinks.links(n.body).mapNotNull { byTitle[it.lowercase()]?.id }.filter { it != n.id }.map { n.id to it }
        }.distinct()
        GraphData(notes.map { it.id to it.title }, edges, notes.associate { it.id to it.folder })
    }

    // ---------------------------------------------------------------- chat
    data class ChatRow(val id: Long, val role: String, val content: String, val meta: String)

    fun chatSync(limit: Int = 200): List<ChatRow> =
        db.rawQuery("SELECT id, role, content, meta FROM (SELECT * FROM chat ORDER BY id DESC LIMIT $limit) ORDER BY id", null)
            .use { c -> buildList<ChatRow> { while (c.moveToNext()) add(ChatRow(c.getLong(0), c.getString(1), c.getString(2), c.getString(3))) } }

    suspend fun addChat(role: String, content: String, meta: String = "") = io {
        db.insert("chat", null, ContentValues().apply {
            put("role", role); put("content", content); put("meta", meta); put("created", System.currentTimeMillis())
        })
    }

    suspend fun clearChat() = io { db.delete("chat", null, null) }

    fun noteFlow(id: Long): Flow<Note?> = observe { noteSync(id) }
    fun noteSync(id: Long): Note? = notesWhere("id = ?", arrayOf(id.toString()), limit = 1).firstOrNull()
    fun noteByTitleSync(title: String): Note? =
        notesWhere("title = ? COLLATE NOCASE", arrayOf(title.trim()), limit = 1).firstOrNull()

    fun backlinksSync(title: String): List<Note> =
        notesWhere("body LIKE ? AND title <> ? COLLATE NOCASE", arrayOf("%[[${title}%", title), limit = 100)
            .filter { n -> WikiLinks.links(n.body).any { it.equals(title, true) } }

    fun backlinks(title: String): Flow<List<Note>> = observe { backlinksSync(title) }

    fun foldersSync(): List<String> =
        db.rawQuery("SELECT DISTINCT folder FROM notes ORDER BY folder COLLATE NOCASE", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }

    fun tagsSync(): List<String> =
        db.rawQuery("SELECT tags FROM notes", null).use { c ->
            buildList { while (c.moveToNext()) addAll(c.getString(0).split(',').filter { it.isNotBlank() }) }
        }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }

    fun facets(): Flow<Pair<List<String>, List<String>>> = observe { foldersSync() to tagsSync().take(40) }

    fun unorganizedSync(limit: Int): List<Note> = notesWhere("organized = 0", emptyArray(), "updated ASC", limit)

    /** Insert or update; title collisions get a numeric suffix. Returns id. */
    fun upsertNoteSync(note: Note, markUnorganized: Boolean = true): Long {
        val tags = (note.tags + WikiLinks.inlineTags(note.body)).map(WikiLinks::normalizeTag)
            .filter { it.isNotBlank() }.distinct()
        var title = note.title.trim().ifBlank { "Untitled" }
        val clash = noteByTitleSync(title)
        if (clash != null && clash.id != note.id) {
            var i = 2
            while (noteByTitleSync("$title $i") != null) i++
            title = "$title $i"
        }
        val now = System.currentTimeMillis()
        val cv = ContentValues().apply {
            put("title", title); put("body", note.body)
            put("tags", tags.joinToString(",", prefix = ",", postfix = ","))
            put("folder", note.folder.ifBlank { "Inbox" }); put("summary", note.summary)
            put("pinned", if (note.pinned) 1 else 0); put("updated", now)
            put("organized", if (markUnorganized) 0 else if (note.organized) 1 else 0)
        }
        val id: Long
        db.beginTransaction()
        try {
            if (note.id == 0L) {
                cv.put("created", now)
                id = db.insertOrThrow("notes", null, cv)
            } else {
                id = note.id
                if (db.update("notes", cv, "id = ?", arrayOf(id.toString())) == 0) return id // deleted meanwhile
                db.execSQL("DELETE FROM notes_fts WHERE docid = ?", arrayOf<Any>(id))
            }
            db.execSQL(
                "INSERT INTO notes_fts(docid,title,body,tags) VALUES(?,?,?,?)",
                arrayOf<Any>(id, title, note.body, tags.joinToString(" "))
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        bump()
        return id
    }

    fun deleteNoteSync(id: Long) {
        db.delete("notes", "id = ?", arrayOf(id.toString()))
        db.execSQL("DELETE FROM notes_fts WHERE docid = ?", arrayOf<Any>(id))
        bump()
    }

    fun markOrganizedSync(id: Long) {
        db.execSQL("UPDATE notes SET organized = 1 WHERE id = ?", arrayOf<Any>(id)); bump()
    }

    fun dailyNoteSync(date: LocalDate = LocalDate.now()): Note {
        val title = "Daily $date"
        return noteByTitleSync(title) ?: noteSync(
            upsertNoteSync(Note(title = title, body = "# $date\n\n", folder = "Daily", tags = listOf("daily")), false)
        )!!
    }

    suspend fun upsertNote(n: Note, markUnorganized: Boolean = true) = io { upsertNoteSync(n, markUnorganized) }
    suspend fun deleteNote(id: Long) = io { deleteNoteSync(id) }
    suspend fun togglePin(n: Note) = io { upsertNoteSync(n.copy(pinned = !n.pinned), markUnorganized = false) }

    // ---------------------------------------------------------------- tasks
    private fun Cursor.toTask() = TaskItem(
        id = getLong(0), title = getString(1), done = getInt(2) == 1, list = getString(3),
        priority = getInt(4), due = if (isNull(5)) null else getLong(5),
        noteId = if (isNull(6)) null else getLong(6), created = getLong(7),
    )

    fun tasksSync(list: String? = null, includeDone: Boolean = true): List<TaskItem> {
        val where = buildString {
            append("1=1")
            if (list != null) append(" AND list = ? COLLATE NOCASE")
            if (!includeDone) append(" AND done = 0")
        }
        return db.rawQuery(
            "SELECT id,title,done,list,priority,due,note_id,created FROM tasks WHERE $where " +
                "ORDER BY done ASC, priority DESC, COALESCE(due, 99999999) ASC, created DESC LIMIT 500",
            if (list != null) arrayOf(list) else emptyArray()
        ).use { c -> buildList { while (c.moveToNext()) add(c.toTask()) } }
    }

    fun tasks(): Flow<List<TaskItem>> = observe { tasksSync() }

    fun addTaskSync(t: TaskItem): Long {
        val id = db.insertOrThrow("tasks", null, ContentValues().apply {
            put("title", t.title.trim()); put("done", if (t.done) 1 else 0); put("list", t.list.ifBlank { "Inbox" })
            put("priority", t.priority.coerceIn(0, 3)); t.due?.let { put("due", it) }
            t.noteId?.let { put("note_id", it) }; put("created", System.currentTimeMillis())
        })
        bump(); return id
    }

    fun setTaskDoneSync(id: Long, done: Boolean): Boolean {
        val n = db.update("tasks", ContentValues().apply { put("done", if (done) 1 else 0) }, "id = ?", arrayOf(id.toString()))
        bump(); return n > 0
    }

    fun deleteTaskSync(id: Long) { db.delete("tasks", "id = ?", arrayOf(id.toString())); bump() }

    suspend fun addTask(t: TaskItem) = io { addTaskSync(t) }
    suspend fun setTaskDone(id: Long, done: Boolean) = io { setTaskDoneSync(id, done) }
    suspend fun deleteTask(id: Long) = io { deleteTaskSync(id) }
    suspend fun clearDone() = io { db.delete("tasks", "done = 1", null); bump() }

    // ---------------------------------------------------------------- boards
    fun boardsSync(): List<Board> = db.rawQuery("SELECT id,name FROM boards ORDER BY id", null).use { c ->
        buildList { while (c.moveToNext()) add(Board(c.getLong(0), c.getString(1))) }
    }

    fun boards(): Flow<List<Board>> = observe { boardsSync() }

    fun boardByNameSync(name: String?, create: Boolean = true): Board {
        val n = name?.trim().orEmpty()
        if (n.isEmpty()) return boardsSync().first()
        boardsSync().firstOrNull { it.name.equals(n, true) }?.let { return it }
        if (!create) return boardsSync().first()
        val id = db.insertOrThrow("boards", null, ContentValues().apply { put("name", n) })
        bump(); return Board(id, n)
    }

    fun cardsSync(boardId: Long): List<Card> = db.rawQuery(
        "SELECT id,board_id,col,title,body,position FROM cards WHERE board_id = ? ORDER BY position, id",
        arrayOf(boardId.toString())
    ).use { c ->
        buildList { while (c.moveToNext()) add(Card(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getString(4), c.getInt(5))) }
    }

    fun cards(boardId: Long): Flow<List<Card>> = observe { cardsSync(boardId) }

    fun addCardSync(card: Card): Long {
        val col = Card.COLUMNS.firstOrNull { it.equals(card.column, true) } ?: Card.COLUMNS.first()
        val id = db.insertOrThrow("cards", null, ContentValues().apply {
            put("board_id", card.boardId); put("col", col); put("title", card.title.trim())
            put("body", card.body); put("position", System.currentTimeMillis().toInt() and 0x7fffffff)
        })
        bump(); return id
    }

    fun moveCardSync(id: Long, column: String): Boolean {
        val col = Card.COLUMNS.firstOrNull { it.equals(column, true) } ?: return false
        val n = db.update("cards", ContentValues().apply { put("col", col) }, "id = ?", arrayOf(id.toString()))
        bump(); return n > 0
    }

    fun deleteCardSync(id: Long) { db.delete("cards", "id = ?", arrayOf(id.toString())); bump() }

    suspend fun addBoard(name: String) = io { boardByNameSync(name) }
    suspend fun deleteBoard(id: Long) = io {
        if (boardsSync().size > 1) { db.delete("boards", "id = ?", arrayOf(id.toString())); bump() }
    }
    suspend fun addCard(c: Card) = io { addCardSync(c) }
    suspend fun moveCard(id: Long, col: String) = io { moveCardSync(id, col) }
    suspend fun deleteCard(id: Long) = io { deleteCardSync(id) }

    // ---------------------------------------------------------------- mcp servers
    fun mcpServersSync(): List<McpServerConfig> =
        db.rawQuery("SELECT id,name,url,token,enabled FROM mcp_servers ORDER BY id", null).use { c ->
            buildList { while (c.moveToNext()) add(McpServerConfig(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getInt(4) == 1)) }
        }

    fun mcpServers(): Flow<List<McpServerConfig>> = observe { mcpServersSync() }

    suspend fun saveMcpServer(s: McpServerConfig) = io {
        val cv = ContentValues().apply {
            put("name", s.name.trim()); put("url", s.url.trim()); put("token", s.token.trim()); put("enabled", if (s.enabled) 1 else 0)
        }
        if (s.id == 0L) db.insert("mcp_servers", null, cv) else db.update("mcp_servers", cv, "id = ?", arrayOf(s.id.toString()))
        bump()
    }

    suspend fun deleteMcpServer(id: Long) = io { db.delete("mcp_servers", "id = ?", arrayOf(id.toString())); bump() }

    // ---------------------------------------------------------------- export
    /** Whole vault as a map of relative markdown paths → contents (Obsidian-compatible frontmatter). */
    suspend fun exportMarkdown(): Map<String, String> = io {
        notesSync(limit = 100_000).associate { n ->
            val safe = n.title.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val fm = buildString {
                append("---\n")
                if (n.tags.isNotEmpty()) append("tags: [${n.tags.joinToString(", ")}]\n")
                if (n.summary.isNotBlank()) append("summary: \"${n.summary.replace("\"", "'")}\"\n")
                append("---\n\n")
            }
            "${n.folder}/$safe.md" to fm + n.body
        }
    }
}
