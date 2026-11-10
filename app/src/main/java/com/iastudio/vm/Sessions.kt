package com.iastudio.vm

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Pièce jointe : le contenu reste ici et n'est jamais collé dans le message affiché. */
data class Att(
    val name: String,
    val kind: String, // text | image | pdf | zip | office | media | file
    val text: String = "",
    val imagePath: String = "",
    val size: Long = 0,
    val note: String = "",
    val path: String = "", // copie du fichier d'origine (pour le visionneur)
)

/** Un message. kind : chat | project (carte logiciel) | image (carte image). */
data class Msg(
    val role: String,
    val text: String,
    val atts: List<Att> = emptyList(),
    val kind: String = "chat",
    val ref: String = "",
    val extra: String = "",
    val route: String = "",
)

data class SessionMeta(
    val id: String,
    val title: String,
    val category: String, // discussion | logiciel | image
    val starred: Boolean,
    val updated: Long,
)

fun categoryOf(msgs: List<Msg>): String {
    val first = msgs.firstOrNull { it.kind == "project" || it.kind == "image" }
    return when (first?.kind) {
        "project" -> "logiciel"
        "image" -> "image"
        else -> "discussion"
    }
}

/** Sauvegarde des sessions (une par fichier JSON, par machine). */
class SessionStore(private val dirOf: (String) -> File) {
    private fun file(vm: String, id: String) = File(dirOf(vm), "$id.json")

    private fun metaOf(o: JSONObject) = SessionMeta(
        o.getString("id"), o.optString("title", "Sans titre"), o.optString("cat", "discussion"),
        o.optBoolean("star", false), o.optLong("updated", 0L),
    )

    fun list(vm: String): List<SessionMeta> =
        (dirOf(vm).listFiles { f -> f.extension == "json" } ?: emptyArray()).mapNotNull { f ->
            try { metaOf(JSONObject(f.readText())) } catch (e: Exception) { null }
        }.sortedByDescending { it.updated }

    fun save(vm: String, id: String, title: String, starred: Boolean, updated: Long, msgs: List<Msg>) {
        try {
            val arr = JSONArray()
            msgs.forEach { m ->
                val atts = JSONArray()
                m.atts.forEach { a ->
                    atts.put(
                        JSONObject().put("n", a.name).put("k", a.kind).put("t", a.text).put("p", a.imagePath)
                            .put("s", a.size).put("note", a.note).put("f", a.path),
                    )
                }
                arr.put(
                    JSONObject().put("r", m.role).put("c", m.text).put("k", m.kind).put("ref", m.ref)
                        .put("x", m.extra).put("rt", m.route).put("atts", atts),
                )
            }
            val o = JSONObject().put("id", id).put("title", title).put("cat", categoryOf(msgs))
                .put("star", starred).put("updated", updated).put("msgs", arr)
            file(vm, id).writeText(o.toString())
        } catch (e: Exception) {
        }
    }

    fun load(vm: String, id: String): Pair<SessionMeta, List<Msg>>? {
        val f = file(vm, id)
        if (!f.exists()) return null
        return try {
            val o = JSONObject(f.readText())
            val a = o.getJSONArray("msgs")
            val msgs = (0 until a.length()).map { i ->
                val m = a.getJSONObject(i)
                val aa = m.optJSONArray("atts") ?: JSONArray()
                val atts = (0 until aa.length()).map { j ->
                    val x = aa.getJSONObject(j)
                    Att(x.optString("n"), x.optString("k"), x.optString("t"), x.optString("p"), x.optLong("s"), x.optString("note"), x.optString("f"))
                }
                Msg(
                    m.getString("r"), m.optString("c"), atts, m.optString("k", "chat"), m.optString("ref"),
                    m.optString("x"), m.optString("rt"),
                )
            }
            metaOf(o) to msgs
        } catch (e: Exception) {
            null
        }
    }

    fun delete(vm: String, id: String) { file(vm, id).delete() }
}
