# CLAUDE.md — l'app Mac

Les règles communes (produit, code, Git, PR, GitHub Project) sont dans l'`AGENTS.md` de la racine, et elles
priment. Ce fichier ne garde que ce qu'il faut savoir en plus pour l'app Mac. Les chemins partent de la racine
du repo.

| Pour… | Lire |
|---|---|
| Lancer et tester l'app | `macos/README.md` |
| Trouver le code d'une fonctionnalité | `macos/docs/ARCHITECTURE.md` › « Où est quoi » |
| Le format JSON des notes | `macos/docs/ARCHITECTURE.md` › « Format de données portable » (le format commun se décide dans T-07) |
| Toucher à l'interface | `macos/docs/DESIGN.md` |
| Toucher aux données, au réseau, à l'audio | `macos/docs/PRIVACY.md` (§5 avant toute fonctionnalité réseau) |
| Choisir la prochaine tâche | Le GitHub Project, comme le dit l'`AGENTS.md` |

## L'app

L'app Mac, en Swift/SwiftUI, enregistre une réunion, la transcrit sur le Mac, sépare les locuteurs et en tire un
résumé. Toute la logique vit dans le package `NotitiaKit`. L'app contient aussi un éditeur de notes par blocs,
venu de la première version : il reste dans le code, mais on ne lui ajoute plus de fonctionnalités (CADRE.md,
partie 5 : les entretiens demandent un CR, pas un carnet de notes).

## Décisions déjà prises (ne pas les remettre en question sans qu'on le demande)

| Sujet | Décision |
|---|---|
| Cible | **macOS 26 (Tahoe) minimum**, Xcode 26.3+, Swift 6.2 (package) / Swift 6 + `MainActor` par défaut (app) |
| Identité | **« Natif Apple pur »** : couleur d'accent du système, SF Pro, SF Symbols, couleurs système, pas de couleur de marque ni d'emoji dans l'interface. Liquid Glass seulement pour les éléments flottants. Voir `macos/docs/DESIGN.md` |
| Éditeur | Éditeur **par blocs typés**, texte riche. Chaque bloc est un `NSTextView` (`BlockTextView`), pas un `TextEditor` (il faut intercepter Entrée, Retour arrière, Tab et les flèches) |
| Données | SwiftData en local. Contenu d'une note = `NoteDocument` en **JSON versionné** (positions en UTF-16, styles et couleurs sémantiques) |
| Confidentialité | **100 % hors ligne, RGPD dès la conception** : sandbox **sans** `com.apple.security.network.client`, audio chiffré (AES-GCM, clé dans le trousseau), consentement avant chaque enregistrement, export complet, effacement total. Voir `macos/docs/PRIVACY.md` |
| Locuteurs | Séparation **sur l'appareil, sans modèle** (MFCC + hauteur, regroupement) : choix de Lucas pour rester sans dépendance. Les mesures de la voix ne sont **jamais conservées** ni comparées entre réunions (sinon identification biométrique, règle 4 de l'`AGENTS.md`). Voir `macos/docs/PRIVACY.md` §6 |
| Transcription / LLM | Tout **sur l'appareil**, derrière les protocoles de `NotitiaKit/AI/AIProtocols.swift`. Transcription : **SpeechAnalyzer** (`AppleSpeechTranscriptionEngine`, par défaut) ou `DemoTranscriptionEngine`. Résumés : `FoundationModelsAIService` (par défaut, sources vérifiées), repli `LocalExtractiveAIService`. Ton : `ToneAnalyzer` (Foundation Models, repli NaturalLanguage) |
| Émotions | **Jamais déduites** (AI Act art. 5 §1 f : interdit au travail et à l'école). On annote ce qu'on entend (sons, volume) et le ton du **texte**, jamais une personne. Voir `macos/docs/PRIVACY.md` §6 |
| Dépendances | **Aucune** : pas de SPM externe, pas de CocoaPods, pas de clé d'API |

## Règles de travail

- Toute logique non visuelle va dans **`NotitiaKit`**, en fonctions pures si possible, **avec des tests**.
- `swift test` doit passer et l'app doit compiler **sans avertissement**.
- Le projet Xcode utilise des **dossiers synchronisés** : un fichier ajouté dans `macos/apple/Notitia/` est compilé
  automatiquement, il n'y a pas besoin de modifier `project.pbxproj`.
- Les textes de l'interface sont en français, langue source de `Localizable.xcstrings`. Les commentaires du code
  Mac sont en français : un nouveau commentaire suit la langue du fichier. Les noms et les messages de commit
  restent en anglais, comme dans le reste du repo.
- Les résultats de l'IA sont toujours des **blocs éditables**.
- Aucune fonctionnalité réseau sans passer par la checklist de `macos/docs/PRIVACY.md` §5 (opt-in, sous-traitant
  nommé, hébergement UE, mise à jour du registre) et par une PR dédiée (règle 1 de l'`AGENTS.md`).
- Mets à jour `macos/README.md` (et `macos/docs/ARCHITECTURE.md` si besoin) quand une fonctionnalité change.

## Commandes

```bash
# Tests (logique métier)
cd macos/apple/NotitiaKit && swift test

# Compiler et lancer (quitter l'app avant avec ⌘Q, sinon `open` remet l'ancienne instance au premier plan)
cd macos/apple
xcodebuild -project Notitia.xcodeproj -scheme Notitia -destination 'platform=macOS' -derivedDataPath build build \
  && open build/Build/Products/Debug/Notitia.app
```

Arguments de lancement utiles : `-NotitiaDemoMode YES` (base **en mémoire** avec des exemples, ne touche ni aux
données ni aux réglages réels), `-NotitiaSnapshots YES` et `-NotitiaSnapshotTutorial YES` (Debug : captures PNG
dans `~/Library/Containers/com.notitia.Notitia/Data/tmp/`, utilisées pour `macos/docs/screenshots/`).

## Pièges déjà rencontrés

- **« PCH was compiled with module cache path… »** : le cache `macos/apple/NotitiaKit/.build` pointe vers un
  ancien chemin (le code a déménagé de `EIP/Notitia` vers `EIP/Notitia-macOS`, puis dans `Notitia/macos`).
  Supprime `macos/apple/NotitiaKit/.build` ou fais Clean Build Folder (⇧⌘K).
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
- **Nouveau moteur** : l'ajouter à la liste d'`EngineCatalog` **et** le choisir dans `transcriptionEngine(id:)` ou
  `aiService(id:)`.
- **Schémas `@Generable`** : ne pas réutiliser un même type dans plusieurs listes d'une réponse. Le modèle recopiait
  alors `#/$defs/PointReading` à la place de la citation (tout était rejeté par `SourceVerifier`, d'où un repli
  silencieux sur le moteur extractif). Demander la citation **avant** la reformulation.
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
- **Réunion de test avec audio** (sans micro) : `macos/scripts/reunion-test/generer.sh` fabrique une réunion de 2 min 30
  à trois voix de synthèse, dans le dossier temporaire du conteneur (le seul lisible par l'app sandboxée). Puis
  `macos/apple/build/Build/Products/Debug/Notitia.app/Contents/MacOS/Notitia -NotitiaDemoMode YES
  -NotitiaDemoAudio notitia-reunion-test.m4a` : la note « Réunion de test — sprint 3 » (Réunions) passe par la vraie transcription,
  les sons, l'empreinte et l'audio chiffré (`DemoAudioImport`, Debug uniquement). Le journal (`NSLog`) indique le
  nombre de segments. Un `AVAudioFile` en écriture doit être fermé (`close()`), sinon le fichier AAC est illisible.
  Les arguments sont dans le schéma partagé, **décochés** (Product › Scheme › Edit Scheme › Run › Arguments) : les
  cocher pour un ⌘R en mode démo. Le mode démo est en mémoire : tout disparaît en quittant l'app.
- **Référence faible à un modèle SwiftData** : après `save()`, le contexte ne retient plus forcément l'objet, et un
  `weak var` peut tomber à `nil`. Garder l'identifiant (`uid`) et relire le modèle dans le contexte. C'est ce qui
  faisait perdre la transcription à l'arrêt (« Aucune transcription. »).
- **`say` lancé depuis `swift test`** remplace sans prévenir la plupart des voix (Flo, Amélie, Grandpa…) par la voix
  par défaut, même sans les variables `DYLD_*`. Toutes les voix se ressemblaient, et le premier réglage des
  locuteurs était faux. Le test d'intégration des locuteurs lit donc les répliques produites par
  `macos/scripts/reunion-test/generer.sh` dans `macos/scripts/reunion-test/.phrases/` (ignoré par git).
- **SpeechAnalyzer ne coupe qu'aux longues pauses** : un résultat définitif contenait plusieurs répliques (11
  segments pour 2 min 30). Il est découpé en phrases avec l'horodatage des mots (`TimedText.sentences`). Deux
  répliques sans ponctuation entre elles restent dans la même phrase, donc avec un seul locuteur.
- **Captures en mode démo** sans fermer l'app ouverte : `open -n macos/apple/build/Build/Products/Debug/Notitia.app
  --args -NotitiaDemoMode YES -NotitiaSnapshots YES`, puis fermer cette seule instance.

## Sur le Mac de Lucas

- `gh` n'est pas installé : propose `brew install gh`. Sans `gh`, remplace `gh pr create` par le lien
  `https://github.com/NotitiaEIP/Notitia/compare/main...<branche>?expand=1`, avec le même titre et la même
  description.
- Dans ce terminal, `grep` est une fonction du shell qui peut échouer : utiliser `/usr/bin/grep`.
