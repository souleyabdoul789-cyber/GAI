package com.muryar.app

import android.app.Application
import android.content.SharedPreferences
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val CHAT_LIMIT = 30

enum class GaiMessageRole { USER, ASSISTANT }

data class GaiMessage(
    val id: String,
    val role: GaiMessageRole,
    val content: String
)

data class GaiConversation(
    val id: String,
    val title: String,
    val messages: List<GaiMessage>
)

data class GaiUiState(
    val enEcoute: Boolean = false,
    val preparationVocale: Boolean = false,
    val texteReconnu: String = "",
    val statut: String = "Prêt. Écris un message ou utilise le microphone.",
    val contenuDocument: String = "",
    val nomDocument: String = "",
    val avertissementVoix: Boolean = false,
    val conversations: List<GaiConversation> = emptyList(),
    val activeConversationId: String = "",
    val traitementEnCours: Boolean = false
) {
    val messages: List<GaiMessage>
        get() = conversations.firstOrNull { it.id == activeConversationId }?.messages.orEmpty()
}

/** Chat hors ligne : historique local, commandes Android et retour vocal. */
class GaiViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val preferences: SharedPreferences = appContext.getSharedPreferences("gai_local_chat", 0)
    private val moteurVocal = VoiceEngine(appContext)
    private val lecteurFichiers = FileReaderEngine(appContext)
    private val gestionnaireIntentions = IntentManager(appContext)

    private val _uiState = MutableStateFlow(chargerEtat())
    val uiState: StateFlow<GaiUiState> = _uiState.asStateFlow()

    private val _commandes = MutableSharedFlow<AssistantCommand>(extraBufferCapacity = 1)
    val commandes = _commandes.asSharedFlow()

    fun definirStatut(message: String) {
        modifierEtat { it.copy(statut = message) }
    }

    fun estFichierMemorise(nom: String): Boolean = lecteurFichiers.estMemorise(nom)

    fun nouveauChat() {
        val current = _uiState.value
        val conversationActive = current.conversations.firstOrNull { it.id == current.activeConversationId }
        if (conversationActive == null || conversationActive.messages.isEmpty()) {
            definirStatut("Nouvelle conversation prête.")
            return
        }
        val id = "chat-${System.currentTimeMillis()}"
        modifierEtat {
            it.copy(
                conversations = (listOf(GaiConversation(id, "Nouvelle conversation", emptyList())) + it.conversations).take(CHAT_LIMIT),
                activeConversationId = id,
                texteReconnu = "",
                statut = "Nouvelle conversation prête.",
                contenuDocument = "",
                nomDocument = "",
                traitementEnCours = false
            )
        }
    }

    fun ouvrirConversation(id: String) {
        if (_uiState.value.conversations.any { it.id == id }) {
            modifierEtat { it.copy(activeConversationId = id, texteReconnu = "", statut = "Conversation ouverte.") }
        }
    }

    /** Accepte le texte saisi ou la transcription vocale. */
    fun envoyerTexte(texteBrut: String, depuisLaVoix: Boolean = false) {
        val texte = texteBrut.trim()
        if (texte.isBlank()) return
        ajouterMessage(GaiMessageRole.USER, texte)
        modifierEtat {
            it.copy(
                texteReconnu = if (depuisLaVoix) texte else it.texteReconnu,
                traitementEnCours = true,
                statut = "GAI traite ton message localement…"
            )
        }

        val action = LanguageMapper.analyser(texte)
        if (action is AssistantCommand.Unknown) {
            repondre(reponseLocale(texte))
        } else {
            if (!_commandes.tryEmit(action)) {
                repondre("Une action est déjà en cours. Attends sa fin puis réessaie.")
            } else {
                definirStatut("Action en cours sur ce téléphone…")
            }
        }
    }

    fun demarrerEcoute() {
        modifierEtat {
            it.copy(
                enEcoute = true,
                preparationVocale = true,
                statut = "Préparation du microphone local…",
                texteReconnu = ""
            )
        }
        moteurVocal.commencerEcoute(
            onDemarrage = {
                if (_uiState.value.enEcoute) {
                    modifierEtat { it.copy(preparationVocale = false, statut = "J'écoute… touche Arrêter quand tu as terminé.") }
                }
            },
            onPartiel = { partiel ->
                if (_uiState.value.enEcoute) modifierEtat { it.copy(texteReconnu = partiel) }
            },
            onFinal = { final ->
                modifierEtat { it.copy(enEcoute = false, preparationVocale = false, texteReconnu = final) }
                if (final.isNotBlank()) envoyerTexte(final, depuisLaVoix = true)
                else definirStatut("Je n'ai pas entendu de message. Réessaie.")
            },
            onErreur = { erreur ->
                modifierEtat {
                    it.copy(
                        enEcoute = false,
                        preparationVocale = false,
                        statut = "Reconnaissance vocale locale indisponible : $erreur",
                        traitementEnCours = false
                    )
                }
            }
        )
    }

    fun arreterEcoute() {
        if (!_uiState.value.enEcoute) return
        modifierEtat { it.copy(enEcoute = false, preparationVocale = false, statut = "Transcription en cours…") }
        moteurVocal.arreterEcoute()
    }

    fun dire(texte: String) {
        if (texte.isBlank()) return
        if (!moteurVocal.parler(texte)) {
            modifierEtat { it.copy(avertissementVoix = true, statut = "Texte prêt. Aucune voix française hors ligne disponible sur le téléphone.") }
        } else {
            definirStatut("Lecture vocale en cours.")
        }
    }

    fun ajouterFichier(uri: Uri) {
        val nom = lecteurFichiers.memoriser(uri)
        modifierEtat { it.copy(nomDocument = nom, statut = "Lecture locale de $nom…", traitementEnCours = true) }
        viewModelScope.launch {
            runCatching { lecteurFichiers.lire(uri) }
                .onSuccess { texte ->
                    val resume = lecteurFichiers.resumerPourLaVoix(nom, texte)
                    modifierEtat {
                        it.copy(
                            nomDocument = nom,
                            contenuDocument = texte.take(7_000),
                            statut = "Fichier lu localement.",
                            traitementEnCours = false
                        )
                    }
                    repondre("J'ai ouvert **$nom**. Voici un résumé local :\n\n$resume")
                }
                .onFailure { erreur -> repondre(erreur.message ?: "Impossible de lire ce fichier.") }
        }
    }

    fun executerAction(action: AssistantCommand) {
        when (action) {
            is AssistantCommand.ReadFile -> {
                modifierEtat { it.copy(statut = "Recherche de ${action.nomFichier} dans les fichiers ajoutés…", traitementEnCours = true) }
                viewModelScope.launch {
                    runCatching { lecteurFichiers.lireParNom(action.nomFichier) }
                        .onSuccess { (nom, texte) ->
                            val resume = lecteurFichiers.resumerPourLaVoix(nom, texte)
                            modifierEtat {
                                it.copy(nomDocument = nom, contenuDocument = texte.take(7_000), statut = "Document lu localement.", traitementEnCours = false)
                            }
                            repondre("J'ai retrouvé **$nom**. Voici un résumé local :\n\n$resume")
                        }
                        .onFailure { erreur -> repondre(erreur.message ?: "Le fichier n'a pas pu être lu.") }
                }
            }
            is AssistantCommand.Unknown -> repondre(reponseLocale(action.texteOriginal))
            else -> {
                modifierEtat { it.copy(statut = "Exécution locale…", traitementEnCours = true) }
                viewModelScope.launch {
                    val retour = runCatching { gestionnaireIntentions.executer(action) }
                        .getOrElse { "Action impossible : ${it.localizedMessage ?: "erreur Android"}." }
                    repondre(retour)
                }
            }
        }
    }

    private fun reponseLocale(texteBrut: String): String {
        val texte = LanguageMapper.normaliser(texteBrut).trim().trimEnd('.', '?', '!', ',', ';', ':')
        return when {
            texte in setOf("bonjour", "salut", "coucou", "bonsoir", "hello", "salam") ||
                texte.startsWith("bonjour ") || texte.startsWith("salut ") ->
                "Bonjour ! Je suis GAI, l'assistante locale de G-SOCIETY. Tu peux m'écrire ou me parler."
            texte.contains("qui t'a developpe") || texte.contains("qui t'a cree") ||
                texte.contains("qui est ton developpeur") || texte.contains("qui t'a concu") ->
                "Je suis GAI, une assistante développée par G-SOCIETY. Cette version fonctionne hors ligne et peut reconnaître la voix, lire les fichiers sélectionnés et exécuter certaines commandes Android avec ton autorisation. Elle n'intègre toutefois pas encore de modèle de langage général pour rédiger librement sur n'importe quel sujet."
            texte == "aide" || texte.contains("que peux tu faire") || texte.contains("que sais tu faire") || texte.contains("comment utiliser gai") ->
                "Tu peux écrire ou dicter un message, demander « Ouvre WhatsApp », « Appelle un contact », préparer un message entre guillemets, ou ajouter un document puis demander à le lire. La rédaction libre et les réponses générales nécessitent encore un modèle de langage local qui n'est pas inclus dans cette version."
            texte.contains("comment vas tu") || texte == "ca va" ->
                "Je fonctionne correctement et je suis prête à t'aider avec les fonctions locales disponibles."
            else ->
                "J'ai reçu ton message, mais le moteur actuel ne comprend que les commandes locales et quelques questions de base. Il ne contient pas encore de modèle de langage général capable de rédiger une réponse libre. Je préfère te le dire clairement plutôt que d'inventer une réponse."
        }
    }

    private fun repondre(texte: String) {
        ajouterMessage(GaiMessageRole.ASSISTANT, texte)
        modifierEtat { it.copy(statut = "Réponse prête.", traitementEnCours = false) }
        dire(texte.replace("**", ""))
    }

    private fun ajouterMessage(role: GaiMessageRole, texte: String) {
        val message = GaiMessage("msg-${System.currentTimeMillis()}-${_uiState.value.messages.size}", role, texte)
        modifierEtat { state ->
            val current = state.conversations.firstOrNull { it.id == state.activeConversationId }
                ?: GaiConversation(state.activeConversationId.ifBlank { "chat-${System.currentTimeMillis()}" }, "Nouvelle conversation", emptyList())
            val titre = if (role == GaiMessageRole.USER && current.messages.isEmpty()) {
                texte.replace('\n', ' ').take(36).ifBlank { "Nouvelle conversation" }
            } else current.title
            val changed = current.copy(title = titre, messages = current.messages + message)
            state.copy(
                conversations = (listOf(changed) + state.conversations.filterNot { it.id == changed.id }).take(CHAT_LIMIT),
                activeConversationId = changed.id
            )
        }
    }

    private fun modifierEtat(transform: (GaiUiState) -> GaiUiState) {
        val next = transform(_uiState.value)
        _uiState.value = next
        enregistrerEtat(next)
    }

    private fun etatInitial(): GaiUiState {
        val id = "chat-${System.currentTimeMillis()}"
        return GaiUiState(
            conversations = listOf(GaiConversation(id, "Nouvelle conversation", emptyList())),
            activeConversationId = id
        )
    }

    private fun chargerEtat(): GaiUiState {
        val raw = preferences.getString("conversations", null) ?: return etatInitial()
        return runCatching {
            val root = JSONObject(raw)
            val chats = mutableListOf<GaiConversation>()
            val array = root.optJSONArray("items") ?: JSONArray()
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val messagesJson = item.optJSONArray("messages") ?: JSONArray()
                val messages = mutableListOf<GaiMessage>()
                for (j in 0 until messagesJson.length()) {
                    val msg = messagesJson.getJSONObject(j)
                    messages += GaiMessage(
                        id = msg.optString("id"),
                        role = runCatching { GaiMessageRole.valueOf(msg.optString("role")) }.getOrDefault(GaiMessageRole.ASSISTANT),
                        content = msg.optString("content")
                    )
                }
                chats += GaiConversation(item.optString("id"), item.optString("title", "Conversation"), messages)
            }
            if (chats.isEmpty()) return@runCatching etatInitial()
            val requestedActive = preferences.getString("active_conversation", "") ?: ""
            val active = chats.firstOrNull { it.id == requestedActive }?.id ?: chats.first().id
            GaiUiState(conversations = chats, activeConversationId = active)
        }.getOrElse { etatInitial() }
    }

    private fun enregistrerEtat(state: GaiUiState) {
        runCatching {
            val items = JSONArray()
            state.conversations.forEach { chat ->
                val messages = JSONArray()
                chat.messages.forEach { message ->
                    messages.put(JSONObject().put("id", message.id).put("role", message.role.name).put("content", message.content))
                }
                items.put(JSONObject().put("id", chat.id).put("title", chat.title).put("messages", messages))
            }
            preferences.edit()
                .putString("conversations", JSONObject().put("items", items).toString())
                .putString("active_conversation", state.activeConversationId)
                .apply()
        }
    }

    override fun onCleared() {
        moteurVocal.fermer()
        super.onCleared()
    }
}
