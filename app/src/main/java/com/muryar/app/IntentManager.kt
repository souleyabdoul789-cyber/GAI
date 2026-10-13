package com.muryar.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Normalizer
import java.util.Locale

/** Exécution locale des actions Android demandées par la voix. */
class IntentManager(context: Context) {
    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager

    private val applicationsConnues = mapOf(
        "whatsapp" to "com.whatsapp",
        "whatsapp business" to "com.whatsapp.w4b",
        "youtube" to "com.google.android.youtube",
        "chrome" to "com.android.chrome",
        "telephone" to "com.google.android.dialer",
        "appareil photo" to "com.android.camera2",
        "camera" to "com.android.camera2",
        "parametres" to "com.android.settings",
        "messages" to "com.google.android.apps.messaging"
    )

    suspend fun executer(commande: AssistantCommand): String = when (commande) {
        is AssistantCommand.OpenApp -> withContext(Dispatchers.Main) {
            ouvrirApplication(commande.nomApplication)
        }
        is AssistantCommand.CallContact -> {
            val numero = withContext(Dispatchers.IO) { chercherNumero(commande.nomContact) }
            withContext(Dispatchers.Main) {
                if (numero == null) {
                    "Je ne trouve pas le numéro de ${commande.nomContact} dans les contacts."
                } else {
                    appelerNumero(numero, commande.nomContact)
                }
            }
        }
        is AssistantCommand.SendMessage -> {
            val numero = withContext(Dispatchers.IO) { chercherNumero(commande.nomContact) }
            withContext(Dispatchers.Main) {
                if (numero == null) {
                    "Je ne trouve pas le numéro de ${commande.nomContact} dans les contacts."
                } else {
                    preparerMessage(numero, commande.message, commande.viaWhatsApp)
                }
            }
        }
        is AssistantCommand.ReadFile -> "Je recherche le fichier ${commande.nomFichier}."
        is AssistantCommand.Unknown -> "Je n'ai pas reconnu cette commande : ${commande.texteOriginal}"
    }

    /** Ouvre un lanceur installé, sans télécharger ni installer d'application. */
    @Suppress("DEPRECATION")
    private fun ouvrirApplication(nomDemande: String): String {
        val cle = normaliser(nomDemande)
        val paquetConnu = applicationsConnues[cle]
            ?: applicationsConnues.entries.firstOrNull { (nom, _) -> cle.contains(nom) }?.value

        if (paquetConnu != null) {
            val lanceur = packageManager.getLaunchIntentForPackage(paquetConnu)
            if (lanceur != null) {
                lanceur.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                appContext.startActivity(lanceur)
                return "J'ouvre $nomDemande."
            }
        }

        val rechercheLanceur = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resultats = packageManager.queryIntentActivities(rechercheLanceur, 0)
        val correspondance = resultats.firstOrNull { info ->
            val nomInstalle = normaliser(info.loadLabel(packageManager).toString())
            nomInstalle == cle || nomInstalle.contains(cle) || cle.contains(nomInstalle)
        }

        val activite = correspondance?.activityInfo
        if (activite != null) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setClassName(activite.packageName, activite.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(intent)
            return "J'ouvre ${activite.loadLabel(packageManager)}."
        }
        return "Je ne trouve pas l'application $nomDemande sur ce téléphone."
    }

    /** Recherche exacte puis partielle, insensible aux accents, via ContentResolver. */
    private fun chercherNumero(nom: String): String? {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) return null

        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val contacts = mutableListOf<Pair<String, String>>()
        appContext.contentResolver.query(
            uri,
            projection,
            null,
            null,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} COLLATE NOCASE ASC"
        )?.use { curseur ->
            val indexNom = curseur.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val indexNumero = curseur.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (curseur.moveToNext()) {
                if (indexNom >= 0 && indexNumero >= 0) {
                    val nomContact = curseur.getString(indexNom)?.trim().orEmpty()
                    val numero = curseur.getString(indexNumero)?.trim().orEmpty()
                    if (nomContact.isNotBlank() && numero.isNotBlank()) {
                        contacts += normaliser(nomContact) to numero
                    }
                }
            }
        }

        val cible = normaliser(nom)
        return contacts.firstOrNull { it.first == cible }?.second
            ?: contacts.firstOrNull { it.first.contains(cible) || cible.contains(it.first) }?.second
    }

    private fun appelerNumero(numero: String, nomContact: String): String {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED
        ) return "Autorisation d'appel manquante. Réessaie après l'avoir accordée."

        return runCatching {
            val appel = Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(numero)}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(appel)
            "J'appelle $nomContact."
        }.getOrElse { "Impossible de lancer l'appel : ${it.localizedMessage ?: "erreur Android"}." }
    }

    /** Prépare le message ; l'utilisateur confirme lui-même son envoi dans l'application choisie. */
    private fun preparerMessage(numero: String, texte: String, viaWhatsApp: Boolean): String {
        return runCatching {
            if (viaWhatsApp) {
                val partage = Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, texte)
                    .setPackage("com.whatsapp")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (packageManager.resolveActivity(partage, 0) != null) {
                    appContext.startActivity(partage)
                    "Message préparé dans WhatsApp. Choisis le destinataire puis confirme l'envoi."
                } else {
                    ouvrirComposeurSms(numero, texte)
                    "WhatsApp est absent. J'ai ouvert l'application SMS avec le texte prérempli."
                }
            } else {
                ouvrirComposeurSms(numero, texte)
                "Message prérempli. Vérifie-le puis appuie sur Envoyer."
            }
        }.getOrElse { "Impossible de préparer le message : ${it.localizedMessage ?: "erreur Android"}." }
    }

    private fun ouvrirComposeurSms(numero: String, texte: String) {
        val sms = Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", numero, null))
            .putExtra("sms_body", texte)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(sms)
    }

    private fun normaliser(valeur: String): String = Normalizer
        .normalize(valeur.lowercase(Locale.FRENCH), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .trim()
}
