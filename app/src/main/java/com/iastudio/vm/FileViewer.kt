package com.iastudio.vm

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/** Visionneuse plein écran : texte/code, image (zoom), PDF, tableau CSV, Markdown, HTML, archive, audio/vidéo, hexadécimal. */
@Composable
fun FileViewer(att: Att, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(att.name, maxLines = 1, style = MaterialTheme.typography.titleMedium)
                        val sub = listOf(formatSmall(att.size), att.note).filter { it.isNotEmpty() }.joinToString(" · ")
                        if (sub.isNotEmpty()) Text(sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    IconButton(onClose) { Icon(Icons.Default.Close, "Fermer") }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) { ViewerBody(att) }
            }
        }
    }
}

private fun extOf(name: String) = name.substringAfterLast('.', "").lowercase()

/** Lit le fichier d'origine (jusqu'à 2 Mo) ou, à défaut, le texte extrait. */
private fun textOf(att: Att): String {
    if (att.kind == "office") return att.text
    if (att.path.isNotEmpty()) {
        val f = File(att.path)
        if (f.exists() && att.kind in setOf("text")) {
            return f.inputStream().use { i ->
                val bo = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var left = 2_000_000
                while (left > 0) {
                    val r = i.read(buf, 0, minOf(buf.size, left))
                    if (r < 0) break
                    bo.write(buf, 0, r)
                    left -= r
                }
                String(bo.toByteArray(), Charsets.UTF_8)
            }
        }
    }
    return att.text
}

@Composable
private fun ViewerBody(att: Att) {
    val file = if (att.path.isNotEmpty()) File(att.path) else null
    when (att.kind) {
        "image" -> ZoomImage(att.imagePath.ifEmpty { att.path })
        "pdf" -> if (file != null && file.exists()) PdfViewer(file, att.text) else TextViewer(att.text, att.name)
        "media" -> if (file != null && file.exists()) MediaViewer(file) else Note("Fichier introuvable.")
        "zip" -> if (file != null && file.exists()) ZipViewer(file) else TextViewer(att.text, att.name)
        "file" -> if (file != null && file.exists()) HexViewer(file) else Note("Fichier introuvable.")
        else -> {
            val t = textOf(att)
            val ext = extOf(att.name)
            when {
                t.isBlank() -> Note("Aucun texte à afficher dans ce fichier.")
                ext == "html" || ext == "htm" -> HtmlViewer(t)
                ext == "csv" || ext == "tsv" -> CsvViewer(t, if (ext == "tsv") '\t' else if (t.lineSequence().firstOrNull()?.contains(';') == true) ';' else ',')
                ext == "md" -> MarkdownViewer(t)
                ext == "json" -> TextViewer(t, att.name, canPretty = true)
                else -> TextViewer(t, att.name)
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ------------------------------------------------------------------ Texte / code avec numéros de ligne et recherche

private fun pretty(json: String): String? = try {
    val t = json.trim()
    if (t.startsWith("[")) JSONArray(t).toString(2) else JSONObject(t).toString(2)
} catch (e: Exception) {
    null
}

@Composable
fun TextViewer(content: String, name: String, canPretty: Boolean = false) {
    val clip = LocalClipboardManager.current
    var text by remember(content) { mutableStateOf(content) }
    var query by remember { mutableStateOf("") }
    var size by remember { mutableFloatStateOf(12f) }
    val lines = remember(text) { text.lines() }
    val matches = remember(text, query) { if (query.isBlank()) 0 else lines.count { it.contains(query, true) } }
    val hl = MaterialTheme.colorScheme.tertiaryContainer
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                query, { query = it }, Modifier.weight(1f), singleLine = true,
                placeholder = { Text("Rechercher", fontSize = 13.sp) },
                supportingText = { if (query.isNotBlank()) Text("$matches ligne(s)") },
            )
            TextButton({ size = (size - 1f).coerceAtLeast(8f) }) { Text("A−") }
            TextButton({ size = (size + 1f).coerceAtMost(24f) }) { Text("A+") }
        }
        Row(Modifier.padding(horizontal = 8.dp).horizontalScroll(rememberScrollState())) {
            TextButton({ clip.setText(AnnotatedString(text)) }) { Text("Copier tout") }
            if (canPretty) TextButton({ pretty(text)?.let { text = it } }) { Text("Mettre en forme") }
            Text("${lines.size} lignes", Modifier.align(Alignment.CenterVertically).padding(start = 8.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SelectionContainer(Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                items(lines.size) { i ->
                    val line = lines[i]
                    Row {
                        Text(
                            "${i + 1}", Modifier.width(40.dp), fontFamily = FontFamily.Monospace, fontSize = size.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            if (query.isBlank()) AnnotatedString(line) else highlight(line, query, hl),
                            fontFamily = FontFamily.Monospace, fontSize = size.sp, lineHeight = (size * 1.35f).sp,
                        )
                    }
                }
            }
        }
    }
}

private fun highlight(line: String, q: String, bg: Color): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < line.length) {
        val k = line.indexOf(q, i, ignoreCase = true)
        if (k < 0) { append(line.substring(i)); break }
        append(line.substring(i, k))
        withStyle(SpanStyle(background = bg, fontWeight = FontWeight.Bold)) { append(line.substring(k, k + q.length)) }
        i = k + q.length
    }
}

// ------------------------------------------------------------------ Markdown

private fun inline(s: String, codeBg: Color): AnnotatedString = buildAnnotatedString {
    val re = Regex("\\*\\*(.+?)\\*\\*|`([^`]+)`")
    var i = 0
    re.findAll(s).forEach { m ->
        append(s.substring(i, m.range.first))
        if (m.groupValues[1].isNotEmpty()) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(m.groupValues[1]) }
        else withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) { append(m.groupValues[2]) }
        i = m.range.last + 1
    }
    append(s.substring(i))
}

@Composable
fun MarkdownViewer(md: String) {
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        TabRow(tab) {
            Tab(tab == 0, { tab = 0 }, text = { Text("Rendu") })
            Tab(tab == 1, { tab = 1 }, text = { Text("Source") })
        }
        if (tab == 1) { TextViewer(md, "source.md"); return@Column }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            md.split("```").forEachIndexed { k, part ->
                if (k % 2 == 0) {
                    if (part.isNotBlank()) Md.Block(part.trim())
                } else {
                    Text(
                        part.substringAfter('\n', part).trimEnd(),
                        Modifier.fillMaxWidth().background(codeBg).padding(8.dp).horizontalScroll(rememberScrollState()),
                        fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------ HTML

@Composable
fun HtmlViewer(html: String) {
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        TabRow(tab) {
            Tab(tab == 0, { tab = 0 }, text = { Text("Aperçu") })
            Tab(tab == 1, { tab = 1 }, text = { Text("Code") })
        }
        if (tab == 1) { TextViewer(html, "page.html"); return@Column }
        AndroidView(
            factory = { c ->
                WebView(c).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    webViewClient = WebViewClient()
                    loadDataWithBaseURL("https://apercu.local/", html, "text/html", "UTF-8", null)
                }
            },
            modifier = Modifier.fillMaxSize(), onRelease = { it.destroy() },
        )
    }
}

// ------------------------------------------------------------------ CSV : tableau

private fun parseCsv(text: String, sep: Char): List<List<String>> {
    val rows = ArrayList<List<String>>()
    var cur = ArrayList<String>()
    val sb = StringBuilder()
    var q = false
    var i = 0
    while (i < text.length && rows.size < 5000) {
        val c = text[i]
        when {
            q && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { sb.append('"'); i++ }
            c == '"' -> q = !q
            !q && c == sep -> { cur.add(sb.toString()); sb.setLength(0) }
            !q && (c == '\n' || c == '\r') -> {
                if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                cur.add(sb.toString()); sb.setLength(0)
                rows.add(cur); cur = ArrayList()
            }
            else -> sb.append(c)
        }
        i++
    }
    if (sb.isNotEmpty() || cur.isNotEmpty()) { cur.add(sb.toString()); rows.add(cur) }
    return rows
}

@Composable
fun CsvViewer(text: String, sep: Char) {
    val rows = remember(text) { parseCsv(text, sep) }
    val hs = rememberScrollState()
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        TabRow(tab) {
            Tab(tab == 0, { tab = 0 }, text = { Text("Tableau") })
            Tab(tab == 1, { tab = 1 }, text = { Text("Texte") })
        }
        if (tab == 1) { TextViewer(text, "donnees.csv"); return@Column }
        Text("${rows.size} ligne(s)", Modifier.padding(8.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(Modifier.fillMaxSize()) {
            items(rows.size) { r ->
                Row(
                    Modifier.horizontalScroll(hs).background(
                        if (r == 0) MaterialTheme.colorScheme.secondaryContainer
                        else if (r % 2 == 0) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f) else Color.Transparent,
                    ),
                ) {
                    rows[r].forEach { cell ->
                        Text(
                            cell, Modifier.width(130.dp).padding(6.dp), fontSize = 12.sp, maxLines = 3,
                            fontWeight = if (r == 0) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Image avec zoom

@Composable
fun ZoomImage(path: String) {
    val bmp by produceState<ImageBitmap?>(null, path) {
        value = withContext(Dispatchers.IO) {
            try {
                val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, b)
                var s = 1
                while (b.outWidth / s > 2400 || b.outHeight / s > 2400) s *= 2
                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })?.asImageBitmap()
            } catch (e: Exception) {
                null
            }
        }
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val b = bmp
    if (b == null) { Note("Chargement de l'image…"); return }
    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 8f)
                    offset = if (scale <= 1f) Offset.Zero else offset + pan
                }
            }
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { scale = if (scale > 1f) 1f else 3f; offset = Offset.Zero }) },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            b, "Image", Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
            contentScale = ContentScale.Fit,
        )
        Text(
            "${b.width}×${b.height} · double toucher = zoom", Modifier.align(Alignment.BottomCenter).padding(8.dp),
            color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp,
        )
    }
}

// ------------------------------------------------------------------ PDF

@Composable
fun PdfViewer(file: File, extracted: String) {
    var tab by remember { mutableIntStateOf(0) }
    val renderer = remember(file) {
        try {
            PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))
        } catch (e: Exception) {
            null
        }
    }
    DisposableEffect(renderer) { onDispose { try { renderer?.close() } catch (e: Exception) { } } }
    var zoom by remember { mutableFloatStateOf(1f) }
    Column(Modifier.fillMaxSize()) {
        TabRow(tab) {
            Tab(tab == 0, { tab = 0 }, text = { Text("Pages") })
            Tab(tab == 1, { tab = 1 }, text = { Text("Texte extrait") })
        }
        if (tab == 1) {
            if (extracted.isBlank()) Note("Aucun texte extractible dans ce PDF.") else TextViewer(extracted, "texte.txt")
            return@Column
        }
        if (renderer == null) { Note("Ce PDF ne peut pas être affiché (protégé ou abîmé)."); return@Column }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${renderer.pageCount} page(s)", Modifier.padding(start = 12.dp).weight(1f), fontSize = 13.sp)
            TextButton({ zoom = (zoom - 0.5f).coerceAtLeast(1f) }) { Text("−") }
            Text("${(zoom * 100).toInt()} %", fontSize = 13.sp)
            TextButton({ zoom = (zoom + 0.5f).coerceAtMost(3f) }) { Text("+") }
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val pageWidth = maxWidth * zoom
            Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
                LazyColumn(Modifier.width(pageWidth), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(renderer.pageCount) { i ->
                        val page by produceState<ImageBitmap?>(null, i, zoom) {
                            value = withContext(Dispatchers.IO) {
                                try {
                                    synchronized(renderer) {
                                        val p = renderer.openPage(i)
                                        val w = (900 * zoom).toInt()
                                        val h = (w.toFloat() * p.height / p.width).toInt().coerceAtLeast(1)
                                        val bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                        bm.eraseColor(AColor.WHITE)
                                        p.render(bm, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                        p.close()
                                        bm.asImageBitmap()
                                    }
                                } catch (e: Exception) {
                                    null
                                }
                            }
                        }
                        val pg = page
                        if (pg != null) Image(pg, "Page ${i + 1}", Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
                        else Box(Modifier.fillMaxWidth().height(300.dp), contentAlignment = Alignment.Center) { Text("Page ${i + 1}…") }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Archive .zip

@Composable
fun ZipViewer(file: File) {
    val entries = remember(file) {
        try {
            ZipFile(file).use { z -> z.entries().toList().filter { !it.isDirectory }.map { it.name to it.size } }
        } catch (e: Exception) {
            emptyList()
        }
    }
    var open by remember { mutableStateOf<Att?>(null) }
    LazyColumn(Modifier.fillMaxSize().padding(8.dp)) {
        item { Text("${entries.size} fichier(s) dans l'archive. Touche un fichier texte pour le lire.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(6.dp)) }
        items(entries.size) { i ->
            val (n, sz) = entries[i]
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .pointerInput(n) {
                        detectTapGestures {
                            if (sz in 0..2_000_000) {
                                try {
                                    ZipFile(file).use { z ->
                                        val e = z.getEntry(n)
                                        val bytes = z.getInputStream(e).use { it.readBytes() }
                                        if (bytes.contains(0)) open = Att(n, "file", size = sz, note = "binaire")
                                        else open = Att(n, "text", String(bytes, Charsets.UTF_8))
                                    }
                                } catch (e: Exception) {
                                }
                            }
                        }
                    }.padding(8.dp),
            ) {
                Text("📄 $n", Modifier.weight(1f), fontSize = 13.sp, maxLines = 1)
                Text(formatSmall(sz), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    open?.let { a ->
        if (a.kind == "text") FileViewer(a) { open = null } else { open = null }
    }
}

// ------------------------------------------------------------------ Audio / vidéo

@Composable
fun MediaViewer(file: File) {
    AndroidView(
        factory = { c ->
            VideoView(c).apply {
                val mc = MediaController(c)
                mc.setAnchorView(this)
                setMediaController(mc)
                setVideoPath(file.path)
                setOnPreparedListener { start() }
            }
        },
        modifier = Modifier.fillMaxSize(), onRelease = { it.stopPlayback() },
    )
}

// ------------------------------------------------------------------ Hexadécimal

@Composable
fun HexViewer(file: File) {
    val lines = remember(file) {
        val bytes = file.inputStream().use { i ->
            val b = ByteArray(16384)
            val n = i.read(b)
            if (n <= 0) ByteArray(0) else b.copyOf(n)
        }
        (0 until bytes.size step 16).map { o ->
            val row = bytes.copyOfRange(o, minOf(o + 16, bytes.size))
            "%08x  ".format(o) + row.joinToString(" ") { "%02x".format(it) }.padEnd(47) + "  " +
                row.map { b -> if (b in 32..126) b.toInt().toChar() else '.' }.joinToString("")
        }
    }
    Column(Modifier.fillMaxSize()) {
        Text("Premiers ${minOf(file.length(), 16384L)} octets sur ${formatSmall(file.length())}", Modifier.padding(8.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(Modifier.fillMaxSize().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
            items(lines.size) { i -> Text(lines[i], fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
        }
    }
}
