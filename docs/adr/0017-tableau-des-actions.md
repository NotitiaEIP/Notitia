# ADR 0017 : le cœur calcule le tableau des actions

| | |
| --- | --- |
| Statut | Proposée le 27 novembre 2026, acceptée à la fusion de sa PR |
| Proposée par | Clément |
| Relue par | Alexandre pour le cœur |
| Tâche d'origine | T-84 (#249). L'onglet Actions du téléphone l'affiche dans T-104 |

## Contexte

L'onglet Actions range les actions de toutes les réunions par échéance et par personne. Ce rangement vivait en Kotlin (`ActionSchedule.kt`, `ActionsByPerson.kt`) et l'écran le recalculait à chaque recomposition. L'app de Nathan (Tauri, T-48) et l'app Mac n'ont pas de Kotlin : elles l'auraient réécrit, et une correction faite d'un côté se serait oubliée de l'autre. Les groupes dépendent du jour, que le cœur ne lit jamais (`clippy.toml`, ADR 0009).

## Décision

1. **Le cœur calcule le tableau, l'app donne le jour.** La demande `{"type":"board","today":"2026-10-26"}` fait dire l'événement `board` à l'appelant, sur son fil et hors de tout verrou (ADR 0004, point 4). Elle ne change aucune réunion, n'écrit rien et ne réveille pas le traitement : elle marche pendant un enregistrement.
   - `today` est obligatoire : une demande sans jour, ou avec un jour qui n'existe pas (`2026-02-30`), est refusée (`Invalid`), jamais lue comme « aujourd'hui ».
   - L'événement rend `today` tel quel : l'app écarte une réponse faite pour la veille.
2. **Une entrée est une référence et ce que le jour en décide**, pas l'action elle-même : `meeting`, `action`, puis `group` (`late`, `today`, `tomorrow`, `nextDays` ou `later`, absent sans échéance) et `daysLate` (seulement pour une action en retard et pas faite). Le texte, le responsable et la case cochée restent ceux de l'événement `changed` : une seule source par valeur. Un identifiant d'action n'étant unique que dans sa réunion, toute clé est le couple. Une référence ne désigne jamais deux actions : si une réunion tient deux fois un identifiant (un fichier abîmé, ou écrit par une autre app), seule la première est dite, celle que les modifications atteignent.
3. **L'événement porte les deux lectures et le retard par réunion** : `toComplete` (sans responsable ou sans échéance), `groups` (par échéance, le plus urgent d'abord, sans groupe vide), `done`, `open`, `late`, `people` (par personne), et `lateByMeeting` (les réunions qui ont au moins une action en retard et pas faite). Passer d'une vue à l'autre est donc instantané, et la liste des réunions n'a plus à connaître la règle du retard.
4. **`lingering` : les actions qu'on vient de cocher.** L'app les redonne à chaque demande. Elles restent à leur place et comptent « ouvertes » pour le rang et pour `open` et `late`, « faites » pour l'anneau d'une personne. Elles ne comptent pas dans `lateByMeeting` ni dans `daysLate`. Un identifiant valide mais inconnu est ignoré ; un identifiant invalide refuse la demande en entier, comme une offre de moteurs fausse (ADR 0010, point 7). La durée pendant laquelle elles s'attardent reste à l'écran.
5. **Un ordre total, écrit en entier.** Le Kotlin s'appuyait sur des tris stables et sur l'ordre de la liste. Le cœur écrit chaque clé, donc le tableau ne dépend pas de l'ordre dans lequel on lui donne les réunions :

   | Liste | Clé |
   | --- | --- |
   | un groupe d'échéance | échéance, début de la réunion, identifiant de la réunion, rang de l'action |
   | à compléter | début, identifiant, rang |
   | faites | début décroissant, identifiant, rang |
   | ouvertes d'une personne | sans échéance en dernier, échéance, début, identifiant, rang |
   | les personnes | en retard décroissant, ouvertes décroissant, clé |

   Les actions sans responsable closent la liste des personnes. Les clés se comparent par point de code (octets UTF-8) : le Kotlin les comparait par unités UTF-16, ce qui ne diffère que pour un caractère hors du plan de base.
6. **Une personne, c'est son prénom écrit** (règle produit 4) : espaces repliés, minuscules, puis les lettres à marque de la même table que le Kotlin, ce qui garde à chacun sa clé et sa couleur. Une espace est toute espace Unicode : l'expression régulière du Kotlin (`\s`) n'en couvre que d'ASCII sur la JVM. Une décomposition Unicode complète (Nguyễn, par exemple) demanderait une dépendance et changerait des clés : écartée pour l'instant. Son nom est l'écriture de la réunion la plus récente, et à égalité de début la première de la liste, puis la première des minutes. Un responsable vide ou fait d'espaces n'est personne : un modèle ou un vieux fichier peut en écrire un, l'utilisateur non.
7. **Le tableau est lu sur les réunions telles qu'elles sont, d'un seul tenant** : `Library::view` tient le verrou des réunions le temps du calcul et le lâche avant de dire l'événement. Les échantillons prêtés en font partie, comme avant. Une réponse est donc un instant, jamais un mélange de deux changements.

## Options écartées

| Option | Pourquoi pas |
| --- | --- |
| Une fonction du pont pour le tableau | Elle toucherait `RustCore.kt` et `RustCore.swift`, dont un écrit sans compilateur (ADR 0004) |
| Le cœur redit le tableau à chaque changement de réunion | Il garderait le jour et les cases cochées de l'écran, et calculerait pour une app qui n'affiche pas l'onglet. L'app redemande quand ce qui compte change |
| Les actions entières dans l'événement | Le texte serait en double, et deux sources se contrediraient un instant |
| Garder `lingering` en Kotlin | Le rang, `open` et `late` en dépendent : l'ordinateur le réécrirait |
| Une seule lecture par demande | Changer de vue coûterait un aller-retour et ferait clignoter l'écran |
| Une décomposition Unicode pour les clés | Une dépendance et des clés qui changent, donc des couleurs qui changent |

## Résultat

Mesuré le 27 novembre 2026 avec `cargo run --release --example board` : des réunions prêtées à une session, 70 % des actions faites, le tableau demandé 30 fois par le chemin d'une app, texte compris.

| Réunions × actions | PC (i5-12450H) | Pixel 10 (`cargo ndk`, deux passes) | Texte |
| --- | --- | --- | --- |
| 300 × 8, soit 2 400 actions | 0,78 ms au mieux, 0,81 ms en médiane | 2,1 à 2,2 ms au mieux, 3,1 à 3,4 ms en médiane, 9,4 ms au pire | 180 Ko |
| 3 000 × 10, soit 30 000 actions | 13 ms | 30 ms | 2,2 Mo |

Écarter un identifiant en double coûte 0,05 à 0,08 ms de plus sur le PC à 2 400 actions (mesuré le 28 novembre avec et sans, à la même heure). Le temps suit le nombre d'actions. Ce que le Kotlin refaisait à chaque recomposition, il le lit une fois par réponse.

`cargo test` fait passer 2 000 jeux tirés d'une action par réunion et 1 000 de réunions à plusieurs actions, et cent fois plus en release (`NOTITIA_CASES=100000`) : chaque action est dite une fois, `open` et `late` sont ce que les listes tiennent, chaque liste suit sa clé, les totaux d'une personne se recomptent par un autre chemin, et le même tableau sort de toute permutation des réunions. Quatre fils cochent et décochent des actions pendant qu'un cinquième demande : chaque réponse est entière (30 passages). Chacune des 26 règles a été cassée à la main : un test échoue à chaque fois.

## Conséquences

**Ce qu'on gagne.** L'ordinateur et le Mac lisent le même tableau que le téléphone, et une correction se fait une fois. Le jour n'est plus lu par chaque écran.

**Ce qu'on paie.**
- Le tableau grandit avec l'historique : les actions faites n'en sortent jamais. À 2 400 actions, 180 Ko de texte par réponse. L'app ne redemande que lorsque le jour, les actions ou ce qu'on vient de cocher changent (T-104), et décode en dehors de l'écran. Si l'historique pèse, la liste des faites sortira de l'événement, en nombre seulement, avec une demande pour la lire.
- Un instant de décalage entre `changed` et `board` : l'écran lit les actions de l'un et leur place dans l'autre. Une action absente du tableau n'est pas montrée, une action absente des réunions est écartée.

**Ce qui reste ouvert.**
- Restent à l'écran, dans T-104 : l'ordre des cartes tant que la liste est visible (`keptInOrder`), et la durée de `lingering`.
- Restent en Kotlin : `knownSpelling` (le cœur devrait l'appliquer quand il enregistre un responsable) et `dueDateRange` (le cœur devrait refuser une échéance avant la réunion ou à plus d'un an dans `SetDue`).
- **La clé d'une personne est écrite deux fois** tant que `PersonColors` doit une clé pour tout nom, intervenants et responsables compris : `core/tests/fixtures/bridge/board.json` porte des paires nom et clé que les tests du cœur et ceux du Kotlin lisent, et ne les laissent pas diverger. À supprimer le jour où le cœur donnera la clé et la couleur avec chaque nom.
- Les jours de réunion du Kotlin se comptent encore avec le fuseau du téléphone ; le cœur a le décalage de la réunion (`offsetMinutes`), à utiliser quand il portera aussi `dueDateRange`.
