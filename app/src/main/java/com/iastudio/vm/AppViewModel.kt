package com.iastudio.vm

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

enum class ThemeMode(val label: String) {
    SYSTEM("Système"), LIGHT("Clair"), DARK("Sombre"),
}

class ProjFile(val path: String, val role: String, status: String = "en attente") {
    var status by mutableStateOf(status)
}

/** Un fichier de modèle présent sur le téléphone, avec son type détecté et le résultat de sa vérification. */
class InstalledModel(val name: String, val size: Long, kind0: String, val place: String = "Mémoire interne", val volumeId: String = "internal") {
    var kind by mutableStateOf(kind0) // chat | image | companion | projector | unknown
    var detail by mutableStateOf("")
    var arch by mutableStateOf("")
    var ok by mutableStateOf<Boolean?>(null)
    var check by mutableStateOf("")
    val image: Boolean get() = kind == "image"
}

private const val CHAT_SYSTEM =
    "Tu es Studo, un assistant utile, chaleureux et honnête qui tourne sur le téléphone de l'utilisateur. " +
        "Réponds en français, de façon claire et concise. Pour du code, donne du code complet et correct dans des blocs " +
        "```langage, puis explique brièvement. Les fichiers joints te sont fournis dans le message. Si tu ne sais pas, dis-le."

private const val CLASSIFY_SYSTEM =
    "Tu classes la demande de l'utilisateur. Réponds par UN SEUL mot : LOGICIEL (créer une application, un site, un jeu " +
        "ou un programme complet avec plusieurs fichiers), IMAGE (créer ou modifier une image, une photo, un dessin, un logo) " +
        "TELEPHONE (agir sur le téléphone : alarme, minuteur, appel, SMS, ouvrir une appli, lampe, volume, réglages, calendrier, fichiers) " +
        "ou DISCUSSION (question, explication, aide, conversation, petit bout de code)."

private const val PROMPT_SYSTEM =
    "Tu transformes la demande de l'utilisateur en un prompt anglais détaillé pour Stable Diffusion : sujet, décor, style, " +
        "éclairage, qualité, séparés par des virgules. Si c'est une modification d'une image existante, décris l'image finale voulue. " +
        "Réponds uniquement avec le prompt, sans guillemets."

private const val PLAN_SYSTEM =
    "Tu es un architecte logiciel senior. À partir de la demande, liste les fichiers nécessaires à un projet complet " +
        "et fonctionnel : point d'entrée, code, configuration, et un README avec les instructions pour lancer le logiciel. " +
        "Réponds UNIQUEMENT par une liste, un fichier par ligne, au format : chemin/fichier.ext | rôle en une phrase. " +
        "Maximum 12 fichiers. Choisis la technologie la plus simple qui répond à la demande " +
        "(par défaut : une page web HTML/CSS/JS qui fonctionne en ouvrant index.html, ou un script Python)."

private const val FILE_SYSTEM =
    "Tu es un développeur expert. Tu écris un fichier complet, correct et prêt à l'emploi, cohérent avec les autres " +
        "fichiers du projet (mêmes noms de fonctions, d'identifiants, d'imports et de fichiers). Aucun texte avant ou " +
        "après : uniquement le contenu du fichier dans un seul bloc de code. Jamais de « ... » ni de TODO : tout doit être écrit."

private const val REVIEW_SYSTEM =
    "Tu es un relecteur de code strict. Corrige les erreurs de syntaxe, les imports manquants, les noms incohérents " +
        "et les bugs évidents. Renvoie le fichier COMPLET corrigé dans un seul bloc de code, sans commentaire autour."

private const val SELECT_SYSTEM =
    "Tu es un lead développeur. Selon la demande de modification, liste les chemins des fichiers à modifier ou à créer, " +
        "un par ligne, sans aucun autre texte."

private const val MODIFY_SYSTEM =
    "Tu es un développeur expert. Applique la demande au fichier et renvoie le fichier COMPLET modifié dans un seul " +
        "bloc de code, sans autre texte."

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val vmm = VmManager(app)
    private val store = SessionStore { vmm.sessionsDir(it) }
    private val prefs = app.getSharedPreferences("prefs", Context.MODE_PRIVATE)
    val catalog = Catalog.load(app)

    val ramGb: Double = run {
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        mi.totalMem / 1e9
    }

    fun ramAdvice(): String = when {
        ramGb < 4.5 -> "modèles de 0,5 à 1,5 milliard de paramètres"
        ramGb < 7 -> "modèles jusqu'à 3-4 milliards de paramètres"
        ramGb < 10 -> "modèles jusqu'à 4 milliards, 7B possible mais lent"
        else -> "modèles jusqu'à 7-8 milliards de paramètres"
    }

    // ---------- Réglages (sauvegardés) ----------
    var themeMode by mutableStateOf(
        ThemeMode.values().firstOrNull { it.name == prefs.getString("theme", "SYSTEM") } ?: ThemeMode.SYSTEM,
    )
    var temperature by mutableStateOf(prefs.getFloat("temp", 0.6f))
    var ctxSize by mutableStateOf(prefs.getInt("ctx", if (ramGb < 6) 2048 else if (ramGb < 10) 4096 else 8192))
    var threads by mutableStateOf(
        prefs.getInt("threads", (Runtime.getRuntime().availableProcessors() - 1).coerceIn(2, 8)),
    )
    var maxTokens by mutableStateOf(prefs.getInt("maxtok", 1024))
    var customPrompt by mutableStateOf(prefs.getString("custom", "") ?: "")
    var hfToken by mutableStateOf(prefs.getString("hf_token", "") ?: "")
    var autoReview by mutableStateOf(prefs.getBoolean("review", false))
    var promptBoost by mutableStateOf(prefs.getBoolean("boost", true))
    var autoResume by mutableStateOf(prefs.getBoolean("auto_resume", true))
    fun updateAutoResume(v: Boolean) { autoResume = v; prefs.edit().putBoolean("auto_resume", v).apply() }
    var persona by mutableStateOf(prefs.getString("persona", "") ?: "")
    fun updatePersona(v: String) { persona = v; prefs.edit().putString("persona", v).apply() }
    var autoRead by mutableStateOf(prefs.getBoolean("auto_read", false))
    var autoLow by mutableStateOf(prefs.getBoolean("auto_low", false))
    fun updateAutoRead(v: Boolean) { autoRead = v; prefs.edit().putBoolean("auto_read", v).apply() }
    fun updateAutoLow(v: Boolean) { autoLow = v; prefs.edit().putBoolean("auto_low", v).apply() }
    var imageSize by mutableStateOf(prefs.getInt("imgsize", 512))
    var strength by mutableStateOf(prefs.getFloat("strength", 0.6f))
    var chatPick by mutableStateOf(prefs.getString("pick_chat", "") ?: "")
    var imagePick by mutableStateOf(prefs.getString("pick_image", "") ?: "")

    fun updateTheme(m: ThemeMode) { themeMode = m; prefs.edit().putString("theme", m.name).apply() }
    fun updateTemperature(v: Float) { temperature = v; prefs.edit().putFloat("temp", v).apply() }
    fun updateCtx(v: Int) { ctxSize = v; prefs.edit().putInt("ctx", v).apply() }
    fun updateThreads(v: Int) { threads = v; prefs.edit().putInt("threads", v).apply() }
    fun updateMaxTokens(v: Int) { maxTokens = v; prefs.edit().putInt("maxtok", v).apply() }
    fun updateCustomPrompt(v: String) { customPrompt = v; prefs.edit().putString("custom", v).apply() }
    fun updateToken(v: String) { hfToken = v; prefs.edit().putString("hf_token", v).apply() }
    fun updateReview(v: Boolean) { autoReview = v; prefs.edit().putBoolean("review", v).apply() }
    fun updateBoost(v: Boolean) { promptBoost = v; prefs.edit().putBoolean("boost", v).apply() }
    fun updateImageSize(v: Int) { imageSize = v; prefs.edit().putInt("imgsize", v).apply() }
    fun updateStrength(v: Float) { strength = v; prefs.edit().putFloat("strength", v).apply() }
    fun pickChatModel(id: String) { chatPick = id; prefs.edit().putString("pick_chat", id).apply() }
    fun pickImageModel(id: String) { imagePick = id; prefs.edit().putString("pick_image", id).apply() }

    // ---------- Machines et modèles ----------
    val machines = mutableStateListOf<String>()
    var current by mutableStateOf<String?>(null)
    var installed by mutableStateOf<Set<String>>(emptySet())
    val models = mutableStateListOf<InstalledModel>()
    val partials = mutableStateListOf<Pair<String, Long>>()
    val volumes = mutableStateListOf<VmManager.Volume>()
    var storageId by mutableStateOf(prefs.getString("storage", "internal") ?: "internal")
    val progress = mutableStateMapOf<String, Int>()
    val dlNames = mutableStateMapOf<String, String>()
    var status by mutableStateOf("")
    var engines by mutableStateOf(vmm.engineStatus())
    val linkStatus = mutableStateMapOf<String, String>()
    var checkingLinks by mutableStateOf(false)
    private val stopSet = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val kindCache = ConcurrentHashMap<String, ModelInfo>()

    /** Catalogue + modèles installés par lien, fichier, Hugging Face ou navigateur (type détecté dans l'en-tête). */
    val allModels: List<ModelEntry>
        get() {
            val known = catalog.map { it.file }.toSet()
            val extra = models.toList().filter { it.name !in known && (it.kind == "chat" || it.kind == "image") }.map { m ->
                if (m.kind == "image") {
                    val fast = m.name.contains("turbo", true) || m.name.contains("lcm", true) ||
                        m.name.contains("sdxs", true) || m.name.contains("schnell", true)
                    ModelEntry("custom-${m.name}", "image", displayName(m.name), m.name, "", if (fast) 4 else 20, if (fast) 1.0 else 7.0)
                } else {
                    ModelEntry("custom-${m.name}", "chat", m.name, m.name, "")
                }
            }
            return catalog + extra
        }

    val chatModels: List<ModelEntry>
        get() = allModels.filter { it.type == "chat" && it.file in installed && isKind(it.file, "chat") }
    val imageModels: List<ModelEntry>
        get() = allModels.filter { it.type == "image" && it.file in installed && isKind(it.file, "image") }

    /** Le type détecté (ou choisi à la main) l'emporte sur le catalogue. */
    private fun isKind(file: String, want: String): Boolean {
        val m = models.firstOrNull { it.name == file } ?: return true
        return m.kind == want || m.kind == "unknown"
    }

    private fun fits(m: ModelEntry) = m.sizeGb <= 0.0 || m.sizeGb < ramGb * 0.55

    /** Modèle de chat choisi, ou (Auto) un modèle adapté : un « coder » pour les logiciels. */
    fun pickChat(forCode: Boolean): ModelEntry? {
        val list = chatModels
        if (list.isEmpty()) return null
        list.firstOrNull { it.id == chatPick }?.let { return it }
        val ok = list.filter { fits(it) }.ifEmpty { list }
        val coders = ok.filter { it.name.contains("coder", true) }
        return if (forCode) {
            coders.maxByOrNull { it.sizeGb } ?: ok.maxByOrNull { it.sizeGb } ?: ok.first()
        } else {
            ok.filter { !it.name.contains("coder", true) }.maxByOrNull { it.sizeGb } ?: ok.first()
        }
    }

    fun pickImage(): ModelEntry? {
        val list = imageModels
        if (list.isEmpty()) return null
        list.firstOrNull { it.id == imagePick }?.let { return it }
        return list.firstOrNull { it.steps <= 4 } ?: list.first()
    }

    // ---------- Conversation (écran unique) ----------
    val messages = mutableStateListOf<Msg>()
    val pending = mutableStateListOf<Att>()
    var forced by mutableStateOf<Intent?>(null) // null = Auto : l'IA choisit
    var busy by mutableStateOf(false)
    var liveIdx by mutableIntStateOf(-1)
    private var runningModel: String? = null
    private var chatCtx = 0
    private var job: Job? = null
    private var stopped = false

    // Sessions (historique)
    val sessions = mutableStateListOf<SessionMeta>()
    var sessionId by mutableStateOf<String?>(null)
    private var sessionTitle = ""
    private var sessionStar = false

    // Vue en direct d'un logiciel en cours de création
    var projectName by mutableStateOf("")
    var projLog by mutableStateOf("")
    val projFiles = mutableStateListOf<ProjFile>()
    var liveFile by mutableStateOf("")
    var liveText by mutableStateOf("")

    // Vue en direct d'une image en cours de création
    var imageLog by mutableStateOf("")
    var imageStep by mutableStateOf(0f)
    var livePrompt by mutableStateOf("")
    val negative = "blurry, low quality, deformed, watermark"
    val gallery = mutableStateListOf<File>()

    // ---------- Hugging Face ----------
    var hfType by mutableStateOf("chat")
    var hfQuery by mutableStateOf("")
    val hfRepos = mutableStateListOf<HfRepo>()
    var hfSelected by mutableStateOf<String?>(null)
    val hfFiles = mutableStateListOf<HfFile>()
    var hfLoading by mutableStateOf(false)
    var hfError by mutableStateOf<String?>(null)
    var webUrl by mutableStateOf("")
    val bookmarks = mutableStateListOf<Pair<String, String>>().also { l ->
        try {
            val arr = org.json.JSONArray(prefs.getString("bookmarks", null) ?: "[]")
            for (i in 0 until arr.length()) { val o = arr.getJSONObject(i); l.add(o.getString("t") to o.getString("u")) }
        } catch (e: Exception) {
        }
        if (l.isEmpty()) {
            l.add("Hugging Face : modèles GGUF" to HF_HOME)
            l.add("Hugging Face : modèles d'images" to "https://huggingface.co/models?pipeline_tag=text-to-image&sort=downloads")
            l.add("DuckDuckGo" to "https://duckduckgo.com/")
            l.add("Wikipédia" to "https://fr.wikipedia.org/")
            l.add("GitHub" to "https://github.com/")
        }
    }

    private fun saveBookmarks() {
        val arr = org.json.JSONArray()
        bookmarks.forEach { arr.put(org.json.JSONObject().put("t", it.first).put("u", it.second)) }
        prefs.edit().putString("bookmarks", arr.toString()).apply()
    }

    fun addBookmark(title: String, url: String) {
        if (bookmarks.none { it.second == url }) { bookmarks.add(title to url); saveBookmarks() }
    }

    fun removeBookmark(url: String) { bookmarks.removeAll { it.second == url }; saveBookmarks() }

    // ---------- Diagnostic ----------
    val report = mutableStateListOf<String>()
    var diagBusy by mutableStateOf(false)

    init {
        vmm.targetVolume = storageId
        // Service au premier plan : l'appli reste en vie pendant les téléchargements et générations
        viewModelScope.launch {
            snapshotFlow {
                when {
                    progress.isNotEmpty() -> {
                        val k = progress.keys.first()
                        "download|" + (dlNames[k] ?: "modèle").take(40) + " · " + (progress[k] ?: 0) + " %" +
                            (if (progress.size > 1) " (+${progress.size - 1})" else "")
                    }
                    busy -> {
                        val t = when (messages.lastOrNull()?.kind) {
                            "image" -> "image"
                            "project" -> "project"
                            "action" -> "phone"
                            else -> "chat"
                        }
                        "$t|L'IA travaille…"
                    }
                    else -> ""
                }
            }.distinctUntilChanged().collect { t ->
                KeepAliveService.update(getApplication<Application>(), t.isNotEmpty(), t.substringAfter('|'), t.substringBefore('|', "chat"))
            }
        }
        if (autoResume) {
            viewModelScope.launch {
                kotlinx.coroutines.delay(2500)
                resumeAll()
            }
        }
        refreshVolumes()
        refresh()
        if (machines.isEmpty()) createMachine("principale")
    }

    private fun refresh() {
        machines.clear()
        machines.addAll(vmm.list())
        if (current == null || current !in machines) current = machines.firstOrNull()
        val vm = current
        gallery.clear()
        messages.clear()
        pending.clear()
        sessionId = null
        sessionTitle = ""
        sessionStar = false
        projectName = ""
        projFiles.clear()
        if (vm != null) gallery.addAll(vmm.gallery(vm))
        reloadModels()
        reloadSessions()
    }

    fun reloadModels() {
        refreshVolumes()
        val vm = current
        if (vm == null) {
            installed = emptySet()
            models.clear()
            partials.clear()
            return
        }
        installed = vmm.installed(vm)
        val files = vmm.allModelFiles(vm)
        val old = models.associateBy { it.name }
        models.clear()
        files.filter { !it.name.endsWith(".part") }.sortedBy { it.name }.forEach { f ->
            val key = "${f.name}:${f.length()}"
            val over = prefs.getString("kind_${f.name}", null)
            val cached = kindCache[key]
            val prev = old[f.name]
            val m = if (prev != null && prev.size == f.length() && prev.volumeId == vmm.volumeIdOf(f)) prev
            else InstalledModel(f.name, f.length(), over ?: cached?.kind ?: Gguf.quickGuess(f.name), vmm.volumeLabelOf(f), vmm.volumeIdOf(f)).also {
                if (cached != null) { it.arch = cached.arch; it.detail = cached.label }
            }
            models.add(m)
            if (cached == null) {
                viewModelScope.launch(Dispatchers.IO) {
                    val info = Gguf.inspect(f)
                    kindCache[key] = info
                    withContext(Dispatchers.Main) {
                        m.arch = info.arch
                        m.detail = info.label
                        if (prefs.getString("kind_${f.name}", null) == null) m.kind = info.kind
                    }
                }
            }
        }
        partials.clear()
        files.filter { it.name.endsWith(".part") }.forEach { partials.add(it.name to it.length()) }
    }

    /** Corrige à la main le type d'un fichier (chat, image, complémentaire). */
    fun setKind(m: InstalledModel, kind: String) {
        prefs.edit().putString("kind_${m.name}", kind).apply()
        m.kind = kind
    }

    /** Fichiers complémentaires encore absents pour une IA d'images qui en exige. */
    fun missingCompanions(m: InstalledModel): List<String> {
        if (m.kind != "image") return emptyList()
        val need = Gguf.requiredCompanions(m.arch, m.name)
        if (need.isEmpty()) return emptyList()
        val have = models.filter { it.kind == "companion" || Gguf.companionRole(it.name) != null }
            .mapNotNull { Gguf.companionRole(it.name) }.toSet()
        return need.filter { it !in have }
    }

    fun diskFree(): Long = vmm.freeSpace()

    fun refreshVolumes() {
        volumes.clear()
        volumes.addAll(vmm.volumes())
        if (volumes.none { it.id == storageId }) { storageId = "internal"; vmm.targetVolume = "internal" }
    }

    /** Choisit où s'installent les prochains modèles (mémoire interne ou carte SD). */
    fun chooseStorage(id: String) {
        storageId = id
        vmm.targetVolume = id
        prefs.edit().putString("storage", id).apply()
    }

    /** Déplace un modèle déjà installé vers un autre volume. */
    fun moveModel(m: InstalledModel, toId: String) {
        val vm = current ?: return
        val key = "mv-${m.name}"
        if (progress.containsKey(key)) return
        stopSet.remove(key)
        stopEngineIfRunning()
        dlNames[key] = "Déplacement de ${displayName(m.name)}"
        progress[key] = 0
        viewModelScope.launch(Dispatchers.IO) {
            try {
                vmm.moveModel(vm, m.name, toId, { key in stopSet }) { progress[key] = it }
                status = "${displayName(m.name)} déplacé ✓"
            } catch (e: DownloadCancelled) {
                status = "Déplacement annulé (le fichier d'origine est conservé)"
            } catch (e: Exception) {
                status = "Déplacement impossible : ${e.message}"
            } finally {
                progress.remove(key)
                dlNames.remove(key)
                withContext(Dispatchers.Main) { refreshVolumes(); reloadModels() }
            }
        }
    }

    fun createMachine(name: String) {
        val clean = name.trim().replace(Regex("[^A-Za-z0-9_-]"), "_")
        if (clean.isEmpty()) return
        saveSession()
        vmm.create(clean)
        current = clean
        refresh()
    }

    fun select(name: String) {
        saveSession()
        vmm.stopChat()
        runningModel = null
        current = name
        refresh()
    }

    fun deleteMachine(name: String) {
        vmm.delete(name)
        runningModel = null
        current = null
        refresh()
        if (machines.isEmpty()) createMachine("principale")
    }

    // ---------- Installation, suppression et vérification des modèles ----------

    private fun isOnline(): Boolean {
        val cm = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val c = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return c.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun startDownload(key: String, display: String, url: String, fileName: String) {
        val vm = current ?: return
        if (progress.containsKey(key)) return
        stopSet.remove(key)
        dlNames[key] = display
        progress[key] = 0
        status = ""
        // mémorisé pour pouvoir reprendre en un toucher même après un arrêt de l'appli
        prefs.edit().putString("dl_$fileName", display + "\n" + url).apply()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val dest = File(vmm.modelsDir(vm), fileName)
                var attempt = 0
                while (true) {
                    try {
                        Downloader.download(url, dest, hfToken, { key in stopSet }) { progress[key] = it }
                        break
                    } catch (e: DownloadCancelled) {
                        throw e
                    } catch (e: FatalDownload) {
                        throw e
                    } catch (e: Exception) {
                        attempt++
                        if (attempt > 40) throw RuntimeException("${e.message} (abandon après 40 essais, le morceau téléchargé est conservé : touche Reprendre)")
                        // attend le retour du réseau puis recommence là où ça s'est arrêté
                        val wait = minOf(5 + attempt * 5, 60)
                        var left = wait
                        while (left > 0) {
                            if (key in stopSet) throw DownloadCancelled()
                            status = if (isOnline()) "$display : coupure, reprise dans ${left}s (essai $attempt)…"
                            else "$display : pas de réseau, reprise automatique dès le retour (essai $attempt)…"
                            kotlinx.coroutines.delay(1000)
                            left--
                        }
                    }
                }
                prefs.edit().remove("dl_$fileName").apply()
                val (ok, msg) = vmm.verifyModel(dest)
                status = if (ok) "$display installé ✓ ($msg)" else "⚠ $display téléchargé mais : $msg"
                Notifier.done(getApplication<Application>(), if (ok) "done" else "error", if (ok) "Modèle installé" else "Modèle à vérifier", status)
            } catch (e: DownloadCancelled) {
                status = "Téléchargement en pause. Touche « Reprendre » dans Modèles pour continuer là où il s'est arrêté."
            } catch (e: Exception) {
                status = "Erreur : ${e.message}"
                Notifier.done(getApplication<Application>(), "error", "Téléchargement échoué", "$display : ${e.message}. Touche Reprendre dans Modèles.")
            } finally {
                progress.remove(key)
                dlNames.remove(key)
                withContext(Dispatchers.Main) { reloadModels() }
            }
        }
    }

    /** Reprend un téléchargement interrompu (fichier .part) à partir de l'adresse mémorisée. */
    fun resumePartial(partName: String): Boolean {
        val file = partName.removeSuffix(".part")
        val saved = prefs.getString("dl_$file", null) ?: return false
        val display = saved.substringBefore('\n')
        val url = saved.substringAfter('\n')
        startDownload("resume-$file", display, url, file)
        return true
    }

    fun testTermux() {
        viewModelScope.launch(Dispatchers.IO) {
            status = "Test de Termux…"
            status = try {
                "Termux ✓ : " + TermuxBridge.run(getApplication<Application>(), "echo Studo OK; uname -m", 30)
            } catch (e: Exception) {
                "Termux ✗ : ${e.message}"
            }
        }
    }

    /** Relance tous les téléchargements interrompus dont l'adresse est connue. */
    fun resumeAll(): Int {
        var n = 0
        partials.toList().forEach { (name, _) -> if (canResume(name) && resumePartial(name)) n++ }
        if (n > 0) status = "Reprise de $n téléchargement(s) interrompu(s)…"
        return n
    }

    fun canResume(partName: String) = prefs.getString("dl_${partName.removeSuffix(".part")}", null) != null

    fun cancelDownload(key: String) { stopSet.add(key) }

    fun install(m: ModelEntry) = startDownload(m.id, m.name, m.url, m.file)

    fun hfInstall(id: String, file: String) {
        val flat = file.substringAfterLast('/')
        val stored = if (hfType == "image" && !flat.startsWith("img__")) "img__$flat" else flat
        startDownload("hf-$id/$file", flat, HfBrowser.url(id, file), stored)
    }

    fun installFromUrl(url: String, name: String, image: Boolean) {
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "modele.gguf" }
        val stored = if (image && !safe.startsWith("img__")) "img__$safe" else safe
        startDownload("web-$stored", safe, url, stored)
    }

    /** Installe un ou plusieurs fichiers choisis sur le téléphone (modèles, VAE, encodeurs…). */
    fun importFiles(uris: List<Uri>) {
        val vm = current ?: return
        val app = getApplication<Application>()
        uris.forEach { uri ->
            val raw = Attachments.displayName(app, uri)
            val name = raw.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "modele.gguf" }
            val key = "imp-$name"
            if (progress.containsKey(key)) return@forEach
            stopSet.remove(key)
            dlNames[key] = name
            progress[key] = 0
            val total = Attachments.sizeOf(app, uri)
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val input = app.contentResolver.openInputStream(uri) ?: throw RuntimeException("fichier illisible")
                    val dest = vmm.importStream(vm, name, input, total, { key in stopSet }) { progress[key] = it }
                    val info = Gguf.inspect(dest)
                    status = "$name installé ✓ · reconnu comme ${info.label}"
                } catch (e: DownloadCancelled) {
                    status = "Installation annulée"
                } catch (e: Exception) {
                    status = "Échec de l'installation de $name : ${e.message}"
                } finally {
                    progress.remove(key)
                    dlNames.remove(key)
                    withContext(Dispatchers.Main) { reloadModels() }
                }
            }
        }
    }

    private fun stopEngineIfRunning() {
        if (vmm.chatRunning() || runningModel != null) {
            vmm.stopChat()
            runningModel = null
        }
    }

    fun deleteModel(name: String) {
        val vm = current ?: return
        stopEngineIfRunning()
        vmm.modelFile(vm, name).delete()
        prefs.edit().remove("kind_$name").apply()
        reloadModels()
        status = "${name.removePrefix("img__")} supprimé"
    }

    fun deletePartial(name: String) {
        val vm = current ?: return
        vmm.modelFile(vm, name).delete()
        reloadModels()
    }

    fun verifyOne(m: InstalledModel) {
        val vm = current ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val (ok, msg) = vmm.verifyModel(vmm.modelFile(vm, m.name))
            m.ok = ok
            m.check = msg
        }
    }

    fun verifyAll() { models.toList().forEach { verifyOne(it) } }

    fun checkLinks() {
        if (checkingLinks) return
        checkingLinks = true
        linkStatus.clear()
        viewModelScope.launch(Dispatchers.IO) {
            catalog.forEach { m ->
                linkStatus[m.id] = try {
                    val c = URL(m.url).openConnection() as HttpURLConnection
                    c.connectTimeout = 8000
                    c.readTimeout = 8000
                    c.setRequestProperty("Range", "bytes=0-0")
                    c.setRequestProperty("User-Agent", "Studo-Android")
                    val code = c.responseCode
                    c.disconnect()
                    if (code in 200..299) "ok" else "HTTP $code"
                } catch (e: Exception) {
                    "réseau"
                }
            }
            checkingLinks = false
        }
    }

    // ---------- Navigateur Hugging Face ----------

    fun hfSearch(query: String = hfQuery) {
        hfQuery = query
        hfLoading = true
        hfError = null
        hfSelected = null
        hfFiles.clear()
        viewModelScope.launch {
            try {
                val r = withContext(Dispatchers.IO) { HfBrowser.search(query, hfToken) }
                hfRepos.clear()
                hfRepos.addAll(r)
                if (r.isEmpty()) hfError = "Aucun résultat pour « $query »"
            } catch (e: Exception) {
                hfError = "La recherche a échoué : ${e.message}"
            } finally {
                hfLoading = false
            }
        }
    }

    fun hfOpen(id: String) {
        if (hfSelected == id) { hfSelected = null; hfFiles.clear(); return }
        hfSelected = id
        hfFiles.clear()
        hfLoading = true
        hfError = null
        viewModelScope.launch {
            try {
                val f = withContext(Dispatchers.IO) { HfBrowser.files(id, hfToken, hfType == "image") }
                hfFiles.addAll(f)
                if (f.isEmpty()) hfError = "Aucun fichier .gguf simple dans ce dépôt"
            } catch (e: Exception) {
                hfError = "Impossible de lire ce dépôt : ${e.message}"
            } finally {
                hfLoading = false
            }
        }
    }

    // ---------- Moteur de chat ----------

    private suspend fun ensureChat(vm: String, model: ModelEntry, minCtx: Int = 0) {
        val want = maxOf(ctxSize, minCtx)
        if (vmm.chatRunning() && runningModel == model.id && chatCtx == want) return
        status = "Démarrage de l'IA « ${displayName(model.name).take(30)} »… (30 s à 2 min la première fois)"
        try {
            vmm.startChat(vmm.modelFile(vm, model.file), threads, want)
        } finally {
            status = ""
        }
        runningModel = model.id
        chatCtx = want
    }

    fun stopWork() {
        stopped = true
        job?.cancel()
        vmm.stopChat()
        vmm.stopImage()
        runningModel = null
        chatCtx = 0
        busy = false
        liveIdx = -1
        status = "Arrêté"
    }

    private fun systemPrompt(): String {
        val extra = customPrompt.trim()
        val role = PERSONAS.firstOrNull { it.first == persona }?.second.orEmpty()
        var sys = CHAT_SYSTEM
        if (role.isNotEmpty()) sys += "\n\nPersonnalité à adopter : " + role
        if (extra.isNotEmpty()) sys += "\n\nInstructions de l'utilisateur : " + extra
        return sys
    }

    private fun effCtx() = if (chatCtx > 0) chatCtx else ctxSize
    private fun chatMax() = minOf(maxTokens, effCtx() / 2)

    // ---------- Pièces jointes ----------

    fun attach(uris: List<Uri>) {
        val vm = current ?: return
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            uris.forEach { u ->
                try {
                    val a = Attachments.load(app, u, vmm.filesDir(vm))
                    withContext(Dispatchers.Main) { pending.add(a) }
                } catch (e: Exception) {
                    status = "Fichier refusé : ${e.message}"
                }
            }
        }
    }

    fun removePending(a: Att) { pending.remove(a) }

    /** Reprend une image générée comme pièce jointe, pour la modifier. */
    fun attachImageFile(f: File) {
        pending.add(Att(f.name, "image", imagePath = f.path, size = f.length(), path = f.path))
    }

    /** Ouvre un fichier du téléphone dans la visionneuse, sans l'envoyer à l'IA. */
    fun viewFile(uri: Uri, onLoaded: (Att) -> Unit) {
        val vm = current ?: return
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val a = Attachments.load(app, uri, vmm.filesDir(vm))
                withContext(Dispatchers.Main) { onLoaded(a) }
            } catch (e: Exception) {
                status = "Fichier illisible : ${e.message}"
            }
        }
    }

    /** Joint du texte (ex. une page web) comme un fichier. */
    fun attachText(name: String, text: String) {
        val vm = current ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60).ifEmpty { "page" }
                val f = File(vmm.filesDir(vm), "${System.currentTimeMillis()}_$safe.txt")
                f.writeText(text)
                val a = Attachments.fromFile(f, "$safe.txt")
                withContext(Dispatchers.Main) { pending.add(a) }
            } catch (e: Exception) {
                status = "Impossible de joindre la page : ${e.message}"
            }
        }
    }

    /** Télécharge un fichier depuis le navigateur intégré et le joint à la conversation. */
    fun downloadAndAttach(url: String, name: String) {
        val vm = current ?: return
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "fichier" }
        val key = "web-att-$safe"
        if (progress.containsKey(key)) return
        stopSet.remove(key)
        dlNames[key] = safe
        progress[key] = 0
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val dest = File(vmm.filesDir(vm), "${System.currentTimeMillis()}_$safe")
                Downloader.download(url, dest, "", { key in stopSet }) { progress[key] = it }
                val a = Attachments.fromFile(dest, safe)
                withContext(Dispatchers.Main) { pending.add(a) }
                status = "$safe joint à ta prochaine demande ✓"
            } catch (e: DownloadCancelled) {
                status = "Téléchargement annulé"
            } catch (e: Exception) {
                status = "Téléchargement impossible : ${e.message}"
            } finally {
                progress.remove(key)
                dlNames.remove(key)
            }
        }
    }

    // ---------- Envoi : l'IA choisit discussion, logiciel ou image ----------

    fun send(text: String) {
        val vm = current ?: return
        val t = text.trim()
        if (busy || (t.isEmpty() && pending.isEmpty())) return
        val atts = pending.toList()
        pending.clear()
        messages.add(Msg("user", t, atts))
        busy = true
        stopped = false
        job = viewModelScope.launch {
            try {
                dispatch(vm, t, atts)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                messages.add(Msg("assistant", "⚠ ${e.message}"))
            } finally {
                busy = false
                liveIdx = -1
                notifyFinished()
                saveSession()
            }
        }
    }

    private suspend fun dispatch(vm: String, text: String, atts: List<Att>) {
        val attachedImage = atts.any { it.kind == "image" }
        val lastKind = messages.dropLast(1).lastOrNull { it.kind != "chat" }?.kind
        var intent = forced
        if (intent == null) {
            val (g, sure) = Router.guess(text, attachedImage, lastKind)
            intent = g
            if (!sure && text.isNotBlank()) {
                val m = pickChat(false)
                if (m != null) intent = classifyWithAi(vm, m, text) ?: g
            }
        }
        val route = if (forced == null) "choisi par Studo : ${intent.label.lowercase()}" else ""
        when (intent) {
            Intent.CHAT -> doChat(vm, route)
            Intent.PROJECT -> doProject(vm, text, atts, route)
            Intent.IMAGE -> doImage(vm, text, atts, route)
            Intent.ACTION -> doAction(vm, text, route)
        }
    }

    private suspend fun classifyWithAi(vm: String, model: ModelEntry, text: String): Intent? {
        return try {
            ensureChat(vm, model)
            val r = stripThink(vmm.chat(listOf("user" to text.take(600)), CLASSIFY_SYSTEM, 60, 0.0)).uppercase()
            when {
                r.contains("TELEPHONE") || r.contains("TÉLÉPHONE") -> Intent.ACTION
                r.contains("LOGICIEL") -> Intent.PROJECT
                r.contains("IMAGE") -> Intent.IMAGE
                r.contains("DISCUSSION") -> Intent.CHAT
                else -> null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }


    // ---------- Mode Téléphone : l'IA propose, tu autorises, Studo exécute ----------

    private suspend fun doAction(vm: String, text: String, route: String) {
        val model = pickChat(false) ?: run { noModel(route, false); return }
        messages.add(Msg("assistant", "", kind = "action", extra = "plan", route = route))
        val idx = messages.size - 1
        liveIdx = idx
        try {
            ensureChat(vm, model)
            val raw = vmm.chat(
                listOf("user" to text), Agent.systemPrompt(), 700, 0.1, jsonMode = true,
            ) { }
            val (say, calls) = Agent.parse(raw)
            if (calls.isEmpty()) {
                messages[idx] = messages[idx].copy(kind = "chat", extra = "", text = say.ifBlank { stripThink(raw) })
                return
            }
            messages[idx] = messages[idx].copy(text = say, ref = Agent.encode(calls), extra = "pending")
            val allSafe = calls.all { Agent.risk(it) == 0 && autoRead }
            val allLow = calls.all { Agent.risk(it) <= 1 && autoLow }
            if (allSafe || allLow) runActions(idx)
        } catch (e: Exception) {
            if (idx < messages.size) {
                if (stopped || e is CancellationException) messages.removeAt(idx)
                else messages[idx] = messages[idx].copy(kind = "chat", extra = "", text = "⚠ ${e.message}")
            }
            if (e is CancellationException) throw e
        }
    }

    /** Autorise : exécute les actions de la carte et note le résultat. */
    fun runActions(idx: Int) {
        val m = messages.getOrNull(idx) ?: return
        if (m.kind != "action" || m.extra != "pending") return
        val vm = current ?: return
        val calls = Agent.decode(m.ref)
        messages[idx] = m.copy(extra = "running")
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val report = StringBuilder(m.text)
            var okAll = true
            calls.forEach { c ->
                try {
                    val r = Agent.run(app, c, vmm.filesDir(vm)) { name, content -> vmm.exportText(name, content, "text/plain") }
                    report.append("\n✓ ").append(Agent.describe(c)).append(" → ").append(r)
                } catch (e: Exception) {
                    okAll = false
                    report.append("\n✗ ").append(Agent.describe(c)).append(" → ").append(e.message ?: e.javaClass.simpleName)
                }
            }
            withContext(Dispatchers.Main) {
                val cur = messages.getOrNull(idx)
                if (cur != null && cur.kind == "action") messages[idx] = cur.copy(text = report.toString(), extra = if (okAll) "done" else "failed")
                saveSession()
            }
        }
    }

    fun refuseActions(idx: Int) {
        val m = messages.getOrNull(idx) ?: return
        if (m.kind == "action" && m.extra == "pending") messages[idx] = m.copy(extra = "refused")
        saveSession()
    }


    private fun noModel(route: String, image: Boolean) {
        messages.add(
            Msg(
                "assistant",
                if (image) "Aucune IA d'images n'est installée. Ouvre Modèles (icône en haut) et installe SD-Turbo ou Stable Diffusion 1.5."
                else "Aucune IA de chat n'est installée. Ouvre Modèles (icône en haut) et installe-en une, par exemple Qwen2.5 1.5B.",
                route = route,
            ),
        )
    }

    /** Contenu envoyé à l'IA : le texte tapé + le contenu des fichiers joints (jamais affiché dans le message). */
    private fun userContent(m: Msg): String {
        val sb = StringBuilder(m.text)
        m.atts.forEach { a ->
            when {
                a.kind == "image" -> sb.append("\n\n[Image jointe : ${a.name}. Tu ne peux pas la voir ; l'IA d'images s'en occupe si l'utilisateur veut la modifier.]")
                a.text.isNotBlank() -> sb.append("\n\n[Fichier joint : ${a.name}]\n```\n${a.text}\n```")
                else -> sb.append("\n\n[Fichier joint : ${a.name} : contenu illisible]")
            }
        }
        return sb.toString()
    }

    private fun historyForAi(list: List<Msg>): List<Pair<String, String>> = list.mapNotNull { m ->
        when {
            m.role == "user" -> "user" to userContent(m)
            m.kind == "project" -> "assistant" to "[J'ai créé le logiciel « ${m.ref} ». ${m.text}]"
            m.kind == "image" -> "assistant" to "[J'ai généré une image avec la description : ${m.text}]"
            m.kind == "action" -> "assistant" to "[Actions sur le téléphone (${m.extra}) : ${m.text.take(400)}]"
            m.text.isBlank() || m.text.startsWith("⚠") -> null
            else -> "assistant" to m.text
        }
    }

    /** Garde la fin de la conversation dans la mémoire de l'IA (≈ 3 caractères par jeton). */
    private fun fitHistory(h: List<Pair<String, String>>, budget: Int): List<Pair<String, String>> {
        val out = ArrayDeque<Pair<String, String>>()
        var used = 0
        for (p in h.asReversed()) {
            var c = p.second
            val left = budget - used
            if (left <= 200) break
            if (c.length > left) {
                val head = (left * 0.6).toInt()
                val tail = left - head - 30
                c = c.take(head) + "\n[… texte raccourci …]\n" + c.takeLast(maxOf(tail, 0))
            }
            out.addFirst(p.first to c)
            used += c.length
        }
        if (out.isEmpty() && h.isNotEmpty()) out.add(h.last().first to h.last().second.take(budget.coerceAtLeast(500)))
        return out.toList()
    }

    private suspend fun doChat(vm: String, route: String) {
        val model = pickChat(false) ?: run { noModel(route, false); return }
        messages.add(Msg("assistant", "", route = route))
        val idx = messages.size - 1
        liveIdx = idx
        try {
            ensureChat(vm, model)
            val sys = systemPrompt()
            val budget = ((effCtx() - chatMax() - 200) * 3 - sys.length).coerceAtLeast(1500)
            val history = fitHistory(historyForAi(messages.toList().subList(0, idx)), budget)
            val r = vmm.chat(history, sys, chatMax(), temperature.toDouble()) { t ->
                if (idx < messages.size) messages[idx] = messages[idx].copy(text = t)
            }
            if (idx < messages.size) messages[idx] = messages[idx].copy(text = r)
        } catch (e: Exception) {
            if (idx < messages.size) {
                if (stopped || e is CancellationException) {
                    if (messages[idx].text.isBlank()) messages.removeAt(idx)
                } else {
                    messages[idx] = messages[idx].copy(text = "⚠ ${e.message}")
                }
            }
            status = ""
            if (e is CancellationException) throw e
        }
    }

    private fun notifyFinished() {
        if (stopped) return
        val m = messages.lastOrNull() ?: return
        if (m.role != "assistant") return
        val err = m.text.startsWith("⚠") || m.extra.startsWith("⚠")
        val (type, body) = when {
            err -> "error" to (m.text.ifBlank { m.extra }).take(160)
            m.kind == "image" -> "image" to "Ton image est prête."
            m.kind == "project" -> "project" to "Ton logiciel est prêt."
            m.kind == "action" -> "phone" to "Des actions attendent ton accord."
            else -> "chat" to stripThink(m.text).replace("\n", " ").take(160)
        }
        Notifier.done(getApplication<Application>(), type, if (err) "Un problème est survenu" else "Terminé", body.ifBlank { "Terminé" })
    }

    // ---------- Lecture à voix haute ----------
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    var speaking by mutableStateOf(false)

    fun speak(text: String) {
        if (speaking) { stopSpeak(); return }
        val clean = stripThink(text).replace(Regex("[*#`>_~|]"), "").take(3500)
        if (clean.isBlank()) return
        fun go() {
            val t = tts ?: return
            t.setLanguage(Locale.FRANCE)
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { speaking = false }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { speaking = false }
            })
            speaking = true
            t.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "studo")
        }
        if (tts == null) {
            tts = TextToSpeech(getApplication<Application>()) { st ->
                ttsReady = st == TextToSpeech.SUCCESS
                if (ttsReady) go() else status = "Synthèse vocale indisponible sur ce téléphone"
            }
        } else if (ttsReady) {
            go()
        }
    }

    fun stopSpeak() {
        tts?.stop()
        speaking = false
    }

    /** Reprend une réponse coupée (par toi, par l'IA ou par une erreur) là où elle s'est arrêtée. */
    fun continueGeneration() {
        val vm = current ?: return
        if (busy || messages.isEmpty()) return
        val idx = messages.size - 1
        val last = messages[idx]
        if (last.role != "assistant" || last.kind != "chat" || last.text.isBlank()) return
        val model = pickChat(false) ?: return
        busy = true
        stopped = false
        liveIdx = idx
        job = viewModelScope.launch {
            try {
                ensureChat(vm, model)
                val sys = systemPrompt()
                val partial = stripThink(last.text).removePrefix("⚠").trimEnd()
                val budget = ((effCtx() - chatMax() - 200) * 3 - sys.length).coerceAtLeast(1500)
                val base = historyForAi(messages.toList().subList(0, idx))
                val history = fitHistory(
                    base + listOf(
                        "assistant" to partial,
                        "user" to "Ta réponse précédente s'est interrompue. Continue-la exactement là où elle s'arrête, sans rien répéter ni recommencer.",
                    ),
                    budget,
                )
                val before = last.text
                val r = vmm.chat(history, sys, chatMax(), temperature.toDouble()) { t ->
                    if (idx < messages.size) messages[idx] = messages[idx].copy(text = before.trimEnd() + " " + stripThink(t))
                }
                if (idx < messages.size) messages[idx] = messages[idx].copy(text = before.trimEnd() + " " + stripThink(r))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!stopped) status = "Impossible de continuer : ${e.message}"
            } finally {
                busy = false
                liveIdx = -1
                notifyFinished()
                saveSession()
            }
        }
    }

    /** Relance la dernière création (logiciel ou image) interrompue ou ratée, avec la même demande. */
    fun retryCreation() {
        val vm = current ?: return
        if (busy || messages.size < 2) return
        val last = messages.last()
        if (last.role != "assistant" || (last.kind != "project" && last.kind != "image")) return
        val userMsg = messages[messages.size - 2]
        if (userMsg.role != "user") return
        messages.removeAt(messages.size - 1)
        val intent = if (last.kind == "project") Intent.PROJECT else Intent.IMAGE
        busy = true
        stopped = false
        job = viewModelScope.launch {
            try {
                if (intent == Intent.PROJECT) doProject(vm, userMsg.text, userMsg.atts, last.route)
                else doImage(vm, userMsg.text, userMsg.atts, last.route)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                messages.add(Msg("assistant", "⚠ ${e.message}"))
            } finally {
                busy = false
                liveIdx = -1
                notifyFinished()
                saveSession()
            }
        }
    }

    fun regenerate() {
        val vm = current ?: return
        if (busy || messages.isEmpty()) return
        val last = messages.last()
        if (last.role != "assistant" || last.kind != "chat") return
        messages.removeAt(messages.size - 1)
        if (messages.isEmpty() || messages.last().role != "user") return
        busy = true
        stopped = false
        job = viewModelScope.launch {
            try {
                doChat(vm, last.route)
            } finally {
                busy = false
                liveIdx = -1
                notifyFinished()
                saveSession()
            }
        }
    }

    // ---------- Logiciel complet (affiché en direct) ----------

    private fun slug(s: String) = s.trim().lowercase().replace(Regex("[^a-z0-9_-]+"), "-").trim('-').ifEmpty { "projet" }

    private fun uniqueSlug(vm: String, base: String): String {
        var s = base
        var n = 2
        while (vmm.projectDir(vm, s).exists()) { s = "$base-$n"; n++ }
        return s
    }

    private fun extractCode(raw: String): String {
        val s = raw.indexOf("```")
        if (s < 0) return raw.trim()
        val nl = raw.indexOf('\n', s)
        if (nl < 0) return raw.trim()
        val e = raw.lastIndexOf("```")
        return raw.substring(nl + 1, if (e > nl) e else raw.length).trimEnd()
    }

    private fun cleanPath(line: String): String =
        line.trim().trimStart('-', '*', ' ').replace(Regex("^\\d+[.)]\\s*"), "").trim('`', ' ', '*')

    private fun parsePlan(txt: String): List<ProjFile> {
        val out = LinkedHashMap<String, ProjFile>()
        txt.lines().forEach { l ->
            val parts = l.split("|", limit = 2)
            val path = cleanPath(parts[0])
            val ok = path.isNotEmpty() && path.length < 80 && !path.contains(' ') && path.contains('.')
            if (ok && path !in out && out.size < 12) out[path] = ProjFile(path, parts.getOrElse(1) { "" }.trim())
        }
        return out.values.toList()
    }

    private fun requestWithFiles(text: String, atts: List<Att>): String {
        val sb = StringBuilder(text)
        var left = 6000
        atts.filter { it.kind != "image" && it.text.isNotBlank() }.forEach { a ->
            val t = a.text.take(left)
            left -= t.length
            if (t.isNotEmpty()) sb.append("\n\n[Fichier joint : ${a.name}]\n").append(t)
        }
        return sb.toString()
    }

    private suspend fun doProject(vm: String, text: String, atts: List<Att>, route: String) {
        val model = pickChat(true) ?: run { noModel(route, false); return }
        val before = messages.dropLast(1).lastOrNull { it.kind == "project" }?.ref
        val existing = before?.takeIf { it.isNotEmpty() && vmm.projectDir(vm, it).exists() }
        val modify = existing != null && Router.isEdit(text)
        val request = requestWithFiles(text.ifBlank { "Fais un logiciel à partir du fichier joint." }, atts)
        projectName = if (modify) existing!! else uniqueSlug(vm, slug(text.ifBlank { atts.firstOrNull()?.name ?: "projet" }.take(24)))
        messages.add(Msg("assistant", "", kind = "project", ref = projectName, route = route))
        val idx = messages.size - 1
        liveIdx = idx
        projLog = ""
        liveFile = ""
        liveText = ""
        val ctxNeeded = maxOf(ctxSize, 4096)
        val fileTokens = (ctxNeeded - 1500).coerceIn(1000, 4000)
        try {
            ensureChat(vm, model, 4096)
            val dir = vmm.projectDir(vm, projectName)
            if (!modify) {
                dir.mkdirs()
                projFiles.clear()
                projLog = "1/3 · L'IA prépare le plan du projet…"
                liveFile = "Plan du projet"
                liveText = ""
                val planRaw = vmm.chat(listOf("user" to "Demande : $request"), PLAN_SYSTEM, 1500, 0.3) { liveText = it }
                val plan = parsePlan(stripThink(planRaw))
                if (plan.isEmpty()) throw RuntimeException("Plan illisible. Reformule la demande ou choisis un modèle plus grand.")
                projFiles.addAll(plan)
                val summary = plan.joinToString("\n") { "- ${it.path} : ${it.role}" }
                plan.forEachIndexed { i, pf ->
                    projLog = "2/3 · Écriture de ${pf.path} (${i + 1}/${plan.size})"
                    pf.status = "écriture…"
                    liveFile = pf.path
                    liveText = ""
                    val user = "Projet : $request\n\nFichiers du projet :\n$summary\n\n" +
                        "Écris le contenu COMPLET du fichier ${pf.path} (${pf.role}). Un seul bloc de code, rien d'autre."
                    val raw = vmm.chat(listOf("user" to user), FILE_SYSTEM, fileTokens, 0.3) { liveText = it }
                    var code = extractCode(stripThink(raw))
                    if (autoReview && code.isNotBlank()) {
                        pf.status = "relecture…"
                        projLog = "3/3 · Relecture de ${pf.path}"
                        liveFile = "Relecture de ${pf.path}"
                        liveText = ""
                        val rev = vmm.chat(
                            listOf("user" to "Fichier ${pf.path} du projet « ${request.take(300)} » :\n```\n$code\n```\nCorrige-le."),
                            REVIEW_SYSTEM, fileTokens, 0.2,
                        ) { liveText = it }
                        val r = extractCode(stripThink(rev))
                        if (r.isNotBlank()) code = r
                    }
                    vmm.writeProjectFile(dir, pf.path, code)
                    pf.status = "✓"
                }
                projLog = "Terminé : ${plan.size} fichiers. Touche « Télécharger le .zip »."
                if (idx < messages.size) messages[idx] = messages[idx].copy(text = "Fichiers : " + plan.joinToString { it.path }, extra = projLog)
            } else {
                val files = vmm.projectFiles(dir)
                projFiles.clear()
                files.forEach { projFiles.add(ProjFile(it, "", "✓")) }
                projLog = "L'IA cherche les fichiers à modifier…"
                liveFile = "Choix des fichiers"
                liveText = ""
                val selRaw = vmm.chat(
                    listOf("user" to "Fichiers du projet :\n${files.joinToString("\n")}\n\nDemande : $request"),
                    SELECT_SYSTEM, 800, 0.2,
                ) { liveText = it }
                val sel = stripThink(selRaw).lines().map { cleanPath(it.substringBefore('|')) }
                    .filter { it.isNotEmpty() && it.contains('.') && !it.contains(' ') }.distinct().take(8)
                if (sel.isEmpty()) throw RuntimeException("L'IA n'a désigné aucun fichier. Reformule la demande.")
                sel.forEachIndexed { i, path ->
                    projLog = "Modification de $path (${i + 1}/${sel.size})"
                    val pf = projFiles.firstOrNull { it.path == path } ?: ProjFile(path, "").also { projFiles.add(it) }
                    pf.status = "modification…"
                    liveFile = path
                    liveText = ""
                    val old = vmm.readProjectFile(dir, path)?.take(9000) ?: "(nouveau fichier)"
                    val user = "Fichiers du projet :\n${files.joinToString("\n")}\n\nDemande : $request\n\n" +
                        "Contenu actuel de $path :\n```\n$old\n```\nRenvoie le fichier $path complet et modifié."
                    val raw = vmm.chat(listOf("user" to user), MODIFY_SYSTEM, fileTokens, 0.3) { liveText = it }
                    val code = extractCode(stripThink(raw))
                    if (code.isNotBlank()) vmm.writeProjectFile(dir, path, code)
                    pf.status = "✓ modifié"
                }
                projLog = "Modifications terminées : " + sel.joinToString() + ". Retélécharge le .zip."
                if (idx < messages.size) messages[idx] = messages[idx].copy(text = "Fichiers modifiés : " + sel.joinToString(), extra = projLog)
            }
            liveFile = ""
        } catch (e: Exception) {
            status = ""
            if (idx < messages.size) {
                if (stopped || e is CancellationException) {
                    messages[idx] = messages[idx].copy(extra = "Arrêté. Les fichiers déjà écrits sont conservés.")
                } else {
                    messages[idx] = messages[idx].copy(extra = "⚠ ${e.message}")
                }
            }
            if (e is CancellationException) throw e
        }
    }

    fun filesOf(slug: String): List<String> {
        val vm = current ?: return emptyList()
        return vmm.projectFiles(vmm.projectDir(vm, slug))
    }

    fun readFileOf(slug: String, path: String): String {
        val vm = current ?: return ""
        return vmm.readProjectFile(vmm.projectDir(vm, slug), path) ?: "(fichier absent)"
    }

    fun projectDirOf(slug: String): File? = current?.let { vmm.projectDir(it, slug) }

    fun exportProject(slug: String) {
        val vm = current ?: return
        viewModelScope.launch(Dispatchers.IO) {
            status = try {
                "Archive enregistrée : " + vmm.exportZip(vmm.projectDir(vm, slug))
            } catch (e: Exception) {
                "⚠ ${e.message}"
            }
        }
    }

    // ---------- Image : création et modification (affichée en direct) ----------

    fun imageFileOf(ref: String): File? = current?.let { File(vmm.outDir(it), ref) }

    private fun lastImage(vm: String): File? =
        messages.toList().asReversed().firstOrNull { it.kind == "image" && it.ref.isNotEmpty() }
            ?.let { File(vmm.outDir(vm), it.ref) }?.takeIf { it.exists() }

    private fun prepareBase(vm: String, src: File, size: Int): File? {
        val bmp = BitmapFactory.decodeFile(src.path) ?: return null
        val side = minOf(bmp.width, bmp.height)
        val sq = Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
        val scaled = Bitmap.createScaledBitmap(sq, size, size, true)
        val dest = File(vmm.outDir(vm), "_base.png")
        FileOutputStream(dest).use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return dest
    }

    private val stepRegex = Regex("(\\d+)/(\\d+)")

    private suspend fun doImage(vm: String, text: String, atts: List<Att>, route: String) {
        val imgModel = pickImage() ?: run { noModel(route, true); return }
        val attached = atts.lastOrNull { it.kind == "image" }?.let { File(it.imagePath) }?.takeIf { it.exists() }
        val baseFile = attached ?: if (Router.isEdit(text)) lastImage(vm) else null
        messages.add(Msg("assistant", "", kind = "image", route = route))
        val idx = messages.size - 1
        liveIdx = idx
        imageLog = ""
        imageStep = 0f
        livePrompt = ""
        try {
            var prompt = text.ifBlank { "a high quality, detailed version of the image" }
            val chat = pickChat(false)
            if (promptBoost && chat != null && text.isNotBlank()) {
                imageLog = "L'IA rédige la description anglaise de l'image…"
                ensureChat(vm, chat)
                val r = vmm.chat(listOf("user" to text), PROMPT_SYSTEM, 150, 0.4) { livePrompt = stripThink(it) }
                val cleaned = stripThink(r).replace("\n", " ").trim().trim('"', '«', '»')
                if (cleaned.isNotBlank()) prompt = cleaned
            }
            livePrompt = prompt
            // Le chat et les images ne tournent pas en même temps : on libère la mémoire.
            vmm.stopChat()
            runningModel = null
            chatCtx = 0
            imageLog = "Préparation du moteur d'images…"
            val base = if (baseFile != null) withContext(Dispatchers.IO) { prepareBase(vm, baseFile, imageSize) } else null
            val f = vmm.generateImage(vm, imgModel, prompt, negative, imageSize, threads, base, strength) { line ->
                imageLog = line
                val m = stepRegex.find(line)
                if (m != null) {
                    val a = m.groupValues[1].toFloatOrNull() ?: 0f
                    val b = m.groupValues[2].toFloatOrNull() ?: 0f
                    if (b > 0f && a <= b) imageStep = a / b
                }
            }
            gallery.add(0, f)
            imageStep = 1f
            if (idx < messages.size) messages[idx] = messages[idx].copy(text = prompt, ref = f.name, extra = "ok")
        } catch (e: Exception) {
            if (idx < messages.size) {
                if (stopped || e is CancellationException) {
                    messages[idx] = messages[idx].copy(extra = "Arrêté.")
                } else {
                    messages[idx] = messages[idx].copy(text = livePrompt, extra = "⚠ ${e.message}")
                }
            }
            status = ""
            if (e is CancellationException) throw e
        }
    }

    fun saveToPhotos(f: File) {
        viewModelScope.launch(Dispatchers.IO) {
            status = if (vmm.exportToPhotos(f)) "Image enregistrée dans Photos › Studo"
            else "Enregistrement impossible (Android 10 ou plus requis)"
        }
    }

    // ---------- Historique : sessions et catégories ----------

    private fun makeTitle(): String {
        val first = messages.firstOrNull { it.role == "user" }
        val t = first?.text?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        return when {
            t.isNotEmpty() -> if (t.length > 40) t.take(40).trimEnd() + "…" else t
            first?.atts?.isNotEmpty() == true -> first.atts.first().name
            else -> "Nouvelle session"
        }
    }

    private fun reloadSessions() {
        val vm = current
        sessions.clear()
        if (vm != null) sessions.addAll(store.list(vm))
    }

    fun saveSession() {
        val vm = current ?: return
        if (messages.isEmpty()) return
        val id = sessionId ?: System.currentTimeMillis().toString(36).also { sessionId = it }
        if (sessionTitle.isBlank()) sessionTitle = makeTitle()
        store.save(vm, id, sessionTitle, sessionStar, System.currentTimeMillis(), messages.toList())
        reloadSessions()
    }

    fun newSession() {
        if (busy) return
        saveSession()
        messages.clear()
        pending.clear()
        sessionId = null
        sessionTitle = ""
        sessionStar = false
    }

    fun openSession(id: String) {
        val vm = current ?: return
        if (busy || id == sessionId) return
        saveSession()
        val (meta, msgs) = store.load(vm, id) ?: return
        messages.clear()
        messages.addAll(msgs)
        pending.clear()
        sessionId = id
        sessionTitle = meta.title
        sessionStar = meta.starred
    }

    fun deleteSession(id: String) {
        val vm = current ?: return
        if (busy && id == sessionId) return
        store.delete(vm, id)
        if (sessionId == id) {
            messages.clear()
            sessionId = null
            sessionTitle = ""
            sessionStar = false
        }
        reloadSessions()
    }

    private fun rewrite(id: String, title: String?, star: Boolean?) {
        val vm = current ?: return
        if (id == sessionId) {
            title?.let { sessionTitle = it }
            star?.let { sessionStar = it }
            if (!busy) saveSession()
            else reloadSessions()
            return
        }
        val (meta, msgs) = store.load(vm, id) ?: return
        store.save(vm, id, title ?: meta.title, star ?: meta.starred, meta.updated, msgs)
        reloadSessions()
    }

    /** Exporte une session en Markdown dans Téléchargements › Studo. */
    fun exportSession(id: String) {
        val vm = current ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val (meta, msgs) = (if (id == sessionId) null else store.load(vm, id)) ?: (SessionMeta(id, sessionTitle, categoryOf(messages.toList()), sessionStar, 0L) to messages.toList())
                val sb = StringBuilder("# ${meta.title}\n\n")
                msgs.forEach { m ->
                    if (m.role == "user") {
                        sb.append("**Toi** : ").append(m.text).append('\n')
                        m.atts.forEach { sb.append("- 📎 ").append(it.name).append('\n') }
                        sb.append('\n')
                    } else {
                        sb.append("**Studo** : ")
                        when (m.kind) {
                            "project" -> sb.append("logiciel « ${m.ref} » — ${m.text}\n\n")
                            "image" -> sb.append("image générée — ${m.text}\n\n")
                            else -> sb.append(stripThink(m.text)).append("\n\n")
                        }
                    }
                }
                val name = meta.title.replace(Regex("[^A-Za-z0-9_-]+"), "_").take(40).ifEmpty { "session" } + ".md"
                status = "Session exportée : " + vmm.exportText(name, sb.toString())
            } catch (e: Exception) {
                status = "Export impossible : ${e.message}"
            }
        }
    }

    fun renameSession(id: String, title: String) {
        val t = title.trim()
        if (t.isNotEmpty()) rewrite(id, t, null)
    }

    fun toggleStar(meta: SessionMeta) { rewrite(meta.id, null, !meta.starred) }

    // ---------- Diagnostic complet ----------

    fun runDiagnostics() {
        if (diagBusy) return
        diagBusy = true
        report.clear()
        val vm = current
        viewModelScope.launch(Dispatchers.IO) {
            try {
                report.add("Studo $APP_VERSION")
                report.add("Téléphone : ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                val abis = Build.SUPPORTED_ABIS.joinToString()
                report.add((if (Build.SUPPORTED_ABIS.contains("arm64-v8a")) "✓" else "✗") + " Processeur : $abis" +
                    (if (Build.SUPPORTED_ABIS.contains("arm64-v8a")) "" else " (les moteurs ont besoin de arm64-v8a)"))
                val am = getApplication<Application>().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val mi = ActivityManager.MemoryInfo()
                am.getMemoryInfo(mi)
                report.add("✓ Mémoire : ${"%.1f".format(mi.totalMem / 1e9)} Go au total, ${"%.1f".format(mi.availMem / 1e9)} Go libres maintenant")
                val free = vmm.freeSpace()
                report.add((if (free > 3_000_000_000L) "✓" else "⚠") + " Espace libre : ${"%.1f".format(free / 1e9)} Go")
                report.add("Moteur de chat : " + vmm.probe(File(vmm.nativeDir, "libllama_server.so"), "--version"))
                report.add("Moteur d'images : " + vmm.probe(File(vmm.nativeDir, "libsd.so"), "--help"))
                if (vm != null) {
                    report.add("Machine : $vm")
                    val files = vmm.allModelFiles(vm).filter { !it.name.endsWith(".part") }
                    if (files.isEmpty()) report.add("⚠ Aucun modèle installé")
                    files.sortedBy { it.name }.forEach { f ->
                        val (ok, msg) = vmm.verifyModel(f)
                        val gb = f.length() / 1e9
                        val heavy = if (gb > ramGb * 0.6) " · ⚠ trop lourd pour ta RAM" else ""
                        report.add((if (ok) "✓ " else "✗ ") + f.name.removePrefix("img__") + " (${"%.2f".format(gb)} Go) : $msg$heavy")
                    }
                    val parts = vmm.allModelFiles(vm).filter { it.name.endsWith(".part") }
                    if (parts.isNotEmpty()) {
                        report.add("⚠ ${parts.size} téléchargement(s) interrompu(s), ${"%.1f".format(parts.sumOf { it.length() } / 1e9)} Go à reprendre ou supprimer")
                    }
                }
                report.add(try {
                    val c = URL("https://huggingface.co/api/models?limit=1").openConnection() as HttpURLConnection
                    c.connectTimeout = 6000
                    c.readTimeout = 6000
                    c.setRequestProperty("User-Agent", "Studo-Android")
                    val code = c.responseCode
                    c.disconnect()
                    if (code in 200..299) "✓ Internet et Hugging Face : accessibles" else "⚠ Hugging Face a répondu HTTP $code"
                } catch (e: Exception) {
                    "✗ Hugging Face inaccessible (${e.message}) : vérifie ta connexion"
                })
                report.add("— Analyse terminée —")
            } finally {
                diagBusy = false
            }
        }
    }

    override fun onCleared() { tts?.shutdown(); vmm.stopChat() }
}


/** Personnalités : (nom affiché, consigne donnée à l'IA). */
val PERSONAS = listOf(
    "Normal" to "",
    "👩‍🏫 Professeur patient" to "Tu es un professeur patient : tu expliques pas à pas, avec des exemples simples, et tu vérifies que c'est compris.",
    "🧑‍💻 Codeur senior" to "Tu es un développeur senior : réponses précises, code propre et commenté, bonnes pratiques et pièges à éviter.",
    "🌍 Traducteur" to "Tu es un traducteur : tu traduis le texte de l'utilisateur entre le français et l'anglais (ou la langue demandée), sans rien ajouter.",
    "✍️ Correcteur" to "Tu es un correcteur : tu corriges l'orthographe, la grammaire et le style du texte, puis tu listes brièvement les corrections.",
    "📝 Résumeur" to "Tu résumes les textes de façon claire et très courte, en points essentiels.",
    "🏋️ Coach" to "Tu es un coach motivant et bienveillant : conseils concrets, petits objectifs, encouragements.",
    "📖 Conteur" to "Tu es un conteur : tu inventes des histoires vivantes, avec des dialogues et des descriptions imagées.",
    "🏴‍☠️ Pirate" to "Tu parles comme un pirate rigolo, tout en restant utile et correct dans le fond.",
    "🧠 Philosophe" to "Tu réponds en philosophe : tu poses les bonnes questions, compares les points de vue et nuances.",
)
