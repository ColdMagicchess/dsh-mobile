package com.example.DSH_Mobile.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.text.Spannable
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.noties.markwon.Markwon
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.latex.CenteredInlineLatexSpan
import io.noties.markwon.ext.latex.JLatexAsyncDrawableSpan
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tables.TableRowSpan
import io.noties.markwon.image.AsyncDrawableSpan
import ru.noties.jlatexmath.JLatexMathDrawable
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
import kotlinx.coroutines.delay
import java.lang.reflect.Field
import java.lang.reflect.Method
import kotlin.math.ceil

private const val TAG = "DshLatex"

/** 公式全屏查看器的载荷：drawable 副本 + 原始尺寸（px）。 */
private data class LatexViewerPayload(val drawable: Drawable, val w: Int, val h: Int)

/** 查看器里公式的放大倍率。 */
private const val VIEWER_SCALE = 1.6f

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
    BoxWithConstraints(modifier) {
        // TextView 一律 fillMaxWidth：宽度是 EXACT 约束、组合期即知，不再依赖
        // tv.width——消息条目滚出视口再滚回来时 TextView 重建，首帧 tv.width
        // 为 0，播种会被跳过，塌缩帧回归且条目高度在滚动中变化把视口弹走。
        // TextView 水平 padding 为 0，文本布局宽 = 视图宽 = maxWidth。
        // constraints.maxWidth 即像素值（Constraints 以 px 计）
        val targetWidthPx = if (constraints.hasBoundedWidth) constraints.maxWidth else 0
        // 点按公式 → 全屏查看器（放大的可滚动视图）
        var viewer by remember { mutableStateOf<LatexViewerPayload?>(null) }
        val onFormulaTap: (Drawable, Int, Int) -> Unit = { d, w, h -> viewer = LatexViewerPayload(d, w, h) }
        AndroidView(
            modifier = Modifier.fillMaxWidth(),
            factory = { ctx ->
                TextView(ctx).apply {
                    movementMethod = LinkMovementMethod.getInstance()
                    textSize = 16f
                    // Kimi 风格行距：字号 16sp × 默认字体行高 ≈ 1.17 → 倍率 1.4
                    // 约合 1.63×字号，比默认 1.15 明显疏朗；打字机与成稿共用此视图。
                    setLineSpacing(0f, 1.4f)
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
                stabilizeTables(tv, targetWidthPx)
                stabilizeLatex(tv)
                centerInlineLatex(tv, targetWidthPx, onFormulaTap)
                clickableBlockLatex(tv, onFormulaTap)
            },
        )

        // 公式全屏查看器：1.6 倍放大 + 双向滚动（窄屏看宽公式的后半部分），
        // 弹出带淡入+上浮动画，避免突兀
        viewer?.let { p ->
            Dialog(onDismissRequest = { viewer = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                val appear = remember(p) { Animatable(0f) }
                LaunchedEffect(p) {
                    appear.animateTo(1f, tween(durationMillis = 200, easing = FastOutSlowInEasing))
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            alpha = appear.value
                            val s = 0.88f + 0.12f * appear.value
                            scaleX = s
                            scaleY = s
                            transformOrigin = TransformOrigin(0.5f, 0f)
                        }
                        .background(androidx.compose.ui.graphics.Color(0xFFF7F5EF))
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("公式 · 可滑动查看，点空白处关闭", fontSize = 12.sp, color = Flat.Muted)
                        Text("关 闭", fontSize = 13.sp, color = Flat.Accent,
                            modifier = Modifier.clickable { viewer = null })
                    }
                    val vScroll = rememberScrollState()
                    val hScroll = rememberScrollState()
                    Row(Modifier.verticalScroll(vScroll)) {
                        Row(Modifier.horizontalScroll(hScroll)) {
                            AndroidView(
                                modifier = Modifier.padding(16.dp),
                                factory = { ctx ->
                                    object : View(ctx) {
                                        var p: LatexViewerPayload? = null
                                        override fun onMeasure(w: Int, h: Int) {
                                            val pp = p
                                            setMeasuredDimension(
                                                ((pp?.w ?: 1) * VIEWER_SCALE).toInt().coerceAtLeast(1),
                                                ((pp?.h ?: 1) * VIEWER_SCALE).toInt().coerceAtLeast(1),
                                            )
                                        }
                                        override fun onDraw(canvas: Canvas) {
                                            super.onDraw(canvas)
                                            val pp = p ?: return
                                            val save = canvas.save()
                                            try {
                                                canvas.scale(VIEWER_SCALE, VIEWER_SCALE)
                                                pp.drawable.draw(canvas)
                                            } finally {
                                                canvas.restoreToCount(save)
                                            }
                                        }
                                    }
                                },
                                update = { v -> v.p = p; v.invalidate() },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun buildMarkwon(context: Context): Markwon =
    Markwon.builder(context)
        .usePlugin(MarkwonInlineParserPlugin.create())
        .usePlugin(CodeHighlightPlugin())
        .usePlugin(
            JLatexMathPlugin.builder(16f * context.resources.displayMetrics.scaledDensity)
                .inlinesEnabled(true)
                .build()
                .let { config -> JLatexMathPlugin.create(config) },
        )
        .usePlugin(TablePlugin.create(context))
        .build()

/** 模型常用 \\[...\\] 与 \\(…\\) 定界符；Markwon 的 LaTeX 插件只认 $ 定界，先归一化。 */
private val DISPLAY_MATH = Regex("\\$\\$([\\s\\S]+?)\\\$\\$")
private val INLINE_DOLLAR = Regex("(?<!\\$)\\$(?!\\$)((?:\\\\.|[^\$\\\\])+?)\\$(?!\\$)")

/** JLaTeXMath 不支持的 AMS 定界宏 → 等价写法（不替换则整条公式渲染失败）。 */
private val UNSUPPORTED_LATEX_MACROS = listOf(
    "\\lvert" to "|",
    "\\rvert" to "|",
    "\\lVert" to "\\Vert",
    "\\rVert" to "\\Vert",
)

// JLaTeXMath 不支持 \tag / \tag* / \notag / \nonumber（方程编号宏）。\tag 一旦出现，
// TeXFormula 直接抛 ParseException，整条公式渲染失败。这里把 \tag{N} 替换成可见的
// “\qquad(N)”编号（在式子尾部显示，用于证明里相互引用结论）；\notag/\nonumber 删掉（不显示编号）。
private val TAG_MACRO = Regex("""\\tag\*?\{([^{}]*)\}""")
private val NOLINE_MACRO = Regex("""\\notag\b|\\nonumber\b""")

/** 仅作用于公式片段（避免误伤代码块里的文本）。 */
private fun cleanMath(latex: String): String {
    var s = latex.replace(TAG_MACRO) { m ->
        val c = m.groupValues[1]
        val label = if (c.startsWith("(") && c.endsWith(")")) c else "(" + c + ")"
        "\\qquad" + label
    }
    return s.replace(NOLINE_MACRO, "")
}

private fun normalizeMath(src: String): String {
    // 先替换不支持的宏（\lvert 等在 JLaTeXMath 中不存在），再做定界符归一化
    var s = src
    for ((from, to) in UNSUPPORTED_LATEX_MACROS) s = s.replace(from, to)
    val blocks = mutableListOf<String>()
    val kept = s.replace(DISPLAY_MATH) { m ->
        blocks += cleanMath(m.value)
        "\u0000B${blocks.size - 1}\u0000"
    }
    val converted = kept.replace(INLINE_DOLLAR) { m ->
        "\$\$" + cleanMath(m.groupValues[1]) + "\$\$"
    }
    if (blocks.isEmpty()) return converted
    return converted.replace(Regex("\u0000B(\\d+)\u0000")) { m -> blocks[m.groupValues[1].toInt()] }
}

/**
 * 表格流式稳定化（与 LaTeX 抽搐同源，但无法用 drawable 缓存根治）：
 * TablePlugin 的 TableRowSpan 每次重建 span 都要经历"零宽首帧（getSize 返回
 * 初始 width=0）→ draw 里用画布宽重建 StaticLayout → height != maxHeight 时
 * invalidator.invalidate() → 二次布局撑开"。打字机每 45ms 全文重解析，表格
 * 区域每秒塌缩-撑开二十余次，表现为剧烈抽搐。
 * 解法：流式期间把 GFM 分隔行（|:---|---:| 形态）打上零宽前缀，使表格保持
 * 普通段落形态、不进 TablePlugin；消息完成（pending=false）后按原文一次性
 * 渲染成真表格。代价：生成中的表格以竖线文本形态显示，完成后"绽放"为表格。
 */
private val TABLE_DELIMITER_ROW = Regex(
    """^[ \t]{0,3}(?=.*\|)\|?[ \t]*:?-+:?[ \t]*(?:\|[ \t]*:?-+:?[ \t]*)*\|?[ \t]*\r?$""",
    RegexOption.MULTILINE,
)

/** 流式渲染前调用：仅给分隔行加零宽前缀，使未完成的表格不按真表格布局。 */
fun neutralizeTablesForStreaming(src: String): String =
    if (src.contains('|')) src.replace(TABLE_DELIMITER_ROW) { "\u200B" + it.value } else src

/** 已完成的表格行（表头/分隔行/正文行都算：行首竖线 + 行尾竖线）。 */
private val TABLE_ROW_LINE = Regex(
    """^[ \t]{0,3}\|.*\|[ \t]*\r?$""",
    RegexOption.MULTILINE,
)

/** 一次流式分段的划分结果。 */
data class StreamTableSplit(val stablePrefix: String, val liveTail: String)

/**
 * 流式渐进渲染的分段点：把「最后一个已完成表格行」之前的内容划为稳定前缀。
 * stablePrefix 在下一个表格行完成前字符串不变 → Compose 跳过 MarkdownText
 * 重组、不 setMarkdown → 其中的 TableRowSpan 不重建，表格零闪烁地逐行生长；
 * liveTail（正在输入的行）走打字机 + 中和。围栏代码块内的竖线行不算表格行，
 * 且开着的围栏整体留在尾部（避免把代码块劈成两半）。没有任何已完成行时
 * 返回 null：整条内容走打字机 + 中和路径。
 */
fun splitAtLastTableRow(src: String): StreamTableSplit? {
    if (!src.contains('|')) return null
    val lines = src.split("\n")
    var inFence = false
    var lastRow = -1
    for (i in lines.indices) {
        val line = lines[i]
        if (line.trimStart().startsWith("```")) {
            // 只有已完成的围栏行才翻转开关；末尾未输入完的行不改变状态
            if (i < lines.size - 1) inFence = !inFence
            continue
        }
        // 只把「后面还有换行」的行视为已完成（最后一行可能输入到一半）
        if (!inFence && i < lines.size - 1 && TABLE_ROW_LINE.matches(line)) lastRow = i
    }
    if (lastRow < 0) return null
    val prefix = lines.subList(0, lastRow + 1).joinToString("\n") + "\n"
    val tail = lines.subList(lastRow + 1, lines.size).joinToString("\n")
    return StreamTableSplit(prefix, tail)
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

// ---------- 行内公式垂直居中（修复下沉） ----------
// Markwon 的 JLatexInlineAsyncDrawableSpan 把公式图盒子中心对到**文字基线**上
// （getSize 报 fm.ascent=-h/2、fm.descent=+h/2，父类 ALIGN_CENTER 再按此画），
// 而公式（f、下标等）自带下降部 → 可见字形明显低于周围文字。这里在
// setMarkdown 后把行内 span 换成 CenteredInlineLatexSpan（见
// io.noties.markwon.ext.latex 包内——上游类是包私有，只能同包继承）：只改
// getSize 的 metrics 报告，以文字自身视觉中心 ((ascent+descent)/2) 为轴；
// 父类 ALIGN_CENTER 的 draw 把 drawable 中心放到行中心，行内占优时行中心即
// 文字中心。颜色传播（draw 里 icon.setForeground(paint.color)）由上游逻辑
// 继承，暗色模式公式仍跟随文字色。块级公式（独占一行）本来就是行中心对齐，
// 不动。公式 LRU 缓存与异步渲染基于同一 drawable 实例，不受影响。

private const val INLINE_LATEX_SPAN_CLASS = "io.noties.markwon.ext.latex.JLatexInlineAsyncDrawableSpan"

@Volatile private var centeredLatexTheme: MarkwonTheme? = null

private fun latexSpanTheme(tv: TextView): MarkwonTheme =
    centeredLatexTheme ?: MarkwonTheme.create(tv.context).also { centeredLatexTheme = it }

/** setMarkdown 之后调用：把行内 LaTeX span 换成基于文字视觉中心对齐的版本，并挂点按查看。 */
private fun centerInlineLatex(tv: TextView, availableWidth: Int, onTap: (Drawable, Int, Int) -> Unit) {
    val spanned = tv.text as? Spannable ?: run {
        Log.d(TAG, "centerInlineLatex: tv.text is not Spannable (${tv.text?.javaClass?.name})")
        return
    }
    val all = spanned.getSpans(0, spanned.length, AsyncDrawableSpan::class.java)
    var replaced = 0
    for (span in all) {
        if (span is CenteredInlineLatexSpan) continue
        // 只替换行内 span；块级（JLatexAsyncDrawableSpan 本体）保持原样
        if (span.javaClass.name != INLINE_LATEX_SPAN_CLASS) continue
        val origin = span as? JLatexAsyncDrawableSpan ?: continue
        val start = spanned.getSpanStart(span)
        val end = spanned.getSpanEnd(span)
        if (start < 0 || end <= start) continue
        val flags = spanned.getSpanFlags(span)
        val replacement = CenteredInlineLatexSpan.from(origin, latexSpanTheme(tv), availableWidth)
        spanned.removeSpan(span)
        spanned.setSpan(replacement, start, end, flags)
        attachFormulaTap(spanned, origin, start, end, onTap)
        replaced++
    }
    if (replaced > 0) Log.d(TAG, "centerInlineLatex: replaced=$replaced")
}

/** 块级公式（JLatexAsyncDrawableSpan 本体）：保持原渲染，仅挂点按查看。 */
private fun clickableBlockLatex(tv: TextView, onTap: (Drawable, Int, Int) -> Unit) {
    val spanned = tv.text as? Spannable ?: return
    for (span in spanned.getSpans(0, spanned.length, JLatexAsyncDrawableSpan::class.java)) {
        if (span.javaClass.name != "io.noties.markwon.ext.latex.JLatexAsyncDrawableSpan") continue
        val start = spanned.getSpanStart(span)
        val end = spanned.getSpanEnd(span)
        if (start < 0 || end <= start) continue
        attachFormulaTap(spanned, span, start, end, onTap)
    }
}

/** 在公式范围内挂 ClickableSpan（点按 → 全屏查看器）。 */
private fun attachFormulaTap(
    spanned: Spannable,
    origin: JLatexAsyncDrawableSpan,
    start: Int,
    end: Int,
    onTap: (Drawable, Int, Int) -> Unit,
) {
    // 直接使用已渲染的 drawable 实例（JLatexMathDrawable 不实现 ConstantState，
    // 无法复制；查看器里只做只读绘制，共用实例安全）。
    val result = origin.getDrawable().result ?: return
    val w = result.bounds.right.coerceAtLeast(1)
    val h = result.bounds.bottom.coerceAtLeast(1)
    spanned.setSpan(
        object : ClickableSpan() {
            override fun onClick(widget: View) = onTap(result, w, h)
            override fun updateDrawState(ds: TextPaint) = Unit
        },
        start,
        end,
        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
    )
}

// ---------- 表格 span 预播种（消除塌缩帧及其引发的滚动跳变） ----------
// TableRowSpan 首次布局前 width=0（getSize 直接返回该字段）→ 首帧整表塌缩，
// draw 后 invalidator.invalidate() 二次布局才撑开。稳定前缀每次 setMarkdown
// 都重建全部 span，塌缩-撑开让消息条目高度抖动一个表高：LazyColumn 滚动锚点
// 失步后 nearBottom 变 false、跟随钉底停用——视口停在表格区域（"自动跳到
// 表格开头"）。这里在 setMarkdown 之后、下一轮布局之前反射写入真实文本宽度
// 并提前构建 StaticLayout，使首次布局即得正确尺寸、彻底消除塌缩帧。
// Markwon 已归档冻结（4.6.2 字段结构稳定）；任何反射异常都降级回两遍布局。
private val tableRowWidthField: Field? = runCatching {
    TableRowSpan::class.java.getDeclaredField("width").apply { isAccessible = true }
}.getOrNull()
private val tableRowTextPaintField: Field? = runCatching {
    TableRowSpan::class.java.getDeclaredField("textPaint").apply { isAccessible = true }
}.getOrNull()
private val tableRowMakeLayouts: Method? = runCatching {
    TableRowSpan::class.java.getDeclaredMethod("makeNewLayouts").apply { isAccessible = true }
}.getOrNull()

/** setMarkdown 之后、下一轮布局之前调用：给每个新表格 span 预播种尺寸。 */
private fun stabilizeTables(tv: TextView, targetWidthPx: Int) {
    val widthField = tableRowWidthField ?: return
    val paintField = tableRowTextPaintField ?: return
    val makeLayouts = tableRowMakeLayouts ?: return
    val spanned = tv.text as? Spanned ?: return
    val target = targetWidthPx
    if (target <= 0) return // 宽度约束未知（理论上不发生）：交给 draw 的两遍布局兜底
    for (span in spanned.getSpans(0, spanned.length, TableRowSpan::class.java)) {
        runCatching {
            if (widthField.getInt(span) == target) return@runCatching // 已播种/已绘制
            paintField.set(span, TextPaint(tv.paint))
            widthField.setInt(span, target)
            makeLayouts.invoke(span)
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
    resetKey: Any? = null,
) {
    // resetKey 变化（尾部内容被并入稳定前缀、字符串整体更换）时以当前全文
    // 重置 displayLen：已显示的字符不重放，新字符继续按 45ms 步进。
    // LaunchedEffect 必须同时以 resetKey 为 key：状态对象随 key 重建，若
    // 效果不重启，仍在运行的旧协程捕获的是旧 State——步进写进无人读取的
    // 旧对象、UI 读到的新对象永远停在重置值 → 尾部失去打字机、整段跳变。
    var displayLen by remember(resetKey) { mutableIntStateOf(raw.length) }
    val currentRaw by rememberUpdatedState(raw)
    LaunchedEffect(pending, resetKey) {
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
