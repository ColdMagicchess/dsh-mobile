package com.example.DSH_Mobile

import com.example.DSH_Mobile.dsh.DSH_JSON
import com.example.DSH_Mobile.dsh.MessageStore
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageStoreTest {

    private fun entry(type: String, seq: Long, data: String): JsonElement =
        DSH_JSON.parseToJsonElement(
            """{"type":"event","event":{"type":"$type","seq":$seq,"time":1,"data":$data}}""",
        )

    @Test
    fun foldsDeltasIntoPendingMessageAndSettlesOnTurnEnd() {
        val store = MessageStore()
        store.applyRecords(
            listOf(
                entry("user/message", 1, """{"id":"u1","content":[{"type":"text","text":"hi"}]}"""),
                entry("assistant/chunk", 2, """{"turn":1,"step":1,"chunk":{"type":"text-delta","index":0,"text":"he"}}"""),
                entry("assistant/chunk", 3, """{"turn":1,"step":1,"chunk":{"type":"text-delta","index":0,"text":"llo"}}"""),
                entry("assistant/chunk", 4, """{"turn":1,"step":1,"chunk":{"type":"reasoning-delta","index":1,"text":"think"}}"""),
                entry("assistant/message", 5, """{"turn":1,"step":1,"message":{"id":"a1","content":[]}}"""),
                entry("turn/end", 6, """{"turn":1,"reason":{"kind":"completed"}}"""),
            ),
        )
        val ms = store.messages.value
        assertEquals(2, ms.size)
        assertEquals("hi", ms[0].text)
        assertEquals("a1", ms[1].id)
        assertEquals("hello", ms[1].text)
        assertEquals("think", ms[1].reasoning)
        assertFalse(ms[1].pending)
        assertEquals(6L, store.watermark)
    }

    @Test
    fun dropsEventsAtOrBelowWatermark() {
        val store = MessageStore()
        store.applyRecords(
            listOf(
                entry("user/message", 1, """{"id":"u1","content":[{"type":"text","text":"hi"}]}"""),
            ),
        )
        store.applyRecords(
            listOf(
                entry("user/message", 1, """{"id":"u1","content":[{"type":"text","text":"hi"}]}"""),
                entry("user/message", 2, """{"id":"u2","content":[{"type":"text","text":"again"}]}"""),
            ),
        )
        assertEquals(2, store.messages.value.size)
    }

    @Test
    fun toolCallAndResultAttachToAssistantMessage() {
        val store = MessageStore()
        store.applyRecords(
            listOf(
                entry("assistant/chunk", 1, """{"turn":2,"step":1,"chunk":{"type":"text-delta","index":0,"text":"run"}}"""),
                entry("tool/call", 2, """{"turn":2,"step":1,"callId":"c1","name":"bash","arguments":"{\"command\":\"ls\"}"}"""),
                entry("tool/result", 3, """{"turn":2,"step":1,"message":{"id":"tr1","role":"user","source":{"kind":"tool","callId":"c1"},"content":[{"type":"tool-result","toolCallId":"c1","content":[{"type":"text","text":"ok"}]}]}}"""),
                entry("assistant/message", 4, """{"turn":2,"step":1,"message":{"id":"a2","content":[]}}"""),
            ),
        )
        val a = store.messages.value.single { it.id == "a2" }
        assertEquals(1, a.tools.size)
        assertEquals("bash", a.tools[0].name)
        assertEquals("ok", a.tools[0].result)
        assertTrue(a.pending)
    }

    @Test
    fun identicalAssistantTextUnderDifferentIdsCollapses() {
        val store = MessageStore()
        val long = "这是一段足够长的助手文本，用于验证宿主重放去重逻辑的正确性。"
        store.applyRecords(
            listOf(
                entry("assistant/message", 1, """{"turn":1,"step":1,"message":{"id":"m1","content":[{"type":"text","text":"$long"}]}}"""),
                entry("assistant/message", 2, """{"turn":1,"step":1,"message":{"id":"m2","content":[{"type":"text","text":"$long"}]}}"""),
                entry("turn/end", 3, """{"turn":1,"reason":{"kind":"completed"}}"""),
            ),
        )
        assertEquals(1, store.messages.value.count { it.role.name == "ASSISTANT" && it.text == long })
    }

    @Test
    fun chunkRowWatermarkAbsorbsScatteredChunks() {
        val store = MessageStore()
        // History: chunkrow at seq 10 absorbing deltas 10..12, then final message.
        store.applyRecords(
            listOf(
                entry("chunkrow/text-chunks", 10, """{"texts":["a","b","c"],"turn":9,"step":1,"index":0,"dt":[0,5,5]}"""),
                entry("assistant/message", 13, """{"turn":9,"step":1,"message":{"id":"a9","content":[]}}"""),
            ),
        )
        // Pending replay of the absorbed raw deltas must not duplicate text.
        store.applyEvents(
            listOf(
                DSH_JSON.parseToJsonElement("""{"type":"assistant/chunk","seq":11,"time":1,"data":{"turn":9,"step":1,"chunk":{"type":"text-delta","index":0,"text":"b"}}}"""),
                DSH_JSON.parseToJsonElement("""{"type":"assistant/chunk","seq":12,"time":1,"data":{"turn":9,"step":1,"chunk":{"type":"text-delta","index":0,"text":"c"}}}"""),
            ),
        )
        val a = store.messages.value.single { it.id == "a9" }
        assertEquals("abc", a.text)
    }

    @Test
    fun chunkRowHistoryRecordsAppendLikeDeltas() {
        val store = MessageStore()
        store.applyRecords(
            listOf(
                entry("chunkrow/text-chunks", 1, """{"texts":["foo","bar"],"turn":3,"step":1,"index":0,"dt":[0,10]}"""),
                entry("assistant/message", 2, """{"turn":3,"step":1,"message":{"id":"a3","content":[]}}"""),
            ),
        )
        val a = store.messages.value.single { it.id == "a3" }
        assertEquals("foobar", a.text)
    }
}
