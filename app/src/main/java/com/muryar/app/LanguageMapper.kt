package com.muryar.app

import java.text.Normalizer
import java.util.Locale

/** Intentions vocales françaises, interprétées localement sans réseau. */
sealed interface AssistantCommand {
    data class OpenApp(val nomApplication: String) : AssistantCommand
    data class CallContact(val nomContact: String) : AssistantCommand
    data class SendMessage(
        val nomContact: String,
        val message: String,
        val viaWhatsApp: Boolean
    ) : AssistantCommand
    data class ReadFile(val nomFichier: String) : AssistantCommand
    data class Unknown(val texteOriginal: String) : AssistantCommand
}

object LanguageMapper {
    private val extensionsAcceptees =
        "pdf|txt|docx|csv|json|png|jpe?g|webp|bmp|heic"

    /** Convertit les accents et la casse pour comparer les mots-clés hors ligne. */
    fun normaliser(texte: String): String = Normalizer
        .normalize(texte.lowercase(Locale.FRENCH), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    fun analyser(texteBrut: String): AssistantCommand {
        val texte = texteBrut.trim()
        if (texte.isBlank()) return AssistantCommand.Unknown(texteBrut)

        val normalise = normaliser(texte)

        // Le nom de fichier est extrait avec l'extension afin de retrouver un fichier mémorisé.
        val lecture = Regex(
            "\\b(?:lis|lire|resume|resumes|ouvre|ouvre-moi)\\s+(?:mon\\s+)?(?:fichier\\s+)?(.+?\\.(?:$extensionsAcceptees))\\b"
        ).find(normalise)
        if (lecture != null) {
            return AssistantCommand.ReadFile(lecture.groupValues[1].trim().trim('"', '\''))
        }

        // Le texte entre guillemets reste dans sa casse d'origine.
        val citation = Regex(
            "[\\\"'“](.+?)[\\\"'”](?:\\s+(?:sur|via)\\s+whatsapp)?$",
            RegexOption.IGNORE_CASE
        ).find(texte)
        if (citation != null) {
            val message = citation.groupValues[1].trim()
            val prefixe = texte.substring(0, citation.range.first)
            val prefixeNormalise = normaliser(prefixe)
            val motifMessage = Regex(
                "^(?:ecris|redige|envoie|prepare)(?:\\s+un)?(?:\\s+message)?\\s+a\\s+(.+?)(?:\\s+(?:sur|via)\\s+whatsapp)?$"
            ).find(prefixeNormalise)
            if (motifMessage != null && message.isNotBlank()) {
                val contact = motifMessage.groupValues[1]
                    .replace(Regex("\\s+(?:sur|via)\\s+whatsapp$"), "")
                    .trim()
                return AssistantCommand.SendMessage(
                    nomContact = contact,
                    message = message,
                    viaWhatsApp = normalise.contains("whatsapp")
                )
            }
        }

        val appel = Regex("^(?:appelle|telephone a|telephones a|compose le numero de)\\s+(.+)$")
            .find(normalise)
        if (appel != null) {
            return AssistantCommand.CallContact(appel.groupValues[1].trim())
        }

        val ouverture = Regex("^(?:ouvre|lance|demarre|demarrer)\\s+(?:l'application\\s+)?(.+)$")
            .find(normalise)
        if (ouverture != null) {
            return AssistantCommand.OpenApp(ouverture.groupValues[1].trim())
        }

        return AssistantCommand.Unknown(texte)
    }
}
