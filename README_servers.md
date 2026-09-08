# DSH 远程连接服务说明（安卓客户端对接版）

> 版本：2026-09-08 · 服务端部署：阿里云轻量服务器 Ubuntu-yfuf（47.122.111.64）
> 适用对象：需要远程连接本服务器 DSH Web GUI 的安卓客户端

---

## 1. 架构总览

```
安卓 App
   │  ① HTTPS (443, TLS + 自签证书)
   ▼
Caddy 2.11.4  ──  第二层：Basic Auth（账号密码）
   │  ② 反向代理（本机回环）
   ▼
DSH Web (127.0.0.1:3080)  ──  第三层：Token → 会话 Cookie
   │
   ├── 完整 Web GUI（官方界面 + dsh-web-all 插件全家桶）
   └── 各类 JSON / WebSocket API
```

**关键点：DSH 本体只监听 127.0.0.1，从不暴露公网**。公网唯一入口是 Caddy 的 443。
公网端口 8080 也由 Caddy 监听（HTTP，功能与 443 相同，仅无 TLS），目前阿里云防火墙未放行，可视为备用通道。

---

## 2. 固定参数速查表

| 项目 | 值 |
|---|---|
| 服务器公网 IP | `47.122.111.64` |
| 主入口 | `https://47.122.111.64`（443 端口） |
| 备用入口 | `http://47.122.111.64:8080`（未放行，需在阿里云防火墙加规则） |
| Caddy Basic Auth | 用户名 `dsh` / 密码 `dsh-ylear222q8sd`（Realm: `restricted`） |
| Caddy 根证书（客户端信任用） | 工作区 `dsh-caddy-root.crt`（CN=Caddy Local Authority - 2026 ECC Root，有效期至 2036-07-17） |
| DSH 登录 token | 每次服务器上执行 `dsh web` 启动时打印（URL 中 `?token=...` 部分），也写入日志 `/home/admin/.dsh/web.log`；仅用于新设备首次登录，**已有设备的会话 Cookie 长期有效（10 年），不随 dsh 重启失效** |
| 凭据存储位置（服务端） | `/home/admin/caddy/Caddyfile`（密码为 bcrypt 哈希） |
| Caddy 日志 | `/home/admin/caddy/caddy.log` |

---

## 3. 三层认证详解

### 第一层：TLS（自签证书）

服务端证书由 Caddy 内部 CA（`tls internal`）签发，**不含受信任的公共根**，安卓默认信任库会拒绝。
正确做法：把 `dsh-caddy-root.crt` 打进 App，只信任这个根，**不要**使用"信任一切证书"的裸奔写法。

细节：

- 证书 SAN 为 IP `47.122.111.64`（无域名，因此浏览器/客户端会提示"不受信任"，属于预期行为）；
- 客户端访问裸 IP 时按 RFC 不发送 SNI，服务端已配置 `default_sni` 兜底，任何 TLS 客户端均可正常握手；
- ALPN 支持 h2 与 http/1.1；WebSocket 走同端口。

### 第二层：Caddy Basic Auth

所有请求（含 WebSocket 升级请求）都必须携带：

```http
Authorization: Basic base64("dsh:dsh-ylear222q8sd")
```

- 凭据错误 → `401 Unauthorized`，响应头 `WWW-Authenticate: Basic realm="restricted"`；
- 每个请求都要带（Caddy 无会话概念），推荐在 OkHttp `authenticator` 中统一注入。

### 第三层：DSH Token → 会话 Cookie

DSH 自身的认证基于"启动令牌换取签名 Cookie"：

1. **获取 token**：服务器上每次执行 `dsh web` 启动时，终端会打印：

   ```
   dsh web: http://127.0.0.1:3080/?token=<一长串>
   ```

   取其中的 `<一长串>` 即为 token（base64url，32 字节）。token 仅用于**新设备首次换取 Cookie**；换取后 Cookie 长期有效（见下）。

   **长期连接机制（重要）**：Cookie 的签名密钥持久保存在服务器 `~/.dsh/.credentials.yaml`，**dsh 重启、服务器重启都不会使已发出的 Cookie 失效**。Cookie 有效期由服务端配置 `cookieMaxAgeDays` 控制，当前已设为 **3650 天（10 年）**（定义于 profile 补丁层 `~/.dsh/profiles/web/cordis.patch.yml`）。因此 App 只需成功登录一次，即可长期免输入。

2. **换取 Cookie**（一次性登录）：

   ```http
   GET /?token=<token> HTTP/1.1
   Host: 47.122.111.64
   ```

   成功响应：`303` + `Set-Cookie: dsh-auth-<hash>=v1.<payload>.<sig>; Max-Age=...; Path=/; HttpOnly; SameSite=Strict` + `Location: /`

   - Cookie 名：`dsh-auth-` + base64url(sha256(Host))，IP 直连 443 时 Host 为 `47.122.111.64`（无端口）；
   - Cookie 与访问域名/端口绑定（authority-bound）：换端口（如 8080）需重新用 token 换一次；
   - token 无效 → `401`（响应体 `dsh web authentication required; reopen the URL printed by dsh web.`）。

3. **后续所有请求**携带该 Cookie 即可，直到其过期或 dsh 重启。

> **Token 的产品化建议**：不要在 App 内写死 token。首次引导时让用户"扫码 / 粘贴 token"，App 换取 Cookie 后持久化保存，并用一个轻量探针接口（如 `GET /` 返回 200）检测会话失效，失效时重新引导输入。

---

## 4. 安卓端推荐实现（OkHttp + Kotlin）

### 4.1 信任 Caddy 根证书（替代系统默认信任库）

```kotlin
fun dshSslSocketFactory(context: Context): Pair<SSLSocketFactory, X509TrustManager> {
    val ca = context.assets.open("dsh-caddy-root.crt").use {
        CertificateFactory.getInstance("X.509").generateCertificate(it)
    }
    val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
        load(null, null)
        setCertificateEntry("dsh-caddy-ca", ca)
    }
    val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }
    val tm = tmf.trustManagers[0] as X509TrustManager
    val sc = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), SecureRandom()) }
    return sc.socketFactory to tm
}
```

### 4.2 组装客户端（Basic Auth + Cookie Jar）

```kotlin
val (ssf, tm) = dshSslSocketFactory(context)

val cookieJar = object : CookieJar {                       // 至少持久化到磁盘
    private val store = mutableMapOf<String, List<Cookie>>() // 建议换成 SharedPreferences/DataStore
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        store[url.host] = cookies
    }
    override fun loadForRequest(url: HttpUrl): List<Cookie> = store[url.host] ?: emptyList()
}

val client = OkHttpClient.Builder()
    .sslSocketFactory(ssf, tm)
    .authenticator { _, response ->                        // 第二层：Basic Auth 自动补发
        response.request.newBuilder()
            .header("Authorization", Credentials.basic("dsh", "dsh-ylear222q8sd"))
            .build()
    }
    .cookieJar(cookieJar)                                  // 第三层：DSH 会话 Cookie
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .build()
```

### 4.3 登录（token 换 Cookie）

```kotlin
val url = "https://47.122.111.64/?token=$userInputToken"
client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
    when (resp.code) {
        303, 200 -> saveSessionOk()      // Set-Cookie 已由 cookieJar 收下
        401 -> if (resp.header("WWW-Authenticate")?.contains("Basic") == true)
                   onCaddyAuthError()    // 第二层失败：账号密码错
               else onDshTokenInvalid()  // 第三层失败：token 错或已失效
    }
}
```

### 4.4 会话探针与重登

- 探针：`GET https://47.122.111.64/` → `200` 会话有效；`401` 说明 Cookie 失效 → 重新走 4.3；
- Cookie 有效期 10 年，且跨 dsh/服务器重启有效。只有这些情况需要重新登录：
  1. 服务器上 `~/.dsh/.credentials.yaml` 被删除或重置（DSH 全新初始化）；
  2. 用户在服务器上主动修改 `cookieMaxAgeDays` 或重置凭据；
  3. App 本地清除了存储的 Cookie。
- 每次成功请求都会收到新的 `Set-Cookie`（滚动续期），cookieJar 应当总是覆盖保存最新值。

---

## 5. WebSocket 接口（实时数据）

与 HTTP 同源同端口，握手时携带同样的 Basic Auth 头与 Cookie：

| 用途 | 地址 |
|---|---|
| 远程多路复用通道（移动端主通道） | `wss://47.122.111.64/api/remote.mux` |
| SSH 终端 | `wss://47.122.111.64/api/dsh-ssh/terminal` |
| 会话终端流 | `wss://47.122.111.64/sidebar/ws/agent-terminals` |

OkHttp 的 `WebSocket` 使用同一个 `client` 实例即可（自动带上 SSL 与 Cookie）。

---

## 6. 已知 API 路径（来自当前部署的实际观测）

| 路径 | 说明 |
|---|---|
| `GET /` | GUI 首页（token 登录入口） |
| `/api/dsh-web-ui-settings` | dsh-web 插件组设置读写 |
| `/api/pair/`、`/pair-accept?pair=<token>` | 移动端设备配对（一次性令牌，配对成功即失效） |
| `/api/remote.mux` | 移动端远程多路复用通道（WebSocket） |
| `/api/dsh-ssh/terminal` | SSH 面板终端（WebSocket） |
| `/api/update/` | 更新检查 |
| `/sidebar/ws/terminal`、`/sidebar/ws/agent-terminals` | 右侧面板终端流（WebSocket） |

> 若 App 目标是"完整界面"而非逐个接口对接，最省力方案是 WebView 嵌入：
> 用 `android.webkit.CookieManager` 同步 OkHttp CookieJar 里的会话 Cookie，
> 并在 `network_security_config.xml` 中把 `dsh-caddy-root.crt` 声明为 47.122.111.64 的信任锚，
> WebView 即可无警告加载。

```xml
<!-- res/xml/network_security_config.xml（WebView 与 OkHttp 均可受益） -->
<network-security-config>
    <domain-config>
        <domain includeSubdomains="false">47.122.111.64</domain>
        <trust-anchors>
            <certificates src="@dsh_caddy_root"/>
        </trust-anchors>
    </domain-config>
</network-security-config>
```

（`dsh_caddy_root` = 放入 `res/raw/` 的根证书；注意 NSC 对 IP 字面量域名的支持需 Android 7+ 实测，OkHttp 路线不受此限制，优先用 4.1 的代码方案。）

---

## 7. 错误对照表

| 现象 | 原因 | 处理 |
|---|---|---|
| TLS 握手失败 / 证书不受信任 | 未导入 Caddy 根证书 | 按 4.1 导入 `dsh-caddy-root.crt` |
| `401` + `WWW-Authenticate: Basic` | 第二层账号密码错误 | 核对 2 节凭据 |
| `401` + 响应体 `dsh web authentication required` | 第三层 Cookie 失效/缺失 | 重新用 token 换 Cookie |
| `502 Bad Gateway` | Caddy 活着但 dsh 没起 | 服务器上启动 `dsh web` |
| 连接超时 | 阿里云防火墙/安全组未放行 443 | 控制台检查（当前 443 已放行） |
| 配对链接打开即失效 | 配对令牌一次性，且 dsh 重启会作废 | 服务器面板重新生成二维码 |

---

## 8. 安全注意事项（务必阅读）

1. **凭据不要硬编码进 APK**：Basic Auth 密码与 token 都应通过首次引导由用户输入/扫码获得，运行时加密存储（Android Keystore + EncryptedSharedPreferences）。
2. **token 仅用于新设备首次登录**；已登录设备的会话由 10 年期 Cookie 维持，token 轮换不影响存量会话。Cookie 泄露的后果等同账号泄露，务必加密存储。
3. 当前 443 对全互联网开放（阿里云防火墙 0.0.0.0/0）。若 App 用户面小，建议把防火墙源 IP 收紧为常用出口，或给 Caddy 的 Basic Auth 换强密码。
4. 禁止在 App 中关闭证书校验（信任所有证书）——用根证书白名单即可，安全性与便利性兼得。
5. 服务器重启后：dsh web 与 Caddy 均由 crontab `@reboot` 自动拉起（dsh 启动日志与最新 token 见 `/home/admin/.dsh/web.log`），App 凭已有 Cookie 自动恢复连接，无需任何人工输入。

---

## 9. 附：服务端变更入口

| 需求 | 位置 |
|---|---|
| 修改 Basic Auth 密码 | `/home/admin/caddy/Caddyfile` 中 `basic_auth` 哈希（用 `caddy hash-password` 生成），改后 `./caddy reload --config Caddyfile` |
| Caddy 开机自启 | admin 用户 crontab `@reboot` 行 |
| DSH web 开机自启 | admin 用户 crontab `@reboot` 行（启动日志与 token 见 `/home/admin/.dsh/web.log`） |
| 更换/查看 TLS 证书 | `tls internal` 自动管理，根证书 `/home/admin/.local/share/caddy/pki/authorities/local/root.crt` |
| DSH 插件源码 | 工作区 `dsh-web-0.3.15/`（聚合包 0.3.15 本地构建，含 #1372 上游修复） |
