# ADR 0006 : l'enregistrement tenu par le cœur, sur un fil à lui

| | |
| --- | --- |
| Statut | Proposée le 18 novembre 2026, acceptée à la fusion de sa PR |
| Modifiée | Le 20 novembre 2026 par T-58 (#189) : les apps passent par l'enregistreur, mesuré sur un téléphone |
| Proposée par | Clément |
| Relue par | Alexandre pour le cœur |
| Tâche d'origine | T-81 (#241), troisième étape de T-58 (#189) |

## Contexte

L'état d'un enregistrement vit dans `AudioRecorder`, en Kotlin. Il change depuis le fil du micro et depuis l'écran. L'app Windows et Linux n'aura pas de Kotlin, et l'audio est maintenant écrit par le cœur (ADR 0005).

Le micro ne doit jamais attendre : un téléphone qui rate des lectures perd du son. Le disque, lui, peut prendre du temps.

## Décision

1. **Un acteur.** `Recorder` (`core/src/recorder.rs`) possède un fil. Les commandes et le son passent par une seule file, dans l'ordre où ils ont été donnés. L'état ne change que sur ce fil : une lecture partie avant une pause compte, une lecture arrivée après est jetée.
2. **Le micro ne fait que déposer.** `hear(piste, octets)` copie le son dans la file et rend la main, sans prendre de verrou. Le son arrive en 16 bits petit-boutiste, 16 kHz. Un octet impair attend la lecture suivante.
3. **La mémoire est bornée.** La file garde au plus 10 secondes de son par piste, mesurées par un compteur sans verrou. Au-delà, le son est jeté et l'enregistrement passe en `interrupted`, une fois écrit ce qui précède. Une piste qui prend 10 secondes d'avance sur une autre l'interrompt aussi. Une réunion n'a donc jamais de trou que personne n'a vu, et l'app ne grossit jamais avec la durée d'une réunion.
4. **La réunion naît au premier son**, avec un UUID tiré par le cœur. Un micro refusé avant tout son ne laisse ni réunion ni fichier.
5. **La durée d'une réunion est celle de son audio**, jamais un compteur à côté. Le minuteur suit les trames que tient l'écrivain de l'audio. La durée de la réunion est écrite toutes les 10 secondes, puis relue dans l'audio à l'arrêt, où la dernière trame est complétée de silence : une seconde refusée par le disque n'est ni dans l'un ni dans l'autre. Après un arrêt brutal, le lancement suivant relit la durée de l'audio, seulement pour les réunions encore « en cours d'écriture ».
6. **Sans clé, rien n'est enregistré** : `start` rend `failed(storage)`.
7. **Le fuseau vient de l'app.** `start` porte `offsetMinutes`, que la réunion garde : son jour sur le calendrier est le même sur tous les appareils. Le cœur ne demande jamais le fuseau au système. C'est un champ facultatif du format version 2, qui ne monte pas la version (ADR 0003, règle 3).
8. **`Session`** (`core/src/session.rs`) compose le cœur : la bibliothèque et l'enregistreur. Le pont des téléphones et l'app de l'ordinateur appellent les mêmes fonctions : `open`, `apply`, `hear`, `meeting` et `sound`, qui rend le son prêt à jouer, les pistes additionnées, une minute au plus par appel.
9. **Supprimer une réunion qui s'enregistre** : l'enregistreur la lâche d'abord, puis le fichier part. Windows refuse de supprimer un fichier ouvert.
10. **Une panne du cœur ne tue pas l'enregistreur.** Chaque message est traité sous `catch_unwind`. Une panique donne `failed(unavailable)`, l'audio reste tel qu'un plantage le laisse, et la commande suivante est prise.

| Commande (`type`) | Depuis | Vers |
| --- | --- | --- |
| `start` (`tracks`, `offsetMinutes`) | `idle`, `failed` | `recording`, sans réunion. Sans clé : `failed(storage)`. Déjà actif : rien |
| `pause` | `recording` | `paused` |
| `resume` | `paused`, `interrupted` | `recording` |
| `stop` | un état actif | `idle`, audio fini, durée finale écrite |
| `cut` (`problem`) | un état actif | `interrupted` si du son a été entendu, sinon `failed(problem)` |
| `dismiss` | `failed` | `idle` |

L'événement `recorder` porte l'état entier : `state`, puis `meeting`, `startedAt` et `recordedMs` pour un état actif, et `level` (de 0 à 1) en `recording`. Il part à chaque changement, au premier son et toutes les 100 ms de son. Au premier son, il nomme la réunion avant que `changed` ne la montre : une app qui laisse la réunion en cours hors de sa liste ne la montre jamais, même une image. `problem` vaut `denied`, `busy`, `unavailable` ou `storage`.

La transcription reste en Kotlin jusqu'à T-59. L'app la confie par la modification `transcribed` (`meeting`, `transcript`, `minutes`). Elle ne crée pas de réunion, ne change pas sa durée, et garde le titre donné et un compte rendu déjà corrigé.

`core/tests/fixtures/bridge/recorder.json` porte un exemple de chaque commande, de `transcribed` et de chaque état. Les apps le liront avec T-58. Les modifications du cœur lui-même, `begin` et `lasted`, ne se lisent jamais depuis une app.

## Mesure

Mesuré le 18 novembre 2026 sur le portable de l'ADR 0005 (Intel i5-12450H), avec `cargo run --release --example recording`. Dix minutes de voix inventée passent par `Session`, par lectures de 100 ms :

- **Le micro** rend la main en 0,6 µs en moyenne, en moins de 0,1 ms au pire.
- **L'enregistreur** encode, scelle et écrit dix minutes en 7,5 s, 80 fois plus vite qu'elles ne s'entendent.

Mesuré le 20 novembre 2026 sur un Pixel 10 (Android 16), avec le même exemple compilé par `cargo ndk` :

- **Le micro** rend la main en 5,8 µs en moyenne, en 0,3 ms au pire.
- **L'enregistreur** passe dix minutes en 6,1 s, 99 fois plus vite qu'elles ne s'entendent.
- **Une heure de son** s'encode en 43 s et pèse 10,9 Mo sur le disque. Trente secondes de la 59ᵉ minute se relisent en 22 ms.

Puis dans l'app, en build de debug, trente minutes enregistrées écran allumé : 5,5 Mo sur le disque, soit 182 Ko par minute. La mémoire de l'app (PSS) reste entre 166 et 180 Mo du début à la fin. Le tas Java monte et descend avec le ramasse-miettes, entre 15 et 30 Mo. Le tas natif prend ses blocs dans les dix premières minutes, puis se stabilise :

| Minute | 0 | 5 | 10 | 15 | 20 | 25 | 30 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Tas natif (Mo) | 11,1 | 15,3 | 16,9 | 17,4 | 17,7 | 17,8 | 18,0 |

## Options écartées

| Option | Pourquoi pas |
| --- | --- |
| Un verrou autour de l'état, pris par le micro et par l'écran | Le micro attendrait l'écriture d'une seconde sur le disque |
| Une file sans borne | Un disque bloqué remplirait la mémoire, puis l'app serait tuée en perdant tout |
| Un verrou autour de la file | Le micro attendrait l'écran ou une suppression. Depuis Rust 1.72, la file s'emprunte à plusieurs fils sans verrou |
| Compter la durée dans l'enregistreur | Deux compteurs finissent par diverger : après une erreur du disque, la réunion annoncerait un son que l'audio n'a pas |
| Combler de silence le son perdu | Une réunion aurait un trou que personne n'a vu, et sa durée mentirait |
| Créer la réunion à `start` | Un micro refusé laisserait des réunions vides dans la liste |
| Lire le fuseau du système dans le cœur | Le même instant tomberait un autre jour selon l'appareil qui relit la réunion |

## Conséquences

**Ce qu'on gagne.** Un seul enregistreur pour toutes les apps, testé en Rust : pause, reprise, micro coupé ou refusé, disque qui refuse l'audio, arrêt brutal, file pleine, piste en avance, quatre fils à la fois. La durée affichée est toujours celle que l'audio peut rejouer.

**Ce qu'on paie.** Chaque lecture du micro est copiée une fois dans la file. Une app tuée perd au plus la seconde en cours, une coupure de courant les 10 dernières secondes (ADR 0005). La durée d'une réunion ainsi coupée est corrigée au lancement suivant, au prix d'un audio ouvert par réunion concernée.

**Ce qui reste ouvert.**

- **Une piste en retard.** Une trame part quand chaque piste en a une. Sur l'ordinateur, le son de l'ordinateur ne donne rien quand rien ne joue : l'enregistrement n'avance plus, puis s'interrompt au bout de 10 secondes. US-72 dira qui comble ce silence, l'app ou le cœur.
- **Un fil temps réel.** Chaque lecture traverse le pont dans un tampon neuf, que `hear` garde. Sur l'ordinateur, si le son arrive sur le fil temps réel du système (CoreAudio, WASAPI), un anneau réservé d'avance remplacera cette copie, si une mesure le demande.
