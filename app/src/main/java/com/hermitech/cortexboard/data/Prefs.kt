package com.hermitech.cortexboard.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.SecureRandom

data class Settings(
    val nCtx: Int = 4096,
    val threads: Int = defaultThreads(),
    val temperature: Float = 0.6f,
    val maxTokens: Int = 768,
    val thinking: Boolean = false,
    val autoOrganize: Boolean = true,
    val organizeOnlyCharging: Boolean = false,
    val bridgeEnabled: Boolean = false,
    val bridgePort: Int = 8765,
    val bridgeToken: String = "",
    val modelPathOverride: String = "",
) {
    companion object {
        fun defaultThreads(): Int = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 6)
    }
}

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("cortex", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<Settings> = _state
    val value: Settings get() = _state.value

    private fun read(): Settings {
        val d = Settings()
        var token = sp.getString("bridgeToken", "") ?: ""
        if (token.isBlank()) {
            token = randomToken()
            sp.edit().putString("bridgeToken", token).apply()
        }
        return Settings(
            nCtx = sp.getInt("nCtx", d.nCtx),
            threads = sp.getInt("threads", d.threads),
            temperature = sp.getFloat("temperature", d.temperature),
            maxTokens = sp.getInt("maxTokens", d.maxTokens),
            thinking = sp.getBoolean("thinking", d.thinking),
            autoOrganize = sp.getBoolean("autoOrganize", d.autoOrganize),
            organizeOnlyCharging = sp.getBoolean("organizeOnlyCharging", d.organizeOnlyCharging),
            bridgeEnabled = sp.getBoolean("bridgeEnabled", d.bridgeEnabled),
            bridgePort = sp.getInt("bridgePort", d.bridgePort),
            bridgeToken = token,
            modelPathOverride = sp.getString("modelPathOverride", "") ?: "",
        )
    }

    fun update(transform: (Settings) -> Settings) {
        val s = transform(_state.value)
        sp.edit()
            .putInt("nCtx", s.nCtx.coerceIn(1024, 32768))
            .putInt("threads", s.threads.coerceIn(1, 12))
            .putFloat("temperature", s.temperature.coerceIn(0f, 2f))
            .putInt("maxTokens", s.maxTokens.coerceIn(64, 4096))
            .putBoolean("thinking", s.thinking)
            .putBoolean("autoOrganize", s.autoOrganize)
            .putBoolean("organizeOnlyCharging", s.organizeOnlyCharging)
            .putBoolean("bridgeEnabled", s.bridgeEnabled)
            .putInt("bridgePort", s.bridgePort.coerceIn(1024, 65535))
            .putString("bridgeToken", s.bridgeToken.ifBlank { randomToken() })
            .putString("modelPathOverride", s.modelPathOverride)
            .apply()
        _state.value = read()
    }

    companion object {
        fun randomToken(): String {
            val b = ByteArray(24).also { SecureRandom().nextBytes(it) }
            return b.joinToString("") { "%02x".format(it) }
        }
    }
}
