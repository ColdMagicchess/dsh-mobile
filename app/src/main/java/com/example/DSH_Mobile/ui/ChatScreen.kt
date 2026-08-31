package com.example.DSH_Mobile.ui

import android.graphics.BitmapFactory
import android.text.format.DateUtils
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.DSH_Mobile.dsh.AgentPresetRow
import com.example.DSH_Mobile.dsh.ImageRef
import com.example.DSH_Mobile.dsh.ChatMessage
import com.example.DSH_Mobile.dsh.ModelGroup
import com.example.DSH_Mobile.dsh.Role
import com.example.DSH_Mobile.dsh.SessionSummary
import com.example.DSH_Mobile.dsh.ToolCallInfo
import com.example.DSH_Mobile.vm.AppUiState
import com.example.DSH_Mobile.vm.AppViewModel
import com.example.DSH_Mobile.vm.ChatViewModel
import com.example.DSH_Mobile.vm.PendingImage

private const val LONG_MESSAGE_THRESHOLD = 1200

/** 钉底偏移：大偏移会被 LazyList 校正到列表真正的最底部。 */
private const val PIN_TO_BOTTOM_SCROLL_OFFSET = 1_000_000

@Composable
fun ChatScreen(appState: AppUiState, appVm: AppViewModel, vm: ChatViewModel) {
    val session = appState.current

    LaunchedEffect(session?.sessionId) {
        session?.let { vm.open(it) } ?: vm.newDraft(appState.defaultCwd, appState.defaultModel)
    }
    LaunchedEffect(Unit) { vm.onSessionCreated = { appVm.refreshSessions() } }
    LaunchedEffect(appState.sessions, appState.defaultCwd) {
        vm.syncWorkspaces(appState.sessions, appState.defaultCwd)
    }

    val messages by vm.messages.collectAsState()
    val liveTitle by vm.liveTitle.collectAsState()
    val liveModel by vm.liveModel.collectAsState()
    val draftModel by vm.draftModel.collectAsState()
    val pending by vm.pending.collectAsState()
    val mode by vm.mode.collectAsState()
    val sending by vm.sending.collectAsState()
    val error by vm.error.collectAsState()
    val catalog by vm.catalog.collectAsState()
    val images by vm.pendingImages.collectAsState()
    val workspaces by vm.workspaces.collectAsState()
    val selectedWs by vm.selectedWorkspace.collectAsState()
    val presets by vm.presets.collectAsState()
    val presetsError by vm.presetsError.collectAsState()
    val livePreset by vm.livePreset.collectAsState()
    val draftPreset by vm.draftPreset.collectAsState()

    var input by rememberSaveable { mutableStateOf("") }
    var drawerOpen by rememberSaveable { mutableStateOf(false) }
    val slide by animateFloatAsState(
        if (drawerOpen) 1f else 0f,
        spring(dampingRatio = 0.82f, stiffness = 420f),
        label = "drawer-slide",
    )

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(error) {
        error?.let {
            snackbar.showSnackbar(it, duration = SnackbarDuration.Short)
            vm.dismissError()
        }
    }
    // 抽屉打开即刷新（新会话/改名即时可见）
    LaunchedEffect(drawerOpen) {
        if (drawerOpen) appVm.refreshSessions()
    }
    // 宿主生成标题后同步刷新列表
    LaunchedEffect(liveTitle) {
        if (!liveTitle.isNullOrBlank() && session != null) appVm.refreshSessions()
    }

    val modelLabel = (liveModel ?: session?.model ?: draftModel)?.model
    val wsLabel = workspaces.firstOrNull { it.path == selectedWs }?.label
        ?: selectedWs?.trimEnd('\\', '/')?.split('\\', '/')?.lastOrNull { it.isNotBlank() }
        ?: "工作区"

    // 当前智能体预设 id：流内事件 > 草稿暂存 > 会话投影。
    val presetLabel = livePreset ?: draftPreset ?: session?.agentPreset

    val pickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(ChatViewModel.MAX_IMAGES),
    ) { uris ->
        if (uris.isNotEmpty()) vm.addImages(uris)
    }

    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Flat.Accent,
            background = Flat.White,
            surface = Flat.White,
            onSurface = Flat.Ink,
            error = Flat.Danger,
        ),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Flat.White)) {
            val drawerW = maxWidth * 0.74f
            val drawerWpx = with(LocalDensity.current) { drawerW.toPx() }

            // ---------- 聊天主体 ----------
            Surface(
                Modifier.fillMaxSize(),
                color = Flat.White,
                contentColor = Flat.Ink,
            ) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Spacer(Modifier.height(62.dp))
                val headerTitle = liveTitle ?: session?.title
                if (!headerTitle.isNullOrBlank()) {
                    Text(
                        headerTitle,
                        fontSize = 13.sp,
                        color = Flat.Muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 70.dp, end = 16.dp, bottom = 2.dp),
                    )
                }
                MessageList(
                    messages = messages,
                    isDraftEmpty = session == null && messages.isEmpty(),
                    modifier = Modifier.weight(1f),
                )
                InputBar(
                    input = input,
                    onInput = { input = it },
                    images = images,
                    mode = mode,
                    sending = sending,
                    pending = pending,
                    onSend = {
                        vm.send(input, vm.attachedRefs())
                        input = ""
                    },
                    onStop = { vm.stop() },
                    onToggleMode = { vm.setMode(if (mode == "steer") "queue" else "steer") },
                    onRemoveImage = { vm.removeImage(it) },
                    onPickImages = { pickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    presetLabel = presetLabel,
                    presets = presets,
                    presetsError = presetsError,
                    currentPresetId = presetLabel,
                    onOpenPresets = { vm.loadPresets() },
                    onPickPreset = { vm.pickPreset(it) },
                )
            }
            }

            // ---------- 左上悬浮：菜单圆钮 + 模型胶囊 ----------
            Row(
                Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FloatCircle(onClick = { drawerOpen = true }) {
                    Icon(MenuLines, contentDescription = "更多", tint = Flat.Ink, modifier = Modifier.size(20.dp))
                }
                Box {
                    var modelMenu by remember { mutableStateOf(false) }
                    FloatPill(onClick = { vm.loadCatalog(); modelMenu = true }) {
                        Text(
                            modelLabel ?: "选择模型",
                            fontSize = 13.sp,
                            color = Flat.Ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 120.dp),
                        )
                        Icon(
                            Icons.Filled.ArrowDropDown,
                            contentDescription = null,
                            tint = Flat.Muted,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    DropdownMenu(
                        expanded = modelMenu,
                        onDismissRequest = { modelMenu = false },
                        containerColor = Flat.White,
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        val cats = catalog
                        if (cats == null) {
                            DropdownMenuItem(
                                text = { Text("加载中…", fontSize = 13.sp, color = Flat.Muted) },
                                onClick = {},
                                enabled = false,
                            )
                        } else {
                            cats.forEach { g ->
                                if (cats.size > 1) {
                                    DropdownMenuItem(
                                        text = { Text(g.name.ifBlank { g.id }, fontSize = 11.sp, color = Flat.Muted) },
                                        onClick = {},
                                        enabled = false,
                                    )
                                }
                                g.models.forEach { m ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(m.name.ifBlank { m.id }, fontSize = 14.sp, color = Flat.Ink)
                                        },
                                        onClick = {
                                            modelMenu = false
                                            vm.pickModel(g.id, m.id, m.defaultEffort)
                                        },
                                        trailingIcon = {
                                            if (m.id == modelLabel) {
                                                Icon(
                                                    Icons.Filled.Check,
                                                    contentDescription = null,
                                                    tint = Flat.Accent,
                                                    modifier = Modifier.size(18.dp),
                                                )
                                            }
                                        },
                                    )
                                }
                            }
                            if (cats.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("无可用模型", fontSize = 13.sp, color = Flat.Muted) },
                                    onClick = {},
                                    enabled = false,
                                )
                            }
                        }
                    }
                }
                Box {
                    var wsMenu by remember { mutableStateOf(false) }
                    FloatPill(onClick = { wsMenu = true }) {
                        Text(
                            wsLabel,
                            fontSize = 13.sp,
                            color = Flat.Ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 100.dp),
                        )
                        Icon(
                            Icons.Filled.ArrowDropDown,
                            contentDescription = null,
                            tint = Flat.Muted,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    DropdownMenu(
                        expanded = wsMenu,
                        onDismissRequest = { wsMenu = false },
                        containerColor = Flat.White,
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        if (workspaces.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text("暂无工作区", fontSize = 13.sp, color = Flat.Muted) },
                                onClick = {},
                                enabled = false,
                            )
                        }
                        workspaces.forEach { w ->
                            DropdownMenuItem(
                                text = { Text(w.label, fontSize = 14.sp, color = Flat.Ink) },
                                onClick = {
                                    wsMenu = false
                                    vm.selectWorkspace(w.path)
                                },
                                trailingIcon = {
                                    if (w.path == selectedWs) {
                                        Icon(
                                            Icons.Filled.Check,
                                            contentDescription = null,
                                            tint = Flat.Accent,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }

            // ---------- 会话标题：已并入内容列 ----------

            // ---------- 遮罩 ----------
            if (slide > 0.001f) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.13f * slide))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            enabled = slide > 0.5f,
                        ) { drawerOpen = false },
                )
            }

            // ---------- 对话记录抽屉 ----------
            Box(
                Modifier
                    .fillMaxHeight()
                    .width(drawerW)
                    .offset { IntOffset(((slide - 1f) * drawerWpx).toInt(), 0) }
                    .shadow(16.dp, RoundedCornerShape(topEnd = 18.dp, bottomEnd = 18.dp), clip = false)
                    .clip(RoundedCornerShape(topEnd = 18.dp, bottomEnd = 18.dp))
                    .background(Flat.White),
            ) {
                HistoryDrawer(
                    state = appState,
                    currentId = session?.sessionId,
                    onPick = {
                        appVm.openSession(it)
                        drawerOpen = false
                    },
                    onCreate = {
                        appVm.openDraft()
                        drawerOpen = false
                    },
                    onClose = { drawerOpen = false },
                    onSettings = {
                        appVm.backToConnect()
                        drawerOpen = false
                    },
                    onArchive = { appVm.archiveSession(it.sessionId) },
                )
            }

            SnackbarHost(
                hostState = snackbar,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 96.dp),
            )
        }


    }
}

/* ==================== 悬浮按钮 ==================== */

@Composable
private fun FloatCircle(
    onClick: () -> Unit,
    elevation: androidx.compose.ui.unit.Dp = 10.dp,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier
            .size(44.dp)
            .shadow(elevation, CircleShape, clip = false, ambientColor = Color(0x33000000), spotColor = Color(0x6B000000))
            .clip(CircleShape)
            .background(Flat.White)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
        content = { content() },
    )
}

@Composable
private fun FloatPill(onClick: () -> Unit, content: @Composable () -> Unit) {
    Row(
        Modifier
            .height(44.dp)
            .shadow(10.dp, RoundedCornerShape(22.dp), clip = false, ambientColor = Color(0x33000000), spotColor = Color(0x6B000000))
            .clip(RoundedCornerShape(22.dp))
            .background(Flat.White)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = { content() },
    )
}

@Composable
private fun MiniPill(text: String, onClick: () -> Unit, icon: ImageVector? = null) {
    Row(
        Modifier
            .shadow(8.dp, RoundedCornerShape(16.dp), clip = false, ambientColor = Color(0x33000000), spotColor = Color(0x66000000))
            .clip(RoundedCornerShape(16.dp))
            .background(Flat.White)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = Flat.Muted, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(text, fontSize = 11.sp, color = Flat.Label)
    }
}

/* ==================== 消息列表 ==================== */

@Composable
private fun MessageList(
    messages: List<ChatMessage>,
    isDraftEmpty: Boolean,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val thresholdPx = remember(density) { with(density) { 80.dp.toPx() } }

    val nearBottom by remember {
        derivedStateOf {
            val li = listState.layoutInfo
            val last = li.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
            if (last.index != li.totalItemsCount - 1) return@derivedStateOf false
            // 视口末端到列表末端的剩余像素。长消息内部滚动时，条目末端远在
            // 视口之下（剩余为正且很大）——那不是"贴底"，不能跟随，否则
            // 流式输出期间每个块都会把视图拽到列表最底部。
            (last.offset + last.size) - li.viewportEndOffset <= thresholdPx
        }
    }

    var anchored by rememberSaveable { mutableStateOf(false) }
    val lastLen = messages.lastOrNull()?.let { it.text.length + it.reasoning.length } ?: 0

    LaunchedEffect(messages.size, lastLen) {
        if (messages.isEmpty()) {
            anchored = false
            return@LaunchedEffect
        }
        // 底部钉住：offset=0 会把长消息的顶部对齐进视口（流式输出时表现
        // 为不断被弹回这条回复的开头），大偏移让 LazyList 校正到列表末尾。
        if (!anchored) {
            listState.scrollToItem(messages.lastIndex, PIN_TO_BOTTOM_SCROLL_OFFSET)
            anchored = true
        } else if (nearBottom) {
            listState.scrollToItem(messages.lastIndex, PIN_TO_BOTTOM_SCROLL_OFFSET)
        }
    }

    if (isDraftEmpty) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("说点什么，开始新对话", fontSize = 15.sp, color = Flat.Muted)
                Spacer(Modifier.height(6.dp))
                Text("发送后对话将自动创建", fontSize = 12.sp, color = Flat.Muted.copy(alpha = 0.7f))
            }
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(messages, key = { it.id }) { m ->
            MessageItem(m)
        }
    }
}

@Composable
private fun MessageItem(m: ChatMessage) {
    when (m.role) {
        Role.USER -> UserBubble(m)
        Role.ASSISTANT -> AssistantBubble(m)
        else -> {}
    }
}

@Composable
private fun UserBubble(m: ChatMessage) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Column(
            Modifier
                .widthIn(max = 300.dp)
                .shadow(9.dp, RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp), clip = false, ambientColor = Color(0x1A000000), spotColor = Color(0x40000000))
                .clip(RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp))
                .background(Flat.Fill)
                .padding(12.dp),
        ) {
            if (m.images.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    m.images.forEach { ref -> SentImage(ref) }
                }
                Spacer(Modifier.height(2.dp))
            } else if (m.imageCount > 0) {
                Text("[图片 ×${m.imageCount}]", fontSize = 12.sp, color = Flat.Label)
            }
            if (m.text.isNotBlank()) {
                Text(m.text, fontSize = 15.sp, lineHeight = 22.sp, color = Flat.Ink)
            }
        }
    }
}

@Composable
private fun AssistantBubble(m: ChatMessage) {
    // 工具调用不展示；纯工具步骤（无文本无思考）整条跳过。
    if (m.text.isBlank() && m.reasoning.isBlank()) return
    var expanded by remember(m.id) { mutableStateOf(false) }
    // Kimi 式：助手内容不带气泡，直接铺在页面背景上。
    Column(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
        ) {
            if (m.reasoning.isNotBlank()) ReasoningSection(m)
            val body = if (!expanded && m.text.length > LONG_MESSAGE_THRESHOLD) {
                m.text.take(LONG_MESSAGE_THRESHOLD)
            } else {
                m.text
            }
            if (body.isNotBlank()) {
                if (m.pending && !body.contains('$')) {
                    // 含 LaTeX 的流式消息跳过打字机：45ms 的部分文本重解析会让每条
                    // 公式反复"归零→渲染→撑开"（疯狂抽搐），且可能截断半截公式。
                    // 宿主本身是增量流，直接渲染全文即可；纯文本仍保留打字机。
                    TypewriterMarkdown(body, pending = true, color = Flat.Ink)
                } else {
                    MarkdownText(body, color = Flat.Ink)
                }
            }
            if (m.text.length > LONG_MESSAGE_THRESHOLD) {
                Text(
                    if (expanded) "收起" else "展开全文",
                    fontSize = 13.sp,
                    color = Flat.Accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { expanded = !expanded }
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                )
            }
        }
    }
}


@Composable
private fun ReasoningSection(m: ChatMessage) {
    var show by remember(m.id) { mutableStateOf(false) }
    Column {
        Row(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { show = !show }
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                (if (show) "▾ " else "▸ ") + "思考过程",
                fontSize = 12.sp,
                color = Flat.Label,
            )
        }
        if (show) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Flat.Fill.copy(alpha = 0.6f))
                    .padding(10.dp),
            ) {
                MarkdownText(m.reasoning, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun ToolRow(t: ToolCallInfo) {
    var show by remember(t.callId) { mutableStateOf(false) }
    Column {
        Row(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { show = !show }
                .padding(vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "🔧 ${t.name.ifBlank { "tool" }}",
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = Flat.Label,
            )
            if (t.isError) {
                Spacer(Modifier.width(6.dp))
                Text("（出错）", fontSize = 12.sp, color = Flat.Danger)
            }
        }
        if (show) {
            if (t.arguments.isNotBlank()) {
                Text(
                    t.arguments,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Flat.Muted,
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            t.result?.let {
                Text(
                    it,
                    fontSize = 12.sp,
                    color = Flat.Muted,
                    maxLines = 12,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 自己发送的图片：本地留存的 base64 解码展示（图片较大时降采样）。 */
@Composable
private fun SentImage(ref: ImageRef) {
    val bitmap by produceState<android.graphics.Bitmap?>(null, ref.dataBase64) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = Base64.decode(ref.dataBase64, Base64.NO_WRAP)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth <= 0) return@runCatching null
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= 1024 && bounds.outHeight / (sample * 2) >= 1024) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            }.getOrNull()
        }
    }
    val image = bitmap?.asImageBitmap() ?: return
    val ratio = if (image.height != 0) image.width.toFloat() / image.height.toFloat() else 1f
    Image(
        bitmap = image,
        contentDescription = "发送的图片",
        contentScale = ContentScale.FillWidth,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            .clip(RoundedCornerShape(10.dp)),
    )
}

/** 归档确认框：圆角矩形卡片 + 阴影。 */
@Composable
private fun ArchiveDialog(target: SessionSummary, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val name = target.title?.takeIf { it.isNotBlank() } ?: if (target.blank) "(新会话)" else "(未命名)"
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Flat.White,
            shadowElevation = 12.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                Text("归档对话", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Flat.Ink)
                Spacer(Modifier.height(8.dp))
                Text(
                    "「$name」将从对话列表归档，桌面端与远端都不再显示，可随时在桌面端恢复。",
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = Flat.Label,
                )
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Flat.Fill)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { onDismiss() }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("取消", fontSize = 14.sp, color = Flat.Label)
                    }
                    FlatButton(
                        onClick = onConfirm,
                        enabled = true,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("归 档", fontSize = 14.sp, color = Flat.White)
                    }
                }
            }
        }
    }
}

/* ==================== 输入栏 ==================== */

@Composable
private fun InputBar(
    input: String,
    onInput: (String) -> Unit,
    images: List<PendingImage>,
    mode: String,
    sending: Boolean,
    pending: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onToggleMode: () -> Unit,
    onRemoveImage: (String) -> Unit,
    onPickImages: () -> Unit,
    presetLabel: String?,
    presets: List<AgentPresetRow>?,
    presetsError: String?,
    currentPresetId: String?,
    onOpenPresets: () -> Unit,
    onPickPreset: (AgentPresetRow) -> Unit,
) {
    Surface(color = Flat.White) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (images.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    images.forEach { p ->
                        Box {
                            if (p.thumb != null) {
                                Image(
                                    bitmap = p.thumb,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(52.dp)
                                        .clip(RoundedCornerShape(10.dp)),
                                )
                            } else {
                                Box(
                                    Modifier
                                        .size(52.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Flat.Fill),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        if (p.tooBig) "过大" else "图片",
                                        fontSize = 11.sp,
                                        color = Flat.Muted,
                                    )
                                }
                            }
                            Box(
                                Modifier
                                    .size(18.dp)
                                    .clip(CircleShape)
                                    .background(Flat.Danger)
                                    .clickable { onRemoveImage(p.uri) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "移除",
                                    tint = Color.White,
                                    modifier = Modifier.size(12.dp),
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            Row(verticalAlignment = Alignment.Bottom) {
                FloatCircle(onClick = onPickImages, elevation = 10.dp) {
                    Icon(Icons.Filled.Add, contentDescription = "添加图片", tint = Flat.Ink, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(8.dp))
                FlatTextField(
                    label = null,
                    value = input,
                    onValueChange = onInput,
                    placeholder = "输入消息…",
                    minLines = 1,
                    corner = 22.dp,
                    compact = true,
                    elevation = 10.dp,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                if (pending) {
                    FloatCircle(onClick = onStop, elevation = 10.dp) {
                        Icon(Icons.Filled.Close, contentDescription = "停止", tint = Flat.Danger, modifier = Modifier.size(20.dp))
                    }
                } else {
                    val enabled = !sending && (input.isNotBlank() || images.isNotEmpty())
                    Box(
                        Modifier
                            .size(44.dp)
                            .shadow(10.dp, CircleShape, clip = false, ambientColor = Color(0x33000000), spotColor = Color(0x6B000000))
                            .clip(CircleShape)
                            .background(if (enabled) Flat.Accent else Flat.Fill)
                            .clickable(
                                enabled = enabled,
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onSend,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (sending) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Flat.Muted)
                        } else {
                            Icon(
                                Icons.Filled.Send,
                                contentDescription = "发送",
                                tint = if (enabled) Color.White else Flat.Muted,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MiniPill(
                    text = if (mode == "steer") "插话模式" else "排队模式",
                    onClick = onToggleMode,
                )
                Box {
                    var presetMenu by remember { mutableStateOf(false) }
                    MiniPill(
                        text = presetLabel ?: "智能体预设",
                        icon = Icons.Filled.Person,
                        onClick = {
                            onOpenPresets()
                            presetMenu = true
                        },
                    )
                    PresetPopup(
                        expanded = presetMenu,
                        presets = presets,
                        error = presetsError,
                        currentId = currentPresetId,
                        onDismiss = { presetMenu = false },
                        onPick = onPickPreset,
                        onRetry = onOpenPresets,
                    )
                }
            }
        }
    }
}

/* ==================== 对话记录抽屉 ==================== */

@Composable
private fun HistoryDrawer(
    state: AppUiState,
    currentId: String?,
    onPick: (SessionSummary) -> Unit,
    onCreate: () -> Unit,
    onClose: () -> Unit,
    onSettings: () -> Unit,
    onArchive: (SessionSummary) -> Unit,
) {
    var pendingArchive by remember { mutableStateOf<SessionSummary?>(null) }
    val groups = state.sessions
        .groupBy { it.cwd?.takeIf { c -> c.isNotBlank() } ?: "未指定工作区" }
        .toList()
        .sortedByDescending { (_, list) -> list.maxOf { it.updatedAt } }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(top = 14.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "对话记录",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = Flat.Ink,
                modifier = Modifier.weight(1f),
            )
            FloatCircle(onClick = onCreate) {
                Icon(Icons.Filled.Add, contentDescription = "新对话", tint = Flat.Ink, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(10.dp))
            FloatCircle(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "收起", tint = Flat.Ink, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.height(8.dp))

        if (groups.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("暂无对话记录", color = Flat.Muted, fontSize = 13.sp)
            }
        } else {
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp),
            ) {
                groups.forEach { (cwd, sessions) ->
                    item(key = "ws-$cwd") { WorkspaceHeader(cwd, sessions.size) }
                    items(sessions, key = { "s-${it.sessionId}" }) { s ->
                        DrawerSessionRow(
                            s,
                            selected = s.sessionId == currentId,
                            onClick = { onPick(s) },
                            onLongPress = { pendingArchive = s },
                        )
                    }
                    item(key = "gap-$cwd") { Spacer(Modifier.height(12.dp)) }
                }
            }
        }

        // 底部：连接设置入口
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onSettings,
                )
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Settings, contentDescription = null, tint = Flat.Muted, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("连接设置", fontSize = 13.sp, color = Flat.Label)
        }

        pendingArchive?.let { target ->
            ArchiveDialog(
                target = target,
                onDismiss = { pendingArchive = null },
                onConfirm = {
                    onArchive(target)
                    pendingArchive = null
                },
            )
        }
    }
}

@Composable
private fun WorkspaceHeader(cwd: String, count: Int) {
    val name = cwd.trimEnd('\\', '/')
        .split('\\', '/')
        .lastOrNull { it.isNotBlank() }
        ?: cwd
    Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                FolderIcon,
                contentDescription = null,
                tint = Flat.Muted,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.size(8.dp))
            Text(
                name,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = Flat.Label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text("$count", fontSize = 11.sp, color = Flat.Muted)
        }
        if (name != cwd) {
            Text(
                cwd,
                fontSize = 10.sp,
                color = Flat.Muted.copy(alpha = 0.75f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 23.dp, top = 2.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DrawerSessionRow(
    s: SessionSummary,
    selected: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Flat.Fill else Color.Transparent)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onLongPress,
            )
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (s.running) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(Flat.Accent),
            )
            Spacer(Modifier.size(7.dp))
        }
        Text(
            s.title?.takeIf { it.isNotBlank() }
                ?: if (s.blank) "(新会话)" else "(未命名)",
            fontSize = 14.sp,
            color = if (selected) Flat.Accent else Flat.Ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.size(8.dp))
        Text(
            DateUtils.getRelativeTimeSpanString(s.updatedAt).toString(),
            fontSize = 11.sp,
            color = Flat.Muted,
        )
    }
}

/* ==================== 智能体预设弹层 ==================== */

private const val PRESET_GRID_COLUMNS = 3

/** 紧贴锚点上方 8dp 打开；M3 DropdownMenu 底部锚点默认间距过大，故自绘定位。 */
private class AboveAnchorPopup(private val gapPx: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val x = anchorBounds.left.coerceIn(0, maxX)
        val y = (anchorBounds.top - popupContentSize.height - gapPx).coerceAtLeast(0)
        return IntOffset(x, y)
    }
}

/** 预设弹层：圆角矩形卡片 + 圆形头像矩阵。 */
@Composable
private fun PresetPopup(
    expanded: Boolean,
    presets: List<AgentPresetRow>?,
    error: String?,
    currentId: String?,
    onDismiss: () -> Unit,
    onPick: (AgentPresetRow) -> Unit,
    onRetry: () -> Unit,
) {
    if (!expanded) return
    val gapPx = with(LocalDensity.current) { 8.dp.roundToPx() }
    Popup(
        popupPositionProvider = AboveAnchorPopup(gapPx),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Flat.White,
            shadowElevation = 10.dp,
        ) {
            Column(
                Modifier
                    .widthIn(max = 320.dp)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                when {
                    error != null -> Column {
                        Text("预设名单加载失败：$error", fontSize = 12.sp, color = Flat.Danger)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "点按重试",
                            fontSize = 12.sp,
                            color = Flat.Accent,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) { onRetry() }
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                    }
                    presets == null -> PopupHint("加载中…")
                    presets.isEmpty() -> PopupHint("暂无智能体预设")
                    else -> Column(
                        Modifier
                            .heightIn(max = 240.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        presets.chunked(PRESET_GRID_COLUMNS).forEachIndexed { index, rowItems ->
                            if (index > 0) Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                rowItems.forEach { p ->
                                    PresetCircle(
                                        row = p,
                                        selected = p.id == currentId,
                                        onClick = {
                                            onDismiss()
                                            onPick(p)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PopupHint(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        color = Flat.Muted,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

/** 圆形预设项：选中变实心勾选，无法加载的预设置灰不可点。 */
@Composable
private fun PresetCircle(row: AgentPresetRow, selected: Boolean, onClick: () -> Unit) {
    val broken = row.broken != null
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(66.dp),
    ) {
        Box(
            Modifier
                .size(48.dp)
                .shadow(8.dp, CircleShape, clip = false, ambientColor = Color(0x33000000), spotColor = Color(0x66000000))
                .clip(CircleShape)
                .background(if (selected) Flat.Accent else Flat.Fill)
                .clickable(
                    enabled = !broken,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            } else {
                Text(
                    row.label.trim().take(1).uppercase().ifBlank { "?" },
                    fontSize = 18.sp,
                    color = if (broken) Flat.Muted else Flat.Ink,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            row.label,
            fontSize = 10.sp,
            color = when {
                selected -> Flat.Accent
                broken -> Flat.Muted
                else -> Flat.Label
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

