package com.aura.edge

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.*
import okio.ByteString
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * ══════════════════════════════════════════════════════════════════════════
 *  LiveSDKManager — The Ears & Voice of Aura
 * ══════════════════════════════════════════════════════════════════════════
 *
 *  Manages a persistent bidirectional WebSocket connection to the
 *  Gemini Live API. Streams raw PCM audio from the device microphone
 *  to Gemini and plays back Gemini's audio responses through the speaker.
 *
 *  Architecture:
 *    ┌──────────┐   base64 PCM 16kHz   ┌─────────────────────┐
 *    │ AudioRec │ ───────────────────→  │                     │
 *    │  (Mic)   │   realtimeInput       │   Gemini Live API   │
 *    └──────────┘                       │   (WebSocket WSS)   │
 *                                       │                     │
 *    ┌──────────┐   base64 PCM 24kHz   │                     │
 *    │AudioTrack│ ←───────────────────  │                     │
 *    │(Speaker) │   serverContent       └─────────────────────┘
 *    └──────────┘
 *
 *  Zero proxy. Zero Python relay. Device-to-Google direct.
 * ══════════════════════════════════════════════════════════════════════════
 */
class LiveSDKManager(private val context: Context) {

    companion object {
        private const val TAG = "AuraLive"

        // ── Gemini Live WebSocket endpoint ──
        private const val LIVE_ENDPOINT =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent"

        // ── Model (per user condition) ──
        private const val MODEL = "models/gemini-2.5-flash-native-audio-latest"

        // ── Audio Config ──
        private const val MIC_SAMPLE_RATE = 16000       // Gemini expects 16 kHz input
        private const val SPEAKER_SAMPLE_RATE = 24000    // Gemini outputs  24 kHz
        private const val MIC_CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val SPEAKER_CHANNEL = AudioFormat.CHANNEL_OUT_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val CHUNK_FRAMES = 1024            // 1024 frames = 64 ms @ 16 kHz
        private const val BYTES_PER_FRAME = 2            // 16-bit = 2 bytes
        private const val CHUNK_BYTES = CHUNK_FRAMES * BYTES_PER_FRAME  // 2048 bytes per chunk

        // ── Aura system prompt (AURA 6.0: Cognitive State Machine) ──
        private const val SYSTEM_PROMPT = """
You are Aura, an elite, fully autonomous AI OS Controller for Project Sahayak. You have full control of the user's Android device. Your prime directive: complete multi-step tasks flawlessly — without giving up, getting lost, or making assumptions. Users have low digital literacy and rely entirely on you.

══ THE AURA LOOP (run every turn) ══
1. RECALL: What is the ultimate goal the user asked for?
2. OBSERVE: What is ACTUALLY on screen right now? Read the [SCREEN_UPDATE] data carefully.
3. DIAGNOSE: Is there an obstacle blocking the goal? (Ad, popup, permission dialog, loading screen, wrong app)
4. PLAN: What is the single next tool call to advance toward the goal?
5. ACT: Call the tool. No explanation. No narration. Just execute.

══ RULES OF ENGAGEMENT ══

RULE 1 — RELENTLESS PURSUIT:
Tasks take as many steps as needed. Do NOT stop mid-task. Do NOT ask for permission to continue unless the next action involves payment, money transfer, or permanent deletion. Keep going until the final success state is visible on screen.

RULE 2 — THE POPUP PROTOCOL:
Mobile apps (especially Indian apps) constantly interrupt with ads, "Rate this App", "Try Premium", "Allow Notifications", "Install Update" popups. These are obstacles — do NOT let them derail you.
Detection: Look for nodes flagged f:DISMISS or any visible text matching: Skip, Close, X, Not Now, Later, No Thanks, Skip Trial, Maybe Later, Cancel, Dismiss.
Action: Tap the dismiss button IMMEDIATELY, then continue the original task without missing a beat.
Never ask the user about a popup. Just kill it and move on.

RULE 3 — SILENT EXECUTION, STRATEGIC BREADCRUMBS:
Never read the screen contents aloud. For tasks requiring more than 3 steps, give a brief spoken update every few steps so the user knows you are working. Keep updates under 5 words. Examples: "Opening Swiggy now." / "Typing your search." / "Almost done!" Speak a final confirmation only when the full goal is achieved.

RULE 4 — SCROLL TO DISCOVER:
If a target element is not visible on the current screen, do NOT give up or say "I can't find it." Call scroll("down") to reveal more content. Keep scrolling until you find it or reach the absolute bottom of the screen. Only after exhausting all scroll attempts should you try a different approach.

RULE 5 — ERROR RECOVERY:
If execute_tap returns screen_changed:false, the button did not respond. Recovery order:
(a) scroll("down") to check if the real button is below the fold.
(b) execute_tap on a synonym or parent container (e.g., tap "Order" instead of "Place Order").
(c) global_action("back") to reset the view and retry from a clean state.
Attempt recovery up to 3 times before admitting failure.

RULE 6 — VERIFY BEFORE DECLARING DONE:
Only say "Done" when the final objective is visually confirmed in the [SCREEN_UPDATE] (e.g., "Order Placed", "Message Sent", video is actively playing, navigation has started). Never assume an action succeeded. Trust only what the screen shows.

══ TOOLS ══
- open_app(app_name): Launch an installed app. e.g. open_app("Swiggy")
- execute_tap(target_text): Tap a visible UI element by its text or label. e.g. execute_tap("Accept Order")
- type_text(text, target_field): Type text into a field. Auto-submits if a submit button is found. Response includes submitted:true/false and screen_changed:true/false.
- scroll(direction): Scroll the screen. Values: "up" / "down" / "left" / "right"
- global_action(action): System navigation. Values: "home" / "back" / "recents"

══ TYPE_TEXT RULES ══
Always check the submitted field in the type_text tool response:
- submitted:true → The search/submit button was pressed automatically. Wait for the next [SCREEN_UPDATE] and continue.
- submitted:false → The submit button was NOT found. You MUST immediately call execute_tap("Search") or execute_tap with the visible submit/go button text. NEVER assume text entry alone completes a search.

══ SCROLL RULES ══
- Standard content (settings, menus, lists, articles): scroll("down") = see content below, scroll("up") = go back up.
- Full-page swipe apps (Instagram Reels, YouTube Shorts, TikTok): scroll("up") = NEXT video/reel. scroll("down") = PREVIOUS video/reel.
- After each scroll, check screen_changed in the response:
  - screen_changed:false → content did not change (you may have hit the end). Try the opposite direction or navigate back.

══ [SCREEN_UPDATE] RULE ══
When you receive a [SCREEN_UPDATE], NEVER respond with words. Silently analyze the screen data and call the next tool. If there is no active task, stay completely silent and wait for the user to speak.

══ EXECUTION TRACES (memorize these exact patterns) ══

TRACE 1 — Zomato Food Delivery (scroll + out-of-stock recovery):
Goal: Order Chicken Biryani.
→ "Opening Zomato." open_app("Zomato") → execute_tap("Search") → type_text("Chicken Biryani","Search for food") → execute_tap("Meghana Foods") → [Biryani NOT visible] scroll("down") → [Biryani visible but marked "Out of Stock"] global_action("back") → execute_tap("Paradise Biryani") → execute_tap("Add") → execute_tap("Go to Cart") → "Chicken Biryani from Paradise is in your cart. Please confirm payment."

TRACE 2 — YouTube (popup dismissal + ad skip):
Goal: Play latest MKBHD video.
→ "Pulling up YouTube." open_app("YouTube") → [SCREEN shows "Try YouTube Premium" popup] execute_tap("Skip Trial") → execute_tap("Search") → type_text("MKBHD","Search YouTube") → [submitted:false] execute_tap("Search") → execute_tap("Smartphone Awards 2025") → [Pre-roll ad playing, Skip Ad countdown visible] Wait one turn → execute_tap("Skip Ad") → "Playing now."

TRACE 3 — Gig Worker speed execution (Swiggy accept + navigate):
Goal: Accept order and start navigation. [Screen shows incoming Swiggy ping]
→ execute_tap("Accept Order") → execute_tap("Go to Maps") → execute_tap("Start") → "Navigation started. Head to the restaurant."

TRACE 4 — Ambiguity resolution (WhatsApp multi-contact):
Goal: Message Rahul.
→ open_app("WhatsApp") → execute_tap("Search") → type_text("Rahul","Search...") → [Screen shows: "Rahul Sharma (Work)", "Rahul College", "Rahul Driver"] → STOP. Say: "I found three Rahuls — Rahul Sharma, Rahul College, or Rahul Driver. Which one?" → [User: Rahul College] → execute_tap("Rahul College") → type_text("I'll be 10 minutes late.","Message") → execute_tap("Send") → "Message sent to Rahul College."
"""
    }

    // ═══════════════════════════════════════════════════════════════
    //  Session State Machine
    // ═══════════════════════════════════════════════════════════════

    enum class SessionState {
        IDLE,           // No connection
        CONNECTING,     // WebSocket handshake in progress
        SETUP_SENT,     // BidiGenerateContentSetup sent, awaiting setupComplete
        ACTIVE,         // Ready — streaming audio both ways
        ERROR,          // Fatal error, needs restart
        CLOSED          // Intentionally terminated
    }

    // ═══════════════════════════════════════════════════════════════
    //  Callbacks for UI layer
    // ═══════════════════════════════════════════════════════════════

    interface SessionCallback {
        fun onStateChanged(state: SessionState)
        fun onError(message: String)
        fun onModelSpeaking(isSpeaking: Boolean)
        fun onTranscript(text: String, isUser: Boolean)
    }

    private var callback: SessionCallback? = null

    fun setCallback(cb: SessionCallback) {
        callback = cb
    }

    // ═══════════════════════════════════════════════════════════════
    //  Internal State
    // ═══════════════════════════════════════════════════════════════

    private val state = AtomicReference(SessionState.IDLE)
    private var webSocket: WebSocket? = null

    // OkHttp client — long-lived, no read timeout, periodic pings
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)   // Keep-alive for mobile networks
        .build()

    // Audio hardware handles
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    // Coroutine management
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var micCaptureJob: Job? = null
    private var audioPlaybackJob: Job? = null

    // Thread-safe queue: WebSocket receiver → AudioTrack consumer
    private val playbackQueue = Channel<ByteArray>(Channel.UNLIMITED)

    // Track model speaking state for barge-in UX
    private var modelCurrentlySpeaking = false

    // Tracks tool call IDs cancelled by the server (toolCallCancellation message)
    private val cancelledCallIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // ═══════════════════════════════════════════════════════════════
    //  PUBLIC API
    // ═══════════════════════════════════════════════════════════════

    /**
     * Opens the WebSocket, sends the setup message, and (once setup completes)
     * begins streaming mic audio and playing back Gemini responses.
     *
     * @param apiKey Google AI Studio API key
     */
    fun startSession(apiKey: String) {
        if (state.get() != SessionState.IDLE && state.get() != SessionState.CLOSED
            && state.get() != SessionState.ERROR
        ) {
            Log.w(TAG, "startSession ignored — already in state ${state.get()}")
            return
        }

        updateState(SessionState.CONNECTING)
        Log.i(TAG, "┌─── Opening Gemini Live WebSocket ───")

        val url = "$LIVE_ENDPOINT?key=$apiKey"
        val request = Request.Builder().url(url).build()
        webSocket = httpClient.newWebSocket(request, GeminiWebSocketListener())
    }

    /**
     * Cleanly shuts down the session: stops audio, cancels coroutines,
     * closes the WebSocket.
     */
    fun stopSession() {
        Log.i(TAG, "└─── Stopping session ───")

        // Mark CLOSED first so onClosed() callback knows this was intentional
        // and won't fire a spurious "Session closed by server" error to the UI.
        updateState(SessionState.CLOSED)

        micCaptureJob?.cancel()
        audioPlaybackJob?.cancel()

        audioRecord?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        audioTrack?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        audioRecord = null
        audioTrack = null

        webSocket?.close(1000, "User stopped session")
        webSocket = null

        // Drain any remaining audio
        while (playbackQueue.tryReceive().isSuccess) { /* discard */ }
        cancelledCallIds.clear()
    }

    /**
     * Injects a UI context update into the live session.
     * Uses realtimeInput.text — the only supported text input channel
     * for native audio models. clientContent causes 1008 session close.
     */
    fun injectUIContext(flattenedTree: String, screenshotBase64: String? = null) {
        if (state.get() != SessionState.ACTIVE) return

        // realtimeInput.text is the correct channel for native audio models
        val message = JsonObject().apply {
            add("realtimeInput", JsonObject().apply {
                addProperty("text", "[SCREEN_UPDATE] Current screen:\n$flattenedTree")
            })
        }

        webSocket?.send(message.toString())
        Log.d(TAG, "📤 UI context injected (${flattenedTree.length} chars)")
    }

    // ═══════════════════════════════════════════════════════════════
    //  TOOL CALL HANDLER (Phase 4 — stub for now)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Callback invoked when Gemini triggers a function call.
     * Set this from AuraAccessibilityService in Phase 4.
     */
    var onToolCall: ((callId: String, functionName: String, args: Map<String, Any>) -> Unit)? =
        null

    /**
     * Sends a tool response back to the Gemini Live session.
     * @param callId The ID from the toolCall message
     * @param functionName The tool name that was called (execute_tap, open_app, etc.)
     * @param response Key-value result payload to return to the model
     */
    fun sendToolResponse(callId: String, functionName: String, response: Map<String, Any>) {
        if (cancelledCallIds.remove(callId)) {
            Log.d(TAG, "│  ⏭️ Skipping response for cancelled call $callId ($functionName)")
            return
        }
        val message = JsonObject().apply {
            add("toolResponse", JsonObject().apply {
                add("functionResponses", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("id", callId)
                        addProperty("name", functionName)
                        add("response", JsonObject().apply {
                            response.forEach { (k, v) ->
                                addProperty(k, v.toString())
                            }
                        })
                    })
                })
            })
        }
        webSocket?.send(message.toString())
        Log.d(TAG, "📤 Tool response: $functionName [id=$callId] → ${response["status"]}")
    }

    // ═══════════════════════════════════════════════════════════════
    //  WEBSOCKET LISTENER
    // ═══════════════════════════════════════════════════════════════

    private inner class GeminiWebSocketListener : WebSocketListener() {

        override fun onOpen(ws: WebSocket, response: Response) {
            Log.i(TAG, "│  WebSocket OPEN — sending setup message")
            sendSetupMessage(ws)
            updateState(SessionState.SETUP_SENT)

            // Setup timeout: if setupComplete doesn't arrive in 15s, report error
            scope.launch {
                delay(15_000)
                if (state.get() == SessionState.SETUP_SENT) {
                    Log.e(TAG, "│  ❌ Setup timeout — no setupComplete after 15s")
                    updateState(SessionState.ERROR)
                    callback?.onError("Setup timeout: server did not respond to setup message")
                    ws.close(1001, "Setup timeout")
                }
            }
        }

        override fun onMessage(ws: WebSocket, text: String) {
            handleServerMessage(text)
        }

        // Handle binary frames — native audio models may send binary WebSocket frames
        override fun onMessage(ws: WebSocket, bytes: okio.ByteString) {
            Log.d(TAG, "│  📩 Binary frame received (${bytes.size} bytes)")
            // Gemini wraps all control messages as UTF-8 JSON even in binary frames
            try {
                handleServerMessage(bytes.utf8())
            } catch (e: Exception) {
                Log.w(TAG, "│  ⚠️ Could not decode binary frame as JSON: ${e.message}")
            }
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "│  WebSocket FAILURE: ${t.message}", t)
            updateState(SessionState.ERROR)
            callback?.onError("Connection failed: ${t.message}")
        }

        override fun onClosing(ws: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "│  WebSocket CLOSING: code=$code reason=$reason")
            ws.close(code, reason)
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "└─── WebSocket CLOSED: code=$code reason=$reason")
            if (state.get() != SessionState.CLOSED) {
                updateState(SessionState.CLOSED)
                // Surface the close reason so the user can see why the session ended
                val msg = if (reason.isNotBlank()) "Session closed ($code): $reason"
                          else "Session closed by server (code $code)"
                callback?.onError(msg)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  SETUP MESSAGE
    // ═══════════════════════════════════════════════════════════════

    private fun sendSetupMessage(ws: WebSocket) {
        val setupJson = JsonObject().apply {
            add("setup", JsonObject().apply {
                addProperty("model", MODEL)

                // Generation config
                add("generationConfig", JsonObject().apply {
                    add("responseModalities", JsonArray().apply {
                        add("AUDIO")
                    })
                    // Voice selection
                    add("speechConfig", JsonObject().apply {
                        add("voiceConfig", JsonObject().apply {
                            add("prebuiltVoiceConfig", JsonObject().apply {
                                addProperty("voiceName", "Aoede")
                            })
                        })
                    })
                })

                // System instruction: Aura persona
                add("systemInstruction", JsonObject().apply {
                    add("parts", JsonArray().apply {
                        add(JsonObject().apply {
                            addProperty("text", SYSTEM_PROMPT.trimIndent())
                        })
                    })
                })

                // Tools: execute_tap for physical screen interaction (Phase 4)
                add("tools", buildToolDeclarations())
            })
        }

        val payload = setupJson.toString()
        ws.send(payload)
        Log.d(TAG, "│  📤 Setup message sent (${payload.length} bytes)")
    }

    // ═══════════════════════════════════════════════════════════════
    //  SERVER MESSAGE HANDLER
    // ═══════════════════════════════════════════════════════════════

    private fun handleServerMessage(raw: String) {
        try {
            val json = JsonParser.parseString(raw).asJsonObject

            when {
                // ── Setup Complete — begin audio streaming ──
                json.has("setupComplete") -> {
                    Log.i(TAG, "│  ✅ Setup complete — starting audio pipeline")
                    updateState(SessionState.ACTIVE)
                    startMicCapture()
                    startAudioPlayback()
                }

                // ── Server Content (audio response, interruption, turn complete) ──
                json.has("serverContent") -> {
                    val sc = json.getAsJsonObject("serverContent")
                    handleServerContent(sc)
                }

                // ── Tool Call from Gemini (Phase 4) ──
                json.has("toolCall") -> {
                    handleToolCall(json.getAsJsonObject("toolCall"))
                }

                // ── Tool Call Cancellation (barge-in or model decided not to execute) ──
                json.has("toolCallCancellation") -> {
                    val cancelObj = json.getAsJsonObject("toolCallCancellation")
                    if (cancelObj.has("ids")) {
                        val ids = cancelObj.getAsJsonArray("ids").map { it.asString }
                        cancelledCallIds.addAll(ids)
                        Log.i(TAG, "│  🚫 Tool calls cancelled: $ids")
                    }
                }

                // ── Server-side error (bad request, auth failure, quota, etc.) ──
                json.has("error") -> {
                    val err = json.getAsJsonObject("error")
                    val code = if (err.has("code")) err.get("code").asInt else -1
                    val message = if (err.has("message")) err.get("message").asString else "Unknown error"
                    val status = if (err.has("status")) err.get("status").asString else ""
                    Log.e(TAG, "│  ❌ Server error $code [$status]: $message")
                    updateState(SessionState.ERROR)
                    callback?.onError("Server error $code: $message")
                }

                else -> {
                    Log.d(TAG, "│  📩 Unhandled message type: ${raw.take(200)}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "│  ❌ Failed to parse server message: ${e.message}")
        }
    }

    /**
     * Processes a serverContent message:
     *  - Extract & queue audio data for playback
     *  - Handle barge-in interruptions (flush playback queue)
     *  - Handle turn completion
     */
    private fun handleServerContent(sc: JsonObject) {
        // ── Check for interruption (barge-in) ──
        if (sc.has("interrupted") && sc.get("interrupted").asBoolean) {
            Log.d(TAG, "│  🔇 Barge-in detected — flushing playback queue")
            flushPlaybackQueue()
            if (modelCurrentlySpeaking) {
                modelCurrentlySpeaking = false
                callback?.onModelSpeaking(false)
            }
            return
        }

        // ── Extract audio from model turn ──
        if (sc.has("modelTurn")) {
            val modelTurn = sc.getAsJsonObject("modelTurn")
            if (modelTurn.has("parts")) {
                val parts = modelTurn.getAsJsonArray("parts")
                for (part in parts) {
                    val partObj = part.asJsonObject

                    // Audio data (inline PCM)
                    if (partObj.has("inlineData")) {
                        val inlineData = partObj.getAsJsonObject("inlineData")
                        val b64Data = inlineData.get("data").asString
                        val pcmBytes = Base64.decode(b64Data, Base64.NO_WRAP)

                        if (!modelCurrentlySpeaking) {
                            modelCurrentlySpeaking = true
                            callback?.onModelSpeaking(true)
                        }

                        // Enqueue for playback (non-blocking)
                        playbackQueue.trySend(pcmBytes)
                    }

                    // Text transcript (if model returns text alongside audio)
                    if (partObj.has("text")) {
                        val text = partObj.get("text").asString
                        callback?.onTranscript(text, isUser = false)
                    }
                }
            }
        }

        // ── Turn complete ──
        if (sc.has("turnComplete") && sc.get("turnComplete").asBoolean) {
            Log.d(TAG, "│  🏁 Model turn complete")
            if (modelCurrentlySpeaking) {
                modelCurrentlySpeaking = false
                callback?.onModelSpeaking(false)
            }
        }
    }

    /**
     * Handles tool call from Gemini (Phase 4).
     * Parses function name and arguments, delegates to registered callback.
     */
    private fun handleToolCall(toolCallJson: JsonObject) {
        if (!toolCallJson.has("functionCalls")) return

        val calls = toolCallJson.getAsJsonArray("functionCalls")
        for (call in calls) {
            val callObj = call.asJsonObject
            val callId = callObj.get("id").asString
            val name = callObj.get("name").asString
            val args = mutableMapOf<String, Any>()

            if (callObj.has("args")) {
                val argsObj = callObj.getAsJsonObject("args")
                for (entry in argsObj.entrySet()) {
                    args[entry.key] = entry.value.asString
                }
            }

            Log.i(TAG, "│  🔧 Tool call: $name($args) [id=$callId]")

            if (onToolCall != null) {
                onToolCall?.invoke(callId, name, args)
            } else {
                // No handler registered — accessibility service not enabled
                Log.w(TAG, "│  ⚠️ No tool call handler registered — returning error")
                sendToolResponse(callId, name, mapOf(
                    "status" to "ERROR",
                    "message" to "Accessibility service not available. Please enable it in Settings."
                ))
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  AUDIO CAPTURE (Microphone → Gemini)
    // ═══════════════════════════════════════════════════════════════

    @SuppressLint("MissingPermission") // Permission checked before calling startSession
    private fun startMicCapture() {
        val minBufSize = AudioRecord.getMinBufferSize(MIC_SAMPLE_RATE, MIC_CHANNEL, ENCODING)
        val bufferSize = maxOf(minBufSize, CHUNK_BYTES * 4)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,  // Noise-cancellation & echo-cancel
            MIC_SAMPLE_RATE,
            MIC_CHANNEL,
            ENCODING,
            bufferSize
        ).also { rec ->
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "│  ❌ AudioRecord failed to initialize")
                callback?.onError("Microphone initialization failed")
                return
            }
        }

        audioRecord!!.startRecording()
        Log.i(TAG, "│  🎙️ Microphone started (16kHz, 16-bit, mono, chunks=${CHUNK_BYTES}B)")

        micCaptureJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(CHUNK_BYTES)

            while (isActive && state.get() == SessionState.ACTIVE) {
                val bytesRead = audioRecord?.read(buffer, 0, CHUNK_BYTES) ?: -1

                if (bytesRead > 0) {
                    // Base64-encode the raw PCM chunk
                    val b64 = Base64.encodeToString(
                        buffer.copyOf(bytesRead),
                        Base64.NO_WRAP
                    )

                    // Build the realtimeInput message
                    val message = JsonObject().apply {
                        add("realtimeInput", JsonObject().apply {
                            add("mediaChunks", JsonArray().apply {
                                add(JsonObject().apply {
                                    addProperty("mimeType", "audio/pcm;rate=$MIC_SAMPLE_RATE")
                                    addProperty("data", b64)
                                })
                            })
                        })
                    }

                    webSocket?.send(message.toString())
                } else if (bytesRead == AudioRecord.ERROR_INVALID_OPERATION) {
                    Log.e(TAG, "│  ❌ AudioRecord read error")
                    break
                }
            }

            Log.d(TAG, "│  🎙️ Mic capture loop ended")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  AUDIO PLAYBACK (Gemini → Speaker)
    // ═══════════════════════════════════════════════════════════════

    private fun startAudioPlayback() {
        val minBufSize = AudioTrack.getMinBufferSize(SPEAKER_SAMPLE_RATE, SPEAKER_CHANNEL, ENCODING)
        val bufferSize = maxOf(minBufSize, CHUNK_BYTES * 4)

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(ENCODING)
                    .setSampleRate(SPEAKER_SAMPLE_RATE)
                    .setChannelMask(SPEAKER_CHANNEL)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack!!.play()
        Log.i(TAG, "│  🔊 Speaker started (24kHz, 16-bit, mono)")

        audioPlaybackJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val pcmChunk = playbackQueue.receive()  // Suspends until data available
                audioTrack?.write(pcmChunk, 0, pcmChunk.size)
            }
            Log.d(TAG, "│  🔊 Playback loop ended")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  HELPERS
    // ═══════════════════════════════════════════════════════════════

    private fun flushPlaybackQueue() {
        var drained = 0
        while (playbackQueue.tryReceive().isSuccess) { drained++ }
        if (drained > 0) Log.d(TAG, "│  🗑️ Flushed $drained audio chunks from playback queue")

        // Also flush the AudioTrack hardware buffer
        audioTrack?.let {
            try {
                it.pause()
                it.flush()
                it.play()
            } catch (_: Exception) {}
        }
    }

    private fun updateState(newState: SessionState) {
        val old = state.getAndSet(newState)
        if (old != newState) {
            Log.i(TAG, "│  State: $old → $newState")
            callback?.onStateChanged(newState)
        }
    }

    /**
     * Registers the full Aura 5.0 tool library with Gemini Live.
     * All 5 tools declared upfront so the model can plan multi-step flows.
     */
    private fun buildToolDeclarations(): JsonArray {
        fun strParam(name: String, desc: String) = JsonObject().apply {
            addProperty("type", "string")
            addProperty("description", desc)
        }.let { name to it }

        fun tool(name: String, desc: String, vararg params: Pair<String, JsonObject>): JsonObject {
            return JsonObject().apply {
                addProperty("name", name)
                addProperty("description", desc)
                add("parameters", JsonObject().apply {
                    addProperty("type", "object")
                    add("properties", JsonObject().apply {
                        params.forEach { (pName, pObj) -> add(pName, pObj) }
                    })
                    add("required", JsonArray().apply {
                        params.forEach { (pName, _) -> add(pName) }
                    })
                })
            }
        }

        return JsonArray().apply {
            add(JsonObject().apply {
                add("functionDeclarations", JsonArray().apply {

                    // ── 1. execute_tap ──
                    add(tool(
                        "execute_tap",
                        "Taps a UI element on screen by its visible text label or description. " +
                        "Use this to press buttons, icons, list items, and menu entries.",
                        strParam("target_text",
                            "The exact or approximate visible text of the element to tap. " +
                            "Examples: 'Accept Order', 'Search', 'OK', 'WhatsApp'")
                    ))

                    // ── 2. open_app ──
                    add(tool(
                        "open_app",
                        "Opens an installed app by its name. Use this at the start of any " +
                        "multi-step task that requires a specific app.",
                        strParam("app_name",
                            "The display name of the app to open. " +
                            "Examples: 'Swiggy', 'YouTube', 'WhatsApp', 'Google Maps', 'PhonePe'")
                    ))

                    // ── 3. global_action ──
                    add(tool(
                        "global_action",
                        "Performs a system-level navigation action. Use 'home' to go to the " +
                        "home screen, 'back' to go back one screen, 'recents' to show recent apps.",
                        strParam("action",
                            "The system action to perform. Must be exactly one of: 'home', 'back', 'recents'")
                    ))

                    // ── 4. scroll ──
                    add(tool(
                        "scroll",
                        "Scrolls the current screen in a direction. Use 'down' to see content " +
                        "below (e.g. more search results), 'up' to go back up.",
                        strParam("direction",
                            "Scroll direction. Must be exactly one of: 'up', 'down', 'left', 'right'")
                    ))

                    // ── 5. type_text ──
                    add(tool(
                        "type_text",
                        "Types text into an input field on screen. First identifies the field " +
                        "by target_field, taps it to focus, then inputs the text.",
                        strParam("text",
                            "The exact text to type into the field. Examples: 'Masala Dosa', '9876543210'"),
                        strParam("target_field",
                            "Description of the input field to type into. " +
                            "Examples: 'Search', 'Search for food', 'Phone number', 'Message'")
                    ))
                })
            })
        }
    }
}
