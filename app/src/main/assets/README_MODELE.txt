Le modèle Vosk français n'est pas stocké dans le dépôt pour éviter un dépôt Git lourd.
Le workflow .github/workflows/build.yml télécharge le modèle officiel à la compilation
et le copie dans assets/model/. L'application ne télécharge jamais ce modèle depuis le téléphone.
