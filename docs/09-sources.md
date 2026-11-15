# 9. Les sources

Tout ce qui suit a été consulté le 14 novembre 2026. Une source de presse ou une offre d'emploi est signalée comme telle : elle pèse moins qu'un dépôt de code ou qu'un article de l'équipe elle-même.

## Les leaders

| Produit | Source | Nature |
| --- | --- | --- |
| 1Password | [1Password 8: the story so far](https://blog.1password.com/1password-8-the-story-so-far) | Article de l'équipe |
| Bitwarden | [Architecture du SDK](https://contributing.bitwarden.com/architecture/sdk/), [ponts vers les langages](https://contributing.bitwarden.com/architecture/sdk/language-bindings), [architecture des clients](https://contributing.bitwarden.com/architecture/clients/) | Documentation de l'équipe |
| Bitwarden | [sdk-internal](https://github.com/bitwarden/sdk-internal), [ios](https://github.com/bitwarden/ios), [android](https://github.com/bitwarden/android), [clients](https://github.com/bitwarden/clients) | Dépôts de code |
| Signal | [libsignal](https://github.com/signalapp/libsignal), [Signal-Desktop](https://github.com/signalapp/Signal-Desktop) | Dépôts de code |
| Discord | [Annonce de la réécriture en Rust](https://www.invenglobal.com/articles/28182/discord-cto-confirms-the-desktop-and-mobile-apps-are-being-rebuilt-in-rust) | Presse, produit pas encore sorti |
| Discord | [Why Discord is sticking with React Native](https://discord.com/blog/why-discord-is-sticking-with-react-native) | Article de l'équipe, état d'avant |
| Spotify | [Building the future of our desktop apps](https://engineering.atspotify.com/2021/4/building-the-future-of-our-desktop-apps) | Article de l'équipe |
| Microsoft Office | [Same code, multiple platforms](https://rcpmag.com/articles/2018/01/30/office-same-code-multiple-platforms.aspx) | Presse |
| Microsoft Office | [Office et React Native](https://devblogs.microsoft.com/react-native/2025-05-09-office-modernize) | Article de l'équipe |
| Notion | [Notion sur Android, deux fois plus rapide](https://www.notion.com/blog/notion-on-android-is-now-more-than-twice-as-fast-to-launch), [SQLite dans le navigateur](https://www.notion.com/blog/how-we-sped-up-notion-in-the-browser-with-wasm-sqlite) | Articles de l'équipe |
| Claude | [Why is Claude an Electron app?](https://dbreunig.com/2026/02/21/why-is-claude-an-electron-app.html) | Article d'un tiers |
| Mullvad | [mullvadvpn-app](https://github.com/mullvad/mullvadvpn-app) | Dépôt de code |
| Anytype | [anytype-heart](https://github.com/anyproto/anytype-heart), [mode local](https://doc.anytype.io/anytype/data/sync-and-backup/local-only.md) | Dépôt de code et documentation |

## Les concurrents

| Produit | Source | Nature |
| --- | --- | --- |
| Granola | [Ingénieur ordinateur](https://builtin.com/job/product-engineer-desktop/7193316), [ingénieur iOS](https://builtin.com/job/product-engineer-ios/7193317), [ingénieur Windows](https://builtin.com/job/windows-product-engineer/9143678) | Offres d'emploi |
| Granola | [Granola pour Windows](https://www.granola.ai/blog/granola-for-windows-setup-teams-and-zoom-capture-and-what-it-controls) | Article de l'équipe |
| Plaud | [Plaud Desktop](https://the-gadgeteer.com/2026/01/04/plaud-desktop-captures-your-virtual-meetings-without-the-bot-awkwardness/) | Presse |
| Otter | [Ingénieur Otter Desktop](https://builtin.com/job/software-engineer-otter-desktop/2489327) | Offre d'emploi |
| Fireflies | [Ingénieur mobile React Native](https://jobs.khoslaventures.com/companies/fireflies-ai/jobs/64112384-mobile-engineer-react-native-platform) | Offre d'emploi |
| Jamie | [Où tournent les modèles de Jamie](https://www.meetjamie.ai/blog/llm-info) | Article de l'équipe |
| Circleback | [Notre moteur d'enregistrement réécrit en Swift](https://circleback.ai/blog/how-we-rebuilt-our-electron-recording-engine-in-swift) | Article de l'équipe |
| Hyprnote | [anarlog](https://github.com/fastrepl/anarlog), le nouveau nom du dépôt | Dépôt de code |
| Meetily | [meetily](https://github.com/Zackriya-Solutions/meetily) | Dépôt de code |
| OpenWhispr | [Séparation locale des locuteurs](https://openwhispr.com/blog/local-speaker-diarization) | Article de l'équipe |

## Les outils

| Outil | Source | Ce qu'on y a vérifié |
| --- | --- | --- |
| UniFFI | [mozilla/uniffi-rs](https://github.com/mozilla/uniffi-rs) | Ponts officiels vers Kotlin et Swift, pas encore en version 1.0 |
| Ponts tiers | [Gobley](https://github.com/gobley/gobley), [uniffi-bindgen-cs](https://github.com/NordSecurity/uniffi-bindgen-cs) | Encore jeunes : écartés |
| Tauri | [tauri.app](https://tauri.app) | Cœur Rust et interface web |
| Compose Desktop | [Accessibilité](https://kotlinlang.org/docs/multiplatform/compose-desktop-accessibility.html), [distribution](https://kotlinlang.org/docs/multiplatform/compose-native-distribution.html) | Lecteurs d'écran non pris en charge sur Linux, formats d'installation |
| Compose Desktop | [Toolbox, étude de cas](https://blog.jetbrains.com/kotlin/2021/12/compose-multiplatform-toolbox-case-study/) | Un produit réel construit avec |
| Linux | [Portail de capture d'écran](https://flatpak.github.io/xdg-desktop-portal/docs/doc-org.freedesktop.portal.ScreenCast.html), [discussion sur un portail audio](https://github.com/flatpak/xdg-desktop-portal/discussions/1142) | Le portail ne couvre pas le son |
| Capture | [Construire une app d'enregistrement sur ordinateur](https://recall.ai/blog/how-to-build-a-desktop-recording-app) | Les voies de capture par système |

## Ce qu'on n'a pas pu vérifier

- Les options exactes des moteurs Whisper et llama appelés depuis Rust. La fiche T-06 les mesure.
- Les commandes de capture du son sur Linux. Le prototype de la tâche T-05 les prouve.
- La technologie exacte de l'app mobile de Plaud : seul le titre d'une offre d'emploi parle de Flutter.
- Le détail interne de Granola, connu par ses offres d'emploi seulement.
