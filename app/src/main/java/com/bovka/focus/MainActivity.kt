package com.bovka.focus

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var cfg: Config
    private lateinit var statusTv: TextView
    private lateinit var kicksTv: TextView

    private val bg = Color.parseColor("#0F172A")
    private val cardColor = Color.parseColor("#1E293B")
    private val accent = Color.parseColor("#7C3AED")
    private val muted = Color.parseColor("#94A3B8")
    private val green = Color.parseColor("#34D399")
    private val amber = Color.parseColor("#FBBF24")

    private val match = LinearLayout.LayoutParams.MATCH_PARENT
    private val wrap = LinearLayout.LayoutParams.WRAP_CONTENT

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cfg = Config(this)
        window.statusBarColor = bg

        val scroll = ScrollView(this).apply { setBackgroundColor(bg) }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(36), dp(18), dp(40))
        }
        scroll.addView(col)

        col.addView(label("bOVKA", 30f, Color.WHITE, true))
        col.addView(label("Messages et stories oui, doomscroll non.", 14f, muted))

        // Statut
        val status = card()
        statusTv = label("", 18f, Color.WHITE, true)
        kicksTv = label("", 13f, muted)
        status.addView(statusTv)
        status.addView(kicksTv)
        status.addView(pill("Ouvrir les réglages d'accessibilité") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }, buttonParams())
        status.addView(label(
            "Active « bOVKA » dans Applications installées. Si l'interrupteur est grisé (Android 13+, appli installée hors Play Store) : " +
                "Infos de l'appli > ⋮ > Autoriser les paramètres restreints.", 12f, muted, top = 10))
        status.addView(pill("Ouvrir les infos de l'appli") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }, buttonParams())
        col.addView(status)

        // Une carte par app
        for (rule in Rules.all) {
            val c = card()
            val head = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            head.addView(label(rule.label, 18f, Color.WHITE, true), LinearLayout.LayoutParams(0, wrap, 1f))
            head.addView(Switch(this).apply {
                isChecked = cfg.enabled(rule.pkg)
                setOnCheckedChangeListener { _, v -> cfg.setEnabled(rule.pkg, v) }
            })
            c.addView(head)
            c.addView(label(rule.hint, 13f, muted))
            if (rule.trayIds != null) {
                c.addView(Switch(this).apply {
                    text = "Masquer le fil (noir sous les stories)"
                    setTextColor(Color.WHITE)
                    setPadding(0, dp(10), 0, 0)
                    isChecked = cfg.cover(rule)
                    setOnCheckedChangeListener { _, v -> cfg.setCover(rule, v) }
                })
            }
            c.addView(label("Temps max hors messages, par session", 13f, Color.WHITE, top = 12))
            c.addView(stepper({ cfg.limitMin(rule) }, { cfg.setLimitMin(rule, it) }, 5, 0, 60) {
                if (it == 0) "désactivé" else "$it min"
            })
            col.addView(c)
        }

        // Tolérance de scroll
        val tol = card()
        tol.addView(label("Scroll toléré avant éjection", 16f, Color.WHITE, true))
        tol.addView(label("Hauteurs d'écran par session (fil, grille, profil). 1 = très strict.", 13f, muted))
        tol.addView(stepper({ cfg.budgetScreens }, { cfg.budgetScreens = it }, 1, 1, 6) {
            if (it == 1) "1 écran" else "$it écrans"
        })
        col.addView(tol)

        // Diagnostic
        val diag = card()
        diag.addView(label("Diagnostic", 16f, Color.WHITE, true))
        diag.addView(label(
            "Si un blocage rate ou se déclenche à tort : active le mode, ouvre l'app sur l'écran concerné, " +
                "attends 3 s, reviens ici et copie. Le dump contient les identifiants de vues, pas le texte des messages " +
                "(mais relis-le avant de le partager).", 13f, muted))
        diag.addView(Switch(this).apply {
            text = "Mode diagnostic"
            setTextColor(Color.WHITE)
            isChecked = cfg.debug
            setOnCheckedChangeListener { _, v -> cfg.debug = v }
        })
        diag.addView(pill("Copier le dernier dump d'écran") {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("bOVKA dump", GuardService.lastDump.ifEmpty { "(vide)" }))
            Toast.makeText(this, "Dump copié", Toast.LENGTH_SHORT).show()
        }, buttonParams())
        col.addView(diag)

        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        val on = serviceEnabled()
        statusTv.text = if (on) "● Protection active" else "○ Protection inactive"
        statusTv.setTextColor(if (on) green else amber)
        kicksTv.text = "${cfg.kicksToday()} blocage(s) aujourd'hui"
    }

    private fun serviceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val cn = ComponentName(this, GuardService::class.java)
        return enabled.split(':').any {
            it.equals(cn.flattenToString(), true) || it.equals(cn.flattenToShortString(), true)
        }
    }

    // --- petits utilitaires d'UI ---

    private fun label(s: String, size: Float, color: Int, bold: Boolean = false, top: Int = 0) =
        TextView(this).apply {
            text = s
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(top), 0, 0)
        }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = GradientDrawable().apply {
            setColor(cardColor)
            cornerRadius = dp(16).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(12) }
    }

    private fun buttonParams() = LinearLayout.LayoutParams(match, dp(48)).apply { topMargin = dp(10) }

    private fun pill(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 14f
        setTextColor(Color.WHITE)
        minWidth = 0
        minHeight = 0
        background = GradientDrawable().apply {
            setColor(accent)
            cornerRadius = dp(24).toFloat()
        }
        setOnClickListener { onClick() }
    }

    private fun stepper(
        get: () -> Int,
        set: (Int) -> Unit,
        step: Int,
        min: Int,
        max: Int,
        fmt: (Int) -> String,
    ): LinearLayout {
        val value = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            minWidth = dp(110)
        }
        fun show() {
            value.text = fmt(get())
        }
        val minus = pill("−") {
            set((get() - step).coerceAtLeast(min))
            show()
        }
        val plus = pill("+") {
            set((get() + step).coerceAtMost(max))
            show()
        }
        show()
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
            addView(minus, LinearLayout.LayoutParams(dp(52), dp(44)))
            addView(value)
            addView(plus, LinearLayout.LayoutParams(dp(52), dp(44)))
        }
    }
}
