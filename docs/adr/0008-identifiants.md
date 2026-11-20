# ADR 0008 : un identifiant désigne le même fichier sur tous les systèmes

| | |
| --- | --- |
| Statut | Proposée le 20 novembre 2026, acceptée à la fusion de sa PR |
| Proposée par | Clément |
| Relue par | Alexandre |
| Tâche d'origine | T-82 (#247) |

## Contexte

L'identifiant d'une réunion donne son nom à ses fichiers, `<id>.notitia` et `<id>.audio` (ADR 0003, règle 6). Cette règle acceptait les lettres sans accent, majuscules comprises. C'était sûr sur le téléphone, dont le système distingue `A` de `a`. Ça ne l'est plus sur l'ordinateur :

- Windows et macOS ne distinguent pas la casse. Deux réunions `point` et `Point` seraient deux réunions en mémoire, mais un seul fichier sur le disque : l'une écraserait l'autre.
- Windows garde quelques noms pour ses périphériques, quelle que soit l'extension qui suit : `con.notitia` y désigne la console, pas un fichier.

Aucun appareil ne crée de tels identifiants aujourd'hui. Ils arriveront avec les réunions venues d'un autre appareil (US-101, puis le lien de E10), et une réunion doit pouvoir passer partout.

## Décision

Elle remplace la règle 6 de l'ADR 0003.

1. **Un identifiant ne contient que des lettres minuscules sans accent, des chiffres, `-` et `_`**, 64 caractères au plus.
2. **Ce n'est jamais un nom de périphérique de Windows** : `con`, `prn`, `aux`, `nul`, `com0` à `com9`, `lpt0` à `lpt9`.
3. **Un fichier dont l'identifiant ne suit pas ces règles reste sur le disque, sans être lu**, comme un fichier qu'une app plus récente aurait écrit.

La règle vit dans une seule fonction du cœur, `checked` (`core/src/meeting/id.rs`). Tout identifiant passe par elle : lu dans un fichier, tiré d'un nom de fichier, ou reçu d'une app.

## Options écartées

| Option | Pourquoi pas |
| --- | --- |
| Mettre l'identifiant en minuscules à la lecture | L'identifiant changerait d'un appareil à l'autre, et avec lui la clé des rappels déjà programmés |
| Comparer les identifiants sans tenir compte de la casse | Chaque recherche devrait y penser, et un téléphone pourrait toujours garder deux fichiers que l'ordinateur confondrait |
| Ne rien changer | Une réunion reçue sur l'ordinateur pourrait en écraser une autre |

## Conséquences

**Ce qu'on gagne.** Une réunion se lit et s'écrit à l'identique sur Android, iPhone, Windows, macOS et Linux.

**Ce qu'on paie.** Rien sur les fichiers existants : le cœur tire des UUID en minuscules, et les identifiants plus anciens sont `recorded-<millisecondes>` et `sample-<n>`. Un fichier qu'un autre outil écrirait avec un identifiant en majuscules ne serait plus lu.
