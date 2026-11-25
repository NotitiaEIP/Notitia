# ADR 0013 : le compte rendu écrit par le cœur, par tranches, sur le fil du traitement

| | |
| --- | --- |
| Statut | Proposée le 25 novembre 2026, acceptée à la fusion de sa PR |
| Modifiée | Le 25 novembre 2026 par T-59 (#190) : l'écran suit le cœur, et une réunion jamais entendue l'est avant d'être rédigée |
| Proposée par | Clément |
| Relue par | Alexandre pour le cœur |
| Tâche d'origine | T-95 (#262) |

## Contexte

Le cœur transcrit les réunions sur son fil (ADR 0012), mais le compte rendu s'écrit encore en Kotlin (`MinutesWriter`), en un seul appel, juste après la transcription que fait l'app. Cela pose trois problèmes :
- une réunion d'une heure, environ 15 000 jetons, dépasse ce qu'un petit modèle lit d'un coup (#182) ;
- une décision inventée par le modèle s'affiche comme une vraie (#183) ;
- l'ordinateur n'a aucun compte rendu.

Tant que le cœur termine chaque transcription par `unavailable`, l'app ne peut pas lui confier la transcription (T-59) sans perdre le compte rendu.

## Décision

Le compte rendu s'écrit dans le cœur. `core/src/minutes/` sait quoi demander à un moteur de texte, lire sa réponse et découper une heure, sans fil ni disque. Le traitement (`pipeline/write.rs`) l'appelle, un appel au moteur par pas.

1. **`writing` veut dire « compte rendu dû ».**
   - Une transcription entière laisse `writing`. Le compte rendu s'écrit dès qu'un moteur de texte existe, tout de suite ou quand un modèle arrive.
   - Le cœur n'écrit plus jamais `unavailable`. Ce qui manque à l'appareil à un instant n'est pas un état de la réunion. L'événement `engines` dit à l'écran si un rédacteur existe.
   - Une transcription où rien n'a été dit se clôt en compte rendu vide, `written`, sans moteur, comme le faisait Kotlin.
   - `interrupted` disparaît : au lancement suivant, il aurait fait passer en `failed` un compte rendu dû.
2. **Le travail se déduit de l'état** (règle 1 du plan du cœur). Une réunion doit sa transcription tant qu'elle a `transcribedMs`, puis son compte rendu tant qu'il est `writing`. `next` ne lit que cela.
3. **L'ordre** : d'abord la réunion qu'on enregistre, pour son texte en direct (US-26). Ensuite chaque réunion de la plus récente à la plus ancienne, jusqu'au bout : transcrite, puis rédigée. Celle qu'on vient d'arrêter est donc prête avant l'arriéré (US-39). Un pas fait un seul appel : l'enregistrement en cours passe entre deux appels d'un long compte rendu.
4. **Les tranches (#182).**

   | Étape | Ce qui se passe |
   | --- | --- |
   | La place d'une tranche | `(fenêtre − réponse) × 85 %`, moins la consigne et le plus long en-tête. Les 15 % couvrent l'estimation de 3 octets par jeton d'un moteur prêté |
   | Le découpage | Les lignes dans leur ordre, autant par tranche qu'il en tient. Une ligne plus longue qu'une tranche est coupée entre deux mots, et un mot trop long à l'intérieur : rien n'est perdu |
   | Une seule tranche | Un seul appel, avec la consigne de Kotlin |
   | Plusieurs tranches | Un compte rendu par tranche, puis un appel qui fond les résumés. S'ils ne tiennent pas dans un appel, ils sont fondus par groupes jusqu'à un seul |
   | L'assemblage | Les décisions et les actions bout à bout, chacune gardée une fois (même texte, à la casse, aux espaces et au point final près) |

5. **Un compte rendu interrompu reprend où il était.**
   - Les parties déjà lues restent en mémoire, une par réunion, jamais sur le disque.
   - À chaque pas, les tranches sont redécoupées depuis la transcription, et comparées par leur empreinte. Une autre transcription, ou un moteur qui découpe autrement, fait repartir du début.
   - Une app tuée repart du début au lancement suivant.
6. **Ce qui est porté de Kotlin, et corrigé.**
   - **La consigne mot pour mot**, en anglais, pour un compte rendu en français.
   - **Une décision doit citer l'heure de sa phrase**, comme une action (`DECISION: … | 02:03`). Sinon elle est écartée : une décision inventée n'a pas de phrase à montrer (#183). Le format 2 n'a pas de place pour cette phrase : elle n'est pas gardée.
   - **L'heure citée** est comparée à la seconde : `00:05`, `0:05` et `[00:05]` désignent la même ligne.
   - **Le raisonnement** d'un modèle (`<think>`) est retiré. Un raisonnement jamais refermé ne contient pas de réponse.
   - **Une réponse sans aucune ligne demandée** est un échec, plus un compte rendu vide qui ferait croire qu'il ne s'est rien dit.
   - **« Aucune décision n'a été prise »** est reconnu à la main, sans crate `regex`.
7. **Les échecs.**

   | Cas | Ce qui se passe |
   | --- | --- |
   | Le moteur de texte n'est pas disponible | Rien n'est marqué, le brouillon est gardé. Les comptes rendus attendent la prochaine offre, et la transcription continue |
   | Un appel échoue, ou dit `stopped` sans qu'on l'ait arrêté | `failed` |
   | Une réponse illisible | `failed` |
   | Une panique | La réunion attend le prochain lancement |
   | Une suppression ou une fermeture pendant l'appel | L'appel est arrêté. Rien n'est écrit |

8. **`rewrite`**, une demande de l'app, remet `failed` ou `unavailable` en `writing`. Elle est refusée pour un compte rendu écrit (l'utilisateur l'a peut-être corrigé), une transcription pas finie, ou un échantillon. La session réveille le traitement après chaque demande de l'app qui change une réunion. Le nom évite le `retry` de #158.
9. **Un moteur de texte offert laisse au moins 1 024 jetons à l'invite** (`SHORTEST_PROMPT`). Sinon l'offre est refusée : la consigne et quelques lignes ne tiendraient pas.
10. **Au lancement**, seul l'audio dont la taille dit une autre durée que la réunion est ouvert. La taille d'un audio, avec son en-tête en clair, donne ses secondes sans clé (ADR 0005). Sinon, trois cents réunions dont le compte rendu attend un modèle seraient déchiffrées à chaque lancement.

## Options écartées

| Option | Pourquoi pas |
| --- | --- |
| Garder `unavailable` et le réécrire en `writing` quand un modèle arrive | Écrire dans la réunion ce qui dépend de l'appareil, puis le corriger : deux sources pour la même chose |
| Écrire le compte rendu d'une traite, en plusieurs appels | Une réunion qu'on enregistre attendrait plusieurs minutes son premier texte |
| Garder les brouillons sur le disque | Une file sur le disque, que la règle 1 interdit, pour économiser une dizaine d'appels après un arrêt brutal |
| Garder la citation d'une décision dans un champ à côté de `decisions` | Deux listes parallèles, que la première correction à la main (US-38) désaccorderait |
| Fondre les doublons de sens entre tranches | Il faudrait un appel de plus et un modèle qui juge. Les doublons exacts suffisent à la bêta |

## Résultat

Mesuré le 25 novembre 2026 sur le portable de l'ADR 0005, en release, avec `cargo run --release --example transcribing`. Les moteurs sont prêtés comme par le pont et répondent à l'instant : seul le cœur est mesuré.
- **Le compte rendu d'une heure** (140 lignes, 60 Ko) prend **10 appels** à un moteur de 4 096 jetons : 9 tranches et leur fusion. Autour d'eux, le cœur passe **1,1 ms**.
- **L'heure s'entend toujours en 1,7 s**, et la reprise après un arrêt redonne le même texte.

Avec `cargo run --release --example opening`, 300 réunions enregistrées dont le compte rendu attend un modèle s'ouvrent en **1,5 ms au mieux, contre 3,0 ms** quand chaque audio était déchiffré.

Les tests le prouvent aussi :
- une heure en tranches ne perd rien de sa fin, aucune tranche ne dépasse sa place, et chaque ligne est lue une fois ;
- un moteur absent au milieu reprend sans redemander une tranche faite ;
- la réunion qu'on enregistre passe entre deux appels d'un long compte rendu ;
- un moteur de texte de l'app est appelé depuis le fil du cœur à travers le vrai pont du téléphone (`RustEnginesTest`).

## Conséquences

**Ce qu'on gagne.**
- Toutes les apps ont le même compte rendu, l'ordinateur compris. Une heure tient dans un petit modèle.
- Une décision affichée pointe toujours vers une phrase dite.
- Les comptes rendus en attente s'écrivent seuls quand un modèle arrive.

**Ce qu'on paie.**
- Une heure coûte une dizaine d'appels au lieu d'un. Sur un téléphone, chacun prend plusieurs secondes.
- Un petit modèle qui oublie l'heure des décisions les perd toutes. À mesurer sur le jeu de T-91.
- Entre la fusion de cette PR et celle de T-59, une transcription Kotlin tuée en cours reste « Rédaction… » au lieu d'« échec ». T-59 la fait rédiger par le cœur.

**Ce qui reste ouvert.**
- **Le branchement des apps (T-59).** L'app offre la gateway comme moteur de texte, montre « en attente du modèle » et « Réessayer », et retire `transcribed`. Jusque-là, aucun moteur de texte n'est offert : si un moteur était offert pendant que Kotlin transcrit, le cœur rédigerait trop tôt. Un compte rendu vide sur une transcription dont chaque passage a raté se dit « transcription ratée » à l'écran, pas « rien n'a été dit ». Fait par T-59 (#190) :
  - sans moteur de texte, la carte dit « En attente d'un modèle », et l'écran « Le compte rendu attend un modèle sur ce téléphone. » ;
  - un compte rendu en échec offre « Rédiger à nouveau », un `unavailable` laissé par une ancienne app « Rédiger le compte rendu » : deux verbes qui disent ce qui se passe (`DESIGN.md`), à la place du bouton de partage ;
  - un compte rendu écrit vide, sur des lignes qui ont toutes raté, dit « Aucun passage n'a pu être transcrit. » ;
  - la vibration « succès » part quand un compte rendu attendu sur son écran arrive ;
  - **une réunion jamais entendue est entendue avant d'être rédigée** (`rehear`, modification interne du cœur). Au pas d'écriture, une réunion sans aucune ligne et de durée non nulle repasse `transcribedMs` à 0 au lieu d'un compte rendu vide sur une heure d'audio. Ce sont les réunions `unavailable` des téléphones de l'équipe, enregistrées sans gateway, et les transcriptions Kotlin tuées avant leur première ligne. Pas de boucle : une écoute finie sans texte clôt en `written` vide, jamais en `writing`. Ce pas n'a lieu qu'avec un moteur de texte, comme toute écriture ;
  - une transcription Kotlin tuée après sa première ligne est rédigée depuis ce qui a été entendu, comme prévu plus haut.
- **La citation d'une décision gardée**, avec un format qui la porte, quand viendront les corrections à la main (US-38).
- **Alterner le modèle de voix et le modèle de texte sur l'appareil** coûte un chargement à chaque fois. Pour un moteur du cœur (T-83), rédiger après la réunion se réglera dans `next`.
- **Une demande `count` exacte des jetons**, si le moteur de T-06 compte loin de la moyenne.
- **La langue du compte rendu**, donnée par l'app avec celle de la réunion.
