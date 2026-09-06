package com.example.DSH_Mobile.ui

import android.graphics.Color
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.syntax.SyntaxHighlight

/**
 * Accurate, per-language code syntax highlighter for Markwon.
 *
 * The bundled Markwon syntax-highlight module is hard-wired to Prism4j, whose
 * grammar set is produced by a codegen annotation processor (prism4j-bundler) —
 * not worth that build complexity for a client. Instead this implements Markwon's
 * own [SyntaxHighlight] interface with a curated, LANGUAGE-AWARE regex tokenizer:
 * each language gets its own keyword set, comment delimiters and literal set, so a
 * token is only colored when it really belongs to that language. No guessing that a
 * capitalized word is a type (the previous source of misleading highlights).
 *
 * Colors are tuned for the app's light chat surface (Flat.White background,
 * Flat.Ink text). Unknown languages fall back to a conservative minimal set so we
 * never color something we are unsure about.
 */
internal class SimpleSyntaxHighlight : SyntaxHighlight {

    override fun highlight(language: String?, code: String): CharSequence {
        val lang = normalizeLang(language)
        val key = lang + "\u0000" + code
        highlightCache[key]?.let { return it }
        val builder = SpannableStringBuilder(code)
        val master = masterRegex(lang)
        if (master != null) {
            for (m in master.findAll(builder)) {
                val color = when {
                    m.groups[1] != null -> COMMENT_COLOR
                    m.groups[2] != null -> STRING_COLOR
                    m.groups[3] != null -> NUMBER_COLOR
                    m.groups[4] != null -> LITERAL_COLOR
                    m.groups[5] != null -> KEYWORD_COLOR
                    else -> continue
                }
                builder.setSpan(ForegroundColorSpan(color), m.range.first, m.range.last + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        highlightCache[key] = builder
        return builder
    }

    // Small LRU so a completed message re-entering composition (scroll back /
    // forward) does not re-run the tokenizer; bounded to avoid unbounded growth.
    private val highlightCache = object : LinkedHashMap<String, CharSequence>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CharSequence>): Boolean = size > 256
    }

    private fun masterRegex(lang: String): Regex? {
        if (lang == "none") return null
        masterCache[lang]?.let { return it }
        val rule = RULES[lang] ?: GENERIC
        val comment = commentRegex(rule)
        val kw = if (rule.keywords.isEmpty()) "" else "(" + """\b(?:""" + rule.keywords.joinToString("|") + """)\b""" + ")"
        val lit = if (rule.literals.isEmpty()) "" else "(" + """\b(?:""" + rule.literals.joinToString("|") + """)\b""" + ")"
        val parts = listOf(comment, STRING, NUMBER, lit, kw).filter { it.isNotEmpty() }
        val re = if (parts.isEmpty()) null else Regex(parts.joinToString("|"))
        masterCache[lang] = re
        return re
    }

    private fun commentRegex(rule: LangRule): String {
        val line = rule.lineComment.map { Regex.escape(it) + LINE_TAIL }
        val block = rule.blockComment?.let { (open, close) -> Regex.escape(open) + BLOCK_MID + Regex.escape(close) }
        val all = line + listOfNotNull(block)
        return if (all.isEmpty()) "" else "(" + all.joinToString("|") + ")"
    }

    private val masterCache = HashMap<String, Regex?>()

    private companion object {
        val COMMENT_COLOR = Color.rgb(0x9E, 0x9E, 0xA7)
        val STRING_COLOR = Color.rgb(0x16, 0xA3, 0x4A)
        val NUMBER_COLOR = Color.rgb(0x15, 0x65, 0xC0)
        val LITERAL_COLOR = Color.rgb(0xD9, 0x77, 0x06)
        val KEYWORD_COLOR = Color.rgb(0x7C, 0x3A, 0xED)

        // Raw strings so the backslashes stay regex metacharacters, not Kotlin escapes.
        val LINE_TAIL = """[^\n]*"""
        val BLOCK_MID = """[\s\S]*?"""
        val STRING = """("(?:\\.|[^"\\\n])*"|'(?:\\.|[^'\\\n])*'|"{3}[\s\S]*?"{3})"""
        val NUMBER = """(\b(?:0[xX][0-9a-fA-F]+|0[bB][01]+|\d[\d_]*(?:\.\d+)?(?:[eE][+-]?\d+)?)\b)"""

        data class LangRule(
            val keywords: List<String>,
            val literals: List<String>,
            val lineComment: List<String> = emptyList(),
            val blockComment: Pair<String, String>? = null,
        )

        val GENERIC = LangRule(
            keywords = listOf("if", "else", "for", "while", "return", "function", "class", "import", "new"),
            literals = listOf("true", "false", "null"),
            lineComment = listOf("//"),
            blockComment = "/*" to "*/",
        )

        val RULES: Map<String, LangRule> = mapOf(
            "kotlin" to LangRule(
                listOf("abstract","actual","as","break","by","catch","class","companion","const","constructor","continue","crossinline","data","do","dynamic","else","enum","expect","external","false","final","finally","for","fun","get","if","import","in","infix","init","inline","inner","interface","internal","is","lateinit","noinline","null","object","open","operator","out","override","package","private","protected","public","reified","return","sealed","set","super","suspend","tailrec","this","throw","true","try","typealias","val","var","vararg","when","where","while"),
                listOf("true","false","null"),
                listOf("//"), "/*" to "*/",
            ),
            "java" to LangRule(
                listOf("abstract","assert","boolean","break","byte","case","catch","char","class","const","continue","default","do","double","else","enum","extends","final","finally","float","for","goto","if","implements","import","instanceof","int","interface","long","native","new","package","private","protected","public","record","return","sealed","short","static","strictfp","super","switch","synchronized","this","throw","throws","transient","try","var","void","volatile","while","yield"),
                listOf("true","false","null"),
                listOf("//"), "/*" to "*/",
            ),
            "python" to LangRule(
                listOf("and","as","assert","async","await","break","class","continue","def","del","elif","else","except","finally","for","from","global","if","import","in","is","lambda","nonlocal","not","or","pass","raise","return","try","while","with","yield"),
                listOf("True","False","None"),
                listOf("#"),
            ),
            "javascript" to LangRule(
                listOf("async","await","break","case","catch","class","const","continue","debugger","default","delete","do","else","export","extends","finally","for","function","if","import","in","instanceof","let","new","of","return","static","super","switch","this","throw","try","typeof","var","void","while","with","yield"),
                listOf("true","false","null","undefined"),
                listOf("//"), "/*" to "*/",
            ),
            "typescript" to LangRule(
                listOf("abstract","any","async","await","boolean","break","case","catch","class","const","continue","declare","default","delete","do","else","enum","export","extends","false","finally","for","function","if","implements","import","in","instanceof","interface","let","never","new","null","number","of","private","protected","public","readonly","return","static","string","super","switch","symbol","this","throw","true","try","type","typeof","undefined","var","void","while","with","unknown","yield"),
                listOf("true","false","null","undefined"),
                listOf("//"), "/*" to "*/",
            ),
            "c" to LangRule(
                listOf("auto","break","case","char","const","continue","default","do","double","else","enum","extern","float","for","goto","if","int","long","register","return","short","signed","sizeof","static","struct","switch","typedef","union","unsigned","void","volatile","while"),
                listOf("true","false","NULL"),
                listOf("//"), "/*" to "*/",
            ),
            "cpp" to LangRule(
                listOf("alignof","auto","bool","break","case","catch","char","class","const","constexpr","continue","decltype","default","delete","do","double","else","enum","explicit","extern","false","float","for","friend","goto","if","inline","int","long","mutable","namespace","new","noexcept","nullptr","operator","private","protected","public","return","short","signed","sizeof","static","struct","switch","template","this","throw","true","try","typedef","typename","typeid","union","unsigned","using","virtual","void","volatile","while"),
                listOf("true","false","nullptr","NULL"),
                listOf("//"), "/*" to "*/",
            ),
            "csharp" to LangRule(
                listOf("abstract","as","async","await","base","bool","break","byte","case","catch","char","checked","class","const","continue","decimal","default","delegate","do","double","else","enum","event","explicit","extern","false","finally","fixed","float","for","foreach","get","goto","if","implicit","in","int","interface","internal","is","lock","long","namespace","new","null","object","operator","out","override","params","private","protected","public","readonly","record","ref","return","sbyte","sealed","set","short","sizeof","stackalloc","static","string","struct","switch","this","throw","true","try","typeof","uint","ulong","unchecked","unsafe","ushort","using","var","virtual","void","volatile","while"),
                listOf("true","false","null"),
                listOf("//"), "/*" to "*/",
            ),
            "go" to LangRule(
                listOf("break","case","chan","const","continue","default","defer","else","fallthrough","for","func","go","goto","if","import","interface","map","package","range","return","select","struct","switch","type","var"),
                listOf("true","false","nil","iota"),
                listOf("//"), "/*" to "*/",
            ),
            "rust" to LangRule(
                listOf("as","async","await","break","const","continue","crate","dyn","else","enum","extern","fn","for","if","impl","in","let","loop","match","mod","move","mut","pub","ref","return","self","static","struct","super","trait","type","unsafe","use","where","while"),
                listOf("true","false","None","Some"),
                listOf("//"), "/*" to "*/",
            ),
            "sql" to LangRule(
                listOf("add","all","alter","and","as","asc","begin","by","case","check","column","commit","constraint","create","default","delete","desc","distinct","drop","else","end","from","full","group","having","if","in","index","inner","insert","into","join","key","left","limit","not","null","on","or","order","outer","primary","references","right","rollback","select","set","table","then","unique","union","update","values","view","when","where"),
                listOf("true","false","null"),
                listOf("--"),
            ),
            "bash" to LangRule(
                listOf("alias","break","case","continue","declare","do","done","echo","elif","else","esac","exit","export","false","fi","for","function","if","in","local","read","return","set","then","true","until","while"),
                listOf("true","false"),
                listOf("#"),
            ),
            "yaml" to LangRule(
                emptyList(),
                listOf("true","false","null","yes","no","on","off"),
                listOf("#"),
            ),
            "toml" to LangRule(emptyList(), listOf("true","false"), listOf("#")),
            "json" to LangRule(emptyList(), listOf("true","false","null"), listOf("//"), "/*" to "*/"),
            "properties" to LangRule(emptyList(), emptyList(), listOf("#", ";")),
            "ini" to LangRule(emptyList(), emptyList(), listOf("#", ";")),
            "makefile" to LangRule(emptyList(), emptyList(), listOf("#")),
            "dockerfile" to LangRule(emptyList(), emptyList(), listOf("#")),
            "perl" to LangRule(
                listOf("my","sub","use","package","if","else","elsif","for","foreach","while","return","last","next"),
                listOf("true","false","undef"),
                listOf("#"),
            ),
            "ruby" to LangRule(
                listOf("begin","case","class","def","do","else","elsif","end","ensure","for","if","in","module","next","rescue","return","then","unless","until","while","yield","require","include","extend"),
                listOf("true","false","nil","self"),
                listOf("#"),
            ),
            "swift" to LangRule(
                listOf("break","case","catch","class","continue","default","defer","do","else","enum","extension","fallthrough","fileprivate","for","func","guard","if","import","in","init","internal","inout","let","open","operator","private","protocol","public","repeat","return","struct","static","switch","throws","try","typealias","var","where","while"),
                listOf("true","false","nil","self"),
                listOf("//"), "/*" to "*/",
            ),
            "dart" to LangRule(
                listOf("as","assert","async","await","break","case","catch","class","const","continue","default","do","else","enum","export","extends","external","factory","false","finally","for","get","if","implements","import","in","interface","is","library","mixin","new","null","on","operator","part","return","set","show","static","super","switch","sync","this","throw","true","try","typedef","var","void","while","with","yield"),
                listOf("true","false","null"),
                listOf("//"), "/*" to "*/",
            ),
            "php" to LangRule(
                listOf("abstract","as","break","case","catch","class","const","continue","declare","default","do","echo","else","elseif","enddeclare","endfor","endforeach","endif","endswitch","endwhile","extends","final","finally","for","foreach","function","global","goto","if","implements","include","instanceof","interface","namespace","new","private","protected","public","require","return","static","switch","throw","trait","try","use","var","while","yield"),
                listOf("true","false","null"),
                listOf("//", "#"), "/*" to "*/",
            ),
            "powershell" to LangRule(
                listOf("begin","break","catch","class","continue","data","do","dynamicparam","else","elseif","end","enum","exit","filter","finally","for","foreach","from","function","if","in","param","process","return","switch","throw","trap","try","until","while"),
                listOf("true","false","null"),
                listOf("#"),
            ),
            "r" to LangRule(
                listOf("break","else","for","function","if","in","library","repeat","require","return","while"),
                listOf("TRUE","FALSE","NULL","NA","NaN","Inf"),
                listOf("#"),
            ),
            "css" to LangRule(emptyList(), listOf("true","false"), emptyList(), "/*" to "*/"),
            "html" to LangRule(emptyList(), emptyList(), emptyList(), "<!--" to "-->"),
            "xml" to LangRule(emptyList(), emptyList(), emptyList(), "<!--" to "-->"),
        )

        fun normalizeLang(language: String?): String {
            val l = (language ?: "").trim().lowercase()
            return when (l) {
                "py", "python3" -> "python"
                "js" -> "javascript"
                "ts" -> "typescript"
                "bash", "zsh" -> "bash"
                "sh", "shell" -> "bash"
                "c++", "cc", "cxx" -> "cpp"
                "c#" -> "csharp"
                "golang" -> "go"
                "yml" -> "yaml"
                "scss", "less" -> "css"
                "text", "txt", "md", "markdown" -> "none"
                else -> l
            }
        }
    }
}

/**
 * Code-block theme colors applied by [buildMarkwon] through [CodeHighlightPlugin].
 */
internal object CodeHighlight {
    val bg = Color.rgb(0xED, 0xEB, 0xE3)
    val text = Color.rgb(0x1E, 0x1E, 0x2E)
}

internal class CodeHighlightPlugin : AbstractMarkwonPlugin() {
    override fun configureTheme(builder: MarkwonTheme.Builder) {
        builder.codeBackgroundColor(CodeHighlight.bg)
        builder.codeTextColor(CodeHighlight.text)
    }

    override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
        builder.syntaxHighlight(SimpleSyntaxHighlight())
    }
}
