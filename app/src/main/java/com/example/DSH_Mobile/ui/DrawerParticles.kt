package com.example.DSH_Mobile.ui

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 抽屉粒子转场引擎 v2 —— 弹出整体聚合 + 收起三点侵蚀（鸿蒙 7 风格，快速、无整片马赛克）。
 *
 * 面板本体始终以清晰位图绘制（android.graphics 原生 saveLayer/PorterDuff，零版本依赖）：
 * - 汇聚（弹出）：仿鸿蒙 7 换入——面板整体一步到位（0.16s 快速渐显），
 *   粒子流自左侧飞入、按 x 坐标左→右波次掠过面板，sin 包络淡入淡出、融入原位；
 * - 消散（收起）：3 个随机种子点开洞向外侵蚀（DST_OUT 软边圆），洞缘释放的粒子
 *   沿**从右向左**方向飘散消隐；波前推进用 easeOutQuad（≥线性），恒不落后于粒子释放。
 *
 * 反向打断：面板直接退场，粒子快照当前位置续飞，不跳变。
 */
internal class DrawerFx {

    var n = 0; private set
    var step = 8f; private set
    var wPx = 0; private set
    var hPx = 0; private set
    val tConverge = 0.85f
    val tDisperse = 0.85f
    private val wave = 0.38f
    /** 弹出侧面板整体渐显时长 */
    private val reveal = 0.16f

    /** 面板清晰位图（软件 ARGB_8888，直接 drawBitmap） */
    private var panelBmp: Bitmap? = null
    /** 0=面板退场（反向打断） 1=整体渐显（弹出） 2=DST_OUT 三点侵蚀（收起） */
    var panelMode = 0; private set

    private companion object { const val CAP = 14000; const val TARGET = 13000 }

    private val hx = FloatArray(CAP); private val hy = FloatArray(CAP)
    private val col = IntArray(CAP)
    private val rn = FloatArray(CAP); private val sd = FloatArray(CAP)
    private val dl = FloatArray(CAP); private val du = FloatArray(CAP)
    private val sx = FloatArray(CAP); private val sy = FloatArray(CAP)   // 汇聚起始偏移
    private val ox = FloatArray(CAP); private val oy = FloatArray(CAP)   // 消散起始偏移
    private val fl = FloatArray(CAP)                                     // 左飘行程
    private val pxy = FloatArray(2)

    private val seedX = FloatArray(3); private val seedY = FloatArray(3)
    private var maxReach = 1f

    /** 采样位图 + 选 3 个侵蚀种子点。bmp 必须是软件位图（ARGB_8888）。 */
    fun build(bmp: Bitmap): Boolean {
        val w = bmp.width; val h = bmp.height
        if (w <= 0 || h <= 0) return false
        step = maxOf(6f, ceil(sqrt((w * h).toDouble() / TARGET)).toFloat())
        val si = step.toInt().coerceAtLeast(1)
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        n = 0
        var y = 0
        while (y < h && n < CAP) {
            var x = 0
            while (x < w && n < CAP) {
                val c = pixels[y * w + x]
                if ((c ushr 24) >= 26) {
                    val i = n++
                    hx[i] = x.toFloat(); hy[i] = y.toFloat(); col[i] = c
                    rn[i] = Random.nextFloat(); sd[i] = Random.nextFloat() * 100f
                    ox[i] = 0f; oy[i] = 0f
                }
                x += si
            }
            y += si
        }
        wPx = w; hPx = h
        if (n == 0) return false
        panelBmp = bmp
        for (k in 0 until 3) {
            seedX[k] = w * (0.12f + 0.76f * ((k + Random.nextFloat()) / 3f))
            seedY[k] = h * (0.12f + 0.76f * Random.nextFloat())
        }
        var far = 0f
        for (i in 0 until n) far = maxOf(far, nearestSeedDist(hx[i], hy[i]))
        maxReach = far * 1.12f + 40f
        return true
    }

    fun release() { panelBmp = null; panelMode = 0 }

    private fun nearestSeedDist(x: Float, y: Float): Float {
        var m = Float.MAX_VALUE
        for (k in 0 until 3) {
            val d = hypot(x - seedX[k], y - seedY[k])
            if (d < m) m = d
        }
        return m
    }

    private fun easeOutQuad(p: Float) = 1f - (1f - p) * (1f - p)
    private fun easeOutCubic(p: Float) = 1f - (1f - p) * (1f - p) * (1f - p)
    private fun seedDelay(i: Int) = (nearestSeedDist(hx[i], hy[i]) / maxReach) * wave + rn[i] * 0.05f

    /** 汇聚：面板整体快速聚合，粒子自左飞入、落位即融入（淡出）。 */
    fun setupConverge(fromCurrent: Boolean, curT: Float) {
        panelMode = if (fromCurrent) 0 else 1
        for (i in 0 until n) {
            if (fromCurrent) {
                dispersePos(i, curT)
                sx[i] = pxy[0] - hx[i]; sy[i] = pxy[1] - hy[i]
                dl[i] = rn[i] * 0.10f
                du[i] = 0.26f + rn[i] * 0.14f
            } else {
                sx[i] = -(40f + rn[i] * 260f)
                sy[i] = (rn[i] - 0.5f) * 90f
                dl[i] = (hx[i] / wPx) * 0.26f + rn[i] * 0.08f
                du[i] = 0.30f + rn[i] * 0.14f
            }
        }
    }

    /** 消散：粒子向左飘散，面板 3 点侵蚀。fromCurrent=反向打断（面板退场）。 */
    fun setupDisperse(fromCurrent: Boolean, curT: Float) {
        panelMode = if (fromCurrent) 0 else 2
        for (i in 0 until n) {
            if (fromCurrent) {
                convPos(i, curT)
                ox[i] = pxy[0] - hx[i]; oy[i] = pxy[1] - hy[i]
                dl[i] = rn[i] * 0.10f
            } else {
                ox[i] = 0f; oy[i] = 0f
                dl[i] = seedDelay(i)
            }
            du[i] = 0.26f + rn[i] * 0.12f
            fl[i] = 280f + rn[i] * 460f + (wPx - hx[i]) * 0.3f
        }
    }

    private fun convPos(i: Int, t: Float) {
        val p = ((t - dl[i]) / du[i]).coerceIn(0f, 1f)
        val e = easeOutCubic(p)
        pxy[0] = hx[i] + sx[i] * (1f - e); pxy[1] = hy[i] + sy[i] * (1f - e)
    }

    private fun dispersePos(i: Int, t: Float) {
        val age = t - dl[i]
        if (age <= 0f) { pxy[0] = hx[i] + ox[i]; pxy[1] = hy[i] + oy[i]; return }
        val k = (age / du[i]).coerceIn(0f, 1f)
        val e = k * k
        pxy[0] = hx[i] + ox[i] - fl[i] * e + sin(t * 6f + sd[i]) * 10f * k
        pxy[1] = hy[i] + oy[i] - (15f + rn[i] * 55f) * e + cos(t * 5f + sd[i] * 1.3f) * 8f * k
    }

    /** 复用 Paint，避免逐帧分配 */
    private val fadePaint = Paint().apply { isFilterBitmap = true }
    private val holePaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }

    /** 面板绘制：弹出=整体渐显；收起=三点 DST_OUT 侵蚀。原生 canvas，稳定可控。 */
    fun drawPanel(scope: DrawScope, converging: Boolean, t: Float) {
        val bmp = panelBmp ?: return
        if (panelMode == 0) return
        run {
            val canvas = scope.drawContext.canvas.nativeCanvas
            if (converging) {
                fadePaint.alpha = (min(t / reveal, 1f) * 255f).toInt()
                canvas.drawBitmap(bmp, 0f, 0f, fadePaint)
                return@run
            }
            val r = easeOutQuad(min(t / wave, 1f)) * maxReach
            val edge = r * 0.28f
            val save = canvas.saveLayer(0f, 0f, wPx.toFloat(), hPx.toFloat(), null)
            canvas.drawBitmap(bmp, 0f, 0f, null)
            if (r > 0.5f) {
                for (k in 0 until 3) {
                    holePaint.shader = RadialGradient(
                        seedX[k], seedY[k], r + edge,
                        intArrayOf(-0x1, -0x1, 0x0),
                        floatArrayOf(0f, 0.72f, 1f),
                        Shader.TileMode.CLAMP,
                    )
                    canvas.drawCircle(seedX[k], seedY[k], r + edge, holePaint)
                }
            }
            canvas.restoreToCount(save)
        }
    }

    /** 粒子层：仅在侵蚀边界/入射流中存在，带柔影（白玻璃粒子在浅背景上可见）。 */
    fun draw(scope: DrawScope, converging: Boolean, t: Float) {
        for (i in 0 until n) {
            val x: Float; val y: Float; val a: Float; val sz: Float
            if (converging) {
                val p = (t - dl[i]) / du[i]
                if (p <= 0f || p >= 1f) continue
                val e = easeOutCubic(p)
                x = hx[i] + sx[i] * (1f - e); y = hy[i] + sy[i] * (1f - e)
                a = sin((p * PI).toFloat()); sz = step
            } else {
                val age = t - dl[i]
                if (age <= 0f) continue
                val k = age / du[i]
                if (k >= 1f) continue
                val e = k * k
                x = hx[i] + ox[i] - fl[i] * e + sin(t * 6f + sd[i]) * 10f * k
                y = hy[i] + oy[i] - (15f + rn[i] * 55f) * e + cos(t * 5f + sd[i] * 1.3f) * 8f * k
                // 满铺尺寸 + 轻闪烁 + 快速渐隐：粒子带是连续"绸缎"而非离散方块
                a = (1f - k) * (0.88f + 0.12f * sin(t * 15f + sd[i] * 29f))
                sz = step
            }
            val base = (col[i] ushr 24) / 255f
            val al = (a * base).coerceIn(0f, 1f)
            if (al <= 0.02f) continue
            scope.drawRect(
                Color(0xFF2A2438),
                topLeft = Offset(x + 1.5f, y + 2.5f),
                size = Size(sz, sz),
                alpha = al * 0.15f,
            )
            scope.drawRect(Color(col[i]), topLeft = Offset(x, y), size = Size(sz, sz), alpha = al)
        }
    }
}
