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
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
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
 * Transport for the DSH host. Two channels:
 *  - "core":   POST /api/<ns>/<method> (payload wrapped in {args:{...}}) + WS /api/remote.mux;
 *  - "plugin": POST /m/api/<dot.method> (raw business payload) + SSE /m/api/events.mux
 *              (the dsh-remote-web-ui plugin BFF, authenticated by the dsh_pair cookie).
 */
class DshClient {

    @Volatile var host: String = ""
    @Volatile var cookieValue: String = ""
    @Volatile var cookieNameOverride: String = ""
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

    data class PairResult(val cookie: String, val channel: String, val cookieName: String)

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
                !resp.isSuccessful -> throw DshApiException(null, "配对失败：HTTP ${resp.code}", resp.code)
            }
            val setCookie = resp.headers("set-cookie").firstOrNull { it.trimStart().startsWith("dsh_pair") }
                ?: resp.headers("set-cookie").firstOrNull()
                ?: throw DshApiException("pair-no-cookie", "配对成功但响应中没有 cookie")
            val name = setCookie.trim().substringBefore('=').trim()
            val value = setCookie.trim().substringAfter('=').substringBefore(';').trim()
            return PairResult("$name=$value", "plugin", name)
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

    // ---------------- core channel RPC ----------------

    suspend fun rpc(endpoint: String, args: JsonObject = JsonObject(emptyMap())): JsonObject =
        withContext(Dispatchers.IO) {
            val url = baseUrl() + "/api/" + endpoint
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

    // ---------------- plugin BFF RPC ----------------

    suspend fun rpcBff(method: String, payload: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        val url = baseUrl() + "/m/api/" + method
        val envelope = buildJsonObject {
            put("type", "client-request")
            put("rpcId", UUID.randomUUID().toString())
            put("method", method)
            put("payload", payload)
        }.toString()
        executeAndParse(url, envelope)
    }

    private fun executeAndParse(url: String, body: String): JsonObject {
        val req = Request.Builder()
            .url(url)
            .header("Cookie", cookieHeader())
            .header("Accept", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: throw DshApiException(null, "empty response (HTTP ${resp.code})", resp.code)
            if (resp.code == 401) {
                throw DshApiException("unauthorized", "鉴权失败（401）：cookie 无效或已撤销，请重新配对", resp.code)
            }
            if (resp.code == 403) {
                if (channel == "plugin") {
                    throw DshApiException(
                        "forbidden",
                        "配对已被桌面端撤销（远程访问被停止或设备被移除）。请在桌面端重新复制配对链接，并在 10 分钟内粘贴连接",
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
                throw DshApiException(null, "HTTP ${resp.code}: ${text.take(200)}", resp.code)
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

    /** Core channel: open the live session stream over the gateway WebSocket mux. */
    fun openFollow(
        sessionId: String,
        maxMessages: Int = 400,
        onFrame: (JsonObject) -> Unit,
        onTerminal: (Throwable?) -> Unit,
    ): WebSocket {
        val httpUrl = baseUrl().toHttpUrlOrNull()
            ?: throw DshApiException("bad-host", "无法解析主机地址：$host")
        val wsUrl = httpUrl.newBuilder()
            .scheme(if (httpUrl.isHttps) "wss" else "ws")
            .encodedPath("/api/remote.mux")
            .query(null)
            .build()
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
            .header("Cookie", cookieHeader())
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
                onTerminal(t)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onTerminal(null)
            }
        })
    }

    class StreamHandle(private val cancelFn: () -> Unit) {
        fun cancel() = cancelFn()
    }

    /** Plugin channel: SSE event stream carrying session/event frames. */
    fun openBffEvents(
        onFrame: (JsonObject) -> Unit,
        onTerminal: (Throwable?) -> Unit,
    ): StreamHandle {
        val request = Request.Builder()
            .url(baseUrl() + "/m/api/events.mux")
            .header("Cookie", cookieHeader())
            .header("Accept", "text/event-stream")
            .build()
        val source = EventSources.createFactory(http).newEventSource(request, object : EventSourceListener() {
            override fun onEvent(es: EventSource, id: String?, type: String?, data: String) {
                val d = data.takeIf { it.isNotBlank() } ?: return
                val o = runCatching { DSH_JSON.parseToJsonElement(d).asObj() }.getOrNull() ?: return
                onFrame(o)
            }

            override fun onFailure(es: EventSource, t: Throwable?, response: Response?) {
                val code = response?.code
                onTerminal(t ?: RuntimeException("事件流中断（HTTP ${code ?: "?"}）"))
            }

            override fun onClosed(es: EventSource) {
                onTerminal(null)
            }
        })
        return StreamHandle { source.cancel() }
    }
}
