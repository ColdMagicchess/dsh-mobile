package com.example.DSH_Mobile.ui

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 抽屉粒子汇聚/消散引擎（particle-window.html 动效的 Android 移植）。
 *
 * - 汇聚（弹出）：粒子自左侧涌出、向右飞入像素原位，装配波从左扫到右；
 * - 消散（收起）：右缘先碎、波从右扫到左，粒子向左飘散（湍流抖动 + 上浮 + 闪烁渐隐）；
 * - 参数化动画（位置 = f(原位, 起始偏移, t)），中途反向只需把当前位置快照为新起点。
 *
 * 粒子颜色采样自抽屉面板录制位图（含毛玻璃背景），落在原位时与真面板逐像素吻合，
 * 动画结束瞬间切换真面板无跳变。
 */
internal class DrawerFx {

    var n = 0; private set
    var step = 8f; private set
    var wPx = 0; private set
    var hPx = 0; private set
    val tConverge = 1.2f
    val tDisperse = 1.2f

    private companion object { const val CAP = 14000; const val TARGET = 13000 }

    private val hx = FloatArray(CAP); private val hy = FloatArray(CAP)   // 原位（抽屉局部 px）
    private val col = IntArray(CAP)                                       // ARGB
    private val rn = FloatArray(CAP); private val sd = FloatArray(CAP)    // 随机数 / 种子
    private val dl = FloatArray(CAP); private val du = FloatArray(CAP)    // 延迟 / 寿命(行程时长)
    private val sx = FloatArray(CAP); private val sy = FloatArray(CAP)    // 汇聚起始偏移
    private val ox = FloatArray(CAP); private val oy = FloatArray(CAP)    // 消散起始偏移(反向快照)
    private val fl = FloatArray(CAP)                                      // 消散左飘行程
    private val pxy = FloatArray(2)

    /** 从录制位图采样粒子；网格步长自适应，粒子数封顶。返回 false 表示无有效像素。 */
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
        return n > 0
    }

    private fun easeOutCubic(p: Float) = 1f - (1f - p) * (1f - p) * (1f - p)

    /** 汇聚参数。fromCurrent：反向打断，以当前消散位置为起点（短错峰、快收束）。 */
    fun setupConverge(fromCurrent: Boolean, curT: Float) {
        for (i in 0 until n) {
            if (fromCurrent) {
                dispersePos(i, curT)
                sx[i] = pxy[0] - hx[i]; sy[i] = pxy[1] - hy[i]
                dl[i] = rn[i] * 0.16f
                du[i] = 0.4f + rn[i] * 0.2f
            } else {
                // 自左侧涌出：横向起点在原位左边，纵向小散布；延迟 ∝ x → 左→右装配波
                sx[i] = -(24f + rn[i] * 250f + (hx[i] / wPx) * 90f)
                sy[i] = (rn[i] - 0.5f) * 130f
                dl[i] = (hx[i] / wPx) * 0.40f + rn[i] * 0.10f
                du[i] = 0.42f + rn[i] * 0.24f
            }
        }
    }

    /** 消散参数。fromCurrent：反向打断，以当前汇聚位置为起点继续向左。 */
    fun setupDisperse(fromCurrent: Boolean, curT: Float) {
        for (i in 0 until n) {
            if (fromCurrent) {
                convPos(i, curT)
                ox[i] = pxy[0] - hx[i]; oy[i] = pxy[1] - hy[i]
                dl[i] = rn[i] * 0.14f
            } else {
                ox[i] = 0f; oy[i] = 0f
                // 延迟 ∝ 右缘距离 → 右边先碎，波向左扫
                dl[i] = ((wPx - hx[i]) / wPx) * 0.36f + rn[i] * 0.09f
            }
            du[i] = 0.5f + rn[i] * 0.22f
            fl[i] = 200f + rn[i] * 460f + (wPx - hx[i]) * 0.35f
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
        pxy[0] = hx[i] + ox[i] - fl[i] * e + sin(t * 5.5f + sd[i]) * 12f * k
        pxy[1] = hy[i] + oy[i] - (20f + rn[i] * 70f) * e + cos(t * 4.5f + sd[i] * 1.3f) * 10f * k
    }

    /** 每帧由 Canvas 调用：按参数式解算位置/透明度/尺寸并绘制方块粒子。 */
    fun draw(scope: DrawScope, converging: Boolean, t: Float) {
        for (i in 0 until n) {
            val x: Float; val y: Float; val a: Float; val sz: Float
            if (converging) {
                val p = (t - dl[i]) / du[i]
                if (p <= 0f) continue
                if (p >= 1f) {
                    x = hx[i]; y = hy[i]; a = 1f; sz = step
                } else {
                    val e = easeOutCubic(p)
                    x = hx[i] + sx[i] * (1f - e); y = hy[i] + sy[i] * (1f - e)
                    a = 0.45f + 0.55f * e; sz = step
                }
            } else {
                val age = t - dl[i]
                if (age <= 0f) {
                    x = hx[i] + ox[i]; y = hy[i] + oy[i]; a = 1f; sz = step
                } else {
                    val k = age / du[i]
                    if (k >= 1f) continue
                    val e = k * k
                    x = hx[i] + ox[i] - fl[i] * e + sin(t * 5.5f + sd[i]) * 12f * k
                    y = hy[i] + oy[i] - (20f + rn[i] * 70f) * e + cos(t * 4.5f + sd[i] * 1.3f) * 10f * k
                    a = (1f - k) * (0.72f + 0.28f * sin(t * 13f + sd[i] * 31f))
                    sz = step * (1f - 0.45f * k)
                }
            }
            val base = (col[i] ushr 24) / 255f
            val al = (a * base).coerceIn(0f, 1f)
            // 投影层：抽屉本体是白玻璃，白粒子在白背景上不可见；
            // 每粒下方先落一枚偏移柔影，飞行期间整片粒子有了浮起感。
            scope.drawRect(
                Color(0xFF2A2438),
                topLeft = Offset(x + 1.5f, y + 2.5f),
                size = Size(sz, sz),
                alpha = al * 0.16f,
            )
            scope.drawRect(
                Color(col[i]),
                topLeft = Offset(x, y),
                size = Size(sz, sz),
                alpha = al,
            )
        }
    }
}
