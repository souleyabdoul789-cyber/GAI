package com.muryar.app

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.collect

private val Brun = Color(0xFF765744)
private val BrunProfond = Color(0xFF392B23)
private val Lait = Color(0xFFF4EEE5)
private val BlancChaud = Color(0xFFFFFCF8)
private val Taupe = Color(0xFFA98970)
private val TexteSecondaire = Color(0xFF796A5F)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(244, 238, 229)
        window.navigationBarColor = android.graphics.Color.rgb(244, 238, 229)
        setContent {
            GaiTheme {
                GaiScreen()
            }
        }
    }
}

@Composable
private fun GaiTheme(contenu: @Composable () -> Unit) {
    val couleurs = lightColorScheme(
        primary = Brun,
        onPrimary = BlancChaud,
        secondary = Taupe,
        onSecondary = BrunProfond,
        background = Lait,
        onBackground = BrunProfond,
        surface = BlancChaud,
        onSurface = BrunProfond,
        surfaceVariant = Color(0xFFE9DED2),
        onSurfaceVariant = TexteSecondaire
    )
    MaterialTheme(colorScheme = couleurs, content = contenu)
}

@Composable
private fun GaiScreen() {
    val contexte = LocalContext.current
    val viewModel: GaiViewModel = viewModel()
    val etat by viewModel.uiState.collectAsStateWithLifecycle()
    var commandeEnAttente by remember { mutableStateOf<AssistantCommand?>(null) }

    val permissionsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { resultat ->
        val action = commandeEnAttente
        commandeEnAttente = null
        if (action != null && resultat.values.all { it }) {
            viewModel.executerAction(action)
        } else if (action != null) {
            viewModel.definirStatut("Autorisation refusée. L'action n'a pas été exécutée.")
        }
    }

    val permissionMicroLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { autorise ->
        viewModel.definirStatut(
            if (autorise) {
                "Microphone autorisé. Maintiens le bouton vocal pour commencer."
            } else {
                "Le microphone est nécessaire pour la commande vocale."
            }
        )
    }

    val choixFichierLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                contexte.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            viewModel.ajouterFichier(uri)
        }
    }

    fun permissionsNecessaires(action: AssistantCommand): Array<String> {
        val aDemander = mutableListOf<String>()
        val demandeContacts = action is AssistantCommand.CallContact || action is AssistantCommand.SendMessage
        if (demandeContacts && ContextCompat.checkSelfPermission(
                contexte, Manifest.permission.READ_CONTACTS
            ) != PackageManager.PERMISSION_GRANTED
        ) aDemander += Manifest.permission.READ_CONTACTS

        if (action is AssistantCommand.CallContact && ContextCompat.checkSelfPermission(
                contexte, Manifest.permission.CALL_PHONE
            ) != PackageManager.PERMISSION_GRANTED
        ) aDemander += Manifest.permission.CALL_PHONE

        if (action is AssistantCommand.ReadFile && Build.VERSION.SDK_INT <= 32 &&
            !viewModel.estFichierMemorise(action.nomFichier) &&
            ContextCompat.checkSelfPermission(contexte, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
        ) aDemander += Manifest.permission.READ_EXTERNAL_STORAGE

        return aDemander.toTypedArray()
    }

    fun executerAction(action: AssistantCommand) {
        val manquantes = permissionsNecessaires(action)
        if (manquantes.isNotEmpty()) {
            commandeEnAttente = action
            permissionsLauncher.launch(manquantes)
            return
        }
        viewModel.executerAction(action)
    }

    LaunchedEffect(viewModel) {
        viewModel.commandes.collect { action -> executerAction(action) }
    }

    val demarrerEcoute = {
        if (ContextCompat.checkSelfPermission(contexte, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionMicroLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            viewModel.demarrerEcoute()
        }
    }
    val arreterEcoute = { viewModel.arreterEcoute() }
    val demarrerMisAJour = rememberUpdatedState(demarrerEcoute)
    val arreterMisAJour = rememberUpdatedState(arreterEcoute)

    Surface(modifier = Modifier.fillMaxSize(), color = Lait) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            EnteteGai()

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = BlancChaud),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Ta voix. Ton téléphone. Tes règles.",
                        fontSize = 21.sp,
                        lineHeight = 27.sp,
                        fontWeight = FontWeight.Bold,
                        color = BrunProfond
                    )
                    Spacer(Modifier.height(7.dp))
                    Text(
                        text = "Un assistant local, sans compte et sans connexion.",
                        color = TexteSecondaire,
                        fontSize = 14.sp
                    )
                    Spacer(Modifier.height(24.dp))

                    val couleurBouton by animateColorAsState(
                        targetValue = if (etat.enEcoute) Color(0xFF4F392B) else Brun,
                        animationSpec = spring(stiffness = 300f),
                        label = "couleur_bouton_vocal"
                    )
                    val echelle by animateFloatAsState(
                        targetValue = if (etat.enEcoute) 1.035f else 1f,
                        animationSpec = spring(dampingRatio = 0.58f, stiffness = 260f),
                        label = "echelle_bouton_vocal"
                    )

                    Box(
                        modifier = Modifier
                            .size(142.dp)
                            .scale(echelle)
                            .clip(CircleShape)
                            .background(couleurBouton)
                            .semantics { contentDescription = "Maintenir pour écouter, relâcher pour traiter" }
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    val premierContact = awaitFirstDown(requireUnconsumed = false)
                                    val appuiLong = awaitLongPressOrCancellation(premierContact.id)
                                    if (appuiLong != null) {
                                        demarrerMisAJour.value.invoke()
                                        try {
                                            waitForUpOrCancellation()
                                        } finally {
                                            arreterMisAJour.value.invoke()
                                        }
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        MicrophoneDessine(
                            modifier = Modifier.size(47.dp),
                            couleur = BlancChaud
                        )
                    }

                    Spacer(Modifier.height(13.dp))
                    Text(
                        text = when {
                            etat.preparationVocale -> "PRÉPARATION LOCALE"
                            etat.enEcoute -> "ÉCOUTE EN COURS"
                            else -> "MAINTIENS POUR PARLER"
                        },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp,
                        color = if (etat.enEcoute) Brun else TexteSecondaire
                    )
                    Spacer(Modifier.height(17.dp))
                    Divider(color = Color(0xFFE8DED4))
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = etat.statut,
                        modifier = Modifier.fillMaxWidth(),
                        color = BrunProfond,
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    )
                    if (etat.texteReconnu.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Surface(
                            color = Color(0xFFF0E7DE),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(13.dp)) {
                                Text("TRANSCRIPTION LOCALE", fontSize = 10.sp, color = TexteSecondaire, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                                Spacer(Modifier.height(4.dp))
                                Text(etat.texteReconnu, fontSize = 15.sp, color = BrunProfond)
                            }
                        }
                    }
                }
            }

            CarteFonction(
                titre = "Fichiers",
                description = "PDF, DOCX, TXT, CSV, JSON et images avec OCR hors ligne.",
                badge = "LECTURE LOCALE"
            ) {
                Button(
                    onClick = { choixFichierLauncher.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(15.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Brun, contentColor = BlancChaud)
                ) {
                    Text("＋   Ajouter un fichier", fontWeight = FontWeight.SemiBold)
                }
                Text(
                    text = "Choisis le document dans le sélecteur Android. GAI le mémorise pour que tu puisses ensuite dire « Lis mon fichier contrat.pdf ».",
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = TexteSecondaire
                )
                if (etat.nomDocument.isNotBlank()) {
                    Surface(color = Color(0xFFEAE0D5), shape = RoundedCornerShape(12.dp)) {
                        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Image(
                                painter = painterResource(id = R.drawable.ic_gsociety_mark),
                                contentDescription = "Logo G-SOCIETY",
                                modifier = Modifier.size(25.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = etat.nomDocument,
                                modifier = Modifier.weight(1f),
                                color = BrunProfond,
                                fontSize = 12.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text("MÉMORISÉ", fontSize = 9.sp, color = Brun, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            CarteFonction(
                titre = "Actions du téléphone",
                description = "Ouvre les applications, appelle un contact ou prépare un SMS / message WhatsApp.",
                badge = "AVEC AUTORISATION"
            ) {
                Text(
                    text = "Exemples : « Ouvre WhatsApp », « Appelle Moussa », « Écris à Aicha ‘je viens’ ». L'envoi d'un message reste à confirmer dans l'application.",
                    color = TexteSecondaire,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
            }

            if (etat.contenuDocument.isNotBlank()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = BlancChaud)
                ) {
                    Column(Modifier.padding(17.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Image(
                                painter = painterResource(R.drawable.ic_gsociety_mark),
                                contentDescription = "Logo G-SOCIETY",
                                modifier = Modifier.size(30.dp)
                            )
                            Spacer(Modifier.width(9.dp))
                            Column {
                                Text("LECTURE DU DOCUMENT", fontWeight = FontWeight.Bold, color = BrunProfond, fontSize = 13.sp)
                                Text(etat.nomDocument, color = TexteSecondaire, fontSize = 11.sp)
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = etat.contenuDocument,
                            color = BrunProfond,
                            fontSize = 13.sp,
                            lineHeight = 19.sp
                        )
                    }
                }
            }

            if (etat.avertissementVoix) {
                Text(
                    text = "Pour la lecture vocale sans réseau, installe une voix française hors ligne dans les paramètres de synthèse vocale Android.",
                    color = TexteSecondaire,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
            }

            SignatureGSociety()
        }
    }
}

@Composable
private fun EnteteGai() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(R.drawable.ic_gai_logo),
            contentDescription = "Logo de l'application GAI",
            modifier = Modifier.size(54.dp)
        )
        Spacer(Modifier.width(11.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("GAI", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = BrunProfond, letterSpacing = 1.sp)
            Text("ASSISTANT HORS LIGNE", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = TexteSecondaire, letterSpacing = 1.05.sp)
        }
        Column(horizontalAlignment = Alignment.End) {
            Image(
                painter = painterResource(R.drawable.ic_gsociety_mark),
                contentDescription = "Logo G-SOCIETY",
                modifier = Modifier.size(30.dp)
            )
            Text("G-SOCIETY", color = Brun, fontWeight = FontWeight.Bold, fontSize = 9.sp, letterSpacing = 0.4.sp)
            Text("GRIND", color = TexteSecondaire, fontSize = 8.sp, letterSpacing = 1.sp)
        }
    }
}

@Composable
private fun CarteFonction(
    titre: String,
    description: String,
    badge: String,
    contenu: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = BlancChaud),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(17.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Image(
                    painter = painterResource(R.drawable.ic_gsociety_mark),
                    contentDescription = "Logo G-SOCIETY",
                    modifier = Modifier.size(29.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(titre, fontSize = 16.sp, color = BrunProfond, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(3.dp))
                    Text(description, color = TexteSecondaire, fontSize = 12.sp, lineHeight = 17.sp)
                }
            }
            Surface(color = Color(0xFFF0E7DE), shape = RoundedCornerShape(7.dp)) {
                Text(
                    text = badge,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.7.sp,
                    color = Brun
                )
            }
            contenu()
        }
    }
}

@Composable
private fun MicrophoneDessine(modifier: Modifier = Modifier, couleur: Color) {
    Canvas(modifier = modifier) {
        val largeur = size.width
        val hauteur = size.height
        drawRoundRect(
            color = couleur,
            topLeft = Offset(largeur * 0.36f, hauteur * 0.08f),
            size = Size(largeur * 0.28f, hauteur * 0.48f),
            cornerRadius = CornerRadius(largeur * 0.14f)
        )
        drawArc(
            color = couleur,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(largeur * 0.20f, hauteur * 0.23f),
            size = Size(largeur * 0.60f, hauteur * 0.51f),
            style = Stroke(width = largeur * 0.07f, cap = StrokeCap.Round)
        )
        drawLine(
            color = couleur,
            start = Offset(largeur * 0.50f, hauteur * 0.74f),
            end = Offset(largeur * 0.50f, hauteur * 0.90f),
            strokeWidth = largeur * 0.07f,
            cap = StrokeCap.Round
        )
        drawLine(
            color = couleur,
            start = Offset(largeur * 0.33f, hauteur * 0.91f),
            end = Offset(largeur * 0.67f, hauteur * 0.91f),
            strokeWidth = largeur * 0.07f,
            cap = StrokeCap.Round
        )
    }
}

@Composable
private fun SignatureGSociety() {
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Divider(color = Color(0xFFE2D5C8))
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(R.drawable.ic_gsociety_mark),
                contentDescription = "Logo G-SOCIETY",
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(7.dp))
            Text("G-SOCIETY", fontSize = 11.sp, color = Brun, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
            Text("  ×  ", fontSize = 12.sp, color = Taupe)
            Text("GRIND", fontSize = 11.sp, color = TexteSecondaire, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp)
        }
        Spacer(Modifier.height(4.dp))
        Text("Pensé pour fonctionner sans connexion.", fontSize = 10.sp, color = TexteSecondaire)
        Spacer(Modifier.height(8.dp))
    }
}
