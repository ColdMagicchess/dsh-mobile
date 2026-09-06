package com.example.DSH_Mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/**
 * 毛玻璃统一质感（规格：白色、25% 透明度）。

 * 两种形态、同一套视觉语言：
 * - 传入 haze（与 hazeSource 处于同一窗口的悬浮件：聊天页浮钮、抽屉）：
 *   真实背景模糊 + 白色 25% 着色（Haze，API31+ RenderEffect / API30 RenderScript）；
 * - haze 为 null（独立窗口 Popup/Dialog、平色页面上的按钮、源内容内部的组件）：
 *   白色 25% 着色。背景本就没有可模糊的细节，观感与模糊态一致。
 * 两者共享发丝描边 + 顶部内高光，保证全局同质。
 */
internal object Glass {
    /** 白色 25% 着色 */
    val Tint = Color(0x40FFFFFF)
    /** 发丝描边：比着色亮一档，勾出玻璃边缘 */
    val Border = Color(0x73FFFFFF)
    /** 顶部内高光（玻璃上缘反光），向中部衰减 */
    val HiTop = Color(0x2EFFFFFF)
    val HiMid = Color(0x0AFFFFFF)
    private val BlurRadius = 28.dp

    /** 供 hazeEffect 使用的样式：模糊底 + 白 25% tint */
    val Style = HazeStyle(
        backgroundColor = Flat.White,
        tints = listOf(HazeTint(Tint)),
        blurRadius = BlurRadius,
        noiseFactor = 0.02f,
    )
}

/**
 * 玻璃基础层。调用序：shadow → clip → glass → 其余（padding/clickable）。
 * 有 haze 时必须先 clip(shape)，模糊背景才会被裁进圆角。
 */
fun Modifier.glass(
    shape: Shape,
    haze: HazeState? = null,
    border: Boolean = true,
    highlight: Boolean = true,
): Modifier {
    var m = if (haze != null) this.hazeEffect(state = haze, style = Glass.Style)
            else this.background(Glass.Tint, shape)
    if (highlight) {
        m = m.background(
            Brush.verticalGradient(0f to Glass.HiTop, 0.45f to Glass.HiMid, 1f to Color.Transparent),
            shape,
        )
    }
    if (border) m = m.border(1.dp, Glass.Border, shape)
    return m
}
