# ADR 0011 : les fenêtres d'audio

| | |
| --- | --- |
| Statut | Proposée le 23 novembre 2026, acceptée à la fusion de sa PR |
| Modifiée | Le 24 novembre 2026 par T-94 (#260) : le traitement prend les fenêtres |
| Modifiée | Le 25 novembre 2026 par T-59 (#190) : le téléphone passe par le cœur, le Kotlin part |
| Proposée par | Clément |
| Relue par | Alexandre pour le cœur |
| Tâche d'origine | T-90 (#258) |

## Contexte

Un moteur de transcription ne prend pas une heure de son d'un coup : il prend des fenêtres. Jusqu'ici, Kotlin les coupait (`SpeechSegmenter`) :
- à 15 s fixes, quelle que soit la limite du moteur ;
- avec un bruit de fond appris sur les seules trames jugées silencieuses. Dans une salle bruyante, aucune ne l'était : plus de coupe sur les pauses, du silence envoyé (#155) ;
- en jetant tout un morceau dès qu'il contenait « sous-titrage » (#156).

Le traitement qui reprend (cœur de T-38) ne garde qu'un nombre, `transcribed_ms`. Reprendre doit redonner exactement les fenêtres qu'un découpage sans arrêt aurait données, que la réunion soit transcrite pendant ou après son enregistrement.

## Décision

Le découpage vit dans le cœur (`core/src/speech/`), le même pour toutes les apps. Il lit l'audio scellé, rend des fenêtres, et change ce que le moteur a entendu en lignes de transcription.

1. **La parole et le bruit** (`voice.rs`) :
   - le bruit de la salle est le 10ᵉ centile de l'énergie des trames de 20 ms des 5 dernières secondes, la trame jugée comprise ;
   - une trame est de la parole si son énergie dépasse à la fois un plancher (RMS 300, −41 dBFS) et 2,5 fois le bruit en amplitude, soit +8 dB.

   Le centile apprend de toutes les trames. Les creux entre deux syllabes et les pauses tombent au niveau du bruit : un bruit fort dès la première seconde est donc appris comme du bruit (#155). Tout se calcule en entiers, sur tous les systèmes. Un son parfaitement constant, même fort, est du bruit : un ventilateur n'est pas une voix.

2. **Les coupes** (`windows.rs`) :

   | Règle | Quand | Le bord | Le son envoyé |
   | --- | --- | --- | --- |
   | Début | Après une pause | | Commence 300 ms avant la première parole, jamais avant le bord |
   | Pause longue | 2 s sans parole | Dans la pause | Jusqu'à 300 ms après la dernière parole |
   | Fenêtre pleine | La longueur visée est atteinte | Dans la dernière pause de 320 ms ou plus, 160 ms avant sa fin | Idem |
   | Coupe forcée | Aucune pause de 320 ms | Juste après la dernière trame de parole | Toute la fenêtre. La suivante reprend 1 s plus tôt, un quart de la longueur visée au plus |
   | Fin | L'audio est fini | La fin | Jusqu'à 300 ms après la dernière parole |
   | Trop peu | Moins de 300 ms de parole ajoutée | Le bord avance | Rien. Si c'était une coupe forcée, la fenêtre suivante ne compte pas son recouvrement comme déjà entendu |

   Une pause de 320 ms ne tombe jamais au milieu d'un mot. Le silence n'est jamais envoyé : au plus 300 ms au bord d'une fenêtre, et moins de 2 s à l'intérieur. Le temps d'une fenêtre se compte depuis le début de la réunion, silence écarté compris.

3. **Une fenêtre vise 30 s au plus, même si le moteur en prend 10 min** (`min(longest_ms, 30 s)`). `longest_ms` est une limite, pas une cible :
   - une question de l'utilisateur passe devant la transcription entre deux fenêtres. À dix fois le temps réel, elle attend 3 s au plus, contre 60 s derrière une fenêtre de 10 min ;
   - une app tuée ou un appel raté ne coûtent qu'une fenêtre ;
   - une fenêtre pèse 1 Mo de son au lieu de 19 Mo, que le pont copie encore ;
   - le texte arrive en direct au moins toutes les 30 s ;
   - Whisper est entraîné sur 30 s, et la fin du texte précédent (`Hint.previous`) porte la suite.

4. **Un bord ne porte aucun état.** Le jugement d'une trame ne dépend que des 5 s qui la précèdent. La coupe ne dépend que des jugements depuis le début de la fenêtre. Reprendre au bord, c'est relire les 6 s d'avant : chaque trame reçoit le même jugement, et chaque fenêtre est la même, au son près.

   Le bord dit seul s'il coupait une parole :
   - une coupe forcée le pose juste après une trame de parole ;
   - une coupe sur une pause le pose après 160 ms de silence.

   `transcribed_ms` reste un simple nombre.

5. **L'audio se lit par secondes entières alignées.** Le décodeur Opus garde un état : un passage décodé depuis un autre point diffère de quelques unités, assez pour qu'une trame à la limite change de côté. Chaque seconde est donc décodée depuis son propre début, lancée sur les 80 ms d'avant : un échantillon a une seule valeur, d'où qu'on lise. Un test le prouve sur un vrai audio scellé, et il échoue si l'on relit d'un seul tenant.

6. **Le texte** (`text.rs`, `lines`) :
   - **Une phrase inventée** par Whisper sur du silence est retirée seulement si elle est entière l'une d'elles : « Sous-titrage ST' 501 », « Merci d'avoir regardé cette vidéo », un générique d'Amara.org… Une vraie phrase qui parle de sous-titrage est gardée (#156).
   - **À une coupe forcée**, le premier segment entendu dans le recouvrement perd ses premiers mots s'ils sont les derniers de la ligne d'avant : six mots au plus, sans tenir compte des majuscules ni de la ponctuation. Jamais après une pause : « Merci. » puis « Merci beaucoup. » garde ses deux mots.
   - **Une ligne est datée** depuis le début de la réunion, jamais avant le bord de la fenêtre précédente.

7. **Les pistes sont mélangées comme à l'écoute** (`audio::mix`, la même fonction que `Session::sound`) : la voix de l'ordinateur seule donne aussi ses fenêtres.

### Ce qui change par rapport à Kotlin

| Kotlin | Le cœur | Pourquoi |
| --- | --- | --- |
| 15 s fixes | La limite du moteur, 30 s au plus | Un moteur sans limite à 30 s, voir le point 3 |
| Coupe à la première pause de 700 ms | Fenêtres remplies jusqu'à 30 s, coupées à la dernière pause | Plus de contexte pour le moteur, moins d'appels |
| Bruit appris sur les trames silencieuses | Centile de toutes les trames | #155 |
| Un morceau jeté s'il contient « sous-titr » | La seule phrase inventée | #156 |
| Aucun recouvrement | 1 s, et les mots en double retirés | Un mot coupé en deux est entendu entier |

Le Kotlin reste jusqu'à T-59, qui le supprime. Fait par T-59 (#190) : `SpeechSegmenter` et ses tests sont partis.

## Options écartées

| Option | Pourquoi pas |
| --- | --- |
| Le lissage exponentiel du bruit, comme Kotlin | Sa mémoire est infinie : une reprise ne retrouve jamais exactement le même seuil |
| Garder le bruit appris dans la réunion | Un champ de format pour toujours (règle 7), pour un détail du découpage |
| Un bruit calculé sur toute la réunion | Il change pendant l'enregistrement : la transcription en direct couperait autrement qu'après coup |
| Des fenêtres de 10 min pour un moteur qui les prend | Voir le point 3 |
| Mettre bout à bout des morceaux de parole séparés par du silence, comme faster-whisper | Chaque segment entendu serait à redater morceau par morceau |
| Retirer les doublons dans le traitement (H) | H tient les fils et l'ordre. Le texte se teste mieux sans fil ni moteur |
| La crate `regex` pour les phrases inventées | Une dépendance pour quelques comparaisons de texte |
| Une détection de la voix par un modèle (Silero) dès maintenant | Un modèle à livrer par les stores, pour un gain à mesurer d'abord en salle réelle |

## Résultat

Mesuré le 23 novembre 2026 sur le portable de l'ADR 0005 (Intel i5-12450H), en release, avec `cargo run --release --example windows`. L'heure est faite de trois minutes inventées répétées vingt fois : des phrases et des pauses, quelqu'un qui parle 45 s sans s'arrêter, 20 s de silence, une salle bruyante. Un moteur de 30 s :
- **Une heure se découpe en 1,6 s**, 2 200 fois plus vite qu'elle ne s'entend. Le décodage d'Opus en prend presque tout.
- **140 fenêtres, dont la plus longue fait 30,0 s.** 20 coupes forcées, une par monologue.
- **47 minutes sur 60 sont envoyées** : le silence est écarté.
- **Reprendre à la 59ᵉ minute** donne la fenêtre suivante en 22 ms.

Mesuré le même jour sur le Pixel 10 (Android 16) de l'ADR 0006, avec le même exemple compilé par `cargo ndk` :
- **Une heure se découpe en 1,7 s**, 2 070 fois plus vite qu'elle ne s'entend.
- **Les fenêtres sont les mêmes que sur le portable** : 140, dont 20 coupes forcées, 47,3 minutes envoyées, 30,0 s pour la plus longue.
- **Reprendre à la 59ᵉ minute** donne la fenêtre suivante en 23 ms.

Les tests le prouvent aussi :
- reprendre à chaque bord redonne les mêmes fenêtres, au son près, sur un vrai audio scellé comme sur des sons tirés au hasard (`NOTITIA_CASES`) ;
- une réunion découpée pendant son enregistrement donne les fenêtres trouvées après coup.

## Conséquences

**Ce qu'on gagne.**
- Toutes les apps, l'ordinateur compris, découpent de la même façon.
- #155 et #156 sont corrigés dans le cœur. Ils se ferment avec T-59 (#190), qui fait passer le téléphone par lui.
- Le traitement (H) n'a qu'à garder `until_ms` : reprendre ne demande rien d'autre.

**Ce qu'on paie.**
- Chaque seconde est précédée de 80 ms décodées pour lancer le décodeur, soit 8 % de décodage en plus, et la seconde d'avant est déchiffrée une deuxième fois.
- Une musique ou une télé, jugées parole, sont coupées de force toutes les 30 s.
- Le retrait des mots en double compare du texte : un mot mal entendu à la coupe peut rester deux fois.

**Ce qui reste ouvert.**
- **Le réglage en salle réelle.** Les valeurs (5 s, 10ᵉ centile, +8 dB) sont vérifiées sur des sons inventés. #155 demande un essai au téléphone en open space, avec T-59.
- **Une détection de la voix par un modèle** remplacerait `voice.rs` sans toucher aux coupes.
- **Les dates par mot.** Si le moteur de T-06 les donne, le retrait des doublons se fera au temps et non plus au texte.
- **Une longueur préférée par le moteur** (`preferredMs`, facultatif dans l'offre), si le moteur de T-06 est meilleur sur des fenêtres plus longues.
- **Le branchement dans le traitement (H).** Fait par T-94 (ADR 0012) : `Windows` est gardé entre deux `Wait` pendant l'enregistrement, et refait depuis le dernier bord pour une autre réunion ou un autre moteur.
