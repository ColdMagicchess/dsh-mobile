package com.example.DSH_Mobile.dsh

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Folds the DSH session event stream into a chat message list.
 *
 * Rules (ported from the web client fold, per README 3.4):
 *  - events apply in ascending [RawEvent.seq]; anything <= watermark is dropped;
 *  - user/assistant messages are upserted by id, in place;
 *  - text/reasoning deltas append to the in-progress assistant message of the turn;
 *  - turn/end settles the messages of that turn (pending = false);
 *  - chunkrow aggregates (history pages) append their runs like deltas do.
 */
class MessageStore {

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _title = MutableStateFlow<String?>(null)
    val title: StateFlow<String?> = _title.asStateFlow()

    private val _model = MutableStateFlow<ModelSelection?>(null)
    val model: StateFlow<ModelSelection?> = _model.asStateFlow()

    private val _preset = MutableStateFlow<String?>(null)
    val preset: StateFlow<String?> = _preset.asStateFlow()

    var watermark: Long = -1L
        private set

    fun reset() {
        watermark = -1L
        _messages.value = emptyList()
        _title.value = null
        _model.value = null
        _preset.value = null
    }

    /** Apply [SessionHistoryRecord]s (event entries, chunk rows, or bare events). */
    fun applyRecords(records: List<JsonElement>) {
        val events = records.mapNotNull { rec ->
            val o = rec.asObj() ?: return@mapNotNull null
            when (o.str("type")) {
                "event" -> o.obj("event")?.toRawEvent()
                "chunks" -> o.obj("event")?.toChunkRow()
                else -> o.obj("event")?.toRawEvent() ?: o.toRawEvent() ?: o.toChunkRow()
            }
        }
        apply(events)
    }

    /** Apply bare [SessionWireEvent]s (e.g. from inspect or WS item frames). */
    fun applyEvents(events: List<JsonElement>) {
        apply(events.mapNotNull { it.asObj()?.toRawEvent() })
    }

    /** Apply already-normalized follow frames (snapshot/event entries handled by caller). */
    fun applyFrames(frames: List<JsonElement>) {
        val events = mutableListOf<RawEvent>()
        for (f in frames) {
            val o = f.asObj() ?: continue
            when (o.str("type")) {
                "snapshot" -> {
                    o.arr("records")?.let { records ->
                        for (rec in records) {
                            val ro = rec.asObj() ?: continue
                            when (ro.str("type")) {
                                "event" -> ro.obj("event")?.toRawEvent()?.let(events::add)
                                "chunks" -> ro.obj("event")?.toChunkRow()?.let(events::add)
                            }
                        }
                    }
                    o.obj("projections", "values", "title")?.str("title")?.let { _title.value = it }
                    o.prim("projections", "values", "agentPreset")?.contentOrNull?.let { _preset.value = it }
                    o.obj("projections", "values", "modelSelection", "lastUsed")?.let { ms ->
                        val p = ms.str("provider")
                        val m = ms.str("model")
                        if (p != null && m != null) {
                            _model.value = ModelSelection(p, m, ms.str("reasoningEffort"))
                        }
                    }
                }
                "event" -> o.obj("event")?.toRawEvent()?.let(events::add)
                else -> o.toRawEvent()?.let(events::add)
            }
        }
        apply(events)
    }

    private fun JsonObject.toRawEvent(): RawEvent? {
        val type = str("type") ?: return null
        val seq = long("seq") ?: return null
        return RawEvent(type, seq, long("time"), obj("data") ?: JsonObject(emptyMap()))
    }

    private fun JsonObject.toChunkRow(): RawEvent? {
        val type = str("type") ?: return null
        val seq = long("seq") ?: return null
        return RawEvent(type, seq, long("time"), obj("data") ?: JsonObject(emptyMap()))
    }

    private fun apply(events: List<RawEvent>) {
        val sorted = events.filter { it.seq > watermark }.sortedBy { it.seq }
        if (sorted.isEmpty()) return
        var list = _messages.value
        for (e in sorted) {
            list = applyOne(list, e)
            // A chunkrow absorbs the scattered chunks seq..seq+n-1 (verified
            // against the live wire: chunkrow seq + texts.size == next chunk
            // seq). Advance the watermark past the whole run so a later poll
            // cannot re-deliver the absorbed deltas as fresh text.
            var w = e.seq
            if (e.type.startsWith("chunkrow/")) {
                val n = e.data.arr("texts")?.size ?: e.data.arr("args")?.size ?: 0
                if (n > 0) w = e.seq + n - 1L
            }
            if (w > watermark) watermark = w
        }
        _messages.value = list
    }

    private fun applyOne(list: List<ChatMessage>, e: RawEvent): List<ChatMessage> {
        val d = e.data
        return when (e.type) {
            "user/message" -> applyUserMessage(list, e)
            "assistant/message" -> applyAssistantMessage(list, e)
            "assistant/chunk" -> applyAssistantChunk(list, d)
            "chunkrow/text-chunks" -> {
                val delta = d.arr("texts").orEmpty()
                    .mapNotNull { it.asPrim()?.contentOrNull }
                    .joinToString("")
                appendToInProgress(list, d.int("turn"), text = delta)
            }
            "chunkrow/reasoning-chunks" -> {
                val delta = d.arr("texts").orEmpty()
                    .mapNotNull { it.asPrim()?.contentOrNull }
                    .joinToString("")
                appendToInProgress(list, d.int("turn"), reasoning = delta)
            }
            "chunkrow/tool-call-chunks" -> {
                val callId = d.str("id") ?: return list
                val name = d.str("name")
                val args = d.arr("args").orEmpty().mapNotNull { it.asPrim()?.contentOrNull }.joinToString("")
                ensureTool(list, d.int("turn"), ToolCallInfo(callId, name ?: "", args))
            }
            "tool/call" -> {
                val callId = d.str("callId") ?: return list
                val info = ToolCallInfo(callId, d.str("name") ?: "", d.str("arguments") ?: "")
                ensureTool(list, d.int("turn"), info)
            }
            "tool/result" -> applyToolResult(list, d)
            "turn/end" -> settleTurn(list, d.int("turn"))
            "agent-preset/selected" -> {
                d.str("agentPreset")?.let { _preset.value = it }
                list
            }
            "session/title" -> {
                d.str("title")?.let { _title.value = it }
                list
            }
            "model/selection" -> {
                val p = d.str("provider")
                val m = d.str("model")
                if (p != null && m != null) _model.value = ModelSelection(p, m, d.str("reasoningEffort"))
                list
            }
            "request/context" -> {
                val p = d.str("provider")
                val m = d.str("model")
                if (p != null && m != null) {
                    val cur = _model.value
                    _model.value = ModelSelection(p, m, cur?.reasoningEffort)
                }
                list
            }
            else -> list
        }
    }

    private fun applyUserMessage(list: List<ChatMessage>, e: RawEvent): List<ChatMessage> {
        val m = e.data
        // Internal context injections (agent-instructions / workspace-context /
        // subagent relays / tool echoes) ride as user/message too — the desktop
        // UI hides them, so we must not render them as user bubbles either.
        val sourceKind = m.obj("source")?.str("kind")
        if (sourceKind != null && sourceKind != "user") return list
        val id = m.str("id") ?: return list
        var text = ""
        var images = 0
        val imageData = mutableListOf<ImageRef>()
        for (b in m.arr("content").orEmpty()) {
            val bo = b.asObj() ?: continue
            when (bo.str("type")) {
                "text" -> text += bo.str("text") ?: ""
                "image" -> {
                    images++
                    val data = bo.str("data")
                    if (!data.isNullOrBlank()) imageData += ImageRef(bo.str("mediaType") ?: "image/png", data)
                }
            }
        }
        // Retire the optimistic local bubble that mirrors this echo. When the
        // echo carries no inline image data (attachments live server-side),
        // keep the locally picked images so the sender still sees their own.
        val localIdx = list.indexOfFirst { it.id.startsWith("local-") && it.text == text }
        var carried = emptyList<ImageRef>()
        val base = if (localIdx >= 0) {
            carried = list[localIdx].images
            list.filterIndexed { i, _ -> i != localIdx }
        } else list
        val visible = if (imageData.isNotEmpty()) imageData else if (images > 0) carried else emptyList()
        return upsert(base, ChatMessage(id, Role.USER, text = text, images = visible, imageCount = images, time = e.time, seq = e.seq, pending = false))
    }

    /** Optimistic user bubble shown the instant the prompt is accepted. */
    fun addLocalUser(text: String, imageCount: Int, images: List<ImageRef> = emptyList()) {
        val msg = ChatMessage(
            id = "local-" + System.nanoTime(),
            role = Role.USER,
            text = text,
            images = images,
            imageCount = imageCount,
            time = System.currentTimeMillis(),
            seq = watermark + 1,
            pending = false,
        )
        _messages.value = _messages.value + msg
    }

    private fun applyAssistantMessage(list: List<ChatMessage>, e: RawEvent): List<ChatMessage> {
        val d = e.data
        val m = d.obj("message") ?: d
        val id = m.str("id") ?: return list
        val turn = d.int("turn")
        var text = ""
        var reasoning = ""
        var images = 0
        for (b in m.arr("content").orEmpty()) {
            val bo = b.asObj() ?: continue
            when (bo.str("type")) {
                "text" -> text += bo.str("text") ?: ""
                "reasoning" -> reasoning += bo.str("text") ?: ""
                "image" -> images++
                "tool-call" -> {
                    val callId = bo.str("id") ?: ""
                    ensureTool(list, turn, ToolCallInfo(callId, bo.str("name") ?: "", bo.str("arguments") ?: ""))
                }
            }
        }
        // Merge with a placeholder created by chunks for the same turn, and with
        // an existing entry of the same id (in-place replacement).
        val placeholder = turn?.let { t ->
            list.firstOrNull { it.role == Role.ASSISTANT && it.pending && it.id.startsWith("pending-") && it.turn == t }
        }
        val existing = list.firstOrNull { it.id == id }
        val merged = ChatMessage(
            id = id,
            role = Role.ASSISTANT,
            text = text.ifEmpty { existing?.text ?: placeholder?.text ?: "" },
            reasoning = reasoning.ifEmpty { existing?.reasoning ?: placeholder?.reasoning ?: "" },
            tools = existing?.tools ?: placeholder?.tools ?: emptyList(),
            imageCount = images,
            time = e.time,
            seq = e.seq,
            pending = true,
            turn = turn,
        )
        // Host-side provider retries can persist the same step text twice under
        // different message ids (both copies may still be pending when applied,
        // e.g. inside one history batch), so match regardless of pending state.
        // Local "pending-" placeholders (delta/chunkrow accumulations) are not
        // persisted messages: their text may legitimately equal the arriving
        // message (that is the merge case above), so they must not count as
        // duplicates here — the retire pass below cleans them up instead.
        // The length floor keeps short legitimate echoes ("好的") from merging.
        if (existing == null && merged.text.length >= 6 &&
            list.any {
                it.role == Role.ASSISTANT && it.id != id && !it.id.startsWith("pending-") &&
                    it.text == merged.text
            }
        ) {
            return list
        }
        var out = upsert(list, merged)
        if (placeholder != null && placeholder.id != id) out = out.filterNot { it.id == placeholder.id }
        // Belt & braces: retire any other pending placeholder for this turn or
        // carrying the identical text (chunkrow vs raw-chunk double coverage).
        out = out.filterNot {
            it.id != id && it.role == Role.ASSISTANT && it.pending && it.id.startsWith("pending-") &&
                ((turn != null && it.turn == turn) ||
                    (it.text.isNotEmpty() && (it.text == merged.text || merged.text.startsWith(it.text))))
        }
        return out
    }

    private fun applyAssistantChunk(list: List<ChatMessage>, d: JsonObject): List<ChatMessage> {
        val turn = d.int("turn")
        val c = d.obj("chunk") ?: return list
        return when (c.str("type")) {
            "text-delta" -> appendToInProgress(list, turn, text = c.str("text") ?: "")
            "reasoning-delta" -> appendToInProgress(list, turn, reasoning = c.str("text") ?: "")
            "tool-call-delta" -> {
                val callId = c.str("id") ?: return list
                ensureTool(list, turn, ToolCallInfo(callId, c.str("name") ?: "", c.str("argumentsDelta") ?: ""))
            }
            else -> list
        }
    }

    private fun targetIndex(list: List<ChatMessage>, turn: Int?): Int {
        // Prefer the pending assistant message of the turn; else the last pending one.
        val idx = list.indexOfLast {
            it.role == Role.ASSISTANT && it.pending && (turn == null || it.turn == turn || it.turn == null)
        }
        return idx
    }

    private fun appendToInProgress(
        list: List<ChatMessage>,
        turn: Int?,
        text: String = "",
        reasoning: String = "",
    ): List<ChatMessage> {
        if (text.isEmpty() && reasoning.isEmpty()) return list
        val idx = targetIndex(list, turn)
        if (idx >= 0) {
            val m = list[idx]
            val updated = m.copy(
                text = m.text + text,
                reasoning = m.reasoning + reasoning,
                turn = m.turn ?: turn,
            )
            return list.toMutableList().also { it[idx] = updated }
        }
        val placeholderId = "pending-${turn ?: -1}-${list.size}"
        return list + ChatMessage(
            id = placeholderId,
            role = Role.ASSISTANT,
            text = text,
            reasoning = reasoning,
            pending = true,
            turn = turn,
        )
    }

    private fun ensureTool(list: List<ChatMessage>, turn: Int?, info: ToolCallInfo): List<ChatMessage> {
        val idx = targetIndex(list, turn)
        if (idx >= 0) {
            val m = list[idx]
            val tools = m.tools.toMutableList()
            val existing = tools.indexOfFirst { it.callId == info.callId }
            if (existing >= 0) {
                val t = tools[existing]
                tools[existing] = t.copy(
                    name = info.name.ifEmpty { t.name },
                    arguments = t.arguments + info.arguments,
                )
            } else {
                tools.add(info.copy(arguments = if (info.name.isEmpty()) info.arguments else info.arguments))
            }
            return list.toMutableList().also { it[idx] = m.copy(tools = tools, turn = m.turn ?: turn) }
        }
        // No assistant message yet: create a placeholder hosting the tool call.
        val placeholderId = "pending-${turn ?: -1}-${list.size}"
        return list + ChatMessage(
            id = placeholderId,
            role = Role.ASSISTANT,
            tools = listOf(info),
            pending = true,
            turn = turn,
        )
    }

    private fun applyToolResult(list: List<ChatMessage>, d: JsonObject): List<ChatMessage> {
        val turn = d.int("turn")
        val msg = d.obj("message") ?: return list
        val callId = msg.arr("content").orEmpty()
            .mapNotNull { it.asObj() }
            .firstOrNull { it.str("type") == "tool-result" }
            ?.str("toolCallId") ?: return list
        var resultText = ""
        var isError = false
        for (b in msg.arr("content").orEmpty()) {
            val bo = b.asObj() ?: continue
            if (bo.str("type") == "tool-result") {
                isError = bo.bool("isError") == true
                for (inner in bo.arr("content").orEmpty()) {
                    val io = inner.asObj() ?: continue
                    if (io.str("type") == "text") resultText += io.str("text") ?: ""
                }
            }
        }
        val idx = list.indexOfLast { m ->
            m.role == Role.ASSISTANT && (turn == null || m.turn == turn) && m.tools.any { it.callId == callId }
        }
        if (idx < 0) return list
        val m = list[idx]
        val tools = m.tools.map { t ->
            if (t.callId == callId) t.copy(result = resultText.take(4000), isError = isError) else t
        }
        return list.toMutableList().also { it[idx] = m.copy(tools = tools) }
    }

    private fun settleTurn(list: List<ChatMessage>, turn: Int?): List<ChatMessage> {
        var changed = false
        val out = list.map { m ->
            if (m.role == Role.ASSISTANT && m.pending && (turn == null || m.turn == null || m.turn == turn)) {
                changed = true
                m.copy(pending = false)
            } else m
        }
        return if (changed) out else list
    }

    private fun upsert(list: List<ChatMessage>, msg: ChatMessage): List<ChatMessage> {
        val idx = list.indexOfFirst { it.id == msg.id }
        return if (idx >= 0) {
            list.toMutableList().also { it[idx] = msg }
        } else {
            list + msg
        }
    }
}
