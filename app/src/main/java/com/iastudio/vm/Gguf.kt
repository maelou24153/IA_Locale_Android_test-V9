package com.iastudio.vm

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/** Résultat de l'analyse d'un fichier de modèle. kind : chat | image | companion | projector | unknown */
data class ModelInfo(
    val kind: String,
    val arch: String = "",
    val needsCompanions: Boolean = false,
    val note: String = "",
) {
    val label: String
        get() = when (kind) {
            "chat" -> "IA de chat / code" + (if (arch.isNotEmpty()) " ($arch)" else "")
            "image" -> "IA d'images" + (if (arch.isNotEmpty()) " ($arch)" else "")
            "companion" -> "fichier complémentaire d'image (VAE / encodeur de texte)"
            "projector" -> "projecteur de vision (non utilisé seul)"
            else -> "type inconnu"
        }
}

/**
 * Lecture de l'en-tête GGUF pour savoir si un fichier est une IA de texte (elle contient un
 * vocabulaire « tokenizer ») ou une IA d'images (stable-diffusion.cpp : pas de vocabulaire).
 */
object Gguf {
    private val IMAGE_EXT = setOf("safetensors", "ckpt", "pt", "pth", "bin")

    /** Rôle d'un fichier complémentaire d'après son nom (vae, clip_l, clip_g, t5xxl) ou null. */
    fun companionRole(name: String): String? {
        val n = name.lowercase()
        return when {
            n.contains("t5xxl") || n.contains("t5-xxl") || n.contains("umt5") -> "t5xxl"
            n.contains("clip_l") || n.contains("clip-l") -> "clip_l"
            n.contains("clip_g") || n.contains("clip-g") -> "clip_g"
            n.contains("vae") || n.startsWith("ae.") || n.contains("_ae.") || n.contains("-ae.") -> "vae"
            else -> null
        }
    }

    /** Fichiers complémentaires indispensables selon l'architecture. */
    fun requiredCompanions(arch: String, name: String): List<String> {
        val a = arch.lowercase()
        val n = name.lowercase()
        return when {
            a.startsWith("flux") || n.contains("flux") || a == "chroma" -> listOf("vae", "clip_l", "t5xxl")
            a.contains("sd3") || n.contains("sd3") -> listOf("vae", "clip_l", "clip_g", "t5xxl")
            a in setOf("sana", "wan", "hidream", "lumina2", "qwen_image", "z_image") -> listOf("vae")
            else -> emptyList()
        }
    }

    /** Estimation rapide d'après le nom, avant la vraie lecture de l'en-tête. */
    fun quickGuess(name: String): String {
        val n = name.lowercase()
        return when {
            n.contains("mmproj") -> "projector"
            companionRole(n) != null -> "companion"
            n.startsWith("img__") -> "image"
            n.endsWith(".gguf") -> "chat"
            n.substringAfterLast('.') in IMAGE_EXT -> "image"
            else -> "unknown"
        }
    }

    fun inspect(f: File): ModelInfo {
        val name = f.name
        val ext = name.substringAfterLast('.', "").lowercase()
        val role = companionRole(name)
        if (name.lowercase().contains("mmproj")) return ModelInfo("projector", note = "mmproj")
        if (ext != "gguf") {
            return when {
                role != null -> ModelInfo("companion", note = role)
                ext in IMAGE_EXT -> ModelInfo("image", arch = "", needsCompanions = requiredCompanions("", name).isNotEmpty())
                else -> ModelInfo("unknown")
            }
        }
        return try {
            parse(f)
        } catch (e: Exception) {
            // En-tête illisible : on se rabat sur le nom du fichier.
            val g = quickGuess(name)
            val low = name.lowercase()
            val img = g == "image" || low.contains("stable-diffusion") || low.contains("sdxl") ||
                low.contains("flux") || low.contains("sd_turbo") || low.contains("sd-turbo") || low.contains("sd1") || low.contains("sd3")
            ModelInfo(if (g == "chat" && img) "image" else g, note = "en-tête non lu")
        }
    }

    private class Reader(private val s: InputStream) {
        fun u8(): Int { val v = s.read(); if (v < 0) throw EOFException(); return v }
        fun u32(): Long { var v = 0L; for (i in 0 until 4) v = v or (u8().toLong() shl (8 * i)); return v }
        fun u64(): Long { var v = 0L; for (i in 0 until 8) v = v or (u8().toLong() shl (8 * i)); return v }
        fun skip(n: Long) {
            var left = n
            while (left > 0) {
                val k = s.skip(left)
                if (k <= 0) { u8(); left-- } else left -= k
            }
        }
        fun readFully(n: Int): ByteArray {
            val b = ByteArray(n)
            var o = 0
            while (o < n) { val r = s.read(b, o, n - o); if (r < 0) throw EOFException(); o += r }
            return b
        }
        fun key(): String {
            val n = u64()
            if (n < 0 || n > 4096) throw EOFException()
            return String(readFully(n.toInt()), Charsets.UTF_8)
        }
        fun string(): String {
            val n = u64()
            if (n < 0 || n > 200_000) { skip(n); return "" }
            return String(readFully(n.toInt()), Charsets.UTF_8)
        }
        fun skipValue(type: Int) {
            when (type) {
                0, 1, 7 -> skip(1)
                2, 3 -> skip(2)
                4, 5, 6 -> skip(4)
                10, 11, 12 -> skip(8)
                8 -> { val n = u64(); skip(n) }
                9 -> {
                    val et = u32().toInt()
                    val cnt = u64()
                    val fixed = when (et) { 0, 1, 7 -> 1; 2, 3 -> 2; 4, 5, 6 -> 4; 10, 11, 12 -> 8; else -> 0 }
                    if (fixed > 0) skip(cnt * fixed) else for (i in 0 until cnt) skipValue(et)
                }
                else -> throw EOFException()
            }
        }
    }

    private fun parse(f: File): ModelInfo {
        return BufferedInputStream(FileInputStream(f), 1 shl 16).use { input ->
            val r = Reader(input)
            val magic = String(r.readFully(4), Charsets.US_ASCII)
            if (magic != "GGUF") throw EOFException()
            r.u32() // version
            r.u64() // nombre de tenseurs
            val kv = r.u64()
            if (kv < 0 || kv > 100_000) throw EOFException()
            var arch = ""
            var hasTokenizer = false
            var i = 0L
            while (i < kv) {
                val key = r.key()
                val type = r.u32().toInt()
                if (key.startsWith("tokenizer.ggml.")) { hasTokenizer = true; break }
                if (key == "general.architecture" && type == 8) arch = r.string() else r.skipValue(type)
                i++
            }
            val a = arch.lowercase()
            val name = f.name
            when {
                a.contains("t5") && !hasTokenizer -> ModelInfo("companion", a, note = "encodeur T5")
                a == "clip" -> ModelInfo(if (name.lowercase().contains("mmproj")) "projector" else "companion", a)
                hasTokenizer && a.contains("t5") -> ModelInfo("companion", a, note = "encodeur T5")
                hasTokenizer -> ModelInfo("chat", a)
                else -> ModelInfo("image", a, requiredCompanions(a, name).isNotEmpty())
            }
        }
    }
}
