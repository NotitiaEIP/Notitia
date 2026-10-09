# Notitia

**Vos notes, vos cours et vos réunions — sur votre appareil, et nulle part ailleurs.**

Notitia est une application de prise de notes riche (dans l'esprit de Notion et d'Apple Notes) qui sait aussi
enregistrer et transcrire des réunions, puis transformer la transcription en résumé ou en carte mentale.
C'est le projet EIP Epitech de l'équipe Notitia, version 2 : une réécriture complète, native sur chaque plateforme.

| Plateforme | Langage | État |
|---|---|---|
| macOS | Swift / SwiftUI | **En cours** (`apple/`) |
| iOS | Swift / SwiftUI (partage `NotitiaKit`) | Prévu |
| Android | Kotlin / Jetpack Compose | Prévu |
| Windows | C# / WinUI 3 | Prévu |
| Linux | à décider (GTK 4) | Prévu |

![Note de cours](docs/screenshots/1-note-light.png)

## Fonctionnalités (macOS)

- **Prise en main** : tutoriel en 7 pages au premier lancement, puis une note **« Aide »** épinglée qui montre
  chaque type de bloc, chaque style et tous les raccourcis. Les deux restent accessibles depuis le menu
  Aide (`⌘?` et « Revoir le tutoriel »).
- **Organisation** : dossiers et sous-dossiers illimités (Cours › Chinois, Anglais, Maths…), icônes et couleurs,
  glisser-déposer des notes et des dossiers, épinglage, tags, corbeille (30 jours), recherche plein texte
  insensible aux accents, ouverture rapide (⌘K).
- **Éditeur par blocs** : texte, titres, listes à puces / numérotées / de tâches, citations, encadrés, code,
  séparateurs, images. Menu `/`, raccourcis Markdown (`# `, `- `, `[] `, `> `, ` ``` `, `---`),
  gras / italique / souligné / barré / code / couleur / surlignage / lien, indentation (Tab), réorganisation
  par glisser-déposer.
- **Enregistrement et transcription** : capture micro, transcription en direct, audio **chiffré**, lecture
  synchronisée (cliquer un horodatage), rappel du consentement des participants.
- **Intelligence locale** : résumé (points clés, décisions, actions, questions), mots-clés, carte mentale
  interactive reliée à la transcription, et « réécrire avec mes mots-clés ».
- **Confidentialité** : 100 % hors ligne, aucune connexion réseau autorisée, export complet, effacement total.
  Voir [docs/PRIVACY.md](docs/PRIVACY.md).

> Le moteur de transcription définitif n'est pas encore choisi : l'application utilise pour l'instant un
> moteur de **démonstration** qui rejoue un texte d'exemple au rythme de l'enregistrement. Le résumé et la
> carte mentale, eux, fonctionnent réellement (moteur extractif local). Voir [docs/ROADMAP.md](docs/ROADMAP.md).

## Prérequis

- macOS 26 (Tahoe) ou plus récent
- Xcode 26.3 ou plus récent (Swift 6.2)

Aucune dépendance externe, aucun gestionnaire de paquets, aucune clé d'API.

## Lancer le projet

```bash
open apple/Notitia.xcodeproj      # puis ⌘R dans Xcode
```

En ligne de commande :

```bash
cd apple
xcodebuild -project Notitia.xcodeproj -scheme Notitia -destination 'platform=macOS' build
```

Options de lancement utiles (Xcode › Product › Scheme › Edit Scheme › Arguments) :

| Argument | Effet |
|---|---|
| `-NotitiaDemoMode YES` | Base **en mémoire** pré-remplie avec des exemples (ne touche pas à vos données). |
| `-NotitiaSnapshots YES` | (Debug, avec le mode démo) parcourt quelques écrans et enregistre des captures PNG dans `~/Library/Containers/com.notitia.Notitia/Data/tmp/`. |
| `-NotitiaSnapshotTutorial YES` | (Debug, avec les deux options ci-dessus) démarre sur le tutoriel et en exporte chaque page, en clair et en sombre. |

## Tests

La logique métier vit dans le package `NotitiaKit` et se teste sans interface :

```bash
cd apple/NotitiaKit
swift test
```

66 tests couvrent : note d'aide, texte riche (dont Unicode/UTF-16), opérations de l'éditeur, raccourcis Markdown,
format de document, recherche, IA locale, moteur de démonstration, disposition des cartes mentales,
chiffrement de l'audio, conservation des données, export et effacement RGPD.

## Structure

```
Notitia/
├── apple/
│   ├── Notitia.xcodeproj   ← projet Xcode (dossiers synchronisés : tout fichier ajouté dans Notitia/ est compilé)
│   ├── Config/             ← entitlements (sandbox, micro, PAS de réseau)
│   ├── Notitia/            ← application macOS (SwiftUI + AppKit pour l'éditeur)
│   └── NotitiaKit/         ← package Swift partagé : modèles, éditeur, IA, audio, confidentialité, export
└── docs/
    ├── ARCHITECTURE.md     ← couches, format de données portable, choix techniques
    ├── DESIGN.md           ← nouvelle identité visuelle
    ├── PRIVACY.md          ← RGPD : registre des traitements, mesures, droits
    └── ROADMAP.md          ← suite du projet
```

## Contribuer

1. Une branche par fonctionnalité (`feature/…`), une pull request relue par un membre de l'équipe.
2. `swift test` doit passer et l'application doit compiler sans avertissement.
3. Toute logique non visuelle va dans `NotitiaKit`, avec ses tests.
4. **Jamais de clé d'API ni de secret dans le code.** Voir [docs/PRIVACY.md](docs/PRIVACY.md).
