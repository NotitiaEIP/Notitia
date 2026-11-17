# ADR 0005 : l'audio d'une réunion, en Opus dans un fichier scellé

| | |
| --- | --- |
| Statut | Proposée le 17 novembre 2026, acceptée à la fusion de sa PR |
| Proposée par | Clément |
| Relue par | Alexandre pour le cœur, Amine pour l'ordinateur |
| Tâche d'origine | T-79 (#239), première étape de T-58 (#189) |

## Contexte

L'audio d'une réunion n'est jamais écrit : il reste en mémoire, et un plantage perd tout. La règle produit 5 demande un audio compressé, jamais de WAV, gardé 30 jours.

Le cœur tourne sur cinq systèmes, et personne dans l'équipe du cœur n'a de Mac. Sur l'ordinateur, une visio s'enregistre sur deux pistes, le micro et le son de l'ordinateur ([Windows et Linux](../architecture/windows-linux.md)).

## Décision

1. **Opus** (RFC 6716, sans redevance), en 16 kHz, une voie, par trames de 20 ms : le son que tous les micros donnent et que tous les moteurs prennent.
2. **Un débit constant de 24 kbit/s.** Chaque paquet fait 60 octets : 10,8 Mo par heure et par piste, contre 115 Mo en WAV.
3. **Le cœur encode et décode lui-même**, avec [`opus-rs`](https://crates.io/crates/opus-rs) 0.1.42, un portage de libopus en Rust pur. Aucun système ne prête son codec : le même son partout.
4. **Seul `core/src/audio/opus.rs` connaît cette bibliothèque.** Ce qui est gardé est de l'Opus standard : en changer, c'est changer ce fichier, sans toucher aux réunions déjà enregistrées.
5. **Une piste sur le téléphone, deux sur l'ordinateur** : la piste 0 est le micro, la piste 1 le son de l'ordinateur. Un encodeur par piste.
6. **Un fichier `<id>.audio` par réunion**, à côté de son `.notitia`, scellé seconde par seconde. T-80 (#240) l'écrit.
7. **Ogg seulement à l'export** (US-101) : aucun lecteur ne lit un fichier chiffré.

## La forme du fichier

Un en-tête en clair, puis une seconde par enregistrement, toutes de la même taille.

| Partie | Contenu |
| --- | --- |
| En-tête | Marque, version, nombre de pistes, taille d'un paquet, sel. La version fixe le reste : Opus, 16 kHz, 20 ms |
| Une seconde | Un nonce de 24 octets, puis scellés ensemble : le nombre de trames (50, sauf à la fin) et 50 paquets par piste. Enfin un sceau de 16 octets |

- **Chiffrement** : XChaCha20-Poly1305 (crate `chacha20poly1305`, Rust pur, MIT ou Apache 2.0). Une clé par fichier, tirée de la clé de l'app et du sel par HKDF.
- **Le nonce est tiré au hasard** à chaque seconde. Une seconde coupée par un plantage puis réécrite ne réutilise donc jamais un nonce.
- **L'en-tête et le numéro de la seconde sont scellés avec elle** : deux secondes échangées, ou une seconde d'un autre fichier, sont refusées.
- **Se déplacer** : la seconde `n` commence à l'octet `en-tête + n × taille`. Rien à parcourir, rien à déchiffrer avant.
- **Ajout seul.** Une fin coupée se voit à la taille du fichier, et se retire à la réouverture.
- **Taille** : 3 041 octets par seconde avec une piste, 6 041 avec deux.

## Options écartées

| Option | Pourquoi pas |
| --- | --- |
| libopus en C (`opus` 0.4.0 sur `opusic-sys`) | La référence. Demande `cmake` sur chaque machine, et un C qui ne compile pas pour l'iPhone, le Mac ou Windows ne se verrait que dans la CI. C'est le repli : même Opus, un fichier à changer |
| Les autres portages en Rust | `ruopus` et `unsafe-libopus` sont à l'arrêt, `rusty-opus` et `opus-pure` ont moins de quatre mois, `mousiki` n'est presque pas utilisé |
| Le codec de chaque système (MediaCodec, AudioToolbox) | Un code par système, un son différent sur chacun, et rien de commun sur Windows et Linux |
| FLAC | Sans perte, mais six fois plus lourd sur la voix de la preuve |
| Un débit variable | Plus léger dans les silences. Mais il faut parcourir le fichier pour s'y déplacer, et la taille de chaque seconde trahit qui parle et quand, même chiffrée |
| Opus en stéréo pour les deux pistes | Fait pour deux voies qui se ressemblent : il code d'abord ce qu'elles ont en commun |
| Deux fichiers, un par piste | Deux fichiers à garder d'accord après un plantage |
| Le numéro de la seconde comme nonce | Une seconde réécrite après un plantage serait chiffrée deux fois avec le même nonce |

## Résultat de la preuve

Mesuré le 17 novembre 2026 sur un portable (Intel i5-12450H). Tous les sons sont inventés : une voix dessinée par le code, et trois voix de synthèse (filtre `flite` de `ffmpeg`), hors du repo.

- **Licence.** BSD-3-Clause, la même que libopus, texte dans l'archive. Aucune dépendance.
- **Compilation.** `cargo check` passe pour Android (arm64 et x86_64), l'iPhone et son simulateur, le Mac, Windows et Linux. `./notitia check` construit les bibliothèques d'Android, le job `ios` celle de l'iPhone.
- **Standard.** `ffmpeg` relit avec libopus un fichier Ogg écrit par `cargo run --release --example recording -- temoin.ogg` : Opus, une voie, 60 secondes à l'échantillon près.
- **Taille.** Tous les paquets font 60 octets, sur 4 143 trames de voix comme sur 200 000 trames extrêmes (bruit à pleine échelle, signaux carrés, silence).
- **Solidité.** 800 000 paquets tirés au hasard ou abîmés, et ces 200 000 trames : aucune panique. Si une arrivait, `opus.rs` la rend comme un échec, et l'app ne tombe pas.
- **Une heure de son** : 10,8 Mo, encodée en 43 s, décodée en 1,7 s.

La même voix de 82,8 secondes, encodée à 24 kbit/s puis relue par libopus :

| Encodeur | Ressemblance | Écart spectral | Temps |
| --- | --- | --- | --- |
| `opus-rs`, complexité 5 (retenue) | 0,902 | 5,4 dB | 0,94 s |
| `opus-rs`, complexité 9 | 0,897 | 5,3 dB | 1,94 s |
| libopus, complexité 5 | 0,902 | 5,5 dB | 1,09 s |
| libopus, complexité 10 | 0,901 | 5,4 dB | 1,41 s |

À débit égal, `opus-rs` rend le même son que libopus. La complexité 5 demande deux fois moins de calcul que la 9, sans différence mesurable.

## Conséquences

**Ce qu'on gagne.** Un seul codec pour cinq systèmes, sans rien à installer. L'iPhone, le Mac et Windows se vérifient depuis Linux. Le fichier est simple : des secondes de taille fixe, où l'on se déplace par une multiplication.

**Ce qu'on paie.** Un silence pèse autant qu'une phrase. La bibliothèque n'a pas un an et n'est pas en version 1.0 : sa version est fixée, et la monter demande de refaire ces mesures.

**Ce qui nous protège.** L'Opus gardé est standard : libopus le relira toujours, et prendre sa place tient dans un fichier.

**Ce qui reste ouvert.**

- Le débit. Les trois quarts des paquets d'une voix de synthèse ne remplissent pas 24 kbit/s. T-59 essaiera 16 et 20 kbit/s sur le jeu d'évaluation de T-91 (#39). La taille d'un paquet est dans l'en-tête : la changer ne casse aucun fichier.
- Le temps d'encodage sur un téléphone, mesuré dans T-58.
- La capture en 16 kHz sur deux pistes, à prouver sur chaque ordinateur par T-05 (#22).
- Un vrai iPhone.
