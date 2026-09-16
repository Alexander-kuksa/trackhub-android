package com.trackhub

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.IdentityHashMap

/** Read the limit plus one probe byte, never the whole file before checking. */
internal fun InputStream.readBoundedUtf8(maxBytes: Int): String? {
    require(maxBytes in 1..(8 * 1024 * 1024))
    val output = ByteArrayOutputStream(minOf(maxBytes, 4096))
    val buffer = ByteArray(minOf(maxBytes + 1, 4096))
    while (true) {
        val count = read(buffer, 0, minOf(buffer.size, maxBytes + 1 - output.size()))
        if (count < 0) return output.toString(Charsets.UTF_8.name())
        if (count == 0) return null // A non-progressing stream must not spin.
        output.write(buffer, 0, count)
        if (output.size() > maxBytes) return null
    }
}

/** Non-recursive preflight before org.json's recursive parser sees untrusted JSON. */
internal fun hasBoundedJsonStructure(raw: String, maxChars: Int, maxDepth: Int = 32): Boolean {
    if (raw.length > maxChars) return false
    var depth = 0
    var quoted = false
    var escaped = false
    for (char in raw) {
        if (quoted) {
            if (escaped) escaped = false
            else if (char == '\\') escaped = true
            else if (char == '"') quoted = false
        } else when (char) {
            '"' -> quoted = true
            '[', '{' -> { if (++depth > maxDepth) return false }
            ']', '}' -> { if (--depth < 0) return false }
        }
    }
    return !quoted && depth == 0
}

internal fun boundedJsonObject(raw: String): JSONObject {
    require(hasBoundedJsonStructure(raw, 64 * 1024))
    return JSONObject(raw)
}

/** Deep snapshot before enqueueing: no caller-owned mutable containers survive. */
internal object EventParameterSnapshot {
    fun copy(input: Map<String, *>): Map<String, Any?>? = runCatchingException {
        val writer = Copier()
        @Suppress("UNCHECKED_CAST")
        writer.copy(input, 0) as Map<String, Any?>
    }.getOrNull()

    private class Copier {
        private var nodes = 2048
        private var bytes = 64 * 1024
        private val ancestors = IdentityHashMap<Any, Boolean>()
        private fun charge(count: Int) { require(count <= bytes); bytes -= count }
        private fun string(value: String): String {
            require(value.length <= bytes)
            charge(JSONObject.quote(value).toByteArray(Charsets.UTF_8).size)
            return value
        }
        fun copy(value: Any?, depth: Int): Any? {
            require(depth <= 16 && --nodes >= 0)
            if (value == null || value === JSONObject.NULL) { charge(4); return null }
            when (value) {
                is String -> return string(value)
                is Boolean -> { charge(5); return value }
                is Byte, is Short, is Int, is Long -> { charge(value.toString().length); return value }
                is Float -> { require(value.isFinite()); charge(value.toString().length); return value }
                is Double -> { require(value.isFinite()); charge(value.toString().length); return value }
            }
            require(ancestors.put(value, true) == null)
            try {
                charge(2)
                return when (value) {
                    is Map<*, *> -> {
                        require(value.size <= nodes)
                        val result = linkedMapOf<String, Any?>()
                        for ((key, item) in value) {
                            require(key is String); charge(2)
                            result[string(key)] = copy(item, depth + 1)
                        }
                        result
                    }
                    is JSONObject -> {
                        require(value.length() <= nodes)
                        val result = linkedMapOf<String, Any?>()
                        for (key in value.keys()) { charge(2); result[string(key)] = copy(value.get(key), depth + 1) }
                        result
                    }
                    is Collection<*> -> {
                        require(value.size <= nodes)
                        val result = ArrayList<Any?>()
                        for (item in value) { charge(1); result.add(copy(item, depth + 1)) }
                        result
                    }
                    is JSONArray -> {
                        require(value.length() <= nodes)
                        List(value.length()) { charge(1); copy(value.get(it), depth + 1) }
                    }
                    else -> {
                        require(value.javaClass.isArray)
                        val count = java.lang.reflect.Array.getLength(value)
                        require(count <= nodes)
                        List(count) { charge(1); copy(java.lang.reflect.Array.get(value, it), depth + 1) }
                    }
                }
            } finally { ancestors.remove(value) }
        }
    }
}
