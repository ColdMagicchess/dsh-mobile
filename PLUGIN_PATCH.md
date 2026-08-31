# 插件补丁：dsh-remote-web-ui 手机通道扩展（归档对话）

> **为什么有这个补丁**：App 的插件通道（`?pair=` 配对）只能调用 remote-web-ui 插件 BFF 白名单里的方法。
> "归档对话" 需要核心网关的 `workspace/archiveSession`，"同步桌面端归档/删除" 需要读取 workspace
> 注册表的 `archivedSessionIds`——两者都不在插件白名单里，因此给**已安装的插件**打了运行时补丁。
> App 侧的归档与同步功能依赖这两个补丁方法；**插件更新会覆盖补丁**，症状与重打步骤见下文。

## 补丁位置

```
<DSH_HOME>\profiles\node_modules\@linxin666\dsh-remote-web-ui\lib\index.js
备份：同目录 index.js.dsh-mobile-bak（原始未打补丁版本）
```

## 改动内容（共 3 处）

### 1. MOBILE_ALLOWLIST 白名单（约 1475 行）

在 `"session.cancel"` 之后追加两个方法（注意给 `"session.cancel"` 补逗号）：

```js
const MOBILE_ALLOWLIST = /* @__PURE__ */ new Set([
    ...
    "session.rename",
    "session.cancel",
    "session.archive",
    "session.archived"
]);
```

### 2. 归档透传处理器（在 `if (method === "session.cancel") ...` 一行之后插入）

```js
if (method === "session.archive") return respond(await invokeGateway(gateway, "workspace", "archiveSession", invokeWireArgs("workspace", "archiveSession", body), signal));
if (method === "session.archived") return { type: "server-response", rpcId, result: { ok: true, value: { archivedSessionIds: Array.isArray(workspaceRegistry.archivedSessionIds) ? workspaceRegistry.archivedSessionIds : [] } } };
```

- `session.archive`：把手机发来的 `{sessionId}` 透传给核心网关的
  `workspace/archiveSession`（宿主官方归档 RPC，参数 `{"request":{"sessionId"}}`，
  返回 `{archivedSessionIds}`）。
- `session.archived`：返回 workspace 注册表的 `archivedSessionIds`
  （getter 位于 `@deepseek-ai/dsh-workspace` 的注册表服务），
  App 用它过滤列表，实现"桌面端归档/删除后手机同步隐藏"。

### 3. 无其他改动

`workspace.list` 保持原样；归档名单走独立的 `session.archived`，避免内联对象改写出错。

## 生效条件

**重启 DSH 桌面端**。插件在 cordis 启动时加载，不热更新——重启前调用会报
"未知方法" 之类的错误。

## 验证方法

1. 手机抽屉**长按**任意对话 → 圆角确认框 → 「归档」→ 该对话立即从列表消失，
   桌面端侧边栏也不再显示。
2. 桌面端归档/删除对话 → 手机打开抽屉（触发刷新）→ 该对话消失（同步）。
3. 报错 `unknown method` / 归档名单始终为空 → 补丁未生效（多半是被插件更新覆盖）。

## 插件更新后重打补丁（破坏性更新自检）

市场更新插件会覆盖 `lib/index.js`，补丁丢失。症状：手机归档报错、桌面端归档/删除后
手机列表不同步。重打步骤：

1. 打开 `lib/index.js`，在 `MOBILE_ALLOWLIST` 的 `"session.cancel"` 后按上文追加
   `"session.archive",` 和 `"session.archived"`。
2. 在 `if (method === "session.cancel") ...` 之后插入上文第 2 节的两行处理器。
3. 语法校验：`node --check lib/index.js`
4. 重启 DSH 桌面端。

> 排查顺序建议：手机报"未知方法" → 先查白名单；白名单有了但名单不同步 → 查
> `session.archived` 处理器；都正常但归档失败 → 查核心 RPC 是否变更
> （`dsh-api-workspace-controller` 的 typert 定义）。

## 回滚

```bat
copy "<DSH_HOME>\profiles\node_modules\@linxin666\dsh-remote-web-ui\lib\index.js.dsh-mobile-bak" ^
     "<DSH_HOME>\profiles\node_modules\@linxin666\dsh-remote-web-ui\lib\index.js"
```
然后重启 DSH 桌面端（App 的归档按钮会报错，其余功能不受影响）。

## 长期建议

给 `@linxin666/dsh-remote-web-ui` 上游提 PR：白名单加入 `workspace.archiveSession`
（或等价的 `session.archive`）并随 `workspace.list` 暴露 `archivedSessionIds`；
合并后即可移除本补丁。