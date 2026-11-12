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
4. **La couleur a un sens.** Violet : agir ou sélectionné. Rouge : enregistrement, suppression ou retard. Une couleur par locuteur. Si on retire le violet d'un écran, on doit perdre une information.
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
| `background` | Fond des écrans | `#0B0A0F` | `#F3F2F7` | |
| `surface` | Cartes, champs, groupes | `#1E1D25` | `#FFFFFF` | |
| `surfaceRaised` | Feuilles, menus, mini-barre | `#201F28` | `#FFFFFF` + ombre | |
| `track` | Creux sous ce qu'on choisit ou qu'on presse : rail du sélecteur, ronds Marquer et Pause | `#16151C` | `#E5E3EA` | |
| `bar` | Fond de la barre d'onglets | `#0B0A0F` | `#FFFFFF` | |
| `divider` | Séparateurs | `#2C2B36` | `#E5E3EA` | |
| `outline` | Contour des champs | `#6F6D7A` | `#8A8895` | 3,9 / 3,1 |
| `text` | Texte principal | `#F4F3F7` | `#121117` | 17,9 / 16,9 |
| `textSecondary` | Métadonnées, aide | `#A3A1AD` | `#5E5C69` | 7,8 / 5,9 |
| `textTertiary` | Groupes, horodatages | `#8C8A97` | `#6F6D7A` | 5,8 / 4,55 |
| `accent` | Fond des boutons principaux | `#882DCD` | `#882DCD` | texte blanc 6,4 |
| `accentContent` | Liens, icônes actives, onglet actif | `#CB95F3` | `#882DCD` | 8,6 / 5,7 |
| `accentSoft` | Sélection, puces, badges | violet à 22 % | violet à 10 % | texte 7,4 / 4,9 |
| `focus` | Contour d'un champ actif | `#B66EED` | `#882DCD` | |
| `dim` | Voile sous un panneau ou une feuille | noir à 50 % | noir à 25 % | |
| `stack` | Derrière un écran qui recule sous une feuille (iPhone) | `#000000` | `#000000` | |
| `popup` | Menus au-dessus d'une surface | `#2B2A35` | blanc, avec ombre | |
| `red` | Enregistrement, suppression, retard | `#FF5A52` | `#BF2D2A` | 6,4 / 5,2 |
| `green` | Succès, action faite | `#3DD68C` | `#15724A` | 10,5 / 5,3 |
| `amber` | Attention (batterie, espace disque, action à compléter) | `#FFB224` | `#A35200` | 10,9 / 5,0 |

Les contrastes sont mesurés contre le fond du thème, et les textes gardent 4,5:1 au moins sur les cartes. `redSoft`, `greenSoft` et `amberSoft` (rouge à 16 % et 10 %, vert à 14 % et 8 %, ambre à 16 % et 10 %) servent de fond aux badges et aux cartes de ces couleurs. `onGreen` (blanc en clair, `#0B0A0F` en sombre) pour la coche et le texte posés sur le vert. `ringTrack` (vert à 18 % sur les deux thèmes) pour la piste de l'anneau de la carte de personne.

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
| Clair | `#1E6CCC` | `#0E7C70` | `#966100` | `#C2266F` | `#4D7A12` | `#B4501A` | `#FFFFFF` |

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
| `bubble` | 20, 6 en bas à droite | Le message prêt à partir, comme une bulle envoyée |
| `full` | rond | Boutons, puces, avatars, mini-barre |

**Élévation.** En sombre, pas d'ombre : une surface plus haute est plus claire. En clair, une ombre douce pour ce qui flotte, et une ombre de 1 dp sous les cartes pour les détacher du fond gris.

**Icônes.** Material Symbols Rounded (licence Apache 2.0), arrondies comme nos boutons. 24 dp, 20 dp dans un bouton. Contour quand inactif, plein quand actif, ou plus épais quand l'icône n'a pas de version pleine. Pas d'emoji dans l'interface. Les SF Symbols d'Apple sont réservés aux plateformes Apple : seulement dans les écrans écrits en Swift.

| Concept | Icône |
| --- | --- |
| Réunions | `graphic_eq` |
| Actions | `task_alt` |
| Recherche | `search` |
| Enregistrer | `mic` |
| Marquer un moment | `bookmark` |
| Partager | `share` sur Android, `ios_share` sur iPhone |
| Relancer | `chat_bubble` |
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

- Un bouton se nomme avec un verbe qui dit ce qui se passe : « Partager le compte rendu », jamais « OK » ni « Valider ». Seule exception : « OK » qui ferme la sélection sur iPhone, le mot d'iOS à cet endroit.
- Pressé : 97 % de sa taille. Désactivé : 38 % d'opacité. En chargement : un indicateur à la place de l'icône, le libellé reste.

### Le bouton Enregistrer, la signature

72 dp, rond, dégradé des boutons, micro blanc de 32. Pendant l'enregistrement, il devient « Arrêter », une pastille rouge pulse lentement (orange en pause) et l'onde prend les couleurs du logo. Avant de démarrer : une feuille de consentement, comme sur Mac.

### Cartes, badges, champs

- **Carte** : fond `surface`, coins de 16, marge intérieure de 16, ombre de 1 dp en clair. Toute la carte se touche.
- **Choisir plusieurs réunions** : à la façon de chaque système, avec une vibration d'appui long. Sur Android, comme WhatsApp et Gmail : un appui long sur une carte la choisit, puis coche comme un toucher ; une barre contextuelle couvre l'en-tête, avec ✕, « 2 sélectionnées », « Tout » précédé du rond des cartes, qui se remplit quand tout est choisi (comme One UI), et la corbeille ; la sélection se ferme avec sa dernière carte ou le retour. Sur iPhone, comme Mail et WhatsApp : un appui long ouvre le menu de la carte, avec « Sélectionner », qui lance la sélection avec elle, et « Supprimer », en rouge et en dernier ; « Sélectionner » aussi dans l'en-tête ; une barre de navigation avec « Tout sélectionner », le compte au milieu et « OK » ; une barre d'outils prend la place de la barre d'onglets, avec la corbeille rouge à droite, grisée tant que rien n'est choisi. Partout, le rond violet glisse à gauche des cartes, une carte choisie prend `accentSoft`, son chevron disparaît, le bouton Enregistrer se cache, et la suppression nomme une réunion seule (« Supprimer « Point produit » ? ») et compte les autres (« Supprimer 3 réunions ? »). Changer d'onglet quitte la sélection.
- **Carte d'action** : une carte par action, comme les autres. Le rond à gauche, centré sur la première ligne, est un bouton à lui seul (cible de 56 × 48) ; le reste de la carte ouvre la fiche de l'action. Cocher : le rond se remplit de vert depuis 40 % de sa taille, la coche se trace, une ligne barre le texte de gauche à droite ; la carte reste 1,1 s à sa place (un nouveau toucher annule), puis rejoint « Terminées », repliée en bas. Glisser la carte vers la droite la termine aussitôt, avec le glissement de Material sur les deux systèmes, comme Google Tasks et Gmail : la carte suit le doigt sans retard, un fond vert apparaît dessous, avec la coche centrée dans la bande découverte, qui grandit à 40 % de la largeur, avec une vibration légère ; un lancer rapide suffit ; la carte part hors de l'écran et la liste se resserre. Dans les deux cas, quand la carte s'en va, un message bref « Action terminée » propose « Annuler ». Glisser vers la gauche relance son responsable : fond `accent` et icône `chat_bubble`, mêmes seuil et vibration ; au lâcher, la carte revient à sa place pendant que la feuille de relance monte, comme « Reporter » dans Gmail. Pas de glissement à gauche sans responsable ni pour une action faite. Un appui long ouvre un menu contextuel (vibration d'appui long), comme dans Rappels et Gmail : « Marquer comme faite » ou « Remettre à faire », « Relancer Nathan », « Ouvrir le compte rendu ». Les lecteurs d'écran ont « Relancer Nathan » dans les actions de la carte. Une action en retard le dit en rouge, en mots (« 2 j de retard »). Une petite cloche `textTertiary` suit l'échéance quand un rappel est prévu, comme dans Rappels.
- **Carte « À compléter »** : en haut de l'onglet Actions, les actions sans responsable ou sans échéance, que personne ne peut encore suivre. Fond `surface` teinté d'`amberSoft`, icône `edit_note` et titre en `amber`. Une ligne par action, avec ce qui lui manque en ambre (« Qui ? », « Pour quand ? ») et un chevron ; la toucher ouvre la fiche. Une action complétée rejoint son groupe et la carte se resserre.
- **Carte de personne** : dans l'onglet Actions, « Par personne » (le choix « Par échéance | Par personne » reste d'un onglet à l'autre). Une carte par responsable, regroupé sur le prénom écrit, sans tenir compte des majuscules ni des accents, jamais sur la voix ; elle prend l'orthographe et la couleur de sa dernière réunion. En tête : l'avatar de 32, le prénom, « 3 en cours · 1 en retard » (le retard en rouge), puis « 2/5 » et un anneau vert de ce qui est fait, sur une piste `ringTrack`, comme les anneaux d'activité d'Apple. L'anneau se remplit depuis midi quand il apparaît et suit chaque coche ; quand tout est fait, il se ferme, une coche se trace dedans et le téléphone vibre légèrement (seulement sous le doigt). Les personnes qui ont le plus de retards viennent d'abord, puis les plus chargées, puis l'ordre alphabétique ; « Sans responsable », avec une silhouette grise, ferme la liste. Toucher l'en-tête déplie la carte sur ses actions en cours, comme les groupes de Rappels : le rond à cocher sous l'avatar, le texte sous le prénom, l'échéance, le retard en rouge, ou « Pour quand ? » en ambre ; toucher la ligne ouvre la fiche. Sous la dernière ligne, un lien « Relancer Nathan » en `accentContent`, icône `chat_bubble` sous les ronds, comme « Nouveau rappel » au pied d'une liste de Rappels ; pas de lien pour « Sans responsable ». Pas de glissement dans une carte. Une action cochée reste 1,1 s à sa place, et la carte ne bouge pas avant. « Réduire les animations » : l'anneau et le dépliage sans mouvement.
- **Badge** : 24 de haut, coins de 8, texte 13 en 600, icône de 16. Il dit l'état en mots, pas seulement en couleur (« Compte rendu prêt », « Transcription 64 % »).
- **Champ** : 52 de haut, coins de 12. Le libellé est au-dessus, jamais remplacé par le texte d'exemple. Actif : contour `focus` de 2 dp. Une erreur dit ce qui ne va pas et comment corriger, avec une icône.
- **Barre de recherche** : l'exception au champ, une seule, en bas de l'écran Recherche. 52 de haut au moins, coins de 26, qui restent les mêmes quand elle grandit jusqu'à 4 lignes. Pas de libellé au-dessus : son texte d'exemple (« Demander à Notitia ») dit ce qu'elle fait et sert de libellé aux lecteurs d'écran. Active : contour `focus` de 2 dp. Le clavier la fait monter ; la barre d'onglets reste dessous.

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
- **Dialogue** : seulement pour ce qui ne se rattrape pas. Ses boutons disent l'action : « Supprimer la réunion ». Une suppression se confirme à la façon du système. Sur iPhone, la feuille d'actions d'iOS, comme dans Photos : « Supprimer la réunion » en rouge au-dessus d'« Annuler », sans icône ; depuis iOS 26, elle sort du bouton qui l'a demandée. Sur Android, le dialogue de Material, comme dans Drive et Gmail : sans icône, puisque le titre dit déjà ce qui part, puis « Annuler » et « Supprimer » côte à côte, à droite ; le titre nomme déjà ce qui part, le bouton garde le seul verbe pour tenir sur une ligne.
- **Feuille du bas** : coins de 28 en haut, poignée visible, on la ferme en glissant. Pour le consentement avant d'enregistrer, le partage, les filtres et la fiche d'une action.
- **Fiche d'une action** : une feuille du bas. Le voile suit la feuille image par image, sous le doigt aussi ; sur iPhone, l'écran derrière recule en plus comme sous une feuille de page d'UIKit (94 %, coins de 28, fond `stack`), sauf avec « Réduire les animations ». Android laisse l'écran en place, comme ses propres feuilles. Dans la fiche, aérée : le titre, puis le responsable et l'échéance en deux lignes groupées (icône, libellé, valeur) qui ouvrent chacune un menu, puis « Me le rappeler » dans le même bloc : toute la ligne bascule l'interrupteur, avec une vibration, et la légende dit « La veille à 9 h », « Choisissez d'abord une date. » (grisée sans échéance) ou « La veille est déjà passée. ». La permission des notifications se demande au premier allumage, jamais avant ; refusée, la légende devient un lien `accentContent` qui ouvre directement les réglages de notification de l'app. La notification ne dit jamais le texte de l'action, seulement « Une action arrive à échéance demain » (une seule par jour d'échéance), et la toucher ouvre la fiche ; la phrase dite sous une barre violette avec qui l'a dite et l'heure, la réunion en une ligne qui ouvre le compte rendu, et « Marquer comme faite » en bouton principal. À droite du titre, l'icône `chat_bubble` (cible de 48) relance le responsable, comme une icône de barre d'outils d'une feuille iOS ; elle n'est là que s'il y a un responsable et que l'action n'est pas faite. Le calendrier seul, sans saisie au clavier, du jour de la réunion à un an après aujourd'hui : rien n'était dû avant qu'on en convienne.
- **Feuille de relance** : aussi haute que son contenu, presque sans texte. En tête, l'avatar de la personne dans sa couleur, son prénom, et à droite un bouton rond `accent` avec la seule icône de partage du système (son nom, « Envoyer à Nathan », est pour les lecteurs d'écran), comme le bouton d'envoi de Messages. Dessous, le message dans une bulle `accentSoft` calée à droite, forme `bubble`, comme un message envoyé ; la toucher le modifie, le clavier ne s'ouvre pas tout seul. Le message, tutoyé et poli, donne la réunion, sa date, l'action et son échéance (« pour le 7 nov. »), jamais le mot « retard » ; une réunion par bloc quand il y en a plusieurs. Le bouton ouvre la feuille de partage du système : Notitia n'envoie rien lui-même. Sur iPhone, annuler le partage ramène au texte ; un envoi referme la feuille. Sur Android, qui ne dit pas si le texte est parti, la feuille se referme dès que le sélecteur s'ouvre. Relancer n'est jamais un gros bouton : un glissement, une icône ou un lien.
- **État vide** : une icône, une phrase, un bouton (« Aucune réunion pour l'instant. Enregistrez la première. »).
- **Chargement** : des squelettes pour les listes, un pourcentage et un temps restant pour la transcription. Une réponse rédigée : douze barres qu'une lumière parcourt (« Notitia cherche dans vos 5 réunions »), puis seules les barres des passages trouvés restent allumées et respirent (« Notitia rédige la réponse »). Le chargement et les messages « Aucun passage » se centrent dans l'espace libre. Jamais de roue qui tourne ni d'icône d'IA.

## Navigation

L'utilisateur doit savoir où aller sans réfléchir.

```
Niveau 1   Réunions             Actions               Recherche
           réunions, Enregistrer  tâches, responsable,  dans toutes les
           réglages en haut à     date                  réunions,
           droite                                       historique à gauche
Niveau 2   Une réunion : Compte rendu (par défaut) et Transcription
           Enregistrement : une feuille, plein écran ou mini-barre sur tous les onglets
Niveau 3   Un détail : une action, un locuteur à renommer, un passage à réécouter
```

- **Le retour du système marche partout** : retour prédictif sur Android, glisser depuis le bord gauche sur iPhone. On ne le bloque jamais, sauf pour confirmer l'arrêt d'un enregistrement.
- **Un titre par écran**, qui dit où l'on est (« Réunions », le nom de la réunion). Sur iPhone, le bouton retour reprend le nom de l'écran précédent.
- **Compte rendu** : son titre se modifie sur place, comme dans Notes et Granola : on le touche, le curseur s'y pose, sans cadre ni fenêtre. Il est gardé quand on le quitte (OK du clavier, toucher à côté, clavier fermé, retour) ; vidé, il reprend l'ancien. Le menu « … » de la barre du haut propose « Renommer », qui sélectionne tout le titre, puis « Supprimer » en rouge, en dernier. Après une suppression, on revient à l'écran d'avant, où la carte de la réunion s'efface et la liste se resserre.
- **L'en-tête d'un onglet** : le grand titre à 8 dp sous la zone sûre du système (barre d'état, encoche, Dynamic Island), à la même hauteur sur les trois onglets. Ses boutons (réglages) sont sur sa ligne, à droite, centrés sur elle, comme la barre du haut de WhatsApp ; leur cible de 48 dp déborde de la ligne sans pousser le contenu.
- **Historique de la recherche** : un panneau qui glisse depuis la gauche par-dessus l'écran (glisser vers la droite, ou l'horloge du titre), comme dans Claude. L'écran et la barre d'onglets restent en place sous un voile. Le retour du système le referme, avec l'animation du retour prédictif sur Android 14+. Un appui long sur une recherche ouvre un menu à côté d'elle (Épingler, Renommer, Supprimer) ; les épinglées restent en tête, même après « Effacer l'historique ». La dernière recherche reste affichée, même après un changement d'onglet ; après 10 minutes hors de l'app, ou au relancement, la recherche repart vierge et la précédente se retrouve dans l'historique.
- **Enregistrer sans ouvrir l'app** : widget et tuile des Réglages rapides sur Android, widget, Live Activity et écran verrouillé sur iPhone.

## Mouvement

Une animation aide à comprendre ce qui se passe, sinon on s'en passe. Les principes viennent d'Emil Kowalski.

| Jeton | Durée | Courbe | Pour |
| --- | --- | --- | --- |
| `instant` | 100 ms | sortie douce | Réaction au toucher |
| `quick` | 150 ms | sortie douce | Puces, interrupteurs, fondus, onglets |
| `standard` | 250 ms | sortie douce | Apparition d'un élément, ouverture d'une feuille |
| écran | système | système | Passage d'un écran à l'autre |
| en place | système | système | Un contenu qui change sans changer d'écran : « fade through » de Material sur Android (300 ms), fondu enchaîné d'UIKit sur iPhone |
| ressort | | amortissement 1, sans rebond | Tout ce qui suit le doigt |

La sortie douce s'écrit `CubicBezierEasing(0.23f, 1f, 0.32f, 1f)`. Dans le code, `NotitiaTheme.motion.quick()` et ses voisines la donnent déjà.

- Ce qu'on fait cent fois par jour ne s'anime presque pas : changer d'onglet, c'est 150 ms au plus pour le contenu.
- Rien n'apparaît depuis une taille zéro : on part de 95 % avec un fondu. Les sorties sont 30 % plus courtes que les entrées.
- Un bouton pressé passe à 97 % en moins de 100 ms. Pas d'effet d'onde sur iPhone : un léger assombrissement à la place.
- Une réponse arrive morceau par morceau, 60 ms d'écart, 8 dp de montée : elle décélère comme Material sur Android et suit le ressort d'iOS sur iPhone. Dans le code, `motion.swap()` et `motion.arrive()` choisissent selon le système. Elle ne se déroule qu'à son arrivée, une fois : quand on revient sur l'écran, elle est déjà là.
- Ce qui suit le doigt utilise un ressort, interruptible, sans rebond. Un léger rebond seulement après un geste lancé (une feuille jetée vers le bas).
- « Réduire les animations » : les mouvements deviennent des fondus. Compose le respecte tout seul sur Android ; sur iPhone, il faut lire le réglage d'iOS (à faire avec la première animation qui bouge).
- Vibration légère au début et à la fin d'un enregistrement, et au passage du seuil d'un glissement ; « succès » quand le compte rendu est prêt. Jamais à chaque appui.

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
