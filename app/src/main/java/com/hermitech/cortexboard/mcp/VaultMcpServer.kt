package com.hermitech.cortexboard.mcp

import com.hermitech.cortexboard.BuildConfig
import com.hermitech.cortexboard.data.Card
import com.hermitech.cortexboard.data.Note
import com.hermitech.cortexboard.data.Store
import com.hermitech.cortexboard.data.TaskItem
import com.hermitech.cortexboard.data.WikiLinks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * The vault exposed as an MCP server. Used in-process by the on-device agent,
 * and over HTTP by [McpBridgeServer] for external agents.
 */
class VaultMcpServer(private val store: Store) : McpToolProvider {
    override val name = "vault"

    private class Handler(val tool: McpTool, val run: (JSONObject) -> String)

    private val handlers: Map<String, Handler> = listOf(
        tool("search_notes", "Full-text search the vault. Returns titles, folders, tags and a snippet.",
            schema { str("query", "keywords", true); int("limit", "max results, default 8") }) { a ->
            val lim = a.optInt("limit", 8).coerceIn(1, 25)
            val hits = store.searchSync(a.getString("query"), lim).ifEmpty { store.searchAnySync(a.getString("query"), lim) }
            if (hits.isEmpty()) "no matches" else hits.joinToString("\n") { n ->
                "- ${n.title} [${n.folder}] ${n.tags.joinToString(" ") { "#$it" }} :: ${snippet(n)}"
            }
        },
        tool("read_note", "Read a note's full markdown by title.", schema { str("title", "exact note title", true) }) { a ->
            val n = store.noteByTitleSync(a.getString("title")) ?: return@tool "ERROR: note not found"
            "# ${n.title}\nfolder: ${n.folder}\ntags: ${n.tags.joinToString(", ")}\n" +
                (if (n.summary.isNotBlank()) "summary: ${n.summary}\n" else "") + "\n" + n.body
        },
        tool("create_note", "Create a markdown note. Use [[Title]] to link other notes.",
            schema {
                str("title", "note title", true); str("body", "markdown body")
                str("folder", "folder, e.g. Projects, Ideas, Reference"); strList("tags", "tags without #")
            }) { a ->
            val id = store.upsertNoteSync(Note(
                title = a.getString("title"), body = a.optString("body"),
                folder = a.optString("folder").ifBlank { "Inbox" }, tags = a.strList("tags"),
            ), markUnorganized = false)
            "created note '${store.noteSync(id)?.title}'"
        },
        tool("append_to_note", "Append markdown to a note (creates it if missing).",
            schema { str("title", "note title", true); str("text", "markdown to append", true) }) { a ->
            val title = a.getString("title")
            val existing = store.noteByTitleSync(title)
            val n = existing?.copy(body = existing.body.trimEnd() + "\n\n" + a.getString("text"))
                ?: Note(title = title, body = a.getString("text"))
            store.upsertNoteSync(n, markUnorganized = false)
            if (existing == null) "created '$title'" else "appended to '$title'"
        },
        tool("update_note", "Rename, refile, retag or summarize a note.",
            schema {
                str("title", "current title", true); str("new_title", "rename to"); str("folder", "move to folder")
                strList("tags", "replace tags"); str("summary", "one-line summary")
            }) { a ->
            val n = store.noteByTitleSync(a.getString("title")) ?: return@tool "ERROR: note not found"
            store.upsertNoteSync(n.copy(
                title = a.optString("new_title").ifBlank { n.title },
                folder = a.optString("folder").ifBlank { n.folder },
                tags = if (a.has("tags")) a.strList("tags") else n.tags,
                summary = a.optString("summary").ifBlank { n.summary },
            ), markUnorganized = false)
            "updated '${n.title}'"
        },
        tool("list_notes", "List notes, optionally filtered by folder or tag.",
            schema { str("folder", "folder filter"); str("tag", "tag filter"); int("limit", "default 30") }) { a ->
            val list = store.notesSync(a.optString("folder").ifBlank { null }, a.optString("tag").ifBlank { null }, a.optInt("limit", 30))
            if (list.isEmpty()) "no notes" else list.joinToString("\n") { "- ${it.title} [${it.folder}]" + if (it.summary.isNotBlank()) " — ${it.summary}" else "" }
        },
        tool("vault_overview", "Folders, top tags and counts. Call first when you need to know how the vault is organized.", schema { }) { _ ->
            val tasks = store.tasksSync(includeDone = false)
            "folders: ${store.foldersSync().joinToString(", ")}\n" +
                "tags: ${store.tagsSync().take(30).joinToString(", ")}\n" +
                "open tasks: ${tasks.size} in lists ${tasks.map { it.list }.distinct().joinToString(", ")}\n" +
                "boards: ${store.boardsSync().joinToString(", ") { it.name }}"
        },
        tool("get_backlinks", "Notes that link to the given note via [[wikilink]].", schema { str("title", "note title", true) }) { a ->
            val bl = store.backlinksSync(a.getString("title"))
            if (bl.isEmpty()) "no backlinks" else bl.joinToString("\n") { "- ${it.title}" }
        },
        tool("create_task", "Add a task to a list.",
            schema {
                str("title", "task", true); str("list", "list name, default Inbox")
                int("priority", "0 none, 1 low, 2 med, 3 high"); str("due", "YYYY-MM-DD, 'today' or 'tomorrow'")
                str("note", "title of a related note")
            }) { a ->
            val noteId = a.optString("note").takeIf { it.isNotBlank() }?.let { store.noteByTitleSync(it)?.id }
            val id = store.addTaskSync(TaskItem(
                title = a.getString("title"), list = a.optString("list").ifBlank { "Inbox" },
                priority = a.optInt("priority", 0), due = parseDue(a.optString("due")), noteId = noteId,
            ))
            "task #$id created"
        },
        tool("list_tasks", "List tasks with their ids.",
            schema { str("list", "list filter"); bool("include_done", "include completed, default false") }) { a ->
            val ts = store.tasksSync(a.optString("list").ifBlank { null }, a.optBoolean("include_done", false))
            if (ts.isEmpty()) "no tasks" else ts.joinToString("\n") { t ->
                val due = t.due?.let { " due ${LocalDate.ofEpochDay(it)}" }.orEmpty()
                "#${t.id} [${if (t.done) "x" else " "}] ${t.title} (${t.list}, p${t.priority}$due)"
            }
        },
        tool("complete_task", "Mark a task done by id or exact title.",
            schema { int("id", "task id"); str("title", "task title if id unknown") }) { a ->
            val id = if (a.has("id")) a.getLong("id") else store.tasksSync(includeDone = false)
                .firstOrNull { it.title.equals(a.optString("title"), true) }?.id ?: return@tool "ERROR: task not found"
            if (store.setTaskDoneSync(id, true)) "task #$id done" else "ERROR: task not found"
        },
        tool("create_card", "Add a kanban card to a board (board is created if new).",
            schema {
                str("title", "card title", true); str("board", "board name, default first board")
                enumStr("column", "column", Card.COLUMNS); str("body", "details")
            }) { a ->
            val b = store.boardByNameSync(a.optString("board").ifBlank { null })
            val id = store.addCardSync(Card(boardId = b.id, title = a.getString("title"),
                column = a.optString("column").ifBlank { "TODO" }, body = a.optString("body")))
            "card #$id on ${b.name}"
        },
        tool("move_card", "Move a kanban card to another column.",
            schema { int("id", "card id", true); enumStr("column", "target column", Card.COLUMNS, true) }) { a ->
            if (store.moveCardSync(a.getLong("id"), a.getString("column"))) "moved" else "ERROR: bad card id or column"
        },
        tool("list_board", "Show a kanban board's cards with ids.", schema { str("board", "board name") }) { a ->
            val b = store.boardByNameSync(a.optString("board").ifBlank { null }, create = false)
            val cards = store.cardsSync(b.id)
            "board ${b.name}\n" + Card.COLUMNS.joinToString("\n") { col ->
                "$col: " + cards.filter { it.column == col }.joinToString("; ") { "#${it.id} ${it.title}" }.ifBlank { "-" }
            }
        },
        tool("daily_log", "Append a timestamped line to today's daily note.", schema { str("text", "entry", true) }) { a ->
            val d = store.dailyNoteSync()
            val ts = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
            store.upsertNoteSync(d.copy(body = d.body.trimEnd() + "\n- `$ts` " + a.getString("text")), markUnorganized = false)
            "logged to ${d.title}"
        },
    ).associateBy { it.tool.name }

    private fun tool(name: String, desc: String, schema: JSONObject, run: (JSONObject) -> String) =
        Handler(McpTool(name, desc, schema, "local"), run)

    override suspend fun listTools(): List<McpTool> = handlers.values.map { it.tool }

    override suspend fun callTool(name: String, args: JSONObject): McpResult = withContext(Dispatchers.IO) {
        val h = handlers[name] ?: return@withContext McpResult("unknown tool: $name", true)
        try {
            val out = h.run(args)
            McpResult(out, out.startsWith("ERROR"))
        } catch (e: Exception) {
            McpResult("ERROR: ${e.message ?: e.javaClass.simpleName}", true)
        }
    }

    /** JSON-RPC entry point for the HTTP bridge. Returns null for notifications. */
    suspend fun handleRpc(req: JSONObject): JSONObject? {
        val id = if (req.has("id")) req.get("id") else null
        val method = req.optString("method")
        val params = req.optJSONObject("params") ?: JSONObject()
        if (id == null) return null // notification (e.g. notifications/initialized)
        return when (method) {
            "initialize" -> JsonRpc.result(id, JSONObject()
                .put("protocolVersion", params.optString("protocolVersion").ifBlank { MCP_PROTOCOL_VERSION })
                .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
                .put("serverInfo", JSONObject().put("name", "cortexboard-vault").put("version", BuildConfig.VERSION_NAME))
                .put("instructions", "CortexBoard vault: markdown notes with [[wikilinks]], tasks and kanban boards."))
            "ping" -> JsonRpc.result(id, JSONObject())
            "tools/list" -> JsonRpc.result(id, JSONObject().put("tools", JSONArray(listTools().map { it.toJson() })))
            "tools/call" -> JsonRpc.result(id, callTool(params.optString("name"), params.optJSONObject("arguments") ?: JSONObject()).toJson())
            else -> JsonRpc.error(id, -32601, "method not found: $method")
        }
    }

    private fun snippet(n: Note): String =
        (n.summary.ifBlank { n.body }).replace(Regex("\\s+"), " ").take(140)

    private fun JSONObject.strList(key: String): List<String> {
        val v = opt(key) ?: return emptyList()
        return when (v) {
            is JSONArray -> (0 until v.length()).map { v.optString(it) }
            is String -> v.split(',')
            else -> emptyList()
        }.map(WikiLinks::normalizeTag).filter { it.isNotBlank() }
    }

    private fun parseDue(s: String): Long? = when (s.trim().lowercase()) {
        "" -> null
        "today" -> LocalDate.now().toEpochDay()
        "tomorrow" -> LocalDate.now().plusDays(1).toEpochDay()
        else -> runCatching { LocalDate.parse(s.trim()).toEpochDay() }.getOrNull()
    }
}
