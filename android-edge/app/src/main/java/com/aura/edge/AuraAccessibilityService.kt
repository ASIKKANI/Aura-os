package com.aura.edge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ══════════════════════════════════════════════════════════════════════════
 *  AuraAccessibilityService — The Eyes & Hands (Aura 5.0)
 * ══════════════════════════════════════════════════════════════════════════
 *
 *  Full autonomous agent toolkit:
 *
 *  👁️ EYES:  Monitor window state changes → flatten UI tree → inject context
 *  🖐️ HANDS: execute_tap, open_app, global_action, scroll, type_text
 *  🔁 REACT: forceScreenUpdate() feeds new screen state back to Gemini
 *            after each tool action to power the ReAct reasoning loop
 * ══════════════════════════════════════════════════════════════════════════
 */
class AuraAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AuraAccess"

        /** Debounce for organic screen change events (ms) */
        private const val DEBOUNCE_MS = 2500L

        /** Tap gesture duration (ms) */
        private const val TAP_DURATION_MS = 50L

        /** If a force-update ran within this window, skip the debounced one */
        private const val FORCE_UPDATE_COOLDOWN_MS = 5000L

        /** Singleton for MainActivity and tool call wiring */
        @Volatile
        var instance: AuraAccessibilityService? = null
            private set

        fun rebindToSession() {
            val svc = instance
            if (svc != null) {
                svc.bindToLiveSession()
            } else {
                Log.w(TAG, "rebindToSession: accessibility service not connected yet")
            }
        }

        /**
         * Well-known package names for common apps used in India.
         * Used as the fast path in open_app before scanning the full package list.
         */
        private val KNOWN_PACKAGES = mapOf(
            "whatsapp"       to "com.whatsapp",
            "swiggy"         to "in.swiggy.android",
            "zomato"         to "com.application.zomato",
            "youtube"        to "com.google.android.youtube",
            "google maps"    to "com.google.android.apps.maps",
            "maps"           to "com.google.android.apps.maps",
            "phonepe"        to "com.phonepe.app",
            "paytm"          to "net.one97.paytm",
            "gpay"           to "com.google.android.apps.nbu.paisa.user",
            "google pay"     to "com.google.android.apps.nbu.paisa.user",
            "uber"           to "com.ubercab",
            "ola"            to "com.olacabs.customer",
            "amazon"         to "com.amazon.mShop.android.shopping",
            "flipkart"       to "com.flipkart.android",
            "chrome"         to "com.android.chrome",
            "settings"       to "com.android.settings",
            "camera"         to "com.android.camera2",
            "phone"          to "com.android.dialer",
            "messages"       to "com.google.android.apps.messaging",
            "instagram"      to "com.instagram.android",
            "facebook"       to "com.facebook.katana",
            "twitter"        to "com.twitter.android",
            "x"              to "com.twitter.android",
            "telegram"       to "org.telegram.messenger",
            "spotify"        to "com.spotify.music",
            "netflix"        to "com.netflix.mediaclient",
            "blinkit"        to "com.grofers.customerapp",
            "dunzo"          to "com.dunzo.user",
            "rapido"         to "com.rapido.passenger",
            "meesho"         to "com.meesho.supply",
            "myntra"         to "com.myntra.android",
            "cred"           to "com.dreamplug.androidapp",
            "groww"          to "com.nextbillion.groww",
            "upstox"         to "in.upstox.app",
            "clock"          to "com.google.android.deskclock",
            "calculator"     to "com.google.android.calculator",
            "gmail"          to "com.google.android.gm",
            "google"         to "com.google.android.googlequicksearchbox",
            "photos"         to "com.google.android.apps.photos",
            "drive"          to "com.google.android.apps.docs",
            "meet"           to "com.google.android.apps.meetings",
            "zoom"           to "us.zoom.videomeetings",
            "truecaller"     to "com.truecaller",
        )
    }

    // ── Core components ──
    private val flattener = SemanticFlattener()
    private val resolver  = TargetResolver()
    private val scope     = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val handler   = Handler(Looper.getMainLooper())

    // ── Screen state ──
    private var lastFlattenedNodes: List<SemanticFlattener.FlatNode> = emptyList()
    private var lastFlattenedString: String = ""
    private var lastPackageName: String = ""
    private val isProcessing = AtomicBoolean(false)

    // ── Dedup: track when we last did a forced (post-action) screen scan ──
    @Volatile private var lastForceUpdateMs = 0L

    // ── Debounce runnable ──
    private val screenChangeRunnable = Runnable { onDebouncedScreenChange() }

    // ── Telemetry (Phase 5) ──
    var telemetry: GCPTelemetry? = null

    // ═══════════════════════════════════════════════════════════════
    //  LIFECYCLE
    // ═══════════════════════════════════════════════════════════════

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "┌─── AuraAccessibilityService CONNECTED (Aura 5.0) ───")
        Log.i(TAG, "│  Tools: execute_tap · open_app · global_action · scroll · type_text")
        bindToLiveSession()
    }

    override fun onDestroy() {
        instance = null
        scope.cancel()
        handler.removeCallbacksAndMessages(null)
        Log.i(TAG, "└─── AuraAccessibilityService DESTROYED ───")
        super.onDestroy()
    }

    // ═══════════════════════════════════════════════════════════════
    //  SCREEN CHANGE DETECTION (The Eyes)
    // ═══════════════════════════════════════════════════════════════

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                event.packageName?.toString()?.let { pkg ->
                    if (pkg != "com.aura.edge") lastPackageName = pkg
                }
                handler.removeCallbacks(screenChangeRunnable)
                handler.postDelayed(screenChangeRunnable, DEBOUNCE_MS)
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "│  ⚠️ Accessibility service interrupted")
    }

    // ═══════════════════════════════════════════════════════════════
    //  DEBOUNCED ORGANIC SCREEN CHANGE
    // ═══════════════════════════════════════════════════════════════

    private fun onDebouncedScreenChange() {
        // Skip if a post-action force-update ran recently (avoids duplicate injection)
        if (System.currentTimeMillis() - lastForceUpdateMs < FORCE_UPDATE_COOLDOWN_MS) {
            Log.d(TAG, "│  ⏭️ Skipping debounced scan — force update ran recently")
            return
        }
        if (isProcessing.getAndSet(true)) return

        scope.launch {
            try {
                scanAndInject()
            } finally {
                isProcessing.set(false)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  FORCE SCREEN UPDATE (feeds new state to ReAct loop)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Immediately scans the current screen and injects context into Gemini.
     * Called after every tool execution so the model can plan the next step.
     * Cancels any pending debounced scan to avoid double-injection.
     * Retries up to [maxRetries] times when the window returns 0 nodes (e.g. during app launch).
     */
    private suspend fun forceScreenUpdate(maxRetries: Int = 3) {
        lastForceUpdateMs = System.currentTimeMillis()
        handler.removeCallbacks(screenChangeRunnable) // cancel debounced scan
        repeat(maxRetries) { attempt ->
            scanAndInject()
            if (lastFlattenedNodes.isNotEmpty()) {
                Log.d(TAG, "│  🔄 Force screen update sent to Gemini (attempt ${attempt + 1})")
                return
            }
            if (attempt < maxRetries - 1) {
                Log.d(TAG, "│  ⏳ Empty screen on attempt ${attempt + 1}, retrying in 800ms…")
                delay(800)
            }
        }
        Log.w(TAG, "│  ⚠️ forceScreenUpdate: screen still empty after $maxRetries attempts")
    }

    /** Core scan-and-inject routine used by both debounced and forced paths */
    private suspend fun scanAndInject() {
        try {
            val rootNode = rootInActiveWindow ?: return
            val nodes = flattener.flatten(rootNode)
            val flatString = nodes.joinToString("\n") { it.toToken() }

            lastFlattenedNodes = nodes
            lastFlattenedString = flatString
            rootNode.packageName?.toString()?.let { pkg ->
                if (pkg.isNotEmpty() && pkg != "com.aura.edge") lastPackageName = pkg
            }

            Log.d(TAG, "│  📋 Scanned: ${nodes.size} nodes · $lastPackageName")

            // Screenshot (API 30+ only)
            val screenshot = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                captureScreenshot()
            } else null

            LiveSessionHolder.liveManager?.injectUIContext(flatString, screenshot)
            telemetry?.onScreenChanged(lastPackageName, flatString)
        } catch (e: Exception) {
            Log.e(TAG, "│  ❌ Scan error: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  SCREENSHOT CAPTURE
    // ═══════════════════════════════════════════════════════════════

    private suspend fun captureScreenshot(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return suspendCancellableCoroutine { continuation ->
            try {
                takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            try {
                                val bmp = Bitmap.wrapHardwareBuffer(
                                    screenshot.hardwareBuffer, screenshot.colorSpace
                                )
                                screenshot.hardwareBuffer.close()
                                if (bmp != null) {
                                    val sw = bmp.copy(Bitmap.Config.ARGB_8888, false)
                                    bmp.recycle()
                                    val b64 = flattener.compressScreenshot(sw)
                                    sw.recycle()
                                    if (continuation.isActive) continuation.resumeWith(Result.success(b64))
                                } else {
                                    if (continuation.isActive) continuation.resumeWith(Result.success(null))
                                }
                            } catch (e: Exception) {
                                if (continuation.isActive) continuation.resumeWith(Result.success(null))
                            }
                        }
                        override fun onFailure(errorCode: Int) {
                            if (continuation.isActive) continuation.resumeWith(Result.success(null))
                        }
                    })
            } catch (e: Exception) {
                if (continuation.isActive) continuation.resumeWith(Result.success(null))
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  TOOL: execute_tap
    // ═══════════════════════════════════════════════════════════════

    fun handleExecuteTap(callId: String, targetText: String, liveManager: LiveSDKManager) {
        scope.launch {
            val t0 = System.currentTimeMillis()
            try {
                val result = resolver.resolve(targetText, lastFlattenedNodes)

                if (resolver.isDangerousAction(targetText)) {
                    Log.w(TAG, "│  🛡️ Dangerous action: '$targetText'")
                    liveManager.sendToolResponse(callId, "execute_tap", mapOf(
                        "status"  to "REQUIRE_VOICE_CONFIRMATION",
                        "message" to "The action '$targetText' is sensitive — ask the user to confirm."
                    ))
                    telemetry?.checkGuardrail(targetText, lastPackageName)
                    return@launch
                }

                val success = suspendCancellableCoroutine<Boolean> { cont ->
                    executeTap(result.x, result.y) { ok ->
                        if (cont.isActive) cont.resumeWith(Result.success(ok))
                    }
                }

                val latencyMs = System.currentTimeMillis() - t0

                if (success) {
                    // Record screen fingerprint BEFORE waiting for the screen to settle
                    val preActionScreenLen = lastFlattenedString.length

                    delay(800)
                    forceScreenUpdate()

                    // Detect whether the tap actually changed the screen
                    val screenChanged = lastFlattenedString.length != preActionScreenLen
                        && lastFlattenedNodes.isNotEmpty()

                    val responseMap = if (screenChanged) {
                        mapOf(
                            "status"         to "SUCCESS",
                            "action"         to "Tapped '${result.matchedText}' at (${result.x}, ${result.y})",
                            "confidence"     to result.confidence.toString(),
                            "latency_ms"     to latencyMs.toString(),
                            "screen_changed" to "true"
                        )
                    } else {
                        mapOf(
                            "status"         to "SUCCESS",
                            "action"         to "Tapped '${result.matchedText}' at (${result.x}, ${result.y})",
                            "confidence"     to result.confidence.toString(),
                            "latency_ms"     to latencyMs.toString(),
                            "screen_changed" to "false",
                            "hint"           to "Screen did not change after this tap. " +
                                               "The element may need a scroll to become interactive, " +
                                               "or a different element should be tapped."
                        )
                    }

                    liveManager.sendToolResponse(callId, "execute_tap", responseMap)
                    telemetry?.onTapExecuted(targetText, result.matchedText,
                        result.x, result.y, result.confidence, latencyMs, lastPackageName)
                } else {
                    liveManager.sendToolResponse(callId, "execute_tap", mapOf(
                        "status"  to "FAILED",
                        "message" to "Gesture failed at (${result.x}, ${result.y})"
                    ))
                }

                Log.i(TAG, "│  ⏱️ execute_tap latency: ${latencyMs}ms")

            } catch (e: TargetResolver.AmbiguousTargetException) {
                val candidates = e.candidates.joinToString(", ") { "'${it.first}' (${"%.2f".format(it.second)})" }
                liveManager.sendToolResponse(callId, "execute_tap", mapOf(
                    "status"  to "AMBIGUOUS",
                    "message" to "Multiple matches for '${e.targetText}': $candidates. Ask user to clarify."
                ))
            } catch (e: Exception) {
                Log.e(TAG, "│  ❌ execute_tap error: ${e.message}", e)
                liveManager.sendToolResponse(callId, "execute_tap", mapOf(
                    "status"  to "ERROR",
                    "message" to "execute_tap failed: ${e.message}"
                ))
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  TOOL: open_app
    // ═══════════════════════════════════════════════════════════════

    fun handleOpenApp(callId: String, appName: String, liveManager: LiveSDKManager) {
        scope.launch {
            try {
                val intent = findLaunchIntent(appName)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                    startActivity(intent)
                    Log.i(TAG, "│  📱 Launched: $appName")
                    liveManager.sendToolResponse(callId, "open_app", mapOf(
                        "status"  to "SUCCESS",
                        "message" to "Opened $appName"
                    ))
                    // Wait for the app to load before feeding back screen state
                    delay(1500)
                    forceScreenUpdate()
                } else {
                    Log.w(TAG, "│  ⚠️ App not found: $appName")
                    liveManager.sendToolResponse(callId, "open_app", mapOf(
                        "status"  to "NOT_FOUND",
                        "message" to "App '$appName' is not installed on this device."
                    ))
                }
            } catch (e: Exception) {
                Log.e(TAG, "│  ❌ open_app error: ${e.message}", e)
                liveManager.sendToolResponse(callId, "open_app", mapOf(
                    "status"  to "ERROR",
                    "message" to "Failed to open $appName: ${e.message}"
                ))
            }
        }
    }

    /**
     * Resolves an app name to a launch Intent.
     * Strategy: 1) KNOWN_PACKAGES exact, 2) KNOWN_PACKAGES fuzzy, 3) PackageManager scan.
     */
    private fun findLaunchIntent(appName: String): Intent? {
        val normalized = appName.lowercase().trim()
        val pm = packageManager

        // 1. Exact match in known packages
        KNOWN_PACKAGES[normalized]?.let { pkg ->
            pm.getLaunchIntentForPackage(pkg)?.let { return it }
        }

        // 2. Partial match in known packages (e.g. "swiggy delivery" → "swiggy")
        KNOWN_PACKAGES.entries.firstOrNull { (key, _) ->
            normalized.contains(key) || key.contains(normalized)
        }?.let { (_, pkg) ->
            pm.getLaunchIntentForPackage(pkg)?.let { return it }
        }

        // 3. Full package manager scan — matches by app display label
        return try {
            val mainIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolvedApps = pm.queryIntentActivities(mainIntent, PackageManager.GET_META_DATA)
            resolvedApps.firstOrNull { ri ->
                val label = ri.loadLabel(pm).toString().lowercase()
                label == normalized || label.contains(normalized) || normalized.contains(label)
            }?.let { ri ->
                pm.getLaunchIntentForPackage(ri.activityInfo.packageName)
            }
        } catch (e: Exception) {
            Log.w(TAG, "│  ⚠️ Package scan error: ${e.message}")
            null
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  TOOL: global_action
    // ═══════════════════════════════════════════════════════════════

    fun handleGlobalAction(callId: String, action: String, liveManager: LiveSDKManager) {
        val actionId = when (action.lowercase().trim()) {
            "home"    -> GLOBAL_ACTION_HOME
            "back"    -> GLOBAL_ACTION_BACK
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            else -> {
                liveManager.sendToolResponse(callId, "global_action", mapOf(
                    "status"  to "ERROR",
                    "message" to "Unknown action '$action'. Use: home, back, recents"
                ))
                return
            }
        }

        val success = performGlobalAction(actionId)
        Log.i(TAG, "│  🌐 global_action($action) → success=$success")

        liveManager.sendToolResponse(callId, "global_action", mapOf(
            "status" to if (success) "SUCCESS" else "FAILED",
            "action" to action
        ))

        scope.launch {
            delay(600)
            forceScreenUpdate()
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  TOOL: scroll
    // ═══════════════════════════════════════════════════════════════

    fun handleScroll(callId: String, direction: String, liveManager: LiveSDKManager) {
        scope.launch {
            try {
                val dir = direction.lowercase().trim()

                // For left/right — always use gesture (no standard AccessibilityAction)
                if (dir == "left" || dir == "right") {
                    val preLen = lastFlattenedString.length
                    executeSwipeGesture(dir)
                    delay(800)
                    forceScreenUpdate()
                    val changed = lastFlattenedString.length != preLen && lastFlattenedNodes.isNotEmpty()
                    liveManager.sendToolResponse(callId, "scroll", mapOf(
                        "status"         to "SUCCESS",
                        "direction"      to dir,
                        "method"         to "gesture",
                        "screen_changed" to changed.toString()
                    ))
                    return@launch
                }

                // For up/down — prefer AccessibilityNodeInfo action on scrollable node
                val scrollActionId = when (dir) {
                    "down" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    "up"   -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    else   -> {
                        liveManager.sendToolResponse(callId, "scroll", mapOf(
                            "status"  to "ERROR",
                            "message" to "Unknown direction '$direction'"
                        ))
                        return@launch
                    }
                }

                val root = rootInActiveWindow
                val scrollable = root?.let { findScrollableNode(it) }

                // Try AccessibilityNode scroll first; if it returns false (node doesn't truly
                // support it — e.g. Instagram Reels / ViewPager2) fall through to gesture swipe
                val accessibilityScrolled = scrollable?.performAction(scrollActionId) == true
                if (accessibilityScrolled) {
                    Log.i(TAG, "│  📜 scroll($dir) via AccessibilityNode → SUCCESS")
                    val preLen = lastFlattenedString.length
                    delay(800) // 800ms settle: let list animation complete before scanning
                    forceScreenUpdate()
                    val changed = lastFlattenedString.length != preLen && lastFlattenedNodes.isNotEmpty()
                    liveManager.sendToolResponse(callId, "scroll", mapOf(
                        "status"         to "SUCCESS",
                        "direction"      to dir,
                        "method"         to "accessibility_action",
                        "screen_changed" to changed.toString()
                    ))
                } else {
                    // AccessibilityNode scroll unavailable or failed → gesture swipe
                    if (scrollable != null) {
                        Log.w(TAG, "│  📜 scroll($dir): performAction returned false, using gesture fallback")
                    } else {
                        Log.i(TAG, "│  📜 scroll($dir): no scrollable node, using gesture")
                    }
                    val preLen = lastFlattenedString.length
                    executeSwipeGesture(dir)
                    delay(800) // 800ms settle: page swipe animations (Reels, Shorts) need time
                    forceScreenUpdate()
                    val changed = lastFlattenedString.length != preLen && lastFlattenedNodes.isNotEmpty()
                    liveManager.sendToolResponse(callId, "scroll", mapOf(
                        "status"         to "SUCCESS",
                        "direction"      to dir,
                        "method"         to "gesture",
                        "screen_changed" to changed.toString()
                    ))
                }

            } catch (e: Exception) {
                Log.e(TAG, "│  ❌ scroll error: ${e.message}", e)
                liveManager.sendToolResponse(callId, "scroll", mapOf(
                    "status"  to "ERROR",
                    "message" to "Scroll failed: ${e.message}"
                ))
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  TOOL: type_text
    // ═══════════════════════════════════════════════════════════════

    fun handleTypeText(callId: String, text: String, targetField: String, liveManager: LiveSDKManager) {
        scope.launch {
            try {
                val preLen = lastFlattenedString.length

                // ── Step 1: Find and tap the input field to give it focus ──
                var tapOk = false
                try {
                    val result = resolver.resolve(targetField, lastFlattenedNodes)
                    tapOk = suspendCancellableCoroutine { cont ->
                        executeTap(result.x, result.y) { ok ->
                            if (cont.isActive) cont.resumeWith(Result.success(ok))
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "│  ⚠️ Could not resolve target field '$targetField': ${e.message}")
                    // Still try to type if something already has focus
                }

                delay(350) // Wait for keyboard and focus animation

                // ── Step 2: Find the focused editable node and set text ──
                val root = rootInActiveWindow
                val inputNode = root?.let { findFocusedEditableNode(it) }

                var textSetOk = false

                if (inputNode != null) {
                    val args = Bundle().apply {
                        putCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text
                        )
                    }
                    textSetOk = inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    Log.i(TAG, "│  ⌨️ type_text('$text') via ACTION_SET_TEXT → $textSetOk")

                    if (!textSetOk) {
                        // ACTION_SET_TEXT failed — fall through to clipboard
                        Log.w(TAG, "│  ⚠️ ACTION_SET_TEXT failed, falling back to clipboard")
                    }
                }

                // ── Step 3: Clipboard fallback if ACTION_SET_TEXT failed or no focused node ──
                if (!textSetOk) {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(ClipData.newPlainText("aura_input", text))
                        val focusedNode = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                        if (focusedNode != null && focusedNode.isEditable) {
                            focusedNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                            textSetOk = true
                            Log.i(TAG, "│  ⌨️ type_text('$text') via clipboard paste")
                        }
                    }
                    if (!textSetOk) {
                        liveManager.sendToolResponse(callId, "type_text", mapOf(
                            "status"  to "FAILED",
                            "message" to "Could not find a focused input field for '$targetField'. " +
                                         "Field tap successful: $tapOk"
                        ))
                        return@launch
                    }
                }

                delay(300) // Let keyboard/suggestions settle

                // ── Step 4: Auto-submit — look for Search / Go / Done button and tap it ──
                val submitLabels = setOf("search", "go", "done", "submit", "send", "find", "ok")
                val rootAfterType = rootInActiveWindow
                val submitNode = rootAfterType?.let { findSubmitButton(it, submitLabels) }

                var submitted = false
                if (submitNode != null) {
                    submitted = submitNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.i(TAG, "│  🔍 Auto-submit via '${submitNode.text ?: submitNode.contentDescription}' → $submitted")
                    if (submitted) delay(400) // wait for search results to load
                }

                forceScreenUpdate()
                val screenChanged = lastFlattenedString.length != preLen && lastFlattenedNodes.isNotEmpty()

                liveManager.sendToolResponse(callId, "type_text", mapOf(
                    "status"         to "SUCCESS",
                    "text"           to text,
                    "field"          to targetField,
                    "submitted"      to submitted.toString(),
                    "screen_changed" to screenChanged.toString()
                ) + if (!submitted) mapOf(
                    "hint" to "Text typed but submit button not found. " +
                              "Call execute_tap with the visible Search, Go or Submit button text."
                                ) else emptyMap<String, String>())

            } catch (e: Exception) {
                Log.e(TAG, "│  ❌ type_text error: ${e.message}", e)
                liveManager.sendToolResponse(callId, "type_text", mapOf(
                    "status"  to "ERROR",
                    "message" to "type_text failed: ${e.message}"
                ))
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  PHYSICAL TAP DISPATCHER
    // ═══════════════════════════════════════════════════════════════

    fun executeTap(x: Int, y: Int, callback: ((Boolean) -> Unit)? = null) {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, TAP_DURATION_MS)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(desc: GestureDescription?) {
                Log.i(TAG, "│  👆 Tap ($x, $y) → SUCCESS")
                callback?.invoke(true)
            }
            override fun onCancelled(desc: GestureDescription?) {
                Log.w(TAG, "│  ⚠️ Tap ($x, $y) → CANCELLED")
                callback?.invoke(false)
            }
        }, null)

        if (!dispatched) {
            Log.e(TAG, "│  ❌ dispatchGesture returned false for ($x, $y)")
            callback?.invoke(false)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  HELPERS
    // ═══════════════════════════════════════════════════════════════

    /** Recursively finds the first scrollable node in the accessibility tree */
    private fun findScrollableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findScrollableNode(child)
            if (found != null) return found
        }
        return null
    }

    /**
     * Finds a clickable button whose text or contentDescription matches one of the [labels].
     * Used by [handleTypeText] to auto-tap "Search", "Go", "Done" etc. after typing.
     * Excludes editable nodes (input fields themselves) to avoid re-tapping the search bar.
     */
    private fun findSubmitButton(
        node: AccessibilityNodeInfo,
        labels: Set<String>
    ): AccessibilityNodeInfo? {
        if (node.isClickable && !node.isEditable) {
            val label = (node.text?.toString() ?: node.contentDescription?.toString() ?: "")
                .trim().lowercase()
            if (labels.any { label.contains(it) }) return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findSubmitButton(child, labels)
            if (found != null) return found
        }
        return null
    }

    /**
     * Finds the currently focused editable node.
     * Tries input-focus first, then tree-walk for editable+focused.
     */
    private fun findFocusedEditableNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // Input focus is the most reliable signal
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && focused.isEditable) return focused

        // If no input focus, walk tree looking for an editable node that has focus
        return findEditableNodeInTree(root)
    }

    private fun findEditableNodeInTree(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable && (node.isFocused || node.isSelected)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditableNodeInTree(child)
            if (found != null) return found
        }
        return null
    }

    /**
     * Dispatches a swipe gesture for scrolling when no scrollable node is available.
     * Convention: "scroll down" = swipe finger upward (content moves up into view).
     * Distance is 65% of screen height so full-page apps (Reels, Shorts) navigate correctly.
     */
    private fun executeSwipeGesture(direction: String) {
        val metrics = resources.displayMetrics
        val cx = metrics.widthPixels / 2f
        val cy = metrics.heightPixels / 2f
        // 65% of screen height — enough to trigger full-page swipe on Reels / Shorts
        val dist = metrics.heightPixels * 0.65f

        val path = Path()
        when (direction.lowercase()) {
            "down"  -> { path.moveTo(cx, cy + dist / 2); path.lineTo(cx, cy - dist / 2) }
            "up"    -> { path.moveTo(cx, cy - dist / 2); path.lineTo(cx, cy + dist / 2) }
            "left"  -> { path.moveTo(cx + dist / 2, cy); path.lineTo(cx - dist / 2, cy) }
            "right" -> { path.moveTo(cx - dist / 2, cy); path.lineTo(cx + dist / 2, cy) }
            else    -> return
        }

        // 400ms stroke duration — slow enough to be recognised as a swipe, not a tap
        val stroke = GestureDescription.StrokeDescription(path, 0, 400)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    // ═══════════════════════════════════════════════════════════════
    //  LIVE SESSION BINDING
    // ═══════════════════════════════════════════════════════════════

    private fun bindToLiveSession() {
        val manager = LiveSessionHolder.liveManager ?: run {
            Log.w(TAG, "│  ⚠️ bindToLiveSession: LiveSDKManager not ready yet")
            return
        }

        manager.onToolCall = { callId, functionName, args ->
            val lm = LiveSessionHolder.liveManager
            if (lm == null) {
                Log.e(TAG, "│  ❌ LiveSDKManager gone during tool call '$functionName'")
            } else {
                when (functionName) {
                    "execute_tap" -> {
                        val target = args["target_text"]?.toString() ?: ""
                        if (target.isNotEmpty()) handleExecuteTap(callId, target, lm)
                        else lm.sendToolResponse(callId, "execute_tap", mapOf(
                            "status" to "ERROR", "message" to "target_text is empty"
                        ))
                    }
                    "open_app" -> {
                        val appName = args["app_name"]?.toString() ?: ""
                        if (appName.isNotEmpty()) handleOpenApp(callId, appName, lm)
                        else lm.sendToolResponse(callId, "open_app", mapOf(
                            "status" to "ERROR", "message" to "app_name is empty"
                        ))
                    }
                    "global_action" -> {
                        val action = args["action"]?.toString() ?: ""
                        if (action.isNotEmpty()) handleGlobalAction(callId, action, lm)
                        else lm.sendToolResponse(callId, "global_action", mapOf(
                            "status" to "ERROR", "message" to "action is empty"
                        ))
                    }
                    "scroll" -> {
                        val dir = args["direction"]?.toString() ?: ""
                        if (dir.isNotEmpty()) handleScroll(callId, dir, lm)
                        else lm.sendToolResponse(callId, "scroll", mapOf(
                            "status" to "ERROR", "message" to "direction is empty"
                        ))
                    }
                    "type_text" -> {
                        val text  = args["text"]?.toString() ?: ""
                        val field = args["target_field"]?.toString() ?: "input"
                        if (text.isNotEmpty()) handleTypeText(callId, text, field, lm)
                        else lm.sendToolResponse(callId, "type_text", mapOf(
                            "status" to "ERROR", "message" to "text is empty"
                        ))
                    }
                    else -> {
                        Log.w(TAG, "│  ⚠️ Unknown tool: $functionName")
                        lm.sendToolResponse(callId, functionName, mapOf(
                            "status"  to "ERROR",
                            "message" to "Unknown tool '$functionName'"
                        ))
                    }
                }
            }
        }

        Log.i(TAG, "│  🔗 Bound — 5 tools active: execute_tap · open_app · global_action · scroll · type_text")
    }
}

/**
 * Process-wide singleton for sharing LiveSDKManager between
 * MainActivity and AuraAccessibilityService.
 */
object LiveSessionHolder {
    @Volatile
    var liveManager: LiveSDKManager? = null
}
