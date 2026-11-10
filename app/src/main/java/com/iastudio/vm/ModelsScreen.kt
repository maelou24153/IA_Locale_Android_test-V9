package com.iastudio.vm

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ModelsScreen(vm: AppViewModel, onBrowser: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxSize()) {
        TitleBar("Modèles")
        MachineBar(vm)
        Text(
            "RAM : ${"%.1f".format(vm.ramGb)} Go · conseillé : ${vm.ramAdvice()}",
            color = grey, fontSize = 13.sp, modifier = Modifier.padding(vertical = 4.dp),
        )
        StorageBar(vm)
        TabRow(selectedTabIndex = tab) {
            listOf("Installés", "Catalogue", "Hugging Face", "Web").forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t, maxLines = 1, fontSize = 13.sp) })
            }
        }
        DownloadsPanel(vm)
        when (tab) {
            0 -> InstalledTab(vm, Modifier.weight(1f))
            1 -> CatalogTab(vm, Modifier.weight(1f))
            2 -> HubTab(vm, Modifier.weight(1f))
            else -> Column(Modifier.weight(1f).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Le navigateur s'ouvre maintenant en plein écran : parcours Hugging Face ou n'importe quel site, touche un fichier .gguf ou .safetensors pour l'installer, " +
                        "ou envoie le texte d'une page à l'IA.",
                    color = grey,
                )
                Button(onBrowser) { Text("Ouvrir le navigateur plein écran") }
            }
        }
    }
}

/** Choix de l'emplacement d'installation : mémoire interne ou carte SD. */
@Composable
fun StorageBar(vm: AppViewModel) {
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.padding(vertical = 4.dp)) {
        Text("Installer les modèles sur :", color = grey, fontSize = 12.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            vm.volumes.toList().forEach { v ->
                FilterChip(
                    vm.storageId == v.id, { vm.chooseStorage(v.id) },
                    { Text("${v.label} · ${formatSize(v.free)} libres", fontSize = 12.sp) },
                )
            }
        }
        if (vm.volumes.size < 2) {
            Text("Aucune carte SD détectée. Insère-la : elle apparaîtra ici toute seule.", color = grey, fontSize = 11.sp)
        }
    }
}

@Composable
fun MachineBar(vm: AppViewModel) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text("Machine : ", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton({ open = true }) { Text(vm.current ?: "aucune") }
        DropdownMenu(open, { open = false }) {
            vm.machines.forEach { n -> DropdownMenuItem({ Text(n) }, { vm.select(n); open = false }) }
        }
        Text("  (créer / supprimer : Réglages)", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    }
}

@Composable
fun DownloadsPanel(vm: AppViewModel) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        vm.progress.keys.toList().forEach { k ->
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${vm.dlNames[k] ?: k}  ·  ${vm.progress[k] ?: 0} %", fontSize = 13.sp)
                    LinearProgressIndicator({ (vm.progress[k] ?: 0) / 100f }, Modifier.fillMaxWidth())
                }
                TextButton({ vm.cancelDownload(k) }) { Text("Annuler") }
            }
        }
        if (vm.status.isNotEmpty()) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(vm.status, Modifier.weight(1f), fontSize = 13.sp)
                TextButton({ vm.status = "" }) { Text("OK") }
            }
        }
    }
}

// ---------------------------------------------------------------- Installés

@Composable
fun InstalledTab(vm: AppViewModel, modifier: Modifier) {
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    var toDelete by remember { mutableStateOf<InstalledModel?>(null) }
    var toDeletePart by remember { mutableStateOf<String?>(null) }
    val total = vm.models.sumOf { it.size }
    Column(modifier.verticalScroll(rememberScrollState()).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "${vm.models.size} modèle(s) · ${formatSize(total)} utilisés · ${formatSize(vm.diskFree())} libres",
            color = grey,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ vm.verifyAll() }, enabled = vm.models.isNotEmpty()) { Text("Tout vérifier") }
            OutlinedButton({ vm.reloadModels() }) { Text("Actualiser") }
        }
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) vm.importFiles(uris)
        }
        Button({ picker.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth()) { Text("Installer depuis un fichier du téléphone") }
        Text(
            "Choisis un ou plusieurs fichiers déjà sur le téléphone (.gguf, .safetensors…). Studo reconnaît tout seul s'il s'agit d'une IA de chat, d'une IA d'images ou d'un fichier complémentaire (VAE, encodeur de texte).",
            color = grey, fontSize = 12.sp,
        )
        if (vm.models.isEmpty()) {
            Text("Aucun modèle installé. Ouvre l'onglet Catalogue ou Hugging Face, ou installe un fichier du téléphone.")
        }
        vm.models.toList().forEach { m ->
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(14.dp)) {
                    Text("${kindIcon(m.kind)} ${displayName(m.name)}")
                    Text(
                        "${kindLabel(m.kind)}${if (m.arch.isNotEmpty()) " · ${m.arch}" else ""} · ${formatSize(m.size)}" +
                            (if (m.size > vm.ramGb * 0.6e9) " · ⚠ lourd pour ta RAM" else ""),
                        color = grey, fontSize = 13.sp,
                    )
                    if (m.check.isNotEmpty()) {
                        Text(
                            (if (m.ok == true) "✓ " else "✗ ") + m.check,
                            color = if (m.ok == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            fontSize = 13.sp,
                        )
                    }
                    val missing = vm.missingCompanions(m)
                    if (missing.isNotEmpty()) {
                        Text(
                            "⚠ Il manque pour l'utiliser : ${missing.joinToString()}. Installe-les avec le bouton ci-dessus.",
                            color = MaterialTheme.colorScheme.error, fontSize = 13.sp,
                        )
                    }
                    var typeMenu by remember { mutableStateOf(false) }
                    Text("📍 ${m.place}", color = grey, fontSize = 12.sp)
                    Row {
                        TextButton({ vm.verifyOne(m) }) { Text("Vérifier") }
                        TextButton({ toDelete = m }) { Text("Désinstaller") }
                        vm.volumes.toList().filter { it.id != m.volumeId }.firstOrNull()?.let { other ->
                            TextButton({ vm.moveModel(m, other.id) }) { Text("→ ${if (other.id == "internal") "Interne" else "SD"}") }
                        }
                        androidx.compose.foundation.layout.Box {
                            TextButton({ typeMenu = true }) { Text("Type…") }
                            DropdownMenu(typeMenu, { typeMenu = false }) {
                                listOf("chat" to "IA de chat / code", "image" to "IA d'images", "companion" to "Fichier complémentaire").forEach { (k, l) ->
                                    DropdownMenuItem({ Text((if (m.kind == k) "✓ " else "") + l) }, { vm.setKind(m, k); typeMenu = false })
                                }
                            }
                        }
                    }
                }
            }
        }
        if (vm.partials.isNotEmpty()) {
            HorizontalDivider()
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Téléchargements interrompus", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                if (vm.partials.any { vm.canResume(it.first) }) TextButton({ vm.resumeAll() }) { Text("Tout reprendre") }
            }
            Text("Touche « Reprendre » pour continuer là où le téléchargement s'est arrêté. Sinon, supprime-les pour libérer de la place.", color = grey, fontSize = 13.sp)
            vm.partials.toList().forEach { (n, size) ->
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("${displayName(n.removeSuffix(".part"))}\n${formatSize(size)}", Modifier.weight(1f), fontSize = 13.sp)
                    if (vm.canResume(n) && vm.progress.keys.none { it == "resume-" + n.removeSuffix(".part") }) {
                        TextButton({ vm.resumePartial(n) }) { Text("Reprendre") }
                    }
                    TextButton({ toDeletePart = n }) { Text("Supprimer") }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
    toDelete?.let { m ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Désinstaller ce modèle ?") },
            text = { Text("${displayName(m.name)} (${formatSize(m.size)}) sera effacé du téléphone.") },
            confirmButton = { TextButton({ vm.deleteModel(m.name); toDelete = null }) { Text("Désinstaller") } },
            dismissButton = { TextButton({ toDelete = null }) { Text("Annuler") } },
        )
    }
    toDeletePart?.let { n ->
        AlertDialog(
            onDismissRequest = { toDeletePart = null },
            title = { Text("Supprimer ce téléchargement ?") },
            text = { Text("Le morceau déjà téléchargé de ${displayName(n.removeSuffix(".part"))} sera effacé.") },
            confirmButton = { TextButton({ vm.deletePartial(n); toDeletePart = null }) { Text("Supprimer") } },
            dismissButton = { TextButton({ toDeletePart = null }) { Text("Annuler") } },
        )
    }
}

// ---------------------------------------------------------------- Catalogue

private val CATEGORIES = listOf(
    "all" to "Tout", "chat" to "💬 Discussion", "code" to "💻 Code", "reflexion" to "🧠 Réflexion",
    "sansfiltre" to "🔓 Sans filtre", "multilingue" to "🌍 Multilingue", "petit" to "⚡ Légers",
    "image" to "🖼 Images", "realiste" to "📷 Réaliste", "art" to "🎨 Art", "rapide" to "🚀 Rapides",
    "img2img" to "🖌 Image→image", "edition" to "✏ Édition par instruction", "roleplay" to "🎭 Histoires", "flux" to "✨ Flux", "complement" to "🧩 Compléments", "gros" to "🏋 Gros (RAM élevée)",
)

@Composable
fun CatalogTab(vm: AppViewModel, modifier: Modifier) {
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    var cat by remember { mutableStateOf("all") }
    var onlyFit by remember { mutableStateOf(false) }
    val list = vm.catalog.filter { m ->
        (cat == "all" || m.tags.split(",").contains(cat)) && (!onlyFit || m.minRam <= vm.ramGb + 0.5)
    }
    Column(modifier.verticalScroll(rememberScrollState()).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CATEGORIES.forEach { (k, l) -> FilterChip(cat == k, { cat = k }, { Text(l, fontSize = 12.sp) }) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            OutlinedButton({ vm.checkLinks() }, enabled = !vm.checkingLinks) { Text("Vérifier les liens") }
            FilterChip(onlyFit, { onlyFit = !onlyFit }, { Text("Adaptés à ma RAM", fontSize = 12.sp) })
            if (vm.checkingLinks) Text("Vérification…", color = grey)
        }
        Text("${list.size} modèle(s)", color = grey, fontSize = 12.sp)
        if (cat == "sansfiltre") {
            Text(
                "Ces IA répondent sans les refus habituels. Tu restes responsable de l'usage que tu en fais. Les IA d'images de Studo n'ont, elles, aucun filtre intégré.",
                color = grey, fontSize = 12.sp,
            )
        }
        if (cat == "flux" || cat == "complement") {
            Text("Flux demande le modèle FLUX + les 3 fichiers complémentaires (VAE, clip_l, t5xxl). Compte 12 Go de RAM ou plus.", color = grey, fontSize = 12.sp)
        }
        list.forEach { m ->
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(14.dp)) {
                    Text("${if (m.type == "chat") "💬" else if (m.type == "companion") "🧩" else "🖼"} ${m.name}")
                    if (m.sizeGb > 0) Text("≈ ${"%.1f".format(m.sizeGb)} Go · RAM conseillée : ${m.minRam} Go", color = grey, fontSize = 12.sp)
                    if (m.minRam > vm.ramGb + 0.5) {
                        Text(
                            "⚠ ${m.minRam} Go de RAM conseillés (tu as ${"%.1f".format(vm.ramGb)} Go)",
                            color = MaterialTheme.colorScheme.error, fontSize = 13.sp,
                        )
                    }
                    vm.linkStatus[m.id]?.let { s ->
                        Text(
                            if (s == "ok") "✓ lien valide" else "✗ lien cassé ($s)",
                            color = if (s == "ok") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            fontSize = 13.sp,
                        )
                    }
                    when {
                        vm.progress.containsKey(m.id) -> Text("Téléchargement en cours…", color = grey)
                        m.file in vm.installed -> TextButton({ vm.deleteModel(m.file) }) { Text("Installé ✓  — désinstaller") }
                        else -> Button({ vm.install(m) }, Modifier.padding(top = 6.dp)) { Text("Installer") }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

// ---------------------------------------------------------------- Hugging Face

private fun quantOf(name: String): String =
    Regex("(?i)(IQ\\d[_A-Z0-9]*|Q\\d[_A-Z0-9]*|BF16|F16|F32)").find(name.substringAfterLast('/'))?.value?.uppercase() ?: ""

private fun compact(n: Long): String = when {
    n >= 1_000_000L -> "%.1f M".format(n / 1e6)
    n >= 1_000L -> "%.1f k".format(n / 1e3)
    else -> "$n"
}

@Composable
fun HubTab(vm: AppViewModel, modifier: Modifier) {
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    var link by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { if (vm.hfRepos.isEmpty() && !vm.hfLoading) vm.hfSearch() }
    Column(modifier.verticalScroll(rememberScrollState()).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(vm.hfType == "chat", { vm.hfType = "chat" }, { Text("Chat / code") })
            FilterChip(vm.hfType == "image", { vm.hfType = "image" }, { Text("Images") })
        }
        OutlinedTextField(
            vm.hfQuery, { vm.hfQuery = it }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("Rechercher un modèle (ex. qwen coder)") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.hfSearch() }),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ vm.hfSearch() }, enabled = !vm.hfLoading) { Text("Chercher") }
            OutlinedButton({ vm.hfSearch("") }, enabled = !vm.hfLoading) { Text("Populaires") }
        }
        val ideas = if (vm.hfType == "chat") listOf("qwen coder", "llama 3", "gemma", "phi", "deepseek", "mistral", "smollm")
        else listOf("stable diffusion", "sd turbo", "lcm", "sdxl")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ideas.forEach { s -> SuggestionChip(onClick = { vm.hfSearch(s) }, label = { Text(s) }) }
        }
        if (vm.hfLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        vm.hfError?.let { e ->
            Text(e, color = MaterialTheme.colorScheme.error)
            Text("Si la recherche ne marche pas, utilise l'onglet Web pour parcourir le site.", color = grey, fontSize = 13.sp)
        }

        vm.hfRepos.toList().forEach { r ->
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(14.dp)) {
                    Column(Modifier.fillMaxWidth().clickable { vm.hfOpen(r.id) }) {
                        Text(r.id)
                        Text("↓ ${compact(r.downloads)} · ♥ ${compact(r.likes)}", color = grey, fontSize = 13.sp)
                    }
                    if (vm.hfSelected == r.id) {
                        val limit = vm.ramGb * 1e9
                        val fit = vm.hfFiles.filter { it.size in 1..(limit * 0.4).toLong() }
                        val rec = fit.filter { it.name.contains("Q4_K_M", true) }.maxByOrNull { it.size }
                            ?: fit.maxByOrNull { it.size }
                        vm.hfFiles.toList().forEach { f ->
                            val badge = when {
                                f.size <= 0L -> ""
                                f.size <= limit * 0.4 -> "✓ adapté"
                                f.size <= limit * 0.6 -> "⚠ lourd"
                                else -> "✗ trop gros"
                            }
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        (if (f == rec) "★ " else "") + quantOf(f.name).ifEmpty { f.name.substringAfterLast('/') },
                                        fontSize = 14.sp,
                                    )
                                    Text(
                                        "${if (f.size > 0) formatSize(f.size) else "taille ?"} · $badge" + (if (f == rec) " · recommandé" else ""),
                                        color = grey, fontSize = 12.sp,
                                    )
                                }
                                Button({ vm.hfInstall(r.id, f.name) }, enabled = !vm.progress.containsKey("hf-${r.id}/${f.name}")) {
                                    Text("Installer")
                                }
                            }
                        }
                    }
                }
            }
        }

        HorizontalDivider()
        Text("Lien direct vers un fichier .gguf", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(link, { link = it }, Modifier.fillMaxWidth(), label = { Text("https://…/modele.gguf") }, singleLine = true)
        Button({
            if (link.startsWith("http")) {
                vm.installFromUrl(link.trim(), fileNameFrom(link.trim(), null), vm.hfType == "image")
                link = ""
            }
        }) { Text("Installer ce lien") }
        Spacer(Modifier.height(16.dp))
    }
}
