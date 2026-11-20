# ADR 0003 : le format `.notitia` version 2

| | |
| --- | --- |
| Statut | Proposée le 16 novembre 2026, acceptée à la fusion de sa PR. Règle 6 remplacée par l'[ADR 0008](0008-identifiants.md) le 20 novembre 2026 |
| Proposée par | Clément |
| Relue par | Alexandre |
| Tâche d'origine | T-73 (#225), première étape de T-57 (#188) |

## Contexte

Un fichier `.notitia` contient une réunion. C'est ce que le téléphone garde sur son disque, ce que l'utilisateur enverra à son ordinateur, et ce que deux appareils reliés s'échangeront. Le cœur Rust est le seul à le lire et à l'écrire (ADR 0002).

La version 1 a été écrite par le code Kotlin, et elle en garde la forme :

- une action est connue par son rang dans le compte rendu : le jour où une action est retirée, les rappels pointent sur la mauvaise ;
- une durée s'écrit `PT1H5M4.062500S`, ce que ni Swift ni JavaScript ne lisent seuls ;
- une app qui réécrit un fichier efface les champs qu'elle ne connaît pas.

Aucune version publique de l'app n'a écrit de fichier : c'est le moment le moins cher pour changer.

## Décision

```json
{
  "version": 2,
  "meeting": {
    "id": "recorded-1",
    "startedAt": "2026-11-10T09:30:00Z",
    "durationMs": 2520000,
    "modifiedAt": "2026-11-10T10:15:00Z",
    "device": "phone-1",
    "title": "Revue de sprint",
    "speakers": ["Lucas", "Nathan"],
    "minutes": {
      "type": "written",
      "minutes": {
        "summary": "Point sur la démo.",
        "decisions": ["La démo a lieu le 15 novembre."],
        "actions": [
          {
            "id": "0",
            "text": "Préparer la démo",
            "owner": "Lucas",
            "due": "2026-11-14",
            "done": true,
            "reminder": false,
            "source": { "quote": "Je prépare la démo.", "atMs": 724000 }
          }
        ]
      }
    },
    "transcript": [
      { "atMs": 0, "text": "Bonjour à tous." },
      { "atMs": 30000 }
    ]
  }
}
```

Les règles du format :

1. **Un fichier, une réunion.** Du JSON en UTF-8. L'enveloppe ne porte que `version` et `meeting`.
2. **La version ne monte que pour une rupture** : un changement qu'une app plus ancienne lirait de travers. Une app refuse un fichier d'une version plus récente et le laisse sur le disque.
3. **Ajouter un champ ne monte pas la version.** Une app garde les champs qu'elle ne connaît pas et les réécrit tels quels, dans la réunion, le compte rendu, une action, sa source et une ligne de transcription.
4. **Un champ absent et un champ `null` disent la même chose.** Le cœur n'écrit pas les champs vides.
5. **Le temps a trois formes.** Un instant en RFC 3339, toujours en UTC. Une durée ou une position dans l'audio en millisecondes entières (`durationMs`, `atMs`). Une échéance en date civile, `2026-11-14`, la même dans tous les fuseaux.
6. **Un identifiant ne contient que des lettres sans accent, des chiffres, `-` et `_`**, 64 caractères au plus. Celui de la réunion donne son nom au fichier : un fichier reçu d'un autre appareil ne peut donc pas écrire hors du dossier.
7. **Chaque action porte un identifiant**, tiré au hasard (UUID) à sa création. Les rappels et les écrans la suivent par lui.
8. **Une ligne de transcription peut porter un locuteur** (`speaker`). Personne ne le remplit avant la séparation des voix (E2).

Lire la version 1 : la réunion garde son identifiant, et chaque action prend son rang comme identifiant (`"0"`, `"1"`). Un rappel déjà programmé sur un téléphone, dont la clé est `recorded-123/0`, reste donc valable sans migration. Le fichier n'est pas réécrit au lancement : il passe en version 2 à sa prochaine modification.

Le fichier `core/tests/fixtures/meeting-v2.notitia` est la référence : le cœur l'écrit à l'octet près, et les autres apps le relisent dans leurs tests.

## Options écartées

| Option | Pourquoi pas |
| --- | --- |
| Garder les durées ISO 8601 | Seul Kotlin les lit sans aide. L'app Mac et l'interface web auraient chacune écrit leur lecteur |
| Convertir tous les fichiers au premier lancement | Une écriture en masse au démarrage, pour des identifiants qui restent valables tels quels |
| Donner un UUID aux réunions de la version 1 | Le fichier changerait de nom et les rappels programmés perdraient leur réunion |
| Un format binaire | Illisible pour qui cherche une panne, et le pont entre le cœur et les écrans parle déjà JSON |
| Sortir le compte rendu de son enveloppe `minutes.minutes` | Plus joli, mais le modèle des écrans la suit déjà : un changement sans gain pour l'utilisateur |

## Conséquences

**Ce qu'on gagne.** Un rappel suit son action même quand le compte rendu change. Le Mac et l'ordinateur lisent le fichier sans rien traduire. Une app en retard d'une version ne détruit pas ce qu'une app plus récente a écrit.

**Ce qu'on paie.** Le cœur garde un lecteur de la version 1, avec les durées de Kotlin. Une app d'avant le cœur ne lit pas les fichiers de la version 2 : elle les laisse sur le disque sans les afficher.

**Ce qui reste ouvert.** Les nouvelles réunions gardent un identifiant `recorded-<millisecondes>` jusqu'à T-58, où le cœur crée la réunion et lui tire un UUID. Le chiffrement du fichier (US-81) et la fusion de deux versions d'une même réunion (E10) auront leur propre fiche.
