# ADR 0004 : le pont entre le cœur et les apps parle JSON

| | |
| --- | --- |
| Statut | Proposée le 17 novembre 2026, acceptée à la fusion de sa PR |
| Proposée par | Clément |
| Relue par | Alexandre pour le cœur, Lucas pour l'iPhone |
| Tâche d'origine | T-57 (#188) |

## Contexte

Le cœur Rust tient les réunions (ADR 0002). Chaque app doit lui demander une modification et suivre ce qu'il change.

Avec UniFFI, un type qui traverse le pont s'écrit en Rust, se génère en Kotlin et en Swift, puis se recopie à la main dans `RustCore.kt` et `RustCore.swift`. Ce Swift ne se compile que dans la CI, faute de Mac dans l'équipe du cœur. Plus le pont est large, plus on écrit de Swift à l'aveugle.

## Décision

Le pont se réduit à trois fonctions et un écouteur. Ce qui traverse est du texte JSON.

```kotlin
interface NotitiaCore {
    fun open(directory: String, listener: CoreListener): CoreFailure?   // null : ouvert
    fun meeting(id: String): CoreReply                                  // la réunion entière, lignes comprises
    fun apply(change: String): CoreFailure?                             // une modification
}
fun interface CoreListener { fun onEvent(event: String) }               // un événement
```

1. **Une modification entre, un événement ressort.** `apply` ne rend rien d'autre qu'un refus de lire : ce qui a changé, et un fichier qui n'a pas pu être écrit, arrivent par l'écouteur. L'app ne garde aucune copie qu'elle modifierait elle-même.
2. **Le JSON est celui des types du cœur**, `Change` et `Event` dans `core/src/`, sans type intermédiaire. Une réunion y a la forme du fichier `.notitia` version 2 (ADR 0003). L'app Tauri prendra ces mêmes types sans passer par UniFFI.
3. **Les exemples font foi.** `core/tests/fixtures/bridge/changes.json` et `events.json` portent un exemple de chaque sorte. Les tests Rust et les tests Kotlin (`BridgeContractTest`) lisent les mêmes fichiers : un champ renommé d'un seul côté casse un test. Un troisième test, `RustCoreTest`, fait passer une réunion inventée par le vrai cœur, compilé pour la machine qui lance les tests : le pont d'Android est prouvé à chaque PR, sans téléphone.
4. **L'événement arrive avant l'écriture**, sur le fil qui a demandé la modification : une case se coche tout de suite. L'écouteur peut relire une réunion. Il ne demande jamais de modification, qui s'attendrait elle-même, et il ne lève jamais d'erreur.
5. **Un appel à la fois.** L'app envoie ses appels dans l'ordre, sur un seul fil d'exécution hors de l'écran (`CoreMeetings`).
6. **Une erreur ne porte aucun texte de réunion** : `NotFound`, `Unreadable`, `Invalid` (le texte n'est pas une modification), et `Broken` côté app pour une panne du pont lui-même.

| Modification (`type`) | Champs |
| --- | --- |
| `edit` | `meeting`, `edit` : `rename` (`title`), `setDone` (`action`, `done`), `setOwner` (`action`, `owner`), `setDue` (`action`, `due`), `setReminder` (`action`, `on`) |
| `delete` | `meetings` : des identifiants |
| `record` | `id`, `startedAt`, `durationMs`, `transcript`, `minutes` : où en est l'enregistreur |
| `lend` | `meetings` : des réunions d'exemple, jamais écrites |

| Événement (`type`) | Champs |
| --- | --- |
| `listed` | `meetings` : toutes les réunions, la plus récente d'abord, sans leurs lignes |
| `changed` | `meeting` : une réunion modifiée ou nouvelle, sans ses lignes. `at` : son rang dans la liste, que l'app n'a donc pas à trier |
| `removed` | `meetings` : des identifiants |
| `unsaved` | `meetings` : les réunions dont le fichier est en retard, vide quand tout est écrit |

Ajouter une modification : une variante dans `Change`, une classe `@Serializable` dans `CoreWire.kt`, une ligne dans `changes.json`. `RustCore.kt` et `RustCore.swift` ne changent pas.

## Options écartées

| Option | Pourquoi pas |
| --- | --- |
| Un type UniFFI par modification et par événement | Chaque ajout se recopie dans les deux `RustCore`, dont un écrit sans compilateur |
| Laisser l'état des réunions en Kotlin, le cœur ne faisant que lire et écrire | L'enregistreur du cœur (T-58) devrait repasser par Kotlin pour garder une réunion, et l'app Tauri n'a pas de Kotlin |
| Renvoyer toute la liste à chaque modification | 300 réunions à réécrire en texte pour une case cochée |
| Un format binaire sur le pont | Plus rapide, mais illisible en cas de panne, et l'interface web de Tauri lit du JSON |

## Conséquences

**Ce qu'on gagne.** Le Swift écrit à la main tient en une cinquantaine de lignes et ne bouge plus. Les cinq systèmes parlent au cœur avec les mêmes messages.

**Ce qu'on paie.** Le compilateur ne vérifie plus les noms des champs entre Rust et Kotlin : ce sont les tests de contrat qui le font. Chaque message est encodé d'un côté et décodé de l'autre : c'est pour cela qu'une réunion voyage sans ses lignes, lues seulement quand on ouvre sa transcription.

**Ce qui reste ouvert.** Le son du micro (T-58) traversera en octets, par une fonction de plus : c'est la seule exception prévue. Sur iPhone, la seule preuve est l'auto-vérification du pont sur le simulateur de la CI (`CoreSelfCheck`) : un vrai iPhone reste à faire.
