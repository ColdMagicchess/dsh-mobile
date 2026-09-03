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

/**
 * High-level DSH operations, written against the dsh-web-all 0.3.12 contract:
 * the plugin channel no longer has a /m/api BFF — remote-web-ui exposes the
 * gated `/remote` mirror of the FULL host API, so both channels share one
 * call surface. Only the URL prefix and the credentials differ, and those are
 * handled inside DshClient (apiPrefix + pairing cookie/device id).
 */
class DshRepository(private val client: DshClient) {

    // ---------------- sessions ----------------

    suspend fun listSessions(): List<SessionSummary> {
        val value = client.rpc("session/list", buildJsonObject { put("_request", buildJsonObject { }) })
        val items = value.arr("items") ?: value.arr("sessions") ?: return emptyList()
        return items.mapNotNull { it.asObj()?.toSessionSummary() }
    }

    private fun JsonObject.toSessionSummary(): SessionSummary? {
        val id = str("sessionId") ?: str("id") ?: return null
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
        val value = client.rpc("session/create", buildJsonObject { put("request", req) })
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

    suspend fun cancel(sessionId: String) {
        client.rpc("session/cancel", buildJsonObject {
            put("request", buildJsonObject { put("sessionId", sessionId) })
        })
    }

    /**
     * Workspace picker rows. 0.3.12 source: the dsh-session-archive plugin
     * (bundled in dsh-web-all) exposes `/api/dsh-session-archive/inventory`
     * whose `workspaces` projection carries id/title/path — reachable on the
     * plugin channel through the /remote gate and on the core channel directly.
     * Falls back to a gateway workspace/list probe for hosts without the plugin.
     */
    suspend fun listWorkspaces(): List<WorkspaceOption> {
        runCatching {
            val inv = client.getJson("dsh-session-archive/inventory")
            val rows = inv.arr("workspaces").orEmpty().mapNotNull { w ->
                val o = w.asObj() ?: return@mapNotNull null
                val path = o.str("path") ?: return@mapNotNull null
                WorkspaceOption(o.str("id") ?: o.str("workspaceId"), path, o.str("title"))
            }
            if (rows.isNotEmpty()) return rows
        }
        runCatching {
            val value = client.rpc("workspace/list", buildJsonObject { })
            val rows = value.arr("items").orEmpty().mapNotNull {
                val o = it.asObj() ?: return@mapNotNull null
                val path = o.str("path") ?: return@mapNotNull null
                WorkspaceOption(o.str("workspaceId") ?: o.str("id"), path, o.str("title"))
            }
            if (rows.isNotEmpty()) return rows
        }
        return emptyList()
    }

    // ---------------- models ----------------

    suspend fun selectModel(sessionId: String, provider: String, model: String, reasoningEffort: String?) {
        val req = buildJsonObject {
            put("sessionId", sessionId)
            put("provider", provider)
            put("model", model)
            reasoningEffort?.takeIf { it.isNotBlank() }?.let { put("reasoningEffort", it) }
        }
        client.rpc("session/selectModel", buildJsonObject { put("request", req) })
    }

    suspend fun modelCatalog(sessionId: String?): List<ModelGroup> {
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

    /** Preset roster: the host gateway's agentPresets/list (both channels). */
    suspend fun listAgentPresets(): List<AgentPresetRow> {
        val value = client.rpc("agentPresets/list")
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
     * Switch a live session's preset. Works on both 0.3.12 channels (the /remote
     * mirror forwards the gateway RPC). The host refuses once the conversation
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

    // ---------------- archive ----------------

    /**
     * Archive a session: the host gateway RPC workspace/archiveSession is the
     * primary path; if the host rejects it, fall back to the bundled
     * dsh-session-archive plugin's batch archive route.
     */
    suspend fun archiveSession(sessionId: String) {
        try {
            client.rpc("workspace/archiveSession", buildJsonObject {
                put("request", buildJsonObject { put("sessionId", sessionId) })
            })
        } catch (e: DshApiException) {
            val value = client.postJson(
                "dsh-session-archive/archive",
                buildJsonObject { put("ids", buildJsonArray { add(sessionId) }) },
            )
            for (row in value.arr("results").orEmpty()) {
                val o = row.asObj() ?: continue
                if (o.bool("ok") == false) {
                    throw DshApiException(o.str("reason") ?: "archive-failed", "归档失败：${o.str("reason") ?: "未知原因"}")
                }
            }
        }
    }

    /**
     * 已归档会话 id（桌面端归档/删除后同步隐藏）。0.3.12 来源：dsh-session-archive
     * inventory 的 archivedSessionIds（注册表归档集合），两个通道都可读；
     * 插件未安装时返回空集合（归档仍可用，只是不同步）。
     */
    suspend fun listArchivedSessionIds(): Set<String> {
        return runCatching {
            val inv = client.getJson("dsh-session-archive/inventory")
            inv.arr("archivedSessionIds").orEmpty()
                .mapNotNull { it.asPrim()?.contentOrNull }.toSet()
        }.getOrDefault(emptySet())
    }

    // ---------------- search / fallback ----------------

    suspend fun search(query: String): List<Pair<String, String>> {
        val value = client.rpc("session/search", buildJsonObject {
            put("request", buildJsonObject { put("query", query) })
        })
        return value.arr("items").orEmpty().mapNotNull { i ->
            val io = i.asObj() ?: return@mapNotNull null
            val sid = io.str("sessionId") ?: return@mapNotNull null
            sid to (io.str("snippet") ?: "")
        }
    }

    /**
     * Catch-up fetch while the live socket is down (the store's seq watermark
     * dedupes overlap). 0.3.12: session/inspect is gone and session/page only
     * pages backward with throughSeq <= the journal cursor ("past cursor" is
     * rejected), so first learn the session's projection watermark from
     * session/list (projections.asOfSeq), then page to exactly that point.
     */
    suspend fun catchUpEvents(sessionId: String, watermark: Long): List<JsonElement> {
        val list = client.rpc("session/list", buildJsonObject { put("_request", buildJsonObject { }) })
        val asOfSeq = list.arr("items").orEmpty()
            .firstOrNull { it.asObj()?.str("sessionId") == sessionId }
            ?.asObj()?.obj("projections")?.long("asOfSeq")
            ?: return emptyList()
        if (asOfSeq <= watermark) return emptyList()
        val value = client.rpc("session/page", buildJsonObject {
            put("request", buildJsonObject {
                put("address", buildJsonObject {
                    put("kind", "session")
                    put("sessionId", sessionId)
                })
                put("throughSeq", asOfSeq)
                put("maxMessages", 250)
            })
        })
        // page answers wrapped history records ({type:"event",event}/{type:"chunks",event})
        // — exactly the shape MessageStore.applyRecords already folds.
        return value.arr("records").orEmpty().toList()
    }
}
