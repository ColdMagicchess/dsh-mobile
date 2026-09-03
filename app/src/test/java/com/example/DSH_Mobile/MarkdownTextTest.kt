package com.example.DSH_Mobile

import com.example.DSH_Mobile.ui.neutralizeTablesForStreaming
import com.example.DSH_Mobile.ui.splitAtLastTableRow
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.parser.Parser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 表格流式稳定化（MarkdownText.neutralizeTablesForStreaming）的行为测试：
 * 只给 GFM 分隔行打零宽前缀，使流式中的表格不进 TablePlugin（TableRowSpan
 * 每次重建都两遍布局，45ms 打字机下剧烈抽搐）。
 */
class MarkdownTextTest {

    private fun parser(): Parser =
        Parser.builder().extensions(listOf(TablesExtension.create())).build()

    @Test
    fun delimiterRowsGetZeroWidthPrefix_headerAndBodyRowsUntouched() {
        val src = "| 场景 | 单元格 |\n|:---|---:|\n| a | b |"
        val lines = neutralizeTablesForStreaming(src).split("\n")
        assertFalse(lines[0].startsWith("\u200B"))
        assertTrue(lines[1].startsWith("\u200B"))
        assertFalse(lines[2].startsWith("\u200B"))
    }

    @Test
    fun delimiterWithoutEdgePipesStillMatched() {
        val out = neutralizeTablesForStreaming("a | b\n--- | ---\nc | d")
        assertTrue(out.split("\n")[1].startsWith("\u200B"))
    }

    @Test
    fun singleDashCellsMatched() {
        val out = neutralizeTablesForStreaming("|h1|h2|\n|-|-|\n|a|b|")
        assertTrue(out.split("\n")[1].startsWith("\u200B"))
    }

    @Test
    fun thematicBreakAndSetextDashLinesUntouched() {
        val src = "标题\n---\n\n***\n\n- 列表\n"
        assertEquals(src, neutralizeTablesForStreaming(src))
    }

    @Test
    fun proseContainingPipeUntouched() {
        assertEquals("a | b", neutralizeTablesForStreaming("a | b"))
    }

    @Test
    fun neutralizedTableNoLongerParsesAsTableBlock() {
        val src = "| 场景 | 单元格 | 涂染 |\n|:---:|:---:|:---:|\n| 行列式 | 公式 | ok |\n"
        val p = parser()
        assertTrue(p.parse(src).firstChild is TableBlock)
        val out = neutralizeTablesForStreaming(src)
        assertFalse(p.parse(out).firstChild is TableBlock)
    }

    @Test
    fun mixedProseAndTable_neutralizesExactlyOneDelimiterRow() {
        val src = "前言\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\n后记"
        val out = neutralizeTablesForStreaming(src)
        assertEquals(1, out.count { it == '\u200B' })
        // 表格区域之外的内容保持原样
        assertTrue(out.startsWith("前言\n\n| a | b |\n"))
        assertTrue(out.endsWith("\n\n后记"))
    }

    @Test
    fun textWithoutPipeIsFastPathNoOp() {
        val src = "完全没有竖线的普通文本\n第二行"
        assertEquals(src, neutralizeTablesForStreaming(src))
    }

    // ---------- splitAtLastTableRow：流式渐进分段 ----------

    @Test
    fun splitReturnsNullBeforeAnyRowCompletes() {
        // 表头还没输入完
        assertEquals(null, splitAtLastTableRow("前言\n\n| h |"))
        // 纯文本带竖线也不分段
        assertEquals(null, splitAtLastTableRow("a | b\nc | d"))
    }

    @Test
    fun splitPlacesHeaderInPrefix_partialDelimiterInTail() {
        val split = splitAtLastTableRow("前言\n\n| a | b |\n|:--")!!
        assertEquals("前言\n\n| a | b |\n", split.stablePrefix)
        assertEquals("|:--", split.liveTail)
    }

    @Test
    fun splitPlacesCompletedRowsInPrefix_partialRowInTail() {
        val split = splitAtLastTableRow("前言\n\n| a | b |\n|---|---|\n| 1 | 2 |\n| 3 ")!!
        assertEquals("前言\n\n| a | b |\n|---|---|\n| 1 | 2 |\n", split.stablePrefix)
        assertEquals("| 3 ", split.liveTail)
    }

    @Test
    fun tailEmptyWhenBodyEndsWithNewlineAfterCompleteRow() {
        val split = splitAtLastTableRow("| a |\n|---|\n| 1 |\n")!!
        assertEquals("| a |\n|---|\n| 1 |\n", split.stablePrefix)
        assertEquals("", split.liveTail)
    }

    @Test
    fun proseAfterTableStaysInTail() {
        val split = splitAtLastTableRow("| a |\n|---|\n| 1 |\n\n说明文字")!!
        assertEquals("| a |\n|---|\n| 1 |\n", split.stablePrefix)
        assertEquals("\n说明文字", split.liveTail)
    }

    @Test
    fun prefixParsesAsRealTable_neutralizedTailDoesNot() {
        val split = splitAtLastTableRow("| a |\n|---|\n| 1 |\n| 2 ")!!
        val p = parser()
        assertTrue(p.parse(split.stablePrefix).firstChild is TableBlock)
        assertFalse(p.parse(neutralizeTablesForStreaming(split.liveTail)).firstChild is TableBlock)
    }

    @Test
    fun pipeLinesInsideFenceDoNotAdvanceBoundary() {
        assertEquals(null, splitAtLastTableRow("```\n| a | b |\n```\n| x "))
    }

    @Test
    fun openFenceStaysInTail() {
        val split = splitAtLastTableRow("| a |\n|---|\n| 1 |\n```text\n| pipe ")!!
        assertEquals("| a |\n|---|\n| 1 |\n", split.stablePrefix)
        assertEquals("```text\n| pipe ", split.liveTail)
    }
}
