/**
 * Utility for extracting Next.js data from HTML or RSC responses.
 * Based on the Next.js extraction logic from the Keiyoushi repository.
 */
@file:JvmName("NextJsUtils")

package org.koitharu.kotatsu.parsers.util.json

import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.util.parseHtml

private val NEXT_F_REGEX = Regex("""self\.__next_f\.push\(\s*(\[.*])\s*\)\s*;?\s*$""", RegexOption.DOT_MATCHES_ALL)

private fun extractValueNextJs(
    payload: Any,
    predicate: (Any) -> Boolean,
): Any? {
    if (payload !is JSONObject && payload !is JSONArray) return null
    if (predicate(payload)) {
        return payload
    }

    if (payload is JSONObject) {
        val keys = payload.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val child = payload.opt(key)
            if (child != null) {
                val result = extractValueNextJs(child, predicate)
                if (result != null) return result
            }
        }
    } else if (payload is JSONArray) {
        for (i in 0 until payload.length()) {
            val child = payload.opt(i)
            if (child != null) {
                val result = extractValueNextJs(child, predicate)
                if (result != null) return result
            }
        }
    }
    return null
}

private fun Document.extractAppRouterPayloads(): List<Any> = select("script:not([src])")
    .map { it.data() }
    .filter { "self.__next_f.push" in it }
    .flatMap { script ->
        try {
            val raw = NEXT_F_REGEX.find(script)?.groupValues?.get(1) ?: return@flatMap emptyList()
            val arr = JSONArray(raw)
            val content = arr.optString(1, null) ?: return@flatMap emptyList()

            extractRscPayloads(content)
        } catch (_: Exception) {
            emptyList()
        }
    }

private fun Document.extractPagesRouterPayloads(): List<Any> {
    val data = selectFirst("script#__NEXT_DATA__")?.data() ?: return emptyList()
    return try {
        val root = JSONObject(data)
        val pageProps = root.optJSONObject("props")?.optJSONObject("pageProps")
        listOfNotNull(pageProps, root)
    } catch (_: Exception) {
        emptyList()
    }
}

private fun extractRscPayloads(body: String): List<Any> {
    val results = mutableListOf<Any>()
    var pos = 0

    while (pos < body.length) {
        val colonIdx = body.indexOf(':', pos)
        if (colonIdx == -1) break

        val id = body.substring(pos, colonIdx)
        if (id.isEmpty() || !id.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
            pos++
            continue
        }

        pos = colonIdx + 1
        if (pos >= body.length) break

        if (body[pos] == 'T') {
            pos++
            val commaIdx = body.indexOf(',', pos)
            if (commaIdx == -1) break
            val byteLen = body.substring(pos, commaIdx).toIntOrNull(16) ?: break
            pos = commaIdx + 1
            var bytes = 0
            val start = pos
            while (pos < body.length && bytes < byteLen) {
                when {
                    body[pos].code < 0x80 -> bytes += 1
                    body[pos].code < 0x800 -> bytes += 2
                    Character.isHighSurrogate(body[pos]) -> {
                        bytes += 4
                        pos++
                    }
                    else -> bytes += 3
                }
                pos++
            }
            try {
                results.add(parseJsonType(body.substring(start, pos)))
            } catch (_: Exception) {}
        } else {
            val (element, end) = parseJsonAt(body, pos)
            if (element != null) results.add(element)
            pos = end
        }
    }

    return results
}

private fun parseJsonType(jsonStr: String): Any {
    val trimmed = jsonStr.trim()
    return when {
        trimmed.startsWith("{") -> JSONObject(trimmed)
        trimmed.startsWith("[") -> JSONArray(trimmed)
        else -> throw JSONException("Not a JSONObject or JSONArray")
    }
}

private fun parseJsonAt(body: String, start: Int): Pair<Any?, Int> {
    if (start >= body.length) return Pair(null, start)

    var depth = 0
    var inString = false
    var escape = false
    var i = start

    while (i < body.length) {
        val c = body[i++]
        if (escape) {
            escape = false
            continue
        }
        if (c == '\\' && inString) {
            escape = true
            continue
        }
        if (c == '"') {
            inString = !inString
            continue
        }
        if (inString) continue
        when (c) {
            '{', '[' -> depth++
            '}', ']' -> if (--depth == 0) {
                return try {
                    Pair(parseJsonType(body.substring(start, i)), i)
                } catch (_: Exception) {
                    Pair(null, i)
                }
            }
        }
        if (depth == 0 && c.isWhitespace()) {
            return try {
                Pair(parseJsonType(body.substring(start, i - 1)), i)
            } catch (_: Exception) {
                Pair(null, i)
            }
        }
    }
    return Pair(null, i)
}

public fun Document.extractNextJs(
    predicate: (Any) -> Boolean,
): Any? {
    val payloads = extractAppRouterPayloads().ifEmpty { extractPagesRouterPayloads() }
    for (payload in payloads) {
        val result = extractValueNextJs(payload, predicate)
        if (result != null) return result
    }
    return null
}

public inline fun <reified T : Any> Document.extractNextJsTyped(
    noinline predicate: (Any) -> Boolean,
): T? = extractNextJs(predicate) as? T

public fun String.extractNextJsRsc(
    predicate: (Any) -> Boolean,
): Any? {
    for (payload in extractRscPayloads(this)) {
        val result = extractValueNextJs(payload, predicate)
        if (result != null) return result
    }
    return null
}

public inline fun <reified T : Any> String.extractNextJsRscTyped(
    noinline predicate: (Any) -> Boolean,
): T? = extractNextJsRsc(predicate) as? T

public fun Response.extractNextJs(
    predicate: (Any) -> Boolean,
): Any? {
    val contentType = header("Content-Type") ?: ""
    return when {
        "text/x-component" in contentType -> body.string().extractNextJsRsc(predicate)
        "text/html" in contentType -> parseHtml().extractNextJs(predicate)
        else -> error("Unsupported Content-Type for Next.js extraction: $contentType")
    }
}

public inline fun <reified T : Any> Response.extractNextJsTyped(
    noinline predicate: (Any) -> Boolean,
): T? = extractNextJs(predicate) as? T
