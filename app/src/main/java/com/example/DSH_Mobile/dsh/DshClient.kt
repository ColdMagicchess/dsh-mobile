package com.example.DSH_Mobile.dsh

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.URL
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Transport for the DSH host. Two channels (dsh-web-all 0.3.12 contract):
 *  - "core":   POST /api/<ns>/<method> (payload wrapped in {args:{...}}) + WS /api/remote.mux,
 *              authenticated by the authority-bound dsh-auth cookie.
 *  - "plugin": the remote-web-ui gated mirror — POST /remote/api/<ns>/<method> and
 *              WS /remote/api/remote.mux, authenticated by the paired-device cookie
 *              (dsh_pair) and, cookieless, by the device id (x-dsh-remote-device header
 *              on HTTP, ?device= on the WS upgrade). The plugin proxies every call to
 *              the loopback host API, so both channels share one RPC surface; only the
 *              pairing-control / self-update / plugin-manager / desktop-launcher
 *              prefixes stay physically local. The 0.3.6 /m/api BFF no longer exists.
 */
class DshClient {

    @Volatile var host: String = ""
    @Volatile var cookieValue: String = ""
    @Volatile var cookieNameOverride: String = ""
    /** Paired-device session id (0.3.12 accept body); the cookieless fallback credential. */
    @Volatile var deviceId: String = ""
    @Volatile var channel: String = "core"

    @Volatile var trustInsecure: Boolean = false
        private set

    @Volatile private var http: OkHttpClient = buildHttp(false)

    /**
     * TOFU trust for self-signed tunnels (e.g. SakuraFrp automatic TLS, whose
     * leaf is self-signed and has no SAN, so installing a CA cannot help).
     * Pairing remains protected by the one-time desktop-issued token.
     */
    fun setTrustInsecure(value: Boolean) {
        if (trustInsecure == value) return
        trustInsecure = value
        http = buildHttp(value)
    }

    private fun buildHttp(insecure: Boolean): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .pingInterval(30, TimeUnit.SECONDS)
        if (insecure) {
            val trustAll = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val sslContext = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(trustAll), SecureRandom())
            }
            builder.sslSocketFactory(sslContext.socketFactory, trustAll)
            builder.hostnameVerifier { _, _ -> true }
        }
        return builder.build()
    }

    fun configured(): Boolean = host.isNotBlank() && cookieValue.isNotBlank()

    fun normalizeHost(input: String): String {
        var h = input.trim()
        if (h.isEmpty()) return h
        if (!h.contains("://")) h = "https://$h"
        return h.trimEnd('/')
    }

    private fun baseUrl(): String = normalizeHost(host)

    /** Canonical authority the server derives from the Host header. */
    fun authority(): String {
        val raw = host.trim().trimEnd('/')
        return try {
            val url = URL(if (raw.contains("://")) raw else "https://$raw")
            val portPart = if (url.port != -1 && url.port != 80) ":${url.port}" else ""
            url.host.lowercase() + portPart
        } catch (t: Throwable) {
            raw
        }
    }

    fun cookieName(): String {
        cookieNameOverride.trim().takeIf { it.isNotEmpty() }?.let { return it }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(authority().toByteArray(Charsets.UTF_8))
        val b64 = Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        return "dsh-auth-$b64"
    }

    fun cookieHeader(): String {
        val v = cookieValue.trim()
        return if (v.contains('=')) v else "${cookieName()}=$v"
    }

    /** HTTP prefix of the active channel: gated mirror (plugin) or direct host API (core). */
    fun apiPrefix(): String = if (channel == "plugin") "/remote/api" else "/api"

    /** Cookieless device credential headers (0.3.12 /remote gate accepts them like the cookie). */
    private fun Request.Builder.channelAuth(): Request.Builder {
        header("Cookie", cookieHeader())
        if (channel == "plugin" && deviceId.isNotBlank()) {
            header("x-dsh-remote-device", deviceId.trim())
        }
        return this
    }

    data class PairResult(
        val cookie: String,
        val channel: String,
        val cookieName: String,
        val deviceId: String = "",
    )

    /**
     * Pair from pasted input. Auto-detects the format:
     *  - URL containing pair=   → plugin channel (POST /api/pair/accept {token});
     *  - URL containing token=  → core channel (GET /?token= → 303 Set-Cookie);
     *  - bare string            → try pair token, then launch token.
     */
    suspend fun pair(input: String): PairResult = withContext(Dispatchers.IO) {
        val s = input.trim()
        if (s.isEmpty()) throw DshApiException("bad-input", "请填写配对链接")
        val params = parseQuery(s)
        when {
            params["pair"] != null -> acceptPair(params.getValue("pair"))
            params["token"] != null -> launchToken(params.getValue("token"))
            s.contains("://") -> throw DshApiException("bad-link", "链接中未找到 pair= 或 token= 参数")
            else -> runCatching { acceptPair(s) }.getOrElse { launchToken(s) }
        }
    }

    private fun parseQuery(input: String): Map<String, String> {
        if (!input.contains("://")) return emptyMap()
        return runCatching {
            val q = URL(input).query ?: return@runCatching emptyMap<String, String>()
            q.split('&').mapNotNull { seg ->
                val at = seg.indexOf('=')
                if (at <= 0) null else seg.substring(0, at) to URLDecoder.decode(seg.substring(at + 1), "UTF-8")
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    private fun acceptPair(token: String): PairResult {
        val url = baseUrl() + "/api/pair/accept"
        val body = buildJsonObject { put("token", token) }.toString()
        val req = Request.Builder().url(url)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            when {
                resp.code == 404 -> throw DshApiException("pair-invalid", "配对链接无效或已过期，请在桌面端重新复制一条")
                resp.code == 409 -> throw DshApiException("pair-used", "配对链接已被使用，请在桌面端重新复制一条")
                resp.code == 429 -> throw DshApiException("pair-ratelimited", "尝试过于频繁，请半分钟后再试")
                resp.code == 403 -> throw DshApiException("forbidden", "桌面端拒绝了配对：请确认主机地址与桌面端「远程访问」设置", resp.code)
                !resp.isSuccessful -> throw DshApiException(null, "配对失败：HTTP ${resp.code}", resp.code)
            }
            // 0.3.12 answers {ok:true, deviceId} and sets the paired-device cookie.
            val bodyDevice = runCatching {
                DSH_JSON.parseToJsonElement(resp.body?.string() ?: "").asObj()?.str("deviceId")
            }.getOrNull().orEmpty()
            val setCookie = resp.headers("set-cookie").firstOrNull { it.trimStart().startsWith("dsh_pair") }
                ?: resp.headers("set-cookie").firstOrNull()
            if (setCookie == null) {
                if (bodyDevice.isNotBlank()) {
                    // Some proxies strip Set-Cookie; the device id alone is a valid credential.
                    return PairResult("dsh_pair=$bodyDevice", "plugin", "dsh_pair", bodyDevice)
                }
                throw DshApiException("pair-no-cookie", "配对成功但响应中没有 cookie")
            }
            val name = setCookie.trim().substringBefore('=').trim()
            val value = setCookie.trim().substringAfter('=').substringBefore(';').trim()
            return PairResult("$name=$value", "plugin", name, bodyDevice)
        }
    }

    private fun launchToken(token: String): PairResult {
        val url = baseUrl() + "/?token=" + android.net.Uri.encode(token)
        val noRedirect = http.newBuilder().followRedirects(false).build()
        val req = Request.Builder().url(url).get().build()
        noRedirect.newCall(req).execute().use { resp ->
            val setCookie = resp.headers("set-cookie").firstOrNull { it.trimStart().startsWith("dsh-auth-") }
                ?: throw DshApiException(null, "启动链接配对失败：HTTP ${resp.code}（无 dsh-auth cookie）", resp.code)
            val name = setCookie.trim().substringBefore('=').trim()
            val value = setCookie.trim().substringAfter('=').substringBefore(';').trim()
            return PairResult(value, "core", name)
        }
    }

    /** Manual cookie paste: dsh_pair=… selects the plugin channel, otherwise core. */
    fun adoptManualCookie(cookie: String): PairResult {
        val c = cookie.trim()
        val name = if (c.contains('=')) c.substringBefore('=').trim() else ""
        val ch = if (name == "dsh_pair") "plugin" else "core"
        return PairResult(c, ch, name)
    }

    // ---------------- host RPC (both channels; plugin rides the /remote mirror) ----------------

    suspend fun rpc(endpoint: String, args: JsonObject = JsonObject(emptyMap())): JsonObject =
        withContext(Dispatchers.IO) {
            val url = baseUrl() + apiPrefix() + "/" + endpoint
            var methodUsed = endpoint
            var lastError: DshApiException? = null
            repeat(2) {
                val envelope = buildJsonObject {
                    put("type", "client-request")
                    put("rpcId", UUID.randomUUID().toString())
                    put("method", methodUsed)
                    put("payload", buildJsonObject { put("args", args) })
                }.toString()
                try {
                    return@withContext executeAndParse(url, envelope)
                } catch (e: DshApiException) {
                    val m = Regex("does not match endpoint \"([^\"]+)\"").find(e.message ?: "")
                    if (m == null || it == 1) throw e
                    methodUsed = m.groupValues[1]
                    lastError = e
                }
            }
            throw lastError ?: DshApiException(null, "rpc failed")
        }

    /**
     * Plain GET on the active channel prefix (0.3.12 plugin HTTP routes such as
     * dsh-session-archive inventory ride the same /remote gate as the RPC surface).
     * These routes answer raw JSON documents, not the SDK RPC envelope.
     */
    suspend fun getJson(path: String): JsonObject = withContext(Dispatchers.IO) {
        val url = baseUrl() + apiPrefix() + "/" + path.trimStart('/')
        val req = Request.Builder().url(url).channelAuth()
            .header("Accept", "application/json")
            .build()
        executePlain(url, req)
    }

    /** Plain POST with a JSON body on the active channel prefix; raw JSON response. */
    suspend fun postJson(path: String, body: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        val url = baseUrl() + apiPrefix() + "/" + path.trimStart('/')
        val req = Request.Builder().url(url).channelAuth()
            .header("Accept", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        executePlain(url, req)
    }

    private fun executePlain(url: String, req: Request): JsonObject {
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                throw DshApiException(null, describeHttpFailure(resp.code, text, url), resp.code)
            }
            val o = runCatching { DSH_JSON.parseToJsonElement(text).asObj() }.getOrNull()
                ?: throw DshApiException("bad-json", "unexpected response: ${text.take(200)}", resp.code)
            return o
        }
    }

    /** Human-readable HTTP failure: always names the URL; 404 carries a triage hint. */
    private fun describeHttpFailure(code: Int, text: String, url: String): String {
        val base = "HTTP $code: ${text.take(200)}（$url）"
        if (code == 404) {
            return base + "。404 通常表示：桌面端没有运行（隧道边缘直接回 404）、" +
                "隧道没有转发到 DSH 端口、或桌面端插件未加载；请先确认桌面端已启动"
        }
        return base
    }

    private fun executeAndParse(url: String, body: String): JsonObject {
        val req = Request.Builder()
            .url(url)
            .channelAuth()
            .header("Accept", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        return parseEnvelopeOrThrow(url, req)
    }

    /** Runs the call and unwraps the {result:{ok,value|error}} SDK envelope. */
    private fun parseEnvelopeOrThrow(url: String, req: Request): JsonObject {
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: throw DshApiException(null, "empty response (HTTP ${resp.code})", resp.code)
            if (resp.code == 401) {
                throw DshApiException("unauthorized", "鉴权失败（401）：cookie 无效或已撤销，请重新配对", resp.code)
            }
            if (resp.code == 403) {
                if (channel == "plugin") {
                    throw DshApiException(
                        "forbidden",
                        "设备未配对或配对已被桌面端撤销（远程访问被停止/设备被移除）。" +
                            "请在桌面端重新复制配对链接，并在有效期内重新连接",
                        resp.code,
                    )
                }
                throw DshApiException(
                    "forbidden",
                    "403：核心通道的主机门禁不放行该地址。公网/隧道场景请改用「配对链接」模式" +
                        "（?pair= 链接走插件通道）；或在桌面端把该地址加入 trustedHosts 后用 ?token= 链接配对",
                    resp.code,
                )
            }
            if (!resp.isSuccessful) {
                throw DshApiException(null, describeHttpFailure(resp.code, text, url), resp.code)
            }
            val root = runCatching { DSH_JSON.parseToJsonElement(text).asObj() }.getOrNull()
                ?: throw DshApiException("bad-json", "unexpected response: ${text.take(200)}", resp.code)
            val result = root.obj("result")
                ?: throw DshApiException("bad-json", "missing result: ${text.take(200)}", resp.code)
            if (result.bool("ok") != true) {
                val err = result.obj("error")
                throw DshApiException(err?.str("code"), err?.str("message") ?: "request failed", resp.code)
            }
            return result.obj("value") ?: JsonObject(emptyMap())
        }
    }

    // ---------------- realtime ----------------

    /** Open the live session stream over the gateway WebSocket mux (plugin channel rides /remote). */
    fun openFollow(
        sessionId: String,
        maxMessages: Int = 400,
        onFrame: (JsonObject) -> Unit,
        onTerminal: (Throwable?) -> Unit,
    ): WebSocket {
        val httpUrl = baseUrl().toHttpUrlOrNull()
            ?: throw DshApiException("bad-host", "无法解析主机地址：$host")
        // OkHttp 4 HttpUrl only accepts http/https — scheme("wss") throws
        // "unexpected scheme: wss". newWebSocket upgrades an https URL to wss
        // (or http to ws) by itself, so pass the http(s) URL as-is.
        val wsBuilder = httpUrl.newBuilder()
            .encodedPath(apiPrefix() + "/remote.mux")
            .query(null)
        // WS handshakes cannot carry custom headers on the Web API side, so the
        // plugin gate also accepts the device id as a query parameter; the
        // cookie stays primary and we send both.
        if (channel == "plugin" && deviceId.isNotBlank()) {
            wsBuilder.addQueryParameter("device", deviceId.trim())
        }
        val wsUrl = wsBuilder.build()
        val streamId = UUID.randomUUID().toString()
        val openMsg = buildJsonObject {
            put("type", "open")
            put("streamId", streamId)
            put("endpoint", "session/follow")
            put("payload", buildJsonObject {
                put("args", buildJsonObject {
                    put("request", buildJsonObject {
                        put("address", buildJsonObject {
                            put("kind", "session")
                            put("sessionId", sessionId)
                        })
                        put("maxMessages", maxMessages)
                    })
                })
            })
        }.toString()
        val request = Request.Builder()
            .url(wsUrl)
            .channelAuth()
            .build()
        return http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(openMsg)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val o = runCatching { DSH_JSON.parseToJsonElement(text).asObj() }.getOrNull() ?: return
                when (o.str("type")) {
                    "item" -> o["value"]?.asObj()?.let(onFrame)
                    "end" -> onTerminal(null)
                    "error" -> {
                        val err = o.obj("error")
                        onTerminal(DshApiException(err?.str("code"), err?.str("message") ?: "stream error"))
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code
                onTerminal(
                    if (code != null) {
                        DshApiException(
                            "ws-handshake",
                            "WebSocket 握手失败（HTTP $code）：${t.message ?: "连接中断"}（${wsUrl}）",
                            code,
                        )
                    } else t,
                )
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onTerminal(null)
            }
        })
    }

}
