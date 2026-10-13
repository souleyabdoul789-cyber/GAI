package com.muryar.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** État observable de l'interface GAI. */
data class GaiUiState(
    val enEcoute: Boolean = false,
    val preparationVocale: Boolean = false,
    val texteReconnu: String = "",
    val statut: String = "Prêt. Maintiens le bouton vocal pour parler.",
    val contenuDocument: String = "",
    val nomDocument: String = "",
    val avertissementVoix: Boolean = false
)

/** Couche ViewModel : orchestre la voix, la lecture locale et les intentions Android. */
class GaiViewModel(application: Application) : AndroidViewModel(application) {
    private val moteurVocal = VoiceEngine(application.applicationContext)
    private val lecteurFichiers = FileReaderEngine(application.applicationContext)
    private val gestionnaireIntentions = IntentManager(application.applicationContext)

    private val _uiState = MutableStateFlow(GaiUiState())
    val uiState: StateFlow<GaiUiState> = _uiState.asStateFlow()

    private val _commandes = MutableSharedFlow<AssistantCommand>(extraBufferCapacity = 1)
    val commandes = _commandes.asSharedFlow()

    fun definirStatut(message: String) {
        _uiState.update { it.copy(statut = message) }
    }

    fun estFichierMemorise(nom: String): Boolean = lecteurFichiers.estMemorise(nom)

    fun demarrerEcoute() {
        _uiState.update {
            it.copy(
                enEcoute = true,
                preparationVocale = true,
                statut = "Préparation du moteur vocal local… garde le bouton enfoncé.",
                texteReconnu = ""
            )
        }

        moteurVocal.commencerEcoute(
            onDemarrage = {
                if (_uiState.value.enEcoute) {
                    _uiState.update {
                        it.copy(
                            preparationVocale = false,
                            statut = "J'écoute… Relâche pour traiter la commande sur ce téléphone."
                        )
                    }
                }
            },
            onPartiel = { partiel ->
                if (_uiState.value.enEcoute) _uiState.update { it.copy(texteReconnu = partiel) }
            },
            onFinal = { final ->
                _uiState.update {
                    it.copy(
                        enEcoute = false,
                        preparationVocale = false,
                        texteReconnu = final,
                        statut = if (final.isBlank()) {
                            "Je n'ai pas entendu de commande claire. Réessaie près du microphone."
                        } else {
                            "Compris : $final"
                        }
                    )
                }
                if (final.isNotBlank()) _commandes.tryEmit(LanguageMapper.analyser(final))
            },
            onErreur = { erreur ->
                _uiState.update {
                    it.copy(
                        enEcoute = false,
                        preparationVocale = false,
                        statut = "Reconnaissance hors ligne indisponible : $erreur"
                    )
                }
            }
        )
    }

    fun arreterEcoute() {
        if (!_uiState.value.enEcoute) return
        _uiState.update {
            it.copy(
                enEcoute = false,
                preparationVocale = false,
                statut = "Traitement local…"
            )
        }
        moteurVocal.arreterEcoute()
    }

    fun ajouterFichier(uri: Uri) {
        val nom = lecteurFichiers.memoriser(uri)
        _uiState.update { it.copy(nomDocument = nom, statut = "Lecture locale de $nom…") }
        viewModelScope.launch {
            runCatching { lecteurFichiers.lire(uri) }
                .onSuccess { texte ->
                    val resume = lecteurFichiers.resumerPourLaVoix(nom, texte)
                    _uiState.update {
                        it.copy(
                            nomDocument = nom,
                            contenuDocument = texte.take(7_000),
                            statut = "Fichier ajouté. Tu peux dire : « Lis mon fichier $nom »."
                        )
                    }
                    parlerOuAvertir(resume)
                }
                .onFailure { erreur ->
                    definirStatut(erreur.message ?: "Impossible de lire ce fichier.")
                }
        }
    }

    fun executerAction(action: AssistantCommand) {
        when (action) {
            is AssistantCommand.ReadFile -> {
                definirStatut("Recherche de ${action.nomFichier} dans les fichiers déjà ajoutés…")
                viewModelScope.launch {
                    runCatching { lecteurFichiers.lireParNom(action.nomFichier) }
                        .onSuccess { (nom, texte) ->
                            val resume = lecteurFichiers.resumerPourLaVoix(nom, texte)
                            _uiState.update {
                                it.copy(
                                    nomDocument = nom,
                                    contenuDocument = "$resume\n\nExtrait du document :\n${texte.take(7_000)}",
                                    statut = "Document lu localement : $nom."
                                )
                            }
                            parlerOuAvertir(resume)
                        }
                        .onFailure { erreur ->
                            val message = erreur.message ?: "Le fichier n'a pas pu être lu."
                            definirStatut(message)
                            parlerOuAvertir(message)
                        }
                }
            }
            is AssistantCommand.Unknown -> {
                val message = "Commande non reconnue. Essaie : « Ouvre WhatsApp », « Appelle Moussa » ou « Lis contrat.pdf »."
                definirStatut(message)
                parlerOuAvertir(message)
            }
            else -> {
                definirStatut("Exécution locale…")
                viewModelScope.launch {
                    val retour = runCatching { gestionnaireIntentions.executer(action) }
                        .getOrElse { "Action impossible : ${it.localizedMessage ?: "erreur Android"}." }
                    definirStatut(retour)
                    parlerOuAvertir(retour)
                }
            }
        }
    }

    private fun parlerOuAvertir(texte: String) {
        if (!moteurVocal.parler(texte)) {
            _uiState.update { it.copy(avertissementVoix = true) }
        }
    }

    override fun onCleared() {
        moteurVocal.fermer()
        super.onCleared()
    }
}
