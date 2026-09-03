package com.example.DSH_Mobile.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.DSH_Mobile.dsh.DshApiException
import com.example.DSH_Mobile.dsh.ModelSelection
import com.example.DSH_Mobile.dsh.SessionSummary
import com.example.DSH_Mobile.store.ConnectionSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Screen { CONNECT, CHAT }

data class AppUiState(
    val screen: Screen = Screen.CONNECT,
    val booting: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
    val host: String = "",
    val sessions: List<SessionSummary> = emptyList(),
    /** null = 未落地的新对话草稿（Kimi 式：发送后才真正创建会话）。 */
    val current: SessionSummary? = null,
    val defaultCwd: String? = null,
    val defaultModel: ModelSelection? = null,
)

class AppViewModel : ViewModel() {

    private val settings = Graph.settings
    private val client = Graph.client
    private val repo = Graph.repo

    private val _state = MutableStateFlow(AppUiState())
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    private var booted = false

    /** 已归档会话 id：列表过滤 + 归档后本地即时移除。 */
    private var archivedIds: Set<String> = emptySet()

    fun boot() {
        if (booted) return
        booted = true
        viewModelScope.launch {
            val s = runCatching { settings.load() }.getOrDefault(ConnectionSettings())
            if (s.host.isNotBlank() && s.cookie.isNotBlank()) {
                client.host = s.host
                client.cookieValue = s.cookie
                client.cookieNameOverride = s.cookieName
                client.channel = s.channel
                client.deviceId = s.deviceId
                client.setTrustInsecure(s.trustInsecure)
                _state.update { it.copy(booting = false, host = s.host) }
                val probe = runCatching { repo.listSessions() }
                if (probe.isSuccess) {
                    _state.update { it.copy(screen = Screen.CHAT, current = null) }
                    applySessions(probe.getOrDefault(emptyList()))
                } else {
                    val cause = probe.exceptionOrNull()
                    val revoked = cause is DshApiException &&
                        (cause.httpStatus == 401 || cause.httpStatus == 403)
                    if (revoked) settings.saveHostCookie(s.host, "", "", s.channel, s.trustInsecure, "")
                    _state.update {
                        it.copy(
                            screen = Screen.CONNECT,
                            error = "已保存的连接不可用：" + (cause?.message ?: "未知原因"),
                        )
                    }
                }
            } else {
                _state.update { it.copy(booting = false, screen = Screen.CONNECT) }
            }
        }
    }

    /**
     * @param secret the pairing URL (?pair= plugin / ?token= core) or a raw cookie value.
     */
    fun connect(hostInput: String, secret: String, useToken: Boolean, trustInsecure: Boolean) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            try {
                val host = client.normalizeHost(hostInput)
                if (host.isBlank()) throw IllegalArgumentException("请填写主机地址")
                if (secret.isBlank()) throw IllegalArgumentException("请填写配对信息")
                client.host = host
                // Trust must be active BEFORE the pairing request itself —
                // self-signed tunnels (SakuraFrp 等) reject the first
                // HTTPS round-trip otherwise, and the toggle would only
                // take effect on the NEXT app launch.
                client.setTrustInsecure(trustInsecure)
                // A pasted URL is always a pairing link, even in "cookie" mode.
                val result = if (useToken || secret.contains("://")) client.pair(secret)
                else client.adoptManualCookie(secret)
                client.cookieValue = result.cookie
                client.channel = result.channel
                client.deviceId = result.deviceId
                if (result.channel == "core") client.cookieNameOverride = result.cookieName
                val probe = runCatching { repo.listSessions() }
                if (probe.isFailure) throw (probe.exceptionOrNull() ?: IllegalStateException("probe failed"))
                settings.saveHostCookie(host, result.cookie, result.cookieName, result.channel, trustInsecure, result.deviceId)
                _state.update {
                    it.copy(
                        busy = false,
                        screen = Screen.CHAT,
                        current = null,
                        host = host,
                        error = null,
                    )
                }
                applySessions(probe.getOrDefault(emptyList()))
            } catch (t: Throwable) {
                _state.update { it.copy(busy = false, error = t.message ?: "连接失败") }
            }
        }
    }

    fun refreshSessions() {
        viewModelScope.launch {
            try {
                archivedIds = runCatching { repo.listArchivedSessionIds() }.getOrDefault(archivedIds)
                applySessions(repo.listSessions())
            } catch (t: Throwable) {
                _state.update { it.copy(error = t.message ?: "刷新失败") }
            }
        }
    }

    /** 归档对话：立即移出列表；桌面端归档/删除后这里同样随刷新隐藏。 */
    fun archiveSession(sessionId: String) {
        viewModelScope.launch {
            try {
                repo.archiveSession(sessionId)
                archivedIds = archivedIds + sessionId
                _state.update { s -> s.copy(sessions = s.sessions.filterNot { it.sessionId == sessionId }) }
            } catch (t: Throwable) {
                _state.update { it.copy(error = t.message ?: "归档失败") }
            }
        }
    }

    /** Sorted list + the draft defaults (first workspace = most recently active). */
    private fun applySessions(items: List<SessionSummary>) {
        val sorted = items.filterNot { it.sessionId in archivedIds }.sortedByDescending { it.updatedAt }
        _state.update {
            it.copy(
                sessions = sorted,
                defaultCwd = sorted.firstOrNull()?.cwd,
                defaultModel = sorted.firstOrNull()?.model,
                error = null,
            )
        }
    }

    fun openSession(s: SessionSummary) {
        _state.update { it.copy(current = s, screen = Screen.CHAT) }
    }

    /** Kimi-style new chat: a draft until the first message is sent. */
    fun openDraft() {
        _state.update { it.copy(current = null, screen = Screen.CHAT) }
    }

    fun backToConnect() {
        _state.update { it.copy(screen = Screen.CONNECT) }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }
}
