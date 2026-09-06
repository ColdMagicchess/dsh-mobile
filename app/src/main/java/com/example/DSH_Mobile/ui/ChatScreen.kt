package com.example.DSH_Mobile.ui

import android.graphics.Bitmap
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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
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
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.asAndroidBitmap
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
    // 抽屉粒子转场状态机：Closed →(录制采样)→ Converging → Open →(录制采样)→ Dispersing → Closed
    var dPhase by remember { mutableStateOf(DrawerPhase.Closed) }
    var animT by remember { mutableFloatStateOf(0f) }
    var animEpoch by remember { mutableIntStateOf(0) }
    var captureSeq by remember { mutableIntStateOf(0) }      // 录制触发计数（effect key，只增不减）
    var settleActive by remember { mutableStateOf(false) }   // 收束后的落位段（阴影渐显+位图交棒）
    var settleEpoch by remember { mutableIntStateOf(0) }
    var captureActive by remember { mutableStateOf(false) }  // 录制开关（非 key，effect 内可安全关闭）
    var captureWantsOpen by remember { mutableStateOf(true) }
    val drawerLayer = rememberGraphicsLayer()
    val fx = remember { DrawerFx() }

    fun reqOpen() {
        when (dPhase) {
            DrawerPhase.Closed -> {
                appVm.refreshSessions()   // 先刷数据再录制，避免位图与收束完成的实时列表不一致
                captureWantsOpen = true; captureActive = true; captureSeq++
            }
            // 中途反向：粒子当前位置快照为汇聚起点，无需重新录制
            DrawerPhase.Dispersing -> {
                fx.setupConverge(fromCurrent = true, curT = animT)
                animT = 0f   // 清零：否则首帧用上一轮的结束值画出"完成态"闪一下
                dPhase = DrawerPhase.Converging; animEpoch++
            }
            else -> {}
        }
    }
    fun reqClose() {
        when (dPhase) {
            DrawerPhase.Open -> {
                settleActive = false; fx.settleP = 0f
                captureWantsOpen = false; captureActive = true; captureSeq++
            }
            DrawerPhase.Converging -> {
                fx.setupDisperse(fromCurrent = true, curT = animT)
                animT = 0f
                dPhase = DrawerPhase.Dispersing; animEpoch++
            }
            else -> {}
        }
    }

    val haze = rememberHazeState()   // 聊天内容作为毛玻璃的背景源

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(error) {
        error?.let {
            snackbar.showSnackbar(it, duration = SnackbarDuration.Short)
            vm.dismissError()
        }
    }


    // 等一帧让抽屉 drawWithContent 完成录制 → 位图采样粒子 → 启动动画。
    // 注意：开关状态不能做本 effect 的 key（体内清开关会改 key 自杀），
    // 用只增计数 captureSeq 触发，captureActive 仅作绘制层读取的开关。
    LaunchedEffect(captureSeq) {
        if (captureSeq == 0) return@LaunchedEffect
        withFrameNanos { }
        withFrameNanos { }
        val wants = captureWantsOpen
        captureActive = false
        // GPU 回读 + 百万像素拷贝/采样挪到 Default 线程，消除点击后的主线程卡顿
        val ok = runCatching {
            withTimeout(1500) {
                val hard = drawerLayer.toImageBitmap().asAndroidBitmap()
                withContext(Dispatchers.Default) {
                    // toImageBitmap 返回 HARDWARE 位图，getPixels 不可用：拷成软件 ARGB_8888 再采样
                    val bmp = if (hard.config == Bitmap.Config.HARDWARE)
                        hard.copy(Bitmap.Config.ARGB_8888, false) ?: hard
                    else hard
                    fx.build(bmp)
                }
            }
        }.getOrDefault(false)
        if (!ok) {
            // 兜底：录制失败/超时直接切换目标态（无粒子效果，功能不受影响）
            dPhase = if (wants) DrawerPhase.Open else DrawerPhase.Closed
            return@LaunchedEffect
        }
        if (wants) fx.setupConverge(fromCurrent = false, curT = 0f)
        else fx.setupDisperse(fromCurrent = false, curT = 0f)
        animT = 0f
        dPhase = if (wants) DrawerPhase.Converging else DrawerPhase.Dispersing
        animEpoch++
    }

    // 落位段：300ms 内 settleP 0→1（fx 绘制层读取），锚点=抽屉头部两个圆钮
    val settleDens = LocalDensity.current
    val settleInsets = WindowInsets.statusBars   // 扩展属性需组合上下文，提前取
    LaunchedEffect(settleEpoch) {
        if (settleEpoch == 0 || !settleActive) return@LaunchedEffect
        val sbPx = settleInsets.getBottom(settleDens).toFloat()
        fx.anchors = with(settleDens) {
            floatArrayOf(
                fx.wPx - 40.dp.toPx(), sbPx + 36.dp.toPx(),   // 收起 ×
                fx.wPx - 94.dp.toPx(), sbPx + 36.dp.toPx(),   // 新建 +
            )
        }
        val start = withFrameMillis { it }
        var u = 0f
        while (u < 1f && settleActive) {
            u = (withFrameMillis { it } - start) / 300f
            fx.settleP = u.coerceIn(0f, 1f)
            // 必须携带变化写 animT：写同值不触发 Canvas 重绘，
            // 落位淡出就整段没播（位图原地停留后瞬间消失=卡顿真因）
            animT = fx.tConverge + u
        }
        settleActive = false
        fx.settleP = 0f
        fx.release()
    }

    // 逐帧驱动：animT 仅被绘制层读取（不触发重组），播完切换终态
    LaunchedEffect(animEpoch) {
        if (animEpoch == 0 || fx.n == 0) return@LaunchedEffect
        val converging = dPhase == DrawerPhase.Converging
        val dur = if (converging) fx.tConverge else fx.tDisperse
        val start = withFrameMillis { it }
        var t = 0f
        while (t < dur) {
            t = (withFrameMillis { it } - start) / 1000f
            animT = t
        }
        animT = dur
        if (converging) {
            // 位图原样保留，进入落位段：按钮阴影渐显 + 位图淡出交棒，
            // 真实抽屉在位图底下完成首绘，切换开销全部被动画吸收
            dPhase = DrawerPhase.Open
            settleActive = true
            settleEpoch++
        } else {
            fx.release()
            dPhase = DrawerPhase.Closed
        }
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

            // ---------- 聊天主体 ----------
            Surface(
                Modifier.fillMaxSize().hazeSource(haze),
                color = Flat.White,
                contentColor = Flat.Ink,
            ) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                // 顶部只预留悬浮按钮行（紧贴刘海下沿），对话名不在聊天内显示
                Spacer(Modifier.height(56.dp))
                MessageList(
                    messages = messages,
                    isDraftEmpty = session == null && messages.isEmpty(),
                    modifier = Modifier.weight(1f),
                    onSwipeToOpen = { reqOpen() },
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

            // ---------- 左上悬浮：菜单圆钮 + 模型胶囊（上提贴住刘海下沿） ----------
            Row(
                Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(start = 12.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FloatCircle(onClick = { reqOpen() }, haze = haze) {
                    Icon(MenuLines, contentDescription = "更多", tint = Flat.Ink, modifier = Modifier.size(20.dp))
                }
                Box {
                    var modelMenu by remember { mutableStateOf(false) }
                    FloatPill(onClick = { vm.loadCatalog(); modelMenu = true }, haze = haze) {
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
                    FloatPill(onClick = { wsMenu = true }, haze = haze) {
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
                                    // 先同步 AppViewModel 的当前会话态（否则 appState.current 仍指向
                                    // 旧会话，而 ChatViewModel 已重置为草稿 → 页面空白且两态分叉）
                                    appVm.openDraft()
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

            // ---------- 遮罩（透明度跟随粒子进度，绘制阶段读取、不触发重组）。
            // 仅非 Closed 时组合：常驻的全屏 disabled clickable 会吞点击 ----------
            if (dPhase != DrawerPhase.Closed) {
            Box(
                Modifier
                    .fillMaxSize()
                    .drawBehind {
                        val pr = when (dPhase) {
                            DrawerPhase.Open -> 1f
                            DrawerPhase.Converging -> (animT / fx.tConverge).coerceIn(0f, 1f)
                            DrawerPhase.Dispersing -> 1f - (animT / fx.tDisperse).coerceIn(0f, 1f)
                            DrawerPhase.Closed -> 0f
                        }
                        if (pr > 0.002f) drawRect(Color.Black.copy(alpha = 0.13f * pr))
                    }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = dPhase == DrawerPhase.Open,
                    ) { reqClose() },
            )
            }

            // ---------- 对话记录抽屉（粒子相位隐藏真实面板，由粒子层接管画面） ----------
            val drawerShape = RoundedCornerShape(topEnd = 18.dp, bottomEnd = 18.dp)
            Box(
                Modifier
                    .fillMaxHeight()
                    .width(drawerW)
                    // 组合≠可点：仅在"内容已组合但面板不可见"的预热/录制相位
                    // 挂 disabled clickable 吞触摸（防隐形列表项截胡）。
                    // 注意不能反着挂到 Closed——Box 常驻会吃掉菜单按钮的点击！
                    .then(if (dPhase == DrawerPhase.Converging ||
                              (captureActive && dPhase != DrawerPhase.Open))
                          Modifier.clickable(enabled = false) {}
                          else Modifier)
                    .drawWithContent {
                        val scope = this
                        if (captureActive) {
                            // 把整块面板（含毛玻璃背景）录制进图形层，供粒子位图采样。
                            // 显式 scope. 前缀：record 的参数解析会把裸 density 误配到
                            // GraphicsLayer.density(Float) 上（Kotlin 接收者作用域陷阱），
                            // 且 1.9 的 record 形参序是 (density, layoutDirection, size)。
                            // ContentDrawScope.record 扩展：录制期间把本作用域画布
                            // 重定向进图形层，drawContent() 才会真正落进 layer
                            // （直接调 GraphicsLayer.record 会把内容画到屏幕画布，layer 为空）
                            drawerLayer.record(
                                IntSize(scope.size.width.toInt(), scope.size.height.toInt()),
                            ) { scope.drawContent() }
                        }
                        if (dPhase == DrawerPhase.Open) scope.drawContent()
                    }
                    .shadow(16.dp, drawerShape, clip = false)
                    .clip(drawerShape)
                    .glass(drawerShape, haze),
            ) {
                // Open/Converging/录制帧组合内容：Converging 期间预热布局，
                // 收束完成切 Open 不再出现整树重组的顿帧
                if (dPhase == DrawerPhase.Open || dPhase == DrawerPhase.Converging || captureActive) {
                HistoryDrawer(
                    state = appState,
                    currentId = session?.sessionId,
                    onPick = {
                        appVm.openSession(it)
                        reqClose()
                    },
                    onCreate = {
                        appVm.openDraft()
                        reqClose()
                    },
                    onClose = { reqClose() },
                    onSettings = {
                        // 跳回连接页即刻生效，无需播放消散动画
                        appVm.backToConnect()
                        dPhase = DrawerPhase.Closed
                    },
                    onArchive = { appVm.archiveSession(it.sessionId) },
                )
                }
            }

            // ---------- 粒子层：弹出=从左向右汇聚，收起=从右向左消散 ----------
            if (dPhase == DrawerPhase.Converging || dPhase == DrawerPhase.Dispersing || settleActive) {
                Canvas(Modifier.fillMaxSize()) {
                    val converging = dPhase == DrawerPhase.Converging
                    fx.drawPanel(this, converging, animT)   // 清晰面板（渐显/侵蚀）垫底
                    fx.draw(this, converging, animT)       // 粒子流覆盖其上
                }
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
    haze: HazeState? = null,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier
            .size(44.dp)
            .shadow(elevation, CircleShape, clip = false, ambientColor = Color(0x33000000), spotColor = Color(0x6B000000))
            .clip(CircleShape)
            .glass(CircleShape, haze)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
        content = { content() },
    )
}

@Composable
private fun FloatPill(onClick: () -> Unit, haze: HazeState? = null, content: @Composable () -> Unit) {
    Row(
        Modifier
            .height(44.dp)
            .shadow(10.dp, RoundedCornerShape(22.dp), clip = false, ambientColor = Color(0x33000000), spotColor = Color(0x6B000000))
            .clip(RoundedCornerShape(22.dp))
            .glass(RoundedCornerShape(22.dp), haze)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = { content() },
    )
}

@Composable
private fun MiniPill(text: String, onClick: () -> Unit, icon: ImageVector? = null, haze: HazeState? = null) {
    Row(
        Modifier
            .shadow(8.dp, RoundedCornerShape(16.dp), clip = false, ambientColor = Color(0x33000000), spotColor = Color(0x66000000))
            .clip(RoundedCornerShape(16.dp))
            .glass(RoundedCornerShape(16.dp), haze)
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
    onSwipeToOpen: () -> Unit = {},
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

    // 右滑打开对话记录抽屉：空草稿占位与消息列表共用同一手势（只累计向右
    // 拖动，不影响纵向滚动）；阈值 120dp → 80dp，更短距离即可触发。
    val swipeModifier = modifier.pointerInput(Unit) {
        var totalDrag = 0f
        detectHorizontalDragGestures(
            onDragStart = { totalDrag = 0f },
            onHorizontalDrag = { _, amount -> totalDrag += amount },
            onDragEnd = {
                if (totalDrag > thresholdPx) onSwipeToOpen()
                totalDrag = 0f
            },
        )
    }

    if (isDraftEmpty) {
        Box(swipeModifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
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
        modifier = swipeModifier,
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
                Text(m.text, fontSize = 15.sp, lineHeight = 24.sp, color = Flat.Ink)
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
                if (m.pending) {
                    // 公式抽搐已由 MarkdownText 的 drawable 缓存根治，打字机全程保留。
                    // 表格（TableRowSpan 每次重建都两遍布局，绝不能进 45ms 重解析路径）
                    // 采用渐进分段：已完成行进稳定前缀按真表格渲染——前缀字符串在
                    // 下一行完成前不变，Compose 跳过重组 → 表格零闪烁逐行生长；
                    // 正在输入的尾行走打字机 + 分隔行中和，行完成即并入前缀。
                    val split = splitAtLastTableRow(body)
                    if (split == null) {
                        TypewriterMarkdown(neutralizeTablesForStreaming(body), pending = true, color = Flat.Ink)
                    } else {
                        Column {
                            MarkdownText(split.stablePrefix, color = Flat.Ink)
                            if (split.liveTail.isNotBlank()) {
                                TypewriterMarkdown(
                                    neutralizeTablesForStreaming(split.liveTail),
                                    pending = true,
                                    resetKey = split.stablePrefix,
                                    color = Flat.Ink,
                                )
                            }
                        }
                    }
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
                MarkdownText(
                    if (m.pending) neutralizeTablesForStreaming(m.reasoning) else m.reasoning,
                    Modifier.fillMaxWidth(),
                )
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
                            .glass(RoundedCornerShape(10.dp))
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
                        Text("归 档", fontSize = 14.sp, color = Flat.Ink)
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
                            .then(if (enabled) Modifier.glass(CircleShape) else Modifier.background(Flat.Fill, CircleShape))
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
                                tint = if (enabled) Flat.Ink else Flat.Muted,
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
            .then(if (selected) Modifier.glass(RoundedCornerShape(8.dp)) else Modifier)
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
            // 玻璃选中态上，天蓝细字在模糊背景里可读性差：改墨色加粗表达选中
            color = Flat.Ink,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
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

/** 抽屉粒子转场状态机。 */
private enum class DrawerPhase { Closed, Converging, Open, Dispersing }

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
                .glass(CircleShape)
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
                    tint = Flat.Accent,
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

