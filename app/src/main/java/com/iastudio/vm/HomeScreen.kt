package com.iastudio.vm

import android.app.Activity
import android.content.Intent as AndroidIntent
import android.graphics.BitmapFactory
import android.speech.RecognizerIntent
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

@Composable
fun HomeScreen(vm: AppViewModel, onModels: () -> Unit, onSettings: () -> Unit, onBrowser: () -> Unit) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var draft by rememberSaveable { mutableStateOf("") }
    var viewer by remember { mutableStateOf<Att?>(null) }
    val openPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.viewFile(uri) { viewer = it }
    }
    val listState = rememberLazyListState()
    val grey = MaterialTheme.colorScheme.onSurfaceVariant

    // Suit l'écriture en direct : on reste en bas tant que l'IA travaille.
    val lastLen = vm.messages.lastOrNull()?.text?.length ?: 0
    LaunchedEffect(vm.messages.size, lastLen, vm.liveText.length, vm.imageLog, vm.projFiles.size) {
        if (vm.busy && vm.messages.isNotEmpty()) listState.scrollToItem(vm.messages.size - 1, 1_000_000)
    }
    LaunchedEffect(vm.sessionId) {
        if (vm.messages.isNotEmpty()) listState.scrollToItem(vm.messages.size - 1, 1_000_000)
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet {
                HistoryDrawer(
                    vm,
                    close = { scope.launch { drawer.close() } },
                    onModels = { scope.launch { drawer.close() }; onModels() },
                    onSettings = { scope.launch { drawer.close() }; onSettings() },
                )
            }
        },
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton({ scope.launch { drawer.open() } }, Modifier.size(42.dp)) { Icon(Icons.Default.Menu, "Historique") }
                StudioLogo(22.dp)
                Spacer(Modifier.weight(1f))
                IconButton({ vm.newSession() }, Modifier.size(42.dp)) { Icon(Icons.Default.Add, "Nouvelle session") }
                IconButton(onBrowser, Modifier.size(42.dp)) { Icon(Icons.Default.Public, "Navigateur") }
                IconButton({ openPicker.launch(arrayOf("*/*")) }, Modifier.size(42.dp)) { Icon(Icons.Default.FolderOpen, "Ouvrir un fichier") }
                IconButton(onModels, Modifier.size(42.dp)) { Icon(Icons.Default.Storage, "Modèles") }
                IconButton(onSettings, Modifier.size(42.dp)) { Icon(Icons.Default.Settings, "Réglages") }
            }
            if (vm.status.isNotEmpty()) {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp)) {
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(vm.status, Modifier.weight(1f), fontSize = 13.sp)
                        TextButton({ vm.status = "" }) { Text("OK") }
                    }
                }
            }
            if (vm.progress.isNotEmpty()) {
                Text(
                    "Téléchargement en cours : " + vm.progress.keys.toList().joinToString { "${vm.dlNames[it] ?: it} ${vm.progress[it]} %" },
                    color = grey, fontSize = 12.sp, modifier = Modifier.padding(vertical = 2.dp),
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (vm.messages.isEmpty()) {
                    Welcome(vm, onModels) { draft = it }
                } else {
                    LazyColumn(Modifier.fillMaxSize(), state = listState, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(vm.messages.size) { i ->
                            val m = vm.messages.getOrNull(i)
                            if (m != null) MessageView(vm, i, m, i == vm.messages.size - 1) { viewer = it }
                        }
                    }
                }
            }
            Composer(vm, draft, { draft = it }, onModels) { viewer = it }
        }
    }
    viewer?.let { FileViewer(it) { viewer = null } }
}

@Composable
private fun Welcome(vm: AppViewModel, onModels: () -> Unit, onPick: (String) -> Unit) {
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        StudioLogo(64.dp)
        Spacer(Modifier.height(8.dp))
        Text("Studo", style = MaterialTheme.typography.headlineLarge)
        Text(
            "Écris ce que tu veux : une question, un logiciel à créer ou une image. Studo choisit tout seul, et tu vois tout se faire en direct.",
            color = grey, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 12.dp),
        )
        if (vm.chatModels.isEmpty() && vm.imageModels.isEmpty()) {
            Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Aucune IA installée pour l'instant.")
                    Spacer(Modifier.height(8.dp))
                    Button(onModels) { Text("Installer une IA") }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        val ideas = remember {
            listOf(
                "Crée une calculatrice en page web avec historique",
                "Dessine un chat astronaute sur la lune",
                "Explique-moi les fractions simplement",
                "Crée un jeu de morpion en page web",
                "Dessine un paysage de montagne au coucher du soleil",
                "Écris une histoire courte pour endormir un enfant",
                "Mets une alarme demain à 7h30",
                "Allume la lampe torche",
                "Donne-moi l'état de ma batterie et de mon stockage",
                "Crée un site web de portfolio avec un thème sombre",
                "Résume-moi un texte que je vais te coller",
                "Aide-moi à corriger mes fautes dans un message",
            ).shuffled().take(4)
        }
        ideas.forEach { s ->
            SuggestionChip({ onPick(s) }, { Text(s, fontSize = 13.sp) }, Modifier.padding(vertical = 2.dp))
        }
    }
}

// ------------------------------------------------------------------ Messages

@Composable
fun MessageView(vm: AppViewModel, idx: Int, m: Msg, isLast: Boolean, onOpen: (Att) -> Unit) {
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    if (m.role == "user") {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            m.atts.forEach { a -> AttachmentChip(a, onClick = { onOpen(a) }) }
            if (m.text.isNotBlank()) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(18.dp)) {
                    SelectionContainer { Text(m.text, Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) }
                }
            }
        }
        return
    }
    val clip = LocalClipboardManager.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (m.route.isNotEmpty()) Text("↳ ${m.route}", color = grey, fontSize = 12.sp)
        when (m.kind) {
            "project" -> {
                ProjectCard(vm, idx, m)
                if (isLast && !vm.busy) TextButton({ vm.retryCreation() }) { Text("Reprendre / refaire") }
            }
            "image" -> {
                ImageCard(vm, idx, m, onOpen)
                if (isLast && !vm.busy) TextButton({ vm.retryCreation() }) { Text("Reprendre / refaire") }
            }
            "action" -> ActionCard(vm, idx, m)
            else -> {
                val live = vm.busy && vm.liveIdx == idx
                if (m.text.isBlank() && live) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("L'IA réfléchit…", color = grey)
                    }
                } else if (m.text.isNotBlank()) {
                    SelectionContainer { RichText(m.text) }
                    if (!live) {
                        Row {
                            TextButton({ clip.setText(AnnotatedString(stripThink(m.text))) }) { Text("Copier") }
                            TextButton({ vm.speak(m.text) }) { Text(if (vm.speaking) "⏹ Stop" else "🔊 Lire") }
                            if (isLast && !vm.busy && !m.text.startsWith("⚠")) {
                                TextButton({ vm.continueGeneration() }) { Text("Continuer") }
                                TextButton({ vm.regenerate() }) { Text("Régénérer") }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun inlineAssets(html: String, read: (String) -> String): String {
    var out = html
    Regex("<link[^>]*href=[\"']([^\"']+\\.css)[\"'][^>]*>", RegexOption.IGNORE_CASE).findAll(html).forEach { m ->
        val css = read(m.groupValues[1].removePrefix("./"))
        if (!css.startsWith("(fichier absent)")) out = out.replace(m.value, "<style>$css</style>")
    }
    Regex("<script[^>]*src=[\"']([^\"']+\\.js)[\"'][^>]*>\\s*</script>", RegexOption.IGNORE_CASE).findAll(html).forEach { m ->
        val js = read(m.groupValues[1].removePrefix("./"))
        if (!js.startsWith("(fichier absent)")) out = out.replace(m.value, "<script>$js</script>")
    }
    return out
}

@Composable
fun ProjectCard(vm: AppViewModel, idx: Int, m: Msg) {
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    val clip = LocalClipboardManager.current
    val live = vm.busy && vm.liveIdx == idx
    var viewing by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(16.dp), tonalElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("📦 Logiciel « ${m.ref} »", style = MaterialTheme.typography.titleMedium)
            if (live) {
                Text(vm.projLog, fontSize = 13.sp)
                LinearProgressIndicator(Modifier.fillMaxWidth())
                vm.projFiles.toList().forEach { pf ->
                    Text(
                        "${if (pf.status.startsWith("✓")) "✓" else if (pf.status == "en attente") "○" else "✍"} ${pf.path}  ·  ${pf.status}",
                        fontSize = 12.sp, color = grey,
                    )
                }
                if (vm.liveFile.isNotEmpty()) {
                    Text("✍ ${vm.liveFile}", style = MaterialTheme.typography.labelMedium)
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(10.dp)) {
                        Text(
                            vm.liveText.lines().takeLast(14).joinToString("\n").ifBlank { "…" },
                            Modifier.fillMaxWidth().padding(10.dp), fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                            lineHeight = 15.sp, maxLines = 14, overflow = TextOverflow.Clip,
                        )
                    }
                }
            } else {
                val files = remember(m.ref, m.extra, live) { vm.filesOf(m.ref) }
                if (m.extra.isNotEmpty()) {
                    Text(m.extra, fontSize = 13.sp, color = if (m.extra.startsWith("⚠")) MaterialTheme.colorScheme.error else grey)
                }
                files.forEach { f ->
                    Text(
                        "📄 $f", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { viewing = f }.padding(vertical = 3.dp),
                    )
                }
                if (files.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ vm.exportProject(m.ref) }) { Text("Télécharger le .zip") }
                        if (files.any { it.endsWith(".html", true) }) OutlinedButton({ preview = true }) { Text("Aperçu") }
                    }
                }
            }
        }
    }
    viewing?.let { path ->
        val content = remember(path) { vm.readFileOf(m.ref, path) }
        AlertDialog(
            onDismissRequest = { viewing = null },
            title = { Text(path, fontSize = 16.sp) },
            text = {
                Column(Modifier.height(380.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        content, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp,
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    )
                }
            },
            confirmButton = { TextButton({ clip.setText(AnnotatedString(content)) }) { Text("Copier") } },
            dismissButton = { TextButton({ viewing = null }) { Text("Fermer") } },
        )
    }
    if (preview) {
        val files = vm.filesOf(m.ref)
        val page = files.firstOrNull { it.equals("index.html", true) } ?: files.firstOrNull { it.endsWith(".html", true) }
        val html = remember(m.ref, page) {
            if (page == null) "<p>Aucune page HTML.</p>" else inlineAssets(vm.readFileOf(m.ref, page)) { vm.readFileOf(m.ref, it) }
        }
        AlertDialog(
            onDismissRequest = { preview = false },
            title = { Text("Aperçu", fontSize = 16.sp) },
            text = {
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
                    modifier = Modifier.fillMaxWidth().height(420.dp),
                    onRelease = { it.destroy() },
                )
            },
            confirmButton = { TextButton({ preview = false }) { Text("Fermer") } },
        )
    }
}

@Composable
private fun Elapsed() {
    var sec by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1000); sec++ } }
    Text("⏱ ${sec / 60} min ${sec % 60} s", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun ImageCard(vm: AppViewModel, idx: Int, m: Msg, onOpen: (Att) -> Unit) {
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    val live = vm.busy && vm.liveIdx == idx
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(16.dp), tonalElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (live) {
                Text("🖼 Création de l'image en cours…", style = MaterialTheme.typography.titleMedium)
                if (vm.livePrompt.isNotBlank()) Text("Description : ${vm.livePrompt.take(300)}", fontSize = 12.sp, color = grey)
                if (vm.imageStep > 0f) LinearProgressIndicator({ vm.imageStep }, Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
                if (vm.imageLog.isNotBlank()) {
                    Text(vm.imageLog, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = grey, maxLines = 3)
                }
                Elapsed()
                Text("Sur processeur, compte plusieurs minutes. Commence en 256×256 pour tester (Réglages).", fontSize = 12.sp, color = grey)
            } else if (m.extra.startsWith("⚠")) {
                Text("🖼 Image", style = MaterialTheme.typography.titleMedium)
                Text(m.extra, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            } else if (m.ref.isNotEmpty()) {
                val bmp by produceState<ImageBitmap?>(null, m.ref) {
                    value = withContext(Dispatchers.IO) {
                        vm.imageFileOf(m.ref)?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() }
                    }
                }
                bmp?.let {
                    Image(
                        it, "Image générée",
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                            vm.imageFileOf(m.ref)?.let { f -> onOpen(Att(m.ref, "image", imagePath = f.path, size = f.length(), path = f.path)) }
                        },
                        contentScale = ContentScale.FillWidth,
                    )
                } ?: Text("Chargement de l'image…", color = grey)
                if (m.text.isNotBlank()) Text(m.text.take(300), fontSize = 12.sp, color = grey)
                vm.imageFileOf(m.ref)?.let { f ->
                    Row {
                        TextButton({ vm.saveToPhotos(f) }) { Text("Enregistrer") }
                        TextButton({ vm.attachImageFile(f) }) { Text("Modifier") }
                    }
                }
            } else {
                Text("🖼 Image", style = MaterialTheme.typography.titleMedium)
                Text(m.extra.ifEmpty { "Arrêtée." }, color = grey, fontSize = 13.sp)
            }
        }
    }
}

// ------------------------------------------------------------------ Zone de saisie unique

@Composable
private fun ModelChip(icon: String, current: String, models: List<ModelEntry>, pickedId: String, onPick: (String) -> Unit, onEmpty: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            { if (models.isEmpty()) onEmpty() else open = true },
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            modifier = Modifier.height(34.dp),
        ) { Text("$icon ${current.take(22)}", fontSize = 12.sp, maxLines = 1) }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem({ Text((if (pickedId.isEmpty()) "✓ " else "") + "Auto (recommandé)") }, { onPick(""); open = false })
            models.forEach { mm ->
                DropdownMenuItem({ Text((if (pickedId == mm.id) "✓ " else "") + displayName(mm.name)) }, { onPick(mm.id); open = false })
            }
        }
    }
}

@Composable
private fun Composer(vm: AppViewModel, text: String, onText: (String) -> Unit, onModels: () -> Unit, onOpen: (Att) -> Unit) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.attach(uris)
    }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            val heard = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!heard.isNullOrBlank()) onText((if (text.isBlank()) "" else "$text ") + heard)
        }
    }
    var personaMenu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (vm.pending.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                vm.pending.toList().forEach { a -> AttachmentChip(a, onClick = { onOpen(a) }, onRemove = { vm.removePending(a) }) }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(vm.forced == null, { vm.forced = null }, { Text("Auto", fontSize = 12.sp) })
            Intent.values().forEach { i ->
                FilterChip(vm.forced == i, { vm.forced = if (vm.forced == i) null else i }, { Text(i.label, fontSize = 12.sp) })
            }
            Box {
                FilterChip(
                    vm.persona.isNotEmpty(), { personaMenu = true },
                    { Text(if (vm.persona.isEmpty()) "🎭 Personnalité" else vm.persona, fontSize = 12.sp) },
                )
                DropdownMenu(personaMenu, { personaMenu = false }) {
                    PERSONAS.forEach { (n, _) ->
                        DropdownMenuItem({ Text((if (vm.persona == n || (n == "Normal" && vm.persona.isEmpty())) "✓ " else "") + n) }, {
                            vm.updatePersona(if (n == "Normal") "" else n)
                            personaMenu = false
                        })
                    }
                }
            }
            ModelChip(
                "💬", (vm.pickChat(false)?.name ?: "aucune IA"), vm.chatModels, vm.chatPick, { vm.pickChatModel(it) }, onModels,
            )
            ModelChip(
                "🖼", (vm.pickImage()?.name ?: "aucune IA"), vm.imageModels, vm.imagePick, { vm.pickImageModel(it) }, onModels,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton({ picker.launch(arrayOf("*/*")) }) { Icon(Icons.Default.AttachFile, "Joindre un fichier") }
            IconButton({
                try {
                    mic.launch(
                        AndroidIntent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR"),
                    )
                } catch (e: Exception) {
                    vm.status = "Dictée vocale indisponible : installe ou active la reconnaissance vocale Google"
                }
            }) { Icon(Icons.Default.Mic, "Dicter") }
            OutlinedTextField(
                text, onText, Modifier.weight(1f),
                placeholder = { Text("Discussion, logiciel ou image : écris ici", fontSize = 14.sp) },
                maxLines = 5, shape = RoundedCornerShape(24.dp),
            )
            if (vm.busy) {
                IconButton({ vm.stopWork() }) { Icon(Icons.Default.Stop, "Arrêter") }
            } else {
                IconButton(
                    { vm.send(text); onText("") },
                    enabled = text.isNotBlank() || vm.pending.isNotEmpty(),
                ) { Icon(Icons.AutoMirrored.Filled.Send, "Envoyer") }
            }
        }
    }
}

// ------------------------------------------------------------------ Historique

private fun groupByDate(list: List<SessionMeta>, filter: String): List<Pair<String, List<SessionMeta>>> {
    val res = ArrayList<Pair<String, List<SessionMeta>>>()
    var rest = list
    if (filter == "all") {
        val st = rest.filter { it.starred }
        if (st.isNotEmpty()) res.add("⭐ Favoris" to st)
        rest = rest.filter { !it.starred }
    }
    val cal = Calendar.getInstance()
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    val today = cal.timeInMillis
    val day = 86_400_000L
    val used = HashSet<String>()
    listOf(
        "Aujourd'hui" to today, "Hier" to today - day, "7 derniers jours" to today - 7 * day, "30 derniers jours" to today - 30 * day,
    ).forEach { (name, from) ->
        val items = rest.filter { it.updated >= from && it.id !in used }
        used += items.map { it.id }
        if (items.isNotEmpty()) res.add(name to items)
    }
    val old = rest.filter { it.id !in used }
    if (old.isNotEmpty()) res.add("Plus ancien" to old)
    return res
}

@Composable
private fun HistoryDrawer(vm: AppViewModel, close: () -> Unit, onModels: () -> Unit, onSettings: () -> Unit) {
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    var filter by remember { mutableStateOf("all") }
    var renaming by remember { mutableStateOf<SessionMeta?>(null) }
    var deleting by remember { mutableStateOf<SessionMeta?>(null) }
    var newTitle by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }
    val shown = vm.sessions.toList().filter {
        (search.isBlank() || it.title.contains(search, true)) &&
            when (filter) { "all" -> true; "star" -> it.starred; else -> it.category == filter }
    }
    Column(Modifier.fillMaxHeight().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StudioLogo(26.dp)
            Spacer(Modifier.width(8.dp))
            Text("Studo", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Button({ vm.newSession(); close() }) { Text("+ Nouvelle") }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                "all" to "Tout", "discussion" to "💬 Discussions", "logiciel" to "📦 Logiciels", "image" to "🖼 Images", "star" to "⭐ Favoris",
            ).forEach { (k, label) -> FilterChip(filter == k, { filter = k }, { Text(label, fontSize = 12.sp) }) }
        }
        OutlinedTextField(
            search, { search = it }, Modifier.fillMaxWidth().padding(top = 6.dp), singleLine = true,
            placeholder = { Text("Rechercher une session", fontSize = 13.sp) }, shape = RoundedCornerShape(20.dp),
        )
        Spacer(Modifier.height(6.dp))
        LazyColumn(Modifier.weight(1f)) {
            if (shown.isEmpty()) {
                item { Text("Aucune session ici pour l'instant.", color = grey, modifier = Modifier.padding(12.dp)) }
            }
            groupByDate(shown, filter).forEach { (title, items) ->
                item(key = "h$title") {
                    Text(title, color = grey, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp, top = 10.dp, bottom = 2.dp))
                }
                items.forEach { s ->
                    item(key = s.id) {
                        SessionRow(
                            s, s.id == vm.sessionId,
                            onOpen = { vm.openSession(s.id); close() },
                            onRename = { newTitle = s.title; renaming = s },
                            onDelete = { deleting = s },
                            onStar = { vm.toggleStar(s) },
                            onExport = { vm.exportSession(s.id) },
                        )
                    }
                }
            }
        }
        HorizontalDivider()
        Row {
            TextButton(onModels) { Text("Modèles") }
            TextButton(onSettings) { Text("Réglages") }
        }
    }
    renaming?.let { s ->
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Renommer la session") },
            text = { OutlinedTextField(newTitle, { newTitle = it }, singleLine = true) },
            confirmButton = { TextButton({ vm.renameSession(s.id, newTitle); renaming = null }) { Text("OK") } },
            dismissButton = { TextButton({ renaming = null }) { Text("Annuler") } },
        )
    }
    deleting?.let { s ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Supprimer cette session ?") },
            text = { Text("« ${s.title} » sera effacée. Les fichiers déjà exportés ne sont pas touchés.") },
            confirmButton = { TextButton({ vm.deleteSession(s.id); deleting = null }) { Text("Supprimer") } },
            dismissButton = { TextButton({ deleting = null }) { Text("Annuler") } },
        )
    }
}

@Composable
private fun SessionRow(
    s: SessionMeta, selected: Boolean, onOpen: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit, onStar: () -> Unit, onExport: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val bg = if (selected) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(bg).clickable { onOpen() }.padding(start = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            when (s.category) { "logiciel" -> "📦"; "image" -> "🖼"; else -> "💬" } + (if (s.starred) "⭐" else ""),
            fontSize = 14.sp,
        )
        Spacer(Modifier.width(8.dp))
        Text(s.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
        Box {
            IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "Options") }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text("Renommer") }, { menu = false; onRename() })
                DropdownMenuItem({ Text(if (s.starred) "Retirer des favoris" else "Ajouter aux favoris") }, { menu = false; onStar() })
                DropdownMenuItem({ Text("Exporter en .md") }, { menu = false; onExport() })
                DropdownMenuItem({ Text("Supprimer") }, { menu = false; onDelete() })
            }
        }
    }
}


// ------------------------------------------------------------------ Mode Téléphone

@Composable
fun ActionCard(vm: AppViewModel, idx: Int, m: Msg) {
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    val calls = androidx.compose.runtime.remember(m.ref) { Agent.decode(m.ref) }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("📱 Actions sur le téléphone", style = MaterialTheme.typography.labelMedium, color = grey)
            when (m.extra) {
                "plan" -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("L'IA prépare les actions…", color = grey)
                }
                "pending" -> {
                    if (m.text.isNotBlank()) Text(m.text)
                    calls.forEach { c ->
                        val sensitive = Agent.risk(c) == 2
                        Text(
                            (if (sensitive) "⚠ " else "• ") + Agent.describe(c), fontSize = 14.sp,
                            fontFamily = if (sensitive) androidx.compose.ui.text.font.FontFamily.Monospace else androidx.compose.ui.text.font.FontFamily.Default,
                        )
                    }
                    if (calls.any { Agent.risk(it) == 2 }) {
                        Text("Une commande sera exécutée dans la machine de Studo (jamais sur le système du téléphone). Vérifie-la avant d'autoriser.", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ vm.runActions(idx) }) { Text("Autoriser") }
                        OutlinedButton({ vm.refuseActions(idx) }) { Text("Refuser") }
                    }
                }
                "running" -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Exécution…", color = grey)
                }
                "refused" -> Text("Refusé : rien n'a été fait.", color = grey)
                else -> SelectionContainer { Text(m.text) }
            }
        }
    }
}
