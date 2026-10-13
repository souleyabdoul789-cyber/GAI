# GAI — assistant vocal hors ligne

GAI est une application Android Kotlin + Jetpack Compose avec une couche ViewModel/StateFlow, structurée autour de moteurs séparés pour la voix, les fichiers, le mapping d'intentions et les actions Android. L'interface reprend une palette crème/blanc-lait et brun doux associée aux signatures G-SOCIETY et GRIND. Le logo GAI est distinct ; faute d'un fichier officiel G-SOCIETY fourni, le projet contient une marque cérébrale vectorielle de substitution.

## Fonctionnalités livrées

- **STT français local** avec Vosk (`vosk-model-small-fr-0.22`) ; aucun service de reconnaissance vocale Google.
- **TTS Android** uniquement si une voix française marquée comme non dépendante du réseau est installée. GAI ne bascule pas vers une voix distante.
- **Lecture locale** de TXT, CSV, JSON, PDF, DOCX et images (OCR ML Kit embarqué). Le résumé vocal est extractif et heuristique : ce n'est pas un LLM ni un résumé sémantique génératif.
- **Commandes françaises** : ouvrir une application, appeler un contact, préparer un SMS ou un partage WhatsApp, lire un fichier.
- **Actions encadrées par Android** : demande les permissions nécessaires, et l'utilisateur confirme l'envoi dans l'application SMS/WhatsApp. L'appel utilise `ACTION_CALL` après permission.
- **Pas de permission Internet** : le manifeste retire aussi une éventuelle permission Internet apportée par une dépendance transitive. Le modèle vocal est acquis pendant le build puis intégré à l'APK ; l'application installée ne le télécharge jamais.
- **Compatibilité visée** : Android 8.0 (API 26) ou plus, ABIs ARM 32/64 bits. Le modèle français fait environ 41 Mo et Vosk peut occuper plusieurs centaines de Mo en mémoire pendant l'écoute.

## Build APK debug avec GitHub Actions

1. Crée un dépôt GitHub et envoie-y le contenu de ce dossier.
2. Ouvre l'onglet **Actions** et lance **Construire l'APK debug GAI** (ou pousse un commit).
3. Télécharge l'artefact **GAI-debug-apk** ; il contient `app-debug.apk`.

Le workflow installe Java 17 et le SDK Android, télécharge le modèle officiel Vosk français pendant la construction, copie son contenu dans `app/src/main/assets/model/`, puis lance `:app:assembleDebug`.

## Build local

```bash
./gradlew :app:assembleDebug
```

Pour obtenir une reconnaissance vocale fonctionnelle dans un build local, place le contenu décompressé du modèle dans `app/src/main/assets/model/` de sorte que le chemin `app/src/main/assets/model/am/final.mdl` existe avant de compiler. Le workflow GitHub fait cette étape automatiquement.

Modèle français officiel :
`https://alphacephei.com/vosk/models/vosk-model-small-fr-0.22.zip`

Liste et licences des modèles Vosk :
`https://alphacephei.com/vosk/models`

## Accès aux fichiers sur Android

- **Android 8 à 12** : GAI peut rechercher dans le catalogue Téléchargements après autorisation facultative de lecture du stockage partagé.
- **Android 13 et plus** : Android limite l'accès direct aux documents partagés. Sélectionne une fois le document avec **Ajouter un fichier** ; l'URI autorisée est mémorisée, puis une commande comme « Lis mon fichier contrat.pdf » peut le retrouver sans redemander le fichier.

Ce choix évite de demander un accès global à tous les fichiers. Les fichiers ne sont pas envoyés vers un serveur.

## Voix française hors ligne

La synthèse dépend du moteur TTS fourni par le téléphone. Dans les paramètres Android, installe une voix française hors ligne avant de couper le réseau. Si aucune voix locale n'est disponible, GAI garde le texte à l'écran et n'essaie pas d'utiliser une voix réseau.

## Arborescence importante

```text
app/src/main/java/com/muryar/app/
├── MainActivity.kt
├── GaiViewModel.kt
├── VoiceEngine.kt
├── FileReaderEngine.kt
├── IntentManager.kt
└── LanguageMapper.kt
.github/workflows/build.yml
```

## Sécurité et limites fonctionnelles

- Le message est prérempli mais n'est jamais envoyé sans validation explicite de l'utilisateur.
- La correspondance des commandes repose sur des règles et mots-clés français, pas sur un modèle génératif embarqué.
- Les PDF chiffrés, documents endommagés et images de mauvaise qualité peuvent ne pas être lisibles.
- Le logo fourni pour G-SOCIETY est un signe vectoriel de substitution ; remplace `ic_gsociety_mark.xml` par le logo officiel autorisé si tu disposes du fichier source.
