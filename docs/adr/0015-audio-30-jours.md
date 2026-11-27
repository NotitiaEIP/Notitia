# ADR 0015 : l'audio d'une réunion effacé après 30 jours, son texte gardé

| | |
| --- | --- |
| Statut | Proposée le 27 novembre 2026, acceptée à la fusion de sa PR. Points 1 à 3 remplacés en partie par l'[ADR 0016](0016-reglages-du-coeur.md) le 27 novembre 2026 : l'utilisateur choisit combien de temps l'audio reste, 30 jours au plus, et le `tidy` passe toutes les heures |
| Proposée par | Clément |
| Relue par | Alexandre pour le cœur |
| Tâche d'origine | T-97 (#266), la partie cœur de US-82 (#38) |

## Contexte

La règle produit 5 veut un audio gardé 30 jours par défaut, puis supprimé ; la transcription et le compte rendu restent. Le cœur scelle l'audio de chaque réunion à côté d'elle (ADR 0005), mais rien ne l'effaçait : il restait sur l'appareil pour toujours. Chaque app aurait pu l'effacer elle-même, en Kotlin, en Swift et dans Tauri. La règle se serait alors écrite trois fois, et une correction faite d'un côté se serait oubliée des autres.

## Décision

1. **La demande `{"type":"tidy"}`** efface l'audio des réunions commencées depuis 30 jours ou plus. Les 30 jours ne sont écrits que dans le cœur (`KEPT_DAYS`, `core/src/meeting/retention.rs`) : aucune app ne les répète. Le choix de l'utilisateur (« Jusqu'à la transcription », 1 jour, 1 semaine, 1 mois) viendra avec T-103, gardé par le cœur lui-même, si bien que la demande n'a pas d'argument.
2. **L'app décide quand, le cœur ne décide jamais seul.** L'app envoie `tidy` une fois le cœur ouvert, puis chaque jour tant qu'elle vit. Le cœur n'a pas d'horloge à lui (ADR 0009) : sans demande, il n'efface rien. Un seul déclencheur, celui de l'app, qui sait quand elle s'ouvre et quand un jour passe. **Chaque app** l'envoie de la même façon : le téléphone par `CoreSession`, l'ordinateur (T-48) et le Mac (T-87) par leur propre façade.
3. **La règle est pure** : une réunion expire quand le temps écoulé depuis son début atteint les jours gardés. On compare une différence de deux instants, qui ne déborde jamais, plutôt que d'ajouter des jours à une date de l'an 9999, ce qui paniquerait (le piège de T-88). L'heure vient de l'horloge injectée de `Library` (ADR 0009).
4. **Marquer, puis effacer** (`Library::erase_audio`). Pour chaque réunion expirée :
   1. la réunion reçoit `audioErasedAt`, la date de l'effacement, et sa modification est datée et signée comme les autres ;
   2. le traitement referme l'audio qu'il lit (`Pipeline::let_go`) ;
   3. le fichier `.audio` est supprimé, et le dossier synchronisé une fois.

   Si l'app est tuée entre la marque et la suppression, la réunion reste marquée avec son audio encore là, et le `tidy` suivant le supprime. Dans l'autre ordre, une réunion sans audio resterait sans date. Un fichier qu'on ne peut pas supprimer (un antivirus qui le tient sous Windows, par exemple) reste marqué, et le `tidy` du lendemain recommence.
5. **Un audio marqué ne s'ouvre plus**, même si son fichier attend d'être supprimé : `sound`, le traitement et l'enregistreur rendent `Absent`. L'enregistreur ne le recrée donc jamais. Au lancement, on ne l'ouvre pas non plus pour lire sa durée.
6. **Jamais la réunion qu'on enregistre.** Elle est épargnée même en pause ou interrompue, puisque c'est l'enregistreur qui répond (`Recorder::holding`), dans l'ordre de ses commandes. Une réunion qu'il ne tient pas, il ne la tiendra plus jamais, car chaque enregistrement tire un identifiant neuf. Son audio part au `tidy` qui suit l'arrêt.
7. **Une réunion sans audio n'est jamais marquée.** Les réunions de l'app Kotlin n'en ont jamais eu, puisque le son restait en mémoire. « Audio effacé le … » serait faux pour elles. Le cœur vérifie donc que le fichier existe avant de marquer. Les exemples prêtés n'ont pas d'audio et ne sont jamais touchés.
8. **Sans clé, rien n'est effacé.** La marque ne pourrait pas s'écrire (ADR 0014, point 6) : au lancement suivant, la réunion n'aurait plus d'audio et ne dirait pas quand il est parti. Sur Android, c'est le cas d'un raté passager du Keystore. L'audio part alors au `tidy` suivant, avec la clé, au plus un jour plus tard. L'iPhone sans clé n'enregistre rien, et n'a donc rien à effacer.
9. **Une transcription inachevée se ferme sur ce qui a été entendu.** L'effacement passe à `None` un `transcribedMs` encore en cours, comme le ferait la fin de l'audio. Le compte rendu n'est dû que si une ligne dit quelque chose (`MinutesDraft::owed`). Sinon, il est écrit vide, sans appel au modèle. Les lignes qui ne sont que dans le fichier sont relues sous `behind`, comme pour `heard`. Un compte rendu déjà écrit reste tel quel.
10. **Le traitement ne boucle pas.**
   - **Fenêtre refusée.** Si une fenêtre est entendue pendant l'effacement, son `heard` est refusé : la réunion n'attend plus à ce bord. Elle n'est pas mise de côté, et son compte rendu s'écrit dans la même session (ADR 0012, point 6).
   - **Réunion à réentendre.** `rehear` est refusé pour une réunion effacée.
   - **Rédaction redemandée.** Un `rewrite` sur une réunion effacée où rien n'a été dit se termine en compte rendu vide, sans appel au moteur.
11. **L'ordre des verrous** : `behind` puis `meetings`, et `forgotten` puis `meetings`, puisque le traitement lit la marque en tenant `forgotten`. Rien ne prend `meetings` puis un autre verrou, et le `tidy` ne tient jamais `forgotten` et `behind` ensemble.
12. **Le format** : `audioErasedAt` est un instant UTC facultatif de la version 2, écrit après `transcribedMs`. Une app plus ancienne le garde parmi les champs qu'elle ne connaît pas (ADR 0003). Ni le témoin `meeting-v2.notitia` ni la version de l'index ne changent.
13. **L'écran d'une réunion** effacée affiche « Audio effacé le 3 déc. », sous la date et la durée. La ligne des réglages « Conserver l'audio : 30 jours » reste telle quelle.

## Options écartées

| Option | Pourquoi pas |
| --- | --- |
| Chaque app efface elle-même | La règle 5 écrite trois fois, en Kotlin, en Swift et en TypeScript |
| Le cœur efface seul, sur un minuteur à lui | Le cœur n'a pas d'horloge à lui (ADR 0009). Un fil qui se réveille chaque jour serait un second déclencheur à côté de celui de l'app |
| Effacer d'abord, marquer ensuite | Une app tuée entre les deux laisserait une réunion sans audio et sans date |
| Marquer toutes les réunions expirées | Celles de Kotlin, qui n'ont jamais eu d'audio, diraient « Audio effacé » |
| Laisser la transcription d'une réunion effacée ouverte | Sans moteur depuis 30 jours, elle afficherait « Transcription x % » pour toujours |
| Mettre de côté une réunion dont le `heard` est refusé, comme avant | Son compte rendu attendrait le lancement suivant |

## Résultat

Mesuré le 27 novembre 2026 sur le portable de l'ADR 0005 (Intel i5-12450H), en release, avec `cargo run --release --example opening` : 300 réunions enregistrées de 10 s, chacune avec son audio et un compte rendu dû, dont le `tidy` efface l'audio, ferme la transcription et réécrit le fichier.

| Où | Les 300 effacées |
| --- | --- |
| Disque NVMe (ext4) | 593 ms, soit 2 ms par réunion : deux synchronisations du fichier et du dossier, plus la suppression |
| `/tmp` en mémoire (tmpfs) | 6,5 ms : ce que coûte le cœur seul, hors disque |
| Pixel 10, mesuré par `cargo ndk` et `adb` comme pour T-94 | 2,4 s, soit 8 ms par réunion |

Ce coût ne se paie qu'une fois, au premier `tidy` d'un téléphone de l'équipe qui a des réunions de plus de 30 jours. Ensuite, le `tidy` quotidien ne touche que les réunions d'un jour. Il passe après la liste, sur le fil des appels de l'app.

## Conséquences

**Ce qu'on gagne.** La règle produit 5 est tenue par le cœur, pour toutes les apps. L'audio d'une réunion ne vit pas plus longtemps que ce que l'utilisateur garde, et chaque réunion dit quand le sien est parti.

**Ce qu'on paie.**
- Chaque app doit envoyer `tidy`, sans quoi rien ne s'efface. Une app qui ne l'envoie pas ne casse rien, mais elle ne tient pas la règle 5.
- Une réunion jamais transcrite pendant ses 30 jours perd son audio sans transcription. C'est le cas d'un appareil sans moteur pendant un mois. La règle 5 passe avant.
- Le premier `tidy` d'un téléphone de l'équipe prend environ 2,4 s pour 300 réunions (voir le résultat).
- Un disque plein au moment de la marque n'empêche pas l'effacement : la règle 5 passe avant. La marque reste en mémoire, signalée par `unsaved`, et s'écrit avec la modification suivante. Si l'app est tuée avant, la réunion perd son audio sans dire quand.

**Ce qui reste ouvert.**
- Le choix de l'utilisateur, gardé par le cœur (T-103), et son écran (US-88) : le critère 2 de US-82.
- Dire à l'écran qu'un effacement a échoué. Aujourd'hui, il est seulement retenté le lendemain.
