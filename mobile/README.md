# Notitia Mobile

L'app Android et iOS de Notitia : elle enregistre une réunion, la transcrit et en tire un compte rendu, sans que rien ne sorte du téléphone.

## Lancer le projet

À la racine du repo, ou dans `mobile/`, une seule commande :

```bash
./notitia
```

Un menu s'ouvre. Choisis avec les flèches ↑ ↓, valide avec Entrée.

Sous Windows, lance-la dans **Git Bash** (installé avec Git), pas dans PowerShell.

### Quelle option choisir

| Ta situation | Option | Ce qu'il te faut |
| --- | --- | --- |
| Première fois, ou quelque chose bloque | Diagnostiquer mon installation | Rien : elle te dit ce qui manque |
| Tu veux voir l'app sur ton téléphone Android | Lancer sur mon téléphone Android | Un câble USB et le débogage USB activé (voir plus bas) |
| Tu codes sans téléphone sous la main | Lancer sur un émulateur Android | Un appareil virtuel créé dans Android Studio (Tools > Device Manager) |
| Tu dois envoyer l'app à un testeur | Construire l'APK | Rien de plus. Le testeur installe le fichier .apk reçu |
| Tu es sur Mac et veux tester sur iPhone | Ouvrir le projet iOS dans Xcode | Xcode et un JDK 21 |
| Tu vas ouvrir une PR | Vérifier avant une PR | Rien de plus : c'est la même vérification que la CI |

### Première installation (une seule fois)

1. Installe [Android Studio](https://developer.android.com/studio). Il apporte Java et le SDK Android.
2. Clone le repo, puis ouvre le dossier `mobile/` une fois dans Android Studio pour qu'il télécharge le SDK :

   ```bash
   git clone https://github.com/NotitiaEIP/Notitia_Mobile.git
   cd Notitia_Mobile
   ```

3. Lance `./notitia` et choisis « Diagnostiquer mon installation ».

### Activer le débogage USB (une fois par téléphone)

1. Paramètres > À propos du téléphone : touche 7 fois « Numéro de build ». Le chemin change un peu selon la marque.
2. Paramètres > Système > Options pour les développeurs : active « Débogage USB ».
3. Branche le téléphone et accepte la fenêtre « Autoriser le débogage USB ».

### Sans le menu

```bash
./notitia phone      # téléphone Android branché
./notitia emulator   # émulateur Android
./notitia apk        # fichier .apk
./notitia ios        # Xcode (Mac)
./notitia check      # build et tests, comme la CI
./notitia doctor     # diagnostic
```

## Organisation du code

Ces dossiers sont dans `mobile/`.

- `shared/` : le code partagé entre Android et iOS (écrans, logique, données). `src/commonMain` pour ce qui est commun, `src/androidMain` et `src/iosMain` pour ce qui dépend de la plateforme.
- `androidApp/` : le point d'entrée Android.
- `iosApp/` : le projet Xcode, point d'entrée iOS.

Les règles du projet (produit, code, Git) sont dans [AGENTS.md](../AGENTS.md).
