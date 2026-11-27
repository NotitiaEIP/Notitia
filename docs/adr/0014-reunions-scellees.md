# ADR 0014 : les réunions et l'index scellés sur le disque

| | |
| --- | --- |
| Statut | Proposée le 26 novembre 2026, acceptée à la fusion de sa PR |
| Modifiée | Le 27 novembre 2026 par T-103 (#276) : le fichier `settings` reste en clair, comme `device` |
| Proposée par | Clément |
| Relue par | Alexandre pour le cœur |
| Tâche d'origine | T-96 (#265), la partie cœur de US-84 (#49) |

## Contexte

Le cœur scelle l'audio de chaque réunion (ADR 0005), sous une clé tirée de la clé de l'app (ADR 0007). Mais la transcription, le compte rendu et les titres restaient en JSON clair dans le dossier de l'app, comme l'index de la liste. Une copie de ce dossier se lisait telle quelle. La règle produit 3, « stockage local chiffré », n'était pas tenue.

## Décision

1. **Chaque `.notitia` et l'index sont scellés**, dans `core/src/library/sealed.rs`. Le JSON du format v2 (ADR 0003) ne change pas : il reste le format lisible, celui qu'un fichier envoyé à un autre appareil portera (US-101). Le sceau n'est que l'enveloppe du disque.

   | Octets | Contenu |
   | --- | --- |
   | 8 | La marque : `NTTMEET1` pour une réunion, `NTTINDX1` pour l'index. Le chiffre est la version du sceau, pas celle du JSON |
   | 32 | Le sel, tiré à chaque écriture |
   | 24 | Le nonce, tiré à chaque écriture |
   | n | Le JSON, scellé en entier par XChaCha20-Poly1305 (`secret.rs`) |
   | 16 | Le sceau |

2. **Une clé par fichier, et par sorte de fichier.** La clé du fichier est tirée de la clé de l'app par HKDF, avec le sel et une étiquette : `notitia meeting 1` ou `notitia index 1`. Ce qui scelle une réunion n'ouvre ni un index ni un audio.
3. **Un fichier ne s'ouvre que sous son nom.** L'en-tête est scellé avec le JSON, et pour une réunion son identifiant aussi. Copié sous le nom d'une autre réunion, le fichier est refusé, y compris quand l'app lit ses lignes. Avant, seule la liste vérifiait l'identifiant lu.
4. **Un fichier qui ne s'ouvre pas reste sur le disque, caché.** C'est le cas d'une autre clé, d'un sceau plus récent (`NTTMEET2`) ou d'octets changés. Une mise à jour, ou la clé retrouvée, le relira. Le cœur n'efface que ce que l'utilisateur supprime.
5. **Les fichiers d'avant le sceau sont migrés au lancement.** Un fichier dont le premier octet visible est `{` est du JSON en clair : il est lu, puis rejoint les fichiers en retard (`behind`), et le chemin habituel le réécrit scellé juste après la liste. Un fichier en clair n'entre jamais dans l'index : un index scellé qui le croirait à jour ne le relirait plus, et il resterait en clair pour toujours. Un index en clair, écrit avant, ne fait pas foi : il est remplacé dès ce lancement, même par un index vide, car il garde titres et comptes rendus en clair.
6. **Sans clé, rien n'est écrit qui contienne une réunion.** C'est le cas de l'iPhone tant qu'il n'a pas sa clé (US-81), et d'un Android dont le Keystore a un raté passager (ADR 0007, point 5).
   - Les fichiers en clair d'avant se lisent, et les fichiers scellés sont cachés.
   - Une modification reste en mémoire, et l'événement `unsaved` la signale. Une suppression, elle, se fait : elle n'écrit aucun mot.
   - L'index n'est pas écrit.

   Ce mode ne coûte rien à l'iPhone d'aujourd'hui : sans clé il n'enregistre rien (`failed(storage)`), et ses réunions d'exemple ne s'écrivent jamais.
7. **`device` et `settings` restent en clair** : le premier est un identifiant tiré au hasard, le second les choix de l'utilisateur (ADR 0016). Ni l'un ni l'autre ne dit rien d'une réunion.
8. **Un témoin du sceau, version 1** (`core/tests/fixtures/meeting-sealed-v1.notitia`), a été scellé une seule fois par la clé de test `key(1)`. Il contient `meeting-v2.notitia`, et son empreinte est épinglée dans `core/src/testing.rs` (ADR 0009).

### Les écarts avec la fiche du 17 novembre

| Fiche | Ici | Pourquoi |
| --- | --- | --- |
| Sans clé : lecture et écriture en clair, comme avant | Sans clé : lecture seule | Un raté passager du Keystore réécrirait en clair les réunions modifiées pendant ce lancement. Choix de Clément le 26 novembre 2026 |
| L'en-tête en donnée authentifiée | L'en-tête, et l'identifiant de la réunion | `Store::read` ne vérifiait pas l'identifiant lu : un fichier renommé aurait prêté ses lignes à une autre réunion |
| La clé passe de `Library` à `Store` | Fait | `Library` lit `store.key()` pour l'audio : une seule place pour la clé |

## Options écartées

| Option | Pourquoi pas |
| --- | --- |
| Sceller le JSON par morceaux, comme l'audio seconde par seconde | Une réunion se lit et s'écrit en entier. Un seul sceau suffit, et sa taille ne dépend que du texte |
| Un format v3 de `.notitia`, scellé à l'intérieur | Le JSON v2 est aussi le format d'échange (US-101). Le sceau du disque, lié à la clé de cet appareil, n'a rien à y faire |
| Écrire en clair sans clé | Voir les écarts : la règle produit 3 ne souffre pas d'exception passagère |
| Laisser une réunion migrée dans l'index | Il la croirait à jour : jamais relue, elle ne serait jamais réécrite scellée |
| Migrer peu à peu, à la prochaine modification de chaque réunion | Une réunion qu'on ne touche plus resterait en clair pour toujours |

## Résultat

Mesuré le 26 novembre 2026 sur le portable de l'ADR 0005 (Intel i5-12450H), en release, sur son disque NVMe, avec `cargo run --release --example opening` : 300 réunions d'une heure, 21 Mo de transcriptions, comparées à `main` avant le sceau.

| Ouverture | Avant (en clair) | Scellées |
| --- | --- | --- |
| Sans l'index : chaque fichier lu et ouvert | Liste en 32 ms | Liste en 46 ms |
| Avec l'index | Liste en 5,0 ms | Liste en 6,5 ms |
| 300 fichiers en clair scellés au premier lancement | | Liste en 34 ms, terminé en 0,75 s |

Ouvrir les sceaux coûte 14 ms pour 21 Mo de texte, et 1,5 ms pour l'index seul. La migration écrit chaque réunion de nouveau, avec ses deux synchronisations sur le disque. Elle n'a lieu qu'une fois, et seulement sur les téléphones de l'équipe : aucune app n'est chez un utilisateur.

Le même exemple, compilé par `cargo ndk` et lancé sur le Pixel 10 de Clément le 26 novembre 2026 :

| Ouverture | Avant (en clair) | Scellées |
| --- | --- | --- |
| Sans l'index | Liste en 262 ms | Liste en 252 à 325 ms |
| Avec l'index | Liste en 37 ms | Liste en 17 ms |
| 300 fichiers en clair scellés au premier lancement | | Liste en 106 ms, terminé en 5,2 s |

Sur le téléphone, le coût du sceau se perd dans l'écart d'une mesure à l'autre. La migration y coûte 17 ms par réunion : quelques dixièmes de seconde pour les réunions d'un téléphone de l'équipe. Sur le Pixel de Clément, ses huit réunions ont été scellées au premier lancement. L'index en clair y est resté, ce qui a révélé le défaut corrigé plus bas : il est scellé depuis, et plus aucun fichier ne contient de JSON lisible.

Les tests le prouvent aussi :
- avec la clé, aucun mot d'une réunion n'est sur le disque, index compris, même après un renommage et une transcription ;
- un fichier copié sous le nom d'une autre réunion, ou scellé par une autre clé, est caché et laissé sur le disque ;
- les témoins v1 et v2 en clair sont lus, scellés au lancement, puis relus à l'identique ;
- sans clé, une modification reste en mémoire et signalée, aucun fichier ni index n'est écrit, et une suppression se fait ;
- un index en clair, abîmé, d'une autre version ou d'une autre clé est reconstruit, et celui d'avant le sceau ne passe pas un lancement de plus sur le disque (trouvé sur le Pixel : quand tous les fichiers étaient encore en clair, il restait jusqu'au lancement suivant) ;
- des octets abîmés au hasard donnent un refus, jamais une panique, et seul le contenu scellé s'ouvre.

## Conséquences

**Ce qu'on gagne.**
- La règle produit 3 est tenue par le cœur, pour toutes les apps : une copie du dossier ne dit rien d'une réunion.
- Un fichier ne peut plus prêter ses lignes à une autre réunion.
- La durée d'une réunion est désormais scellée dans son `.notitia`. Un audio raccourci entre deux secondes, que l'ADR 0005 ne pouvait pas distinguer d'un enregistrement arrêté là, se verra en comparant les deux. Rien ne le compare encore.

**Ce qu'on paie.**
- **Une clé Android perdue pour toujours rend aussi les réunions illisibles**, plus seulement leurs audios (ADR 0007, point 6). C'est le prix d'un chiffrement lié à l'appareil, sans sauvegarde dans le cloud (règle produit 3). Les fichiers restent sur le disque, jamais effacés.
- Sans clé, une liste peut se montrer vide alors que les réunions sont là.
- Le premier lancement d'un téléphone de l'équipe attend la migration.
- **Un fichier en clair est encore lu**, pour que les téléphones de l'équipe gardent leurs réunions. Seuls les fichiers scellés sont donc authentifiés : qui peut écrire dans le dossier de l'app peut y glisser une réunion en clair, que l'app scellera. Sur un téléphone, le dossier de l'app n'est ouvert qu'à elle.

**Ce qui reste ouvert.**
- **Dire à l'écran que des réunions sont cachées**, sans clé ou avec une clé perdue : un événement qui compte les fichiers qu'on ne peut pas ouvrir. Ce sera une issue à part.
- **La clé du Trousseau sur iPhone** (US-81) et la classe de protection des fichiers pendant un enregistrement écran verrouillé (US-84).
- **Comparer la durée scellée à celle de l'audio**, pour voir un audio raccourci.
- **Refuser les fichiers en clair** une fois les téléphones de l'équipe migrés. Aucun utilisateur n'en aura jamais eu.
