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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

private val GaiBrown = Color(0xFF765744)
private val GaiDark = Color(0xFF392B23)
private val GaiMilk = Color(0xFFF4EEE5)
private val GaiWhite = Color(0xFFFFFCF8)
private val GaiMuted = Color(0xFF796A5F)
private val GaiBorder = Color(0xFFE6DACE)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(244, 238, 229)
        window.navigationBarColor = android.graphics.Color.rgb(244, 238, 229)
        setContent { GaiTheme { GaiScreen() } }
    }
}

@Composable
private fun GaiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = GaiBrown,
            onPrimary = GaiWhite,
            secondary = Color(0xFFA98970),
            background = GaiMilk,
            onBackground = GaiDark,
            surface = GaiWhite,
            onSurface = GaiDark,
            surfaceVariant = Color(0xFFE9DED2),
            onSurfaceVariant = GaiMuted
        ),
        content = content
    )
}

private enum class GaiTab { CHAT, OUTILS, APROPOS }

@Composable
private fun GaiScreen() {
    val context = LocalContext.current
    val vm: GaiViewModel = viewModel()
    val state by vm.uiState.collectAsStateWithLifecycle()
    var draft by rememberSaveable { mutableStateOf("") }
    var selectedTab by rememberSaveable { mutableStateOf(GaiTab.CHAT) }
    var pendingCommand by remember { mutableStateOf<AssistantCommand?>(null) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val permissionsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val action = pendingCommand
        pendingCommand = null
        if (action != null && results.values.all { it }) {
            vm.executerAction(action)
        } else if (action != null) {
            vm.definirStatut("Autorisation refusée. L'action n'a pas été exécutée.")
        }
    }
    val micPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.definirStatut(if (granted) "Microphone autorisé. Touche encore le microphone pour parler." else "Le microphone est nécessaire pour dicter un message.")
    }
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            vm.ajouterFichier(uri)
            selectedTab = GaiTab.CHAT
        }
    }

    fun permissionsNecessaires(action: AssistantCommand): Array<String> {
        val missing = mutableListOf<String>()
        val needsContacts = action is AssistantCommand.CallContact || action is AssistantCommand.SendMessage
        if (needsContacts && ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            missing += Manifest.permission.READ_CONTACTS
        }
        if (action is AssistantCommand.CallContact && ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            missing += Manifest.permission.CALL_PHONE
        }
        if (action is AssistantCommand.ReadFile && Build.VERSION.SDK_INT <= 32 &&
            !vm.estFichierMemorise(action.nomFichier) &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            missing += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return missing.toTypedArray()
    }

    fun executerAvecPermissions(action: AssistantCommand) {
        val missing = permissionsNecessaires(action)
        if (missing.isNotEmpty()) {
            pendingCommand = action
            permissionsLauncher.launch(missing)
        } else vm.executerAction(action)
    }

    LaunchedEffect(vm) {
        vm.commandes.collect { action -> executerAvecPermissions(action) }
    }

    fun envoyer() {
        val message = draft.trim()
        if (message.isNotEmpty()) {
            draft = ""
            selectedTab = GaiTab.CHAT
            vm.envoyerTexte(message)
        }
    }

    fun basculerMicro() {
        if (state.enEcoute) {
            vm.arreterEcoute()
        } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            vm.demarrerEcoute()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        scrimColor = Color.Black.copy(alpha = 0.38f),
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = GaiMilk) {
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 18.dp)) {
                        BrandMark(Modifier.size(42.dp))
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("GAI", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = GaiDark)
                            Text("PAR G-SOCIETY", fontSize = 10.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold, color = GaiBrown)
                        }
                    }
                    Button(
                        onClick = { vm.nouveauChat(); selectedTab = GaiTab.CHAT; scope.launch { drawerState.close() } },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(15.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = GaiBrown, contentColor = GaiWhite)
                    ) { Text("＋   Nouvelle conversation", fontWeight = FontWeight.SemiBold) }
                    Text("RÉCENTS", modifier = Modifier.padding(top = 24.dp, bottom = 8.dp), color = GaiMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                    if (state.conversations.isEmpty()) {
                        Text("Tes conversations apparaîtront ici.", color = GaiMuted, fontSize = 13.sp)
                    } else {
                        state.conversations.forEach { chat ->
                            Surface(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable {
                                    vm.ouvrirConversation(chat.id)
                                    selectedTab = GaiTab.CHAT
                                    scope.launch { drawerState.close() }
                                },
                                shape = RoundedCornerShape(12.dp),
                                color = if (chat.id == state.activeConversationId) Color(0xFFE6DACE) else Color.Transparent
                            ) {
                                Text(
                                    text = "▤  ${chat.title}",
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
                                    color = GaiDark,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Divider(color = GaiBorder)
                    Text("G-SOCIETY · GAI", modifier = Modifier.padding(top = 12.dp), color = GaiBrown, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text("Historique stocké sur ce téléphone", color = GaiMuted, fontSize = 11.sp)
                }
            }
        }
    ) {
        Column(
            modifier = Modifier.fillMaxSize().background(GaiMilk).imePadding()
        ) {
            Header(onMenu = { scope.launch { drawerState.open() } }, onNewChat = { vm.nouveauChat(); selectedTab = GaiTab.CHAT })
            when (selectedTab) {
                GaiTab.CHAT -> ChatPage(
                    modifier = Modifier.weight(1f),
                    state = state,
                    draft = draft,
                    onDraft = { draft = it },
                    onSend = { envoyer() },
                    onMic = { basculerMicro() },
                    onAttach = { fileLauncher.launch(arrayOf("*/*")) },
                    onSpeak = { vm.dire(it) },
                    onQuickPrompt = { vm.envoyerTexte(it) }
                )
                GaiTab.OUTILS -> ToolsPage(
                    modifier = Modifier.weight(1f),
                    onAttach = { fileLauncher.launch(arrayOf("*/*")) },
                    onCommand = { vm.envoyerTexte(it); selectedTab = GaiTab.CHAT }
                )
                GaiTab.APROPOS -> AboutPage(modifier = Modifier.weight(1f))
            }
            BottomTabs(selectedTab = selectedTab, onSelect = { selectedTab = it })
        }
    }
}

@Composable
private fun Header(onMenu: () -> Unit, onNewChat: () -> Unit) {
    Surface(color = GaiWhite, shadowElevation = 2.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onMenu) { Text("☰", fontSize = 23.sp, color = GaiDark) }
            BrandMark(Modifier.size(38.dp))
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text("GAI", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = GaiDark, letterSpacing = 0.7.sp)
                Text("ASSISTANTE LOCALE · G-SOCIETY", fontSize = 8.sp, color = GaiMuted, letterSpacing = 0.6.sp)
            }
            Surface(color = Color(0xFFEDE4DB), shape = RoundedCornerShape(20.dp)) {
                Text("HORS LIGNE", modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp), color = GaiBrown, fontWeight = FontWeight.Bold, fontSize = 9.sp)
            }
            TextButton(onClick = onNewChat) { Text("＋", color = GaiBrown, fontSize = 24.sp) }
        }
    }
}

@Composable
private fun ChatPage(
    modifier: Modifier = Modifier,
    state: GaiUiState,
    draft: String,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    onMic: () -> Unit,
    onAttach: () -> Unit,
    onSpeak: (String) -> Unit,
    onQuickPrompt: (String) -> Unit
) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.messages.size, state.texteReconnu, state.traitementEnCours) {
        if (state.messages.isNotEmpty() || state.traitementEnCours) {
            val lastItem = state.messages.lastIndex + if (state.traitementEnCours) 1 else 0
            listState.animateScrollToItem(lastItem.coerceAtLeast(0))
        }
    }
    Column(modifier.fillMaxWidth()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            if (state.messages.isEmpty()) {
                item {
                    WelcomeCard()
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("ESSAIE PAR EXEMPLE", modifier = Modifier.padding(start = 3.dp), fontSize = 10.sp, color = GaiMuted, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        SuggestionCard("Ouvre WhatsApp", "Lancer une application") { onQuickPrompt("Ouvre WhatsApp") }
                        SuggestionCard("Qui t'a développée ?", "Découvrir GAI et G-SOCIETY") { onQuickPrompt("Qui t'a développée ?") }
                        SuggestionCard("Lis mon fichier cours.pdf", "Lire un document déjà ajouté") { onQuickPrompt("Lis mon fichier cours.pdf") }
                    }
                }
            }
            items(state.messages, key = { it.id }) { message ->
                MessageBubble(message = message, onSpeak = onSpeak)
            }
            if (state.traitementEnCours) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BrandMark(Modifier.size(27.dp))
                        Text("GAI réfléchit aux commandes locales…", color = GaiMuted, fontSize = 12.sp)
                    }
                }
            }
            if (state.texteReconnu.isNotBlank() && state.enEcoute) {
                item {
                    Surface(color = Color(0xFFE8DED3), shape = RoundedCornerShape(12.dp)) {
                        Text("J'entends : ${state.texteReconnu}", modifier = Modifier.padding(12.dp), color = GaiDark, fontSize = 13.sp)
                    }
                }
            }
        }
        if (state.statut.isNotBlank()) {
            Text(state.statut, modifier = Modifier.fillMaxWidth().padding(horizontal = 17.dp, vertical = 4.dp), color = GaiMuted, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (state.avertissementVoix) {
            Text("Pour parler, installe une voix française hors ligne dans les paramètres de synthèse vocale Android.", modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = GaiMuted, fontSize = 10.sp)
        }
        Composer(
            text = draft,
            onText = onDraft,
            onSend = onSend,
            onMic = onMic,
            onAttach = onAttach,
            listening = state.enEcoute,
            preparing = state.preparationVocale
        )
    }
}

@Composable
private fun WelcomeCard() {
    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 8.dp),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = GaiWhite),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            BrandMark(Modifier.size(66.dp))
            Spacer(Modifier.height(16.dp))
            Text("Bonjour, je suis GAI.", color = GaiDark, fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text("Ton assistante locale développée par G-SOCIETY.", color = GaiBrown, fontSize = 13.sp, textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            Text("Écris dans la zone ci-dessous ou touche le microphone pour dicter un message.", color = GaiMuted, fontSize = 13.sp, lineHeight = 19.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun SuggestionCard(title: String, caption: String, onClick: () -> Unit) {
    Surface(modifier = Modifier.clickable(onClick = onClick), color = GaiWhite, shape = RoundedCornerShape(13.dp), border = androidx.compose.foundation.BorderStroke(1.dp, GaiBorder)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("↗", color = GaiBrown, fontSize = 17.sp)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(title, color = GaiDark, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(caption, color = GaiMuted, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun MessageBubble(message: GaiMessage, onSpeak: (String) -> Unit) {
    val user = message.role == GaiMessageRole.USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (user) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        if (!user) {
            BrandMark(Modifier.size(29.dp))
            Spacer(Modifier.width(8.dp))
        }
        Column(
            modifier = Modifier.fillMaxWidth(0.88f),
            horizontalAlignment = if (user) Alignment.End else Alignment.Start
        ) {
            Surface(
                color = if (user) GaiBrown else GaiWhite,
                shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = if (user) 18.dp else 5.dp, bottomEnd = if (user) 5.dp else 18.dp),
                shadowElevation = if (user) 0.dp else 1.dp
            ) {
                Text(
                    text = message.content.replace("**", ""),
                    modifier = Modifier.padding(horizontal = 15.dp, vertical = 12.dp),
                    color = if (user) GaiWhite else GaiDark,
                    fontSize = 14.sp,
                    lineHeight = 21.sp
                )
            }
            if (!user) {
                TextButton(onClick = { onSpeak(message.content.replace("**", "")) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                    Text("▶ Lire la réponse", color = GaiMuted, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
private fun Composer(
    text: String,
    onText: (String) -> Unit,
    onSend: () -> Unit,
    onMic: () -> Unit,
    onAttach: () -> Unit,
    listening: Boolean,
    preparing: Boolean
) {
    Surface(color = GaiWhite, shadowElevation = 8.dp) {
        Column(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 9.dp, bottom = 9.dp)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                TextButton(onClick = onAttach, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 8.dp)) {
                    Text("＋", fontSize = 23.sp, color = GaiBrown)
                }
                Surface(
                    modifier = Modifier.weight(1f),
                    color = GaiMilk,
                    shape = RoundedCornerShape(19.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, GaiBorder)
                ) {
                    Box(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                        if (text.isBlank()) Text("Écris à GAI…", color = GaiMuted, fontSize = 14.sp)
                        BasicTextField(
                            value = text,
                            onValueChange = onText,
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = androidx.compose.ui.text.TextStyle(color = GaiDark, fontSize = 14.sp, lineHeight = 20.sp),
                            maxLines = 5
                        )
                    }
                }
                Button(
                    onClick = onSend,
                    enabled = text.isNotBlank(),
                    shape = CircleShape,
                    modifier = Modifier.size(45.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = GaiBrown, contentColor = GaiWhite)
                ) { Text("↑", fontSize = 22.sp, fontWeight = FontWeight.Bold) }
                Button(
                    onClick = onMic,
                    shape = CircleShape,
                    modifier = Modifier.size(45.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (listening) Color(0xFFB24E43) else Color(0xFFE9DED2),
                        contentColor = if (listening) Color.White else GaiBrown
                    )
                ) { Text(if (listening) "■" else "🎙", fontSize = if (listening) 17.sp else 19.sp) }
            }
            if (preparing) {
                Text("Préparation du microphone…", modifier = Modifier.padding(start = 45.dp, top = 4.dp), color = GaiMuted, fontSize = 10.sp)
            } else if (listening) {
                Text("Écoute en cours — touche ■ pour terminer", modifier = Modifier.padding(start = 45.dp, top = 4.dp), color = GaiBrown, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun ToolsPage(modifier: Modifier = Modifier, onAttach: () -> Unit, onCommand: (String) -> Unit) {
    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Outils de GAI", fontSize = 24.sp, color = GaiDark, fontWeight = FontWeight.Bold)
        Text("Fonctions locales disponibles", fontSize = 13.sp, color = GaiMuted)
        ToolCard("Documents", "Lire localement un PDF, DOCX, TXT, CSV, JSON ou une image.") {
            Button(onClick = onAttach, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = GaiBrown)) {
                Text("＋ Ajouter un fichier")
            }
        }
        ToolCard("Commandes vocales ou écrites", "Utilise une phrase précise pour lancer une action Android.") {
            Button(onClick = { onCommand("Ouvre WhatsApp") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(13.dp), colors = ButtonDefaults.buttonColors(containerColor = GaiBrown)) { Text("Ouvrir WhatsApp") }
            Button(onClick = { onCommand("Qui t'a développée ?") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(13.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE9DED2), contentColor = GaiDark)) { Text("Qui développe GAI ?") }
        }
        ToolCard("Vie privée", "Les conversations et les fichiers sont conservés localement sur ce téléphone. Le moteur de réponse général n'est pas inclus dans cette version.") {}
    }
}

@Composable
private fun ToolCard(title: String, description: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = GaiWhite), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(title, color = GaiDark, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(description, color = GaiMuted, fontSize = 12.sp, lineHeight = 17.sp)
            content()
        }
    }
}

@Composable
private fun AboutPage(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = GaiWhite), shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                BrandMark(Modifier.size(76.dp))
                Spacer(Modifier.height(13.dp))
                Text("GAI", color = GaiDark, fontWeight = FontWeight.ExtraBold, fontSize = 28.sp)
                Text("Développée par G-SOCIETY", color = GaiBrown, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Spacer(Modifier.height(12.dp))
                Text("Une assistante Android pensée pour fonctionner localement : transcription vocale, lecture de documents et commandes du téléphone.", color = GaiMuted, fontSize = 13.sp, lineHeight = 19.sp, textAlign = TextAlign.Center)
            }
        }
        ToolCard("État des capacités", "La conversation texte/voix et l'historique sont disponibles dans cette interface. Le code actuel n'inclut pas encore de modèle de langage général : les demandes libres ne peuvent donc pas recevoir les réponses développées d'une IA comme ChatGPT.") {}
        ToolCard("Identité", "GAI signifie G-SOCIETY Artificial Intelligence. Produit de G-SOCIETY.") {}
    }
}

@Composable
private fun BottomTabs(selectedTab: GaiTab, onSelect: (GaiTab) -> Unit) {
    Surface(color = GaiWhite, shadowElevation = 5.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            TabButton("▤", "Chat", selectedTab == GaiTab.CHAT, Modifier.weight(1f)) { onSelect(GaiTab.CHAT) }
            TabButton("✦", "Outils", selectedTab == GaiTab.OUTILS, Modifier.weight(1f)) { onSelect(GaiTab.OUTILS) }
            TabButton("ⓘ", "À propos", selectedTab == GaiTab.APROPOS, Modifier.weight(1f)) { onSelect(GaiTab.APROPOS) }
        }
    }
}

@Composable
private fun TabButton(icon: String, label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        color = if (selected) Color(0xFFF0E7DE) else Color.Transparent,
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(Modifier.padding(vertical = 7.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(icon, color = if (selected) GaiBrown else GaiMuted, fontSize = 19.sp)
            Text(label, color = if (selected) GaiBrown else GaiMuted, fontSize = 10.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
        }
    }
}

@Composable
private fun BrandMark(modifier: Modifier = Modifier) {
    Surface(modifier = modifier, color = GaiBrown, shape = RoundedCornerShape(14.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Text("G", color = GaiWhite, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
        }
    }
}
