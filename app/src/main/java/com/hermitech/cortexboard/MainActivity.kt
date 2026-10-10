package com.hermitech.cortexboard

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.hermitech.cortexboard.ui.CortexRoot
import com.hermitech.cortexboard.ui.theme.CortexTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashGuard.pending(this)?.let { trace ->
            CrashGuard.show(this, trace) { recreate() }
            return
        }
        enableEdgeToEdge()
        val cortex = Cortex.get(this)
        cortex.start()
        handleShare(intent)
        setContent { CortexTheme { CortexRoot(cortex) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** Anything shared to the app lands in the Inbox and gets auto-organized. */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = listOfNotNull(
            intent.getStringExtra(Intent.EXTRA_SUBJECT),
            intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString(),
        ).joinToString("\n\n").trim()
        if (text.isNotEmpty()) {
            Cortex.get(this).capture(text)
            Toast.makeText(this, "Captured to Inbox", Toast.LENGTH_SHORT).show()
        }
        intent.action = null
    }
}
