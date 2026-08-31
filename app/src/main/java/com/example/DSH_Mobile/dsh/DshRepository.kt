package com.example.DSH_Mobile.dsh

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.util.TimeZone
import java.util.UUID

/** High-level DSH operations; routes each call through the active channel. */
class DshRepository(private val client: DshClient) {

    private val plugin: Boolean get() = client.channel == "plugin"

    // ---------------- sessions ----------------

    suspend fun listSessions(): List<SessionSummary> {
        val value = if (plugin) {
            client.rpcBff("session.list", buildJsonObject { })
        } else {
            client.rpc("session/list", buildJsonObject { put("_request", buildJsonObject { }) })
        }
        val items = value.arr("items") ?: value.arr("sessions") ?: return emptyList()
        return items.mapNotNull { it.asObj()?.toSessionSummary() }
    }

    private fun JsonObject.toSessionSummary(): SessionSummary? {
        val id = str("sessionId") ?: str("id") ?: return null
        // Core puts projections.values.title as a plain string; the plugin
        // BFF may flatten title/displayTitle at item level.
        val title = str("title")
            ?: str("displayTitle")
            ?: prim("projections", "values", "title")?.contentOrNull?.takeIf { it.isNotBlank() }
        val model = obj("projections", "values", "modelSelection", "lastUsed")?.let { ms ->
            val p = ms.str("provider")
            val m = ms.str("model")
            if (p != null && m != null) ModelSelection(p, m, ms.str("reasoningEffort")) else null
        }
        val agentPreset = prim("projections", "values", "agentPreset")?.contentOrNull
        return SessionSummary(
            sessionId = id,
            title = title,
            updatedAt = long("updatedAt") ?: long("updated") ?: 0L,
            running = bool("running") == true,
            blank = bool("blank") == true,
            cwd = str("cwd") ?: str("path"),
            model = model,
            agentPreset = agentPreset,
        )
    }

    suspend fun createSession(cwd: String? = null, agentPreset: String? = null, workspaceId: String? = null): String {
        val req = buildJsonObject {
            cwd?.takeIf { it.isNotBlank() }?.let { put("cwd", it) }
            agentPreset?.takeIf { it.isNotBlank() }?.let { put("agentPreset", it) }
            workspaceId?.takeIf { it.isNotBlank() }?.let { put("workspaceId", it) }
        }
        val value = if (plugin) client.rpcBff("session.create", req)
        else client.rpc("session/create", buildJsonObject { put("request", req) })
        return value.str("sessionId") ?: value.str("id")
            ?: throw DshApiException("bad-value", "create：响应缺少 sessionId")
    }

    // ---------------- chat ----------------

    suspend fun prompt(sessionId: String, mode: String, text: String, images: List<ImageRef>) {
        val content = buildJsonArray {
            if (text.isNotBlank()) {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", text)
                })
            }
            for (img in images) {
                add(buildJsonObject {
                    put("type", "image")
                    put("mediaType", img.mediaType)
                    put("data", img.dataBase64)
                    img.name?.let { put("name", it) }
                })
            }
        }
        if (plugin) {
            // The BFF injects the requestId itself.
            client.rpcBff("session.prompt", buildJsonObject {
                put("sessionId", sessionId)
                put("mode", mode.ifBlank { "queue" })
                put("content", content)
                put("clientTimeZone", TimeZone.getDefault().id)
            })
        } else {
            client.rpc("session/prompt", buildJsonObject {
                put("request", buildJsonObject {
                    put("requestId", UUID.randomUUID().toString())
                    put("sessionId", sessionId)
                    put("mode", mode.ifBlank { "steer" })
                    put("content", content)
                    put("clientTimeZone", TimeZone.getDefault().id)
                })
            })
        }
    }

    /** Plugin channel history (the core channel gets history inside the follow snapshot). */
    suspend fun history(sessionId: String, maxMessages: Int = 200): List<JsonElement> {
        if (!plugin) return emptyList()
        val value = client.rpcBff("session.history", buildJsonObject {
            put("sessionId", sessionId)
            put("maxMessages", maxMessages)
        })
        return (value.arr("events") ?: value.arr("records") ?: return emptyList()).toList()
    }

    suspend fun cancel(sessionId: String) {
        if (plugin) client.rpcBff("session.cancel", buildJsonObject { put("sessionId", sessionId) })
        else client.rpc("session/cancel", buildJsonObject {
            put("request", buildJsonObject { put("sessionId", sessionId) })
        })
    }

    /**
     * Plugin channel live events: the BFF keeps a persistent session.follow
     * accumulator per session; polling session.pending is the designed
     * frp-friendly realtime path (the SSE mux carries approvals only).
     */
    suspend fun pendingEvents(sessionId: String): List<JsonElement> {
        val value = client.rpcBff("session.pending", buildJsonObject { put("sessionId", sessionId) })
        return (value.arr("events") ?: return emptyList()).toList()
    }

    suspend fun listWorkspaces(): List<WorkspaceOption> {
        if (!plugin) return emptyList()
        val value = client.rpcBff("workspace.list", buildJsonObject { })
        return value.arr("items").orEmpty().mapNotNull {
            val o = it.asObj() ?: return@mapNotNull null
            val path = o.str("path") ?: return@mapNotNull null
            WorkspaceOption(o.str("workspaceId"), path, o.str("title"))
        }
    }

    // ---------------- models ----------------

    suspend fun selectModel(sessionId: String, provider: String, model: String, reasoningEffort: String?) {
        val req = buildJsonObject {
            put("sessionId", sessionId)
            put("provider", provider)
            put("model", model)
            reasoningEffort?.takeIf { it.isNotBlank() }?.let { put("reasoningEffort", it) }
        }
        if (plugin) client.rpcBff("session.selectModel", req)
        else client.rpc("session/selectModel", buildJsonObject { put("request", req) })
    }

    suspend fun modelCatalog(sessionId: String?): List<ModelGroup> {
        if (plugin) {
            val value = client.rpcBff("session.models", buildJsonObject { })
            // The BFF proxies modelCatalog: {groups:[{id,name,models:[…]}], current, …}
            val raw = value.arr("groups") ?: value.arr("models") ?: value.arr("items")
                ?: return emptyList()
            val first = raw.firstOrNull()?.asObj()
            val isGrouped = first?.containsKey("models") == true
            return if (isGrouped) raw.mapNotNull { g ->
                val go = g.asObj() ?: return@mapNotNull null
                val gid = go.str("id") ?: return@mapNotNull null
                val models = go.arr("models").orEmpty().mapNotNull { m ->
                    val mo = m.asObj() ?: return@mapNotNull null
                    val mid = mo.str("id") ?: return@mapNotNull null
                    val efforts = mo.obj("reasoning")?.arr("efforts").orEmpty().mapNotNull { e ->
                        e.asObj()?.str("id") ?: e.asPrim()?.contentOrNull
                    }
                    ModelEntry(mid, mo.str("name") ?: "", efforts, mo.obj("reasoning")?.str("defaultEffort"))
                }
                ModelGroup(gid, go.str("name") ?: "", models)
            } else {
                val entries = raw.mapNotNull { m ->
                    val mo = m.asObj() ?: return@mapNotNull null
                    val mid = mo.str("id") ?: mo.str("model") ?: return@mapNotNull null
                    ModelEntry(mid, mo.str("name") ?: mid, emptyList(), null)
                }
                listOf(ModelGroup("models", "模型", entries))
            }
        }
        val value = client.rpc("session/modelCatalog")
        return value.arr("groups").orEmpty().mapNotNull { g ->
            val go = g.asObj() ?: return@mapNotNull null
            val gid = go.str("id") ?: return@mapNotNull null
            val models = go.arr("models").orEmpty().mapNotNull { m ->
                val mo = m.asObj() ?: return@mapNotNull null
                val mid = mo.str("id") ?: return@mapNotNull null
                val efforts = mo.obj("reasoning")?.arr("efforts").orEmpty().mapNotNull { e ->
                    e.asObj()?.str("id") ?: e.asPrim()?.contentOrNull
                }
                ModelEntry(mid, mo.str("name") ?: "", efforts, mo.obj("reasoning")?.str("defaultEffort"))
            }
            ModelGroup(gid, go.str("name") ?: "", models)
        }
    }

    // ---------------- agent presets ----------------

    /** Preset roster: core `agentPresets/list`, or the plugin BFF's `agentPreset.list` proxy. */
    suspend fun listAgentPresets(): List<AgentPresetRow> {
        val value = if (plugin) client.rpcBff("agentPreset.list", buildJsonObject { })
        else client.rpc("agentPresets/list")
        return value.arr("presets").orEmpty().mapNotNull { p ->
            val o = p.asObj() ?: return@mapNotNull null
            val id = o.str("id") ?: return@mapNotNull null
            AgentPresetRow(
                id = id,
                name = o.str("name"),
                description = o.str("description"),
                isDefault = o.bool("isDefault") == true,
                broken = o.str("broken"),
            )
        }
    }

    /**
     * Switch a live session's preset. The host refuses once the conversation
     * has started (error code agent-preset-locked); presets for new sessions
     * ride session.create's agentPreset argument instead. Wire args are flat:
     * agentId (lookup-resolved by the gateway) + agentPreset.
     */
    suspend fun selectAgentPreset(sessionId: String, presetId: String) {
        client.rpc("agentPresets/select", buildJsonObject {
            put("agentId", sessionId)
            put("agentPreset", presetId)
        })
    }

    /** 归档对话：核心走 workspace/archiveSession；插件走 BFF 透传 session.archive。 */
    suspend fun archiveSession(sessionId: String) {
        if (plugin) client.rpcBff("session.archive", buildJsonObject { put("sessionId", sessionId) })
        else client.rpc("workspace/archiveSession", buildJsonObject {
            put("request", buildJsonObject { put("sessionId", sessionId) })
        })
    }

    /** 已归档会话 id（插件通道经 session.archived 返回注册表状态；核心通道暂无来源）。 */
    suspend fun listArchivedSessionIds(): Set<String> {
        if (!plugin) return emptySet()
        val value = client.rpcBff("session.archived", buildJsonObject { })
        return value.arr("archivedSessionIds").orEmpty()
            .mapNotNull { it.asPrim()?.contentOrNull }.toSet()
    }

    // ---------------- search / fallback ----------------

    suspend fun search(query: String): List<Pair<String, String>> {
        if (plugin) throw DshApiException("unsupported", "插件通道暂不支持搜索")
        val value = client.rpc("session/search", buildJsonObject {
            put("request", buildJsonObject { put("query", query) })
        })
        return value.arr("items").orEmpty().mapNotNull { i ->
            val io = i.asObj() ?: return@mapNotNull null
            val sid = io.str("sessionId") ?: return@mapNotNull null
            sid to (io.str("snippet") ?: "")
        }
    }

    /** Catch-up event fetch: core uses inspect, plugin uses session.history. */
    suspend fun catchUpEvents(sessionId: String): List<JsonElement> {
        if (plugin) return history(sessionId, maxMessages = 500)
        val value = client.rpc("session/inspect", buildJsonObject { put("sessionId", sessionId) })
        return value.arr("events").orEmpty().toList()
    }
}
