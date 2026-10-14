# CLAUDE.md — Notitia (macOS)

Contexte pour reprendre le travail dans ce dépôt. Lis aussi `README.md` et `docs/` (ARCHITECTURE, DESIGN,
PRIVACY, ROADMAP) : ce fichier ne les répète pas, il dit ce qu'il faut savoir en plus.

| Pour… | Lire |
|---|---|
| Comprendre le produit, lancer, tester | `README.md` |
| Trouver le code d'une fonctionnalité | `docs/ARCHITECTURE.md` › « Où est quoi » |
| Le format JSON commun aux plateformes | `docs/ARCHITECTURE.md` › « Format de données portable » |
| Toucher à l'interface | `docs/DESIGN.md` |
| Toucher aux données, au réseau, à l'audio | `docs/PRIVACY.md` (§5 avant toute fonctionnalité réseau) |
| Choisir la prochaine tâche | `docs/ROADMAP.md` › « Prochaines étapes » |

## Le projet

Notitia est le projet **EIP Epitech** de l'équipe Notitia (GitHub : `NotitiaEIP`). La **v2** est une réécriture
complète, commencée le 2026-10-09 : une application **native par OS**. Ce dépôt (`NotitiaEIP/Notitia-macOS`)
contient l'app **macOS** en Swift/SwiftUI et le package partagé `NotitiaKit`, que la future app iOS réutilisera.

- **Ce qu'on veut** : une app de prise de notes riche, dans l'esprit de Notion et d'Apple Notes (dossiers et
  sous-dossiers, comme Cours › Chinois / Anglais / Maths, beaucoup de styles), qui sait aussi **enregistrer et
  transcrire une réunion**. La transcription peut ensuite être réécrite avec les mots-clés de l'utilisateur, en
  **résumé** ou en **carte mentale**. Plus tard : poser des questions sur toutes ses notes (RAG local).
- **Pourquoi une réécriture** : l'ancienne app Flutter (`../MVP_Notitia`) était jugée trop simple et trop
  enfantine (mascotte, néons, emojis). Elle est **abandonnée** : on n'y a repris que des concepts, jamais de code.
- **L'utilisateur** (Lucas, membre de l'équipe) écrit en **français**. Réponds en français. Les textes de l'app,
  les commentaires et la doc sont en français.

## Décisions déjà prises (ne pas les remettre en question sans qu'on le demande)

| Sujet | Décision |
|---|---|
| Cible | **macOS 26 (Tahoe) minimum**, Xcode 26.3+, Swift 6.2 (package) / Swift 6 + `MainActor` par défaut (app) |
| Identité | **« Natif Apple pur »** : couleur d'accent du système, SF Pro, SF Symbols, couleurs système, pas de couleur de marque ni d'emoji dans l'interface. Liquid Glass seulement pour les éléments flottants. Voir `docs/DESIGN.md` |
| Éditeur | Éditeur **par blocs typés**, texte riche. Chaque bloc est un `NSTextView` (`BlockTextView`), pas un `TextEditor` (il faut intercepter Entrée, Retour arrière, Tab et les flèches) |
| Données | SwiftData en local. Contenu d'une note = `NoteDocument` en **JSON versionné**, le format commun à toutes les plateformes (positions en UTF-16, styles et couleurs sémantiques) |
| Confidentialité | **100 % hors ligne, RGPD dès la conception** : sandbox **sans** `com.apple.security.network.client`, audio chiffré (AES-GCM, clé dans le trousseau), consentement avant chaque enregistrement, export complet, effacement total. Voir `docs/PRIVACY.md` |
| Transcription / LLM | Tout **sur l'appareil**, derrière les protocoles de `NotitiaKit/AI/AIProtocols.swift`. Transcription : **SpeechAnalyzer** (`AppleSpeechTranscriptionEngine`, par défaut) ou `DemoTranscriptionEngine`. Résumés : `LocalExtractiveAIService`. Ton : `ToneAnalyzer` (Foundation Models, repli NaturalLanguage) |
| Émotions | **Jamais déduites** (AI Act art. 5 §1 f : interdit au travail et à l'école). On annote ce qu'on entend (sons, volume) et le ton du **texte**, jamais une personne. Voir `docs/PRIVACY.md` §6 |
| Dépendances | **Aucune** : pas de SPM externe, pas de CocoaPods, pas de clé d'API |
| Abandonné de la v1 | NFC, réunions LAN, mascotte, abonnement, authentification Supabase |

## Règles de travail

- Toute logique non visuelle va dans **`NotitiaKit`**, en fonctions pures si possible, **avec des tests**.
- `swift test` doit passer et l'app doit compiler **sans avertissement**.
- Le projet Xcode utilise des **dossiers synchronisés** : un fichier ajouté dans `apple/Notitia/` est compilé
  automatiquement, il n'y a pas besoin de modifier `project.pbxproj`.
- Les résultats de l'IA sont toujours des **blocs éditables**.
- Aucune fonctionnalité réseau sans passer par la checklist de `docs/PRIVACY.md` §5 (opt-in, sous-traitant
  nommé, hébergement UE, mise à jour du registre). **Jamais de secret dans le dépôt.**
- Une branche par fonctionnalité (`feature/…`) et une PR relue. Ne commite et ne pousse que si on te le demande.
- Mets à jour `docs/ROADMAP.md` (et README/ARCHITECTURE si besoin) quand une étape est finie.

## Commandes

```bash
# Tests (logique métier)
cd apple/NotitiaKit && swift test

# Compiler et lancer (quitter l'app avant avec ⌘Q, sinon `open` remet l'ancienne instance au premier plan)
cd apple
xcodebuild -project Notitia.xcodeproj -scheme Notitia -destination 'platform=macOS' -derivedDataPath build build \
  && open build/Build/Products/Debug/Notitia.app
```

Arguments de lancement utiles : `-NotitiaDemoMode YES` (base **en mémoire** avec des exemples, ne touche ni aux
données ni aux réglages réels), `-NotitiaSnapshots YES` et `-NotitiaSnapshotTutorial YES` (Debug : captures PNG
dans `~/Library/Containers/com.notitia.Notitia/Data/tmp/`, utilisées pour `docs/screenshots/`).

## Pièges déjà rencontrés

- **« PCH was compiled with module cache path… »** : le cache `apple/NotitiaKit/.build` pointe vers un ancien
  chemin (le dépôt a été déplacé de `EIP/Notitia` vers `EIP/Notitia-macOS`). Supprime `apple/NotitiaKit/.build`
  ou fais Clean Build Folder (⇧⌘K).
- **Le tutoriel ne s'affiche que si la base est vide.** Pour revoir le premier lancement : Réglages ›
  Confidentialité › « Effacer toutes mes données », ou le menu Aide › « Revoir le tutoriel ». Ne lance pas de
  tests manuels sur les **vraies** données (bundle `com.notitia.Notitia`, conteneur sandbox) : utilise
  `-NotitiaDemoMode YES`.
- **Mise en page de l'éditeur** : la hauteur des blocs se mesure avec un `NSLayoutManager` séparé. Sinon on
  retombe dans une boucle de mise en page Auto Layout (bogue déjà corrigé une fois).
- **Captures d'écran** : la barre latérale et l'inspecteur (matériaux translucides) apparaissent vides, et les
  boutons AppKit rendus par `ImageRenderer` apparaissent comme des rectangles jaunes. C'est une limite de
  l'outil de capture, pas un bogue de l'app.
- Les **Outils d'écriture d'Apple** sont désactivés volontairement dans l'éditeur (ils peuvent passer par le
  Private Cloud Compute).
- **`EngineCatalog.aiService(id:)` ignore encore l'`id`** (seul le moteur extractif existe pour les résumés). Un
  nouveau moteur doit être ajouté à la liste **et** choisi dans cette fonction (`transcriptionEngine(id:)`, lui,
  choisit déjà selon l'`id`).
- **Modèle de langue de SpeechAnalyzer** : il peut être présent sur le disque mais pas encore « réservé » pour
  l'app (`AssetInventory.status` = `.supported`). `assetInstallationRequest` renvoie alors `nil` et le réserve sans
  rien télécharger. Le moteur le fait au démarrage ; un vrai téléchargement passe par Réglages › Transcription,
  jamais en pleine réunion. **Pas encore vérifié dans l'app sandboxée sans `network.client`.**
- **Foundation Models** : n'utiliser que `SystemLanguageModel` (sur l'appareil). Le SDK contient aussi
  `PrivateCloudComputeLanguageModel` (cloud) : interdit ici. Le libellé du ton est construit par le code
  (catégorie + sujet) : en texte libre, le modèle inventait des mots (« Remontade ») et jugeait.
- **Seuil de sentiment NaturalLanguage** : une phrase neutre en français sort autour de -0,4 ; seuil à 0,6.
- **Note d'aide** : son contenu n'est écrit qu'à la création. Une installation existante garde l'ancien texte ;
  pour la régénérer, supprimer la note (elle est recréée par le menu Aide).
- **Tests d'intégration** (vrais moteurs) : `NOTITIA_INTEGRATION=1 swift test --filter AppleEnginesIntegrationTests`.
  Le filtre utilise le nom du type, pas le nom affiché de la suite.
- **Tests et SwiftData** : n'ouvrir une base en mémoire (`NotitiaSchema.makeContainer(inMemory: true)`) que dans
  les suites `.serialized` qui en ont besoin. Une base ouverte en parallèle par une autre suite a fait échouer une
  fois, de façon intermittente, « Base de données et RGPD ». Un modèle non inséré suffit pour tester sa logique.
- **Captures en mode démo** sans fermer l'app de Lucas : `open -n build/Build/Products/Debug/Notitia.app --args
  -NotitiaDemoMode YES -NotitiaSnapshots YES`, puis fermer cette seule instance.

## État actuel (2026-10-13)

- `main` : `Initial macOS Notitia implementation` (itération 1), poussé sur `origin`
  (`git@github.com:NotitiaEIP/Notitia-macOS.git`).
- Branche **`feature/transcription-enrichie`** (non commitée au moment d'écrire) : itération 2, voir
  `docs/ROADMAP.md` › Fait. Transcription réelle SpeechAnalyzer, sons et volume, ton des passages, citations avec
  contexte, empreinte de la transcription d'origine. **95 tests** passent, plus 3 tests d'intégration vérifiés sur
  le Mac de Lucas (transcription française exacte, silence repéré, ton conflictuel repéré). L'app compile sans
  avertissement. Rendu vérifié par captures en mode démo (`docs/screenshots/6-…`, `7-…`).
- `CLAUDE.md` n'est pas encore versionné.
- Pas encore testé à la main : un vrai enregistrement dans l'app (micro, rires réels, réglage des seuils),
  l'installation du modèle depuis les Réglages dans le sandbox, plusieurs Mac.

## Prochaine étape conseillée

1. Tester un vrai enregistrement dans l'app (`docs/ROADMAP.md` › Prochaines étapes, point 1), puis relire et
   fusionner `feature/transcription-enrichie` par PR.
2. Résumés génératifs avec Foundation Models (nouveau `NoteAIService`, en gardant `LocalExtractiveAIService` en
   repli ; découper les longues transcriptions ; reprendre la règle « chaque nœud cite son passage source »).

## À ne pas oublier

- L'ancien dépôt Flutter contient des **clés d'API en clair dans l'historique git** (Anthropic, Gemini ×2,
  Mistral, Deepgram). Elles doivent être **révoquées** chez chaque fournisseur (PRIVACY.md §8).
- Le prompt de carte mentale de la v1 (`../MVP_Notitia/lib/services/mind_map_service.dart`, règle « chaque nœud
  cite son passage source ») est à reprendre pour les résumés et cartes mentales avec Foundation Models.
- L'ancien dossier `../Notitia` ne contient plus qu'un `.git` sans commit et deux `.gitignore` : c'est un
  reste du déplacement, il peut être supprimé (à confirmer avec Lucas avant).
- `../Allopets_APP` est un **autre** projet EIP (refuges animaliers), sans rapport avec Notitia.
