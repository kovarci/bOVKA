package com.bovka.focus

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

private enum class Surface { SAFE, NEUTRAL, BLOCKED }

/**
 * Surveille les apps ciblées. Trois mécanismes :
 *  1. écrans interdits (Reels, Shorts, Spotlight) => éjection immédiate ;
 *  2. budget de scroll vertical par session, hors conversations ;
 *  3. limite de temps par session, hors conversations.
 * L'éjection clique d'abord sur l'onglet messages si l'app en a un, sinon retour/accueil.
 */
class GuardService : AccessibilityService() {

    private lateinit var cfg: Config
    private val handler = Handler(Looper.getMainLooper())
    private var overlay: View? = null
    private var cover: View? = null
    private var coverLp: WindowManager.LayoutParams? = null

    private var curPkg: String? = null
    private var leftAt = 0L
    private var scrollPx = 0
    private var lastFrom = -1
    private var unsafeMs = 0L
    private var lastEvalAt = 0L
    private var surface = Surface.NEUTRAL

    private var lastKickAt = 0L
    private var streak = 0
    private val pausedUntil = HashMap<String, Long>()
    private val lastBackAt = HashMap<String, Long>()

    private val ticker = object : Runnable {
        override fun run() {
            try {
                tick()
            } catch (_: Exception) {
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onServiceConnected() {
        cfg = Config(this)
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, TICK_MS)
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        handler.removeCallbacksAndMessages(null)
        removeOverlay()
        hideCover()
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        val pkg = e.packageName?.toString() ?: return
        val rule = Rules.byPackage[pkg]
        if (rule == null) {
            // Une autre fenêtre passe devant (accueil, volet de notifications) : on retire le masque,
            // puis on revérifie peu après au cas où ce n'était qu'un clavier ou une bulle.
            if (cover != null && e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg != packageName) {
                hideCover()
                handler.postDelayed({
                    try {
                        tick()
                    } catch (_: Exception) {
                    }
                }, 700)
            }
            return
        }
        if (!cfg.enabled(pkg)) return
        val now = SystemClock.elapsedRealtime()
        when (e.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> onScroll(e, rule, now)
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ->
                if (now - lastEvalAt >= EVAL_GAP_MS) foreground(rule, now, false)
        }
    }

    /** Appelé toutes les 3 s : fait avancer le chrono même sans événement. */
    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isInteractive) {
            hideCover()
            if (curPkg != null && leftAt == 0L) leftAt = now
            return
        }
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: return
        val rule = Rules.byPackage[pkg]
        if (rule == null) {
            hideCover()
            if (curPkg != null && leftAt == 0L) leftAt = now
            return
        }
        if (!cfg.enabled(pkg)) {
            hideCover()
            return
        }
        foreground(rule, now, true)
    }

    private fun newSession(pkg: String) {
        curPkg = pkg
        scrollPx = 0
        lastFrom = -1
        unsafeMs = 0
        lastEvalAt = 0
        surface = Surface.NEUTRAL
    }

    private fun foreground(rule: AppRule, now: Long, fromTick: Boolean) {
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != rule.pkg) {
            hideCover()
            return
        }
        if (now < (pausedUntil[rule.pkg] ?: 0L)) {
            hideCover()
            return
        }

        if (curPkg != rule.pkg || (leftAt != 0L && now - leftAt > SESSION_GAP_MS)) newSession(rule.pkg)
        leftAt = 0L

        val s = Scanner.scan(root, rule)
        val next = when {
            s.blocked -> Surface.BLOCKED
            s.safe || s.editable -> Surface.SAFE
            else -> Surface.NEUTRAL
        }

        // Le temps écoulé depuis le dernier balayage est imputé à la nature de l'écran précédent.
        val dt = if (lastEvalAt == 0L) 0L else minOf(now - lastEvalAt, MAX_GAP_MS)
        if (surface != Surface.SAFE) unsafeMs += dt
        lastEvalAt = now
        surface = next
        if (next == Surface.SAFE) scrollPx = 0

        updateCover(rule, s, next)

        if (fromTick && cfg.debug) {
            lastDump = "app=${rule.pkg} surface=$next blocked=${s.blocked}(${s.reason}) safe=${s.safe} " +
                "editable=${s.editable} unsafeMs=$unsafeMs scrollPx=$scrollPx " +
                "onFeed=${s.onFeed} trayBottom=${s.trayBottom} topBarBottom=${s.topBarBottom} navTop=${s.navTop} cover=${cover != null}\n" +
                Scanner.dump(root)
        }

        if (next == Surface.BLOCKED) {
            kick(s, rule, "${rule.label} : écran bloqué", now)
            return
        }
        // Pas d'éjection de la limite de temps si on est arrivé dans une conversation entre-temps.
        val limit = cfg.limitMin(rule) * 60_000L
        if (limit > 0 && next != Surface.SAFE && unsafeMs >= limit) kick(s, rule, "Temps écoulé sur ${rule.label}", now)
    }

    private fun onScroll(e: AccessibilityEvent, rule: AppRule, now: Long) {
        if (!rule.scrollGuard || surface == Surface.SAFE) return
        val src = e.source ?: return
        val r = Rect()
        src.getBoundsInScreen(r)
        val dm = resources.displayMetrics
        // On ne compte que les grandes listes verticales (fil, grille), pas les carrousels de stories.
        if (r.height() < dm.heightPixels * 0.45 || r.width() < dm.widthPixels * 0.6) return

        var px = abs(e.scrollDeltaY)
        if (px == 0) {
            // Les RecyclerView ne donnent pas de delta : on suit l'avancée de l'index visible.
            if (e.scrollDeltaX != 0) return
            val from = e.fromIndex
            if (from < 0 || from == lastFrom) return
            lastFrom = from
            px = dm.heightPixels / 2
        }
        scrollPx += px
        if (scrollPx < cfg.budgetScreens * dm.heightPixels) return

        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != rule.pkg) return
        val s = Scanner.scan(root, rule)
        if (s.safe || s.editable) {
            surface = Surface.SAFE
            scrollPx = 0
            return
        }
        kick(s, rule, "Stop le scroll sur ${rule.label}", now)
    }

    private fun kick(s: Scan, rule: AppRule, reason: String, now: Long) {
        if (now - lastKickAt < KICK_COOLDOWN_MS) return
        streak = if (now - lastKickAt < STREAK_GAP_MS) streak + 1 else 1
        lastKickAt = now

        // Garde-fou : si une règle déclenche en boucle, elle est mise en pause pour ne pas rendre l'app inutilisable.
        if (streak >= STREAK_MAX) {
            streak = 0
            pausedUntil[rule.pkg] = now + PAUSE_MS
            showMessage("bOVKA : règle instable sur ${rule.label}, pause 1 min")
            return
        }

        hideCover()
        cfg.countKick(reason)
        scrollPx = 0
        unsafeMs = 0
        showMessage("bOVKA · $reason")

        if (redirect(s)) return
        val lastBack = lastBackAt[rule.pkg] ?: 0L
        if (rule.backFirst && now - lastBack > BACK_RETRY_MS) {
            lastBackAt[rule.pkg] = now
            performGlobalAction(GLOBAL_ACTION_BACK)
        } else {
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    /** Clique sur l'onglet messages de l'app, s'il existe. */
    private fun redirect(s: Scan): Boolean {
        for (n in s.redirectById + s.redirectByLabel) {
            var cur: AccessibilityNodeInfo? = n
            var depth = 0
            while (cur != null && depth < 5) {
                if (cur.isClickable && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                cur = cur.parent
                depth++
            }
        }
        return false
    }

    private fun showMessage(text: String) {
        try {
            removeOverlay()
            val tv = TextView(this).apply {
                this.text = text
                setTextColor(Color.WHITE)
                textSize = 17f
                gravity = Gravity.CENTER
                setPadding(56, 36, 56, 36)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#EE0F172A"))
                    cornerRadius = 48f
                }
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.CENTER }
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(tv, lp)
            overlay = tv
            handler.postDelayed({ removeOverlay() }, 2200)
        } catch (_: Exception) {
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Masque noir sur le fil d'accueil : démarre sous la barre de stories et s'arrête au-dessus
     * des onglets. Les stories, la barre du haut et les onglets restent utilisables.
     */
    private fun updateCover(rule: AppRule, s: Scan, surface: Surface) {
        val h = resources.displayMetrics.heightPixels
        val top = if (s.trayBottom > 0) s.trayBottom else s.topBarBottom
        if (rule.trayIds == null || !cfg.cover(rule) || surface != Surface.NEUTRAL || !s.onFeed ||
            top <= 0 || top > h * 0.6 || s.navTop - top < 150
        ) {
            hideCover()
            return
        }
        showCover(top, s.navTop)
    }

    private fun showCover(top: Int, bottom: Int) {
        val height = bottom - top
        try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val existing = cover
            val lp = coverLp
            if (existing != null && lp != null) {
                if (lp.y != top || lp.height != height) {
                    lp.y = top
                    lp.height = height
                    wm.updateViewLayout(existing, lp)
                }
                return
            }
            val tv = TextView(this).apply {
                text = "Fil masqué par bOVKA\nMessages et stories seulement"
                setTextColor(Color.parseColor("#64748B"))
                textSize = 15f
                gravity = Gravity.CENTER
                setBackgroundColor(Color.BLACK)
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                height,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.OPAQUE,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = top
            }
            wm.addView(tv, params)
            cover = tv
            coverLp = params
        } catch (_: Exception) {
        }
    }

    private fun hideCover() {
        val v = cover ?: return
        cover = null
        coverLp = null
        try {
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (_: Exception) {
        }
    }

    private fun removeOverlay() {
        val v = overlay ?: return
        overlay = null
        try {
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TICK_MS = 3000L
        private const val EVAL_GAP_MS = 400L
        private const val SESSION_GAP_MS = 120_000L
        private const val MAX_GAP_MS = 20_000L
        private const val KICK_COOLDOWN_MS = 1500L
        private const val STREAK_GAP_MS = 2500L
        private const val STREAK_MAX = 5
        private const val PAUSE_MS = 60_000L
        private const val BACK_RETRY_MS = 6000L

        /** Dernier arbre de vues capturé en mode diagnostic (même processus que l'écran principal). */
        @Volatile
        var lastDump: String = ""
    }
}
