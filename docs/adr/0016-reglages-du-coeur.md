# ADR 0016 : les réglages tenus par le cœur, à commencer par la durée de l'audio

| | |
| --- | --- |
| Statut | Proposée le 27 novembre 2026, acceptée à la fusion de sa PR |
| Proposée par | Clément |
| Relue par | Alexandre pour le cœur |
| Tâche d'origine | T-103 (#276), la partie cœur du critère 2 de US-82 (#38) |

## Contexte

Depuis T-97 (ADR 0015), le cœur efface l'audio d'une réunion après 30 jours, une durée fixe. L'utilisateur doit pouvoir choisir : jusqu'à la transcription, 1 jour, 1 semaine ou 1 mois. Si chaque app gardait ce choix, il serait stocké trois fois (le téléphone, l'ordinateur sous Tauri, le Mac), et une durée imposée par une organisation (E11) devrait passer par chacune. La question dépasse l'audio : quels réglages vont dans le cœur, et lesquels restent dans l'app ?

## Décision

1. **Un réglage va dans le cœur quand une règle du cœur le lit.** C'est le cas de la durée de l'audio. Plus tard viendront l'heure des rappels (T-61), la langue des réunions et le moteur préféré. Ce qui ne touche que l'écran, comme le thème, reste dans l'app (T-44).
2. **Quatre choix, du plus court au plus long** : `untilHeard`, `day`, `week` et `month`. Le défaut est `month`, soit 30 jours. La règle tient dans `core/src/meeting/retention.rs`. L'audio d'une réunion part dans deux cas :
   - la réunion a 30 jours ou plus, transcrite ou non ;
   - elle est transcrite, et le délai choisi est passé depuis son début.

   Une réunion pas encore transcrite garde donc son audio jusqu'à 30 jours, quel que soit le choix : elle n'est jamais perdue parce que le modèle se télécharge encore. « Jusqu'à la transcription » ne regarde pas l'âge de la réunion : une horloge réglée avant la réunion ne garde pas un audio que l'utilisateur ne veut plus.

   Une réunion est transcrite quand `transcribedMs` est absent, sauf si elle n'a jamais été entendue (`Meeting::never_heard`) : aucune ligne alors qu'elle dure, un compte rendu pas écrit, et son audio encore là. C'est le cas d'une réunion que le cœur a enregistrée avant T-94, quand `transcribedMs` n'existait pas, et qu'aucun moteur n'a entendue. Le traitement l'entend depuis le début avant d'écrire son compte rendu (`rehear`), et la même fonction dit à la règle de garder son audio. La liste des réunions ne tient pas leurs lignes : le cœur lit donc le fichier, mais seulement pour une réunion déjà due qui pourrait ne jamais avoir été entendue. Avec le défaut, il n'en lit aucun.
3. **Le fichier `settings`** est rangé dans le dossier des réunions, à côté de `device` : `{"version":1,"keepAudio":"week"}`.
   - **Il est en clair**, comme `device` (ADR 0014, point 7) : un choix ne dit rien d'une réunion. Il s'écrit donc aussi sans clé. L'écriture suit le chemin des autres fichiers : un brouillon, la synchronisation, puis le renommage. Seul son propriétaire peut le lire.
   - **La version se lit avant le reste**, comme pour une réunion. Un fichier absent, abîmé ou d'une autre version donne le défaut. Il n'est pas réécrit tant que l'utilisateur ne fait pas de choix : une app plus récente l'a peut-être écrit. Le défaut est le plus long des choix, donc un fichier abîmé n'efface jamais rien de plus.
   - **Les champs inconnus sont réécrits tels quels** : une app plus récente qui ajoute un réglage sans changer de version ne perd rien.
   - **Son témoin** est `core/tests/fixtures/settings-v1.json`, épinglé par son empreinte dans `core/src/testing.rs` (ADR 0009). Il se relit et se réécrit à l'octet près.
4. **La demande est partielle, l'événement est entier.**
   - La demande `{"type":"settings","keepAudio":"day"}` change le choix. Un champ absent ne change rien : un réglage ajouté plus tard n'aura pas besoin d'une autre demande. Un choix inconnu rend la demande illisible (`Invalid`).
   - L'événement `{"type":"settings","keepAudio":"month"}` porte tous les réglages. Il est dit à l'ouverture, juste après `listed`, puis à chaque changement.
   - Un choix identique au précédent n'écrit rien et ne dit rien.
   - Les exemples sont dans `core/tests/fixtures/bridge/settings.json`.
5. **`Library` tient les réglages**, sous un verrou à eux, jamais pris avec un autre. Ce verrou est tenu du choix jusqu'à son fichier et à son événement : deux choix venus de deux fils s'écrivent et se disent dans l'ordre. Le choix change d'abord en mémoire, puis le fichier s'écrit, puis l'app est prévenue. Si le fichier ne s'écrit pas (disque plein), le choix tient pour la session, et le `tidy` suivant réécrit le fichier.
6. **Un choix plus court efface aussitôt** ce qui le dépasse, par le chemin du `tidy` (ADR 0015) : la réunion qu'on enregistre est épargnée, le traitement referme l'audio qu'il lit, puis le fichier part. **Un choix plus long n'efface rien** : l'écran ne demande une confirmation que pour un choix plus court (US-88).
7. **À la fin d'une transcription**, le traitement applique la règle à la réunion qu'il vient d'entendre en entier (`Library::erase_heard`). Avec « Jusqu'à la transcription », son audio part tout de suite. Le traitement vient de refermer cet audio, et l'enregistreur ne tient plus une réunion entendue en entier (ADR 0015, point 6) : il n'y a ni attente ni réunion à épargner. Le compte rendu, dû ensuite, ne lit que les lignes (ADR 0013). `rehear` ne concerne qu'une réunion qui n'a aucune ligne.
8. **Le `tidy` passe toutes les heures** au lieu de chaque jour (ADR 0015, point 2). Avec « 1 jour », un audio ne reste donc jamais deux jours. Le cœur n'efface toujours que sur trois déclencheurs : une demande de l'app, un choix de l'utilisateur, ou la fin d'une transcription qu'il a faite lui-même. Jamais sur une horloge à lui.

## Options écartées

| Option | Pourquoi pas |
| --- | --- |
| Chaque app garde le choix | Il serait stocké trois fois, et une règle d'organisation passerait par chacune |
| Le choix en argument de `tidy`, comme le prévoyait le premier plan de T-97 | L'app devrait le garder, et le cœur ne pourrait pas l'appliquer seul à la fin d'une transcription |
| Le délai compté depuis la fin de la transcription | Il faudrait dater la fin de la transcription dans le format. La date de la réunion est ce que l'utilisateur voit |
| Le délai choisi appliqué aussi à une réunion pas encore transcrite | Une réunion enregistrée sans modèle perdrait son audio avant d'être transcrite |
| Un fichier `settings` scellé | Il n'a rien à cacher. Scellé, il devrait attendre la clé, qui manque à l'iPhone tant que US-81 n'est pas fait |
| Lancer un `tidy` aussi après un choix plus long | Il effacerait sans confirmation ce que l'écran n'a pas annoncé |
| Annoncer « effectif dans 24 h » plutôt qu'un `tidy` toutes les heures | Un choix plus court s'applique déjà tout de suite. Seuls les délais qui arrivent ensuite attendent, une heure au plus |

## Résultat

Mesuré le 27 novembre 2026 avec `cargo run --release --example opening` : un `tidy` qui n'a plus rien à effacer, sur 300 réunions de plus de 30 jours dont l'audio est déjà parti. C'est le pire cas d'un téléphone de l'équipe : le cœur cherche le fichier de chacune, sans rien lire, écrire ni dire.

| Où | Le meilleur et le pire de 20 `tidy` |
| --- | --- |
| Portable de l'ADR 0005, disque NVMe (ext4) | 0,17 ms et 0,40 ms |
| Pixel 10 (Android 16), par `cargo ndk` et `adb`, deux passes | 1,26 à 1,29 ms et 4,05 à 4,78 ms |

À 24 `tidy` par jour, cela fait moins de 10 ms par jour sur le portable, et moins de 0,12 s sur le Pixel au pire. Côté batterie, c'est un `delay` de coroutine, sans alarme ni verrou de réveil : quand le téléphone dort, Android gèle l'app, et le `tidy` attend son réveil.

Seize erreurs provoquées dans la règle, le fichier, la demande et le traitement sont chacune refusées par un test. Les tests à fils de `library`, `session`, `pipeline` et `recorder` passent 30 fois de suite. Sur l'hôte, `RustEnginesTest` fait passer « Jusqu'à la transcription » par le vrai cœur : l'`.audio` disparaît, et le compte rendu s'écrit quand même.

## Conséquences

**Ce qu'on gagne.**
- Une seule source du choix pour toutes les apps. L'ordinateur (T-48) et le Mac (T-87) n'auront qu'à envoyer `settings` et à suivre son événement.
- La règle produit 5 respecte le choix de l'utilisateur sans qu'aucune app ne la répète.
- Une organisation pourra un jour imposer une durée à un seul endroit.

**Ce qu'on paie.**
- Avec « Jusqu'à la transcription », une fenêtre que le moteur a ratée, c'est-à-dire une ligne sans texte, ne pourra plus jamais être réentendue. C'est le choix de l'utilisateur, et aucune fonction de retranscription n'existe encore.
- Un fichier `settings` écrit par une app plus récente est remplacé si l'utilisateur fait un choix dans l'app plus ancienne.
- Le `tidy` passe 24 fois par jour au lieu d'une : quand il n'a rien à effacer, de 1 à 5 ms chaque fois sur le Pixel.

**Ce qui reste ouvert.**
- L'écran des quatre choix et la confirmation avant d'effacer : US-88 (#277).
- Une durée imposée par une organisation (E11).
- Dire à l'écran qu'un choix n'a pas pu s'écrire. Aujourd'hui, le `tidy` suivant réessaie seulement.
