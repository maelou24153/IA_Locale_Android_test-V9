package com.iastudio.vm

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private val THINK_REGEX = Regex("(?s)<think>.*?(</think>|$)")

/** Retire les blocs <think>…</think> (réflexion des modèles « raisonnement »). */
fun stripThink(s: String): String = s.replace(THINK_REGEX, "").trim()

/**
 * Une « machine virtuelle » = un conteneur isolé : son propre dossier de modèles, de sorties,
 * de projets et de conversations. Les moteurs (llama-server, sd) sont des exécutables natifs ARM64
 * fournis dans l'APK.
 */
class VmManager(private val ctx: Context) {
    private val base = File(ctx.filesDir, "vms").apply { mkdirs() }

    /** Un emplacement où ranger les modèles : mémoire interne ou carte SD / USB. */
    data class Volume(val id: String, val label: String, val vmsDir: File, val free: Long)

    /** Id du volume où s'installent les nouveaux modèles (« internal » ou « sd1 », « sd2 »…). */
    @Volatile var targetVolume: String = "internal"

    fun volumes(): List<Volume> {
        val out = ArrayList<Volume>()
        out.add(Volume("internal", "Mémoire interne", base, ctx.filesDir.usableSpace))
        try {
            ctx.getExternalFilesDirs(null).forEachIndexed { i, d ->
                if (i > 0 && d != null && android.os.Environment.getExternalStorageState(d) == android.os.Environment.MEDIA_MOUNTED) {
                    val v = File(d, "vms")
                    if (v.exists() || v.mkdirs()) out.add(Volume("sd$i", "Carte SD / USB" + (if (i > 1) " $i" else ""), v, d.usableSpace))
                }
            }
        } catch (e: Exception) {
        }
        return out
    }

    private fun target(): Volume = volumes().let { l -> l.firstOrNull { it.id == targetVolume } ?: l.first() }

    /** Nom du volume qui contient ce fichier. */
    fun volumeLabelOf(f: File): String =
        volumes().firstOrNull { f.path.startsWith(it.vmsDir.path) }?.label ?: "Mémoire interne"

    fun volumeIdOf(f: File): String =
        volumes().firstOrNull { f.path.startsWith(it.vmsDir.path) }?.id ?: "internal"

    /** Dossiers de modèles de cette machine sur tous les volumes. */
    fun modelsDirs(vm: String): List<File> =
        volumes().map { File(it.vmsDir, "$vm/models") }.filter { it.isDirectory }

    fun allModelFiles(vm: String): List<File> =
        modelsDirs(vm).flatMap { d -> d.listFiles()?.filter { it.isFile } ?: emptyList() }

    /** Fichier d'un modèle, où qu'il soit installé (sinon son futur emplacement). */
    fun modelFile(vm: String, name: String): File =
        modelsDirs(vm).map { File(it, name) }.firstOrNull { it.exists() } ?: File(modelsDir(vm), name)

    fun filesDir(vm: String) = File(base, "$vm/files").apply { mkdirs() }

    /** Déplace un modèle vers un autre volume (copie avec progression, puis suppression de l'original). */
    fun moveModel(vm: String, name: String, toId: String, shouldStop: () -> Boolean, onProgress: (Int) -> Unit) {
        val vol = volumes().firstOrNull { it.id == toId } ?: throw RuntimeException("volume introuvable")
        val src = modelFile(vm, name)
        if (!src.exists()) throw RuntimeException("fichier absent")
        if (volumeIdOf(src) == toId) return
        val dir = File(vol.vmsDir, "$vm/models").apply { mkdirs() }
        if (src.length() > vol.free - 100_000_000L) {
            throw RuntimeException("pas assez de place sur ${vol.label} (${formatSize(vol.free)} libres)")
        }
        val part = File(dir, "$name.part")
        val dest = File(dir, name)
        try {
            src.inputStream().use { i ->
                part.outputStream().buffered(1 shl 16).use { o ->
                    val buf = ByteArray(1 shl 16)
                    var done = 0L
                    var last = -1
                    val total = src.length().coerceAtLeast(1L)
                    while (true) {
                        if (shouldStop()) throw DownloadCancelled()
                        val n = i.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        done += n
                        val p = (done * 100 / total).toInt()
                        if (p != last) { last = p; onProgress(p) }
                    }
                }
            }
        } catch (e: Exception) {
            part.delete()
            throw e
        }
        dest.delete()
        if (!part.renameTo(dest)) { part.copyTo(dest, true); part.delete() }
        src.delete()
    }
    val nativeDir: String = ctx.applicationInfo.nativeLibraryDir
    private var chatProc: Process? = null
    @Volatile private var sdProc: Process? = null
    private val port = 8080

    fun list(): List<String> =
        base.listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted() ?: emptyList()

    fun create(name: String) {
        File(base, "$name/models").mkdirs()
        File(base, "$name/out").mkdirs()
        File(base, "$name/rootfs").mkdirs()
    }

    fun delete(name: String) {
        stopChat()
        volumes().forEach { File(it.vmsDir, name).deleteRecursively() }
    }
    fun modelsDir(vm: String) = File(target().vmsDir, "$vm/models").apply { mkdirs() }
    fun outDir(vm: String) = File(base, "$vm/out").apply { mkdirs() }
    fun sessionsDir(vm: String) = File(base, "$vm/sessions").apply { mkdirs() }
    fun freeSpace(): Long = target().free

    fun installed(vm: String): Set<String> =
        allModelFiles(vm).filter { !it.name.endsWith(".part") }.map { it.name }.toSet()

    /** Images générées (les fichiers commençant par « _ » sont des fichiers de travail). */
    fun gallery(vm: String): List<File> =
        outDir(vm).listFiles { f -> f.extension == "png" && !f.name.startsWith("_") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun engineStatus(): String {
        fun has(n: String) = File(nativeDir, n).exists()
        return "Chat : ${if (has("libllama_server.so")) "OK" else "absent"} · " +
            "Images : ${if (has("libsd.so")) "OK" else "absent"}"
    }

    // ---------- Vérification des fichiers et des moteurs ----------

    /** Vérifie qu'un fichier de modèle est complet et lisible. */
    fun verifyModel(f: File): Pair<Boolean, String> {
        if (!f.exists()) return false to "fichier absent"
        if (f.length() < 1_000_000L) return false to "fichier trop petit (${f.length()} octets), téléchargement raté"
        if (f.name.endsWith(".gguf", true)) {
            val head = ByteArray(8)
            val n = f.inputStream().use { it.read(head) }
            if (n < 8 || String(head, 0, 4, Charsets.US_ASCII) != "GGUF") {
                return false to "en-tête GGUF invalide : fichier corrompu ou ce n'est pas un modèle GGUF"
            }
            val ver = (head[4].toInt() and 0xFF) or ((head[5].toInt() and 0xFF) shl 8)
            if (ver < 2 || ver > 3) return false to "version GGUF inattendue ($ver)"
            return true to "GGUF v$ver valide · reconnu comme ${Gguf.inspect(f).label}"
        }
        return true to "format .${f.extension} : non vérifiable, supposé correct"
    }

    /** Lance un moteur une seconde pour voir s'il démarre sur ce téléphone. */
    fun probe(exe: File, arg: String): String {
        if (!exe.exists()) return "✗ absent de l'APK"
        return try {
            val pb = ProcessBuilder(exe.path, arg).redirectErrorStream(true)
            pb.environment()["TMPDIR"] = ctx.cacheDir.path
            val p = pb.start()
            val out = StringBuilder()
            val t = Thread {
                try {
                    p.inputStream.bufferedReader().forEachLine { if (out.length < 300) out.append(it.take(100)).append(' ') }
                } catch (e: Exception) {}
            }
            t.start()
            if (!p.waitFor(8, TimeUnit.SECONDS)) {
                p.destroy()
                "✓ démarre"
            } else {
                t.join(500)
                val code = p.exitValue()
                if (code == 0 || code == 1) "✓ démarre ${out.toString().trim().take(70)}" else "✗ ${explainExit(code)}"
            }
        } catch (e: Exception) {
            "✗ impossible à lancer : ${e.message}"
        }
    }

    fun explainExit(code: Int): String = when (code) {
        137 -> "tué par Android : mémoire insuffisante (code 137)"
        132 -> "ton processeur ne gère pas une instruction utilisée par le moteur (code 132)"
        139 -> "le moteur a planté (code 139)"
        134 -> "le moteur s'est interrompu sur une erreur (code 134)"
        126, 127 -> "Android refuse de lancer le moteur (code $code)"
        else -> "le moteur s'est arrêté (code $code)"
    }

    private fun builder(cmd: List<String>): ProcessBuilder =
        ProcessBuilder(cmd).redirectErrorStream(true).apply {
            environment()["TMPDIR"] = ctx.cacheDir.path
        }

    // ---------- Chat (llama.cpp) ----------

    suspend fun startChat(model: File, threads: Int, ctxSize: Int) = withContext(Dispatchers.IO) {
        val old = chatProc
        stopChat()
        // Attend la fin de l'ancien moteur : sinon il peut encore occuper le port.
        old?.let { if (!it.waitFor(5, TimeUnit.SECONDS)) it.destroyForcibly().waitFor() }
        val exe = File(nativeDir, "libllama_server.so")
        if (!exe.exists()) throw RuntimeException("Moteur de chat absent de l'APK (voir le workflow GitHub)")
        chatProc = builder(
            listOf(
                exe.path, "-m", model.path, "--host", "127.0.0.1", "--port", "$port",
                "-c", "$ctxSize", "-t", "$threads", "--parallel", "1",
            ),
        ).start()
        val p = chatProc!!
        val tail = ConcurrentLinkedDeque<String>()
        Thread {
            try {
                p.inputStream.bufferedReader().forEachLine { l ->
                    tail.add(l.takeLast(160))
                    while (tail.size > 12) tail.pollFirst()
                }
            } catch (e: Exception) {}
        }.start()
        repeat(240) {
            if (!p.isAlive) {
                delay(300)
                val code = try { p.exitValue() } catch (e: Exception) { -1 }
                throw RuntimeException("Le moteur de chat a échoué : " + explainExit(code) + "\n" + tail.toList().takeLast(6).joinToString("\n"))
            }
            try {
                val c = URL("http://127.0.0.1:$port/health").openConnection() as HttpURLConnection
                c.connectTimeout = 500
                c.readTimeout = 500
                if (c.responseCode == 200) return@withContext
            } catch (e: Exception) {}
            delay(500)
        }
        throw RuntimeException("Le moteur de chat ne répond pas")
    }

    fun chatRunning() = chatProc?.isAlive == true

    fun stopImage() {
        sdProc?.destroy()
        sdProc = null
    }

    /** Fichiers complémentaires installés (vae, clip_l, clip_g, t5xxl) rangés par rôle. */
    fun companions(vm: String): Map<String, File> {
        val m = LinkedHashMap<String, File>()
        allModelFiles(vm).filter { !it.name.endsWith(".part") }.sortedBy { it.name }.forEach { f ->
            val role = Gguf.companionRole(f.name)
            if (role != null && role !in m) m[role] = f
        }
        return m
    }

    /** Copie un fichier choisi sur le téléphone dans les modèles de la machine. */
    fun importStream(
        vm: String, name: String, input: java.io.InputStream, total: Long, shouldStop: () -> Boolean, onProgress: (Int) -> Unit,
    ): File {
        val dest = File(modelsDir(vm), name)
        val part = File(modelsDir(vm), "$name.part")
        if (total > 0 && total > freeSpace() - 200_000_000L) {
            throw RuntimeException("pas assez de place (${formatSize(total)} nécessaires, ${formatSize(freeSpace())} libres)")
        }
        try {
            input.use { i ->
                part.outputStream().buffered(1 shl 16).use { o ->
                    val buf = ByteArray(1 shl 16)
                    var done = 0L
                    var lastPct = -1
                    while (true) {
                        if (shouldStop()) throw DownloadCancelled()
                        val n = i.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt().coerceIn(0, 100)
                            if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            part.delete()
            throw e
        }
        dest.delete()
        if (!part.renameTo(dest)) { part.copyTo(dest, true); part.delete() }
        return dest
    }

    fun stopChat() {
        chatProc?.destroy()
        chatProc = null
    }

    /**
     * Discussion en direct (streaming). onText reçoit le texte complet à chaque nouveau morceau
     * (la réflexion éventuelle est placée dans un bloc <think>…</think>).
     */
    suspend fun chat(
        history: List<Pair<String, String>>, system: String, maxTokens: Int, temperature: Double,
        jsonMode: Boolean = false,
        onText: ((String) -> Unit)? = null,
    ): String = withContext(Dispatchers.IO) {
        val msgs = JSONArray()
        msgs.put(JSONObject().put("role", "system").put("content", system))
        history.forEach { (r, c) ->
            msgs.put(JSONObject().put("role", r).put("content", if (r == "assistant") stripThink(c) else c))
        }
        val body = JSONObject().put("messages", msgs).put("temperature", temperature)
            .put("max_tokens", maxTokens).put("stream", true)
        if (jsonMode) body.put("response_format", JSONObject().put("type", "json_object"))
        val c = URL("http://127.0.0.1:$port/v1/chat/completions").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 5000
        c.readTimeout = 600000
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        if (c.responseCode !in 200..299) {
            val err = c.errorStream?.bufferedReader()?.readText().orEmpty()
            throw RuntimeException("Erreur moteur : ${err.take(300)}")
        }
        val think = StringBuilder()
        val ans = StringBuilder()
        fun combined(): String = when {
            think.isEmpty() -> ans.toString()
            ans.isEmpty() -> "<think>$think"
            else -> "<think>$think</think>\n$ans"
        }
        var last = 0L
        c.inputStream.bufferedReader().use { r ->
            while (true) {
                val line = r.readLine() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break
                if (data.isEmpty()) continue
                val choices = try { JSONObject(data).getJSONArray("choices") } catch (e: Exception) { continue }
                if (choices.length() == 0) continue
                val d = choices.getJSONObject(0).optJSONObject("delta") ?: continue
                if (!d.isNull("reasoning_content")) think.append(d.getString("reasoning_content"))
                if (!d.isNull("content")) ans.append(d.getString("content"))
                val now = System.currentTimeMillis()
                if (onText != null && now - last > 60) {
                    last = now
                    onText(combined())
                }
            }
        }
        val result = combined().trim()
        if (result.isBlank()) throw RuntimeException("Réponse vide : relance, ou essaie un autre modèle")
        onText?.invoke(result)
        result
    }

    // ---------- Conversations sauvegardées ----------

    fun saveChat(vm: String, key: String, msgs: List<Pair<String, String>>) {
        try {
            val arr = JSONArray()
            msgs.forEach { arr.put(JSONObject().put("r", it.first).put("c", it.second)) }
            File(base, "$vm/chat_$key.json").writeText(arr.toString())
        } catch (e: Exception) {}
    }

    fun loadChat(vm: String, key: String): List<Pair<String, String>> {
        val f = File(base, "$vm/chat_$key.json")
        if (!f.exists()) return emptyList()
        return try {
            val a = JSONArray(f.readText())
            (0 until a.length()).map { val o = a.getJSONObject(it); o.getString("r") to o.getString("c") }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ---------- Images (stable-diffusion.cpp) : création et modification ----------

    suspend fun generateImage(
        vm: String, model: ModelEntry, prompt: String, negative: String, size: Int, threads: Int,
        baseImage: File?, strength: Float, onLog: (String) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val exe = File(nativeDir, "libsd.so")
        if (!exe.exists()) throw RuntimeException("Moteur d'images absent de l'APK (voir le workflow GitHub)")
        val out = File(outDir(vm), "img_${System.currentTimeMillis()}.png")
        val mf = modelFile(vm, model.file)
        val info = Gguf.inspect(mf)
        val cmd = mutableListOf(exe.path)
        var steps = model.steps
        var cfg = model.cfg
        val kontext = mf.name.contains("kontext", true)
        val needs = Gguf.requiredCompanions(info.arch, mf.name)
        if (info.needsCompanions || needs.isNotEmpty()) {
            val comps = companions(vm)
            val missing = needs.filter { it !in comps }
            if (missing.isNotEmpty()) {
                throw RuntimeException(
                    "Ce modèle d'images (${info.arch.ifEmpty { mf.name }}) a besoin de fichiers complémentaires qui manquent : " +
                        missing.joinToString() + ". Installe-les avec « Installer depuis un fichier » (Modèles › Installés).",
                )
            }
            cmd += listOf("--diffusion-model", mf.path)
            comps["vae"]?.let { cmd += listOf("--vae", it.path) }
            comps["clip_l"]?.let { cmd += listOf("--clip_l", it.path) }
            comps["clip_g"]?.let { cmd += listOf("--clip_g", it.path) }
            comps["t5xxl"]?.let { cmd += listOf("--t5xxl", it.path) }
            cfg = if (info.arch.contains("sd3")) 4.5 else 1.0
            steps = if (mf.name.contains("schnell", true)) 4 else 20
        } else {
            cmd += listOf("-m", mf.path)
        }
        cmd += listOf(
            "-p", prompt, "-o", out.path, "--steps", "$steps", "--cfg-scale", "$cfg",
            "-W", "$size", "-H", "$size", "-t", "$threads", "--seed", "-1",
        )
        if (negative.isNotBlank() && cfg > 1.0) { cmd += listOf("-n", negative) }
        if (baseImage != null) {
            // Modification d'image : l'image de départ est transformée selon la description.
            if (kontext) cmd += listOf("-r", baseImage.path, "--guidance", "2.5", "--sampling-method", "euler")
            else cmd += listOf("-i", baseImage.path, "--strength", "$strength")
        }
        fun runSd(args: List<String>): Int {
            val p = builder(args).start()
            sdProc = p
            p.inputStream.bufferedReader().forEachLine { onLog(it.takeLast(120)) }
            val c = p.waitFor()
            sdProc = null
            return c
        }
        var code = -1
        if (baseImage != null && !kontext) {
            // Les anciennes versions de stable-diffusion.cpp exigent -M img2img, les récentes le refusent éventuellement.
            code = runSd(cmd + listOf("-M", "img2img"))
            if (code != 0 || !out.exists()) {
                onLog("Nouvel essai sans l'option de mode…")
                code = runSd(cmd)
            }
        } else {
            code = runSd(cmd)
        }
        if (code != 0 || !out.exists()) throw RuntimeException("Échec de la génération (${explainExit(code)}). Essaie une taille plus petite.")
        out
    }

    /** Copie l'image dans la galerie Photos du téléphone (Android 10+). */
    fun exportToPhotos(f: File): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        val v = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, f.name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Studo")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: return false
        ctx.contentResolver.openOutputStream(uri)?.use { o -> f.inputStream().use { it.copyTo(o) } }
        return true
    }

    // ---------- Projets de code (logiciels générés) ----------

    fun projectDir(vm: String, slug: String) = File(base, "$vm/projects/$slug")

    fun projects(vm: String): List<String> =
        File(base, "$vm/projects").listFiles { f -> f.isDirectory }?.map { it.name }?.sorted() ?: emptyList()

    fun projectFiles(dir: File): List<String> =
        dir.walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).path }.sorted().toList()

    fun writeProjectFile(dir: File, path: String, content: String) {
        val clean = path.trim().trimStart('/', '\\').replace("\\", "/")
        if (clean.isEmpty() || clean.split("/").any { it == ".." }) throw RuntimeException("Chemin refusé : $path")
        val f = File(dir, clean)
        f.parentFile?.mkdirs()
        f.writeText(content)
    }

    fun readProjectFile(dir: File, path: String): String? =
        File(dir, path).takeIf { it.isFile }?.readText()

    fun zipProject(dir: File): File {
        val zip = File(ctx.cacheDir, "${dir.name}.zip")
        ZipOutputStream(zip.outputStream().buffered()).use { z ->
            projectFiles(dir).forEach { rel ->
                z.putNextEntry(ZipEntry(rel))
                File(dir, rel).inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
        }
        return zip
    }

    /** Enregistre un texte (ex. une session en .md) dans Téléchargements › Studo et renvoie où le trouver. */
    fun exportText(fileName: String, content: String, mime: String = "text/markdown"): String {
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/Studo")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
                ?: throw RuntimeException("Export impossible")
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            return "Téléchargements › Studo › $fileName"
        }
        val d = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val dest = File(d, fileName)
        dest.writeBytes(bytes)
        return dest.path
    }

    /** Enregistre le .zip dans Téléchargements (Android 10+) et renvoie où le trouver. */
    fun exportZip(dir: File): String {
        val zip = zipProject(dir)
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, zip.name)
                put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/Studo")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
                ?: throw RuntimeException("Export impossible")
            ctx.contentResolver.openOutputStream(uri)?.use { o -> zip.inputStream().use { it.copyTo(o) } }
            return "Téléchargements › Studo › ${zip.name}"
        }
        val d = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val dest = File(d, zip.name)
        zip.copyTo(dest, true)
        return dest.path
    }
}
