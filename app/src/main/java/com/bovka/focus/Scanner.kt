package com.bovka.focus

import android.content.res.Resources
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/** Résultat d'un balayage de l'écran courant. */
class Scan {
    var blocked = false
    var reason = ""
    var safe = false
    var editable = false
    /** Bas de la barre de stories, haut de la barre d'onglets, et accueil sélectionné. */
    var trayBottom = 0
    var topBarBottom = 0
    var navTop = 0
    var onFeed = false
    /** Écran autorisé : conversation ou story. L'accueil visible (onglet sélectionné) ne l'est jamais. */
    val allowed get() = editable || (safe && !onFeed)
    /** Rectangles (écran) des miniatures et rayons de vidéos courtes à couvrir de noir. */
    val shelfRects = ArrayList<Rect>()
    val redirectById = ArrayList<AccessibilityNodeInfo>()
    val redirectByLabel = ArrayList<AccessibilityNodeInfo>()
}

object Scanner {
    private const val MAX_NODES = 3000
    private const val MAX_DUMP = 700
    private const val MAX_SHELF = 24
    private const val MIN_SHELF_PX = 80
    /** En dessous de cette fraction de la hauteur : barre d'onglets, jamais masquée. */
    private const val NAV_ZONE = 0.85f

    fun scan(root: AccessibilityNodeInfo, rule: AppRule): Scan {
        val s = Scan()
        val stack = ArrayList<AccessibilityNodeInfo>()
        stack.add(root)
        var seen = 0
        val r = Rect()
        val dm = Resources.getSystem().displayMetrics
        val screenH = dm.heightPixels
        val screenW = dm.widthPixels
        while (stack.isNotEmpty() && seen < MAX_NODES) {
            val node = stack.removeAt(stack.size - 1)
            seen++
            val id = node.viewIdResourceName?.substringAfter('/')
            val label = (node.contentDescription ?: node.text)?.toString()

            // Instagram garde la page des messages hors écran dans le même ViewPager : seules les vues
            // réellement affichées comptent pour savoir où l'on est.
            val shown = onScreen(node, r, screenW, screenH)

            // Un champ de saisie = on est dans une conversation.
            if (shown && node.isEditable) s.editable = true

            if (id != null) {
                if (!s.blocked && rule.blockIds?.containsMatchIn(id) == true) {
                    s.blocked = true
                    s.reason = id
                }
                if (shown && rule.safeIds?.containsMatchIn(id) == true) s.safe = true
                if (id in rule.redirectIds) s.redirectById.add(node)
                if (shown && rule.topBarIds?.containsMatchIn(id) == true && r.height() > 0 && r.bottom < screenH * 0.3) {
                    if (r.bottom > s.topBarBottom) s.topBarBottom = r.bottom
                }
                if (rule.trayIds?.containsMatchIn(id) == true && node.isVisibleToUser) {
                    node.getBoundsInScreen(r)
                    if (r.bottom > s.trayBottom) s.trayBottom = r.bottom
                }
            }
            if (s.shelfRects.size < MAX_SHELF && node.isVisibleToUser && rule.isShelf(id, label)) {
                node.getBoundsInScreen(r)
                val c = Rect(maxOf(r.left, 0), maxOf(r.top, 0), minOf(r.right, screenW), minOf(r.bottom, screenH))
                if (c.width() >= MIN_SHELF_PX && c.height() >= MIN_SHELF_PX && c.top < screenH * NAV_ZONE &&
                    s.shelfRects.none { it.contains(c) }
                ) {
                    s.shelfRects.add(c)
                }
            }
            if (node.isSelected) {
                if (node.isVisibleToUser && matchesAny(rule.feedSelected, id, label)) {
                    node.getBoundsInScreen(r)
                    if (r.top > screenH * 0.6) {
                        s.onFeed = true
                        s.navTop = r.top
                    }
                }
                if (!s.blocked && matchesAny(rule.blockSelected, id, label)) {
                    s.blocked = true
                    s.reason = id ?: label ?: "onglet"
                }
                if (matchesAny(rule.safeSelected, id, label)) s.safe = true
            }
            if (label != null && node.isClickable && rule.redirectLabels?.containsMatchIn(label) == true) {
                s.redirectByLabel.add(node)
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.add(it) }
            }
        }
        return s
    }

    /** Vue affichée à l'écran : visible, avec une surface positive dans l'écran. */
    private fun onScreen(node: AccessibilityNodeInfo, r: Rect, screenW: Int, screenH: Int): Boolean {
        if (!node.isVisibleToUser) return false
        node.getBoundsInScreen(r)
        return r.width() > 0 && r.height() > 0 && r.right > 0 && r.left < screenW && r.bottom > 0 && r.top < screenH
    }

    /**
     * Arbre de vues lisible pour le mode diagnostic. Le texte des vues n'est PAS inclus
     * (seulement id, description et drapeaux) pour ne pas exporter de messages privés.
     */
    fun dump(root: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        val count = intArrayOf(0)
        val r = Rect()

        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (count[0] >= MAX_DUMP) return
            count[0]++
            val id = n.viewIdResourceName?.substringAfter('/')
            val desc = n.contentDescription?.toString()?.take(40)
            if (id != null || desc != null || n.isEditable || n.isScrollable) {
                n.getBoundsInScreen(r)
                sb.append("  ".repeat(minOf(depth, 12)))
                sb.append(n.className?.toString()?.substringAfterLast('.') ?: "?")
                if (id != null) sb.append(" id=").append(id)
                if (desc != null) sb.append(" desc=\"").append(desc).append('"')
                val flags = StringBuilder()
                if (n.isSelected) flags.append('S')
                if (n.isEditable) flags.append('E')
                if (n.isClickable) flags.append('C')
                if (n.isScrollable) flags.append('V')
                if (flags.isNotEmpty()) sb.append(" [").append(flags).append(']')
                sb.append(" ").append(r.width()).append('x').append(r.height())
                sb.append('\n')
            }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { walk(it, depth + 1) }
            }
        }

        walk(root, 0)
        return sb.toString()
    }
}
