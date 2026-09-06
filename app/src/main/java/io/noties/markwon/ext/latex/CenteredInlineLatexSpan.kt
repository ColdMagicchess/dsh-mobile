package io.noties.markwon.ext.latex

import android.graphics.Canvas
import android.graphics.Paint
import io.noties.markwon.core.MarkwonTheme
import ru.noties.jlatexmath.JLatexMathDrawable
import ru.noties.jlatexmath.awt.Color as AwtColor

/**
 * 行内 LaTeX 公式的垂直居中 + 超宽缩放版本。
 *
 * 1. 修复"公式下沉"：上游 [JLatexInlineAsyncDrawableSpan] 的 getSize 把公式
 *    盒子中心对到**文字基线**；且父链 ALIGN_CENTER 的 draw 以「行盒中心」为
 *    轴——行距倍率会把行盒底部拉长，行中心下坠。本类 getSize 以文字视觉中心
 *    ((ascent+descent)/2) 为轴报告 metrics，draw 显式平移把盒子中心钉在文字
 *    视觉中心（不受行距影响）。
 * 2. 修复"超宽裁切"：行内公式没有块级那条缩放到画布宽的逻辑，比屏宽宽的
 *    公式会被 TextView 直接裁掉。本类按 [availableWidth] 等比缩小（draw 里
 *    canvas.scale，JLatexMathDrawable 自身只缩不放），保证完整可见。
 *
 * 颜色传播（icon.setForeground(paint.color)，暗色模式公式跟随文字颜色）从
 * 上游 draw 复制保留。上游类是包私有，本类留在 io.noties.markwon.ext.latex
 * 包内且为 internal（Markwon 4.6.2 已归档冻结，字段结构稳定）。
 */
internal class CenteredInlineLatexSpan private constructor(
    theme: MarkwonTheme,
    d: JLatextAsyncDrawable,
    color: Int,
    private val availableWidth: Int,
) : JLatexInlineAsyncDrawableSpan(theme, d, color) {

    private var appliedTextColor = color != 0

    override fun getSize(
        paint: Paint,
        text: CharSequence,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?,
    ): Int {
        val d = drawable
        if (!d.hasResult()) return super.getSize(paint, text, start, end, fm)
        val rect = d.bounds
        val scale = scaleOf(rect.right)
        val w = (rect.right * scale).toInt().coerceAtLeast(1)
        val h = (rect.bottom * scale).toInt().coerceAtLeast(1)
        if (fm != null) {
            val tfm = paint.fontMetricsInt
            val center = (tfm.ascent + tfm.descent) / 2
            fm.ascent = minOf(tfm.ascent, center - h / 2)
            fm.descent = maxOf(tfm.descent, center + h / 2)
            fm.top = fm.ascent
            fm.bottom = fm.descent
        }
        return w
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint,
    ) {
        val d = drawable
        if (!d.hasResult()) {
            super.draw(canvas, text, start, end, x, top, y, bottom, paint)
            return
        }
        // 上游的颜色传播：把当前文字颜色应用到 TeXIcon（文字色变化时重设）
        if (!appliedTextColor) {
            val result = d.result
            if (result is JLatexMathDrawable) {
                result.icon().setForeground(AwtColor(paint.color))
                appliedTextColor = true
            }
        }
        val rect = d.bounds
        val scale = scaleOf(rect.right)
        val tfm = paint.fontMetricsInt
        val textCenter = y + (tfm.ascent + tfm.descent) / 2f
        val drawH = rect.bottom * scale
        val save = canvas.save()
        try {
            canvas.translate(x, textCenter - drawH / 2f)
            if (scale != 1f) canvas.scale(scale, scale)
            d.draw(canvas)
        } finally {
            canvas.restoreToCount(save)
        }
    }

    /** 超出可用宽度时等比缩小（JLatexMathDrawable.draw 内部会按 bounds 缩放）。 */
    private fun scaleOf(intrinsicWidth: Int): Float {
        if (availableWidth <= 0 || intrinsicWidth <= availableWidth) return 1f
        return availableWidth.toFloat() / intrinsicWidth
    }

    companion object {
        /** 从原始行内 span 派生（同 drawable，异步渲染与缓存机制不变）。 */
        @JvmStatic
        fun from(
            origin: JLatexAsyncDrawableSpan,
            theme: MarkwonTheme,
            availableWidth: Int,
        ): CenteredInlineLatexSpan {
            return CenteredInlineLatexSpan(theme, origin.drawable(), origin.color(), availableWidth)
        }
    }
}
