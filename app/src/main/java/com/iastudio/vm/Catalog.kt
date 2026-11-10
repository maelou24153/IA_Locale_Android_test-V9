package com.iastudio.vm

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class ModelEntry(
    val id: String,
    val type: String, // "chat" ou "image"
    val name: String,
    val file: String,
    val url: String,
    val steps: Int = 20,
    val cfg: Double = 7.0,
    val minRam: Int = 0,
    val sizeGb: Double = 0.0,
    val tags: String = "",
)

object Catalog {
    fun load(ctx: Context): List<ModelEntry> {
        val text = ctx.assets.open("catalog.json").bufferedReader().use { it.readText() }
        val arr = JSONArray(text)
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            ModelEntry(
                o.getString("id"), o.getString("type"), o.getString("name"),
                o.getString("file"), o.getString("url"),
                o.optInt("steps", 20), o.optDouble("cfg", 7.0),
                o.optInt("minRam", 0), o.optDouble("sizeGb", 0.0), o.optString("tags", ""),
            )
        }
    }
}

class DownloadCancelled : Exception("Téléchargement annulé")

class FatalDownload(msg: String) : RuntimeException(msg)

object Downloader {
    /**
     * Télécharge avec reprise (fichier .part). onProgress reçoit un pourcentage 0..100.
     * shouldStop est interrogé régulièrement : s'il renvoie true, le téléchargement s'arrête (le .part est conservé).
     */
    fun download(url: String, dest: File, token: String, shouldStop: () -> Boolean, onProgress: (Int) -> Unit) {
        val part = File(dest.path + ".part")
        val already = if (part.exists()) part.length() else 0L
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20000
            readTimeout = 30000
            setRequestProperty("User-Agent", "Studo-Android")
            if (already > 0) setRequestProperty("Range", "bytes=$already-")
            if (token.isNotBlank() && url.contains("huggingface.co")) setRequestProperty("Authorization", "Bearer $token")
        }
        try {
            val code = conn.responseCode
            if (code == 416 && part.exists()) { // le .part était déjà complet
                if (!part.renameTo(dest)) throw FatalDownload("Impossible de finaliser ${dest.name}")
                return
            }
            if (code == 401 || code == 403) throw FatalDownload("Accès refusé (HTTP $code) : modèle réservé, ajoute ton jeton Hugging Face dans Réglages")
            if (code !in 200..299) {
                val msg = "Téléchargement refusé (HTTP $code)"
                if (code in 400..499 && code != 408 && code != 429) throw FatalDownload(msg) else throw RuntimeException(msg)
            }
            val resumed = code == 206
            val len = conn.contentLengthLong
            val total = if (len > 0) len + (if (resumed) already else 0L) else -1L
            var done = if (resumed) already else 0L
            FileOutputStream(part, resumed).use { out ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(256 * 1024)
                    var last = -1
                    while (true) {
                        if (shouldStop()) throw DownloadCancelled()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val p = (done * 100 / total).toInt()
                            if (p != last) { last = p; onProgress(p) }
                        }
                    }
                }
            }
            if (total > 0 && done != total) throw RuntimeException("Téléchargement incomplet (${done / 1000000} Mo sur ${total / 1000000} Mo)")
            if (!part.renameTo(dest)) throw FatalDownload("Impossible de finaliser ${dest.name}")
        } finally {
            conn.disconnect()
        }
    }
}

data class HfRepo(val id: String, val downloads: Long, val likes: Long)
data class HfFile(val name: String, val size: Long)

/** Recherche dans Hugging Face : accès à tous les modèles GGUF publics. */
object HfBrowser {
    private fun get(url: String, token: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.setRequestProperty("User-Agent", "Studo-Android")
        if (token.isNotBlank()) c.setRequestProperty("Authorization", "Bearer $token")
        try {
            if (c.responseCode !in 200..299) throw RuntimeException("Hugging Face a répondu HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    fun search(query: String, token: String): List<HfRepo> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val arr = JSONArray(get("https://huggingface.co/api/models?search=$q&filter=gguf&sort=downloads&direction=-1&limit=30", token))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            HfRepo(o.getString("id"), o.optLong("downloads", 0L), o.optLong("likes", 0L))
        }
    }

    /** Fichiers .gguf d'un dépôt (sans les modèles découpés ni les fichiers mmproj), avec leur taille. */
    fun files(id: String, token: String, safetensors: Boolean = false): List<HfFile> {
        val o = JSONObject(get("https://huggingface.co/api/models/$id?blobs=true", token))
        val s = o.getJSONArray("siblings")
        return (0 until s.length()).map { s.getJSONObject(it) }
            .filter { j ->
                val n = j.getString("rfilename")
                (n.endsWith(".gguf", true) || (safetensors && n.endsWith(".safetensors", true))) && !n.contains("mmproj", true) && !Regex("-0000\\d-of-0000\\d").containsMatchIn(n)
            }
            .map { j ->
                var size = j.optLong("size", 0L)
                if (size <= 0L) size = j.optJSONObject("lfs")?.optLong("size", 0L) ?: 0L
                HfFile(j.getString("rfilename"), size)
            }
            .sortedBy { it.size }
    }

    fun url(id: String, file: String) = "https://huggingface.co/$id/resolve/main/$file?download=true"
}
