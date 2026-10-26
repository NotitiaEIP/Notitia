# DESIGN.md

La charte de l'interface de Notitia : ce qu'on fait par défaut, avec des chiffres. Elle guide sans bloquer : on peut s'en écarter quand un écran le justifie, en le disant en une phrase dans la description de la PR (« bouton de 60 dp ici, on le touche en marchant »).

Elle vaut pour l'app Android et iPhone, dans `mobile/`. Les couleurs, la police et la façon d'écrire sont celles de toutes les apps Notitia. L'app Mac suit pour l'instant ses propres règles, dans `macos/docs/DESIGN.md`.

Dans le code, chaque valeur vient du thème `NotitiaTheme`, dans `mobile/shared/src/commonMain/kotlin/<package>/ui/theme/`. Si une valeur manque, on l'ajoute au thème dans la même PR, plutôt que de l'écrire en dur dans l'écran.

| En bref | |
| --- | --- |
| Police | Schibsted Grotesk, en 17 pour lire |
| Couleur | Des neutres, et un violet tiré du logo, rare |
| Thème | Clair ou sombre, selon le réglage du téléphone |
| Espaces | Multiples de 4 : 8, 12, 16, 24, 32 |
| Formes | Ce qui se touche est rond, ce qui se lit a des coins de 16 |
| Accessibilité | Cibles de 48 dp, contrastes AA partout |

## Six principes

Quand une règle chiffrée ne couvre pas un cas, on revient à ces six phrases.

1. **Le contenu d'abord.** L'interface s'efface derrière le compte rendu : un fond calme, du texte bien composé, un accent quand il faut agir. Un écran de compte rendu, c'est 90 % de texte.
2. **Un geste pour enregistrer.** Le bouton Enregistrer est à un appui depuis l'accueil, et hors de l'app : widget, tuile Android, écran verrouillé.
3. **Toujours savoir où l'on est.** Trois onglets, un titre par écran, le geste retour du système toujours actif, trois niveaux au plus.
4. **La couleur a un sens.** Violet : agir ou sélectionné. Rouge : enregistrement ou suppression. Une couleur par locuteur. Si on retire le violet d'un écran, on doit perdre une information.
5. **Accessible par défaut.** Un écran qui ne marche pas avec TalkBack ou VoiceOver n'est pas fini.
6. **La confidentialité se voit.** On montre que tout reste sur le téléphone (le badge « Sur l'appareil » pendant l'enregistrement), et on demande le consentement avant chaque enregistrement, comme sur Mac.

## Ce qu'on reprend des meilleures apps

Leur navigation est banale, et c'est voulu : personne ne redessine un panneau stop. Leur personnalité tient à la police, à une couleur rare et à un ou deux détails signature.

| Source | Ce qu'on reprend |
| --- | --- |
| Apple, Human Interface Guidelines | Texte qui suit le réglage de taille, grands titres, gestes du système jamais détournés. Nos cibles de 48 dp couvrent ses 44 pt |
| Material 3 | La base Android, habillée de nos couleurs, police et formes |
| Revolut | L'information clé en très grand en haut : le minuteur, le nombre d'actions du compte rendu |
| Trade Republic | Une interface presque noir et blanc : le violet n'apparaît que pour agir ou montrer une sélection |
| Discord | Une couleur par personne, toujours à côté de son nom : nos locuteurs |
| Netflix | Le fond sombre qui met le contenu en avant |
| Uber Eats | L'action principale collée en bas, pleine largeur : « Partager le compte rendu » |
| Spotify | Le mini-lecteur visible partout : notre mini-barre d'enregistrement |
| L'app Mac de Lucas | Consentement avant d'enregistrer, transcription horodatée, vouvoiement, pas d'emoji, l'échelle d'espacement |

## Couleurs

Notitia n'a pas d'images : la couleur porte l'identité, et elle reste rare pour garder sa force. Sur un écran : 90 % de neutres, 8 % de violet (actions, sélection), 2 % de dégradé (une seule fois).

L'app suit le réglage clair ou sombre du téléphone. Les deux thèmes sont dessinés, et le sombre sert de vitrine (captures, fiche du store). Sur Android, les couleurs dynamiques (Material You) sont coupées : Notitia garde son violet quel que soit le fond d'écran.

| Jeton | Rôle | Sombre | Clair | Contraste sombre / clair |
| --- | --- | --- | --- | --- |
| `background` | Fond des écrans | `#0B0A0F` | `#FFFFFF` | |
| `surface` | Cartes, champs, groupes | `#16151C` | `#F5F4F8` | |
| `surfaceRaised` | Feuilles, menus, mini-barre | `#201F28` | `#FFFFFF` + ombre | |
| `divider` | Séparateurs | `#2C2B36` | `#E5E3EA` | |
| `outline` | Contour des champs | `#6F6D7A` | `#8D8B98` | 3,9 / 3,3 |
| `text` | Texte principal | `#F4F3F7` | `#121117` | 17,9 / 18,8 |
| `textSecondary` | Métadonnées, aide | `#A3A1AD` | `#5E5C69` | 7,8 / 6,5 |
| `textTertiary` | Groupes, horodatages | `#8C8A97` | `#6F6D7A` | 5,8 / 5,1 |
| `accent` | Fond des boutons principaux | `#882DCD` | `#882DCD` | texte blanc 6,4 |
| `accentContent` | Liens, icônes actives, onglet actif | `#CB95F3` | `#882DCD` | 8,6 / 6,4 |
| `accentSoft` | Sélection, puces, badges | violet à 22 % | violet à 10 % | texte 7,4 / 5,4 |
| `focus` | Contour d'un champ actif | `#B66EED` | `#882DCD` | |
| `red` | Enregistrement, suppression | `#FF5A52` | `#BF2D2A` | 6,4 / 5,9 |
| `green` | Succès, action faite | `#3DD68C` | `#15724A` | 10,5 / 5,9 |
| `amber` | Attention (batterie, espace disque) | `#FFB224` | `#A35200` | 10,9 / 5,6 |

Les contrastes sont mesurés contre le fond du thème. `redSoft` et `greenSoft` (rouge à 16 % et 10 %, vert à 14 % et 8 %) servent de fond aux badges de ces couleurs.

Le violet vient du logo (`#7929B6` au milieu du N), éclairci pour rester lisible. Sa teinte, plus rose que le violet habituel des apps d'IA (`#7C3AED`), le rend reconnaissable.

### Le dégradé du logo, une seule fois par écran

- Dégradé du logo : `#1592D8`, `#4B3BC6`, `#8E2BB0`, `#E0207F`, `#F6475F`. Pour l'icône de l'app, l'écran de démarrage et l'onde de la voix pendant l'enregistrement.
- Dégradé des boutons, sa partie foncée : `#4B3BC6`, `#8E2BB0`, `#C21A7A`. Pour le bouton Enregistrer, qui garde un texte blanc lisible (5,6:1 au pire).
- Jamais en fond d'écran, derrière du texte long, ni sur plus d'un élément du même écran.

### Les locuteurs

Six couleurs, données dans l'ordre où les personnes prennent la parole, toujours avec le nom ou les initiales. Au-delà de six, on recommence la liste : les initiales font la différence.

| | 1 | 2 | 3 | 4 | 5 | 6 | Texte posé dessus |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Sombre | `#5AA9FF` | `#2CC7B5` | `#F5B83D` | `#FF7AB6` | `#9BD84A` | `#FF8A4C` | `#0B0A0F` |
| Clair | `#1F6FD1` | `#0E7C70` | `#9A6400` | `#C2266F` | `#4D7A12` | `#B4501A` | `#FFFFFF` |

Elles restent lisibles en texte : 7:1 au moins en sombre, 4,5:1 au moins en clair.

## Police

Schibsted Grotesk, dessinée en 2023 pour un grand groupe de presse norvégien : nette, un peu serrée, elle fait sérieux et journal. Licence SIL Open Font License 1.1. Une seule famille sur toutes les apps, embarquée dans l'app : trois fichiers (400, 600, 700) dans `composeResources/font/`, et sa licence dans `composeResources/files/licenses/`.

- Ses chiffres sont proportionnels par défaut. Le minuteur et les horodatages utilisent les chiffres à chasse fixe (`fontFeatureSettings = "tnum"`), pour qu'ils ne tremblent pas.
- Elle n'a pas l'espace fine insécable (U+202F). On utilise l'espace insécable (U+00A0), écrite `&#160;` dans `strings.xml`, avant « : ; ! ? » et à l'intérieur des guillemets « ».
- Dans les textes de l'app, l'apostrophe typographique : « l’audio », pas « l'audio ».

Les tailles sont en sp sur Android et en pt sur iPhone : elles suivent le réglage du téléphone. Trois graisses seulement : 400 pour lire, 600 pour structurer, 700 pour les grands titres.

| Jeton | Taille / interligne | Graisse | Pour |
| --- | --- | --- | --- |
| `display` | 34 / 40 | 700 | Titre des onglets (« Réunions ») |
| `title1` | 28 / 34 | 700 | Titre d'un compte rendu |
| `title2` | 22 / 28 | 600 | Grandes sections (« Décisions ») |
| `title3` | 18 / 24 | 600 | Titre de carte ou de feuille |
| `body` | 17 / 26 | 400 | Lecture : compte rendu, transcription |
| `bodyStrong` | 17 / 26 | 600 | Nom du locuteur, mot clé |
| `callout` | 15 / 22 | 400 | Texte secondaire des listes |
| `label` | 16 / 20 | 600 | Boutons |
| `labelSmall` | 14 / 18 | 600 | Boutons compacts, puces |
| `caption` | 13 / 18 | 400 | Date, durée, participants |
| `badge` | 13 / 16 | 600 | Texte des badges |
| `tab` | 13 / 16 | 600 | Libellés de la barre d'onglets |
| `timer` | 56 / 60 | 600 | Minuteur, chiffres fixes |
| `timestamp` | 13 / 18 | 400 | Horodatages, chiffres fixes |

- Texte aligné à gauche, jamais justifié. Rien sous 13.
- `display` et `title1` resserrent un peu les lettres (-0,01 em).
- Majuscule au premier mot seulement : « Partager le compte rendu ». Pas de phrase en capitales.

## Espaces, formes, icônes

Tout est multiple de 4. Les noms viennent de l'app Mac.

| Jeton | Valeur | Pour |
| --- | --- | --- |
| `xxs` | 2 | Titre et sous-titre serrés |
| `xs` | 4 | Icône et pastille |
| `s` | 8 | Éléments liés, icône et texte |
| `m` | 12 | Intérieur des éléments compacts |
| `l` | 16 | Marges de l'écran, intérieur des cartes |
| `xl` | 24 | Entre groupes, côtés des grands boutons |
| `xxl` | 32 | Entre sections |
| `xxxl` | 48 | Haut d'écran, états vides |

Ce qui se touche est rond, ce qui se lit a des coins :

| Forme | Rayon | Pour |
| --- | --- | --- |
| `badge` | 8 | Badges, miniatures |
| `field` | 12 | Champs de saisie |
| `card` | 16 | Cartes |
| `sheet` | 28 en haut | Feuilles du bas, dialogues |
| `full` | rond | Boutons, puces, avatars, mini-barre |

**Élévation.** En sombre, pas d'ombre : une surface plus haute est plus claire. En clair, une ombre douce, seulement pour ce qui flotte.

**Icônes.** Material Symbols Rounded (licence Apache 2.0), arrondies comme nos boutons. 24 dp, 20 dp dans un bouton. Contour quand inactif, plein quand actif, ou plus épais quand l'icône n'a pas de version pleine. Pas d'emoji dans l'interface. Les SF Symbols d'Apple sont réservés aux plateformes Apple : seulement dans les écrans écrits en Swift.

| Concept | Icône |
| --- | --- |
| Réunions | `graphic_eq` |
| Actions | `task_alt` |
| Recherche | `search` |
| Enregistrer | `mic` |
| Marquer un moment | `bookmark` |
| Partager | `share` sur Android, `ios_share` sur iPhone |
| Sur l'appareil | `lock` |
| Réglages | `settings` |

## Composants

Chaque composant n'existe qu'une fois dans le code (`NotitiaButton`, `RecordButton`, `MeetingCard`, `SpeakerChip`...), et les écrans l'utilisent tel quel.

### Boutons

| Variante | Hauteur | Côtés | Texte | Quand |
| --- | --- | --- | --- | --- |
| Principal | 52 | 24 | `label` | Une seule action principale par écran, en bas, pleine largeur. Fond `accent` |
| Secondaire | 52 | 24 | `label` | L'alternative : « Plus tard », « Annuler » |
| Compact | 40, cible 48 | 12 | `labelSmall` | Actions dans une carte ou une liste. Fond `accentSoft` |
| Texte | 40, cible 48 | 12 | `labelSmall` | Action discrète : « Tout voir » |
| Destructif | 52 ou 40 | 24 ou 12 | rouge | Supprimer, avec « Annuler » juste après |
| Icône | 48 × 48 | | icône 24 | Barre du haut, toujours avec un libellé pour les lecteurs d'écran |

- Un bouton se nomme avec un verbe qui dit ce qui se passe : « Partager le compte rendu », jamais « OK » ni « Valider ».
- Pressé : 97 % de sa taille. Désactivé : 38 % d'opacité. En chargement : un indicateur à la place de l'icône, le libellé reste.

### Le bouton Enregistrer, la signature

72 dp, rond, dégradé des boutons, micro blanc de 32. Pendant l'enregistrement, il devient « Arrêter », une pastille rouge pulse lentement (orange en pause) et l'onde prend les couleurs du logo. Avant de démarrer : une feuille de consentement, comme sur Mac.

### Cartes, badges, champs

- **Carte** : fond `surface`, coins de 16, marge intérieure de 16. Toute la carte se touche.
- **Badge** : 24 de haut, coins de 8, texte 13 en 600, icône de 16. Il dit l'état en mots, pas seulement en couleur (« Compte rendu prêt », « Transcription 64 % »).
- **Champ** : 52 de haut, coins de 12. Le libellé est au-dessus, jamais remplacé par le texte d'exemple. Actif : contour `focus` de 2 dp. Une erreur dit ce qui ne va pas et comment corriger, avec une icône.

### Barre d'onglets et mini-barre

- **Barre d'onglets** : 64 dp, trois onglets, libellé toujours visible. L'onglet actif a une icône plus épaisse (ces trois icônes n'ont pas de version pleine), sur une pilule `accentSoft`, en `accentContent`. Elles sont redessinées en code d'après les Material Symbols, pour que leurs traits bougent.
- **Feuille d'enregistrement**, comme le lecteur de Spotify : deux positions, plein écran ou mini-barre de 64 dp, ronde, fond `surfaceRaised`, posée au-dessus de la barre d'onglets sur tous les onglets. La mini-barre montre la pastille, le minuteur et un bouton Arrêter rouge ; la toucher rouvre la feuille. On passe de l'une à l'autre en glissant n'importe où sur la feuille : elle suit le doigt, part avec la vitesse du lancer et se cale sur la position la plus proche, sans rebond, pendant que la barre d'onglets descend. Le chevron et le retour du système la réduisent. Glisser ne ferme ni n'arrête jamais l'enregistrement : seul Arrêter le fait. La poignée en haut porte les actions « Agrandir » et « Réduire » pour les lecteurs d'écran. « Réduire les animations » : on passe d'une position à l'autre sans mouvement.

L'effet de sélection d'un onglet. Le contenu change tout de suite : seule l'icône s'anime, et rien n'attend la fin de l'animation.

- La pilule grandit depuis le centre en 250 ms.
- Chaque icône joue son geste en 800 ms, à la sélection seulement : l'onde de Réunions s'agite comme un égaliseur, le cercle d'Actions se trace puis la coche en sort par l'ouverture, la loupe de Recherche balaie (un balayage, pas un tour complet, pour ne pas ressembler à un chargement).
- Toucher l'onglet déjà actif rejoue le geste.
- À la désélection, l'icône revient au repos en 100 ms, sans animation. Pas de vibration.
- « Réduire les animations » : seule la couleur change.

### Messages, dialogues, feuilles

- **Message bref** (snackbar) : couleurs inversées, il confirme et propose « Annuler » quand c'est possible, plutôt qu'une question avant d'agir. 4 secondes, plus longtemps avec un lecteur d'écran.
- **Dialogue** : seulement pour ce qui ne se rattrape pas. Ses boutons disent l'action : « Supprimer la réunion ».
- **Feuille du bas** : coins de 28 en haut, poignée visible, on la ferme en glissant. Pour le consentement avant d'enregistrer, le partage et les filtres.
- **État vide** : une icône, une phrase, un bouton (« Aucune réunion pour l'instant. Enregistrez la première. »).
- **Chargement** : des squelettes pour les listes, un pourcentage et un temps restant pour la transcription.

## Navigation

L'utilisateur doit savoir où aller sans réfléchir.

```
Niveau 1   Réunions             Actions               Recherche
           réunions, Enregistrer  tâches, responsable,  dans toutes les
           réglages en haut à     date                  réunions
           droite
Niveau 2   Une réunion : Compte rendu (par défaut) et Transcription
           Enregistrement : une feuille, plein écran ou mini-barre sur tous les onglets
Niveau 3   Un détail : une action, un locuteur à renommer, un passage à réécouter
```

- **Le retour du système marche partout** : retour prédictif sur Android, glisser depuis le bord gauche sur iPhone. On ne le bloque jamais, sauf pour confirmer l'arrêt d'un enregistrement.
- **Un titre par écran**, qui dit où l'on est (« Réunions », le nom de la réunion). Sur iPhone, le bouton retour reprend le nom de l'écran précédent.
- **Enregistrer sans ouvrir l'app** : widget et tuile des Réglages rapides sur Android, widget, Live Activity et écran verrouillé sur iPhone.

## Mouvement

Une animation aide à comprendre ce qui se passe, sinon on s'en passe. Les principes viennent d'Emil Kowalski.

| Jeton | Durée | Courbe | Pour |
| --- | --- | --- | --- |
| `instant` | 100 ms | sortie douce | Réaction au toucher |
| `quick` | 150 ms | sortie douce | Puces, interrupteurs, fondus, onglets |
| `standard` | 250 ms | sortie douce | Apparition d'un élément, ouverture d'une feuille |
| écran | système | système | Passage d'un écran à l'autre |
| ressort | | amortissement 1, sans rebond | Tout ce qui suit le doigt |

La sortie douce s'écrit `CubicBezierEasing(0.23f, 1f, 0.32f, 1f)`. Dans le code, `NotitiaTheme.motion.quick()` et ses voisines la donnent déjà.

- Ce qu'on fait cent fois par jour ne s'anime presque pas : changer d'onglet, c'est 150 ms au plus pour le contenu.
- Rien n'apparaît depuis une taille zéro : on part de 95 % avec un fondu. Les sorties sont 30 % plus courtes que les entrées.
- Un bouton pressé passe à 97 % en moins de 100 ms. Pas d'effet d'onde sur iPhone : un léger assombrissement à la place.
- Ce qui suit le doigt utilise un ressort, interruptible, sans rebond. Un léger rebond seulement après un geste lancé (une feuille jetée vers le bas).
- « Réduire les animations » : les mouvements deviennent des fondus. Compose le respecte tout seul sur Android ; sur iPhone, il faut lire le réglage d'iOS (à faire avec la première animation qui bouge).
- Vibration légère au début et à la fin d'un enregistrement, « succès » quand le compte rendu est prêt. Jamais à chaque appui.

## Accessibilité

WCAG 2.2 niveau AA, plus les règles d'Apple et de Google. Notitia s'adresse aussi aux organisations publiques et de santé : c'est une exigence, pas un bonus.

- **Contraste** : texte 4,5:1 au moins ; grand texte (24 sp, ou 19 sp en gras), icônes et contours 3:1. Toute la palette ci-dessus est vérifiée.
- **Cibles** : 48 × 48 dp au moins, 8 dp entre deux cibles.
- **Taille du texte** : tout en sp, testé à 200 % : rien de coupé, les lignes passent à la ligne.
- **Jamais la couleur seule** : locuteur, couleur et nom ; erreur, icône et phrase ; action faite, coche et texte barré.
- **Lecteurs d'écran** : chaque icône seule a un libellé en français dans les ressources (`contentDescription`). Une carte se lit d'un bloc. Les titres sont marqués comme titres.
- **Enregistrement** : l'état est annoncé quand il change (« Enregistrement démarré »), pas chaque seconde.
- **Ordre de lecture** logique, de haut en bas. Clavier externe et contacteurs fonctionnent.
- **Mouvement réduit** respecté partout (voir Mouvement).

**Le test de 2 minutes, avant chaque PR qui ajoute ou change un écran** : parcourir l'écran avec TalkBack ou VoiceOver, passer le texte à 200 %, essayer le thème clair et le sombre, et l'afficher sur un petit écran de 360 dp de large.

## Rédaction

Les mots font partie du design. On vouvoie, comme l'app Mac. Phrases courtes, verbes d'action, mots de l'utilisateur plutôt que ceux du code. Pas d'emoji, pas de point d'exclamation.

| À éviter | À écrire |
| --- | --- |
| OK | Enregistrer la réunion |
| Erreur 503 | La transcription s'est arrêtée. Réessayez : l'audio est conservé. |
| Voulez-vous vraiment supprimer ? | Supprimer la réunion du 25 oct. ? L'audio et le compte rendu seront effacés. |
| Inférence en cours… | Rédaction du compte rendu · environ 1 min |
| Super, c'est prêt ! | Compte rendu prêt |
| Aucune donnée | Aucune réunion pour l'instant. Enregistrez la première. |
| 25/10/2026 14h02min | Aujourd'hui, 14:02 · hier · lundi · 3 oct. |

## Plateformes

Natif dans les gestes, Notitia dans le style : retour, clavier, partage et vibrations se comportent comme sur chaque système ; couleurs, police et formes sont celles de Notitia.

- **Partagé** : tous les écrans par défaut, écrits une fois dans `commonMain` avec Compose Multiplatform et le thème Notitia.
- **Android** : Material 3 habillé de nos jetons, couleurs dynamiques coupées, affichage bord à bord, retour prédictif.
- **iPhone** : les mêmes écrans, sans effet d'onde, avec le geste retour, l'icône de partage et les vibrations d'iOS. En Swift seulement le widget, la Live Activity, et un écran s'il le justifie, avec les mêmes couleurs et la même police.
- **Téléphones hors des cases** : Huawei est hors du périmètre de la bêta. Sur Samsung et Xiaomi, tester l'enregistrement long en arrière-plan. Écrans de 360 à 430 dp de large ; la tablette viendra plus tard.
- **À vérifier sur un vrai iPhone** : que la taille de texte du système agrandit bien nos textes (la documentation de Compose Multiplatform ne le dit pas), VoiceOver, et le geste retour, qui passe par Navigation 3, encore en alpha.

## Dans le code

La charte n'est pas une barrière si le bon chemin est le plus court. Un écran ne contient jamais de couleur, de marge ou de taille de texte en dur.

```kotlin
// À faire : le composant et les jetons du thème.
NotitiaButton(
    text = stringResource(Res.string.share_minutes),
    onClick = onShare,
    modifier = Modifier
        .fillMaxWidth()
        .padding(NotitiaTheme.spacing.l),
)

// À éviter : une couleur, une marge, une taille et un texte en dur.
Button(
    onClick = onShare,
    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7929B6)),
    modifier = Modifier.padding(12.dp),
) {
    Text("Partager", fontSize = 14.sp)
}
```

- Les jetons : `NotitiaTheme.colors`, `.typography`, `.spacing`, `.sizes`, `.shapes`, `.motion`.
- Les composants Material 3 qu'on utilise tels quels (barre d'onglets, feuilles, dialogues, messages brefs) reçoivent nos couleurs, notre police et nos formes par `MaterialTheme`.
- Chaque composant a trois aperçus (`@Preview`) : clair, sombre et texte à 200 %. Les aperçus du thème montrent la palette et l'échelle de textes dans Android Studio.

## Sources

- [Apple, Human Interface Guidelines](https://developer.apple.com/design/human-interface-guidelines/)
- [Material 3](https://m3.material.io/)
- [WCAG 2.2](https://www.w3.org/TR/WCAG22/)
- [Emil Kowalski, animation d'interface](https://emilkowal.ski/)
- [Schibsted Grotesk](https://github.com/schibsted/schibsted-grotesk)
- [Material Symbols](https://fonts.google.com/icons)
- [Compose Multiplatform, accessibilité sur iOS](https://kotlinlang.org/docs/multiplatform/compose-ios-accessibility.html)
- [Compose Multiplatform, ressources et polices](https://kotlinlang.org/docs/multiplatform/compose-multiplatform-resources-usage.html)
