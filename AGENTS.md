# AGENTS.md

Instructions pour toute IA qui code dans ce repo : Claude Code, GitHub Copilot, Antigravity ou une autre. Le développeur reste l'auteur de chaque commit et de chaque pull request.

## Le projet

Notitia est une app mobile (Android et iOS) qui enregistre une réunion, la transcrit et en tire un compte rendu : décisions, actions et responsables. Tout se passe sur le téléphone. C'est l'EIP d'une équipe d'Epitech Nice (promo 2028). Les autres briques vivent dans l'organisation GitHub NotitiaEIP (`Notitia-macOS`, `Notitia_Documentation`...).

## Règles produit non négociables

1. **Rien ne sort du téléphone.** Aucun appel réseau ne transporte d'audio, de transcription, de compte rendu ou de métadonnées de réunion. Pas de SDK d'analytics ou de crash reporting qui verrait ce contenu. Seule exception prévue : une gateway de secours, désactivée par défaut, activée explicitement par l'utilisateur, et jamais pour une organisation de santé. Toute exception passe par une PR dédiée.
2. **Aucun secret dans le code** : pas de clé d'API, de token ni de mot de passe en dur, pas de `local.properties` ou de `.env` commité.
3. **Pas de sauvegarde cloud des réunions** : données exclues de la sauvegarde Android (`dataExtractionRules`) et d'iCloud (`isExcludedFromBackup`). Stockage local chiffré.
4. **Ni empreinte vocale persistante, ni analyse d'émotion** (RGPD article 9, AI Act article 5). Les locuteurs sont séparés réunion par réunion et l'utilisateur les renomme.
5. **Audio compressé (jamais de WAV stocké), gardé 30 jours par défaut**, puis supprimé. La transcription et le compte rendu restent.

En cas de doute sur une de ces règles, arrête-toi et demande plutôt que deviner.

## Stack

- Kotlin Multiplatform et Compose Multiplatform : l'interface est partagée entre Android et iOS.
- Gradle 9.4.0 via le wrapper (`./gradlew`). Les versions des dépendances vivent uniquement dans `gradle/libs.versions.toml`.
- Android Studio avec le plugin Kotlin Multiplatform. Xcode sur Mac pour iOS.

## Organisation du code

Les modules sont listés dans `settings.gradle.kts`. Dans chacun :

- `src/commonMain` : tout ce qui peut être partagé (écrans, logique, modèles de données, accès aux modèles IA).
- `src/androidMain` et `src/iosMain` : seulement ce qui dépend de la plateforme (micro, enregistrement en arrière-plan, widget, exécution des modèles).
- `iosApp/` : le projet Xcode qui embarque le code partagé.

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

- Compiler et tester : `./gradlew build`
- APK de debug : `./gradlew assembleDebug`
- Installer sur le téléphone branché : `./gradlew installDebug`
- iOS : ouvrir `iosApp/iosApp.xcodeproj` dans Xcode, sur un Mac. Sous Linux et Windows, les cibles iOS ne compilent pas : c'est normal, ne cherche pas à « réparer » une erreur iOS depuis ces machines.

## Conventions

- Code, noms, commentaires et messages de commit en anglais. Textes de l'interface en français et en anglais, toujours dans les ressources Compose (`composeResources`), jamais en dur.
- Commits au format Conventional Commits : `<type>(<scope>): <description>`, avec les types `feat`, `fix`, `docs`, `style`, `refactor`, `perf`, `test`, `build`, `ci`, `chore`, `revert`. Une PR traite un seul sujet et se fusionne en squash.
- La logique métier vit dans `commonMain` et se teste dans `commonTest`.
- Une nouvelle dépendance passe par `gradle/libs.versions.toml`, avec une licence vérifiée (Apache 2.0, MIT ou BSD de préférence).
- Jamais de fichier de modèle IA dans le repo (`.gguf`, `.onnx`, `.tflite`, `.bin`...). Les modèles sont livrés par les stores (Play for On-device AI sur Android, Background Assets sur iOS).
- Monter Gradle, AGP, Kotlin ou Compose se fait dans une PR à part.

## Ce que tu fais, ce que tu proposes

| Action | Qui |
| --- | --- |
| Lire le code, les issues, les PR et le GitHub Project | Toi |
| Modifier des fichiers, lancer le build et les tests | Toi |
| Créer une branche locale | Toi |
| Changer le statut d'une tâche dans le GitHub Project | Toi |
| `git commit`, `git push`, `git tag`, `gh pr create`, `gh pr merge` | Tu écris la commande, le développeur la lance |
| Approuver une PR, fermer une issue | Un humain, jamais toi |
| Travailler directement sur `main` | Personne |

## Aucune trace d'IA

Ni `Co-Authored-By`, ni « Generated with », ni mention d'une IA dans un message de commit, un titre ou une description de PR, le code ou un commentaire. Aucun autre fichier d'instructions pour IA que celui-ci et `CLAUDE.md`.

## Le cycle d'une tâche

Une tâche = une issue = une branche = une PR. Si la tâche dépasse deux ou trois jours de travail ou environ 400 lignes modifiées, propose de la découper en plusieurs issues avant de commencer.

### 1. Démarrer

1. Si le développeur ne dit pas sur quoi il travaille, liste ses tâches au statut « Todo » dans le GitHub Project et laisse-le choisir.
2. Lis l'issue (`gh issue view 12`). Résume en trois lignes le besoin et les critères d'acceptation, puis attends son accord.
3. Passe la tâche en « In progress » (voir « Changer un statut »).
4. Crée la branche depuis `main` à jour, au format `<type>/<numéro>-<description-courte>` :

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
2. Lance `./gradlew build`. S'il échoue, corrige ou explique pourquoi, et ne propose rien d'autre.
3. Rappelle la checklist : aucune clé ni token, aucun fichier de modèle IA, aucun contenu de réunion envoyé sur le réseau, textes d'interface en français et en anglais dans les ressources, testé sur un vrai téléphone si la tâche touche l'audio, l'arrière-plan ou l'IA.
4. Propose les commandes dans un seul bloc, sans les lancer. Nomme les fichiers un par un dans `git add`. Adapte les guillemets au terminal du développeur (bash, zsh ou PowerShell).

   ```bash
   git add <module>/src/androidMain/kotlin/<package>/audio/AndroidAudioRecorder.kt
   git commit -m "feat(audio): keep recording when the screen is locked" -m "Android stopped the recorder after ten minutes with the screen off. It now runs in a foreground service of type microphone." -m "Test plan: 45-minute recording on a Pixel 7 with the screen locked, the file plays to the end." -m "Closes NotitiaEIP/Notitia_Mobile#12"
   git push -u origin feat/12-record-lock-screen
   gh pr create --base main --title "feat(audio): keep recording when the screen is locked" --body "Android stopped the recorder after ten minutes with the screen off. It now runs in a foreground service of type microphone.

   Test plan: 45-minute recording on a Pixel 7 with the screen locked, the file plays to the end.

   Closes NotitiaEIP/Notitia_Mobile#12"
   gh pr merge --auto --squash
   ```

5. Quand le développeur confirme que la PR est ouverte, vérifie qu'elle est liée à l'issue (`gh pr view --json closingIssuesReferences`) et passe la tâche en « Need review ».

Ensuite, un humain relit. Quand il approuve et que la CI est verte, la PR se fusionne toute seule, l'issue se ferme et la tâche passe en « Acquise ».

### Le message de commit et la description de PR

- Première ligne : Conventional Commits, en anglais, 72 caractères au plus.
- Un paragraphe qui dit pourquoi.
- Un paragraphe `Test plan:` qui dit comment le changement a été vérifié, et sur quel appareil.
- Dernière ligne : `Closes NotitiaEIP/Notitia_Mobile#<numéro de l'issue>`.

La description de la PR reprend le même texte, car c'est elle qui devient le commit sur `main` au moment du squash.

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

Repasse la tâche en « In progress », corrige sur la même branche, puis propose un nouveau `git commit` et un `git push`. Pas de nouvelle PR : la PR existante se met à jour.

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

## Changer un statut dans le GitHub Project

<!-- Numéro du Project et identifiants à remplir quand le Project sera créé. -->

Statuts : Todo, In progress, Need review, Acquise.

```bash
gh project item-list <numéro> --owner NotitiaEIP --format json
gh project item-edit --project-id <id du project> --id <id de l'item> --field-id <id du champ Status> --single-select-option-id <id du statut>
```

Il faut `gh` connecté avec le droit `project` (`gh auth refresh -s project`). Si la commande échoue, dis-le au développeur au lieu de contourner.
