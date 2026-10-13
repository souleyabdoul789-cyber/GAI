package com.muryar.app

import android.content.Context
import android.net.Uri
import android.os.Build
import android.content.ContentUris
import android.content.pm.PackageManager
import android.Manifest
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import android.provider.OpenableColumns
import android.util.Xml
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.ZipInputStream

/** Lecture privée et locale des documents. Les URI choisies sont mémorisées pour les prochaines commandes vocales. */
class FileReaderEngine(private val context: Context) {
    private val preferences = context.getSharedPreferences("gai_fichiers_recents", Context.MODE_PRIVATE)
    private val limiteTexte = 100_000

    init {
        PDFBoxResourceLoader.init(context.applicationContext)
    }

    /** Ajoute un fichier choisi avec le sélecteur Android aux fichiers retrouvables par commande vocale. */
    fun memoriser(uri: Uri): String {
        val nom = nomAffiche(uri)
        preferences.edit().putString(uri.toString(), nom).apply()
        return nom
    }

    fun nomAffiche(uri: Uri): String {
        val colonnes = arrayOf(OpenableColumns.DISPLAY_NAME)
        runCatching {
            context.contentResolver.query(uri, colonnes, null, null, null)?.use { curseur ->
                if (curseur.moveToFirst()) {
                    val index = curseur.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) curseur.getString(index)?.takeIf(String::isNotBlank)?.let { return it }
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "document"
    }

    suspend fun lire(uri: Uri): String = withContext(Dispatchers.IO) {
        lireEnArrierePlan(uri)
    }

    /** Indique si le nom demandé a déjà été autorisé par l'utilisateur via le sélecteur Android. */
    fun estMemorise(nomRecherche: String): Boolean {
        val cible = nomRecherche.trim().lowercase(Locale.ROOT)
        return preferences.all.entries.any { entree ->
            entree.key.startsWith("content:") && entree.value is String &&
                ((entree.value as String).lowercase(Locale.ROOT) == cible ||
                    (entree.value as String).lowercase(Locale.ROOT).contains(cible))
        }
    }

    /** Recherche dans les documents ajoutés et, sur Android 8–12, dans le catalogue Téléchargements. */
    suspend fun lireParNom(nomRecherche: String): Pair<String, String> = withContext(Dispatchers.IO) {
        val cible = nomRecherche.trim().lowercase(Locale.ROOT)
        val fichierRecent = preferences.all.entries
            .asSequence()
            .filter { it.key.startsWith("content:") && it.value is String }
            .map { Uri.parse(it.key) to (it.value as String) }
            .firstOrNull { (_, nom) ->
                nom.lowercase(Locale.ROOT) == cible || nom.lowercase(Locale.ROOT).contains(cible)
            }
        val fichier = fichierRecent ?: chercherDansTelechargements(nomRecherche)
            ?: throw IOException(
                "Je ne trouve pas « $nomRecherche ». Sur Android 13 et plus, appuie sur « Ajouter un fichier » et sélectionne-le dans Téléchargements une fois."
            )
        fichier.second to lireEnArrierePlan(fichier.first)
    }

    private fun chercherDansTelechargements(nomRecherche: String): Pair<Uri, String>? {
        // Android 13+ réserve l'accès aux fichiers non multimédias au Storage Access Framework.
        if (Build.VERSION.SDK_INT > 32) return null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) return null

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Files.getContentUri("external")
        }
        val colonnes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME)
        } else {
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATA)
        }
        val nomNettoye = nomRecherche.trim().replace("%", "").replace("_", "")
        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
        } else {
            "${MediaStore.MediaColumns.DATA} LIKE ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
        }
        val arguments = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf("%$nomNettoye%")
        } else {
            arrayOf("%/Download/%", "%$nomNettoye%")
        }

        return runCatching {
            context.contentResolver.query(
                collection, colonnes, selection, arguments,
                "${MediaStore.MediaColumns.DISPLAY_NAME} COLLATE NOCASE ASC"
            )?.use { curseur ->
                val indexId = curseur.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val indexNom = curseur.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                while (curseur.moveToNext()) {
                    val nom = curseur.getString(indexNom) ?: continue
                    if (nom.equals(nomRecherche, ignoreCase = true) ||
                        nom.contains(nomRecherche, ignoreCase = true)
                    ) {
                        val id = curseur.getLong(indexId)
                        return@use ContentUris.withAppendedId(collection, id) to nom
                    }
                }
                null
            }
        }.getOrNull()
    }

    private fun lireEnArrierePlan(uri: Uri): String {
        val nom = nomAffiche(uri)
        val extension = nom.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val flux = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Android n'a pas pu ouvrir « $nom ».")

        return when (extension) {
            "txt", "csv", "json" -> flux.use { lireTexte(it, extension) }
            "pdf" -> flux.use { lirePdf(it) }
            "docx" -> flux.use { lireDocx(it) }
            "png", "jpg", "jpeg", "webp", "bmp", "heic" -> {
                // ML Kit est la dépendance embarquée, pas la variante de modèle téléchargé par Play Services.
                flux.close()
                lireImage(uri)
            }
            else -> {
                flux.close()
                throw IOException("Format non pris en charge : .$extension. Choisis TXT, PDF, DOCX, CSV, JSON ou une image.")
            }
        }.trim().take(limiteTexte).ifBlank {
            "Aucun texte exploitable n'a été trouvé dans « $nom ». Le PDF est peut-être scanné ou l'image illisible."
        }
    }

    private fun lireTexte(flux: InputStream, extension: String): String {
        val contenu = lireLimite(flux, limiteTexte)
        if (extension != "json") return contenu
        return runCatching {
            when {
                contenu.trimStart().startsWith("{") -> JSONObject(contenu).toString(2)
                contenu.trimStart().startsWith("[") -> JSONArray(contenu).toString(2)
                else -> contenu
            }
        }.getOrDefault(contenu)
    }

    private fun lirePdf(flux: InputStream): String {
        val document = PDDocument.load(flux)
        document.use { pdf ->
            if (pdf.isEncrypted) throw IOException("Ce PDF est chiffré et ne peut pas être lu sans mot de passe.")
            val extracteur = PDFTextStripper().apply {
                startPage = 1
                endPage = minOf(pdf.numberOfPages, 200)
            }
            return extracteur.getText(pdf).take(limiteTexte)
        }
    }

    private fun lireDocx(flux: InputStream): String {
        ZipInputStream(flux).use { zip ->
            while (true) {
                val entree = zip.nextEntry ?: break
                if (entree.name == "word/document.xml") {
                    val resultat = StringBuilder()
                    val analyseur = Xml.newPullParser()
                    analyseur.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
                    analyseur.setInput(zip, StandardCharsets.UTF_8.name())
                    var evenement = analyseur.eventType
                    while (evenement != XmlPullParser.END_DOCUMENT && resultat.length < limiteTexte) {
                        if (evenement == XmlPullParser.START_TAG) {
                            when (analyseur.name) {
                                "t" -> {
                                    resultat.append(analyseur.nextText()).append(' ')
                                    evenement = analyseur.eventType
                                    continue
                                }
                                "tab" -> resultat.append('\t')
                                "br", "cr" -> resultat.append('\n')
                                "p" -> resultat.append('\n')
                            }
                        }
                        evenement = analyseur.next()
                    }
                    return resultat.toString().take(limiteTexte)
                }
                zip.closeEntry()
            }
        }
        throw IOException("Le document DOCX ne contient pas word/document.xml.")
    }

    private fun lireImage(uri: Uri): String {
        val reconnaissance = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            val image = InputImage.fromFilePath(context, uri)
            Tasks.await(reconnaissance.process(image)).text.take(limiteTexte)
        } catch (exception: Exception) {
            throw IOException("L'OCR hors ligne n'a pas pu lire cette image : ${exception.localizedMessage ?: "format illisible"}", exception)
        } finally {
            reconnaissance.close()
        }
    }

    private fun lireLimite(flux: InputStream, maximum: Int): String {
        val resultat = StringBuilder()
        BufferedReader(InputStreamReader(flux, StandardCharsets.UTF_8)).use { lecteur ->
            val tampon = CharArray(4096)
            while (resultat.length < maximum) {
                val restant = minOf(tampon.size, maximum - resultat.length)
                val lus = lecteur.read(tampon, 0, restant)
                if (lus <= 0) break
                resultat.append(tampon, 0, lus)
            }
        }
        return resultat.toString()
    }

    /** Résumé extractif déterministe : aucun modèle génératif ni accès Internet. */
    fun resumerPourLaVoix(nom: String, contenu: String): String {
        val texte = contenu.replace(Regex("\\s+"), " ").trim()
        if (texte.isBlank()) return "Le fichier $nom ne contient pas de texte lisible."
        val phrases = texte.split(Regex("(?<=[.!?])\\s+"))
            .map(String::trim)
            .filter(String::isNotBlank)
        val resume = if (phrases.size <= 3) {
            phrases.joinToString(" ")
        } else {
            // Favorise les phrases informatives, en gardant leur ordre dans le document.
            val stop = setOf("avec", "dans", "pour", "mais", "donc", "ainsi", "elle", "elles", "nous", "vous", "leur", "leurs", "est", "sont", "été", "être", "avoir", "aux", "des", "les", "une", "que", "qui", "sur", "par", "pas", "plus", "comme", "cette", "cet", "ces", "tout", "tous")
            val selection = phrases.mapIndexed { index, phrase ->
                val mots = LanguageMapper.normaliser(phrase)
                    .split(Regex("[^a-z0-9]+"))
                    .filter { it.length > 3 && it !in stop }
                Triple(index, phrase, mots.size)
            }.sortedByDescending { it.third }
                .take(3)
                .sortedBy { it.first }
                .map { it.second }
            selection.joinToString(" ")
        }
        return "Résumé de $nom. ${resume.take(700)}".take(760)
    }
}
