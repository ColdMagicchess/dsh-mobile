package com.example.DSH_Mobile.dsh

import kotlinx.serialization.json.JsonObject

/** One raw DSH session event: { type, seq, time, data }. */
data class RawEvent(val type: String, val seq: Long, val time: Long?, val data: JsonObject)

data class ModelSelection(val provider: String, val model: String, val reasoningEffort: String? = null)

/** One agent-preset roster row (agentPresets/list). */
data class AgentPresetRow(
    val id: String,
    val name: String? = null,
    val description: String? = null,
    val isDefault: Boolean = false,
    val broken: String? = null,
) {
    val label: String
        get() = name?.takeIf { it.isNotBlank() } ?: id
}

data class SessionSummary(
    val sessionId: String,
    val title: String?,
    val updatedAt: Long,
    val running: Boolean,
    val blank: Boolean,
    val cwd: String?,
    val model: ModelSelection?,
    val agentPreset: String? = null,
)

data class ImageRef(val mediaType: String, val dataBase64: String, val name: String? = null)

data class WorkspaceOption(val workspaceId: String?, val path: String, val title: String?) {
    val label: String
        get() = title?.takeIf { it.isNotBlank() }
            ?: path.trimEnd('\\', '/').split('\\', '/').lastOrNull { it.isNotBlank() }
            ?: path
}

data class ToolCallInfo(
    val callId: String,
    val name: String,
    val arguments: String,
    val result: String? = null,
    val isError: Boolean = false,
)

enum class Role { USER, ASSISTANT, SYSTEM, TOOL }

data class ChatMessage(
    val id: String,
    val role: Role,
    val text: String = "",
    val reasoning: String = "",
    val tools: List<ToolCallInfo> = emptyList(),
    val imageCount: Int = 0,
    val images: List<ImageRef> = emptyList(),
    val time: Long? = null,
    val seq: Long = 0,
    val pending: Boolean = false,
    val turn: Int? = null,
)

data class ModelGroup(val id: String, val name: String, val models: List<ModelEntry>)

data class ModelEntry(
    val id: String,
    val name: String,
    val efforts: List<String>,
    val defaultEffort: String?,
)
