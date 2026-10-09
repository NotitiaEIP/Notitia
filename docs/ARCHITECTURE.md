# Architecture

## Vue d'ensemble

```
┌────────────────────────── Notitia (app macOS) ──────────────────────────┐
│  SwiftUI : barre latérale · liste · éditeur · inspecteur · réglages       │
│  AppKit  : BlockTextView (NSTextView) pour le texte des blocs             │
│  Contrôleurs : Workspace (fenêtre) · NoteEditorModel · RecordingController│
└──────────────────────────────┬───────────────────────────────────────────┘
                               │
┌──────────────────────────── NotitiaKit (package Swift) ──────────────────┐
│ Models   : RichText, Block, NoteDocument, MindMap, TranscriptSegment,     │
│            Folder / Note / Tag / Recording (SwiftData)                    │
│ Editor   : BlockOperations, MarkdownShortcut, MindMapLayout (pur, testé)  │
│ AI       : TranscriptionEngine, NoteAIService (protocoles) + implémentations│
│ Audio    : AudioRecorder (AVAudioEngine)                                  │
│ Privacy  : AudioVault (AES-GCM), KeyStore (trousseau), RetentionPolicy    │
│ Storage  : AppPaths, NotitiaStore (suppression réelle, maintenance), SampleData │
│ Export   : MarkdownRenderer, DataExporter (portabilité RGPD)              │
│ Search   : NoteSearch                                                     │
└───────────────────────────────────────────────────────────────────────────┘
```

**Règle** : tout ce qui n'est pas de l'affichage va dans `NotitiaKit`, sous forme de fonctions pures
quand c'est possible, et avec des tests. L'app iOS réutilisera ce package tel quel.

## Format de données portable

Le contenu d'une note est un `NoteDocument` sérialisé en **JSON versionné**, stocké dans `Note.contentData` :

```json
{
  "version": 1,
  "blocks": [
    { "id": "…", "kind": "heading1", "text": [{ "text": "Les tons" }] },
    { "id": "…", "kind": "todo", "isChecked": true, "indent": 1,
      "text": [{ "text": "Réviser " }, { "text": "jeudi", "style": { "bold": true, "highlight": "yellow" } }] },
    { "id": "…", "kind": "transcript", "recordingID": "…" },
    { "id": "…", "kind": "mindMap", "mindMap": { "title": "…", "root": { … } } }
  ]
}
```

Ce format est **le contrat commun à toutes les plateformes** :
- le texte riche est une liste de fragments (`runs`) et de styles **sémantiques** (`bold`, `color: "blue"`…),
  et non des attributs propres à une plateforme ;
- les positions sont en **unités UTF-16**, comme dans `NSString`, Java/Kotlin et C# ;
- les couleurs sont des **noms** (`NoteColor`), traduits par chaque plateforme en couleur système ;
- un type de bloc inconnu est lu comme un paragraphe : une ancienne version ne casse pas une note récente.

Les futures apps Android, Windows et Linux doivent lire et écrire exactement ce format (et ré-implémenter
`BlockOperations` avec les mêmes tests).

## Persistance

- **SwiftData**, base locale dans le conteneur sandbox : `Application Support/Notitia/Notitia.store`.
- `Folder` est arborescent (`parent` / `children`, cascade). `Note` → `Folder` (nullify), `Tag` (N–N),
  `Recording` (cascade).
- Audio : `Application Support/Notitia/Recordings/<uuid>.naudio`, **chiffré** (voir PRIVACY.md).
- Images : `Application Support/Notitia/Attachments/`.
- `NotitiaStore` centralise les suppressions pour qu'elles touchent **à la fois** la base et les fichiers.

## Éditeur par blocs

- `NoteEditorModel` garde les blocs en mémoire. Chaque modification passe par `BlockOperations`
  (split, merge, indent, convert, move…) puis est enregistrée dans la note avec un délai de 0,5 s.
- **Choix de `NSTextView` plutôt que `TextEditor`.** Le plan prévoyait `TextEditor` (macOS 26) avec
  `NSTextView` en repli. Nous avons pris directement le repli : un éditeur par blocs doit intercepter
  Entrée, Retour arrière, Tab et les flèches *avant* le traitement du texte (`textView(_:doCommandBy:)`),
  ce que `TextEditor` ne permet pas de façon fiable sur macOS.
- `RichTextConverter` traduit `RichText` ↔ `NSAttributedString`. Le style d'origine est porté par un attribut
  privé (`com.notitia.style`) : la relecture ne dépend jamais du rendu (un titre est en gras visuellement
  sans que son texte soit « gras »).
- Chaque bloc a son propre `UndoManager` (⌘Z agit sur le bloc actif).
- Mise en forme : les menus envoient des actions dans la chaîne des répondeurs (`NSApp.sendAction`),
  reçues par le `BlockTextView` qui a le focus.
- Mesure de hauteur : un `NSLayoutManager` de mesure séparé, et des réponses de taille indépendantes du
  cadre courant. Sinon, boucle de mise en page avec Auto Layout (bogue rencontré et corrigé pendant le développement).

## Transcription et IA

```swift
protocol TranscriptionEngine: Sendable {
    var descriptor: EngineDescriptor { get }   // nom, hors ligne ?, disponible ?
    func transcribe(audio: AsyncStream<AudioChunk>, locale: Locale) -> AsyncThrowingStream<TranscriptSegment, any Error>
}

protocol NoteAIService: Sendable {
    func summarize(_ source: AISource, focus: [String]) async throws -> [Block]
    func keywords(_ source: AISource, limit: Int) async throws -> [String]
    func mindMap(_ source: AISource, focus: [String]) async throws -> MindMapDocument
}
```

- `AudioRecorder` capture le micro, écrit un `.m4a` temporaire (chiffré à l'arrêt) et publie des
  `AudioChunk` (échantillons copiés, donc `Sendable`).
- Implémentations actuelles :
  - `DemoTranscriptionEngine` rejoue un script au rythme de l'audio. **Aucune reconnaissance réelle.**
  - `LocalExtractiveAIService` produit de vrais résultats hors ligne : sélection des phrases par fréquence des
    termes, repérage des décisions, actions et questions, entités nommées et dates (NaturalLanguage,
    NSDataDetector).
- Les résultats IA sont toujours des **blocs éditables** : l'utilisateur garde la main.
- Ajouter un moteur = une nouvelle implémentation + une entrée dans `EngineCatalog`. L'interface ne change pas.
  Candidats : Apple SpeechAnalyzer et Foundation Models (sur l'appareil, macOS 26), Deepgram, LLM cloud
  (opt-in uniquement, voir PRIVACY.md). Le prompt de carte mentale de l'ancienne app
  (`lib/services/mind_map_service.dart`, règle `sourceText`) est à reprendre pour un moteur génératif.

## Ce qui vient de l'ancienne application (Flutter)

Seuls des **concepts** ont été repris, pas de code : la taxonomie des nœuds de carte mentale et la règle
« chaque nœud cite son passage source », la structure du résumé riche, les paramètres Deepgram.
NFC, réunions LAN, mascotte, abonnement et authentification Supabase ont été abandonnés pour cette version.
