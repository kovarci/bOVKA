package com.bovka.focus

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Réglages persistants, partagés entre l'écran de config et le service. */
class Config(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("bovka", Context.MODE_PRIVATE)

    fun enabled(pkg: String) = p.getBoolean("on_$pkg", true)
    fun setEnabled(pkg: String, v: Boolean) = p.edit().putBoolean("on_$pkg", v).apply()

    /** Minutes max passées hors messages par session (0 = pas de limite). */
    fun limitMin(rule: AppRule) = p.getInt("lim_${rule.pkg}", rule.defaultLimitMin)
    fun setLimitMin(rule: AppRule, v: Int) = p.edit().putInt("lim_${rule.pkg}", v).apply()

    /** Masque noir sur le fil, sous la barre de stories. */
    fun cover(rule: AppRule) = p.getBoolean("cover_${rule.pkg}", rule.trayIds != null)
    fun setCover(rule: AppRule, v: Boolean) = p.edit().putBoolean("cover_${rule.pkg}", v).apply()

    /** Voile noir instantané sur les Shorts / Reels / Spotlight (écrans bloqués et miniatures). */
    fun veil(rule: AppRule) = p.getBoolean("veil_${rule.pkg}", true)
    fun setVeil(rule: AppRule, v: Boolean) = p.edit().putBoolean("veil_${rule.pkg}", v).apply()

    /** Nombre d'écrans de scroll tolérés par session avant d'être éjecté. */
    var budgetScreens: Int
        get() = p.getInt("budget", 2)
        set(v) = p.edit().putInt("budget", v).apply()

    var debug: Boolean
        get() = p.getBoolean("debug", false)
        set(v) = p.edit().putBoolean("debug", v).apply()

    private fun day() = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    fun countKick(reason: String) {
        val d = day()
        val n = if (p.getString("kd", "") == d) p.getInt("kc", 0) else 0
        val at = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        p.edit().putString("kd", d).putInt("kc", n + 1).putString("kl", "$at · $reason").apply()
    }

    /** Dernier blocage, pour comprendre ce qui se passe sans passer par un dump. */
    fun lastKick(): String = p.getString("kl", "") ?: ""

    fun kicksToday(): Int = if (p.getString("kd", "") == day()) p.getInt("kc", 0) else 0
}
