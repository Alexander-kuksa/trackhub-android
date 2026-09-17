package com.trackhub

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.InputStream

class BoundedInputTest {
    @Test fun boundedReadStopsAfterOneProbeAndRejectsTruncation() {
        var consumed = 0
        val endless = object : InputStream() { override fun read(): Int { consumed++; return 'x'.code } }
        assertNull(endless.readBoundedUtf8(1024))
        assertEquals(1025, consumed)
        assertEquals("hello", "hello".byteInputStream().readBoundedUtf8(5))
        assertNull("hello!".byteInputStream().readBoundedUtf8(5))
        assertEquals("", byteArrayOf().inputStream().readBoundedUtf8(5))
    }

    @Test fun depthPreflightHonorsEscapesAndRejectsStackExhaustion() {
        assertFalse(hasBoundedJsonStructure("[".repeat(10000) + "]".repeat(10000), 65536))
        assertTrue(hasBoundedJsonStructure(JSONObject().put("text", "[\"{\\]".repeat(100)).toString(), 65536))
        assertFalse(hasBoundedJsonStructure("{\"broken\":\"escape\\", 65536))
    }

    @Test fun cyclicOrDeepParamsAreRejectedWithoutCallingToString() {
        val cycle = mutableMapOf<String, Any?>(); cycle["self"] = cycle
        assertNull(EventParameterSnapshot.copy(cycle))
        val list = mutableListOf<Any?>(); list.add(list)
        assertNull(EventParameterSnapshot.copy(mapOf("cycle" to list)))
        val json = JSONObject(); json.put("self", json)
        assertNull(EventParameterSnapshot.copy(mapOf("json" to json)))
        var deep: Any = "leaf"
        repeat(10000) { deep = mapOf("next" to deep) }
        assertNull(EventParameterSnapshot.copy(mapOf("deep" to deep)))
        val custom = object { override fun toString(): String = error("must not execute caller code") }
        assertNull(EventParameterSnapshot.copy(mapOf("custom" to custom)))
        assertNull(EventParameterSnapshot.copy(mapOf("nan" to Double.NaN)))
    }

    @Test fun snapshotPreservesJsonAndDetachesNestedMutableValues() {
        val nested = mutableListOf<Any?>(false, null, 7)
        val shared = mutableMapOf<String, Any?>("items" to nested)
        val copy = EventParameterSnapshot.copy(mapOf("a" to shared, "b" to shared, "array" to intArrayOf(1, 2)))!!
        nested.add("later"); shared["extra"] = true
        val actual = JSONObject(copy)
        assertEquals(3, actual.getJSONObject("a").getJSONArray("items").length())
        assertEquals(actual.getJSONObject("a").toString(), actual.getJSONObject("b").toString())
        assertEquals("[1,2]", actual.getJSONArray("array").toString())
    }

    @Test fun budgetsRejectBroadAndLargePayloadsBeforeSerialization() {
        assertNull(EventParameterSnapshot.copy(mapOf("wide" to List(10000) { 0 })))
        assertNull(EventParameterSnapshot.copy(mapOf("huge" to "x".repeat(65537))))
        assertNull(EventParameterSnapshot.copy(mapOf("escaped" to "\u0000".repeat(12000))))
        assertNotNull(EventParameterSnapshot.copy(mapOf("ok" to JSONArray("[null,false,1]"))))
    }
}
