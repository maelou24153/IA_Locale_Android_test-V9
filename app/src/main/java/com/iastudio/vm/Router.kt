package com.iastudio.vm

import java.text.Normalizer

enum class Intent(val label: String) {
    CHAT("Discussion"), PROJECT("Logiciel"), IMAGE("Image"), ACTION("Téléphone"),
}

/** Devine ce que veut l'utilisateur : discussion, logiciel ou image. */
object Router {
    fun norm(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").replace('’', '\'')

    private val ask = Regex("^(comment|pourquoi|qu'est|quest|quel|quelle|quels|quelles|que |qui |ou |quand|est-ce|explique|c'est quoi|combien|what|how|why|who|when|peux-tu m'expliquer)")
    private val imgNouns = listOf(
        "image", "photo", "illustration", "dessin", "portrait", "paysage", "logo", "affiche", "wallpaper",
        "fond d'ecran", "icone", "picture", "poster", "peinture", "avatar",
    )
    private val imgVerbs = listOf(
        "genere", "cree", "creer", "dessine", "fais", "fait", "realise", "produis", "imagine", "montre", "peins",
        "draw", "generate", "make", "create", "je veux", "j'aimerais", "il me faut",
    )
    private val swNouns = listOf(
        "application", "appli", "logiciel", "site web", "site internet", "page web", "script", "programme",
        "calculatrice", "extension", "jeu", "outil", "interface", "landing", "portfolio", "to-do", "todo",
        "projet", "serveur", "dashboard", "tableau de bord", "bot ", "api ", "site ", "app ", "software", "website",
    )
    private val createVerbs = listOf(
        "cree", "creer", "fais", "fait", "genere", "developpe", "code ", "programme", "construis", "ecris",
        "realise", "build", "make", "create", "je veux", "j'aimerais", "il me faut", "besoin d'un", "besoin d'une",
    )
    private val editVerbs = listOf(
        "modifie", "modifier", "ajoute", "ajouter", "corrige", "corriger", "change", "changer", "enleve", "enlever",
        "supprime", "ameliore", "ameliorer", "mets", "mettre", "rends", "rendre", "remplace", "transforme",
        "retouche", "fix", "update", "edit", "en dessin", "en peinture", "style ", "cartoon", "anime",
    )

    private val phoneWords = listOf(
        "alarme", "reveil", "reveille-moi", "minuteur", "chrono ", "appelle ", "telephone a ", "envoie un sms", "envoie un message a", "un sms",
        "ouvre l'appli", "ouvre l'application", "lance l'appli", "lance l'application", "allume la lampe", "eteins la lampe", "lampe torche",
        "la torche", "monte le volume", "baisse le volume", "mets le volume", "volume a ", "ouvre les reglages", "ouvre le wifi", "ouvre le bluetooth",
        "ajoute au calendrier", "ajoute a mon calendrier", "rappelle-moi", "rappelle moi", "rendez-vous demain", "itineraire", "niveau de batterie",
        "ma batterie", "mon stockage", "copie dans le presse", "execute la commande", "lance la commande", "liste mes applis", "mes applications installees",
        "enregistre dans mes telechargements", "dans termux", "avec termux", "sur termux", "envoie un mail", "envoie un e-mail", "prepare un mail",
    )

    fun isPhone(text: String): Boolean {
        val n = " " + norm(text) + " "
        return phoneWords.any { n.contains(it) }
    }

    fun isEdit(text: String): Boolean {
        val n = " " + norm(text) + " "
        return editVerbs.any { n.contains(it) }
    }

    /**
     * @param attachedImage une image est jointe au message
     * @param lastKind type de la dernière carte de la session (« project », « image » ou null)
     * @return (intention, certitude)
     */
    fun guess(text: String, attachedImage: Boolean, lastKind: String?): Pair<Intent, Boolean> {
        val n = norm(text).trim()
        val padded = " $n "
        val isAsk = ask.containsMatchIn(n)
        val edit = editVerbs.any { padded.contains(it) }

        if (!attachedImage && isPhone(text)) return Intent.ACTION to true

        if (n.isEmpty()) return (if (attachedImage) Intent.IMAGE else Intent.CHAT) to attachedImage

        if (n.contains("dessine") || n.contains("peins") || n.startsWith("image de") || n.startsWith("image of") ||
            n.startsWith("photo de")
        ) return Intent.IMAGE to true
        if (attachedImage && edit) return Intent.IMAGE to true
        if (!isAsk && edit && lastKind == "project") return Intent.PROJECT to true
        if (!isAsk && edit && lastKind == "image") return Intent.IMAGE to true

        val hasImgNoun = imgNouns.any { n.contains(it) }
        val hasSwNoun = swNouns.any { padded.contains(it) }
        val wantsCreate = createVerbs.any { padded.contains(it) } || imgVerbs.any { padded.contains(it) }
        if (!isAsk && hasSwNoun && wantsCreate && !(hasImgNoun && (n.contains("logo") || n.contains("image") || n.contains("photo")) && !n.contains("site") && !n.contains("appli"))) {
            return Intent.PROJECT to true
        }
        if (!isAsk && hasImgNoun && wantsCreate) return Intent.IMAGE to true

        if (isAsk && !hasSwNoun && !hasImgNoun) return Intent.CHAT to true
        if (n.length < 25 && !hasSwNoun && !hasImgNoun) return Intent.CHAT to true
        return Intent.CHAT to false
    }
}
