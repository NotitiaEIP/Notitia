# ADR 0007 : la clé de l'app, gardée par le téléphone

| | |
| --- | --- |
| Statut | Proposée le 19 novembre 2026, acceptée à la fusion de sa PR |
| Proposée par | Clément |
| Relue par | Alexandre pour le cœur, Nathan pour Android |
| Tâche d'origine | T-58 (#189), avec la moitié Android de US-81 (#37) |

## Contexte

Le cœur scelle chaque audio sous une clé tirée de la clé de l'app (ADR 0005), et scellera bientôt les réunions elles-mêmes. Cette clé ne peut vivre ni dans le code (règle produit 2) ni en clair sur le disque. Seul le système du téléphone sait la protéger : le Keystore sur Android, le Trousseau sur iPhone.

Perdue, elle rend tous les audios illisibles pour toujours. Écrasée par erreur aussi.

## Décision

1. **32 octets tirés au hasard, une fois par installation** (`SecureRandom`), jamais dérivés de l'appareil ni d'un compte.
2. **Gardés dans `files/meetings.key`, scellés par une clé AES-256-GCM du Keystore** (`notitia-meetings`), qui ne sort jamais du téléphone. Le fichier est exclu de toute sauvegarde et de tout transfert, comme les réunions (règle produit 3).
3. **La clé du Keystore vit dans l'environnement de confiance du téléphone, pas dans StrongBox**, qui ralentirait chaque lancement. Elle ne demande ni authentification ni écran déverrouillé : une réunion continue de s'enregistrer écran verrouillé (US-11).
4. **Durable avant d'être utilisée.** Fichier temporaire synchronisé, renommé, puis dossier synchronisé : une clé dont une coupure de courant perdrait l'enveloppe scellerait des audios que personne ne pourrait rouvrir.
5. **Jamais écrasée.** Une enveloppe qui ne s'ouvre pas maintenant rend `null` : une panne passagère du Keystore, ou une version et une taille que seule une app plus récente saurait lire. L'app n'enregistre rien et réessaie au lancement suivant.
6. **Sauf si elle ne s'ouvrira plus jamais** : le Keystore n'a plus l'alias, le déclare invalide pour toujours, ou refuse le sceau de l'enveloppe (elle est abîmée, ou scellée sous une autre clé). L'ancienne clé ne reviendra pas. L'enveloppe est mise de côté (`meetings.key.lost-<ms>`) et une nouvelle clé est tirée : les anciens audios restent illisibles, les nouveaux s'enregistrent, et les transcriptions restent.
7. **La clé traverse le pont une seule fois, en octets, à `open`** (ADR 0004), puis l'app l'efface de sa mémoire. Le cœur la garde dans une mémoire effacée quand il la lâche (`secret.rs`).
8. **Sur iPhone, le Trousseau gardera la clé elle-même** (US-81, Lucas), sans enveloppe. D'ici là, l'iPhone ouvre le cœur sans clé : `start` y rend `failed(storage)`.

## La forme du fichier, version 1

| Octets | Contenu |
| --- | --- |
| 1 | La version, `1` |
| 12 | L'IV, tiré par le Keystore |
| 48 | La clé de l'app scellée en AES-256-GCM, tag compris, avec la version comme donnée authentifiée |

Un témoin de la version 1, scellé par une autre bibliothèque, est relu par `MeetingsKeyTest` : le format ne peut plus changer sans qu'on le voie.

## Options écartées

| Option | Pourquoi pas |
| --- | --- |
| Sceller l'audio avec une clé du Keystore, sans clé de l'app | Le cœur ne peut pas appeler le Keystore, et l'ordinateur n'en a pas |
| `EncryptedSharedPreferences` (Jetpack Security) | Une bibliothèque que Google a dépréciée, pour ce que quelques lignes font |
| StrongBox | Des dizaines de millisecondes par appel, et absent de beaucoup de téléphones |
| Demander l'authentification de l'utilisateur | L'enregistrement s'arrêterait écran verrouillé |
| Tirer une nouvelle clé dès que l'enveloppe ne s'ouvre pas | Une panne passagère du Keystore rendrait illisibles d'un coup tous les audios |
| Ne jamais tirer de nouvelle clé | Un Keystore qui perd sa clé, ou une enveloppe abîmée, bloquerait l'enregistrement jusqu'à une réinstallation, qui efface toutes les réunions |

## Conséquences

**Ce qu'on gagne.** Un audio copié hors du téléphone est illisible, et la clé qui l'ouvrirait ne quitte jamais le téléphone. Toute la logique se teste sur la machine de build, sauf l'appel au Keystore lui-même.

**Ce qu'on paie.** Le premier écran attend le Keystore une fois, avant de lister les réunions : 12 à 17 ms à chaque lancement, mesurés le 20 novembre 2026 sur un Pixel 10, hors du fil principal. La première image ne l'attend pas et arrive en 0,7 s, en build de debug. Le tirage de la clé au premier lancement n'a pas été mesuré : il aurait fallu effacer une clé qui scelle de vrais audios. Le tampon qu'UniFFI alloue pour faire traverser la clé n'est pas effacé : il est hors de notre code.

**Ce qui reste ouvert.** La clé de l'iPhone (US-81). Une clé perdue ne se voit que par l'échec de l'enregistrement : les réglages (T-44) pourront le dire.
