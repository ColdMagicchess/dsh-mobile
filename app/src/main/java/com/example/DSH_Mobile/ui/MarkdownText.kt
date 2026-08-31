package com.example.DSH_Mobile.ui

import android.content.Context
import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.Markwon
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
import kotlinx.coroutines.delay
import kotlin.math.ceil

/**
 * Markdown rendering via Markwon with JLaTeXMath formulas ($...$ and $$...$$).
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color(0xFF0D0C22),
) {
    val context = LocalContext.current
    val markwon = remember(context) { buildMarkwon(context) }
    val resolved = LocalContentColor.current
    val textColor = if (color != androidx.compose.ui.graphics.Color.Unspecified) {
        color
    } else if (resolved != androidx.compose.ui.graphics.Color.Unspecified) {
        resolved
    } else {
        androidx.compose.ui.graphics.Color(0xFF0D0C22)
    }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextView(ctx).apply {
                movementMethod = LinkMovementMethod.getInstance()
                textSize = 16f
                setLineSpacing(0f, 1.15f)
                setPadding(0, 2, 0, 2)
            }
        },
        update = { tv ->
            tv.setTextColor(textColor.toArgb())
            markwon.setMarkdown(tv, normalizeMath(markdown))
        },
    )
}

private fun buildMarkwon(context: Context): Markwon =
    Markwon.builder(context)
        .usePlugin(MarkwonInlineParserPlugin.create())
        .usePlugin(
            JLatexMathPlugin.create(
                JLatexMathPlugin.builder(16f * context.resources.displayMetrics.scaledDensity)
                    .inlinesEnabled(true)
                    .build(),
            ),
        )
        .build()

/** 保护已成对的 $$ 块，再把单个 $...$ 归一为 $$...$$（插件行内解析只认 $$）。 */
private val DISPLAY_MATH = Regex("\\$\\$([\\s\\S]+?)\\$\\$")
private val INLINE_DOLLAR = Regex("(?<!\\$)\\$(?!\\$)((?:\\\\.|[^$\\\\])+?)\\$(?!\\$)")

private fun normalizeMath(src: String): String {
    val blocks = mutableListOf<String>()
    val kept = src.replace(DISPLAY_MATH) { m ->
        blocks += m.value
        "\u0000B${blocks.size - 1}\u0000"
    }
    val converted = kept.replace(INLINE_DOLLAR) { m ->
        "\$\$" + m.groupValues[1] + "\$\$"
    }
    if (blocks.isEmpty()) return converted
    return converted.replace(Regex("\u0000B(\\d+)\u0000")) { m -> blocks[m.groupValues[1].toInt()] }
}

/**
 * Typewriter display for in-progress assistant messages (README F5):
 * every 45ms advance by max(1, min(9, ceil(remaining/12))) characters.
 *
 * 滚动回收友好：displayLen 初值取当前已缓冲全文。LazyColumn 释放滑出视口
 * 的消息后，滑回来是"重新进入组合"——旧实现从 0 重放整段打字机（表现为
 * 整条回复重新渲染一遍）；现在重新进入时直接显示已缓冲内容，只有之后新
 * 到的增量继续按打字机节奏出现。效果协程只以 pending 为 key（用
 * rememberUpdatedState 读取最新 raw），chunk 到达不会反复重启协程。
 */
@Composable
fun TypewriterMarkdown(
    raw: String,
    pending: Boolean,
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color(0xFF0D0C22),
) {
    var displayLen by remember { mutableIntStateOf(raw.length) }
    val currentRaw by rememberUpdatedState(raw)
    LaunchedEffect(pending) {
        if (!pending) {
            displayLen = currentRaw.length
            return@LaunchedEffect
        }
        while (true) {
            if (displayLen < currentRaw.length) {
                val gap = currentRaw.length - displayLen
                displayLen += maxOf(1, minOf(9, ceil(gap / 12.0).toInt()))
                delay(45)
            } else {
                delay(60)
            }
        }
    }
    val shown = if (displayLen >= currentRaw.length) currentRaw else currentRaw.take(displayLen)
    MarkdownText(shown, modifier, color)
}
