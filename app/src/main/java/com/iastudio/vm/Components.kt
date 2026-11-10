package com.iastudio.vm

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

const val APP_VERSION = "0.9"

/** Logo Studo : un astérisque à six branches. */
@Composable
fun StudioLogo(logoSize: Dp = 28.dp, color: Color = MaterialTheme.colorScheme.primary) {
    Canvas(Modifier.size(logoSize)) {
        val s = this.size.minDimension
        val cx = this.size.width / 2f
        val cy = this.size.height / 2f
        val r = s * 0.42f
        val w = s * 0.14f
        for (i in 0 until 3) {
            val a = Math.toRadians((i * 60 + 90).toDouble())
            val dx = (r * cos(a)).toFloat()
            val dy = (r * sin(a)).toFloat()
            drawLine(color, Offset(cx - dx, cy - dy), Offset(cx + dx, cy + dy), strokeWidth = w, cap = StrokeCap.Round)
        }
    }
}

@Composable
fun TitleBar(title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StudioLogo(30.dp)
        Spacer(Modifier.width(10.dp))
        Text(title, style = MaterialTheme.typography.headlineMedium)
    }
}

fun formatSize(bytes: Long): String =
    if (bytes >= 1_000_000_000L) "%.2f Go".format(bytes / 1e9) else "%.0f Mo".format(bytes / 1e6)

fun formatSmall(bytes: Long): String = when {
    bytes < 0 -> ""
    bytes < 1024 -> "$bytes o"
    bytes < 1_000_000 -> "%.0f Ko".format(bytes / 1e3)
    else -> formatSize(bytes)
}

fun kindIcon(kind: String) = when (kind) {
    "image" -> "🖼"; "companion" -> "🧩"; "projector" -> "👁"; "unknown" -> "❓"; else -> "💬"
}

fun kindLabel(kind: String) = when (kind) {
    "image" -> "IA d'images"
    "companion" -> "Fichier complémentaire d'images (VAE, encodeur)"
    "projector" -> "Projecteur de vision (non utilisé seul)"
    "unknown" -> "Type inconnu"
    else -> "IA de chat / code"
}

fun displayName(name: String): String = name.removePrefix("img__")

/** Nom de fichier déduit d'un lien de téléchargement (en-tête Content-Disposition ou fin de l'adresse). */
fun fileNameFrom(url: String, cd: String?): String {
    if (!cd.isNullOrBlank()) {
        val m = Regex("filename\\*?=(?:UTF-8'')?\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(cd)
        val n = m?.groupValues?.get(1)?.trim()
        if (!n.isNullOrEmpty()) return Uri.decode(n).substringAfterLast('/')
    }
    return Uri.decode(url.substringBefore('?').substringAfterLast('/')).ifEmpty { "modele.gguf" }
}

private val THINK_BLOCK = Regex("(?s)<think>(.*?)(</think>|$)")

/** Affiche une réponse de l'IA : réflexion repliable, texte et blocs de code avec bouton Copier. */
@Composable
fun RichText(content: String) {
    val clip = LocalClipboardManager.current
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    val m = THINK_BLOCK.find(content)
    val think = m?.groupValues?.get(1)?.trim()
    val closed = m != null && m.groupValues[2].isNotEmpty()
    val body = if (m != null) content.removeRange(m.range).trim() else content
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (think != null) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.clickable { open = !open },
            ) {
                Column(Modifier.padding(10.dp)) {
                    val title = when {
                        !closed -> "💭 Réflexion en cours…"
                        open -> "💭 Réflexion (toucher pour masquer)"
                        else -> "💭 Réflexion (toucher pour voir)"
                    }
                    Text(title, style = MaterialTheme.typography.labelMedium, color = grey)
                    if (open || !closed) {
                        Text(if (closed) think else think.takeLast(600), color = grey, fontSize = 13.sp, lineHeight = 18.sp)
                    }
                }
            }
        }
        val parts = body.split("```")
        parts.forEachIndexed { i, p ->
            if (i % 2 == 0) {
                if (p.isNotBlank()) Md.Block(p.trim())
            } else {
                val lang = p.substringBefore('\n', "").trim()
                val code = p.substringAfter('\n', p).trimEnd()
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                        Row(
                            Modifier.padding(0.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(lang.ifBlank { "code" }, style = MaterialTheme.typography.labelMedium, color = grey)
                            Spacer(Modifier.width(16.dp))
                            TextButton({ clip.setText(AnnotatedString(code)) }) { Text("Copier") }
                        }
                        Text(
                            code, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp,
                            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ModelPicker(models: List<ModelEntry>, selected: ModelEntry, onPick: (ModelEntry) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        OutlinedButton({ open = true }) { Text(displayName(selected.name).take(40)) }
        DropdownMenu(open, { open = false }) {
            models.forEach { DropdownMenuItem({ Text(displayName(it.name)) }, { onPick(it); open = false }) }
        }
    }
}


/** Pastille de pièce jointe : le contenu du fichier n'est jamais affiché dans le message. */
@Composable
fun AttachmentChip(att: Att, onClick: (() -> Unit)? = null, onRemove: (() -> Unit)? = null) {
    val icon = when (att.kind) { "image" -> "🖼"; "pdf" -> "📕"; "zip" -> "🗜"; "office" -> "📘"; "media" -> "🎞"; "file" -> "📦"; else -> "📄" }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = if (onClick != null) Modifier.clickable { onClick() } else Modifier,
    ) {
        Row(Modifier.padding(start = 10.dp, top = 6.dp, bottom = 6.dp, end = if (onRemove == null) 10.dp else 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(icon)
            Spacer(Modifier.width(6.dp))
            Column {
                Text(att.name.take(28), fontSize = 13.sp, maxLines = 1)
                val sub = listOf(formatSmall(att.size), att.note).filter { it.isNotEmpty() }.joinToString(" · ")
                if (sub.isNotEmpty()) Text(sub, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                if (onClick != null) Text("toucher pour ouvrir", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
            }
            if (onRemove != null) {
                androidx.compose.material3.IconButton(onRemove, Modifier.size(32.dp)) {
                    androidx.compose.material3.Icon(Icons.Default.Close, "Retirer", Modifier.size(16.dp))
                }
            }
        }
    }
}
