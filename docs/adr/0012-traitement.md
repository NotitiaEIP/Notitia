# ADR 0012 : le traitement d'une réunion, un fil du cœur qui reprend où la réunion en est

| | |
| --- | --- |
| Statut | Proposée le 24 novembre 2026, acceptée à la fusion de sa PR |
| Proposée par | Clément |
| Relue par | Alexandre pour le cœur |
| Tâche d'origine | T-94 (#260), moitié cœur de T-38 (#141) |

## Contexte

Le cœur sait découper l'audio scellé d'une réunion (ADR 0011) et faire tourner un moteur (ADR 0010), mais rien ne les relie. Jusqu'ici, Kotlin transcrit le son du micro gardé en mémoire (`MeetingTranscription`). Ce choix pose trois problèmes :
- si l'app est tuée, le travail est perdu, et le son avec lui ;
- un modèle plus lent que le temps réel garde jusqu'à 115 Mo de son par heure ;
- l'ordinateur n'a aucune transcription.

Il faut un traitement qui lit l'audio du disque et reprend après un arrêt. Il doit tenir sur un téléphone (un seul modèle en mémoire), et l'app ne doit pas pouvoir transcrire une réunion deux fois.

## Décision

Le traitement vit dans le cœur (`core/src/pipeline.rs`), sur un fil à lui, `notitia-pipeline`. Il est le seul propriétaire de ce travail : son état ne change que sur son fil, et les autres ne font que le réveiller.

1. **Le travail restant se déduit des réunions, sans file sur le disque.**
   - **Le champ `transcribedMs`.** La réunion gagne ce champ, facultatif dans la version 2 du format, qui ne monte donc pas (ADR 0003, règle 3). Il vaut le bord de la dernière fenêtre transcrite, d'où l'on reprend. Le bord suffit à retrouver les mêmes fenêtres (ADR 0011).
   - **Absent, la transcription est entière.** C'est le cas de toutes les réunions d'avant, et de celles que l'app transcrit elle-même jusqu'à T-59.
   - **Qui le pose.** `begin` le met à 0. Le `transcribed` de l'app l'enlève : l'app a pris la réunion, et le cœur n'y touche plus.
   - **Ce qu'on refait au lancement.** La liste des réunions qui ont ce champ, et rien d'autre. Un index, une file ou un cache pourraient contredire les fichiers (règle 1 du plan du cœur).
2. **Le choix du travail est une fonction pure** (`next`), testée par une table. On transcrit d'abord la réunion qu'on enregistre, dont on attend le texte en direct (US-26). Viennent ensuite les autres, la plus récente d'abord : celle qu'on vient d'arrêter (US-39). Le compte rendu (G), l'index de recherche (I) et la réponse à une question (J) seront d'autres sortes de travail dans la même fonction. Un pas vaut une fenêtre et un appel au moteur, après quoi `next` est recalculée : une question passera devant une heure de transcription au pas suivant.
3. **Un pas de transcription** :
   - **Lire.** On lit la réunion, puis la fenêtre suivante dans l'audio. L'audio est refermé avant l'appel au moteur : une suppression n'attend jamais un moteur.
   - **Entendre.** On appelle le moteur, avec la fin du texte d'avant (200 caractères) et la langue (`fr`).
   - **Ajouter.** Les lignes s'ajoutent par un changement interne, `heard`, qu'aucune app ne peut envoyer :
     - il **compare puis écrit** : il n'est pris que si la réunion en est encore au bord d'où la fenêtre part. Une fenêtre n'est donc jamais ajoutée deux fois, ni à une réunion supprimée ou prise par l'app ;
     - les lignes sont **ajoutées sous le verrou des écritures** à celles du fichier, jamais à une copie lue avant : une ligne corrigée entre-temps reste corrigée.
   - **Garder les fenêtres.** Elles restent d'un pas à l'autre : pendant l'enregistrement, ce qui a été lu n'est pas relu. Elles sont refaites depuis le bord pour une autre réunion ou un autre moteur. On ne les remet en place qu'après un pas qui les laisse fidèles à la réunion, si bien qu'une fenêtre prise et non entendue n'est jamais sautée.
4. **Pendant l'enregistrement**, la réunion est transcrite au fil de l'eau, comme Kotlin le faisait :
   - le fil se réveille à chaque seconde scellée, puisque le lecteur ne voit que des secondes entières, et quand la réunion tenue change ;
   - l'enregistreur dit quelle réunion il tient avant de la commencer, et le fil liste les réunions avant de lire ce que tient l'enregistreur. Une réunion listée et encore enregistrée n'est donc jamais prise pour une réunion finie ;
   - l'audio de la réunion tenue n'est lu jusqu'au bout qu'une fois l'enregistrement arrêté.
5. **Les réveils ont une génération.** Chaque réveil l'avance : une offre de moteurs, une seconde enregistrée, un enregistrement qui commence ou s'arrête. Une réunion au repos (rien de neuf à entendre) est sautée tant que la génération n'a pas bougé. Ainsi un réveil arrivé pendant un pas n'est jamais perdu, et le fil ne tourne jamais à vide. Les changements du fil lui-même ne l'avancent pas.
6. **Les échecs ne marquent que ce qui est sûr** :

   | Cas | Ce qui se passe |
   | --- | --- |
   | Le moteur rate une fenêtre (`failed`) | Une ligne sans texte, datée où la fenêtre commence à ajouter du son, puis on avance |
   | Le moteur dit `stopped` sans qu'on l'ait arrêté | Comme `failed` : redemander demanderait sans fin |
   | Le moteur n'est pas disponible (`unavailable`) | Rien n'est marqué. Il attend la prochaine offre de l'app, pas chaque seconde enregistrée : ce serait un appel par seconde à une gateway hors d'atteinte |
   | La réunion est enregistrée, mais son audio n'existe pas encore ou ne se lit pas | Au repos, relu à la seconde suivante |
   | L'enregistrement est fini, et l'audio n'existe pas (premier son jamais écrit, audio effacé par D) | La transcription est entière, sans ligne |
   | Un autre défaut de lecture, une panique, ou un `heard` refusé | La réunion attend le prochain lancement. Rien n'est marqué, car le défaut peut passer, et le moteur n'est jamais rappelé en boucle sur la même fenêtre |

7. **Une transcription finie passe le compte rendu de `writing` à `unavailable`**, l'état qui dit « pas de rédacteur dans ce build ». C'est vrai tant que G n'existe pas, et l'écran sait déjà l'afficher. G remplacera ce pas par la rédaction.
8. **`interrupted` ne touche plus que les réunions que le cœur n'entend pas.** Au lancement, un compte rendu `writing` passait en `failed`. C'est maintenant le cas seulement si la réunion n'a pas `transcribedMs` : une réunion de Kotlin tuée en cours, que rien ne reprendra. Une réunion que le cœur entend reprend.
9. **Supprimer et fermer** :
   - `forget` attend la fin d'une lecture en cours, de quelques millisecondes à 1,7 s pour une heure de silence, et arrête l'appel au moteur en cours. Ce qu'il a entendu n'est pas gardé, et la réunion ne revient pas ;
   - fermer la session arrête l'appel en cours et attend qu'il rende la main : un moteur de l'app ne s'interrompt pas en plein milieu. Plus rien n'est écrit ensuite, si bien qu'une autre session peut ouvrir le même dossier.
10. **Pas d'événement `progress`.** L'avancement se lit dans la réunion, que chaque `changed` porte entière, ce qui fait une seule source par valeur :
    - `transcribedMs` sur `durationMs` donne la part transcrite ;
    - un compte rendu `writing` sans `transcribedMs` dit « compte rendu en cours » ;
    - l'événement `engines` sans moteur de voix dit « en attente d'un moteur ».

    Le pont ne change pas.

### Ce qui change par rapport à Kotlin

| Kotlin | Le cœur | Pourquoi |
| --- | --- | --- |
| Le son du micro, gardé en mémoire | L'audio scellé, lu sur le disque | Rien n'est perdu à l'arrêt de l'app, aucun son n'attend en mémoire |
| Lié à l'écran, perdu si l'app est tuée | Un fil du cœur, qui reprend au bord suivant | T-38 |
| Toute la transcription renvoyée à chaque morceau | Les lignes d'une fenêtre ajoutées au fichier | Une correction faite entre-temps n'est pas écrasée |
| Une file de morceaux sans fin | Une fenêtre à la fois, l'ordre recalculé à chaque pas | La réunion en cours d'abord, puis une question (J) |

Le Kotlin reste jusqu'à T-59, qui branche les moteurs de l'app et le supprime. D'ici là, l'app n'offre aucun moteur : le fil ne fait rien, et l'app transcrit comme avant.

## Options écartées

| Option | Pourquoi pas |
| --- | --- |
| Une file de travaux gardée sur le disque (le premier énoncé de T-38) | Un second état à tenir d'accord avec les réunions, à réparer après une coupure. Le bord suffit |
| Un événement `progress`, comme l'annonçait le plan du cœur | Une deuxième source pour ce que la réunion dit déjà |
| Renvoyer toute la transcription à chaque fenêtre, comme `transcribed` | Une correction faite pendant l'appel au moteur serait perdue |
| Supprimer `interrupted` tout de suite, comme l'annonçait le plan | Jusqu'à T-59, une réunion de Kotlin tuée resterait « en cours » à jamais |
| Laisser le compte rendu en `writing` jusqu'à G | Au lancement suivant, `interrupted` le passerait en `failed`, et l'écran tournerait jusque-là |
| Un fil par réunion, ou un pool | Deux modèles en mémoire sur un téléphone. Un fil suffit et garde l'ordre |
| Réessayer un moteur indisponible à chaque réveil | Un appel par seconde pendant un enregistrement, quand le réseau manque |

## Résultat

Mesuré le 24 novembre 2026 sur le portable de l'ADR 0005 (Intel i5-12450H), en release, avec `cargo run --release --example transcribing`. L'heure est celle de l'ADR 0011. Le moteur est prêté comme par le pont : du JSON, 75 mots par fenêtre, une réponse immédiate. Seul le cœur est donc mesuré :
- **Une heure s'entend en 1,7 s** (1,6 s pour le seul découpage) : 140 fenêtres, 12 ms en moyenne et 21 ms au plus chacune. Le traitement lui-même coûte moins de 0,5 ms par fenêtre.
- **Le fichier de la réunion fait 60 Ko à la fin.** Il est réécrit à chaque fenêtre, soit environ 4,2 Mo écrits pour l'heure.
- **Fermée à la 70ᵉ fenêtre puis relancée**, la session entend la fenêtre suivante 18 à 25 ms après le lancement. Le texte repris est, au caractère près, celui entendu sans arrêt.
- **Le processus tient 24 Mo au plus**, l'heure inventée comprise.

Mesuré le même jour sur le Pixel 10 (Android 16) de l'ADR 0006, avec le même exemple compilé par `cargo ndk` :
- **Une heure s'entend en 7,1 s** (1,7 s pour le seul découpage) : 51 ms en moyenne et 116 ms au plus par fenêtre.
- **L'écart vient de l'écriture durable du fichier de la réunion** à chaque fenêtre : le fichier, puis le dossier, forcés sur la mémoire flash, pour qu'une coupure de courant ne perde rien. Cela fait environ 40 ms par fenêtre, peu à côté des secondes qu'un modèle sur téléphone mettra à entendre 30 s de parole (T-06). Écrire plus rarement ferait réentendre au moteur les dernières fenêtres après un arrêt, ce qui coûte bien plus.
- **Fermée à la 70ᵉ fenêtre puis relancée**, la session entend la fenêtre suivante 163 ms après le lancement, avec le même texte.
- **Le processus tient 18 Mo au plus.**

Les tests le prouvent aussi :
- reprendre à chaque bord, un lancement par fenêtre, redonne le texte d'une seule traite ;
- une réunion transcrite pendant son enregistrement finit avec le texte trouvé après coup ;
- un moteur de l'app est appelé depuis le fil du cœur à travers le vrai pont du téléphone (`RustEnginesTest`, JNA).

## Conséquences

**Ce qu'on gagne.**
- Une transcription interrompue reprend où elle était, sur toutes les apps, l'ordinateur compris.
- Plus aucun son n'attend en mémoire. Un seul modèle tourne à la fois.
- Le compte rendu (G), l'index (I) et les questions (J) s'ajoutent comme d'autres sortes de travail dans `next`, sans toucher au fil.

**Ce qu'on paie.**
- Le fichier de la réunion est réécrit à chaque fenêtre : environ 4 Mo pour une heure, 40 Mo pour trois heures, puisque cela croît comme le carré de la durée. Chaque écriture est forcée sur la flash, soit environ 40 ms par fenêtre sur un téléphone.
- Fermer la session attend la fin de l'appel au moteur en cours : jusqu'à 60 s pour la gateway.
- Pendant l'enregistrement, `transcribedMs` peut dépasser `durationMs` de moins de 10 s, la durée n'étant tenue à jour que toutes les 10 s. L'écran borne la part transcrite.
- Une réunion mise de côté pour un défaut qui passe attend le prochain lancement.

**Ce qui reste ouvert.**
- **Le compte rendu (G).** Il remplace le passage à `unavailable`, décide si les réunions `unavailable` reviennent quand un moteur de texte arrive, et ajoute `retry`. Il retire `interrupted` une fois que T-59 a retiré `transcribed`.
- **Le branchement des apps (T-59).** L'app offre ses moteurs et lit `transcribedMs`. Elle offre à nouveau quand un moteur revient : modèle téléchargé, réseau retrouvé.
- **Le réveil du cœur en tâche de fond** : WorkManager sur Android, une tâche de fond sur iPhone. C'est la partie téléphone de T-38.
- **Transcrire seulement après la réunion**, si le moteur de T-06 ne tient pas le temps réel sur un téléphone.
- **La langue de la réunion**, donnée par l'app avec `start` quand l'app parlera anglais.
- **Garder les lignes en mémoire tant que la réunion s'entend**, si le coût de la réécriture se mesure sur des réunions de plusieurs heures.
- **L'appel Swift hors du fil principal**, que rien ne prouve avant un Mac.
