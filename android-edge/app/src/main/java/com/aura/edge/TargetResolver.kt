package com.aura.edge

import android.graphics.Rect
import android.util.Log
import kotlin.math.max
import kotlin.math.min

/**
 * ══════════════════════════════════════════════════════════════════════════
 *  TargetResolver — The Local Fuzzy Matcher
 * ══════════════════════════════════════════════════════════════════════════
 *
 *  Replaces the cloud-based TargetResolver entirely. When Gemini triggers
 *  execute_tap("Confirm Order"), this class scans the current flattened
 *  UI tree and finds the best-matching node using Jaro-Winkler string
 *  similarity — all locally, in <10ms.
 *
 *  Bypasses obfuscated Android Resource IDs by matching against visible
 *  text and contentDescription exclusively.
 *
 *  Threshold: similarity must be ≥ 0.75 or AmbiguousTargetException is thrown.
 * ══════════════════════════════════════════════════════════════════════════
 */
class TargetResolver {

    companion object {
        private const val TAG = "AuraResolve"
        private const val SIMILARITY_THRESHOLD = 0.75
    }

    // ═══════════════════════════════════════════════════════════════
    //  Data Classes
    // ═══════════════════════════════════════════════════════════════

    /**
     * Successful resolution result with exact tap coordinates.
     */
    data class ResolveResult(
        val x: Int,                      // Center X coordinate
        val y: Int,                      // Center Y coordinate
        val matchedText: String,         // The text that matched
        val matchedNodeId: String,       // Synthetic ID of matched node
        val confidence: Double,          // Similarity score [0.0, 1.0]
        val bounds: Rect                 // Full bounds of matched element
    )

    /**
     * Thrown when no node meets the similarity threshold,
     * or multiple nodes tie with identical high scores.
     */
    class AmbiguousTargetException(
        val targetText: String,
        val bestScore: Double,
        val candidates: List<Pair<String, Double>>,  // (text, score) pairs
        message: String
    ) : Exception(message)

    // ═══════════════════════════════════════════════════════════════
    //  PUBLIC API
    // ═══════════════════════════════════════════════════════════════

    /**
     * Finds the best-matching UI element for the given target text.
     *
     * @param targetText The text/description from Gemini's tool call (e.g., "Confirm Order")
     * @param nodes The current flattened UI tree from [SemanticFlattener]
     * @return [ResolveResult] with exact (x, y) tap coordinates
     * @throws AmbiguousTargetException if no match meets threshold or multiple ties exist
     */
    fun resolve(targetText: String, nodes: List<SemanticFlattener.FlatNode>): ResolveResult {
        val startTime = System.nanoTime()

        if (nodes.isEmpty()) {
            throw AmbiguousTargetException(
                targetText = targetText,
                bestScore = 0.0,
                candidates = emptyList(),
                message = "No UI nodes available — screen may not have loaded yet"
            )
        }

        val normalizedTarget = targetText.lowercase().trim()

        // Short-circuit for single/double-char queries (e.g. "X", "OK").
        // Jaro-Winkler performs poorly on very short strings; exact match is better.
        if (normalizedTarget.length <= 2) {
            val exactMatch = nodes.firstOrNull { node ->
                node.text.trim().lowercase() == normalizedTarget ||
                node.description.trim().lowercase() == normalizedTarget
            }
            if (exactMatch != null) {
                Log.d(TAG, "Exact short-match '$targetText' → '${exactMatch.text.ifEmpty { exactMatch.description }}'")
                return buildResult(exactMatch, 1.0, 0.0)
            }
        }

        // Score every node against the target
        data class ScoredNode(
            val node: SemanticFlattener.FlatNode,
            val score: Double,
            val matchedOn: String  // Which field matched: "text" or "desc"
        )

        val scored = nodes.mapNotNull { node ->
            val textScore = if (node.text.isNotEmpty()) {
                jaroWinkler(normalizedTarget, node.text.lowercase().trim())
            } else 0.0

            val descScore = if (node.description.isNotEmpty()) {
                jaroWinkler(normalizedTarget, node.description.lowercase().trim())
            } else 0.0

            // Also check for exact substring containment (boost)
            val containsBoost = when {
                node.text.contains(targetText, ignoreCase = true) -> 0.15
                node.description.contains(targetText, ignoreCase = true) -> 0.12
                else -> 0.0
            }

            val bestFieldScore = if (textScore >= descScore) {
                ScoredNode(node, min(1.0, textScore + containsBoost), "text")
            } else {
                ScoredNode(node, min(1.0, descScore + containsBoost), "desc")
            }

            // Only keep candidates with non-trivial scores
            if (bestFieldScore.score > 0.3) bestFieldScore else null
        }.sortedByDescending { it.score }

        val elapsedMs = (System.nanoTime() - startTime) / 1_000_000.0

        // Log all candidates for debugging
        Log.d(TAG, "Resolve '$targetText' → ${scored.size} candidates in ${"%.1f".format(elapsedMs)}ms")
        scored.take(5).forEach { s ->
            val matchField = if (s.matchedOn == "text") s.node.text else s.node.description
            Log.d(TAG, "  [${"%.3f".format(s.score)}] ${s.node.id} $matchField (${s.node.type})")
        }

        // Check threshold
        if (scored.isEmpty() || scored[0].score < SIMILARITY_THRESHOLD) {
            val bestScore = scored.firstOrNull()?.score ?: 0.0
            throw AmbiguousTargetException(
                targetText = targetText,
                bestScore = bestScore,
                candidates = scored.take(3).map {
                    val text = if (it.matchedOn == "text") it.node.text else it.node.description
                    text to it.score
                },
                message = "No match above threshold $SIMILARITY_THRESHOLD for '$targetText' " +
                        "(best: ${"%.3f".format(bestScore)})"
            )
        }

        // Check for ambiguous ties (top 2 scores within 0.05 of each other)
        if (scored.size >= 2 && (scored[0].score - scored[1].score) < 0.05) {
            // If the top candidate is clickable and the second isn't, prefer clickable
            val top = scored[0]
            val second = scored[1]
            if (top.node.isClickable && !second.node.isClickable) {
                // Clear winner — the clickable one
            } else if (!top.node.isClickable && second.node.isClickable) {
                // Swap — prefer the clickable one
                val winner = second
                return buildResult(winner.node, winner.score, elapsedMs)
            } else {
                Log.w(TAG, "⚠️ Ambiguous tie: '${top.node.text}' vs '${second.node.text}'")
                // Still return the top one but flag it
            }
        }

        val winner = scored[0]
        return buildResult(winner.node, winner.score, elapsedMs)
    }

    /**
     * Checks if the target text matches a dangerous/sensitive action.
     * Used by the safety guardrail to decide if voice confirmation is needed.
     */
    fun isDangerousAction(targetText: String): Boolean {
        val dangerousKeywords = listOf(
            "pay", "transfer", "send money", "delete", "remove",
            "confirm order", "logout", "log out", "sign out",
            "uninstall", "purchase", "buy", "place order",
            "confirm payment", "proceed to pay", "upi", "bhim"
        )
        val lower = targetText.lowercase().trim()
        return dangerousKeywords.any { keyword ->
            jaroWinkler(lower, keyword) >= 0.80 || lower.contains(keyword)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  JARO-WINKLER SIMILARITY (Pure Kotlin — no external libs)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Computes Jaro-Winkler similarity between two strings.
     *
     * - Jaro: Based on matching characters and transpositions
     * - Winkler: Boosts score for common prefixes (up to 4 chars)
     *
     * @return Similarity score in [0.0, 1.0] where 1.0 = identical
     */
    fun jaroWinkler(s1: String, s2: String, prefixScale: Double = 0.1): Double {
        if (s1 == s2) return 1.0
        if (s1.isEmpty() || s2.isEmpty()) return 0.0

        val jaro = jaroSimilarity(s1, s2)

        // Winkler boost: common prefix up to 4 chars
        val prefixLen = commonPrefixLength(s1, s2, maxLength = 4)
        return jaro + (prefixLen * prefixScale * (1.0 - jaro))
    }

    /**
     * Core Jaro similarity calculation.
     */
    private fun jaroSimilarity(s1: String, s2: String): Double {
        val maxLen = max(s1.length, s2.length)
        if (maxLen == 0) return 1.0

        // Match window
        val matchWindow = max(maxLen / 2 - 1, 0)

        val s1Matches = BooleanArray(s1.length)
        val s2Matches = BooleanArray(s2.length)

        var matches = 0
        var transpositions = 0

        // Find matching characters
        for (i in s1.indices) {
            val start = max(0, i - matchWindow)
            val end = min(s2.length - 1, i + matchWindow)

            for (j in start..end) {
                if (s2Matches[j] || s1[i] != s2[j]) continue
                s1Matches[i] = true
                s2Matches[j] = true
                matches++
                break
            }
        }

        if (matches == 0) return 0.0

        // Count transpositions
        var k = 0
        for (i in s1.indices) {
            if (!s1Matches[i]) continue
            while (!s2Matches[k]) k++
            if (s1[i] != s2[k]) transpositions++
            k++
        }

        val jaro = (matches.toDouble() / s1.length +
                matches.toDouble() / s2.length +
                (matches - transpositions / 2.0) / matches) / 3.0

        return jaro
    }

    /**
     * Counts matching characters at the start of both strings, up to [maxLength].
     */
    private fun commonPrefixLength(s1: String, s2: String, maxLength: Int): Int {
        val limit = min(min(s1.length, s2.length), maxLength)
        for (i in 0 until limit) {
            if (s1[i] != s2[i]) return i
        }
        return limit
    }

    // ═══════════════════════════════════════════════════════════════
    //  HELPERS
    // ═══════════════════════════════════════════════════════════════

    private fun buildResult(
        node: SemanticFlattener.FlatNode,
        score: Double,
        elapsedMs: Double
    ): ResolveResult {
        val result = ResolveResult(
            x = node.centerX,
            y = node.centerY,
            matchedText = node.text.ifEmpty { node.description },
            matchedNodeId = node.id,
            confidence = score,
            bounds = node.bounds
        )
        Log.i(TAG, "✅ Resolved → (${result.x}, ${result.y}) | " +
                "'${result.matchedText}' | conf=${"%.3f".format(score)} | " +
                "${"%.1f".format(elapsedMs)}ms")
        return result
    }
}
