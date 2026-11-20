# ADR 0009 : les garde-fous du cœur

| | |
| --- | --- |
| Statut | Proposée le 20 novembre 2026, acceptée à la fusion de sa PR |
| Proposée par | Clément |
| Relue par | Alexandre |
| Tâche d'origine | T-88 (#254) |

## Contexte

Les règles produit de l'AGENTS.md et les règles du cœur ne tenaient que par la relecture. Rien n'empêchait une crate de réseau d'entrer dans le cœur, une licence incompatible de s'y glisser, un fichier témoin d'être réécrit pour faire passer un test, ni un fichier abîmé de faire tomber l'app. Les réunions viendront d'autres appareils (US-101, puis le lien de E10) : un fichier reçu ne doit jamais faire planter le cœur.

## Décision

Ce qu'un outil peut vérifier, un outil le vérifie, à chaque PR.

| Règle | Garde-fou | Où |
| --- | --- | --- |
| Rien ne sort de l'appareil (règle produit 1) | `core/deny.toml` refuse les crates de réseau, d'exécution asynchrone et de rapport de plantage. `core/clippy.toml` refuse `std::net`, la résolution d'un nom et le lancement d'un programme. Le cœur n'écrit rien sur la sortie standard | `./notitia check`, jobs `core` |
| Une licence que l'app peut porter | Apache 2.0, BSD, ISC, MIT, Unicode, Zlib. La MPL 2.0 pour les seules crates d'UniFFI | `./notitia check` |
| Des crates de confiance, chacune une fois | crates.io seulement, jamais `*`, une seule version par crate (sauf `syn` 2, que les macros d'UniFFI tirent encore, à la compilation seulement) | `./notitia check` |
| Aucune faille connue | Les avis RustSec : sur la PR qui change `core/Cargo.lock` ou `core/deny.toml`, et chaque lundi sur `main` | Workflow `advisories` |
| Le cœur ignore UniFFI (ADR 0002) | `uniffi` permis à `notitia-ffi` seule | `./notitia check` |
| Le cœur ne panique jamais | `unwrap`, `expect`, `panic!`, `todo!`, `unimplemented!`, `unreachable!`, `exit` et `abort` refusés. Chaque décodeur lit des milliers de fichiers abîmés | clippy, `cargo test` |
| Un secret ne s'affiche jamais | `Key` n'a pas `Debug`, comme `Meeting` : un doctest le prouve | `cargo test` |
| Un format est pour toujours | `audio-v1.audio` rejoint les témoins, et l'empreinte SHA-256 de chaque témoin est épinglée. Une réunion lue se réécrit à l'identique | `cargo test` |
| L'heure est donnée au cœur, jamais lue | `Instant::now`, `SystemTime::now`, `UtcDateTime::now` et `OffsetDateTime::now_utc` refusés, sauf l'horloge de `Library::open`. La feature `local-offset` de `time` refusée | clippy, `./notitia check` |
| Une mémoire sûre | `#![forbid(unsafe_code)]` dans le cœur. Le pont, que UniFFI écrit, est une crate à part | Compilateur |

1. **Les avis de sécurité n'arrêtent pas toutes les PR.** Leur base change chaque jour : un avis publié dans la nuit bloquerait la PR d'interface d'un coéquipier qui ne touche aucune crate. La PR qui change les crates est arrêtée, et le lundi signale le reste. Un avis écarté porte son identifiant et sa raison dans `deny.toml`.
2. **Les fichiers abîmés se rejouent.** `core/src/testing.rs` les abîme toujours de la même façon, à partir d'une graine fixe : un échec donne son numéro de cas, et le cas revient à l'identique sur les trois systèmes. Un dommage sur deux tombe dans l'en-tête. Quatre sur dix remplacent une valeur par une valeur limite : un nombre au-delà de `u64`, un instant qui sort des années 0 à 9999 une fois en UTC, un nom que Windows réserve. Le fichier reste lisible, et c'est le code qui lit le sens d'un champ qui est mis à l'épreuve. `NOTITIA_CASES` en fait passer autant que voulu.
3. **Sous le sceau aussi.** Des octets au hasard n'ouvrent jamais une seconde scellée. Un test scelle donc, avec la bonne clé, des secondes au contenu abîmé, comme une app plus récente pourrait en écrire.
4. **Un témoin ne se régénère pas.** Le sel et les nonces de l'audio sont tirés au hasard : réécrire le témoin, c'est le changer. Le test qui l'épingle échoue, et dit d'ajouter une version.
5. **Opus et le chiffrement sont optimisés même en debug.** Sans cela, les tests de l'audio seraient plusieurs fois plus lents. Le code du cœur reste en debug.

## Options écartées

| Option | Pourquoi pas |
| --- | --- |
| Une liste des crates permises, plutôt qu'interdites | 80 crates aujourd'hui, 200 après les moteurs : la relecture de chaque ajout finirait en tampon |
| cargo-vet, l'audit de chaque version | Le plus sûr, écrit par Mozilla pour Firefox. Trop lourd tant que les moteurs ne sont pas choisis (T-06) |
| cargo-audit | Ne voit que les avis. cargo-deny tient aussi les licences, les sources et les crates interdites, dans un seul fichier |
| Les avis de sécurité dans chaque PR | Voir le point 1 |
| proptest, cargo-fuzz | proptest ajoute une vingtaine de crates pour ce qu'une centaine de lignes font. cargo-fuzz, guidé par la couverture, demande Rust nightly : il complétera ces tests |
| L'action d'EmbarkStudios, ou une action tierce, pour installer cargo-deny | La première ne tourne que sous Linux, la seconde est un fournisseur de plus à qui faire confiance. `cargo install --locked` part de crates.io, comme le reste, et un cache garde le binaire |

## Résultat

Le bug trouvé en préparant ces garde-fous, et qu'ils retrouvent seuls quand on retire sa correction :

- `"startedAt":"9999-12-31T23:59:59-01:00"` faisait paniquer la bibliothèque `time` à la lecture. Un seul fichier ainsi daté empêchait l'app de s'ouvrir, à chaque lancement.
- `"0000-01-01T00:00:00+01:00"` se lisait mais ne se réécrivait plus. L'événement `listed` ne pouvait plus s'écrire, et la liste entière disparaissait.

Le cœur refuse désormais un instant qui sort des années 0 à 9999 une fois en UTC. Le fichier reste sur le disque sans être lu, comme celui d'une app plus récente.

Mesuré le 20 novembre 2026 sur un portable (Intel i5-12450H) :

- **`cargo deny check bans licenses sources`** : 0,2 s. Les avis de sécurité : 1,1 s, base RustSec comprise. Aucun avis sur les 82 crates du graphe.
- **Les tests du cœur, en debug** : 138 tests en 3,2 s avant, 147 en 1,2 s après, grâce au point 5.
- **La passe longue, en release** (`NOTITIA_CASES`) : un million de fichiers abîmés pour les réunions (0,7 s), l'index (0,9 s) et les demandes des apps (1,0 s), un million de paquets Opus (11,6 s), cent mille audios (26 s) et deux cent mille secondes scellées par la bonne clé (66 s). Aucune panique, et chaque réunion lue s'est réécrite à l'identique.
- **Chaque garde-fou a refusé une erreur provoquée** : `tokio`, `uniffi` dans le cœur, `local-offset`, une dépendance `git`, une licence retirée, une crate en double, un socket, un programme, la résolution d'un nom, `exit`, les deux horloges, `println!`, `unreachable!`, `unsafe`, `Debug` sur la clé, un octet changé dans un témoin, un témoin non épinglé.

## Conséquences

**Ce qu'on gagne.** Une règle tenue par un outil ne dépend plus de l'attention d'un relecteur. Une crate qui arrive montre sa licence, et un fichier reçu d'un autre appareil ne fait plus tomber l'app.

**Ce qu'on paie.** cargo-deny s'installe une fois, et `./notitia doctor` dit comment : 80 s de compilation. Une nouvelle crate peut demander une ligne dans `deny.toml`, et une crate en double une raison.

**Ce qui reste ouvert.**

- Le fuzzing guidé par la couverture (`cargo fuzz`, nightly), sur les mêmes décodeurs.
- Un fichier démesuré est lu en entier en mémoire. Seule l'app écrit ces fichiers aujourd'hui ; l'échange par fichier (US-101) bornera la taille d'un fichier là où il entre.
- Le hasard n'est pas injecté (`MeetingId::random`, `ActionId::random`, `Store::device`) : c'est l'écart accepté avec T-81.
- Une crate qui ouvrirait elle-même un socket par `std` ne serait vue que par un avis. Une liste de crates permises, ou cargo-vet, fermera ce trou quand le graphe sera stable.
- Les actions GitHub sont épinglées par étiquette, pas par empreinte.
- `advisories` ne devient une vérification obligatoire de `main` que par une modification du ruleset, faite par un administrateur.
