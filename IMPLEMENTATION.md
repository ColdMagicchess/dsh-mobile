# Deepseek Harness Mobile — 实现说明

> 本文档记录 App 的实际实现与**经 DSH 源码核实的真实 API 契约**（与 README 方案的假设有多处出入，以本文为准）。
> 代码结构、构建方式、以及后续迭代建议。

## 1. 技术栈（已落地）

| 项 | 实现 |
| --- | --- |
| 语言 / UI | Kotlin + Jetpack Compose（Material 3, dynamic color） |
| 网络 | OkHttp（HTTP RPC + WebSocket mux）；kotlinx.serialization JSON（JsonObject 防御式解析） |
| 实时 | WebSocket `/api/remote.mux` 逻辑流 `session/follow`；失败自动重连，2 次后退化为 `session/page` 拉日志尾部（0.3.12 起 `session/inspect` 已被宿主移除；seq 水位去重） |
| Markdown | Markwon core + ext-latex（JLaTeXMath Android 版） |
| LaTeX | `$...$` / `$$...$$` 由 Markwon LatexPlugin 在 TextView 内渲染（F7） |
| 存储 | DataStore + AndroidKeyStore AES-256/GCM 加密配对 cookie |
| 图片 | Photo Picker（PickMultipleVisualMedia）→ 校验 mediaType/大小 → base64（无前缀）→ session/prompt content |
| 架构 | ViewModel + StateFlow + Coroutines；MessageStore 折叠事件流 |

## 2. 真实 API 契约（核实自 DSH 源码，含 file:line 的完整报告见会话记录）

### 2.1 与 README 方案假设的差异（重要）

| README 假设 | 实际情况 |
| --- | --- |
| SSE `events.mux` | **不存在**。实时通道是 WebSocket `/api/remote.mux`（Gateway 拥有），逻辑流帧 `{type:"open"/"item"/"end"/"error",streamId,...}` |
| `POST /api/<method>`，payload 平铺 | endpoint 为 `<namespace>/<method>`（如 `session/list`），且 **payload 固定包一层 `{args:{...}}`**（wire 名：list 用 `_request`，其余多为 `request`） |
| `session.history` | 不存在 → `session/page`（`{address, throughSeq, beforeSeq?, maxMessages?}`）与 `session/inspect`（全量事件，轮询兜底用） |
| `session.models` | 实为 `session/modelCatalog`（无参数）→ `{default, routableProviders, groups:[{id,name,models:[{id,name,reasoning?:{efforts,defaultEffort?}}]}], failures}` |
| `workspace.list` | 不存在 → `workspace/follow` 流（baseline/upsert/remove 帧）。v1 未做工作区分组，会话列表平铺（含 cwd 标签） |
| 配对 = QR/设备码 | 插件通道用桌面端 remote-web-ui 面板的 `?pair=` 链接（`POST /api/pair/accept` 换 `dsh_pair` cookie）；也支持手动粘贴 cookie。注：`?token=` 核心配对流程存在于 dsh-client-connection 源码，但桌面版 2.0.4 未暴露生成入口（`?token=` 全包 0 命中），本部署实际只用插件配对 |

### 2.2 信封与鉴权

- 请求：`POST {host}/api/<ns>/<method>`，`content-type: application/json`（否则 415），body：
  ```json
  {"type":"client-request","rpcId":"<uuid>","method":"<ns>/<method>","payload":{"args":{...}}}
  ```
- 响应：HTTP 200（业务错误也是 200）`{"type":"server-response","rpcId","result":{ok:true,value}|{ok:false,error:{code,message,details}}}`
- 鉴权：cookie 名 `dsh-auth-` + base64url(sha256(Host authority))（authority = Host 头规范形，仅去默认 80 端口、小写）；值为 `v1.<b64url payload>.<b64url hmac>`。**App 只需原样回放 cookie**（连 WS 握手也带）；非浏览器客户端被明确支持（Host 门禁 + cookie，无 CSRF）。
- 配对：`GET /?token=<launchToken>` → 303 + Set-Cookie（`dsh-auth-…=v1.…`），App 捕获并持久化。

### 2.3 关键 RPC（App 已接）

| endpoint | args（在 {args:{…}} 内） | value |
| --- | --- | --- |
| `session/list` | `{"_request":{}}` | `{items:[{sessionId,updatedAt,running,blank,cwd?,projections.values.{title,modelSelection.lastUsed,…}}]}` |
| `session/create` | `{"request":{cwd?/workspaceId?/agentPreset?}}` | `{sessionId}` |
| `session/prompt` | `{"request":{requestId,sessionId,mode:"queue"\|"steer",content:[{type:"text",text}\|{type:"image",mediaType,data:base64,name?}],clientTimeZone?}}` | `{accepted:true}` |
| `session/cancel` | `{"request":{sessionId}}` | `{accepted:true}` |
| `session/selectModel` | `{"request":{sessionId,provider,model,reasoningEffort?}}` | `{selected}` |
| `session/modelCatalog` | `{}` | 模型分组目录 |
| `session/search` | `{"request":{query}}` | `{items:[{sessionId,snippet}],hasMore}` |
| ~~`session/inspect`~~ | **0.3.12 已移除** → 改用 `session/page`（`{request:{address:{kind,sessionId},throughSeq,maxMessages?}}`；throughSeq 不得超出日志 cursor——宿主对超界报 "past cursor"——故先经 `session/list` 取该会话 `projections.asOfSeq` 作 throughSeq） | `{records:[{type:"event",event}|{type:"chunks",event}],hasMore}` |
| `agentPresets/list` | `{}`（无参数） | `{presets:[{id,trust,isDefault,name?,description?,broken?}],authorable}` |
| `agentPreset.list`（插件 BFF） | `{}` | 同上（插件代理核心网关）；BFF 白名单只有 list，没有 select |
| `workspace/archiveSession` | `{"request":{"sessionId":…}}` | `{archivedSessionIds:[…]}`；归档后宿主在 workspace 状态中记录，`session/list` 仍会返回该会话 |
| `session.archive` / `session.archived`（插件 BFF，本机补丁） | `{sessionId}` / `{}` | 归档透传 / 返回注册表 `archivedSessionIds`；补丁位于已安装插件 lib/index.js，改动需重启 DSH 桌面端生效。补丁详情、插件更新后重打步骤见 **PLUGIN_PATCH.md** |
| `agentPresets/select` | `{agentId:<sessionId>,agentPreset:<id>}`（args 平铺；agentId 由网关 lookup 解析为 live agent） | 预设 id；会话已开始报错码 `agent-preset-locked` |

> **0.3.12 通道更新（2026-09）**：dsh-web-all 0.3.12 起 `/m/api` 手机 BFF 彻底移除，手机通道改为 remote-web-ui 的 `/remote` 门控镜像——完整契约见 **2.3.1**；下表中标注「插件 BFF」的行为仅适用于 0.3.6。

### 2.3.1 dsh-web-all 0.3.12 手机通道契约（/remote 镜像）

- **配对**：`POST /api/pair/accept` body `{token}`（`?pair=` 链接里的参数）→ 200 `{ok:true, deviceId}` + `Set-Cookie: dsh_pair=<deviceId>`（HttpOnly；cookie 名可配置，以 Set-Cookie 实际名字为准）。404=token 无效、409=已用、429=限流（每 IP 10 次/30s）。App 现同时持久化 `deviceId`。
- **数据通道**：手机端所有流量走 `/remote` 前缀（插件在 webServer 上注册的门控镜像，配对 cookie / `x-dsh-remote-device` 头（HTTP）/ `?device=`（WS）三种凭据等价）：
  - HTTP RPC：`POST /remote/api/<ns>/<method>`，信封与核心通道完全一致（`{type:"client-request",rpcId,method:"<ns>/<method>",payload:{args:{…}}}`）——插件把请求以 loopback 形态（Host 改写 127.0.0.1、附加内部 browser-auth cookie、`sec-fetch-site: same-origin`）转发给宿主，因此**两个通道共享同一 RPC 面**，0.3.6 的 dot-method/平铺 payload 差异不复存在。
  - WebSocket：`/remote/api/remote.mux`（帧格式与核心通道相同）。
  - 物理本地、配对设备不可达的前缀：`/api/pair`、`/api/update`、`/api/plugin-manager`、`/api/dsh-desktop-launcher`。
  - 未配对/被撤销：HTTP 403 + `{result:{ok:false,error:{code:'unpaired'}}}`。
- **归档与同步（不再需要任何插件补丁）**：
  - 归档：`workspace/archiveSession` RPC（`{"request":{"sessionId"}}`）；宿主拒绝时回退到 dsh-web-all 自带的 dsh-session-archive 插件 `POST /remote/api/dsh-session-archive/archive`（body `{ids:[…]}`，`results[].ok/reason`）。
  - 归档名单：`GET /remote/api/dsh-session-archive/inventory` → `{archivedSessionIds, workspaces:[{id,title,path,sessionIds}], rows,…}`（核心通道直接 `/api/dsh-session-archive/inventory`）。`workspaces` 同时作为工作区选择器数据源（取代旧 BFF `workspace.list`）。
- **App 侧代码落点**：`DshClient.apiPrefix()`（`/remote/api` vs `/api`）、`channelAuth()`（cookie + device 头）、`getJson/postJson`（插件原生 HTTP 路由）、`DshRepository`（两通道统一走核心 RPC）。

### 2.4 实时流（session/follow over /api/remote.mux）

- open：`{"type":"open","streamId":"<uuid>","endpoint":"session/follow","payload":{"args":{"request":{"address":{"kind":"session","sessionId":…},"maxMessages":400}}}}`
- 帧服务端→客户端：`{type:"item",streamId,value}`（value = `{type:"snapshot",header,cursor,records,hasMore,projections}` 或 `{type:"event",event}`）、`{type:"end"}`、`{type:"error",error}`
- 取消：`{"type":"cancel","streamId"}`；心跳：客户端 ping 30s（OkHttp pingInterval）
- 事件词汇：`user/message`（data=消息本体，content 块）、`assistant/message`（`{turn,step,message,usage?}`）、`assistant/chunk`（chunk.type = `text-delta`/`reasoning-delta`/`tool-call-delta`/…）、`tool/call`（`{turn,step,callId,name,arguments:JSON字符串}`）、`tool/result`、`turn/start`/`turn/end`（`{turn,reason}`）、`request/context`、`model/selection`、`session/title`、`agent-preset/selected`（`{agentPreset}`，替换提交后追加）等
- 历史页记录：`{type:"event",event}` 或 `{type:"chunks",event:{type:"chunkrow/text-chunks"|…,data:{texts?:[],turn,step,…}}}`

## 3. 代码结构

```
app/src/main/java/com/example/DSH_Mobile/
├── MainActivity.kt            # Compose 入口，Graph.init
├── dsh/
│   ├── DshJson.kt             # Json 配置 + JsonObject 路径取值助手
│   ├── DshModels.kt           # 领域模型（SessionSummary/ChatMessage/ModelGroup/…）
│   ├── DshClient.kt           # RPC 信封 + 配对 + WS mux（endpoint/method 不匹配自动重试）
│   ├── DshRepository.kt       # 各 RPC 的语义封装与防御式解析
│   └── MessageStore.kt        # fold：seq 水位、按 id upsert、delta 累积、turn/end 收口
├── store/SettingsStore.kt     # DataStore + Keystore 加密
├── vm/
│   ├── Graph.kt               # 进程级单例
│   ├── AppViewModel.kt        # 连接/会话列表/路由
│   └── ChatViewModel.kt       # 流管理、发送/停止、模型、图片
└── ui/
    ├── Theme.kt / DshApp.kt / ConnectScreen.kt / SessionListScreen.kt
    ├── ChatScreen.kt          # 消息列表 + 滚动策略(F13) + 长消息折叠(F14) + 输入栏 + 模型弹窗
    └── MarkdownText.kt        # Markwon+LaTeX(F7) + 打字机(F5: 45ms, step=max(1,min(9,ceil(remain/12))))
app/src/test/java/com/example/DSH_Mobile/MessageStoreTest.kt  # fold 逻辑单元测试
```

## 4. 构建

```bash
./gradlew assembleDebug          # Windows: gradlew.bat assembleDebug
./gradlew :app:testDebugUnitTest # fold 单元测试
```

JDK 17 + Android SDK（local.properties 指向）。输出：`app/build/outputs/apk/debug/DSH-Mobile.apk`（applicationVariants 已固定改名，不再产出 app-debug.apk）。

**构建成功后自动覆盖桌面安装包**：`assembleDebug` / `assembleRelease` 成功收尾时会把 APK 覆盖复制到
`E:\Desktop\DSH-Mobile.apk`（桌面真实位置，构建脚本经注册表 `User Shell Folders\Desktop` 自动识别，
支持桌面重定向）。`doLast` 仅在 assemble 成功时执行，构建失败不会用坏产物覆盖桌面的旧包；
日志行 `DSH-Mobile.apk 已覆盖到桌面：…` 即为成功标志。如需改目标目录：gradle 属性 `-PapkDropDir=…`
或环境变量 `DSH_APK_DROP_DIR`。见 app/build.gradle.kts 末尾。

## 5. 已实现 / 待办对照（README F1–F14）

- ✅ F1 配对/连接（token 链接自动配对 + 手动 cookie；持久化加密）
- ✅ F2 会话列表（平铺 + cwd 标签；workspace/follow 分组未做。抽屉长按对话可归档——圆角阴影确认框；列表过滤 `archivedSessionIds`，桌面端归档/删除后打开抽屉即同步隐藏）
- ✅ F3 新建会话（FAB；优先带 `workspaceId` 挂载到所选工作区——桌面侧边栏按挂载分组，仅传 cwd 会落到"未分组"；无工作区 id 时回退 cwd）
- ✅ F4 聊天消息列表（Markdown，user/assistant 分列）
- ✅ F5 逐字渲染（45ms 步进 displayLen）
- ✅ F6 实时流（WS mux + 重连 + session/page 尾部兜底 + seq 水位）
- ✅ F7 公式渲染（Markwon + JLaTeXMath）
- ✅ F8 思考折叠（reasoning 块 / reasoning-delta 累积）
- ✅ F9 工具调用折叠（tool/call + tool/result + tool-call-delta）
- ✅ F10 图片上传（Photo Picker → base64 → prompt content）
- ✅ F11 发送/停止（queue/steer 切换、session/cancel）
- ✅ F12 切换模型（modelCatalog + selectModel + reasoningEffort）
- ✅ F13 滚动策略（首进到底；nearBottom < 80px 才跟随）
- ✅ F14 长消息折叠（截断正文，按钮在正文之外）
- ✅ F15 智能体预设切换（插话模式旁新增预设按钮，点击展开圆形矩阵弹层；模型/工作区菜单改圆角矩形。名单走核心 `agentPresets/list`；0.3.12 起两通道等价，已有会话经 `agentPresets/select` 切换（会话开始后宿主拒绝：agent-preset-locked）。草稿态记住选择、首发消息随 `session/create` 的 `agentPreset` 下发）
- ⏳ 待办：历史分页加载更早消息（session/page）、workspace/follow 工作区分组、附件取回（session/attachment 渲染历史图片）、$events 流驱动会话列表实时刷新、消息重发/编辑队列（updateQueue）、深链/快捷入口。

## 6. 已知风险

- `LatexPlugin` 极宽公式在 TextView 内不可横向滚动（v1 接受；后续可换 Compose 原生分段渲染）。
- frp 隧道需放行 WebSocket（Upgrade）与 Host 门禁白名单（桌面端 trustedHosts 需包含公网域名）。
- `session/list` 的 cursor 目前宿主忽略（全量返回），大会话量时列表可能较长。
- WS 断线期间的事件由 `session/page` 拉日志尾部补齐（幂等，水位去重），maxMessages 限 250。实测宿主对 `throughSeq` 超出日志 cursor 直接报错（"session page through seq … is past cursor …"），不钳位——因此兜底先经 `session/list` 取 `projections.asOfSeq`（随事件推进、恒 ≤ cursor）作 throughSeq，asOfSeq ≤ 当前水位时跳过。
- **已核实移除的端点（0.3.12 cohort）**：`session/inspect`。已核实存在的 session 面：list、create、prompt、cancel、selectModel、modelCatalog、search、page、follow、fork、rename、updateQueue、attachment、control（typert face 枚举自 `@deepseek-ai/dsh-api-session-controller`）。
- `agentPresets/select` 的 `agentId` 是 lookup 参数，要求会话在宿主侧处于 live 状态；宿主重启后未恢复的空白会话切换预设会报 lookup-not-found（重进会话即恢复）。插件 BFF 白名单只有 `agentPreset.list`：已有会话切换预设在插件通道不可用（提示走新建对话）。

## 7. 调试与踩坑记录（AI 维护者 / 新维护者必读）

### 7.1 环境事实

- **开发机即宿主机**：DSH 桌面端监听 `127.0.0.1:43120`（frp 公网 47030 转发至此）；桌面端源码在
  `D:\Deepseek Harness\DSH Desktop\resources\app.asar.unpacked`，**运行中的插件与 web 前端**在
  `~/.dsh/profiles/node_modules`（改这里要重启桌面端生效）。
- 仓库已是 git 仓库：`main` 分支，remote = `ColdMagicchess/dsh-mobile`，凭据在 Windows 凭据管理器（GCM）。
- 宿主凭据/签名密钥在 `~/.dsh/.credentials.yaml`（**不要提交、不要外传**）；core 网关对本机也强制鉴权，
  无本地旁路；本桌面版**没有** `?token=` 配对入口（全包检索 0 命中），手机配对只走插件 `?pair=`。

### 7.2 调试手法（ADB 端到端自测，全程可无人值守）

```bash
adb logcat -d -s DshPreset              # 预设名单加载日志（channel/n/ids）
adb shell screencap -p /sdcard/x.png    # 截图（PowerShell 的 `>` 重定向会损坏二进制，
adb pull /sdcard/x.png out.png          #  必须走 shell+screencap+pull 组合）
adb shell input tap X Y                 # 模拟点击，配合截图定位坐标
adb shell input keyevent KEYCODE_WAKEUP # 熄屏时先唤醒（授权弹窗掉线用 adb reconnect offline）
```

查第三方库行为**直接读 Gradle 缓存里的 sources jar**（如
`~/.gradle/caches/modules-2/files-2.1/io.noties.markwon/ext-latex/.../*-sources.jar`，
复制成 .zip 再 Expand-Archive），不要靠猜。

### 7.3 已踩过的坑

- **LaTeX 不渲染**：markwon-ext-latex 的 `inlinesEnabled` 默认 **false**（单行 `$$…$$` 与 `$…$` 全部
  原样显示）；块解析器还要求 `$$` **独占一行**。已通过 `MarkwonInlineParserPlugin` +
  `inlinesEnabled(true)` + `normalizeMath()`（单个 `$…$` 归一为 `$$…$$`，已成对 `$$` 块先占位保护）解决，
  见 `MarkdownText.kt`。注意 `JLatexMathPlugin.Builder.build()` 返回 **Config**，要用
  `JLatexMathPlugin.create(config)` 包装。
- **流式输出抽搐（LaTeX）**：打字机每 45ms 全文重解析会让每条 LaTeX 公式反复"归零→渲染→撑开"，且会截断
  半截公式。现行修法是 `MarkdownText.kt` 的公式 drawable LRU 缓存 + `stabilizeLatex` 同步回填
  （00a1503），打字机全程保留；更早的"含 `$` 跳过打字机"方案已废弃。
- **流式输出抽搐（表格，1.0.2 引入）**：`TablePlugin` 的 `TableRowSpan` 每次重建 span 都要经历
  "零宽首帧（`getSize` 返回初始 width=0）→ draw 中 `invalidator.invalidate()` → 二次布局撑开"，
  且表格 span 无法像公式 drawable 那样缓存——45ms 打字机下每秒塌缩-撑开二十余次。修法
  （`MarkdownText.kt`，渐进分段渲染）：`splitAtLastTableRow` 把最后一个**已完成表格行**之前的内容
  划为稳定前缀——前缀字符串在下一行完成前不变，Compose 跳过 `MarkdownText` 重组、不 `setMarkdown`，
  表格零闪烁地**逐行生长**；正在输入的尾行走打字机 + `neutralizeTablesForStreaming`（分隔行零宽
  前缀，保持段落形态），行完成即并入前缀。围栏代码块内的竖线行不算表格行，开着的围栏整体留在尾部。
  注意：只中和"开放中"的表格是不够的——表格关闭后若整条消息仍单 TextView 重解析，关掉的表格
  每个 45ms 步照样重建 span 抽搐，所以分段一旦开始就贯穿到 turn/end。围栏内竖线行可能导致
  代码块流式期间短暂分段，完成后收敛。塌缩帧本身曾引发滚动 bug（"自动跳到表格开头"）：
  行完成重建 span → 首帧塌缩 → 条目高度抖动一个表高 → LazyColumn 锚点失步后 `nearBottom`
  变 false、跟随钉底停用，视口卡在表格区域。修法：`stabilizeTables` 在 setMarkdown 后、布局前
  反射预播种 `TableRowSpan`（写入真实文本宽度 + 提前 `makeNewLayouts`），首次布局即得正确尺寸、
  彻底消除塌缩帧；Markwon 已归档冻结故反射结构稳定，异常一律降级回两遍布局。
  **播种的宽度来源必须是组合期约束（BoxWithConstraints + fillMaxWidth），不能用 `tv.width`**：
  消息条目滚出视口再滚回来时 TextView 重建，首帧 `tv.width == 0` → 播种被跳过 → 塌缩帧在
  "滚动重进"路径全面回归，条目高度边滚边变，视口被弹飞（1.0.2 实际踩过）。fillMaxWidth 同时
  消除了 TextView 按内容反推宽度的不确定性（表格 span getSize 返回 0 时会拖拽 wrap 宽度）。
  测试见 `MarkdownTextTest`（含 commonmark 端到端：前缀解析出 TableBlock、中和尾不再解析出）。
- **Compose 陷阱：LaunchedEffect 捕获 `remember(key)` 的旧状态**：打字机尾部加 `resetKey`（行并入
  前缀时整体重置 displayLen）后出现过"表格输出完正文失去打字机、整段跳变"——原因是 `displayLen`
  写成 `remember(resetKey) {...}` 而效果只以 `pending` 为 key：key 变化新建了 State，但仍在运行的
  协程闭包捕获的是**旧 State**，步进全写进无人读取的旧对象，UI 读的新对象永远停在重置值。
  修法：`LaunchedEffect(pending, resetKey)` 让效果随状态一起重启（`TypewriterMarkdown`）。
  凡是 effect 闭包要写、UI 又要读的状态，effect 的 key 必须覆盖该状态的 remember key。
- **"unexpected scheme: wss"（OkHttp 坑，1.0.3 修复）**：OkHttp 4 的 `HttpUrl.Builder.scheme()` 只接受 http/https，`scheme("wss")` 直接抛 `IllegalArgumentException`——WS 从未真正发出过（实时流一直在靠轮询兜底）。OkHttp 的正确用法是给 `newWebSocket` 传 **http/https URL**（https 连接自动按 wss 升级握手），不要手改 scheme。
- **自动跟随**：`nearBottom` 必须算"视口末端到列表末端剩余距离"（`last.offset+last.size-viewEnd`）。
  老写法 `viewportEnd-last.size` 在长消息内部滚动时为负值被误判为贴底，导致每个 chunk 把视图拽到最底部。
  跟随滚动用大偏移 `scrollToItem(lastIndex, 1_000_000)` 钉底——offset=0 会把长消息**顶部**弹进视口。
- **打字机重放**：`displayLen` 初值必须取当前全文（LazyColumn 释放滑出视口的消息后，滑回来重新
  进组合会从 0 重放整段）；含 `$` 的消息同理跳过打字机。
- **会话分组**：桌面端按工作区**挂载**分组——`session/create` 只传 `cwd` 不会挂载（落到"未分组"），
  必须传 `workspaceId`（与 `cwd` 互斥）。插件 `workspace.list` 的行里就有 `workspaceId`。
- **插件通道能力边界 = BFF 白名单**（`MOBILE_ALLOWLIST`）：缺能力先给插件加透传（见 PLUGIN_PATCH.md），
  别试图直连核心通道——本部署手机侧拿不到 core cookie。
- **PS 5.1 坑**：`Get-Content -Raw` 的字符串带 PSPath 等隐藏属性，`ConvertTo-Json` 会把它们序列化进去，
  改用 `[IO.File]::ReadAllText`；`Expand-Archive` 不认 `.jar` 后缀，先复制成 `.zip`；
  `Invoke-WebRequest -SkipHttpErrorCheck` 参数不存在（用 try/catch 读 `$_.Exception.Response`）。
