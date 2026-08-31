# 附录：原始工程方案（PLAN）

> 本文是项目初版的完整工程方案文档，供了解设计脉络。当前功能状态与 API 契约以 [IMPLEMENTATION.md](../IMPLEMENTATION.md) 为准。

# Deepseek Harness Mobile — 原生 Android 远端客户端 · 工程方案

> 本文档是把这个安卓空工程改造成“DeepSeek Harness 原生远端 App”的**详细工程方案**。
> 目标：**独立原生 UI + 只对接 DSH 稳定核心接口**，完整保留下方“必须保留的功能清单”（含**逐字渲染**、实时流、公式渲染、图片上传等）。
> 供后续任意智能体**按本方案执行**，无需回到之前的上下文。

---

## 1. 目标

做一个**纯原生 Android App**（不依赖插件的 web UI / 手机 web 页面），直接**对接 DSH 宿主的稳定核心接口**，用于**控制工程进度 / 提问 / 看对话**。

- App 自己的 UI（Kotlin + Jetpack Compose），不加载插件 web UI。
- 只依赖 DSH 核心 API（桌面端 Web 用的那套，稳定），不依赖插件的私有补丁（如 session.pending、web 端的打字机/kaTeX 等都是插件内实现，App 要自己实现这些能力）。
- 对插件作者后续更新免疫（作者只更新插件 UI，不动核心接口）。

## 2. 必须保留的功能清单（现有网页端远端有的能力，App 必须复刻）

| # | 功能 | 说明 |
| --- | --- | --- |
| F1 | 配对 / 连接 | 连到公网 frp IP（如 https://your-host.example.com:47030），完成配对，持配对 cookie |
| F2 | 会话/工作区列表 + 切换 | 列出工作区与会话，可切换 |
| F3 | 新建会话 | 在工作区新建会话 |
| F4 | 聊天（消息列表） | 用户 / 助手消息，Markdown 渲染 |
| F5 | 逐字/打字机渲染（重点） | 生成中的消息按“每 45ms 前进几个字符”渐进显示（参考网页端 MarkdownText 的 displayLen 逻辑） |
| F6 | 实时流（重点） | 生成期间边出边刷新：200ms 轮询（或 SSE）取增量。不能只等整段结束 |
| F7 | 公式（LaTeX）渲染 | 用原生 LaTeX 渲染（如 JLaTeXMath），不用 KaTeX / 不用整页 WebView |
| F8 | 思考(reasoning) 展示 | 助手“思考”内容可折叠展示（collapsible disclosure） |
| F9 | 工具调用展示 | 助手用到的工具（工具名 + 参数）折叠展示 |
| F10 | 图片上传 | 选图（Photo Picker）→ base64 → 随 session.prompt 发送 |
| F11 | 发送 / 停止 | 发消息（支持 queue/steer 模式）、停止当前回合 |
| F12 | 切换模型 | provider/model/reasoningEffort 选择 |
| F13 | 消息滚动策略 | 贴近底部才自动跟随；用户往上滚动看上面内容时不强制拉回（nearBottom < 80px 才跟随；首次进会话滚到底） |
| F14 | 长消息折叠 | 超长消息折叠成“展开全文”，展开按钮不能被裁掉（裁剪只作用正文，按钮在裁剪区外） |

## 3. DSH 接口契约（App 要对接的）

### 3.1 两种可用通道（优先核心 API）
- 核心 API（推荐，稳定）：桌面端 Web 用的那套。含会话操作 + events.mux（SSE 实时事件，桌面就靠它流式）。配对 cookie 可过其门禁。
- 插件 /m/api（备选，参考）：插件给手机 web UI 的简化 BFF，契约简单、已知，但插件私有（作者更新可能改）。仅作对照/快速打通用。

- 注意：P1 必须先做探针：用配对 cookie 直接调核心 API（列表 / 历史 / events.mux），验证“原生 App 能否直连核心 API 拿到实时”。走通则全工程以核心 API 为主线；若核心 API 鉴权/契约有障碍，再退回 /m/api（此时需把插件相关补丁冻结成自有分支）。

### 3.2 请求信封（POST 到 host/api/<method>）

请求: { type: client-request, rpcId: <string>, method: <name>, payload: {...} }
响应: { type: server-response, rpcId: <same>, result: { ok: true, value: {...} } | { ok: false, error: { code, message } } }

常用 method：session.list、session.create、session.history、session.search、session.prompt、session.models、session.selectModel、session.rename、session.cancel、workspace.list、workspace.create。

### 3.3 session.prompt 入参（SessionPromptRequest 形状，App 要拼）

{ requestId: <uuid，必填；App 每次生成>, sessionId: <string，必填>, mode: queue | steer,
  content: [ { type: text, text: <string> } , { type: image, mediaType: image/png|jpeg|webp|gif, data: <base64，不含 data:...;base64, 前缀>, name: <可选> } ], clientTimeZone: <可选> }

### 3.4 事件 / 消息结构（fold 用）

session.history / 实时事件返回 { events: [ { event: { type, seq, time, data } } ] }。
关键 event.type：user/message（用户消息 id/role/content/source）、assistant/message（turn/step/message{id,content}/usage）、assistant/chunk（或 message/chunk，data={turn,step,chunk:{type:text-delta|reasoning-delta,text}}，增量）、message/update、turn/start、turn/end（reason）、tool/call（工具名+参数）、request/context（provider/model/contextWindow）。

fold 规则（App 要移植网页端 messages.ts 的 fold）：
- 事件按 seq 升序应用；seq 水位 = 已渲染消息的最大 seq，≤ 水位的丢弃。
- user/message、assistant/message 按 id 去重、原位替换；assistant/chunk 追加到“进行中”助手消息（text 与 reasoning 分开累积）；turn/end 结束该消息（pending=false）。

## 4. 技术栈（建议）

| 项 | 选型 |
| --- | --- |
| 语言 / UI | Kotlin + Jetpack Compose（Material 3）。工程目前是 Java+View，需加 Kotlin + Compose 插件。若想最小改动可先 Java+View，但流式/打字机更啰嗦 |
| 网络 | OkHttp（HTTP）+ okhttp-sse（若用 SSE）+ Moshi（JSON）+ Coroutines |
| 实时 | 优先 SSE events.mux；若隧道不透传 SSE则退 200ms 轮询（取增量事件，用 seq 水位去重） |
| Markdown | Markwon（markdown→Spannable）或自实现轻量 GFM |
| LaTeX | JLaTeXMath（纯 Java → Bitmap/Image）；不用 KaTeX |
| 存储 | DataStore / 加密存储存 host URL + 配对 cookie |
| 图片 | Android Photo Picker → base64 |
| 架构 | ViewModel + StateFlow + Coroutines |

## 5. 分阶段路线图（先打通主线，逐层加）

- P1 网络骨架 + 配对 + 会话列表（探针）：建网络层（OkHttp + RPC 信封 + 鉴权头），实现配对（拿 cookie），session.list/会话列表 UI。验收：App 能连上公网 host、成功配对、列出会话/工作区。
- P2 聊天基础：打开会话、session.history 加载历史消息（Markdown 渲染）、session.prompt 发消息（纯文本）。
- P3 实时流 + 逐字渲染（重点）：SSE 或 200ms 轮询取增量 → fold → 打字机渐进显示（displayLen）。验收：生成时逐字刷新、不卡、可回看上方（near-bottom 才跟随）。
- P4 渲染完善：LaTeX（JLaTeXMath）、reasoning/工具折叠、长消息展开（按钮不被裁）、Markdown 完善。
- P5 图片 + 交互完善：图片上传、模型/权限选择、停止、新建会话、主题、错误处理。
- P6 打磨：离线判断、重连、加载态、无障碍等。

## 6. 关键实现细节（给智能体的“怎么做”）

### 6.1 实时流 + 逐字渲染（网页端逻辑，需移植）
- 流：优先 SSE（events.mux，OkHttp SSE）；若不通，则每 200ms 轮询新增事件端点。用 lastSeq 水位去重。
- fold：按 3.4 规则，把增量事件折进消息列表（StateFlow 持有 List<ChatMessage>）。
- 逐字/打字机：对“进行中”助手消息，展示时用 displayLen 状态，每 45ms 前进 max(1, min(9, ceil((text.length - prev)/12))) 个字符；只渲染 text.slice(0, displayLen)；pending=false（turn/end）时立即显示全量。参考网页端 ChatView.tsx 的 MarkdownText。
- 滚动：初次进会话滚到底；之后仅当 scrollHeight - scrollTop - clientHeight < 80px 才跟随；否则（用户回看上面）不打断。

### 6.2 公式渲染
- 用 JLaTeXMath：把消息里的 $...$ / $$...$$ / \(...\) / \[...\] 文本段提取 → JLaTeXMath 渲染成 Bitmap → 用 Compose Image 展示（行内或块级居中、宽公式可横滚）。
- 定界符识别参考网页端 markdown.ts：对 $...$ 要启发式（含 \、^、_ 或无空格的运算符才算公式，避免把 $5 当公式）。

### 6.3 图片上传
- Photo Picker 选图 → 校验 mediaType（image/png|jpeg|webp|gif）、单张≤20MB、≤10 张 → File 转 base64（去掉 data:...;base64, 前缀）→ 拼进 session.prompt 的 content 为 {type:image,...}。
- 预览缩略图 + 可移除。

### 6.4 消息渲染与会话管理
- 会话/工作区：workspace.list / session.list。切换工作区刷新会话。
- 新建：session.create（{ workspaceId?, cwd?, agentPreset? }）。
- 切模型：session.selectModel（{ sessionId, provider, model, reasoningEffort? }）。
- 停止：session.cancel。

### 6.5 其他
- requestId：session.prompt 每次必须带新 UUID 的 requestId（否则网关报 wire field "request" failed boundary validation）。
- 鉴权：配对 cookie 作为请求头（cookie name 取插件 service.config.cookieName；核心 API 需确认鉴权头/协议——P1 探针确认）。
- 持久化：host URL + cookie 存加密存储。

## 7. 风险与决策

| 风险 | 说明 / 对策 |
| --- | --- |
| 核心 API 鉴权/契约 | P1 探针确认；若核心 API 难走，退 /m/api（此时把插件补丁做成自有分支冻结） |
| SSE 透传（frp） | 若 events.mux 的 SSE 在隧道不通，退 200ms 轮询（配合打字机观感接近逐字） |
| 原生 LaTeX 质量 | JLaTeXMath 已够数学笔记主流公式；极冷僻宏可能差，遇问题可局部用 WebView 兜底 |
| 工作量大 | 全原生重写聊天 UI；按 P1→P6 分步，先 P1 打通再逐步 |

## 8. 验收标准（做完一段对照一条）

- App 能连上公网 host、配对成功、列出会话/工作区（P1）
- 打开会话能看到历史消息（Markdown 渲染）（P2）
- 发消息能收到回复；生成期间逐字刷新、能回看上方不被打断（P3 重点）
- 公式（分式/积分/求和/希腊/矩阵）正常渲染（P4）
- 思考、工具调用可折叠展示（P4）
- 长消息折叠后“展开全文”按钮可点、可见（P4）
- 图片能选、预览、随消息发送（P5）
- 可切模型、新建会话、停止当前回合（P5）

## 9. 给执行智能体的提醒

- 先做 P1 探针确认核心 API 可用，再决定主线（核心 API vs /m/api）。
- 每一步都保留现有功能，尤其 F5 逐字渲染、F6 实时流、F7 公式、F10 图片——这些是用户明确要求不能丢的。
- App 与 DSH 宿主解耦：只依赖稳定接口，不要把插件 web 端那些补丁（session.pending、打字机、kaTeX 资源）当成 App 的依赖。
- 构建：./gradlew assembleDebug（SDK 已就绪，依赖走 google/mavenCentral）。