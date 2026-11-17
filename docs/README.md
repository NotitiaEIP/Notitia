# Documentation de Notitia

Toute la documentation du projet : ce qu'on construit cette année, comment c'est construit, et pourquoi. Chaque page commence par sa conclusion, puis la montre par un schéma.

![Une seule cuisine, plusieurs salles : le cœur Rust sert toutes les apps](schemas/02-architecture.svg)

## Nouveau dans l'équipe

Commence par **[Bien démarrer](demarrer.md)** : lancer l'app, la carte du repo, le cycle d'une tâche et les cinq règles, en une page.

## Le projet

| Page | La question à laquelle elle répond |
| --- | --- |
| [Le cadre](projet/cadre.md) | Ce qu'on fait cette année, ce qu'on ne fait pas, les jalons et qui décide |
| [Vue d'ensemble](projet/vision.md) | À quoi ressemble Notitia une fois fini, et quelles règles dessinent l'architecture |
| [La feuille de route](projet/feuille-de-route.md) | Dans quel ordre on construit, et les risques qu'on surveille |
| [Le benchmark](projet/benchmark.md) | Comment font les leaders et nos concurrents, sources à l'appui |
| [Les sources](projet/sources.md) | Tous les liens du benchmark, avec la date de vérification |

## L'architecture

| Page | La question à laquelle elle répond |
| --- | --- |
| [Le cœur commun en Rust](architecture/coeur-rust.md) | Pourquoi un seul cœur, pourquoi Rust, et ce qu'on y perd |
| [Android et iPhone](architecture/android-iphone.md) | Pourquoi Kotlin et Compose pour les écrans du téléphone |
| [Mac](architecture/mac.md) | Pourquoi le Mac reste en Swift, et ce qui change pour lui |
| [Windows et Linux](architecture/windows-linux.md) | Pourquoi une seule interface, dans Tauri, et comment capter une visio |
| [Le lien entre appareils](architecture/lien.md) | Comment le téléphone et l'ordinateur se parlent sans serveur |

## Les décisions

Une fiche se lit en deux minutes et ne change plus : pour revenir sur un choix, on écrit une nouvelle fiche qui remplace l'ancienne.

| Fiche | Décision | Statut |
| --- | --- | --- |
| [0002](adr/0002-coeur-rust.md) | Un cœur Rust pour toutes les apps, une interface par famille d'appareils | Acceptée le 15 novembre 2026 |
| [0003](adr/0003-format-notitia-v2.md) | Le format `.notitia` version 2 : un identifiant par action, le temps en millisecondes, les champs inconnus gardés | Proposée le 16 novembre 2026 |
| [0004](adr/0004-pont-json.md) | Le pont entre le cœur et les apps : trois fonctions, un écouteur, du JSON | Proposée le 17 novembre 2026 |

## Ailleurs dans le repo

Ces documents restent à côté du code qu'ils décrivent.

| Document | Ce qu'il contient |
| --- | --- |
| [DESIGN.md](../DESIGN.md) | La charte de l'app Android et iPhone : couleurs, police, tailles, espacements |
| [mobile/README.md](../mobile/README.md) | Lancer et construire l'app Android et iPhone |
| [macos/README.md](../macos/README.md) | L'app Mac et sa documentation propre, dans `macos/docs/` |

## Le code couleur des schémas

Les mêmes couleurs veulent dire la même chose sur toutes les pages.

| Couleur | Ce qu'elle désigne |
| --- | --- |
| Bleu | Une interface : ce que l'utilisateur voit |
| Violet | Le cœur Rust : ce qui fait le travail |
| Indigo | Un pont entre une interface et le cœur |
| Vert | Du code propre à un système (Android, iPhone, Mac) |
| Turquoise | L'IA qui tourne sur l'appareil |
| Gris | Le stockage, ou un élément neutre |
| Orange et rouge | Un point d'attention, une option écartée, un serveur |

## Tenir ce dossier à jour

- Le cadre ne change qu'à la revue de fin de jalon, par une PR que toute l'équipe relit (partie 9 du cadre).
- Un choix technique qui change passe par une nouvelle fiche dans `adr/`, puis par la page concernée.
- Les schémas sont des fichiers SVG dans [`schemas/`](schemas/). Ils s'ouvrent et se corrigent dans un éditeur de texte.
- Les icônes des schémas sont dans [`schemas/icones.svg`](schemas/icones.svg). Un schéma reprend le symbole dont il a besoin dans ses propres `<defs>`, car GitHub n'affiche pas un SVG qui en charge un autre.
- Chaque fait du benchmark porte sa date. Un fait qu'on n'a pas pu vérifier est écrit comme tel.
