package com.iastudio.vm

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.Inflater
import java.util.zip.ZipInputStream

private const val MAX_TEXT = 24000

/** Lit au plus n octets (readNBytes n'existe pas avant Android 13). */
private fun java.io.InputStream.readUpTo(n: Int): ByteArray {
    val bo = ByteArrayOutputStream()
    val buf = ByteArray(8192)
    var left = n
    while (left > 0) {
        val r = read(buf, 0, minOf(buf.size, left))
        if (r < 0) break
        bo.write(buf, 0, r)
        left -= r
    }
    return bo.toByteArray()
}

/** Lecture des fichiers joints : le contenu part à l'IA sans jamais être collé dans le message. */
object Attachments {
    private val TEXT_EXT = setOf(
        "txt", "md", "json", "xml", "html", "htm", "css", "js", "ts", "tsx", "jsx", "py", "java", "kt", "kts", "c", "h",
        "cpp", "hpp", "cs", "go", "rs", "rb", "php", "sh", "bat", "ps1", "sql", "yml", "yaml", "toml", "ini", "cfg",
        "csv", "tsv", "log", "gradle", "properties", "swift", "dart", "lua", "r", "vue", "svelte", "tex", "srt",
    )
    private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "webp", "bmp", "gif")

    fun displayName(ctx: Context, uri: Uri): String {
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getString(0)
                    if (!n.isNullOrBlank()) return n
                }
            }
        } catch (e: Exception) {
        }
        return Uri.decode(uri.lastPathSegment ?: "fichier").substringAfterLast('/')
    }

    fun sizeOf(ctx: Context, uri: Uri): Long {
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0)
            }
        } catch (e: Exception) {
        }
        return -1L
    }

    private val MEDIA_EXT = setOf("mp4", "webm", "mkv", "3gp", "mov", "mp3", "wav", "ogg", "m4a", "flac", "aac", "opus")
    private val OFFICE_EXT = setOf("docx", "odt", "ods", "odp", "xlsx", "pptx")

    /** Copie le fichier choisi dans le dossier de la machine, puis l'analyse. */
    fun load(ctx: Context, uri: Uri, dir: File): Att {
        val name = displayName(ctx, uri)
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "fichier" }
        val f = File(dir, "${System.currentTimeMillis()}_$safe")
        var total = 0L
        ctx.contentResolver.openInputStream(uri)?.use { i ->
            f.outputStream().buffered(1 shl 16).use { o ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = i.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > 300_000_000L) { o.flush(); f.delete(); throw RuntimeException("fichier trop gros (300 Mo maximum)") }
                    o.write(buf, 0, n)
                }
            }
        } ?: throw RuntimeException("fichier illisible")
        return fromFile(f, name)
    }

    fun fromFile(f: File, name: String): Att {
        val size = f.length()
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            ext in IMAGE_EXT -> {
                val bmp = decode({ f.inputStream() }, 1024) ?: throw RuntimeException("image illisible")
                val png = File(f.parentFile, f.name + ".s.png")
                FileOutputStream(png).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                Att(name, "image", imagePath = png.path, size = size, path = f.path)
            }
            ext == "pdf" -> {
                if (size > 40_000_000) {
                    Att(name, "pdf", size = size, note = "trop gros pour en extraire le texte (visible dans le visionneur)", path = f.path)
                } else {
                    val t = PdfText.extract(f.readBytes())
                    if (t.isBlank()) {
                        Att(name, "pdf", size = size, note = "texte non extractible (PDF scanné ou police spéciale)", path = f.path)
                    } else {
                        Att(name, "pdf", t.take(MAX_TEXT), size = size, note = if (t.length > MAX_TEXT) "début du document seulement" else "", path = f.path)
                    }
                }
            }
            ext == "zip" -> {
                val sb = StringBuilder()
                var count = 0
                ZipInputStream(f.inputStream().buffered()).use { z ->
                    while (sb.length < MAX_TEXT) {
                        val e = z.nextEntry ?: break
                        if (e.isDirectory) continue
                        val en = e.name.substringAfterLast('.', "").lowercase()
                        if (en !in TEXT_EXT) continue
                        val data = z.readBytes()
                        if (data.size > 80_000 || data.contains(0)) continue
                        sb.append("=== ").append(e.name).append(" ===\n").append(String(data, Charsets.UTF_8)).append("\n\n")
                        count++
                    }
                }
                Att(name, "zip", sb.toString().take(MAX_TEXT), size = size, note = if (count == 0) "aucun fichier texte lu" else "$count fichier(s) lu(s)", path = f.path)
            }
            ext in OFFICE_EXT -> {
                val t = try { officeText(f, ext) } catch (e: Exception) { "" }
                Att(name, "office", t.take(MAX_TEXT), size = size, note = if (t.isBlank()) "texte non extractible" else if (t.length > MAX_TEXT) "début du document seulement" else "", path = f.path)
            }
            ext in MEDIA_EXT -> Att(name, "media", size = size, note = "audio/vidéo : lisible dans le visionneur, l'IA ne peut pas l'écouter", path = f.path)
            ext in TEXT_EXT || looksLikeText(f.inputStream().use { it.readUpTo(4096) }) -> {
                val bytes = f.inputStream().use { it.readUpTo(400_000) }
                val s = String(bytes, Charsets.UTF_8)
                Att(name, "text", s.take(MAX_TEXT), size = size, note = if (s.length > MAX_TEXT) "début du fichier seulement" else "", path = f.path)
            }
            else -> Att(name, "file", size = size, note = "fichier binaire : visible en hexadécimal dans le visionneur", path = f.path)
        }
    }

    private fun xmlText(x: String): String = x.replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")

    /** Texte d'un document Office / LibreOffice (docx, odt, ods, odp, xlsx, pptx). */
    fun officeText(f: File, ext: String): String {
        java.util.zip.ZipFile(f).use { z ->
            fun read(n: String): String? = z.getEntry(n)?.let { z.getInputStream(it).use { s -> String(s.readBytes(), Charsets.UTF_8) } }
            return when (ext) {
                "docx" -> xmlText((read("word/document.xml") ?: "").replace("</w:p>", "\n").replace("<w:tab/>", "\t"))
                "odt", "ods", "odp" -> xmlText((read("content.xml") ?: "").replace("</text:p>", "\n").replace("</text:h>", "\n"))
                "xlsx" -> xmlText((read("xl/sharedStrings.xml") ?: "").replace("</si>", "\n"))
                "pptx" -> {
                    val names = z.entries().toList().map { it.name }.filter { it.startsWith("ppt/slides/slide") && it.endsWith(".xml") }
                        .sortedBy { it.filter { c -> c.isDigit() }.toIntOrNull() ?: 0 }
                    names.mapIndexed { i, n -> "--- Diapo ${i + 1} ---\n" + xmlText((read(n) ?: "").replace("</a:p>", "\n")) }.joinToString("\n")
                }
                else -> ""
            }
        }
    }

    private fun looksLikeText(b: ByteArray?): Boolean = b != null && b.isNotEmpty() && b.none { it.toInt() == 0 }

    private fun decode(open: () -> java.io.InputStream?, max: Int): Bitmap? {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open()?.use { BitmapFactory.decodeStream(it, null, b) }
        var s = 1
        while (b.outWidth / s > max * 2 || b.outHeight / s > max * 2) s *= 2
        val o = BitmapFactory.Options().apply { inSampleSize = s }
        return open()?.use { BitmapFactory.decodeStream(it, null, o) }
    }
}

/** Extraction de texte d'un PDF sans bibliothèque (au mieux : les PDF scannés ne marchent pas). */
object PdfText {
    private val TOKEN = Regex("\\((?:\\\\.|[^\\\\)])*\\)|\\[|\\]|TJ|Tj|ET|T\\*|Td|TD")

    fun extract(bytes: ByteArray): String {
        val s = String(bytes, Charsets.ISO_8859_1)
        val out = StringBuilder()
        var i = 0
        while (out.length < 200_000) {
            val a = s.indexOf("stream", i)
            if (a < 0) break
            var st = a + 6
            if (st < s.length && s[st] == '\r') st++
            if (st < s.length && s[st] == '\n') st++
            val e = s.indexOf("endstream", st)
            if (e < 0) break
            val objStart = s.lastIndexOf("obj", a)
            val dict = if (objStart >= 0) s.substring(objStart, a) else ""
            var data = bytes.copyOfRange(st, e)
            if (dict.contains("FlateDecode")) data = inflate(data)
            val txt = String(data, Charsets.ISO_8859_1)
            if (txt.contains("BT") && txt.contains("ET")) out.append(ops(txt))
            i = e + 9
        }
        val res = out.toString().replace(Regex("[ \\t]+"), " ").replace(Regex("\\n{3,}"), "\n\n").trim()
        val letters = res.count { it.isLetter() }
        return if (letters < 20) "" else res
    }

    private fun inflate(d: ByteArray): ByteArray {
        val inf = Inflater()
        inf.setInput(d)
        val bo = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        try {
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
                bo.write(buf, 0, n)
            }
        } catch (e: Exception) {
        } finally {
            inf.end()
        }
        return bo.toByteArray()
    }

    private fun unescape(lit: String): String {
        val sb = StringBuilder()
        var i = 1
        val end = lit.length - 1
        while (i < end) {
            val c = lit[i]
            if (c == '\\' && i + 1 < end) {
                val n = lit[i + 1]
                when {
                    n == 'n' -> { sb.append('\n'); i += 2 }
                    n == 'r' || n == 't' -> { sb.append(' '); i += 2 }
                    n in '0'..'7' -> {
                        var j = i + 1
                        var v = 0
                        var k = 0
                        while (j < end && k < 3 && lit[j] in '0'..'7') { v = v * 8 + (lit[j] - '0'); j++; k++ }
                        if (v >= 32) sb.append(v.toChar())
                        i = j
                    }
                    else -> { sb.append(n); i += 2 }
                }
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    private fun ops(t: String): String {
        val sb = StringBuilder()
        TOKEN.findAll(t).forEach { m ->
            val v = m.value
            when {
                v.startsWith("(") -> sb.append(unescape(v))
                v == "Td" || v == "TD" || v == "T*" || v == "ET" -> if (sb.isNotEmpty() && sb.last() != '\n') sb.append('\n')
                else -> {}
            }
        }
        return sb.toString() + "\n"
    }
}
