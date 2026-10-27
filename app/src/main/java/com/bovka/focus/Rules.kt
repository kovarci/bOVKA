package com.bovka.focus

/**
 * Règles de détection par app. Les identifiants de vues changent selon les versions
 * des apps : le mode diagnostic de l'écran principal sert à les ajuster ici.
 */
class AppRule(
    val pkg: String,
    val label: String,
    val hint: String,
    /** Présence d'une vue dont l'id matche => écran interdit (Reels, Shorts...). */
    val blockIds: Regex? = null,
    /** Onglet sélectionné (id ou libellé) qui matche => écran interdit. */
    val blockSelected: Regex? = null,
    /** Présence d'une vue dont l'id matche => écran autorisé (messages, stories). */
    val safeIds: Regex? = null,
    /** Onglet sélectionné qui matche => écran autorisé. */
    val safeSelected: Regex? = null,
    /** Vues à cliquer pour renvoyer vers les messages après un blocage. */
    val redirectIds: List<String> = emptyList(),
    val redirectLabels: Regex? = null,
    /** Barre de stories du fil : le masque noir démarre juste en dessous. */
    val trayIds: Regex? = null,
    /** Barre du haut du fil : repli pour placer le masque quand la barre de stories a défilé hors écran. */
    val topBarIds: Regex? = null,
    /** Onglet « accueil » sélectionné : le masque ne s'affiche que là. */
    val feedSelected: Regex? = null,
    /**
     * Rayons et miniatures de vidéos courtes dans un écran autorisé (fil, accueil) : un voile
     * noir est posé sur leur rectangle, tout de suite, avant même d'avoir pu les regarder.
     */
    val shelfIds: Regex? = null,
    val shelfLabels: Regex? = null,
    /** Compte le scroll vertical des grandes listes dans le budget. */
    val scrollGuard: Boolean = true,
    /** Tente un "retour" avant de renvoyer à l'accueil. */
    val backFirst: Boolean = false,
    val defaultLimitMin: Int = 0,
)

private fun rx(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE)

/** Vrai si l'id ou le libellé d'une vue correspond à l'expression (qui peut être absente). */
fun matchesAny(rx: Regex?, id: String?, label: String?): Boolean =
    rx != null && ((id != null && rx.containsMatchIn(id)) || (label != null && rx.containsMatchIn(label)))

/** Cette vue est-elle une miniature ou un rayon de vidéos courtes à masquer ? */
fun AppRule.isShelf(id: String?, label: String?): Boolean =
    matchesAny(shelfIds, id, null) || matchesAny(shelfLabels, null, label)

object Rules {
    val all = listOf(
        AppRule(
            pkg = "com.instagram.android",
            label = "Instagram",
            hint = "Messages et stories OK. Reels bloqués, fil limité.",
            blockIds = rx("clips_viewer|clips_swipe|clips_pager|root_clips"),
            blockSelected = rx("^clips_tab$|^reels?\\b"),
            // Les boutons de la barre du haut (action_bar_inbox_button) et la pub Threads ne comptent pas :
            // sinon l'accueil serait toujours vu comme « messages » et rien ne serait bloqué.
            safeIds = rx("^(?!action_bar|.*button|.*threads).*(inbox|thread|composer|direct_text)|reel_viewer(?!_front_avatar|_back_avatar)|story_viewer|stories_viewer"),
            safeSelected = rx("^direct_tab$|^(messages?|messenger|direct)\\b"),
            redirectIds = listOf("direct_tab", "action_bar_inbox_button"),
            redirectLabels = rx("^(messages?|messenger|direct)\\b"),
            trayIds = rx("reels_tray|stories_tray|reel_tray"),
            topBarIds = rx("^action_bar_LinearLayout$|^main_feed_action_bar$"),
            feedSelected = rx("^feed_tab$|^(home|accueil)\\b"),
            // « Reel by x » / « Reel de x » : miniatures des Reels dans le fil et l'explorer.
            // Les ids en reel_* sont ceux des stories chez Instagram : on ne les touche pas.
            shelfIds = rx("clips_(media|item|grid|cell|thumbnail|preview)"),
            shelfLabels = rx("^reels?\\b"),
            defaultLimitMin = 10,
        ),
        AppRule(
            pkg = "com.facebook.katana",
            label = "Facebook",
            hint = "Stories OK. Fil, Reels et Vidéo limités. Messages : app Messenger.",
            blockSelected = rx("^(reels|video|watch|vidéos?)\\b"),
            shelfIds = rx("reels?_(unit|carousel|shelf|thumbnail|item)"),
            shelfLabels = rx("^reels?\\b"),
            defaultLimitMin = 5,
        ),
        AppRule(
            pkg = "com.snapchat.android",
            label = "Snapchat",
            hint = "Chats et stories OK. Spotlight bloqué.",
            blockSelected = rx("^spotlight\\b|spotlight_tab"),
            shelfIds = rx("spotlight"),
            shelfLabels = rx("^spotlight\\b"),
            defaultLimitMin = 10,
        ),
        AppRule(
            pkg = "com.google.android.youtube",
            label = "YouTube",
            hint = "Shorts bloqués. Les vidéos normales restent libres.",
            blockIds = rx("reel_recycler|reel_player_page_container|reel_player|shorts_player|reel_watch"),
            blockSelected = rx("^shorts$"),
            // Rayon Shorts de l'accueil et miniatures « … - play Short » / « … - lire le Short ».
            shelfIds = rx("reel_(shelf|item|multi_item|thumbnail)|shorts_(shelf|thumbnail|item)"),
            shelfLabels = rx("(play|lire( le)?)\\s+short\\b|^shorts?$"),
            scrollGuard = false,
            backFirst = true,
            defaultLimitMin = 0,
        ),
    )

    val byPackage: Map<String, AppRule> = all.associateBy { it.pkg }
}
