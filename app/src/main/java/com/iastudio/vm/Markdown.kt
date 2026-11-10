package com.iastudio.vm

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Mise en forme Markdown : gras, italique, barré, code, liens, titres, listes, citations, tableaux. */
object Md {
    private fun isWordChar(c: Char?) = c != null && (c.isLetterOrDigit())

    /** Transforme le texte en ligne (**gras**, *italique*, ~~barré~~, `code`, [lien](url)) en texte mis en forme. */
    fun inline(src: String, codeBg: androidx.compose.ui.graphics.Color, link: androidx.compose.ui.graphics.Color): AnnotatedString =
        buildAnnotatedString { parse(src, this, codeBg, link) }

    private fun parse(s: String, b: AnnotatedString.Builder, codeBg: androidx.compose.ui.graphics.Color, link: androidx.compose.ui.graphics.Color) {
        var i = 0
        val n = s.length
        while (i < n) {
            val c = s[i]
            // échappement \*
            if (c == '\\' && i + 1 < n && s[i + 1] in "*_`~[]\\#") {
                b.append(s[i + 1]); i += 2; continue
            }
            // code `...`
            if (c == '`') {
                val end = s.indexOf('`', i + 1)
                if (end > i) {
                    b.withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) { append(s.substring(i + 1, end)) }
                    i = end + 1; continue
                }
            }
            // lien [texte](url)
            if (c == '[') {
                val close = s.indexOf(']', i + 1)
                if (close > i && close + 1 < n && s[close + 1] == '(') {
                    val pe = s.indexOf(')', close + 2)
                    if (pe > close) {
                        b.withStyle(SpanStyle(color = link, textDecoration = TextDecoration.Underline)) {
                            parse(s.substring(i + 1, close), this, codeBg, link)
                        }
                        i = pe + 1; continue
                    }
                }
            }
            // gras **x** ou __x__
            if ((c == '*' || c == '_') && i + 1 < n && s[i + 1] == c) {
                val end = s.indexOf("$c$c", i + 2)
                if (end > i + 2 && !s[i + 2].isWhitespace() && (c == '*' || (!isWordChar(s.getOrNull(i - 1)) && !isWordChar(s.getOrNull(end + 2))))) {
                    b.withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { parse(s.substring(i + 2, end), this, codeBg, link) }
                    i = end + 2; continue
                }
            }
            // barré ~~x~~
            if (c == '~' && i + 1 < n && s[i + 1] == '~') {
                val end = s.indexOf("~~", i + 2)
                if (end > i + 2) {
                    b.withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { parse(s.substring(i + 2, end), this, codeBg, link) }
                    i = end + 2; continue
                }
            }
            // italique *x* ou _x_
            if ((c == '*' || c == '_') && i + 1 < n && !s[i + 1].isWhitespace() && s[i + 1] != c) {
                val okStart = c == '*' || !isWordChar(s.getOrNull(i - 1))
                if (okStart) {
                    var end = i + 1
                    var found = -1
                    while (end < n) {
                        val e = s.indexOf(c, end)
                        if (e < 0) break
                        if (!s[e - 1].isWhitespace() && (c == '*' || !isWordChar(s.getOrNull(e + 1))) && s.getOrNull(e + 1) != c) { found = e; break }
                        end = e + 1
                    }
                    if (found > i + 1) {
                        b.withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { parse(s.substring(i + 1, found), this, codeBg, link) }
                        i = found + 1; continue
                    }
                }
            }
            b.append(c)
            i++
        }
    }

    private fun isTableSep(l: String): Boolean {
        val t = l.trim()
        return t.contains('-') && t.contains('|') && t.all { it == '|' || it == '-' || it == ':' || it == ' ' }
    }

    private fun cells(l: String): List<String> {
        var t = l.trim()
        if (t.startsWith("|")) t = t.substring(1)
        if (t.endsWith("|")) t = t.substring(0, t.length - 1)
        return t.split("|").map { it.trim() }
    }

    @Composable
    fun Block(text: String, fontSize: Int = 15) {
        val codeBg = MaterialTheme.colorScheme.surfaceVariant
        val link = MaterialTheme.colorScheme.primary
        val grey = MaterialTheme.colorScheme.onSurfaceVariant
        val lines = text.replace("\r\n", "\n").split("\n")
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            var i = 0
            while (i < lines.size) {
                val raw = lines[i]
                val line = raw.trimEnd()
                val t = line.trimStart()
                val indent = (line.length - t.length).coerceAtMost(12)
                when {
                    t.isEmpty() -> { Spacer(Modifier.height(4.dp)); i++ }
                    Regex("^(-{3,}|\\*{3,}|_{3,})$").matches(t) -> { HorizontalDivider(Modifier.padding(vertical = 6.dp)); i++ }
                    t.startsWith("#") && Regex("^#{1,6}\\s+.*").matches(t) -> {
                        val lvl = t.takeWhile { it == '#' }.length
                        val sz = when (lvl) { 1 -> fontSize + 7; 2 -> fontSize + 4; 3 -> fontSize + 2; else -> fontSize + 1 }
                        Text(
                            inline(t.drop(lvl).trim(), codeBg, link),
                            fontSize = sz.sp, fontWeight = FontWeight.Bold, lineHeight = (sz + 6).sp,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        i++
                    }
                    t.startsWith(">") -> {
                        val buf = StringBuilder()
                        while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                            buf.append(lines[i].trimStart().removePrefix(">").trim()).append('\n'); i++
                        }
                        Row(Modifier.fillMaxWidth()) {
                            Box(Modifier.width(3.dp).height(20.dp).background(grey))
                            Spacer(Modifier.width(8.dp))
                            Text(inline(buf.toString().trim(), codeBg, link), color = grey, fontSize = fontSize.sp)
                        }
                    }
                    Regex("^[-*+•]\\s+.*").matches(t) -> {
                        val body = t.substring(1).trim()
                        val box = Regex("^\\[( |x|X)\\]\\s+(.*)").matchEntire(body)
                        Row(Modifier.padding(start = (indent * 2).dp)) {
                            Text(if (box != null) (if (box.groupValues[1] == " ") "☐ " else "☑ ") else "•  ", fontSize = fontSize.sp)
                            Text(inline(box?.groupValues?.get(2) ?: body, codeBg, link), fontSize = fontSize.sp, modifier = Modifier.weight(1f))
                        }
                        i++
                    }
                    Regex("^\\d+[.)]\\s+.*").matches(t) -> {
                        val num = t.takeWhile { it.isDigit() }
                        val body = t.substring(num.length + 1).trim()
                        Row(Modifier.padding(start = (indent * 2).dp)) {
                            Text("$num.  ", fontSize = fontSize.sp, fontWeight = FontWeight.Medium)
                            Text(inline(body, codeBg, link), fontSize = fontSize.sp, modifier = Modifier.weight(1f))
                        }
                        i++
                    }
                    t.contains('|') && i + 1 < lines.size && isTableSep(lines[i + 1]) -> {
                        val head = cells(t)
                        i += 2
                        val rows = mutableListOf<List<String>>()
                        while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) { rows.add(cells(lines[i])); i++ }
                        Column(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
                            Row { head.forEach { h -> Text(inline(h, codeBg, link), fontWeight = FontWeight.Bold, fontSize = (fontSize - 1).sp, modifier = Modifier.width(130.dp).padding(4.dp)) } }
                            HorizontalDivider()
                            rows.forEach { r ->
                                Row { head.indices.forEach { k -> Text(inline(r.getOrElse(k) { "" }, codeBg, link), fontSize = (fontSize - 1).sp, modifier = Modifier.width(130.dp).padding(4.dp)) } }
                            }
                        }
                    }
                    else -> {
                        // paragraphe : on regroupe les lignes consécutives
                        val buf = StringBuilder(t)
                        i++
                        while (i < lines.size) {
                            val nx = lines[i].trim()
                            if (nx.isEmpty() || nx.startsWith("#") || nx.startsWith(">") || Regex("^([-*+•]|\\d+[.)])\\s+.*").matches(nx) ||
                                (nx.contains('|') && i + 1 < lines.size && isTableSep(lines[i + 1]))
                            ) break
                            buf.append('\n').append(nx); i++
                        }
                        Text(inline(buf.toString(), codeBg, link), fontSize = fontSize.sp, lineHeight = (fontSize + 7).sp)
                    }
                }
            }
        }
    }
}
