package com.bovka.focus

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesTest {
    private fun rule(pkg: String) = Rules.byPackage.getValue(pkg)

    private val yt = "com.google.android.youtube"
    private val ig = "com.instagram.android"
    private val fb = "com.facebook.katana"
    private val snap = "com.snapchat.android"

    // --- ce qui doit passer sous le voile noir ---

    @Test fun youtubeShortsShelfById() = assertTrue(rule(yt).isShelf("reel_shelf", null))
    @Test fun youtubeShortsItemByLabelEn() = assertTrue(rule(yt).isShelf(null, "Chat drôle - play Short"))
    @Test fun youtubeShortsItemByLabelFr() = assertTrue(rule(yt).isShelf(null, "Chat drôle - lire le Short"))
    @Test fun youtubeShortsHeader() = assertTrue(rule(yt).isShelf(null, "Shorts"))
    @Test fun instagramReelThumbEn() = assertTrue(rule(ig).isShelf(null, "Reel by marie"))
    @Test fun instagramReelThumbFr() = assertTrue(rule(ig).isShelf(null, "Reel de marie"))
    @Test fun instagramReelGridById() = assertTrue(rule(ig).isShelf("clips_media_component", null))
    @Test fun facebookReelsLabel() = assertTrue(rule(fb).isShelf(null, "Reels"))
    @Test fun snapchatSpotlightLabel() = assertTrue(rule(snap).isShelf(null, "Spotlight"))

    // --- ce qui ne doit JAMAIS être masqué : messages, stories, vidéos normales ---

    @Test fun neverVeilMessagesOrStories() {
        val safeIds = listOf(
            "direct_tab", "inbox_refresh_container", "thread_message_list", "row_thread_composer_edittext",
            "reels_tray_container", "stories_tray", "story_viewer_container", "reel_viewer_root",
            "action_bar_inbox_button", "clips_tab", "feed_tab", "snap_chat_list", "chat_input",
        )
        val safeLabels = listOf(
            "Story de marie", "Marie's story", "Messages", "Message envoyé", "Chat with Léa",
            "Cours de maths - lecture 45 minutes", "Accueil", "Abonnements", "Direct",
        )
        for (r in Rules.all) {
            for (id in safeIds) assertFalse("${r.label} masque l'id $id", r.isShelf(id, null))
            for (l in safeLabels) assertFalse("${r.label} masque « $l »", r.isShelf(null, l))
        }
    }

    // --- les écrans bloqués restent bloqués ---

    @Test fun youtubeShortsPlayerIsBlocked() =
        assertTrue(rule(yt).blockIds!!.containsMatchIn("reel_player_page_container"))

    @Test fun snapchatSpotlightTabIsBlocked() =
        assertTrue(matchesAny(rule(snap).blockSelected, "spotlight_tab", null))

    @Test fun matchesAnyIsFalseWithoutRegex() = assertFalse(matchesAny(null, "x", "y"))
}
