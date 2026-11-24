# ADR 0010 : les moteurs du cœur

| | |
| --- | --- |
| Statut | Proposée le 21 novembre 2026, acceptée à la fusion de sa PR |
| Modifiée | Le 24 novembre 2026 par T-94 (#260) : le rappel `run` depuis un fil du cœur est prouvé sur Android, la réunion est transcrite pendant son enregistrement |
| Modifiée | Le 25 novembre 2026 par T-95 (#262) : la marge sous la fenêtre d'un moteur de texte est fixée, et une offre laisse au moins 1 024 jetons à l'invite |
| Proposée par | Clément |
| Relue par | Alexandre pour le cœur, Lucas pour l'iPhone |
| Tâche d'origine | T-89 (#256) |

## Contexte

Le cœur va transcrire, écrire le compte rendu et chercher : ce sont les fiches F, G et H, puis T-59. Aujourd'hui, il ne connaît aucun moteur, et Kotlin appelle seul la gateway. Plusieurs moteurs se brancheront au fil de l'année :
- la gateway, en debug ;
- les moteurs du téléphone, Gemini Nano et SpeechAnalyzer d'Apple ;
- le moteur que T-06 (#23) choisira pour la bêta ;
- nos modèles maison (E9).

Les uns tournent dans l'app, les autres seront compilés dans le cœur. Le cœur ne doit dépendre d'aucun.

## Décision

**Trois interfaces, et rien sur la façon dont tourne un moteur** (`core/src/engine.rs`) :

| Interface | Ce qui entre | Ce qui sort |
| --- | --- | --- |
| `SpeechEngine` | Le son d'une fenêtre (16 kHz, une voie), la langue, la fin du texte précédent | Des segments datés depuis le début de la fenêtre |
| `TextEngine` | Une consigne et un texte | Le texte écrit |
| `EmbeddingEngine` | Des textes | Un vecteur par texte, tous de la même dimension |

1. **Chaque moteur dit son nom et s'il est distant (`remote`).** Un moteur distant envoie ce qu'il reçoit hors de l'appareil. `remote` n'a pas de valeur par défaut : un moteur qui l'oublie est refusé. Le nom change avec le modèle (`gemini-nano-2`) : des vecteurs faits sous un autre nom sont refaits, jamais comparés à ceux d'un autre modèle.
2. **Chaque moteur dit ses limites.**
   - Un moteur de transcription donne le son le plus long qu'il prend en un appel (`longest_ms`). Whisper prend 30 s, un autre modèle davantage. Le découpage de F suivra ce chiffre, et non une constante.
   - Un moteur de texte donne sa fenêtre en jetons et une estimation prudente du nombre de jetons d'un texte, pour que G sache couper.
3. **Tout appel peut être arrêté.** Le `Stop` est lu par le moteur aussi souvent qu'il le peut. Le cœur le lève quand une réunion est supprimée ou que l'app se ferme.
4. **Trois erreurs, et aucune ne porte de contenu** :
   - `Unavailable` : pas maintenant, le travail attend ;
   - `Failed` : cette entrée a raté, le travail passe à la suite ;
   - `Stopped` : l'appel a été arrêté.
5. **Le catalogue choisit, pour chaque sorte, le premier moteur.** Les moteurs de l'appareil passent avant les moteurs distants, puis l'ordre donné est gardé. Le cœur tient cette part de la règle produit 1, quel que soit l'ordre que l'app donne. Il ne peut pas savoir si l'utilisateur a permis un moteur distant : en offrir un, c'est pour l'app affirmer qu'il l'est (la gateway en debug, puis un réglage explicite). Le catalogue ne change jamais une fois fait : une nouvelle offre en fait un autre, et un appel en cours garde le moteur avec lequel il a commencé. Le verrou qui le garde n'est jamais tenu pendant qu'on prévient l'app : un écouteur peut relire les moteurs depuis n'importe quel fil sans attendre. L'événement `engines` donne chaque sorte, le moteur choisi en premier. Il part à l'ouverture, juste après `listed`, puis à chaque offre.
6. **L'app prête ses moteurs par une seule fonction du pont** (`core/src/engine/lent.rs`), `offer(offered, engines)` :
   - **L'offre** est un tableau JSON. Elle remplace la précédente, et une offre vide reprend tous les moteurs.
   - **L'objet `engines`** n'a qu'une méthode, `run(engine, request, sound)`. La demande et la réponse sont du JSON, et le son est en octets. Ajouter un moteur ne touche donc ni `RustCore.kt` ni `RustCore.swift`.
   - **Le fichier témoin** `core/tests/fixtures/bridge/engines.json` garde un exemple de chaque partie.
7. **Une offre que le cœur devrait corriger est refusée en entier** (`Invalid`). C'est le cas d'un nom vide, trop long ou en double, d'un `longestMs` hors de 1 s à 10 min, ou d'une réponse de texte qui ne tient pas dans sa fenêtre. C'est une faute de l'app : les tests la montrent tout de suite, alors qu'une correction silencieuse cacherait un moteur changé.
8. **Ce que vaut la réponse d'un moteur prêté :**
   - **Une réponse illisible, ou d'une autre sorte que la demande**, donne `Failed`.
   - **Des segments qui sortent de la fenêtre** y sont ramenés et rangés : un Whisper qui déborde de quelques millisecondes ne perd aucun texte.
   - **Des vecteurs qui ne correspondent pas aux textes** donnent `Failed`. C'est le cas d'un vecteur en trop ou en moins, de dimensions différentes ou d'une valeur infinie : chacun serait comparé au mauvais passage.
   - **Un son vide, ou plus long que ce que le moteur a annoncé,** n'est jamais envoyé.
9. **`run` ne lève jamais d'exception, et un échec revient comme une réponse.** UniFFI 0.32.2 change une exception étrangère, levée dans un rappel qui ne rend pas un `Result`, en panique du cœur (`uniffi_core`, `ffi_converter_traits.rs:393`). Sur l'iPhone, l'exception terminerait l'app en traversant Swift. Les deux classes qui tiennent le cœur (`RustCore.kt`, `RustCore.swift`) passent donc chaque moteur par `neverThrowing` (`NotitiaCore.kt`) : une exception y devient la réponse `failed`. Un moteur de plus n'a pas à y penser.
10. **Le cœur n'a toujours aucun code réseau.** La gateway reste un moteur que Kotlin prête, en debug. `cargo deny` et `clippy.toml` (ADR 0009) ne changent pas.

Un moteur compilé dans le cœur (whisper.cpp, llama.cpp, un modèle de l'équipe) implémentera les mêmes traits, dans sa propre crate. Il s'ajoutera au catalogue sans toucher au découpage (F), au compte rendu (G), au traitement (H), au pont ni aux écrans.

### Les écarts avec la fiche du 17 novembre

| Fiche | Ici | Pourquoi |
| --- | --- | --- |
| `lend(engines)` | `offer(offered, engines)` | `lend` désigne déjà les réunions d'exemple prêtées à la liste (`Change::Lend`) |
| `offered()`, appelé par le cœur | L'offre passée en argument | Un seul rappel, et ce rappel ne sert qu'à faire tourner un moteur. L'offre est une donnée, vérifiée comme une demande |
| Un rappel `heard` pour le texte écrit au fil de l'eau | Un `Stop` passé à toutes les sortes | L'arrêt sert aux trois sortes, et un moteur prêté ne sait pas envoyer son texte au fil de l'eau. Voir « Ce qui reste ouvert » |
| Aucune limite de son | `longest_ms` | Un modèle autre que Whisper n'a pas de limite à 30 s |
| Le premier moteur disponible | Les moteurs de l'appareil d'abord | Règle produit 1 : la gateway est un recours, jamais le premier choix |

## Options écartées

| Option | Pourquoi pas |
| --- | --- |
| Une fonction du pont par sorte de moteur (`transcribe`, `complete`, `embed`) | Chaque sorte nouvelle (locuteurs, ordre du jour) toucherait `RustCore.kt` et `RustCore.swift`, et Swift ne se compile qu'en CI |
| Un champ « locuteur » dans les segments | Un locuteur repéré dans une fenêtre de 30 s ne veut rien dire dans la suivante. Séparer les voix sera un moteur à part (E2). Un champ JSON facultatif ajouté plus tard ne casse rien |
| Le son en `List<Short>` | UniFFI emballe chaque échantillon en objet côté Kotlin. Des octets ne font qu'une copie |
| Corriger une offre fausse | Voir le point 7 |
| Le choix du moteur par l'app | Le cœur tiendrait alors la règle produit 1 sur la foi de l'app |

## Résultat

Mesuré le 21 novembre 2026 sur un portable (Intel i5-12450H), en release :
- **Une fenêtre de 30 s prêtée** coûte 0,31 ms côté cœur. Cela comprend la demande, le son en octets (960 Ko) et la lecture d'une réponse de 50 segments. Un moteur met des secondes à transcrire cette fenêtre : la traversée ne compte pas.
- **2 000 offres et 2 000 réponses abîmées** donnent un refus ou un échec, jamais une panique. `NOTITIA_CASES` en fait passer autant que voulu.
- **Une offre traverse depuis Kotlin jusqu'au vrai cœur**, et l'événement revient tel que le fichier témoin l'écrit (`RustEnginesTest`, sur l'hôte).

## Conséquences

**Ce qu'on gagne.**
- Le modèle de T-06, Gemini Nano et nos modèles maison se branchent sans toucher à la chaîne de traitement.
- La règle produit 1 est tenue par le cœur, et l'événement `engines` permet à l'écran de dire quel moteur sert.

**Ce qu'on paie.**
- Un appel prêté ne se coupe pas en plein milieu : le cœur jette la réponse d'un appel arrêté entre-temps. Une demande à la gateway dure 60 s au plus.
- Le compte des jetons d'un moteur prêté est une moyenne : 3 octets par jeton. Elle surestime un texte en français courant, mais un texte plein de chiffres ou de mots rares peut tenir plus de jetons que compté.

**Ce qui reste ouvert.**
- **Le rappel `run` depuis un fil du cœur.** Prouvé sur Android par T-94 : JNA attache le fil du traitement à la JVM le temps de l'appel (`RustEnginesTest`). Swift le reçoit hors du fil principal, ce que rien ne prouve avant un Mac.
- **Kotlin relira `engines.json`** avec l'adaptateur de la gateway, dans T-59.
- **La marge sous la fenêtre d'un moteur de texte.** Fixée par T-95 (ADR 0013) : une invite remplit au plus 85 % de ce que la réponse laisse, et un moteur de texte offert laisse au moins 1 024 jetons à l'invite. Si le modèle de T-06 compte ses jetons très loin de la moyenne, une demande `count` exacte s'ajoutera à `run`.
- **Le texte écrit au fil de l'eau**, pour la réponse à une question (J), si les mesures sur le téléphone le demandent.
- **Le choix du moteur par l'utilisateur** : une demande `prefer`, et un écran de réglages.
- **L'interdiction de tout moteur distant pour une organisation de santé** (T-72, E11).
- **Les moteurs compilés dans le cœur**, et le choix de la bêta : T-06 (#23).
- **Transcrire pendant ou après la réunion.** Le traitement transcrit pendant l'enregistrement (ADR 0012). Un réglage « après la réunion » attend le moteur que T-06 retiendra.
