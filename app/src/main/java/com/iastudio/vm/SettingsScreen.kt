package com.iastudio.vm

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    val clip = LocalClipboardManager.current
    var newName by remember { mutableStateOf("") }
    var showHelp by remember { mutableStateOf(false) }

    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TitleBar("Réglages")

        // ---- Apparence
        Text("Apparence", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeMode.values().forEach { m -> FilterChip(vm.themeMode == m, { vm.updateTheme(m) }, { Text(m.label) }) }
        }

        // ---- Machines
        HorizontalDivider()
        Text("Machines virtuelles", style = MaterialTheme.typography.titleMedium)
        Text("Une machine est un espace isolé : ses IA, projets, images et conversations restent séparés.", color = grey, fontSize = 13.sp)
        vm.machines.forEach { n ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(vm.current == n, { vm.select(n) })
                Text(n, Modifier.weight(1f))
                TextButton({ vm.deleteMachine(n) }) { Text("Supprimer") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(newName, { newName = it }, Modifier.weight(1f), label = { Text("Nouvelle machine") }, singleLine = true)
            Spacer(Modifier.width(8.dp))
            Button({ vm.createMachine(newName); newName = "" }) { Text("Créer") }
        }

        // ---- IA
        HorizontalDivider()
        Text("Réglages de l'IA", style = MaterialTheme.typography.titleMedium)
        Text("Créativité : ${"%.1f".format(vm.temperature)}  (bas = précis, idéal pour le code)", color = grey)
        Slider(vm.temperature, { vm.updateTemperature(it) }, valueRange = 0f..1.2f)
        Text("Mémoire de conversation : ${vm.ctxSize}  (plus grand = plus de RAM)", color = grey)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(2048, 4096, 8192, 16384).forEach { c -> FilterChip(vm.ctxSize == c, { vm.updateCtx(c) }, { Text("$c") }) }
        }
        Text("Longueur maximale des réponses : ${vm.maxTokens}", color = grey)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(512, 1024, 2048, 4096).forEach { c -> FilterChip(vm.maxTokens == c, { vm.updateMaxTokens(c) }, { Text("$c") }) }
        }
        Text("Threads CPU : ${vm.threads}  (nombre de cœurs moins 1 conseillé)", color = grey)
        Slider(vm.threads.toFloat(), { vm.updateThreads(it.toInt()) }, valueRange = 1f..8f, steps = 6)
        OutlinedTextField(
            vm.customPrompt, { vm.updateCustomPrompt(it) }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), minLines = 2,
            label = { Text("Instructions personnalisées (ex. : réponds toujours brièvement)") },
        )
        OutlinedTextField(
            vm.hfToken, { vm.updateToken(it) }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("Jeton Hugging Face (modèles réservés, facultatif)") },
            visualTransformation = PasswordVisualTransformation(),
        )

        // ---- Logiciels et images
        HorizontalDivider()
        Text("Logiciels et images", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(vm.autoReview, { vm.updateReview(it) })
            Spacer(Modifier.width(8.dp))
            Text("Relecture automatique du code (plus fiable, deux fois plus long)", Modifier.weight(1f), fontSize = 13.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(vm.promptBoost, { vm.updateBoost(it) })
            Spacer(Modifier.width(8.dp))
            Text("L'IA de chat réécrit mes demandes d'images en description anglaise (meilleurs résultats)", Modifier.weight(1f), fontSize = 13.sp)
        }
        Text("Taille des images : ${vm.imageSize} px (256 pour tester vite)", color = grey)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(256, 384, 512, 768).forEach { c -> FilterChip(vm.imageSize == c, { vm.updateImageSize(c) }, { Text("$c") }) }
        }
        Text("Force de modification d'une image : ${(vm.strength * 100).toInt()} %  (bas = proche de l'original)", color = grey)
        Slider(vm.strength, { vm.updateStrength(it) }, valueRange = 0.2f..0.95f)

        // ---- Arrière-plan et téléphone
        HorizontalDivider()
        Text("Arrière-plan et mode Téléphone", style = MaterialTheme.typography.titleMedium)
        Text(
            "Les téléchargements et générations continuent quand tu quittes l'écran (une notification s'affiche). " +
                "Pour qu'Android ne ferme pas Studo, autorise-le à ignorer l'économie de batterie.",
            color = grey, fontSize = 13.sp,
        )
        val ctx = LocalContext.current
        OutlinedButton({
            try {
                ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName)))
            } catch (e: Exception) {
                try { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (e2: Exception) { }
            }
        }) { Text("Ne pas fermer Studo (batterie)") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(vm.autoRead, { vm.updateAutoRead(it) })
            Spacer(Modifier.width(8.dp))
            Text("Mode Téléphone : exécuter sans demander les actions de simple lecture (batterie, liste des applis, fichiers)", Modifier.weight(1f), fontSize = 13.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(vm.autoLow, { vm.updateAutoLow(it) })
            Spacer(Modifier.width(8.dp))
            Text("Mode Téléphone : exécuter aussi sans demander les actions courantes (alarme, minuteur, ouvrir une appli, lampe…). Les commandes shell demandent toujours ton accord.", Modifier.weight(1f), fontSize = 13.sp)
        }

        // ---- Téléchargements
        HorizontalDivider()
        Text("Téléchargements", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(vm.autoResume, { vm.updateAutoResume(it) })
            Spacer(Modifier.width(8.dp))
            Text("Reprendre automatiquement les téléchargements interrompus au lancement de Studo", Modifier.weight(1f), fontSize = 13.sp)
        }

        // ---- Notifications
        HorizontalDivider()
        Text("Notifications et émoticônes", style = MaterialTheme.typography.titleMedium)
        Text(
            "Une notification s'affiche quand un travail est terminé et que Studo n'est pas à l'écran. " +
                "Choisis l'émoticône de chaque type (laisse vide pour la valeur par défaut) : elle apparaît en grande icône et dans le titre.",
            color = grey, fontSize = 13.sp,
        )
        Notifier.defaults.keys.forEach { t ->
            var v by remember { mutableStateOf(Notifier.emoji(ctx, t)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Notifier.labels[t] ?: t, Modifier.weight(1f))
                OutlinedTextField(v, { nv -> v = nv.take(8); Notifier.setEmoji(ctx, t, v) }, Modifier.width(110.dp), singleLine = true)
            }
        }
        OutlinedButton({ Notifier.done(ctx, "done", "Test", "Voilà à quoi ressemblent tes notifications.", force = true) }) { Text("Tester une notification") }

        // ---- Termux
        HorizontalDivider()
        Text("Lien avec Termux", style = MaterialTheme.typography.titleMedium)
        Text(
            "Termux est un vrai terminal Linux sur Android (python, git, node, ssh, ffmpeg…). Une fois relié, le mode Téléphone " +
                "peut y lancer des commandes (toujours après ton accord).\n" +
                "1) Installe Termux depuis F-Droid ou GitHub (pas le Play Store).\n" +
                "2) Copie la commande ci-dessous, colle-la dans Termux, puis ferme et rouvre Termux.\n" +
                "3) Touche « Autoriser Studo », puis « Tester ».",
            color = grey, fontSize = 13.sp,
        )
        val clipTx = LocalClipboardManager.current
        val termuxPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            vm.status = if (ok) "Autorisation Termux accordée ✓" else "Autorisation Termux refusée"
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ clipTx.setText(AnnotatedString(TermuxBridge.SETUP_COMMAND)); vm.status = "Commande copiée : colle-la dans Termux" }) { Text("Copier la commande") }
            OutlinedButton({
                val i = ctx.packageManager.getLaunchIntentForPackage(TermuxBridge.PKG)
                if (i != null) ctx.startActivity(i) else vm.status = "Termux n'est pas installé"
            }) { Text("Ouvrir Termux") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ termuxPerm.launch(TermuxBridge.PERMISSION) }) { Text("Autoriser Studo") }
            OutlinedButton({ vm.testTermux() }) { Text("Tester") }
        }

        // ---- Diagnostic
        HorizontalDivider()
        Text("Analyse complète", style = MaterialTheme.typography.titleMedium)
        Text(
            "Vérifie le téléphone, les moteurs, tous les modèles installés (fichiers complets) et la connexion à Hugging Face.",
            color = grey, fontSize = 13.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ vm.runDiagnostics() }, enabled = !vm.diagBusy) { Text("Analyser mon Studo") }
            if (vm.report.isNotEmpty()) {
                OutlinedButton({ clip.setText(AnnotatedString(vm.report.joinToString("\n"))) }) { Text("Copier le rapport") }
            }
        }
        if (vm.diagBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (vm.report.isNotEmpty()) {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    vm.report.toList().forEach { line ->
                        Text(
                            line, fontSize = 13.sp,
                            color = if (line.startsWith("✗")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }

        // ---- Notice
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Notice d'utilisation", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton({ showHelp = !showHelp }) { Text(if (showHelp) "Masquer" else "Afficher") }
        }
        if (showHelp) HelpContent()

        HorizontalDivider()
        Text("Studo $APP_VERSION · ${vm.engines}", color = grey, fontSize = 13.sp)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun HelpContent() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val lines = remember { ctx.assets.open("NOTICE.md").bufferedReader().readLines() }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        lines.forEach { l ->
            when {
                l.startsWith("# ") -> Text(l.drop(2), style = MaterialTheme.typography.headlineMedium)
                l.startsWith("## ") -> Text(l.drop(3), Modifier.padding(top = 14.dp), style = MaterialTheme.typography.titleLarge)
                l.startsWith("- ") -> Text("•  " + l.drop(2).replace("**", ""))
                l.isBlank() -> Spacer(Modifier.height(2.dp))
                else -> Text(l.replace("**", ""))
            }
        }
    }
}
