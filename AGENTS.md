# AGENTS.md

Instructions pour toute IA qui code dans ce repo : Claude Code, GitHub Copilot, Antigravity ou une autre. Le développeur reste l'auteur de chaque commit et de chaque pull request.

## Le projet

Notitia est une app mobile (Android et iOS) qui enregistre une réunion, la transcrit et en tire un compte rendu : décisions, actions et responsables. Tout se passe sur l'appareil. C'est l'EIP d'une équipe d'Epitech Nice (promo 2028). L'app Android et iOS vit dans `mobile/`, l'app Mac dans `macos/`, la documentation dans le repo `Notitia_Documentation` de l'organisation NotitiaEIP.

Le cadre de l'année, ce qu'on fait et ce qu'on ne fait pas, est dans [CADRE.md](https://github.com/NotitiaEIP/notitia_documentation/blob/main/CADRE.md). Si une demande le contredit, signale-le avant de coder.

## Règles produit non négociables

1. **Rien ne sort du téléphone.** Aucun appel réseau ne transporte d'audio, de transcription, de compte rendu ou de métadonnées de réunion. Pas de SDK d'analytics ou de crash reporting qui verrait ce contenu. Seule exception prévue : une gateway de secours, désactivée par défaut, activée explicitement par l'utilisateur, et jamais pour une organisation de santé. Toute exception passe par une PR dédiée. Aucun texte de réunion dans les journaux de l'app (`Log`, `println`), ni dans une issue, une PR ou un log de CI : le repo est public.
2. **Aucun secret dans le code** : pas de clé d'API, de token ni de mot de passe en dur, pas de `local.properties` ou de `.env` commité.
3. **Pas de sauvegarde cloud des réunions** : données exclues de la sauvegarde Android (`dataExtractionRules`) et d'iCloud (`isExcludedFromBackup`). Stockage local chiffré.
4. **Ni empreinte vocale persistante, ni analyse d'émotion** (RGPD article 9, AI Act article 5). Les locuteurs sont séparés réunion par réunion et l'utilisateur les renomme.
5. **Audio compressé (jamais de WAV stocké), gardé 30 jours par défaut**, puis supprimé. La transcription et le compte rendu restent.

En cas de doute sur une de ces règles, arrête-toi et demande plutôt que deviner.

## Stack

- Kotlin Multiplatform et Compose Multiplatform : l'interface est partagée entre Android et iOS.
- Gradle via le wrapper (`mobile/gradlew`), dont la version est fixée dans `mobile/gradle/wrapper/gradle-wrapper.properties`. Les versions des dépendances vivent uniquement dans `mobile/gradle/libs.versions.toml`.
- Android Studio avec le plugin Kotlin Multiplatform, ouvert sur le dossier `mobile/`. Xcode sur Mac pour iOS.

## Organisation du code

- `mobile/shared/` : le code partagé entre Android et iOS. Dans `mobile/shared/src/` :
  - `commonMain` : tout ce qui peut être partagé (écrans, logique, modèles de données, accès aux modèles IA) ;
  - `androidMain` et `iosMain` : seulement ce qui dépend de la plateforme (micro, enregistrement en arrière-plan, widget, exécution des modèles).
- `mobile/androidApp/` : le point d'entrée Android (activité, manifeste, icônes).
- `mobile/iosApp/` : le projet Xcode qui embarque le code partagé.
- `macos/` : l'app Mac en Swift, avec son projet Xcode et le package `NotitiaKit` dans `macos/apple/`. Ses règles propres sont dans `macos/CLAUDE.md`.

Le code propre à une plateforme se cache derrière une interface déclarée dans `commonMain` :

```kotlin
// commonMain
interface AudioRecorder {
    suspend fun start()
    suspend fun stop(): RecordingFile
}
// androidMain : AndroidAudioRecorder (service de premier plan de type micro)
// iosMain : IosAudioRecorder (AVAudioSession, mode audio en arrière-plan)
```

Préférer une interface injectée à `expect`/`actual` dès que l'implémentation garde un état.

## Commandes

- Lancer l'app : `./notitia`, depuis la racine ou depuis `mobile/`, ouvre un menu. Sans menu : `./notitia phone` (téléphone branché), `./notitia emulator`, `./notitia apk`.
- Compiler et tester avant une PR : `./notitia check`, qui lance `./gradlew build` dans `mobile/`.
- Tests du code partagé seuls : `cd mobile && ./gradlew :shared:testAndroidHostTest`.
- iOS : `./notitia ios` ouvre `mobile/iosApp/iosApp.xcodeproj` dans Xcode, sur un Mac. Sous Linux et Windows, les cibles iOS ne compilent pas : c'est normal, ne cherche pas à « réparer » une erreur iOS depuis ces machines.
- Mac : ouvrir `macos/apple/Notitia.xcodeproj` dans Xcode, et tester `NotitiaKit` avec `cd macos/apple/NotitiaKit && swift test`, sur un Mac seulement. Le build Gradle ignore `macos/`.

## Conventions

- Code, noms, commentaires et messages de commit en anglais. Textes de l'interface en français, toujours dans les ressources Compose (`composeResources`), jamais en dur : l'anglais viendra après le Greenlight.
- Commits au format Conventional Commits (voir « Le message de commit et la description de PR »). Une PR traite un seul sujet et se fusionne en squash.
- La logique métier vit dans `commonMain` et se teste dans `commonTest`.
- Une nouvelle dépendance passe par `mobile/gradle/libs.versions.toml`, avec une licence vérifiée (Apache 2.0, MIT ou BSD de préférence).
- Jamais de fichier de modèle IA dans le repo (`.gguf`, `.onnx`, `.tflite`, `.bin`...). Les modèles sont livrés par les stores (Play for On-device AI sur Android, Background Assets sur iOS).
- Monter Gradle, AGP, Kotlin ou Compose se fait dans une PR à part.

## Ce que tu fais, ce que tu proposes

| Action | Qui |
| --- | --- |
| Lire le code, les issues, les PR et le GitHub Project | Toi |
| Modifier des fichiers, lancer le build et les tests | Toi |
| Créer une branche locale | Toi |
| Créer une issue depuis un modèle, après l'accord du développeur | Toi |
| Changer le statut et les champs d'une tâche dans le GitHub Project | Toi |
| `git commit`, `git push`, `git tag`, `gh pr create`, `gh pr merge` | Tu écris la commande, le développeur la lance |
| Approuver une PR, fermer une issue | Un humain, jamais toi |
| Travailler directement sur `main` | Personne |

## Aucune trace d'IA

Ni `Co-Authored-By`, ni « Generated with », ni mention d'une IA dans un message de commit, un titre ou une description de PR, le code ou un commentaire. Aucun autre fichier d'instructions pour IA que celui-ci, `CLAUDE.md` et le `CLAUDE.md` d'un dossier comme `macos/`, qui ne garde que les règles propres à ce dossier.

## Le cycle d'une tâche

Une tâche = une issue = une branche = une PR. Pas de PR sans issue. Si la tâche dépasse deux ou trois jours de travail ou environ 400 lignes modifiées, propose de la découper en plusieurs issues avant de commencer.

### 1. Démarrer

1. Si le développeur ne dit pas sur quoi il travaille, liste ses tâches au statut « Todo » du sprint en cours dans le GitHub Project et laisse-le choisir.
2. S'il décrit un travail qui n'a pas encore d'issue, crée-la avant de coder. Pars du modèle du repo : `.github/ISSUE_TEMPLATE/feature.md` pour une user story, `task.md` pour une tâche technique ou business, `bug.md` pour un bug. Remplis ses sections telles quelles, sans en ajouter ni en retirer, et reprends les labels indiqués en tête du modèle.
   - Le titre commence par le prochain identifiant libre : `US-` suivi du numéro de l'epic et d'un rang (US-13 est la troisième story de l'epic E1, US-102 la deuxième de E10), `T-` pour une tâche technique, `B-` pour le business. Un bug n'a pas d'identifiant. Pour trouver le prochain : `gh issue list --state all --search "US-1 in:title" --json title`.
   - Montre le texte au développeur, puis crée l'issue après son accord (`gh issue create --title "..." --body "..." --label "..." --milestone "..."`). Range-la sous son epic et remplis ses champs dans le Project (voir « Le GitHub Project »).
3. Lis l'issue (`gh issue view 12`). Résume en trois lignes le besoin et les critères d'acceptation, puis attends son accord.
4. Assigne-la au développeur (`gh issue edit 12 --add-assignee @me`) et passe-la en « In progress » (voir « Le GitHub Project »).
5. Crée la branche depuis `main` à jour, au format `<type>/<numéro>-<description-courte>` :

   ```bash
   git switch main
   git pull
   git switch -c feat/12-record-lock-screen
   ```

### 2. Coder

- Respecte les règles produit. Si la tâche les contredit, arrête-toi et demande.
- Reste dans le périmètre de l'issue. Si autre chose mérite d'être changé, propose une nouvelle issue au lieu de le faire.

### 3. Terminer

Seulement quand le développeur dit que c'est terminé et qu'il a relu le code. Avant, tu ne proposes ni commit ni PR.

1. Mets la branche à jour avec `main` (voir « Mettre la branche à jour avec main »).
2. Lance `./notitia check`. S'il échoue, corrige ou explique pourquoi, et ne propose rien d'autre.
3. Rappelle la checklist : aucune clé ni token, aucun fichier de modèle IA, aucun contenu de réunion envoyé sur le réseau, textes d'interface en français dans les ressources, testé sur un vrai téléphone si la tâche touche l'audio, l'arrière-plan ou l'IA.
4. Propose les commandes dans un seul bloc, sans les lancer. Nomme les fichiers un par un dans `git add`. Adapte les guillemets au terminal du développeur (bash, zsh ou PowerShell).

   ```bash
   git add mobile/shared/src/androidMain/kotlin/<package>/audio/AndroidAudioRecorder.kt
   git commit -m "feat(audio): keep recording when the screen is locked" -m "Android stopped the recorder after ten minutes with the screen off. It now runs in a foreground service of type microphone." -m "Test plan: 45-minute recording on a Pixel 7 with the screen locked, the file plays to the end." -m "Closes NotitiaEIP/Notitia_Mobile#12"
   git push -u origin feat/12-record-lock-screen
   gh pr create --base main --title "feat(audio): keep recording when the screen is locked" --body "Android stopped the recorder after ten minutes with the screen off. It now runs in a foreground service of type microphone.

   Test plan: 45-minute recording on a Pixel 7 with the screen locked, the file plays to the end.

   Closes NotitiaEIP/Notitia_Mobile#12"
   gh pr merge --auto --squash
   ```

5. Quand le développeur confirme que la PR est ouverte, vérifie qu'elle est liée à l'issue (`gh pr view --json closingIssuesReferences`) et passe la tâche en « Need review ». Demande-lui combien de temps la tâche a pris, en jours-homme (une demi-journée vaut 0,5), et remplis le champ « Réel (j/h) » : à la revue de sprint, l'équipe le compare à l'estimation. Si la tâche change l'interface, rappelle-lui d'ajouter des captures d'écran dans un commentaire de la PR.

Ensuite, la relecture est facultative : GitHub demande le binôme du domaine, listé dans `.github/CODEOWNERS`, mais la PR n'attend pas son approbation. Dès que la CI est verte, elle se fusionne toute seule, l'issue se ferme et la tâche passe en « Acquise ». Si le développeur veut un avis avant la fusion, propose les mêmes commandes sans `gh pr merge --auto --squash` : il fusionnera lui-même après la relecture.

### Le message de commit et la description de PR

- Première ligne : `<type>(<scope>): <description>`, en anglais, à l'impératif, sans point final, 72 caractères au plus.
- Un paragraphe qui dit pourquoi.
- Un paragraphe `Test plan:` qui dit comment le changement a été vérifié, et sur quel appareil.
- Dernière ligne : `Closes NotitiaEIP/Notitia_Mobile#<numéro de l'issue>`.

Types : `feat`, `fix`, `docs`, `style`, `refactor`, `perf`, `test`, `build`, `ci`, `chore`, `revert`.

Le scope est facultatif. S'il y en a un, il vient de cette liste :

| Scope | Pour |
| --- | --- |
| `audio` | Enregistrement, micro, fichiers audio |
| `transcription` | Passage de l'audio au texte, séparation des locuteurs |
| `minutes` | Compte rendu : décisions, actions, responsables |
| `search` | Recherche dans les réunions |
| `ui` | Écrans et composants d'interface |
| `storage` | Base de données, fichiers, chiffrement, suppression après 30 jours |
| `ai` | Chargement et exécution des modèles |
| `dependencies` | Versions dans `mobile/gradle/libs.versions.toml` |
| `android` | Code propre à Android qui ne relève d'aucun autre scope |
| `ios` | Code propre à iOS qui ne relève d'aucun autre scope |

Un `!` après le type ou le scope signale une rupture, par exemple un format de données qui change : `feat(storage)!: store speakers per meeting`.

Le titre de la PR reprend la première ligne, et sa description le reste du message, sans checklist ni autre section : au moment du squash, c'est ce titre et cette description qui deviennent le commit sur `main`. Les captures d'écran vont dans un commentaire de la PR. La CI vérifie le titre et la description de chaque PR et signale ce qui manque.

### Mettre la branche à jour avec main

Avant de proposer le push, et chaque fois que GitHub indique que la branche n'est plus à jour :

```bash
git fetch origin
git merge --no-commit origin/main
```

- En cas de conflit, résous-le fichier par fichier. Pour chacun, explique au développeur ce que chaque côté changeait et ce que tu as gardé.
- Si les deux côtés changent la logique du même code, montre les deux versions et demande avant de choisir. Propose au besoin de demander à l'auteur de l'autre changement.
- Relance le build, puis propose `git add <fichiers>` et `git commit --no-edit`. Le message par défaut de Git suffit : ce commit disparaît au squash.

### Si le relecteur demande des changements

Si la PR est encore ouverte, repasse la tâche en « In progress », corrige sur la même branche, puis propose un nouveau `git commit` et un `git push`. Pas de nouvelle PR : la PR existante se met à jour. Si elle est déjà fusionnée, la correction devient une nouvelle issue, avec sa branche et sa PR.

## Publier une version

Seulement quand le développeur le demande.

1. Vérifie que la CI est verte sur `main`.
2. Trouve le dernier tag de la plateforme (`git describe --tags --abbrev=0 --match "android-v*" origin/main`) et liste les commits depuis (`git log <tag>..origin/main --oneline`).
3. Propose le numéro : correctif +1 s'il n'y a que des `fix`, mineur +1 s'il y a au moins un `feat`.
4. Propose les commandes, sans les lancer :

   ```bash
   git switch main
   git pull
   git tag -a android-v0.2.0 -m "android 0.2.0"
   git push origin android-v0.2.0
   ```

Préfixes de tag : `android-v` et `ios-v`.

## Le GitHub Project

Tout le travail de l'équipe vit dans le Project [Notitia](https://github.com/orgs/NotitiaEIP/projects/10) (numéro 10) : une issue par tâche, rangée sous son epic, avec ses champs. Une issue créée dans ce repo y entre toute seule, au statut « Todo ».

| Champ | Valeurs |
| --- | --- |
| Status | Todo, In progress, Need review, Acquise |
| Plateforme | Android, iOS, Commun, Ordinateur, IA, Hors code |
| Priorité | P0 indispensable au jalon, P1 important, P2 si le temps le permet |
| Terrain | Validé terrain, Hypothèse, À décider, Remis en cause |
| Estimation (j/h), Difficulté, Réel (j/h) | Des nombres : jours-homme, difficulté de 1 à 5 |
| Sprint | Deux semaines, du lundi au dimanche |

Les epics et leur responsable :

| Epic | Issue | Responsable |
| --- | --- | --- |
| E1 Enregistrer d'un geste sur le téléphone | #5 | @nathanderenty |
| E2 Transcrire sur l'appareil et savoir qui parle | #6 | @Lucasljn |
| E3 Compte rendu | #7 | @AdamLbrz |
| E4 Envoyer le compte rendu | #8 | @AdamLbrz |
| E5 Suivre les actions | #9 | @clembdn |
| E6 Retrouver une info | #10 | @Alexou52 |
| E7 Capter les visios sur l'ordinateur | #11 | @sadikamine1 |
| E8 Mes données | #12 | @nathanderenty |
| E9 Modèles maison | #13 | @Alexou52 |
| E10 Retrouver mes réunions sur tous mes appareils | #14 | @nathanderenty |
| E11 Notitia pour les organisations | #15 | @sadikamine1 |
| Socle technique | #16 | @clembdn |
| Business et rendus EIP | #17 | @clembdn |

Les identifiants dont `gh` a besoin :

| Quoi | Identifiant |
| --- | --- |
| Project | `PVT_kwDOD4mZuM4BofzF` |
| Champ Status | `PVTSSF_lADOD4mZuM4BofzFzhm_qII` |
| Todo | `162fef56` |
| In progress | `09676934` |
| Need review | `9e1f8211` |
| Acquise | `b659614a` |
| Champ Réel (j/h) | `PVTF_lADOD4mZuM4BofzFzhm_qM4` |

Les autres champs et leurs options : `gh project field-list 10 --owner NotitiaEIP --format json`.

Retrouver l'item d'une issue (ici la 12), puis changer son statut ou remplir le temps réel :

```bash
gh project item-list 10 --owner NotitiaEIP --format json --limit 1000 --jq '.items[] | select(.content.number == 12) | .id'
gh project item-edit --project-id PVT_kwDOD4mZuM4BofzF --id <id de l'item> --field-id PVTSSF_lADOD4mZuM4BofzFzhm_qII --single-select-option-id 09676934
gh project item-edit --project-id PVT_kwDOD4mZuM4BofzF --id <id de l'item> --field-id PVTF_lADOD4mZuM4BofzFzhm_qM4 --number 1.5
```

Ranger une nouvelle issue sous son epic (ici la 12 sous l'epic 5) :

```bash
gh api -X POST repos/NotitiaEIP/notitia_mobile/issues/5/sub_issues -F sub_issue_id=$(gh api repos/NotitiaEIP/notitia_mobile/issues/12 --jq .id)
```

Il faut `gh` connecté avec le droit `project` (`gh auth refresh -s project`). Si une commande échoue, dis-le au développeur au lieu de contourner.
