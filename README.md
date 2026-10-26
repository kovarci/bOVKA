# bOVKA — anti-scroll pour Android (Galaxy S24 FE)

Service d'accessibilité qui surveille Instagram, Facebook, Snapchat et YouTube et t'éjecte
des écrans de scroll infini, en gardant les messages et les stories.

| App | Autorisé | Bloqué / limité |
|---|---|---|
| Instagram | Messages, stories | Reels (bloqués), fil et explorer (budget de scroll) |
| Facebook | Stories | Fil, Reels, Vidéo (budget de scroll). Messages = app Messenger |
| Snapchat | Chats, stories | Spotlight |
| YouTube | Vidéos, recherche | Shorts |

Trois mécanismes : écrans interdits (éjection immédiate), budget de scroll vertical par session
(2 écrans par défaut), limite de temps par session hors conversations. Tout est réglable dans l'app.
Aucune permission réseau : rien ne quitte le téléphone.

## Compiler

Via GitHub Actions : pousse le repo, l'onglet *Actions* produit l'artefact `bovka-apk`.
En local : JDK 17 + Android SDK (platform 34) + Gradle 8.9, puis `gradle assembleDebug`.

## Installer

1. Copie `app-debug.apk` sur le téléphone et installe-le.
2. Android 13+ : Paramètres > Applications > bOVKA > ⋮ > **Autoriser les paramètres restreints**.
3. Paramètres > Accessibilité > Applications installées > bOVKA > activer.
4. Samsung : Batterie > Aucune restriction pour bOVKA, sinon One UI peut tuer le service.

## Ajuster les règles

Les identifiants de vues (`clips_tab`, `reel_recycler`...) changent selon les versions des apps.
Active le **mode diagnostic**, ouvre l'app sur l'écran concerné, copie le dump et corrige
[Rules.kt](app/src/main/java/com/bovka/focus/Rules.kt).
