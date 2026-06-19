package com.darkmentor.data.btidalpool

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Re-emit a kotlinx.serialization JsonElement tree in the exact byte form that Python's
 * `json.dumps(value, sort_keys=True)` would produce. The BTIDALPOOL upload server identifies
 * duplicate uploads by the SHA1 of that canonical form — if our hash diverges from Python's
 * for the same content, dedup falls back to a server-side rejection on the full upload, which
 * still works but wastes bandwidth.
 *
 * Specifically matches Python's defaults:
 *   - Object keys sorted lexicographically (Python `sort_keys=True`)
 *   - `, ` separator between elements, `: ` separator after object keys
 *   - Booleans / null lowercased
 *   - Strings in double-quotes; all non-ASCII escaped as `\uXXXX` (Python `ensure_ascii=True`)
 *   - Integers in the smallest decimal form Python would use
 *   - Floats via shortest-repr; whole-valued floats keep their `.0` suffix
 */
object PythonCanonicalJson {

    fun encode(element: JsonElement): String {
        // Build into a separately-named StringBuilder rather than `buildString { append(this, ...) }`
        // — the latter binds `append(StringBuilder, JsonElement)` to Kotlin's
        // `StringBuilder.append(Any?)` extension (which just calls toString() on the second
        // argument), bypassing this object's private `append` entirely. Caught by an
        // instrumented test that expected a canonical hash and got compact JsonElement.toString
        // output instead.
        val sb = StringBuilder()
        appendElement(sb, element)
        return sb.toString()
    }

    private fun appendElement(sb: StringBuilder, element: JsonElement) {
        when (element) {
            is JsonNull -> sb.append("null")
            is JsonPrimitive -> appendPrimitive(sb, element)
            is JsonArray -> {
                sb.append('[')
                element.forEachIndexed { i, v ->
                    if (i > 0) sb.append(", ")
                    appendElement(sb, v)
                }
                sb.append(']')
            }
            is JsonObject -> {
                sb.append('{')
                val sortedKeys = element.keys.sorted()
                sortedKeys.forEachIndexed { i, k ->
                    if (i > 0) sb.append(", ")
                    appendString(sb, k)
                    sb.append(": ")
                    appendElement(sb, element.getValue(k))
                }
                sb.append('}')
            }
        }
    }

    private fun appendPrimitive(sb: StringBuilder, p: JsonPrimitive) {
        if (p.isString) {
            appendString(sb, p.content)
            return
        }
        // Boolean / number / null arrive as non-string JsonPrimitive. Distinguish on the
        // content text rather than relying on isString.
        when {
            p.content == "null" -> sb.append("null")
            p.booleanOrNull != null -> sb.append(if (p.boolean) "true" else "false")
            p.longOrNull != null -> sb.append(p.longOrNull)
            p.doubleOrNull != null -> sb.append(formatDouble(p.doubleOrNull!!))
            else -> sb.append(p.content)
        }
    }

    /**
     * Matches Python's `repr(float)`: an integral float renders with a trailing `.0`, otherwise
     * the shortest representation that round-trips. Java's `Double.toString` already gives the
     * shortest round-trip representation, so the only adjustment is the integral-suffix case.
     */
    private fun formatDouble(d: Double): String {
        if (d.isNaN()) return "NaN"
        if (d.isInfinite()) return if (d > 0) "Infinity" else "-Infinity"
        val s = d.toString()
        // Java prints `1.0E10`; Python prints `10000000000.0`. For numbers in the typical
        // BTIDES domain (RSSIs, addresses, time deltas) we never see exponential notation, but
        // hand back Java's form for outliers — the dedup hash will fall through to the
        // server-side check on those.
        return s
    }

    private fun appendString(sb: StringBuilder, s: String) {
        sb.append('"')
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\b' -> sb.append("\\b")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    val cp = ch.code
                    when {
                        cp == 0x0C -> sb.append("\\f")
                        cp < 0x20 -> sb.append("\\u").append("%04x".format(cp))
                        cp < 0x7F -> sb.append(ch)
                        // Python's ensure_ascii escapes everything >= 0x7F. Surrogate pairs
                        // are emitted as two `\uXXXX` escapes (which is what we get by
                        // stepping char-by-char).
                        else -> sb.append("\\u").append("%04x".format(cp))
                    }
                }
            }
            i++
        }
        sb.append('"')
    }
}
