package com.iastudio.vm

import android.annotation.SuppressLint
import android.content.Intent as AndroidIntent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONTokener

const val BROWSER_HOME = "https://duckduckgo.com/"
const val HF_HOME = "https://huggingface.co/models?library=gguf&sort=downloads"

private const val DESKTOP_UA =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

private fun isModelUrl(u: String): Boolean {
    val p = u.substringBefore('?').lowercase()
    return p.endsWith(".gguf") || p.endsWith(".safetensors")
}

/**
 * Navigateur plein écran : adresse, précédent/suivant, favoris, mode bureau, « envoyer la page à l'IA »,
 * installation d'un modèle (.gguf, .safetensors) ou ajout d'un fichier téléchargé à la conversation.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(vm: AppViewModel, startUrl: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    var address by remember { mutableStateOf(startUrl) }
    var title by remember { mutableStateOf("") }
    var progress by remember { mutableIntStateOf(0) }
    var canBack by remember { mutableStateOf(false) }
    var canFwd by remember { mutableStateOf(false) }
    var desktop by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var stars by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<Pair<String, String>?>(null) }
    val webRef = remember { arrayOfNulls<WebView>(1) }

    BackHandler(enabled = true) {
        val w = webRef[0]
        if (w != null && w.canGoBack()) w.goBack() else onClose()
    }
    DisposableEffect(Unit) {
        onDispose {
            webRef[0]?.destroy()
            webRef[0] = null
        }
    }

    fun go(text: String) {
        val t = text.trim()
        val url = when {
            t.isEmpty() -> return
            t.startsWith("http://") || t.startsWith("https://") -> t
            t.contains(".") && !t.contains(" ") -> "https://$t"
            else -> "https://duckduckgo.com/?q=" + Uri.encode(t)
        }
        webRef[0]?.loadUrl(url)
    }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClose) { Icon(Icons.Default.Close, "Fermer le navigateur") }
            IconButton({ webRef[0]?.goBack() }, enabled = canBack) { Icon(Icons.Default.ArrowBack, "Précédent") }
            IconButton({ webRef[0]?.goForward() }, enabled = canFwd) { Icon(Icons.Default.ArrowForward, "Suivant") }
            IconButton({ webRef[0]?.reload() }) { Icon(Icons.Default.Refresh, "Actualiser") }
            IconButton({ webRef[0]?.loadUrl(BROWSER_HOME) }) { Icon(Icons.Default.Home, "Accueil") }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                androidx.compose.foundation.layout.Box {
                    IconButton({ stars = true }) { Icon(Icons.Default.Star, "Favoris") }
                    DropdownMenu(stars, { stars = false }) {
                        vm.bookmarks.toList().forEach { (t, u) ->
                            DropdownMenuItem(
                                { Text(t.take(34), fontSize = 14.sp) },
                                { stars = false; webRef[0]?.loadUrl(u) },
                                trailingIcon = { IconButton({ vm.removeBookmark(u) }) { Icon(Icons.Default.Close, "Retirer", Modifier.padding(4.dp)) } },
                            )
                        }
                        DropdownMenuItem({ Text("＋ Ajouter cette page") }, { stars = false; vm.addBookmark(title.ifBlank { address }, address) })
                    }
                }
                androidx.compose.foundation.layout.Box {
                    IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "Menu") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("Envoyer le texte de la page à l'IA") }, {
                            menu = false
                            webRef[0]?.evaluateJavascript("(function(){return document.title+'\\n\\n'+document.body.innerText;})()") { raw ->
                                val txt = try { JSONTokener(raw).nextValue() as? String ?: "" } catch (e: Exception) { "" }
                                if (txt.isBlank()) info = "Cette page ne contient pas de texte à envoyer."
                                else { vm.attachText(title.ifBlank { "page-web" }, txt.take(60000)); onClose() }
                            }
                        })
                        DropdownMenuItem({ Text(if (desktop) "✓ Version bureau" else "Version bureau") }, {
                            menu = false
                            desktop = !desktop
                            webRef[0]?.settings?.userAgentString = if (desktop) DESKTOP_UA else null
                            webRef[0]?.settings?.useWideViewPort = desktop
                            webRef[0]?.reload()
                        })
                        DropdownMenuItem({ Text("Copier l'adresse") }, { menu = false; clip.setText(AnnotatedString(address)) })
                        DropdownMenuItem({ Text("Ouvrir dans une autre appli") }, {
                            menu = false
                            try {
                                ctx.startActivity(AndroidIntent(AndroidIntent.ACTION_VIEW, Uri.parse(address)))
                            } catch (e: Exception) {
                                info = "Aucune appli ne peut ouvrir ce lien."
                            }
                        })
                        DropdownMenuItem({ Text("Hugging Face : modèles GGUF") }, { menu = false; webRef[0]?.loadUrl(HF_HOME) })
                    }
                }
            }
        }
        OutlinedTextField(
            address, { address = it }, Modifier.fillMaxWidth().padding(horizontal = 8.dp), singleLine = true,
            shape = RoundedCornerShape(24.dp),
            placeholder = { Text("Adresse ou recherche") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { go(address) }),
        )
        if (progress in 1..99) LinearProgressIndicator({ progress / 100f }, Modifier.fillMaxWidth().padding(top = 2.dp))
        if (info.isNotEmpty()) {
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(info, Modifier.weight(1f), fontSize = 13.sp)
                TextButton({ info = "" }) { Text("OK") }
            }
        }
        AndroidView(
            factory = { c ->
                WebView(c).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    settings.loadWithOverviewMode = true
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) { progress = newProgress }
                        override fun onReceivedTitle(view: WebView?, t: String?) { title = t.orEmpty() }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val u = request?.url?.toString() ?: return false
                            if (isModelUrl(u)) { pending = u to fileNameFrom(u, null); return true }
                            return false
                        }

                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            canBack = view?.canGoBack() == true
                            canFwd = view?.canGoForward() == true
                            if (url != null) address = url
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            canBack = view?.canGoBack() == true
                            canFwd = view?.canGoForward() == true
                            if (url != null) { address = url; vm.webUrl = url }
                        }
                    }
                    setDownloadListener { url, _, contentDisposition, _, _ ->
                        pending = url to fileNameFrom(url, contentDisposition)
                    }
                    webRef[0] = this
                    loadUrl(startUrl)
                }
            },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }

    pending?.let { (u, n) ->
        if (isModelUrl(n) || isModelUrl(u)) {
            AlertDialog(
                onDismissRequest = { pending = null },
                title = { Text("Installer ce fichier ?") },
                text = {
                    Column {
                        Text(n)
                        Text(
                            "Choisis ce que c'est. Studo vérifie ensuite l'en-tête du fichier et corrige si besoin.",
                            fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp),
                        )
                        TextButton({ vm.installFromUrl(u, n, false); pending = null }) { Text("IA de chat / code") }
                        TextButton({ vm.installFromUrl(u, n, true); pending = null }) { Text("IA d'images") }
                        TextButton({ vm.installFromUrl(u, n, false); pending = null }) { Text("Fichier complémentaire (VAE, encodeur…)") }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton({ pending = null }) { Text("Annuler") } },
            )
        } else {
            AlertDialog(
                onDismissRequest = { pending = null },
                title = { Text("Télécharger ce fichier ?") },
                text = { Text("$n\n\nIl sera joint à ta prochaine demande dans Studo, et tu pourras l'ouvrir dans la visionneuse.") },
                confirmButton = { TextButton({ vm.downloadAndAttach(u, n); pending = null }) { Text("Télécharger et joindre") } },
                dismissButton = { TextButton({ pending = null }) { Text("Annuler") } },
            )
        }
    }
}
