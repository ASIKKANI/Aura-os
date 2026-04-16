package com.aura.edge

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Base64
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * ══════════════════════════════════════════════════════════════════════════
 *  SemanticFlattener — The UI Compressor
 * ══════════════════════════════════════════════════════════════════════════
 *
 *  Takes a raw Android AccessibilityNodeInfo tree (often 50KB+ of XML junk)
 *  and compresses it into a hyper-efficient token string that fits within
 *  Gemini's context window without wasting tokens on FrameLayouts.
 *
 *  Pruning Rules:
 *    Keep a node ONLY if:
 *      a) isClickable == true  OR  isLongClickable == true
 *      b) text is not null/empty
 *      c) contentDescription is not null/empty
 *
 *  Output Format:
 *    [i:{syntheticId}|t:{ClassType}|txt:{Text}|desc:{Description}|b:{L},{T},{R},{B}]
 *
 *  Example:
 *    [i:a1b2|t:Btn|txt:Confirm Order|desc:|b:500,1000,700,1100]
 * ══════════════════════════════════════════════════════════════════════════
 */
class SemanticFlattener {

    companion object {
        private const val TAG = "AuraFlatten"
    }

    /**
     * A flattened representation of a single meaningful UI node.
     */
    data class FlatNode(
        val id: String,               // Synthetic short hash ID
        val type: String,             // Abbreviated class name (Btn, Txt, Img, etc.)
        val text: String,             // Visible text (may be empty)
        val description: String,      // contentDescription (may be empty)
        val bounds: Rect,             // Screen coordinates
        val isClickable: Boolean,
        val isScrollable: Boolean,
        val isEditable: Boolean,
        val isCheckable: Boolean,
        val isChecked: Boolean
    ) {
        /** Center X coordinate */
        val centerX: Int get() = (bounds.left + bounds.right) / 2

        /** Center Y coordinate */
        val centerY: Int get() = (bounds.top + bounds.bottom) / 2

        /** Token string representation for injection into LLM context */
        fun toToken(): String {
            val parts = mutableListOf<String>()
            parts.add("i:$id")
            parts.add("t:$type")
            if (text.isNotEmpty()) parts.add("txt:$text")
            if (description.isNotEmpty()) parts.add("desc:$description")
            parts.add("b:${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}")

            // Add interaction flags for richer context
            val flags = mutableListOf<String>()
            if (isClickable) flags.add("click")
            if (isScrollable) flags.add("scroll")
            if (isEditable) flags.add("edit")
            if (isCheckable) flags.add(if (isChecked) "checked" else "unchecked")

            // Tag popup/ad dismissal elements so the model can spot them instantly
            val dismissKeywords = setOf(
                "skip", "close", "not now", "later", "no thanks", "dismiss",
                "cancel", "skip trial", "maybe later", "skip ad", "✕", "×", "✖",
                "no, thanks", "remind me later", "got it", "allow once"
            )
            val combinedLabel = (text + " " + description).lowercase().trim()
            // Also flag single-char text that looks like a close button ("X", "x")
            val isSingleCharClose = (text.trim().length == 1 && text.trim().lowercase() == "x")
                    || (description.trim().length == 1 && description.trim().lowercase() == "x")
            if (dismissKeywords.any { combinedLabel.contains(it) } || isSingleCharClose) {
                flags.add("DISMISS")
            }

            if (flags.isNotEmpty()) parts.add("f:${flags.joinToString(",")}")

            return "[${parts.joinToString("|")}]"
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  PUBLIC API
    // ═══════════════════════════════════════════════════════════════

    /**
     * Recursively flattens the accessibility tree into a list of [FlatNode]s.
     *
     * @param rootNode The root AccessibilityNodeInfo from the active window
     * @return List of semantically meaningful nodes, pruned of structural noise
     */
    fun flatten(rootNode: AccessibilityNodeInfo?): List<FlatNode> {
        if (rootNode == null) return emptyList()

        val result = mutableListOf<FlatNode>()
        walkTree(rootNode, result)

        Log.d(TAG, "Flattened: ${result.size} nodes from tree")
        return result
    }

    /**
     * Convenience: returns the concatenated token string ready for LLM injection.
     */
    fun flattenToString(rootNode: AccessibilityNodeInfo?): String {
        return flatten(rootNode).joinToString("\n") { it.toToken() }
    }

    // ═══════════════════════════════════════════════════════════════
    //  SCREENSHOT COMPRESSION (Phase 3)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Compresses a screenshot bitmap for multimodal injection into Gemini.
     *
     * Downscales to max 720px on longest edge, JPEG at 65% quality.
     * Target: <150KB output.
     *
     * @return Base64-encoded JPEG string, or null if compression fails
     */
    fun compressScreenshot(bitmap: Bitmap): String? {
        return try {
            // Calculate scale factor — max 720px on longest edge
            val maxDim = 720
            val scale = if (bitmap.width > bitmap.height) {
                maxDim.toFloat() / bitmap.width
            } else {
                maxDim.toFloat() / bitmap.height
            }

            val scaledBitmap = if (scale < 1.0f) {
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt(),
                    (bitmap.height * scale).toInt(),
                    true // bilinear filtering
                )
            } else {
                bitmap
            }

            // Compress to JPEG
            val stream = ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 65, stream)
            val bytes = stream.toByteArray()

            // Verify size constraint
            if (bytes.size > 150_000) {
                // Retry at lower quality
                stream.reset()
                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 45, stream)
            }

            val finalBytes = stream.toByteArray()
            Log.d(TAG, "Screenshot compressed: ${finalBytes.size} bytes " +
                    "(${scaledBitmap.width}x${scaledBitmap.height})")

            // Clean up scaled bitmap if we created a new one
            if (scaledBitmap !== bitmap) {
                scaledBitmap.recycle()
            }

            Base64.encodeToString(finalBytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Screenshot compression failed: ${e.message}")
            null
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  TREE WALKER
    // ═══════════════════════════════════════════════════════════════

    private fun walkTree(node: AccessibilityNodeInfo, result: MutableList<FlatNode>) {
        // Check if this node is semantically meaningful
        val nodeText = node.text?.toString()?.trim() ?: ""
        val nodeDesc = node.contentDescription?.toString()?.trim() ?: ""
        val isClickable = node.isClickable || node.isLongClickable
        val isEditable = node.isEditable
        val isCheckable = node.isCheckable

        val isMeaningful = isClickable
                || nodeText.isNotEmpty()
                || nodeDesc.isNotEmpty()
                || isEditable
                || isCheckable

        if (isMeaningful) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)

            // Skip nodes with zero-area bounds (invisible)
            if (bounds.width() > 0 && bounds.height() > 0) {
                result.add(
                    FlatNode(
                        id = generateSyntheticId(bounds, nodeText, nodeDesc),
                        type = abbreviateClassName(node.className?.toString()),
                        text = nodeText,
                        description = nodeDesc,
                        bounds = bounds,
                        isClickable = isClickable,
                        isScrollable = node.isScrollable,
                        isEditable = isEditable,
                        isCheckable = isCheckable,
                        isChecked = node.isChecked
                    )
                )
            }
        }

        // Recurse into children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            walkTree(child, result)
            child.recycle()
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  HELPERS
    // ═══════════════════════════════════════════════════════════════

    /**
     * Generates a stable 4-char hex ID from bounds + text.
     * This bypasses obfuscated Android Resource IDs entirely.
     */
    private fun generateSyntheticId(bounds: Rect, text: String, desc: String): String {
        val input = "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}|$text|$desc"
        return try {
            val digest = MessageDigest.getInstance("MD5")
            val hash = digest.digest(input.toByteArray())
            hash.take(2).joinToString("") { "%02x".format(it) }  // 4 hex chars
        } catch (_: Exception) {
            // Fallback: simple hash
            Integer.toHexString(input.hashCode()).take(4)
        }
    }

    /**
     * Abbreviates Android class names for token efficiency.
     * "android.widget.Button" → "Btn"
     */
    private fun abbreviateClassName(className: String?): String {
        if (className == null) return "View"

        val simple = className.substringAfterLast(".")
        return when {
            simple.contains("Button", ignoreCase = true) -> "Btn"
            simple.contains("TextView", ignoreCase = true) -> "Txt"
            simple.contains("EditText", ignoreCase = true) -> "Edit"
            simple.contains("ImageView", ignoreCase = true) -> "Img"
            simple.contains("ImageButton", ignoreCase = true) -> "ImgBtn"
            simple.contains("CheckBox", ignoreCase = true) -> "Chk"
            simple.contains("RadioButton", ignoreCase = true) -> "Radio"
            simple.contains("Switch", ignoreCase = true) -> "Switch"
            simple.contains("ToggleButton", ignoreCase = true) -> "Toggle"
            simple.contains("SeekBar", ignoreCase = true) -> "Seek"
            simple.contains("ProgressBar", ignoreCase = true) -> "Prog"
            simple.contains("Spinner", ignoreCase = true) -> "Spin"
            simple.contains("RecyclerView", ignoreCase = true) -> "List"
            simple.contains("ListView", ignoreCase = true) -> "List"
            simple.contains("ScrollView", ignoreCase = true) -> "Scroll"
            simple.contains("WebView", ignoreCase = true) -> "Web"
            simple.contains("TabLayout", ignoreCase = true) -> "Tab"
            simple.contains("Toolbar", ignoreCase = true) -> "Toolbar"
            simple.contains("ViewGroup", ignoreCase = true) -> "Group"
            simple.contains("FrameLayout", ignoreCase = true) -> "Frame"
            simple.contains("LinearLayout", ignoreCase = true) -> "Linear"
            simple.contains("RelativeLayout", ignoreCase = true) -> "Rel"
            simple.contains("ConstraintLayout", ignoreCase = true) -> "Cstr"
            else -> simple.take(8)  // Truncate long names
        }
    }
}
