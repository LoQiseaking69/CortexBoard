package com.hermitech.cortexboard

import android.app.Application
import android.content.Context
import android.util.Log
import com.hermitech.cortexboard.agent.Agent
import com.hermitech.cortexboard.agent.Organizer
import com.hermitech.cortexboard.data.Note
import com.hermitech.cortexboard.data.Prefs
import com.hermitech.cortexboard.data.Store
import com.hermitech.cortexboard.llm.LlamaEngine
import com.hermitech.cortexboard.mcp.McpBridgeServer
import com.hermitech.cortexboard.mcp.McpHub
import com.hermitech.cortexboard.mcp.VaultMcpServer
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Manual DI graph — no reflection, no codegen, tiny startup cost. */
class Cortex(val app: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e ->
        Log.e("Cortex", "background failure", e)
        CrashGuard.record(app, "background", e)
    })
    @Volatile private var started = false
    val prefs = Prefs(app)
    val store = Store(app)
    val engine = LlamaEngine(app, prefs)
    val vault = VaultMcpServer(store)
    val hub = McpHub(store, vault)
    val agent = Agent(engine, hub, prefs, store)
    val organizer = Organizer(engine, store, prefs)
    val bridge = McpBridgeServer(vault)

    @Synchronized
    fun start() {
        if (started) return
        started = true
        runCatching { Organizer.schedule(app, prefs) }.onFailure { CrashGuard.record(app, "organizer.schedule", it) }
        scope.launch {
            prefs.state.map { Triple(it.bridgeEnabled, it.bridgePort, it.bridgeToken) }.distinctUntilChanged()
                .collect { (on, port, token) ->
                    runCatching { if (on) bridge.start(port, token) else bridge.stop() }
                        .onFailure { Log.e("Cortex", "bridge: ${it.message}") }
                }
        }
        scope.launch { runCatching { hub.refresh() } }
        // warm the brain so the first chat doesn't pay the load cost
        if (prefs.value.preloadModel) scope.launch { if (engine.hasLocalModel()) engine.ensureLoaded() }
    }

    /** Quick capture: drop raw text into the Inbox and let the organizer file it. */
    fun capture(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        scope.launch {
            val firstLine = t.lineSequence().first().take(60).trim().trimStart('#', ' ')
            store.upsertNote(Note(title = firstLine.ifBlank { "Capture" }, body = t, folder = "Inbox"))
            if (prefs.value.autoOrganize) Organizer.kick(app)
        }
    }

    companion object {
        @Volatile private var instance: Cortex? = null
        fun get(ctx: Context): Cortex = instance ?: synchronized(this) {
            instance ?: Cortex(ctx.applicationContext).also { instance = it }
        }
    }
}

class CortexApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashGuard.install(this)
        // After a crash, stay in safe mode until the user has seen the report (MainActivity).
        if (CrashGuard.pending(this) == null) Cortex.get(this).start()
    }
}
