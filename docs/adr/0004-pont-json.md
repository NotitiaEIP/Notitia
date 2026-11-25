# ADR 0004 : le pont entre le cœur et les apps parle JSON

| | |
| --- | --- |
| Statut | Proposée le 17 novembre 2026, acceptée à la fusion de sa PR |
| Modifiée | Le 19 novembre 2026 par T-58 (#189) : l'enregistreur, le son et la clé passent par le pont |
| Modifiée | Le 25 novembre 2026 par T-59 (#190) : les moteurs de l'app traversent en JSON, `transcribed` part |
| Proposée par | Clément |
| Relue par | Alexandre pour le cœur, Lucas pour l'iPhone |
| Tâche d'origine | T-57 (#188) |

## Contexte

Le cœur Rust tient les réunions (ADR 0002) et leur enregistrement (ADR 0006). Chaque app doit lui demander une modification ou une commande, et suivre ce qu'il change.

Avec UniFFI, un type qui traverse le pont s'écrit en Rust, se génère en Kotlin et en Swift, puis se recopie à la main dans `RustCore.kt` et `RustCore.swift`. Ce Swift ne se compile que dans la CI, faute de Mac dans l'équipe du cœur. Plus le pont est large, plus on écrit de Swift à l'aveugle.

## Décision

Le pont se réduit à cinq fonctions et un écouteur. Ce qui traverse est du texte JSON, sauf le son et la clé de l'app, en octets.

```kotlin
interface NotitiaCore {
    fun open(directory: String, key: ByteArray?, listener: CoreListener): CoreFailure?   // null : ouvert
    fun meeting(id: String): CoreReply                                                  // la réunion entière, lignes comprises
    fun apply(request: String): CoreFailure?                                            // une modification ou une commande
    fun hear(track: Int, pcm: ByteArray)                                                // le son du micro
    fun sound(id: String, fromMs: Long, lengthMs: Long): CoreSound                      // le son d'une réunion
}
fun interface CoreListener { fun onEvent(event: String) }                               // un événement
```

1. **Une demande entre, un événement ressort.** `apply` ne rend rien d'autre qu'un refus de lire : ce qui a changé, et un fichier qui n'a pas pu être écrit, arrivent par l'écouteur. L'app ne garde aucune copie qu'elle modifierait elle-même.
2. **Le JSON est celui des types du cœur**, `Request` (`Change` et `Command`) et `Event` dans `core/src/`, sans type intermédiaire. Une réunion y a la forme du fichier `.notitia` version 2 (ADR 0003). L'app Tauri prendra ces mêmes types sans passer par UniFFI.
3. **Les exemples font foi.** `core/tests/fixtures/bridge/changes.json`, `events.json`, `recorder.json` et `engines.json` portent un exemple de chaque sorte. Les tests Rust et les tests Kotlin (`BridgeContractTest`) lisent les mêmes fichiers : un champ renommé d'un seul côté casse un test. `RustCoreTest` et `RustRecordingTest` font passer une réunion inventée par le vrai cœur, compilé pour la machine qui lance les tests, de son premier son à sa suppression : le pont d'Android est prouvé à chaque PR, sans téléphone.
4. **L'événement arrive avant l'écriture**, sur le fil qui a demandé la modification, ou sur le fil de l'enregistreur pour ses états et pour la réunion qu'il crée puis prolonge. Le cœur dit les événements des réunions un à un. L'écouteur peut relire une réunion. Il ne demande jamais rien au cœur, qui s'attendrait lui-même, et il ne lève jamais d'erreur.
5. **Un appel à la fois.** L'app envoie ses appels dans l'ordre, sur un seul fil d'exécution hors de l'écran (`CoreSession`). Seule exception : `hear`, que le micro appelle depuis son propre fil, sans jamais attendre une écriture.
6. **Une erreur ne porte aucun texte de réunion** : `NotFound` (pas de réunion, ou pas d'audio), `Unreadable`, `Invalid` (le texte n'est pas une demande, ou la clé ne fait pas 32 octets), et `Broken` côté app pour une panne du pont lui-même.
7. **Le son et la clé traversent en octets.** Le son du micro arrive par `hear`, en 16 bits petit-boutiste à 16 kHz. Celui d'une réunion repart par `sound`, dans la même forme, une minute au plus par appel. La clé de l'app traverse une seule fois, à `open`, puis l'app l'efface (ADR 0007).

| Modification (`type`) | Champs |
| --- | --- |
| `edit` | `meeting`, `edit` : `rename` (`title`), `setDone` (`action`, `done`), `setOwner` (`action`, `owner`), `setDue` (`action`, `due`), `setReminder` (`action`, `on`) |
| `delete` | `meetings` : des identifiants |
| `rewrite` | `meeting` : un compte rendu en échec, ou jamais écrit par une ancienne app, de nouveau dû (ADR 0013) |
| `lend` | `meetings` : des réunions d'exemple, jamais écrites |

| Commande (`type`) | Champs |
| --- | --- |
| `start` | `tracks` (1 sur un téléphone), `offsetMinutes` : l'avance du fuseau de l'appareil sur UTC |
| `pause`, `resume`, `stop`, `dismiss` | aucun |
| `cut` | `problem` : `denied`, `busy` ou `unavailable` |

| Événement (`type`) | Champs |
| --- | --- |
| `listed` | `meetings` : toutes les réunions, la plus récente d'abord, sans leurs lignes |
| `changed` | `meeting` : une réunion modifiée ou nouvelle, sans ses lignes. `at` : son rang dans la liste, que l'app n'a donc pas à trier |
| `removed` | `meetings` : des identifiants |
| `unsaved` | `meetings` : les réunions dont le fichier est en retard, vide quand tout est écrit |
| `recorder` | `state` et ses champs (ADR 0006). Il nomme la réunion avant que `changed` ne la montre |
| `engines` | `speech`, `text`, `embedding` : les moteurs que le cœur connaît, le choisi d'abord (ADR 0010) |

Ajouter une demande : une variante dans `Change` ou `Command`, une classe `@Serializable` dans `CoreWire.kt`, une ligne dans `changes.json` ou `recorder.json`. `RustCore.kt` et `RustCore.swift` ne changent pas.

## Options écartées

| Option | Pourquoi pas |
| --- | --- |
| Un type UniFFI par modification et par événement | Chaque ajout se recopie dans les deux `RustCore`, dont un écrit sans compilateur |
| Une fonction par commande de l'enregistreur | Même chose, pour six commandes |
| Laisser l'état des réunions en Kotlin, le cœur ne faisant que lire et écrire | L'enregistreur du cœur devrait repasser par Kotlin pour garder une réunion, et l'app Tauri n'a pas de Kotlin |
| Renvoyer toute la liste à chaque modification | 300 réunions à réécrire en texte pour une case cochée |
| Un format binaire sur le pont | Plus rapide, mais illisible en cas de panne, et l'interface web de Tauri lit du JSON |
| Le son ou la clé en texte (base64) | Un tiers de plus à chaque lecture du micro, et une clé dans une chaîne qu'aucune app ne sait effacer |

## Conséquences

**Ce qu'on gagne.** Le Swift écrit à la main tient en une centaine de lignes et ne bouge plus. Les cinq systèmes parlent au cœur avec les mêmes messages.

**Ce qu'on paie.** Le compilateur ne vérifie plus les noms des champs entre Rust et Kotlin : ce sont les tests de contrat qui le font. Chaque message est encodé d'un côté et décodé de l'autre : c'est pour cela qu'une réunion voyage sans ses lignes, lues seulement quand on ouvre sa transcription.

**Ce qui reste ouvert.** Sur iPhone, la seule preuve est l'auto-vérification du pont sur le simulateur de la CI (`CoreSelfCheck`), qui entend et relit une seconde inventée : un vrai iPhone reste à faire.
