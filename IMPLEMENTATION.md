# Deepseek Harness Mobile — 实现说明

> 本文档记录 App 的实际实现与**经 DSH 源码核实的真实 API 契约**（与 README 方案的假设有多处出入，以本文为准）。
> 代码结构、构建方式、以及后续迭代建议。

## 1. 技术栈（已落地）

| 项 | 实现 |
| --- | --- |
| 语言 / UI | Kotlin + Jetpack Compose（Material 3, dynamic color） |
| 网络 | OkHttp（HTTP RPC + WebSocket mux）；kotlinx.serialization JSON（JsonObject 防御式解析） |
| 实时 | WebSocket `/api/remote.mux` 逻辑流 `session/follow`；失败自动重连，2 次后退化为 `session/inspect` 2s 轮询（seq 水位去重） |
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
| `session/inspect` | `{"sessionId":…}`（轮询兜底） | `{meta,events:[SessionEvent]}` |
| `agentPresets/list` | `{}`（无参数） | `{presets:[{id,trust,isDefault,name?,description?,broken?}],authorable}` |
| `agentPreset.list`（插件 BFF） | `{}` | 同上（插件代理核心网关）；BFF 白名单只有 list，没有 select |
| `workspace/archiveSession` | `{"request":{"sessionId":…}}` | `{archivedSessionIds:[…]}`；归档后宿主在 workspace 状态中记录，`session/list` 仍会返回该会话 |
| `session.archive` / `session.archived`（插件 BFF，本机补丁） | `{sessionId}` / `{}` | 归档透传 / 返回注册表 `archivedSessionIds`；补丁位于已安装插件 lib/index.js，改动需重启 DSH 桌面端生效。补丁详情、插件更新后重打步骤见 **PLUGIN_PATCH.md** |
| `agentPresets/select` | `{agentId:<sessionId>,agentPreset:<id>}`（args 平铺；agentId 由网关 lookup 解析为 live agent） | 预设 id；会话已开始报错码 `agent-preset-locked` |

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

JDK 17 + Android SDK（local.properties 指向）。输出：`app/build/outputs/apk/debug/app-debug.apk`。

## 5. 已实现 / 待办对照（README F1–F14）

- ✅ F1 配对/连接（token 链接自动配对 + 手动 cookie；持久化加密）
- ✅ F2 会话列表（平铺 + cwd 标签；workspace/follow 分组未做。抽屉长按对话可归档——圆角阴影确认框；列表过滤 `archivedSessionIds`，桌面端归档/删除后打开抽屉即同步隐藏）
- ✅ F3 新建会话（FAB；优先带 `workspaceId` 挂载到所选工作区——桌面侧边栏按挂载分组，仅传 cwd 会落到"未分组"；无工作区 id 时回退 cwd）
- ✅ F4 聊天消息列表（Markdown，user/assistant 分列）
- ✅ F5 逐字渲染（45ms 步进 displayLen）
- ✅ F6 实时流（WS mux + 重连 + inspect 轮询兜底 + seq 水位）
- ✅ F7 公式渲染（Markwon + JLaTeXMath）
- ✅ F8 思考折叠（reasoning 块 / reasoning-delta 累积）
- ✅ F9 工具调用折叠（tool/call + tool/result + tool-call-delta）
- ✅ F10 图片上传（Photo Picker → base64 → prompt content）
- ✅ F11 发送/停止（queue/steer 切换、session/cancel）
- ✅ F12 切换模型（modelCatalog + selectModel + reasoningEffort）
- ✅ F13 滚动策略（首进到底；nearBottom < 80px 才跟随）
- ✅ F14 长消息折叠（截断正文，按钮在正文之外）
- ✅ F15 智能体预设切换（插话模式旁新增预设按钮，点击展开圆形矩阵弹层；模型/工作区菜单改圆角矩形。名单两通道都支持：核心 `agentPresets/list`、插件 `agentPreset.list`。草稿态记住选择、首发消息随 `session/create` 的 `agentPreset` 下发；已有会话仅核心通道可走 `agentPresets/select` 切换（会话开始后宿主拒绝：agent-preset-locked），插件通道提示不可用）
- ⏳ 待办：历史分页加载更早消息（session/page）、workspace/follow 工作区分组、附件取回（session/attachment 渲染历史图片）、$events 流驱动会话列表实时刷新、消息重发/编辑队列（updateQueue）、深链/快捷入口。

## 6. 已知风险

- `LatexPlugin` 极宽公式在 TextView 内不可横向滚动（v1 接受；后续可换 Compose 原生分段渲染）。
- frp 隧道需放行 WebSocket（Upgrade）与 Host 门禁白名单（桌面端 trustedHosts 需包含公网域名）。
- `session/list` 的 cursor 目前宿主忽略（全量返回），大会话量时列表可能较长。
- WS 断线期间的事件由 `session/inspect` 全量补齐（幂等，水位去重），长会话下流量偏大；后续可换 `session/page` 增量。
- `agentPresets/select` 的 `agentId` 是 lookup 参数，要求会话在宿主侧处于 live 状态；宿主重启后未恢复的空白会话切换预设会报 lookup-not-found（重进会话即恢复）。插件 BFF 白名单只有 `agentPreset.list`：已有会话切换预设在插件通道不可用（提示走新建对话）。
