package com.example.DSH_Mobile.vm

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.DSH_Mobile.dsh.AgentPresetRow
import com.example.DSH_Mobile.dsh.ChatMessage
import com.example.DSH_Mobile.dsh.DshApiException
import com.example.DSH_Mobile.dsh.DshClient
import com.example.DSH_Mobile.dsh.ImageRef
import com.example.DSH_Mobile.dsh.MessageStore
import com.example.DSH_Mobile.dsh.ModelGroup
import com.example.DSH_Mobile.dsh.ModelSelection
import com.example.DSH_Mobile.dsh.SessionSummary
import com.example.DSH_Mobile.dsh.WorkspaceOption
import com.example.DSH_Mobile.dsh.obj
import com.example.DSH_Mobile.dsh.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.WebSocket

data class PendingImage(
    val uri: String,
    val mediaType: String,
    val base64: String,
    val thumb: ImageBitmap?,
    val tooBig: Boolean = false,
)

class ChatViewModel : ViewModel() {

    private val repo = Graph.repo
    private val settings = Graph.settings
    val store = MessageStore()

    val messages: StateFlow<List<ChatMessage>> = store.messages
    val liveTitle = store.title
    val liveModel = store.model
    val livePreset = store.preset

    private val _current = MutableStateFlow<SessionSummary?>(null)
    val current: StateFlow<SessionSummary?> = _current.asStateFlow()

    private val _mode = MutableStateFlow("steer")
    val mode: StateFlow<String> = _mode.asStateFlow()

    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    val pending: StateFlow<Boolean> = messages
        .map { list -> list.any { it.pending } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _catalog = MutableStateFlow<List<ModelGroup>?>(null)
    val catalog: StateFlow<List<ModelGroup>?> = _catalog.asStateFlow()

    private val _pendingImages = MutableStateFlow<List<PendingImage>>(emptyList())
    val pendingImages: StateFlow<List<PendingImage>> = _pendingImages.asStateFlow()

    private val _presets = MutableStateFlow<List<AgentPresetRow>?>(null)
    val presets: StateFlow<List<AgentPresetRow>?> = _presets.asStateFlow()
    private val _presetsError = MutableStateFlow<String?>(null)
    val presetsError: StateFlow<String?> = _presetsError.asStateFlow()

    private var streamJob: Job? = null
    private var openedSessionId: String? = null

    /** 草稿态：未落地的新对话，发送第一条消息时才真正 session/create。 */
    private var isDraft = false
    private var draftCwd: String? = null
    private var pendingModel: ModelSelection? = null
    private val _draftModel = MutableStateFlow<ModelSelection?>(null)
    val draftModel: StateFlow<ModelSelection?> = _draftModel.asStateFlow()

    /** 草稿态选中的智能体预设；发送建会话时随 session/create 下发。 */
    private var pendingPreset: String? = null
    private val _draftPreset = MutableStateFlow<String?>(null)
    val draftPreset: StateFlow<String?> = _draftPreset.asStateFlow()
    var onSessionCreated: ((SessionSummary) -> Unit)? = null

    private val _workspaces = MutableStateFlow<List<WorkspaceOption>>(emptyList())
    val workspaces: StateFlow<List<WorkspaceOption>> = _workspaces.asStateFlow()
    private val _selectedWorkspace = MutableStateFlow<String?>(null)
    val selectedWorkspace: StateFlow<String?> = _selectedWorkspace.asStateFlow()

    /** 当前工作区的挂载 id；桌面端按它分组，建会话时优先于 cwd 传入。 */
    private var selectedWorkspaceId: String? = null

    fun syncWorkspaces(sessions: List<SessionSummary>, defaultCwd: String?) {
        viewModelScope.launch {
            var list = runCatching { repo.listWorkspaces() }.getOrDefault(emptyList())
            if (list.isEmpty()) {
                list = sessions.mapNotNull { it.cwd?.takeIf { c -> c.isNotBlank() } }
                    .distinct()
                    .map { WorkspaceOption(null, it, null) }
            }
            _workspaces.value = list
            if (_selectedWorkspace.value == null) {
                _selectedWorkspace.value = defaultCwd ?: list.firstOrNull()?.path
            }
            selectedWorkspaceId = list.firstOrNull { it.path == _selectedWorkspace.value }?.workspaceId
        }
    }

    fun selectWorkspace(path: String) {
        _selectedWorkspace.value = path
        selectedWorkspaceId = _workspaces.value.firstOrNull { it.path == path }?.workspaceId
        newDraft(path, null)
    }

    fun newDraft(cwd: String?, modelHint: ModelSelection?) {
        val effective = _selectedWorkspace.value ?: cwd
        if (isDraft && openedSessionId == null && draftCwd == effective) return
        isDraft = true
        openedSessionId = null
        _current.value = null
        _error.value = null
        _draftModel.value = pendingModel ?: modelHint
        _draftPreset.value = pendingPreset
        draftCwd = effective
        streamJob?.cancel()
        store.reset()
    }

    fun open(s: SessionSummary) {
        if (openedSessionId == s.sessionId && !isDraft) return
        isDraft = false
        _draftModel.value = null
        _draftPreset.value = null
        openedSessionId = s.sessionId
        _current.value = s
        // 顶部工作区跟随会话所属工作区（桌面端按挂载分组，cwd 即工作区路径）
        s.cwd?.takeIf { it.isNotBlank() }?.let { cwd ->
            _selectedWorkspace.value = cwd
            selectedWorkspaceId = _workspaces.value.firstOrNull { it.path == cwd }?.workspaceId
        }
        _error.value = null
        streamJob?.cancel()
        store.reset()
        streamJob = viewModelScope.launch { runStream(s.sessionId) }
        viewModelScope.launch {
            runCatching { settings.load().mode }.getOrNull()?.let { saved ->
                if (saved == "queue" || saved == "steer") _mode.value = saved
            }
        }
    }

    /**
     * Live stream over the WS mux with automatic reconnect; after two failed
     * attempts it degrades to 2s polling of session/inspect (seq watermark
     * still dedupes), matching the README realtime requirement.
     */
    private suspend fun runStream(sessionId: String) {
        if (Graph.client.channel == "plugin") runPluginStream(sessionId) else runCoreStream(sessionId)
    }

    private suspend fun runCoreStream(sessionId: String) {
        var attempt = 0
        while (viewModelScope.isActive && openedSessionId == sessionId) {
            attempt++
            val frames = Channel<JsonObject>(Channel.UNLIMITED)
            var ws: WebSocket? = null
            try {
                ws = Graph.client.openFollow(
                    sessionId = sessionId,
                    onFrame = { frames.trySend(it) },
                    onTerminal = { frames.close() },
                )
                while (true) {
                    val first = frames.receiveCatching().getOrNull() ?: break
                    val batch = ArrayList<JsonObject>(32)
                    batch.add(first)
                    while (batch.size < 200) {
                        val more = frames.tryReceive().getOrNull() ?: break
                        batch.add(more)
                    }
                    store.applyFrames(batch)
                }
            } catch (e: CancellationException) {
                runCatching { ws?.cancel() }
                throw e
            } catch (t: Throwable) {
                _error.value = t.message ?: "实时流中断"
            } finally {
                runCatching { ws?.cancel() }
            }
            if (!viewModelScope.isActive || openedSessionId != sessionId) break
            if (attempt >= 2) catchUp(sessionId)
            delay(if (attempt >= 2) 2000L else 1500L)
        }
    }

    private suspend fun runPluginStream(sessionId: String) {
        try {
            store.applyRecords(repo.history(sessionId))
        } catch (t: Throwable) {
            _error.value = t.message ?: "加载历史失败"
        }
        // The plugin's live path is session.pending polling (the SSE mux only
        // carries approvals/questions). 200ms matches the web client cadence.
        var failures = 0
        while (viewModelScope.isActive && openedSessionId == sessionId) {
            try {
                store.applyRecords(repo.pendingEvents(sessionId))
                failures = 0
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                failures++
                if (failures % 5 == 1) _error.value = t.message ?: "无法获取会话更新"
            }
            delay(if (failures == 0) 200L else 1500L)
        }
    }

    private suspend fun catchUp(sessionId: String) {
        try {
            store.applyRecords(repo.catchUpEvents(sessionId))
        } catch (t: Throwable) {
            _error.value = t.message ?: "无法获取会话更新"
        }
    }

    fun setMode(m: String) {
        if (m != "queue" && m != "steer") return
        _mode.value = m
        viewModelScope.launch { runCatching { settings.saveMode(m) } }
    }

    fun send(text: String, images: List<ImageRef>) {
        if (text.isBlank() && images.isEmpty()) return
        viewModelScope.launch {
            _sending.value = true
            try {
                var s = _current.value
                if (s == null) {
                    // Draft → materialize the session first (Kimi logic).
                    // 优先 workspaceId（桌面按挂载分组）；无 id 时回退 cwd。
                    val id = if (selectedWorkspaceId != null) {
                        repo.createSession(workspaceId = selectedWorkspaceId, agentPreset = pendingPreset)
                    } else {
                        repo.createSession(cwd = draftCwd, agentPreset = pendingPreset)
                    }
                    s = SessionSummary(id, null, System.currentTimeMillis(), true, false, draftCwd, null)
                    openedSessionId = id
                    isDraft = false
                    _current.value = s
                    store.reset()
                    streamJob?.cancel()
                    streamJob = launch { runStream(id) }
                    onSessionCreated?.invoke(s)
                    pendingModel?.let { pm ->
                        runCatching { repo.selectModel(id, pm.provider, pm.model, pm.reasoningEffort) }
                        pendingModel = null
                    }
                    pendingPreset = null
                    _draftPreset.value = null
                }
                repo.prompt(s.sessionId, _mode.value, text, images)
                store.addLocalUser(text, images.size, images)
                _pendingImages.value = emptyList()
            } catch (t: Throwable) {
                _error.value = t.message ?: "发送失败"
            } finally {
                _sending.value = false
            }
        }
    }

    fun stop() {
        val s = _current.value ?: return
        viewModelScope.launch {
            try {
                repo.cancel(s.sessionId)
            } catch (t: Throwable) {
                _error.value = t.message ?: "停止失败"
            }
        }
    }

    fun loadCatalog() {
        viewModelScope.launch {
            try {
                _catalog.value = repo.modelCatalog(_current.value?.sessionId)
            } catch (t: Throwable) {
                _error.value = t.message ?: "获取模型列表失败"
            }
        }
    }

    fun dismissCatalog() {
        _catalog.value = null
    }

    /** 智能体预设名单；每次展开都重取，失败保留上次结果并把原因显示在弹层里。 */
    fun loadPresets() {
        viewModelScope.launch {
            _presetsError.value = null
            try {
                val list = repo.listAgentPresets()
                Log.i(TAG, "roster ok: channel=${Graph.client.channel} n=${list.size} ids=${list.joinToString(",") { it.id }}")
                _presets.value = list
            } catch (t: Throwable) {
                Log.e(TAG, "roster failed: channel=${Graph.client.channel}", t)
                _presetsError.value = t.message ?: "加载失败"
                if (_presets.value == null) _presets.value = emptyList()
            }
        }
    }

    /**
     * 草稿态记住选择、建会话时生效；已有会话走 agentPresets/select（会话
     * 开始后宿主拒绝：agent-preset-locked）。
     */
    fun pickPreset(row: AgentPresetRow) {
        val s = _current.value
        if (s == null) {
            pendingPreset = row.id
            _draftPreset.value = row.id
            return
        }
        if (Graph.client.channel == "plugin") {
            _error.value = "插件通道下已有会话不能切换预设；新建对话时选择即可生效"
            return
        }
        viewModelScope.launch {
            try {
                repo.selectAgentPreset(s.sessionId, row.id)
            } catch (e: DshApiException) {
                _error.value = if (e.code == "agent-preset-locked") {
                    "会话已开始，智能体预设不可再切换"
                } else {
                    e.message ?: "切换预设失败"
                }
            } catch (t: Throwable) {
                _error.value = t.message ?: "切换预设失败"
            }
        }
    }

    fun pickModel(provider: String, model: String, effort: String?) {
        val s = _current.value
        if (s == null) {
            // Draft: remember and apply right after the session materializes.
            pendingModel = ModelSelection(provider, model, effort)
            _draftModel.value = pendingModel
            _catalog.value = null
            return
        }
        viewModelScope.launch {
            try {
                repo.selectModel(s.sessionId, provider, model, effort)
                _catalog.value = null
            } catch (t: Throwable) {
                _error.value = t.message ?: "切换模型失败"
            }
        }
    }

    fun addImages(uris: List<Uri>) {
        viewModelScope.launch(Dispatchers.IO) {
            val loaded = mutableListOf<PendingImage>()
            for (uri in uris.take(MAX_IMAGES)) {
                runCatching<PendingImage?> {
                    val cr = Graph.app.contentResolver
                    val mime = cr.getType(uri) ?: "image/jpeg"
                    if (mime !in ALLOWED_MEDIA) return@runCatching null
                    val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching null
                    if (bytes.size > MAX_IMAGE_BYTES) {
                        loaded.add(PendingImage(uri.toString(), mime, "", null, tooBig = true))
                        return@runCatching null
                    }
                    val thumb = decodeSampled(bytes, 256)?.asImageBitmap()
                    PendingImage(
                        uri = uri.toString(),
                        mediaType = mime,
                        base64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
                        thumb = thumb,
                    )
                }.getOrNull()?.let { loaded.add(it) }
            }
            withContext(Dispatchers.Main) {
                _pendingImages.value = (_pendingImages.value + loaded)
                    .distinctBy { it.uri }
                    .take(MAX_IMAGES)
            }
        }
    }

    fun removeImage(uri: String) {
        _pendingImages.value = _pendingImages.value.filterNot { it.uri == uri }
    }

    fun attachedRefs(): List<ImageRef> =
        _pendingImages.value.filter { !it.tooBig && it.base64.isNotBlank() }
            .map { ImageRef(it.mediaType, it.base64) }

    fun dismissError() {
        _error.value = null
    }

    private fun decodeSampled(bytes: ByteArray, target: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= target) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    override fun onCleared() {
        streamJob?.cancel()
    }

    companion object {
        private const val TAG = "DshPreset"
        const val MAX_IMAGES = 10
        const val MAX_IMAGE_BYTES = 20 * 1024 * 1024
        val ALLOWED_MEDIA = setOf("image/png", "image/jpeg", "image/webp", "image/gif")
    }
}
