package com.example.DSH_Mobile.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.text.Spanned
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
import io.noties.markwon.image.AsyncDrawableSpan
import ru.noties.jlatexmath.JLatexMathDrawable
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
            val normalized = normalizeMath(markdown)
            // 流式期间的抽搐根源：插件异步渲染每条公式前占位高度归零，打字机
            // 每步全文重解析 → 所有公式反复"归零→撑开"。此处按公式内容缓存
            // drawable，setMarkdown 后立即回填缓存结果 → 已见过的公式高度稳定。
            preRenderLatex(normalized, tv)
            markwon.setMarkdown(tv, normalized)
            stabilizeLatex(tv)
        },
    )
}

private fun buildMarkwon(context: Context): Markwon =
    Markwon.builder(context)
        .usePlugin(MarkwonInlineParserPlugin.create())
        .usePlugin(
            JLatexMathPlugin.builder(16f * context.resources.displayMetrics.scaledDensity)
                .inlinesEnabled(true)
                .build()
                .let { config -> JLatexMathPlugin.create(config) },
        )
        .build()

/** 模型常用 \\[...\\] 与 \\(…\\) 定界符；Markwon 的 LaTeX 插件只认 $ 定界，先归一化。 */
private val DISPLAY_MATH = Regex("\\$\\$([\\s\\S]+?)\\\$\\$")
private val INLINE_DOLLAR = Regex("(?<!\\$)\\$(?!\\$)((?:\\\\.|[^\$\\\\])+?)\\$(?!\\$)")

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
 * 公式 drawable 缓存（LRU，key = latex 内容）。命中即可同步回填占位，
 * 消除流式期间的高度反复；未命中的新公式由插件异步渲染一次。
 */
private val latexDrawableCache = object : LinkedHashMap<String, Drawable>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Drawable>): Boolean = size > 96
}

private val LATEX_FORMULA = Regex("\\$\\$([\\s\\S]+?)\\\$\\$")

/** 把 normalized 文本里的公式预渲染进缓存（仅未命中项，同步、毫秒级）。 */
private fun preRenderLatex(normalized: String, tv: TextView) {
    val textSize = 16f * tv.resources.displayMetrics.scaledDensity
    for (m in LATEX_FORMULA.findAll(normalized)) {
        val latex = m.groupValues[1].trim()
        if (latex.isEmpty() || latexDrawableCache.containsKey(latex)) continue
        runCatching {
            latexDrawableCache[latex] = JLatexMathDrawable.builder(latex)
                .textSize(textSize)
                .build()
        }
    }
}

/** setMarkdown 之后、布局之前调用：给每条公式回填缓存 drawable，消除占位塌缩。 */
private fun stabilizeLatex(tv: TextView) {
    val spanned = tv.text as? Spanned ?: return
    for (span in spanned.getSpans(0, spanned.length, AsyncDrawableSpan::class.java)) {
        val drawable = span.drawable ?: continue
        val cached = latexDrawableCache[drawable.destination] ?: continue
        drawable.setResult(cached.constantState?.newDrawable(tv.resources, null) ?: cached)
    }
}

/**
 * Typewriter display for in-progress assistant messages (README F5):
 * every 45ms advance by max(1, min(9, ceil(remaining/12))) characters.
 *
 * displayLen 初值取当前已缓冲全文：LazyColumn 释放滑出视口的消息后，滑回来
 * 是"重新进入组合"——从 0 起会重放整段打字机。效果协程只以 pending 为 key
 * （rememberUpdatedState 读最新 raw），chunk 到达不会反复重启协程。
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
