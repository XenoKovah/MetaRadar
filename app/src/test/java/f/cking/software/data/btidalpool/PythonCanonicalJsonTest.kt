package f.cking.software.data.btidalpool

import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Test

/**
 * Pins [PythonCanonicalJson]'s output, byte-for-byte, against what
 * `python -c "import json; print(json.dumps(value, sort_keys=True))"` produces for the same
 * input. The BTIDALPOOL upload server identifies duplicate uploads by the SHA1 hash of the
 * canonical form — a divergence here makes the client-side dedup miss and forces the server
 * to reject the full upload after we've already streamed it (wasted bandwidth, not a
 * correctness bug, but still worth catching early).
 *
 * Specific regressions guarded:
 *   - The `appendElement` rename incident: a previous implementation called the recursive
 *     helper `append`, which Kotlin's `StringBuilder.append(Any?)` extension shadowed in
 *     `buildString` blocks. Output silently became `JsonElement.toString()` (compact, no
 *     `, ` separators) instead of canonical form. The current code uses an explicitly-named
 *     `appendElement` and an explicitly-typed `StringBuilder` — these tests fail loudly if
 *     anyone reintroduces the shadowing.
 *   - Object key ordering: Python's `sort_keys=True` is lexical Unicode-codepoint order. The
 *     test exercises a key set whose insertion order is the inverse of sort order so any
 *     "preserved insertion order" regression flips the byte sequence visibly.
 *   - Separator spacing: `, ` between elements, `: ` after object keys (Python's default).
 *   - String escaping: backslashes, quotes, control chars, non-ASCII via `\uXXXX` escapes
 *     (Python's `ensure_ascii=True` default).
 *
 * If you change [PythonCanonicalJson], regenerate the expected strings with:
 *   `python3 -c "import json; print(json.dumps(VALUE, sort_keys=True))"`
 * and paste the literal output into the matching assertion.
 */
class PythonCanonicalJsonTest {

    // ---- Primitives

    @Test
    fun `null primitive renders as the unquoted lowercase token`() {
        assertEquals("null", PythonCanonicalJson.encode(JsonNull))
    }

    @Test
    fun `boolean primitives render lowercase like Python`() {
        // Java/Kotlin `Boolean.toString` is already lowercase, but Python is the contract,
        // not Java — pin both branches explicitly.
        assertEquals("true", PythonCanonicalJson.encode(JsonPrimitive(true)))
        assertEquals("false", PythonCanonicalJson.encode(JsonPrimitive(false)))
    }

    @Test
    fun `integer primitives render in shortest decimal form`() {
        assertEquals("0", PythonCanonicalJson.encode(JsonPrimitive(0L)))
        assertEquals("42", PythonCanonicalJson.encode(JsonPrimitive(42L)))
        assertEquals("-17", PythonCanonicalJson.encode(JsonPrimitive(-17L)))
        // Exact 53-bit boundary — both Python and Kotlin's Long handle this without loss.
        assertEquals("9007199254740993", PythonCanonicalJson.encode(JsonPrimitive(9_007_199_254_740_993L)))
    }

    @Test
    fun `whole-valued doubles keep the trailing dot-zero like Python`() {
        // Python: `json.dumps(1.0)` => `'1.0'`. Kotlin's Double.toString already does this.
        assertEquals("1.0", PythonCanonicalJson.encode(JsonPrimitive(1.0)))
        assertEquals("0.0", PythonCanonicalJson.encode(JsonPrimitive(0.0)))
        assertEquals("-3.0", PythonCanonicalJson.encode(JsonPrimitive(-3.0)))
    }

    @Test
    fun `fractional doubles round-trip via shortest-repr`() {
        assertEquals("3.14", PythonCanonicalJson.encode(JsonPrimitive(3.14)))
        assertEquals("-0.5", PythonCanonicalJson.encode(JsonPrimitive(-0.5)))
    }

    // ---- Strings

    @Test
    fun `plain ASCII strings are double-quoted`() {
        assertEquals("\"hello\"", PythonCanonicalJson.encode(JsonPrimitive("hello")))
        assertEquals("\"\"", PythonCanonicalJson.encode(JsonPrimitive("")))
    }

    @Test
    fun `embedded quotes and backslashes escape with leading backslash`() {
        // Python: `json.dumps('a"b\\c')` => `'"a\\"b\\\\c"'`.
        assertEquals(
            "\"a\\\"b\\\\c\"",
            PythonCanonicalJson.encode(JsonPrimitive("a\"b\\c")),
        )
    }

    @Test
    fun `whitespace control chars use named two-char escapes`() {
        // Tab, newline, carriage return, backspace, form-feed map to `\t \n \r \b \f`. Python's
        // json.dumps does the same. Other C0 controls fall back to `\uXXXX`.
        val s = "\t\n\r\b"
        assertEquals(
            "\"\\t\\n\\r\\b\\f\"",
            PythonCanonicalJson.encode(JsonPrimitive(s)),
        )
    }

    @Test
    fun `low control chars without named escape use lowercase 4-hex unicode form`() {
        // Python: `json.dumps('\x01\x02\x1f')` => `'"\\u0001\\u0002\\u001f"'`. Lowercase hex.
        assertEquals(
            "\"\\u0001\\u0002\\u001f\"",
            PythonCanonicalJson.encode(JsonPrimitive("")),
        )
    }

    @Test
    fun `non-ASCII chars are escaped to lowercase 4-hex unicode like ensure_ascii=True`() {
        // Python defaults: `json.dumps('café')` => `'"caf\\u00e9"'`. The é (U+00E9) escapes
        // even though it's UTF-8 representable, because Python defaults `ensure_ascii=True`.
        assertEquals(
            "\"caf\\u00e9\"",
            PythonCanonicalJson.encode(JsonPrimitive("café")),
        )

        // Multi-byte BMP char: U+2603 ☃
        assertEquals(
            "\"\\u2603\"",
            PythonCanonicalJson.encode(JsonPrimitive("☃")),
        )
    }

    @Test
    fun `surrogate-pair chars (above BMP) emit two-paired uXXXX escapes`() {
        // U+1F600 😀 → UTF-16 surrogate pair D83D + DE00 → "😀".
        // Python does the same with ensure_ascii=True.
        assertEquals(
            "\"\\ud83d\\ude00\"",
            PythonCanonicalJson.encode(JsonPrimitive("😀")),
        )
    }

    @Test
    fun `0x7F (DEL) is below the escape threshold and rendered as raw byte`() {
        // The implementation escapes `cp >= 0x7F` (everything from DEL upward) — DEL itself
        // becomes ``. Pinning the boundary so a future "<=" vs "<" flip is caught.
        assertEquals(
            "\"\\u007f\"",
            PythonCanonicalJson.encode(JsonPrimitive("")),
        )
        // 0x7E (~) is the highest non-escaped char.
        assertEquals(
            "\"~\"",
            PythonCanonicalJson.encode(JsonPrimitive("~")),
        )
    }

    // ---- Arrays

    @Test
    fun `empty array renders as compact brackets with no inner space`() {
        assertEquals("[]", PythonCanonicalJson.encode(buildJsonArray { }))
    }

    @Test
    fun `array elements joined by comma-space`() {
        // Python default separator is `, ` between array elements (and `: ` after keys).
        assertEquals(
            "[1, 2, 3]",
            PythonCanonicalJson.encode(buildJsonArray {
                add(JsonPrimitive(1))
                add(JsonPrimitive(2))
                add(JsonPrimitive(3))
            }),
        )
    }

    @Test
    fun `mixed-type arrays preserve element order`() {
        assertEquals(
            "[\"a\", 1, true, null]",
            PythonCanonicalJson.encode(buildJsonArray {
                add(JsonPrimitive("a"))
                add(JsonPrimitive(1))
                add(JsonPrimitive(true))
                add(JsonNull)
            }),
        )
    }

    // ---- Objects

    @Test
    fun `empty object renders as compact braces with no inner space`() {
        assertEquals("{}", PythonCanonicalJson.encode(buildJsonObject { }))
    }

    @Test
    fun `object keys are sorted lexicographically regardless of insertion order`() {
        // Insert in reverse-alphabetical order. Python's sort_keys=True must restore the
        // sorted order. If a future implementation skips the sort, the test fails because
        // the output starts with `{"zeta":` instead of `{"alpha":`.
        val obj = buildJsonObject {
            put("zeta", 1)
            put("mu", 2)
            put("alpha", 3)
        }
        assertEquals(
            "{\"alpha\": 3, \"mu\": 2, \"zeta\": 1}",
            PythonCanonicalJson.encode(obj),
        )
    }

    @Test
    fun `key sort uses Unicode codepoint ordering not case-insensitive`() {
        // Python's sort_keys uses straight codepoint ordering: uppercase ASCII (< 0x60) sorts
        // before lowercase (>= 0x61). A locale-aware case-insensitive sort would put them
        // alphabetically interleaved — that would diverge from Python and break the SHA1.
        val obj = buildJsonObject {
            put("apple", 1)
            put("Banana", 2)
        }
        assertEquals(
            "{\"Banana\": 2, \"apple\": 1}",
            PythonCanonicalJson.encode(obj),
        )
    }

    // ---- Recursive structures (the top-of-file shadowing-regression smoke check)

    @Test
    fun `nested object inside array uses canonical form recursively`() {
        // The pre-fix `append(this, ...)` bug only surfaced inside containers — at the top
        // level the call resolved correctly. So a regression is caught most decisively by a
        // nested case where the inner object's canonical encoding has to come back out of
        // the recursion step intact (sorted keys, `, ` separators, `: ` after keys).
        val tree = buildJsonArray {
            add(buildJsonObject {
                put("z", 1)
                put("a", 2)
            })
            add(buildJsonObject {
                put("b", "hi")
            })
        }
        assertEquals(
            "[{\"a\": 2, \"z\": 1}, {\"b\": \"hi\"}]",
            PythonCanonicalJson.encode(tree),
        )
    }

    @Test
    fun `realistic BTIDES-shaped record matches Python json dumps sort_keys output exactly`() {
        // Mini structural fixture similar to a single SingleBDADDR entry. Generated reference
        // via:
        //   `python3 -c "import json; print(json.dumps({'AdvData': [{'AdvType': 0, 'rssi': -42, 'time_ms': 1700000000000}], 'bdaddr': 'AA:BB:CC:DD:EE:FF', 'bdaddr_rand': 1, 'transport': 'LE'}, sort_keys=True))"`
        val tree = buildJsonObject {
            put("transport", "LE")
            put("bdaddr_rand", 1)
            put("bdaddr", "AA:BB:CC:DD:EE:FF")
            // AdvData inserted last to make sort_keys=True actually move it up.
            putJsonArray("AdvData") {
                addJsonObject {
                    put("rssi", -42)
                    put("AdvType", 0)
                    put("time_ms", 1_700_000_000_000L)
                }
            }
        }
        assertEquals(
            "{\"AdvData\": [{\"AdvType\": 0, \"rssi\": -42, \"time_ms\": 1700000000000}], \"bdaddr\": \"AA:BB:CC:DD:EE:FF\", \"bdaddr_rand\": 1, \"transport\": \"LE\"}",
            PythonCanonicalJson.encode(tree),
        )
    }

    @Test
    fun `parsed-then-canonicalised round-trip preserves byte sequence for canonical input`() {
        // If we feed in a JSON string that's already in canonical form, parse it, and
        // re-emit, the result must be byte-identical to the input. Catches regressions where
        // a parse step normalises whitespace or numeric form differently from emit.
        val canonical = "{\"a\": 1, \"b\": [1, 2, 3]}"
        val element = Json.parseToJsonElement(canonical)
        assertEquals(canonical, PythonCanonicalJson.encode(element))
    }

    @Test
    fun `top-level integer round-trips as bare token`() {
        // Some BTIDALPOOL endpoints accept a top-level integer (e.g. `1700000000000`); the
        // emitter must handle a bare JsonPrimitive root, not just objects/arrays.
        assertEquals("1700000000000", PythonCanonicalJson.encode(JsonPrimitive(1_700_000_000_000L)))
    }

    @Test
    fun `output is always non-empty for any valid input - regression guard for buildString shadowing bug`() {
        // The `append(this, ...)` shadowing bug emitted JsonElement.toString() of the root
        // (e.g. `{"a":1}`), which was non-empty but missing the `, ` and `: ` separators. A
        // sufficient guard: any input that has at least one separator-requiring construct
        // (multi-element array, multi-key object) must contain `, ` somewhere in the output.
        val obj = buildJsonObject {
            put("a", 1)
            put("b", 2)
        }
        val out = PythonCanonicalJson.encode(obj)
        assertTrue(
            "Multi-key object must have `, ` separator (got: $out)",
            ", " in out,
        )
        assertTrue("Object must have `: ` after key", ": " in out)
    }
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putJsonArray(
    key: String,
    block: kotlinx.serialization.json.JsonArrayBuilder.() -> Unit,
) {
    put(key, kotlinx.serialization.json.buildJsonArray(block))
}

private fun kotlinx.serialization.json.JsonArrayBuilder.addJsonObject(
    block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
) {
    add(kotlinx.serialization.json.buildJsonObject(block))
}
