package com.aura.edge

import android.content.Context
import android.util.Log
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * ══════════════════════════════════════════════════════════════════════════
 *  GCPTelemetry — The Cloud Sync Layer (Phase 5)
 * ══════════════════════════════════════════════════════════════════════════
 *
 *  All GCP interactions via HTTP to Cloud Run (no Firebase Android SDK needed).
 *  The Cloud Run backend writes to Firestore and Cloud Storage server-side.
 *
 *    🔥 /telemetry/screen   → Firestore: real-time session state
 *    👆 /telemetry/tap      → Firestore: tap event + latency metrics
 *    🛡️ /telemetry/ghost_tap → Firestore: prevented tap log
 *    📸 /telemetry/screenshot → Cloud Storage: audit trail
 *    🛡️ /verify_tap         → Safety guardrail check
 *
 *  Setup: after deploying to Cloud Run, set GUARDRAIL_API_URL in local.properties.
 * ══════════════════════════════════════════════════════════════════════════
 */
class GCPTelemetry(
    private val context: Context,
    val deviceId: String = UUID.randomUUID().toString().take(8)
) {

    companion object {
        private const val TAG = "AuraTelemetry"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val apiUrl: String = BuildConfig.GUARDRAIL_API_URL.trimEnd('/')
    private val isConfigured: Boolean get() = apiUrl.isNotBlank()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    // Local metrics (for quick access without waiting for Firestore)
    @Volatile var totalTaps = 0; private set
    @Volatile var ghostTapsPrevented = 0; private set
    private val latencies = mutableListOf<Long>()
    val avgLatencyMs: Long get() = if (latencies.isEmpty()) 0L else latencies.average().toLong()

    // ═══════════════════════════════════════════════════════════════
    //  SCREEN STATE → Firestore via Cloud Run
    // ═══════════════════════════════════════════════════════════════

    fun onScreenChanged(packageName: String, flattenedTree: String) {
        val nodeCount = flattenedTree.count { it == '[' }
        Log.d(TAG, "📋 Screen: $packageName ($nodeCount nodes)")

        if (!isConfigured) return

        val payload = JsonObject().apply {
            addProperty("device_id", deviceId)
            addProperty("package_name", packageName)
            addProperty("node_count", nodeCount)
            addProperty("timestamp", System.currentTimeMillis())
        }
        fireAndForget("/telemetry/screen", payload)
    }

    // ═══════════════════════════════════════════════════════════════
    //  TAP EVENT → Firestore via Cloud Run
    // ═══════════════════════════════════════════════════════════════

    fun onTapExecuted(
        targetText: String,
        resolvedText: String,
        x: Int,
        y: Int,
        confidence: Double,
        latencyMs: Long,
        packageName: String
    ) {
        totalTaps++
        synchronized(latencies) { latencies.add(latencyMs) }
        Log.i(TAG, "👆 Tap: '$resolvedText' @ ($x,$y) | ${latencyMs}ms | conf=${"%.2f".format(confidence)}")

        if (!isConfigured) return

        val payload = JsonObject().apply {
            addProperty("device_id", deviceId)
            addProperty("target_text", targetText)
            addProperty("resolved_text", resolvedText)
            addProperty("x", x)
            addProperty("y", y)
            addProperty("confidence", confidence)
            addProperty("latency_ms", latencyMs)
            addProperty("package_name", packageName)
            addProperty("timestamp", System.currentTimeMillis())
        }
        fireAndForget("/telemetry/tap", payload)
    }

    // ═══════════════════════════════════════════════════════════════
    //  GHOST TAP PREVENTED → Firestore via Cloud Run
    // ═══════════════════════════════════════════════════════════════

    fun onGhostTapPrevented(targetText: String, reason: String) {
        ghostTapsPrevented++
        Log.w(TAG, "🛡️ Ghost tap prevented: '$targetText' — $reason")

        if (!isConfigured) return

        val payload = JsonObject().apply {
            addProperty("device_id", deviceId)
            addProperty("target_text", targetText)
            addProperty("reason", reason)
            addProperty("timestamp", System.currentTimeMillis())
        }
        fireAndForget("/telemetry/ghost_tap", payload)
    }

    // ═══════════════════════════════════════════════════════════════
    //  SCREENSHOT AUDIT → Cloud Storage via Cloud Run
    // ═══════════════════════════════════════════════════════════════

    fun uploadAuditScreenshot(screenshotBase64: String) {
        Log.d(TAG, "📸 Uploading audit screenshot (${screenshotBase64.length} chars)")

        if (!isConfigured) return

        val payload = JsonObject().apply {
            addProperty("device_id", deviceId)
            addProperty("screenshot_b64", screenshotBase64)
            addProperty("timestamp", System.currentTimeMillis())
        }
        fireAndForget("/telemetry/screenshot", payload)
    }

    // ═══════════════════════════════════════════════════════════════
    //  CLOUD RUN: SAFETY GUARDRAIL API
    // ═══════════════════════════════════════════════════════════════

    fun checkGuardrail(targetText: String, packageName: String) {
        if (!isConfigured) {
            Log.d(TAG, "🛡️ Guardrail not configured — skipping")
            return
        }

        scope.launch {
            try {
                val payload = JsonObject().apply {
                    addProperty("target_text", targetText)
                    addProperty("package_name", packageName)
                }

                val request = Request.Builder()
                    .url("$apiUrl/verify_tap")
                    .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                val response = httpClient.newCall(request).execute()
                val body = response.body?.string()

                if (response.isSuccessful && body != null) {
                    val result = com.google.gson.JsonParser.parseString(body).asJsonObject
                    val safe = result.get("safe").asBoolean
                    Log.d(TAG, "🛡️ Guardrail: safe=$safe for '$targetText'")

                    if (!safe) {
                        onGhostTapPrevented(targetText, "Cloud Run guardrail flagged")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Guardrail API unavailable: ${e.message}")
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  HELPERS
    // ═══════════════════════════════════════════════════════════════

    /** Fire-and-forget POST: does not block caller, silently handles errors. */
    private fun fireAndForget(path: String, payload: JsonObject) {
        scope.launch {
            try {
                val request = Request.Builder()
                    .url("$apiUrl$path")
                    .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    Log.w(TAG, "⚠️ Telemetry POST $path returned ${response.code}")
                }
                response.body?.close()
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Telemetry POST $path failed: ${e.message}")
            }
        }
    }

    fun destroy() {
        scope.cancel()
    }
}
