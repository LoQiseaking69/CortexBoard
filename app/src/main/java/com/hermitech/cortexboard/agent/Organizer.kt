package com.hermitech.cortexboard.agent

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.hermitech.cortexboard.Cortex
import com.hermitech.cortexboard.data.Note
import com.hermitech.cortexboard.data.Prefs
import com.hermitech.cortexboard.data.Store
import com.hermitech.cortexboard.data.TaskItem
import com.hermitech.cortexboard.data.WikiLinks
import com.hermitech.cortexboard.llm.ChatML
import com.hermitech.cortexboard.llm.GenParams
import com.hermitech.cortexboard.llm.LlamaEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** The "automated organization" loop: files, tags, summarizes and extracts tasks from new notes. */
class Organizer(private val engine: LlamaEngine, private val store: Store, private val prefs: Prefs) {

    data class Plan(
        val folder: String?, val tags: List<String>, val summary: String, val tasks: List<String>, val links: List<String>,
        val title: String? = null,
    )

    /** A note whose title is just its first line (quick capture / share) deserves a real title. */
    private fun needsTitle(n: Note): Boolean {
        val first = n.body.lineSequence().firstOrNull()?.trim()?.trimStart('#', ' ').orEmpty()
        if (!n.folder.equals("Inbox", true)) return false // never rename notes the user has filed
        if (n.title == "Capture" || n.title.startsWith("Untitled")) return true
        // captured text: the title is a raw (often truncated) first sentence
        return first.startsWith(n.title) && (first.length > 60 || n.title.split(' ').size > 6)
    }

    suspend fun organize(note: Note): Plan? {
        val folders = withContext(Dispatchers.IO) { store.foldersSync() }
        val tags = withContext(Dispatchers.IO) { store.tagsSync().take(40) }
        val titles = withContext(Dispatchers.IO) { store.notesSync(limit = 60).map { it.title }.filter { it != note.title } }
        val prompt = ChatML.build(listOf(
            ChatML.Msg("system", """
You organize a personal knowledge vault. Reply with ONE JSON object and nothing else:
{"title": string, "folder": string, "tags": [string], "summary": string, "tasks": [string], "links": [string]}
- title: a short descriptive title, max 6 words
- folder: reuse an existing folder when it fits, else a short new one (Projects, Ideas, Reference, Journal, People, Ops...)
- tags: 1-5 lowercase tags, reuse existing ones when possible
- summary: one line, max 20 words
- tasks: concrete action items explicitly implied by the note (may be empty)
- links: titles from EXISTING NOTES that are clearly related (may be empty)
EXISTING FOLDERS: ${folders.joinToString(", ")}
EXISTING TAGS: ${tags.joinToString(", ")}
EXISTING NOTES: ${titles.joinToString(" | ")}
            """.trim()),
            ChatML.Msg("user", "TITLE: ${note.title}\n\n${note.body.take(3000)}"),
        ), thinking = false)
        val out = engine.complete(prompt, GenParams(maxTokens = 320, temperature = 0.2f, topK = 20))
        val json = extractJson(out) ?: return null
        return Plan(
            folder = json.optString("folder").trim().takeIf { it.isNotBlank() && it.length < 40 },
            tags = json.optJSONArray("tags")?.let { a -> (0 until a.length()).map { WikiLinks.normalizeTag(a.optString(it)) } }
                ?.filter { it.isNotBlank() && it.length < 40 }?.take(5).orEmpty(),
            summary = json.optString("summary").trim().take(200),
            tasks = json.optJSONArray("tasks")?.let { a -> (0 until a.length()).map { a.optString(it).trim() } }
                ?.filter { it.length in 3..200 }?.take(8).orEmpty(),
            links = json.optJSONArray("links")?.let { a -> (0 until a.length()).map { a.optString(it).trim() } }
                ?.filter { it in titles }?.take(5).orEmpty(),
            title = json.optString("title").trim().trim('"').take(60).takeIf { it.length >= 3 && needsTitle(note) },
        )
    }

    suspend fun apply(note: Note, plan: Plan) = withContext(Dispatchers.IO) {
        val current = store.noteSync(note.id) ?: return@withContext
        val existingLinks = WikiLinks.links(current.body).map { it.lowercase() }.toSet()
        val newLinks = plan.links.filter { it.lowercase() !in existingLinks }
        val body = if (newLinks.isEmpty()) current.body
        else current.body.trimEnd() + "\n\n---\nrelated: " + newLinks.joinToString(" ") { "[[$it]]" }
        store.upsertNoteSync(current.copy(
            title = plan.title ?: current.title,
            folder = if (current.folder.equals("Inbox", true) && plan.folder != null) plan.folder else current.folder,
            tags = (current.tags + plan.tags).distinct(),
            summary = plan.summary.ifBlank { current.summary },
            body = body,
            organized = true,
        ), markUnorganized = false)
        val existingTasks = store.tasksSync().map { it.title.lowercase() }.toSet()
        plan.tasks.filter { it.lowercase() !in existingTasks }.forEach {
            store.addTaskSync(TaskItem(title = it, list = "Extracted", noteId = note.id))
        }
        store.markOrganizedSync(note.id)
    }

    /** Organize up to [limit] pending notes. Returns how many were processed. */
    suspend fun runPending(limit: Int = 8): Int {
        if (!engine.ensureLoaded()) return 0
        var n = 0
        for (note in withContext(Dispatchers.IO) { store.unorganizedSync(limit) }) {
            val plan = runCatching { organize(note) }.getOrNull()
            if (plan != null) apply(note, plan) else withContext(Dispatchers.IO) { store.markOrganizedSync(note.id) }
            n++
        }
        return n
    }

    /** One-shot text transforms used by the editor's AI actions. */
    suspend fun transform(kind: Transform, text: String): String {
        val instruction = when (kind) {
            Transform.SUMMARIZE -> "Summarize the note as 3-6 tight markdown bullets."
            Transform.EXPAND -> "Expand the note into well-structured markdown with headings. Keep the author's voice and facts; do not invent facts."
            Transform.CLEAN -> "Rewrite the note as clean, concise markdown. Fix grammar, merge duplicates, keep every fact and every [[link]]."
            Transform.NEXT_STEPS -> "List the concrete next actions implied by the note as a markdown checklist (- [ ] ...)."
            Transform.CONTINUE -> "Continue writing the note from where it ends, in the same voice and format. Output only the new text (2-6 sentences or bullets)."
            Transform.QUESTIONS -> "Ask 3-5 sharp questions that would deepen or challenge this note, as a markdown list."
        }
        val prompt = ChatML.build(listOf(
            ChatML.Msg("system", "You are a precise writing assistant inside a notes app. $instruction Output only the result."),
            ChatML.Msg("user", text.take(6000)),
        ), thinking = false)
        return ChatML.stripThinking(engine.complete(prompt, GenParams(maxTokens = prefs.value.maxTokens, temperature = 0.4f)))
    }

    enum class Transform { SUMMARIZE, EXPAND, CLEAN, NEXT_STEPS, CONTINUE, QUESTIONS }

    companion object {
        fun extractJson(s: String): JSONObject? {
            val t = ChatML.stripThinking(s)
            val start = t.indexOf('{')
            val end = t.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            return runCatching { JSONObject(t.substring(start, end + 1)) }.getOrNull()
        }

        private const val PERIODIC = "cortex-organize-periodic"
        private const val NOW = "cortex-organize-now"

        fun schedule(context: Context, prefs: Prefs) {
            val wm = WorkManager.getInstance(context)
            val s = prefs.value
            if (!s.autoOrganize) { wm.cancelUniqueWork(PERIODIC); return }
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .setRequiresCharging(s.organizeOnlyCharging)
                .build()
            wm.enqueueUniquePeriodicWork(
                PERIODIC, ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<OrganizeWorker>(2, TimeUnit.HOURS).setConstraints(constraints).build(),
            )
        }

        fun kick(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                NOW, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<OrganizeWorker>().build(),
            )
        }
    }
}

class OrganizeWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = Cortex.get(applicationContext)
        if (!c.prefs.value.autoOrganize) return Result.success()
        return runCatching { c.organizer.runPending(); Result.success() }.getOrElse { Result.retry() }
    }
}
