# Confidentialité, hors ligne et RGPD

> Document de travail de l'équipe. **Ce n'est pas un avis juridique** : il devra être relu
> (référent RGPD d'Epitech ou juriste) avant toute diffusion publique de l'application.

## 1. Engagements

1. **Hors ligne par construction.** L'application macOS est sandboxée **sans** l'autorisation
   `com.apple.security.network.client` : le système lui interdit toute connexion sortante.
   Aucune fonctionnalité ne dépend d'Internet.
2. **Aucune collecte.** Pas de compte, pas d'analytics, pas de télémétrie, pas de SDK tiers.
   Le manifeste `PrivacyInfo.xcprivacy` le déclare.
3. **Les données restent sur l'appareil**, dans le conteneur privé de l'application.
4. **L'audio est chiffré au repos.**
5. **L'utilisateur garde le contrôle** : export complet, suppression ciblée, effacement total.

## 2. Registre des traitements (art. 30)

Notitia fonctionne entièrement en local. L'éditeur du logiciel ne reçoit **aucune** donnée :
l'utilisateur est seul maître de ses données. Ce registre décrit ce que fait le logiciel, pour transparence.

| Traitement | Données | Finalité | Base légale (pour l'utilisateur qui enregistre) | Conservation | Destinataires |
|---|---|---|---|---|---|
| Prise de notes | Texte, images | Organisation personnelle | Usage personnel | Jusqu'à suppression (corbeille : 30 j) | Aucun |
| Enregistrement de réunion | **Voix des participants** (audio) | Transcription | Consentement / intérêt légitime, **avec information préalable des participants** | Réglable : 7 j, 30 j, 3 mois, 1 an ou manuel | Aucun |
| Transcription | Texte transcrit, noms de locuteurs | Notes de réunion | idem | Jusqu'à suppression (survit à l'audio) | Aucun |
| Résumé, carte mentale | Dérivés du texte | Synthèse | idem | Comme la note | Aucun (traitement local) |

**Sous-traitants : aucun.** Si un moteur cloud est ajouté (voir §5), ce tableau devra être mis à jour.

## 3. Mesures techniques

| Mesure | Mise en œuvre | Code |
|---|---|---|
| Pas d'accès réseau | Entitlements sans `network.client` | `apple/Config/Notitia.entitlements` |
| Chiffrement de l'audio | AES-256-GCM (CryptoKit). Clé aléatoire stockée dans le trousseau, `WhenUnlockedThisDeviceOnly` (ni iCloud, ni sauvegarde) | `NotitiaKit/Privacy/AudioVault.swift`, `KeyStore.swift` |
| Fichier en clair éphémère | L'enregistrement est écrit en clair dans le dossier temporaire, chiffré à l'arrêt puis supprimé. La lecture déchiffre dans un fichier temporaire supprimé à l'arrêt | `RecordingController`, `AudioPlaybackController` |
| Consentement | Feuille de rappel avant chaque enregistrement ; horodatage stocké (`Recording.consentConfirmedAt`) | `RecordingViews.swift` |
| Indicateur d'enregistrement | Barre d'enregistrement toujours visible, et indicateur micro du système | `RecordingBar` |
| Limitation de conservation | Purge auto de la corbeille (30 j) et de l'audio expiré, au lancement puis toutes les 6 h | `NotitiaStore.runMaintenance`, `RetentionPolicy` |
| Suppression réelle | Supprimer une note supprime ses enregistrements, leur audio et ses images | `NotitiaStore.deletePermanently` |
| Outils d'écriture Apple | Désactivés dans l'éditeur (ils peuvent utiliser le Private Cloud Compute d'Apple) | `BlockTextView.make()` |
| Aucun secret dans le code | Les futures clés éventuelles iront dans le trousseau | — |

Les images insérées dans les notes ne sont pas chiffrées par l'application (elles bénéficient du sandbox et
de FileVault). À réévaluer si des documents sensibles sont visés.

## 4. Droits de l'utilisateur (Réglages › Confidentialité)

| Droit | Implémentation |
|---|---|
| Accès et portabilité (art. 15, 20) | **Exporter toutes mes données** : `notitia-export.json` (format structuré), notes en Markdown rangées par dossier, audio déchiffré, images, `LISEZMOI.txt` |
| Effacement (art. 17) | Suppression d'un audio (texte conservé), d'un enregistrement, d'une note, ou **Effacer toutes mes données** (base, audio, images **et clé de chiffrement**) |
| Information (art. 13) | Panneau Confidentialité : ce qui est stocké, où, combien de temps, espace utilisé |
| Limitation | Réglage de la durée de conservation de l'audio |

Le droit à l'information des **participants** enregistrés relève de l'utilisateur qui enregistre :
l'application le lui rappelle avant chaque enregistrement.

## 5. Règles pour toute évolution

Avant d'ajouter une fonctionnalité qui envoie des données hors de l'appareil (moteur cloud, synchronisation) :

- [ ] Elle est **désactivée par défaut** et activée par un **consentement explicite** (opt-in), révocable.
- [ ] L'écran d'activation nomme le sous-traitant, son pays d'hébergement (**UE en priorité**) et les données envoyées.
- [ ] Un contrat de sous-traitance (DPA, art. 28) existe ; le registre (§2) est mis à jour.
- [ ] L'option `network.client` n'est ajoutée qu'à ce moment-là, et l'application reste utilisable hors ligne.
- [ ] Pour une synchronisation : chiffrement de bout en bout envisagé, suppression propagée au serveur.
- [ ] Les secrets éventuels sont dans le trousseau, jamais dans le dépôt.

## 6. Ébauche de politique de confidentialité

> **Notitia ne collecte aucune donnée.** Vos notes, transcriptions et enregistrements sont stockés
> uniquement sur votre appareil. L'application ne se connecte pas à Internet. Les enregistrements audio
> sont chiffrés. Vous pouvez à tout moment exporter l'intégralité de vos données ou les effacer
> définitivement depuis Réglages › Confidentialité. Si vous enregistrez une conversation, vous devez
> en informer les participants et obtenir leur accord.

## 7. Héritage de l'ancienne application

L'application Flutter contenait des **clés d'API en clair dans le code, versionnées dans git**
(Anthropic, Google Gemini ×2, Mistral, Deepgram). Elles doivent être **révoquées** chez chaque fournisseur
et considérées comme compromises, même si le code est abandonné : l'historique git les conserve.
