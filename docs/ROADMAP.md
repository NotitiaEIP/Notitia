# Feuille de route

## Fait — macOS, itération 1

- [x] Socle : dépôt, projet Xcode (dossiers synchronisés), package `NotitiaKit`, 66 tests
- [x] Identité « Natif Apple », icône provisoire
- [x] Organisation : dossiers imbriqués, couleurs, icônes, glisser-déposer, épinglage, tags, corbeille, recherche, ⌘K
- [x] Éditeur par blocs : 12 types de blocs, menu `/`, raccourcis Markdown, mise en forme, images, autosave
- [x] Enregistrement : micro, transcription en direct (moteur de démonstration), lecture synchronisée
- [x] IA locale : résumé, mots-clés, carte mentale interactive, réécriture par mots-clés
- [x] Confidentialité : hors ligne, audio chiffré, consentement, conservation, export, effacement total
- [x] Prise en main : tutoriel au premier lancement, note « Aide » complète, menu Aide

## Prochaines étapes (ordre conseillé)

1. **Choisir et brancher le moteur de transcription réel.** Recommandation : Apple **SpeechAnalyzer**
   (macOS 26, sur l'appareil, gratuit, conforme à l'exigence hors ligne). Implémenter `TranscriptionEngine`,
   l'ajouter à `EngineCatalog`, puis tester en français sur une vraie réunion.
2. **Moteur génératif local** : Apple **Foundation Models** pour des résumés reformulés (et non plus seulement
   extractifs), en conservant `LocalExtractiveAIService` en repli. Attention à la fenêtre de contexte : découper
   les longues transcriptions.
3. **Tests manuels de l'enregistrement** sur plusieurs Mac (micro intégré, casque, permissions refusées).
4. **Éditeur** : annulation des opérations structurelles (aujourd'hui ⌘Z agit dans un bloc), collage
   multi-lignes en plusieurs blocs, sélection sur plusieurs blocs, tableaux, export PDF d'une note.
5. **Localisation anglaise** : les textes sont en français (langue source) ; remplir `Localizable.xcstrings`,
   et traduire la note d'aide (`NotitiaKit/Storage/HelpContent.swift`).
6. **Icône Liquid Glass** (Icon Composer) et vérification d'accessibilité (VoiceOver, contraste, taille du texte).
7. **iOS / iPadOS** : nouvelle cible qui réutilise `NotitiaKit` ; Apple Pencil (PencilKit) pour l'écriture manuscrite.
8. **Synchronisation** entre appareils (local-first), à concevoir avec les contraintes de PRIVACY.md §5.
9. **Android (Kotlin)**, **Windows (C#/WinUI 3)**, **Linux** : ré-implémenter le format `NoteDocument`
   et `BlockOperations` avec les mêmes tests (voir ARCHITECTURE.md).
10. **Questions sur toutes les notes** (RAG local) : embeddings sur l'appareil, en reprenant le découpage de
    l'ancienne app.
