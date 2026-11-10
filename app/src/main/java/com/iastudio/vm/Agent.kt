package com.iastudio.vm

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Mode « Téléphone » : l'IA propose des actions, Studo les exécute avec les moyens que permet Android
 * (alarmes, minuteurs, applis, appels préparés, calendrier, lampe, volume, réglages, fichiers, commandes shell
 * dans la machine). Chaque action est montrée et doit être autorisée, sauf les actions de simple lecture
 * si tu actives « Exécuter sans demander » dans les réglages.
 */
object Agent {
    data class Call(val tool: String, val args: JSONObject)

    /** 0 = lecture seule, 1 = action normale, 2 = sensible (toujours confirmée). */
    private val risks = mapOf(
        "phone_info" to 0, "list_apps" to 0, "list_files" to 0, "read_file" to 0,
        "alarm" to 1, "timer" to 1, "open_app" to 1, "open_url" to 1, "web_search" to 1, "map" to 1, "dial" to 1,
        "sms" to 1, "email" to 1, "calendar_event" to 1, "flashlight" to 1, "volume" to 1, "open_settings" to 1,
        "clipboard" to 1, "share_text" to 1, "write_file" to 1, "save_to_downloads" to 1,
        "shell" to 2, "termux" to 2,
    )

    fun risk(c: Call) = risks[c.tool] ?: 2

    val toolDocs = """
phone_info {} : batterie, stockage, version d'Android
list_apps {} : liste des applications installées
alarm {"hour":7,"minute":30,"label":"Réveil"} : crée une alarme
timer {"seconds":600,"label":"Pâtes"} : lance un minuteur
open_app {"name":"YouTube"} : ouvre une application installée
open_url {"url":"https://..."} : ouvre une page web
web_search {"query":"..."} : recherche sur le web
map {"query":"pharmacie près de moi"} : ouvre la carte
dial {"number":"0612345678"} : prépare un appel (l'utilisateur appuie sur appeler)
sms {"number":"0612345678","text":"..."} : prépare un SMS (l'utilisateur appuie sur envoyer)
email {"to":"a@b.fr","subject":"...","body":"..."} : prépare un e-mail
calendar_event {"title":"...","start":"2026-11-10 14:30","minutes":60,"location":""} : prépare un événement
flashlight {"on":true} : lampe torche
volume {"level":50,"stream":"music"} : volume en % (music, ring, alarm)
open_settings {"page":"wifi"} : ouvre un réglage (wifi, bluetooth, display, sound, battery, apps, location, storage, date, accessibility, main)
clipboard {"text":"..."} : copie dans le presse-papiers
share_text {"text":"..."} : ouvre le menu Partager
write_file {"path":"notes/a.txt","content":"..."} : écrit un fichier dans la machine
read_file {"path":"notes/a.txt"} : lit un fichier de la machine
list_files {} : liste les fichiers de la machine
save_to_downloads {"name":"a.txt","content":"..."} : enregistre un fichier dans Téléchargements/Studo
shell {"command":"ls -la"} : commande shell dans le dossier de la machine (outils Android uniquement)
termux {"command":"pkg install -y python && python --version"} : commande bash dans Termux (vrai Linux : python, git, node, ssh, ffmpeg…)
""".trim()

    fun systemPrompt(): String =
        "Tu es l'assistant qui agit sur le téléphone Android de l'utilisateur. Tu choisis les actions à faire parmi les outils ci-dessous. " +
            "Réponds UNIQUEMENT avec un objet JSON : {\"say\":\"courte phrase pour l'utilisateur\",\"calls\":[{\"tool\":\"nom\",\"args\":{…}}]}. " +
            "Utilise uniquement les outils listés, avec les arguments indiqués. Si la demande ne correspond à aucun outil, mets calls à [] et explique dans say. " +
            "N'invente jamais de numéro ou d'adresse : si une information manque, mets calls à [] et demande-la dans say. " +
            "Date et heure actuelles : ${SimpleDateFormat("yyyy-MM-dd HH:mm (EEEE)", Locale.FRANCE).format(java.util.Date())}.\n\nOutils :\n$toolDocs"

    fun parse(raw: String): Pair<String, List<Call>> {
        val s = raw.substringAfter("</think>").trim()
        val a = s.indexOf('{')
        val b = s.lastIndexOf('}')
        if (a < 0 || b <= a) return s to emptyList()
        return try {
            val o = JSONObject(s.substring(a, b + 1))
            val arr = o.optJSONArray("calls") ?: JSONArray()
            val calls = (0 until arr.length()).mapNotNull {
                val c = arr.optJSONObject(it) ?: return@mapNotNull null
                val t = c.optString("tool")
                if (t in risks) Call(t, c.optJSONObject("args") ?: JSONObject()) else null
            }
            o.optString("say") to calls
        } catch (e: Exception) {
            s to emptyList()
        }
    }

    fun describe(c: Call): String {
        val a = c.args
        return when (c.tool) {
            "alarm" -> "Alarme à %02d:%02d %s".format(a.optInt("hour"), a.optInt("minute"), a.optString("label"))
            "timer" -> "Minuteur de ${a.optInt("seconds")} s ${a.optString("label")}"
            "open_app" -> "Ouvrir l'appli « ${a.optString("name")} »"
            "open_url" -> "Ouvrir ${a.optString("url")}"
            "web_search" -> "Chercher « ${a.optString("query")} »"
            "map" -> "Carte : ${a.optString("query")}"
            "dial" -> "Préparer un appel au ${a.optString("number")}"
            "sms" -> "Préparer un SMS au ${a.optString("number")} : « ${a.optString("text").take(80)} »"
            "email" -> "Préparer un e-mail à ${a.optString("to")} : ${a.optString("subject")}"
            "calendar_event" -> "Événement « ${a.optString("title")} » le ${a.optString("start")}"
            "flashlight" -> if (a.optBoolean("on", true)) "Allumer la lampe torche" else "Éteindre la lampe torche"
            "volume" -> "Volume ${a.optString("stream", "music")} à ${a.optInt("level")} %"
            "open_settings" -> "Ouvrir les réglages « ${a.optString("page")} »"
            "clipboard" -> "Copier dans le presse-papiers : « ${a.optString("text").take(80)} »"
            "share_text" -> "Partager un texte"
            "write_file" -> "Écrire le fichier ${a.optString("path")} (${a.optString("content").length} caractères)"
            "read_file" -> "Lire le fichier ${a.optString("path")}"
            "list_files" -> "Lister les fichiers de la machine"
            "save_to_downloads" -> "Enregistrer ${a.optString("name")} dans Téléchargements/Studo"
            "shell" -> "Commande : ${a.optString("command")}"
            "termux" -> "Termux : ${a.optString("command")}"
            "phone_info" -> "Lire l'état du téléphone (batterie, stockage)"
            "list_apps" -> "Lister les applications installées"
            else -> c.tool
        }
    }

    fun encode(calls: List<Call>): String {
        val arr = JSONArray()
        calls.forEach { arr.put(JSONObject().put("tool", it.tool).put("args", it.args)) }
        return arr.toString()
    }

    fun decode(s: String): List<Call> = try {
        val arr = JSONArray(s)
        (0 until arr.length()).map { Call(arr.getJSONObject(it).getString("tool"), arr.getJSONObject(it).optJSONObject("args") ?: JSONObject()) }
    } catch (e: Exception) {
        emptyList()
    }

    private fun safe(sandbox: File, rel: String): File {
        val f = File(sandbox, rel.trimStart('/')).canonicalFile
        if (!f.path.startsWith(sandbox.canonicalPath)) throw RuntimeException("chemin interdit (hors de la machine)")
        return f
    }

    private fun start(ctx: Context, i: Intent) {
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Exécute une action. Renvoie un court compte rendu. Lève une exception si ça échoue. */
    fun run(ctx: Context, c: Call, sandbox: File, export: (String, String) -> String): String {
        val a = c.args
        when (c.tool) {
            "phone_info" -> {
                val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                val st = StatFs(Environment.getDataDirectory().path)
                return "Batterie ${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)} % · stockage libre ${st.availableBytes / 1_000_000_000} Go · " +
                    "Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}"
            }
            "list_apps" -> {
                val pm = ctx.packageManager
                val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val l = pm.queryIntentActivities(i, 0).map { it.loadLabel(pm).toString() }.distinct().sorted()
                return l.joinToString(", ").take(1500).ifEmpty { "aucune appli visible" }
            }
            "alarm" -> {
                start(
                    ctx,
                    Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, a.getInt("hour")).putExtra(AlarmClock.EXTRA_MINUTES, a.optInt("minute"))
                        .putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label")).putExtra(AlarmClock.EXTRA_SKIP_UI, true),
                )
                return "Alarme créée à %02d:%02d".format(a.getInt("hour"), a.optInt("minute"))
            }
            "timer" -> {
                start(
                    ctx,
                    Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, a.getInt("seconds")).putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label"))
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true),
                )
                return "Minuteur lancé (${a.getInt("seconds")} s)"
            }
            "open_app" -> {
                val want = Router.norm(a.getString("name"))
                val pm = ctx.packageManager
                val l = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                val hit = l.firstOrNull { Router.norm(it.loadLabel(pm).toString()) == want }
                    ?: l.firstOrNull { Router.norm(it.loadLabel(pm).toString()).contains(want) }
                    ?: throw RuntimeException("aucune appli nommée « ${a.getString("name")} »")
                val launch = pm.getLaunchIntentForPackage(hit.activityInfo.packageName) ?: throw RuntimeException("appli non lançable")
                start(ctx, launch)
                return "Ouverture de ${hit.loadLabel(pm)}"
            }
            "open_url" -> {
                val u = a.getString("url")
                if (!u.startsWith("http://") && !u.startsWith("https://")) throw RuntimeException("seules les adresses http(s) sont permises")
                start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(u)))
                return "Page ouverte"
            }
            "web_search" -> {
                start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://duckduckgo.com/?q=" + Uri.encode(a.getString("query")))))
                return "Recherche lancée"
            }
            "map" -> {
                start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(a.getString("query")))))
                return "Carte ouverte"
            }
            "dial" -> {
                start(ctx, Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(a.getString("number")))))
                return "Numéro prêt dans le composeur : appuie sur Appeler"
            }
            "sms" -> {
                start(ctx, Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(a.getString("number")))).putExtra("sms_body", a.optString("text")))
                return "SMS prêt : appuie sur Envoyer"
            }
            "email" -> {
                start(
                    ctx,
                    Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + a.optString("to"))).putExtra(Intent.EXTRA_SUBJECT, a.optString("subject"))
                        .putExtra(Intent.EXTRA_TEXT, a.optString("body")),
                )
                return "E-mail prêt : appuie sur Envoyer"
            }
            "calendar_event" -> {
                val d = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse(a.getString("start")) ?: throw RuntimeException("date illisible")
                val end = d.time + a.optInt("minutes", 60) * 60_000L
                start(
                    ctx,
                    Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI).putExtra(CalendarContract.Events.TITLE, a.optString("title"))
                        .putExtra(CalendarContract.Events.EVENT_LOCATION, a.optString("location"))
                        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, d.time).putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end),
                )
                return "Événement prêt : appuie sur Enregistrer"
            }
            "flashlight" -> {
                val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
                    ?: throw RuntimeException("pas de lampe sur cet appareil")
                val on = a.optBoolean("on", true)
                cm.setTorchMode(id, on)
                return if (on) "Lampe allumée" else "Lampe éteinte"
            }
            "volume" -> {
                val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val stream = when (a.optString("stream", "music")) {
                    "ring" -> AudioManager.STREAM_RING
                    "alarm" -> AudioManager.STREAM_ALARM
                    else -> AudioManager.STREAM_MUSIC
                }
                val max = am.getStreamMaxVolume(stream)
                am.setStreamVolume(stream, (a.getInt("level").coerceIn(0, 100) * max / 100), 0)
                return "Volume réglé"
            }
            "open_settings" -> {
                val act = when (a.optString("page")) {
                    "wifi" -> Settings.ACTION_WIFI_SETTINGS
                    "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
                    "display" -> Settings.ACTION_DISPLAY_SETTINGS
                    "sound" -> Settings.ACTION_SOUND_SETTINGS
                    "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
                    "apps" -> Settings.ACTION_APPLICATION_SETTINGS
                    "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
                    "storage" -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
                    "date" -> Settings.ACTION_DATE_SETTINGS
                    "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
                    else -> Settings.ACTION_SETTINGS
                }
                start(ctx, Intent(act))
                return "Réglages ouverts (Android ne laisse pas une appli changer ce réglage à ta place)"
            }
            "clipboard" -> {
                (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Studo", a.getString("text")))
                return "Copié"
            }
            "share_text" -> {
                start(ctx, Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, a.getString("text")), "Partager").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return "Menu de partage ouvert"
            }
            "write_file" -> {
                val f = safe(sandbox, a.getString("path"))
                f.parentFile?.mkdirs()
                f.writeText(a.optString("content"))
                return "Fichier écrit : ${f.name} (${f.length()} octets)"
            }
            "read_file" -> {
                val f = safe(sandbox, a.getString("path"))
                if (!f.isFile) throw RuntimeException("fichier introuvable")
                return f.readText().take(3000)
            }
            "list_files" -> {
                val l = sandbox.walkTopDown().filter { it.isFile }.take(200).map { it.relativeTo(sandbox).path + " (" + it.length() + " o)" }.toList()
                return l.joinToString("\n").ifEmpty { "(dossier vide)" }
            }
            "save_to_downloads" -> {
                val where = export(a.getString("name").replace(Regex("[^A-Za-z0-9._-]"), "_"), a.optString("content"))
                return "Enregistré : $where"
            }
            "termux" -> return TermuxBridge.run(ctx, a.getString("command"))
            "shell" -> {
                val p = ProcessBuilder("/system/bin/sh", "-c", a.getString("command")).directory(sandbox).redirectErrorStream(true).start()
                val out = StringBuilder()
                val reader = Thread { try { p.inputStream.bufferedReader().use { r -> val buf = CharArray(2048); while (true) { val n = r.read(buf); if (n < 0) break; if (out.length < 4000) out.append(buf, 0, n) } } } catch (e: Exception) { } }
                reader.start()
                if (!p.waitFor(60, TimeUnit.SECONDS)) { p.destroyForcibly(); return "Arrêtée après 60 s\n" + out.toString().take(1500) }
                reader.join(1000)
                return "Code ${p.exitValue()}\n" + out.toString().take(3000)
            }
        }
        throw RuntimeException("outil inconnu : ${c.tool}")
    }
}
