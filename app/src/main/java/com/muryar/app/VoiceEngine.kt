package com.muryar.app

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Executors

/** Moteur vocal hors ligne : Vosk pour le français, voix Android uniquement si elle est locale. */
class VoiceEngine(private val context: Context) {
    private val execurteur = Executors.newSingleThreadExecutor()
    private val gestionnairePrincipal = Handler(Looper.getMainLooper())
    private val verrou = Any()

    @Volatile private var modele: Model? = null
    @Volatile private var reconnaissance: Recognizer? = null
    @Volatile private var serviceVocal: SpeechService? = null
    @Volatile private var numeroSession = 0
    @Volatile private var dernierTexte = ""
    @Volatile private var resultatEnvoye = false

    @Volatile private var rappelPartiel: ((String) -> Unit)? = null
    @Volatile private var rappelFinal: ((String) -> Unit)? = null
    @Volatile private var rappelErreur: ((String) -> Unit)? = null

    @Volatile private var synthese: TextToSpeech? = null
    @Volatile private var voixFrancaiseHorsLigneDisponible = false

    init {
        synthese = TextToSpeech(context.applicationContext) { statut ->
            gestionnairePrincipal.post {
                if (statut == TextToSpeech.SUCCESS) configurerSyntheseHorsLigne()
            }
        }
    }

    /** Commence à écouter. Appeler arrêterÉcoute() quand l'utilisateur relâche le bouton. */
    fun commencerEcoute(
        onDemarrage: () -> Unit,
        onPartiel: (String) -> Unit,
        onFinal: (String) -> Unit,
        onErreur: (String) -> Unit
    ) {
        val idSession: Int
        synchronized(verrou) {
            numeroSession += 1
            idSession = numeroSession
            dernierTexte = ""
            resultatEnvoye = false
            rappelPartiel = onPartiel
            rappelFinal = onFinal
            rappelErreur = onErreur
        }

        execurteur.execute {
            try {
                val modeleCharge = chargerModele()
                val reconnaisseur = Recognizer(modeleCharge, 16_000.0f)
                reconnaissance = reconnaisseur
                val service = SpeechService(reconnaisseur, 16_000.0f)
                serviceVocal = service
                service.startListening(object : RecognitionListener {
                    override fun onPartialResult(hypothesis: String?) {
                        val texte = extraireTexte(hypothesis)
                        if (texte.isNotBlank() && idSession == numeroSession) {
                            dernierTexte = texte
                            gestionnairePrincipal.post {
                                if (idSession == numeroSession) rappelPartiel?.invoke(texte)
                            }
                        }
                    }

                    override fun onResult(hypothesis: String?) {
                        val texte = extraireTexte(hypothesis)
                        if (texte.isNotBlank() && idSession == numeroSession) {
                            dernierTexte = texte
                            gestionnairePrincipal.post {
                                if (idSession == numeroSession) rappelPartiel?.invoke(texte)
                            }
                        }
                    }

                    override fun onFinalResult(hypothesis: String?) {
                        transmettreFinal(idSession, extraireTexte(hypothesis))
                    }

                    override fun onError(exception: Exception?) {
                        val message = exception?.localizedMessage ?: "Erreur de reconnaissance vocale."
                        gestionnairePrincipal.post {
                            if (idSession == numeroSession) rappelErreur?.invoke(message)
                        }
                    }

                    override fun onTimeout() {
                        transmettreFinal(idSession, "")
                    }
                })
                gestionnairePrincipal.post {
                    if (idSession == numeroSession) onDemarrage()
                }
            } catch (exception: Exception) {
                val message = exception.localizedMessage
                    ?: "Impossible de démarrer la reconnaissance vocale hors ligne."
                gestionnairePrincipal.post {
                    if (idSession == numeroSession) rappelErreur?.invoke(message)
                }
            }
        }
    }

    /** Termine la capture à la relâche du bouton, puis remet les ressources en état. */
    fun arreterEcoute() {
        val idSession = numeroSession
        execurteur.execute {
            val service = serviceVocal
            val reconnaisseurSession = reconnaissance
            if (service == null) {
                transmettreFinal(idSession, dernierTexte)
                return@execute
            }

            runCatching { service.stop() }
            // Vosk transmet normalement onFinalResult après stop(). Le repli évite un silence
            // d'interface si un modèle ou un appareil ne produit pas cet événement final.
            gestionnairePrincipal.postDelayed({
                if (idSession == numeroSession && !resultatEnvoye) {
                    transmettreFinal(idSession, dernierTexte)
                }
                execurteur.execute {
                    runCatching { service.shutdown() }
                    if (serviceVocal === service) serviceVocal = null
                    if (reconnaissance === reconnaisseurSession) {
                        runCatching { reconnaisseurSession?.close() }
                        reconnaissance = null
                    }
                }
            }, 450L)
        }
    }

    /** Parle seulement avec une voix française annoncée comme non dépendante du réseau. */
    fun parler(texte: String): Boolean {
        val moteur = synthese ?: return false
        if (!voixFrancaiseHorsLigneDisponible || texte.isBlank()) return false

        val options = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "gai-${System.nanoTime()}")
        }
        return moteur.speak(texte.take(3_500), TextToSpeech.QUEUE_FLUSH, options, "gai-${System.nanoTime()}") == TextToSpeech.SUCCESS
    }

    private fun configurerSyntheseHorsLigne() {
        val moteur = synthese ?: return
        runCatching {
            moteur.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            val resultatLangue = moteur.setLanguage(Locale.FRANCE)
            if (resultatLangue == TextToSpeech.LANG_MISSING_DATA ||
                resultatLangue == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                voixFrancaiseHorsLigneDisponible = false
                return
            }

            val voixHorsLigne = moteur.voices
                ?.filter { it.locale.language == "fr" && !it.isNetworkConnectionRequired }
                ?.sortedBy { it.name }
                ?.firstOrNull()

            if (voixHorsLigne != null) {
                moteur.voice = voixHorsLigne
                voixFrancaiseHorsLigneDisponible = true
            } else {
                // Pas de repli sur une voix distante : le mode avion reste réellement utilisable.
                voixFrancaiseHorsLigneDisponible = false
            }
        }.onFailure {
            voixFrancaiseHorsLigneDisponible = false
        }
    }

    private fun transmettreFinal(idSession: Int, texteVosk: String) {
        if (idSession != numeroSession || resultatEnvoye) return
        resultatEnvoye = true
        val texte = texteVosk.takeIf(String::isNotBlank) ?: dernierTexte
        gestionnairePrincipal.post {
            if (idSession == numeroSession) rappelFinal?.invoke(texte.trim())
        }
    }

    private fun extraireTexte(hypothese: String?): String {
        if (hypothese.isNullOrBlank()) return ""
        return runCatching {
            JSONObject(hypothese).optString("text", "").trim()
        }.getOrDefault(hypothese.trim())
    }

    /** Copie une seule fois le modèle fourni dans les assets vers le stockage privé. */
    @Throws(IOException::class)
    private fun chargerModele(): Model {
        modele?.let { return it }
        synchronized(verrou) {
            modele?.let { return it }
            val dossierModele = File(context.filesDir, "modele-vosk-fr")
            val fichierCentral = File(dossierModele, "am/final.mdl")
            if (!fichierCentral.isFile) {
                if (dossierModele.exists()) dossierModele.deleteRecursively()
                dossierModele.mkdirs()
                try {
                    copierArborescenceAsset("model", dossierModele)
                } catch (exception: Exception) {
                    dossierModele.deleteRecursively()
                    throw IOException(
                        "Modèle Vosk absent. Le workflow GitHub doit intégrer le modèle français.",
                        exception
                    )
                }
            }
            if (!fichierCentral.isFile) {
                throw IOException("Le modèle vocal français est incomplet dans les assets.")
            }
            return Model(dossierModele.absolutePath).also { modele = it }
        }
    }

    @Throws(IOException::class)
    private fun copierArborescenceAsset(source: String, destination: File) {
        val enfants = context.assets.list(source)
            ?: throw IOException("Asset introuvable : $source")

        if (enfants.isEmpty()) {
            destination.parentFile?.mkdirs()
            context.assets.open(source).use { entree ->
                destination.outputStream().use { sortie -> entree.copyTo(sortie) }
            }
            return
        }

        if (!destination.exists() && !destination.mkdirs()) {
            throw IOException("Impossible de créer ${destination.absolutePath}")
        }
        enfants.forEach { enfant ->
            copierArborescenceAsset("$source/$enfant", File(destination, enfant))
        }
    }

    fun fermer() {
        runCatching { serviceVocal?.stop() }
        runCatching { serviceVocal?.shutdown() }
        runCatching { reconnaissance?.close() }
        runCatching { modele?.close() }
        runCatching { synthese?.stop() }
        runCatching { synthese?.shutdown() }
        serviceVocal = null
        reconnaissance = null
        modele = null
        synthese = null
        execurteur.shutdownNow()
    }
}
