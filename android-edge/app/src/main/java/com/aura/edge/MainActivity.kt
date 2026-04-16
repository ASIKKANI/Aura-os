package com.aura.edge

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton

/**
 * ══════════════════════════════════════════════════════════════════════════
 *  MainActivity — Aura Voice + UI Control
 * ══════════════════════════════════════════════════════════════════════════
 *
 *  Launches the Gemini Live session and wires together:
 *    - LiveSDKManager: bidirectional voice stream to Gemini
 *    - LiveSessionHolder: global reference for AuraAccessibilityService
 *    - AuraAccessibilityService.rebindToSession(): called on ACTIVE state
 *      so the tool call handler is always connected regardless of init order
 * ══════════════════════════════════════════════════════════════════════════
 */
class MainActivity : AppCompatActivity(), LiveSDKManager.SessionCallback {

    companion object {
        private const val REQUEST_MIC = 100
    }

    private lateinit var liveManager: LiveSDKManager

    // UI elements
    private lateinit var statusText: TextView
    private lateinit var toggleButton: MaterialButton
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var pulseIndicator: android.view.View

    private var isSessionActive = false
    private var pulseAnimator: ObjectAnimator? = null

    // ─────────────────────────────────────────────────────────────
    //  Lifecycle
    // ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Bind views
        statusText = findViewById(R.id.statusText)
        toggleButton = findViewById(R.id.toggleButton)
        logText = findViewById(R.id.logText)
        logScroll = findViewById(R.id.logScroll)
        pulseIndicator = findViewById(R.id.pulseIndicator)

        // Initialize LiveSDKManager and register globally
        liveManager = LiveSDKManager(this)
        liveManager.setCallback(this)
        LiveSessionHolder.liveManager = liveManager

        // Wire button
        toggleButton.setOnClickListener {
            if (!isSessionActive) {
                requestMicAndStart()
            } else {
                stopSession()
            }
        }

        appendLog("Aura v4.0 — Voice + UI Control")
        appendLog("──────────────────────────────────────────")

        // Validate API key at launch
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isBlank()) {
            appendLog("⚠️  No API key found!")
            appendLog("   Set GEMINI_API_KEY in local.properties")
            appendLog("   Example: GEMINI_API_KEY=AIzaSy...")
            toggleButton.isEnabled = false
        } else {
            appendLog("✅ API key loaded (${apiKey.take(8)}...)")
        }

        // Show Cloud Run URL status
        if (BuildConfig.GUARDRAIL_API_URL.isBlank()) {
            appendLog("⚠️  GUARDRAIL_API_URL not set — telemetry disabled")
            appendLog("   Deploy Cloud Run, then set in local.properties")
        } else {
            appendLog("✅ Cloud Run configured")
        }

        // Show accessibility service status
        if (AuraAccessibilityService.instance != null) {
            appendLog("✅ Accessibility service connected")
        } else {
            appendLog("⚠️  Accessibility service not enabled")
            appendLog("   Settings → Accessibility → Aura → Enable")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        liveManager.stopSession()
    }

    // ─────────────────────────────────────────────────────────────
    //  Permission Flow
    // ─────────────────────────────────────────────────────────────

    private fun requestMicAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startSession()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_MIC
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MIC && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startSession()
        } else {
            appendLog("❌ Microphone permission denied")
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  Session Control
    // ─────────────────────────────────────────────────────────────

    private fun startSession() {
        val apiKey = BuildConfig.GEMINI_API_KEY
        appendLog("🔗 Connecting to Gemini Live...")
        liveManager.startSession(apiKey)
        isSessionActive = true
        toggleButton.text = "Stop Session"
        toggleButton.setBackgroundColor(0xFFE53935.toInt()) // Red
    }

    private fun stopSession() {
        liveManager.stopSession()
        isSessionActive = false
        toggleButton.text = "Start Session"
        toggleButton.setBackgroundColor(0xFF1DB954.toInt()) // Green
        stopPulseAnimation()
    }

    // ─────────────────────────────────────────────────────────────
    //  LiveSDKManager.SessionCallback
    // ─────────────────────────────────────────────────────────────

    override fun onStateChanged(state: LiveSDKManager.SessionState) {
        runOnUiThread {
            statusText.text = state.name

            val color = when (state) {
                LiveSDKManager.SessionState.IDLE -> 0xFF9E9E9E.toInt()
                LiveSDKManager.SessionState.CONNECTING -> 0xFFFFA726.toInt()
                LiveSDKManager.SessionState.SETUP_SENT -> 0xFFFFA726.toInt()
                LiveSDKManager.SessionState.ACTIVE -> 0xFF1DB954.toInt()
                LiveSDKManager.SessionState.ERROR -> 0xFFE53935.toInt()
                LiveSDKManager.SessionState.CLOSED -> 0xFF9E9E9E.toInt()
            }
            statusText.setTextColor(color)

            appendLog("State → ${state.name}")

            if (state == LiveSDKManager.SessionState.ACTIVE) {
                appendLog("🎙️ Microphone LIVE — speak to Aura!")
                startPulseAnimation()
                // Wire the accessibility service as the execute_tap handler.
                // This handles the race condition where the service connected
                // before LiveSessionHolder.liveManager was set.
                AuraAccessibilityService.rebindToSession()
                if (AuraAccessibilityService.instance != null) {
                    appendLog("👁️ Screen vision: ACTIVE")
                }
            }
            if (state == LiveSDKManager.SessionState.ERROR ||
                state == LiveSDKManager.SessionState.CLOSED) {
                isSessionActive = false
                toggleButton.text = "Start Session"
                toggleButton.setBackgroundColor(0xFF1DB954.toInt())
                stopPulseAnimation()
            }
        }
    }

    override fun onError(message: String) {
        runOnUiThread {
            appendLog("❌ $message")
        }
    }

    override fun onModelSpeaking(isSpeaking: Boolean) {
        runOnUiThread {
            if (isSpeaking) {
                appendLog("🔊 Aura is speaking...")
            } else {
                appendLog("🔇 Aura finished speaking")
            }
        }
    }

    override fun onTranscript(text: String, isUser: Boolean) {
        runOnUiThread {
            val prefix = if (isUser) "👤" else "🤖"
            appendLog("$prefix $text")
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  UI Helpers
    // ─────────────────────────────────────────────────────────────

    private fun appendLog(message: String) {
        logText.append("$message\n")
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun startPulseAnimation() {
        pulseIndicator.alpha = 1f
        pulseAnimator = ObjectAnimator.ofFloat(pulseIndicator, "alpha", 0.3f, 1f).apply {
            duration = 1200
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun stopPulseAnimation() {
        pulseAnimator?.cancel()
        pulseIndicator.alpha = 0f
    }
}
