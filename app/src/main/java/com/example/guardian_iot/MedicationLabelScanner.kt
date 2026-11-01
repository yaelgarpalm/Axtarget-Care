package com.example.guardian_iot

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

/** Una línea leída de la foto; el alto y la posición ayudan a ubicar el nombre y la dosis. */
data class OcrLine(val text: String, val heightPx: Int = 0, val topPx: Int = 0)

/** Lo que la app propone a partir de una caja o receta. Siempre se revisa antes de guardar. */
data class MedicationLabelGuess(
    val name: String = "",
    val presentation: String = "",
    val dose: String = "",
    val frequency: String = "",
    val anchorTime: String = "",
) {
    val isEmpty: Boolean
        get() = name.isBlank() && presentation.isBlank() && dose.isBlank() && frequency.isBlank()
}

/** De dónde se lee la foto: en el propio teléfono o con un modelo en la nube. */
enum class MedicationScanMode { DEVICE, CLOUD }

/** Punto de entrada desde el formulario hacia la cámara o la galería del teléfono. */
interface MedicationLabelScanner {
    val cloudAvailable: Boolean
    fun takePhoto(mode: MedicationScanMode, onResult: (MedicationLabelGuess?, String?) -> Unit)
    fun pickFromGallery(mode: MedicationScanMode, onResult: (MedicationLabelGuess?, String?) -> Unit)
}

/**
 * Heurísticas sobre el texto de una caja o receta. No valida indicaciones médicas: solo propone
 * nombre, presentación, dosis escrita, frecuencia y una hora, que la persona confirma o corrige.
 */
object MedicationLabelParser {
    private val explicitName = Regex(
        "(?i)^(?:medicamento|nombre(?: del medicamento)?|rx|prescripci[oó]n|indicaci[oó]n(?:es)?)\\s*[:\\-–]\\s*(.{3,60})$",
    )
    private val explicitPresentation = Regex("(?i)^presentaci[oó]n\\s*[:\\-–]\\s*(.{2,60})$")
    private val containerPresentation = Regex(
        "(?i)\\b(?:cajas?|frasco|envase|tubo|bl[ií]ster|estuche|bolsa|gotero|jeringa(?: prellenada)?|aplicador|sachet)\\b.{0,40}",
    )
    // Solo plurales: "1 tableta" es una instrucción de toma, no una presentación.
    private val unitCountPresentation = Regex(
        "(?i)\\b\\d{1,4}\\s*(?:tabletas(?: recubiertas)?|grageas|c[aá]psulas(?: blandas| duras)?|comprimidos(?: recubiertos)?|sobres|sachets|parches|supositorios|ampolletas|[aá]mpulas|viales|aplicadores|plumas)\\b",
    )
    private val presentationNoise = Regex(
        "(?i)\\b(?:mantengase|conservese|alcance|temperatura|almacenar|proteger|luz|solar|ninos|prospecto|tomar|tome|administrar|aplicar|cada|dosis)\\b",
    )
    private val strength = Regex(
        "(?i)(?<![\\p{L}\\p{N}])(\\d{1,4}(?:[.,]\\d{1,3})?)\\s*(mg|mcg|µg|ug|g|ml|ui|u\\.?i\\.?|%)(?:\\s*/\\s*(?:(\\d{1,4}(?:[.,]\\d{1,3})?)\\s*)?(mg|ml|g))?(?![\\p{L}\\p{N}])",
    )
    private val containerWords = Regex(
        "(?i)\\b(?:caja|frasco|envase|tubo|bl[ií]ster|estuche|bolsa|gotero|jeringa|contenido|contiene|presentaci[oó]n|excipientes)\\b",
    )
    private val intervalHours = Regex(
        "(?i)\\bcada\\s+(\\d{1,2}|dos|tres|cuatro|seis|ocho|doce|veinticuatro)\\s*(?:horas?|hrs?|h)\\b",
    )
    private val compactInterval = Regex("(?i)\\bc\\s*/\\s*(\\d{1,2})\\s*(?:horas?|hrs?|h)\\b")
    private val qInterval = Regex("(?i)\\bq\\.?\\s*(\\d{1,2})\\s*(?:hs?|hrs?)\\b")
    private val intervalDays = Regex("(?i)\\bcada\\s+(\\d{1,2}|dos|tres)\\s*d[ií]as?\\b")
    private val dailyText = Regex("(?i)\\b(?:diaria|diario|una vez al d[ií]a|1 vez al d[ií]a|cada d[ií]a)\\b")
    private val weeklyText = Regex("(?i)\\b(?:semanal|una vez a la semana|cada semana)\\b")
    private val amPmTime = Regex("(?i)\\b(\\d{1,2})(?::(\\d{2}))?\\s*(a\\.?m\\.?|p\\.?m\\.?)\\b")
    private val atTime = Regex(
        "(?i)\\ba\\s+las?\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(?:de la\\s+(ma[nñ]ana|tarde|noche))?\\b",
    )
    private val clockTime = Regex("\\b(?:[01]?\\d|2[0-3]):[0-5]\\d\\b")
    private val enumeration = Regex("^(?:\\d{1,2}[.)]|[-*•·])\\s*")
    private val whitespace = Regex("\\s+")

    private val numberWords = mapOf(
        "dos" to 2, "tres" to 3, "cuatro" to 4, "seis" to 6, "ocho" to 8,
        "doce" to 12, "veinticuatro" to 24,
    )

    private val formWords = setOf(
        "tableta", "tabletas", "capsula", "capsulas", "comprimido", "comprimidos", "suspension",
        "jarabe", "solucion", "gotas", "crema", "gel", "polvo", "inyectable", "supositorio",
        "supositorios", "parche", "parches", "sobre", "sobres", "ampolleta", "ampolletas",
        "vial", "viales", "aerosol", "spray", "locion", "shampoo", "unguento", "pomada",
        "gragea", "grageas", "recubierta", "recubiertas", "recubierto", "recubiertos",
        "liberacion", "prolongada", "blanda", "blandas", "dura", "duras",
    )
    // Palabras que delatan texto de empaque, receta, publicidad o laboratorio y no el medicamento.
    private val stopWords = setOf(
        "mg", "ml", "mcg", "ug", "g", "ui", "kg", "caja", "cajas", "frasco", "frascos", "envase",
        "tubo", "blister", "estuche", "bolsa", "con", "para", "por", "sin", "de", "del", "la", "el",
        "los", "las", "y", "en", "via", "oral", "cada", "hora", "horas", "dia", "dias", "semana",
        "tomar", "tome", "administrar", "aplicar", "dosis", "indicacion", "indicaciones", "lote",
        "caducidad", "cad", "contenido", "contiene", "excipientes", "fabricante", "laboratorio",
        "laboratorios", "distribuido", "distribuidor", "farmaceutica", "generico", "medicamento",
        "nombre", "rx", "receta", "prescripcion", "paciente", "medico", "medica", "doctor",
        "doctora", "consultorio", "clinica", "hospital", "direccion", "telefono", "tel", "cedula",
        "especialidad", "fecha", "edad", "firma", "sello", "atentamente", "mantengase", "conservese",
        "almacenar", "temperatura", "ambiente", "proteger", "luz", "solar", "ninos", "alcance",
        "leer", "prospecto", "advertencia", "advertencias", "precauciones", "contraindicaciones",
        "registro", "numero", "clave", "marca", "hecho", "mexico", "cofepris", "salud",
        "secretaria", "servicio", "gobierno", "uso", "humano", "venta", "bajo", "libre", "casa",
        "lab", "genomma", "sanofi", "pfizer", "bayer", "novartis", "roche", "merck", "astrazeneca",
        "gsk", "boehringer", "liomont", "pisa", "senosiain", "silanes", "carnot", "teva",
        "armstrong", "chinoin", "raam", "opella", "grunenthal", "stendhal", "kendrick", "mylan",
        "viatris", "alivio", "rapido", "rapida", "dolor", "fiebre", "gripe", "tos", "resfriado",
        "nueva", "nuevo", "formula", "forte", "extra", "fuerte", "max", "plus", "sabor", "menta",
        "limon", "naranja", "azucar", "gratis", "promocion", "precio", "descuento", "farmacia",
        "ahorro", "oferta", "frasco", "aerosol", "total", "doble", "triple", "mini", "compacto",
    )

    private data class CatalogEntry(val key: List<String>, val folded: List<String>, val display: String)

    private data class DoseCandidate(
        val value: String,
        val index: Int,
        val height: Int,
        val nearName: Boolean,
    )

    /** Nombres frecuentes en recetas y cajas de México; se usan para corregir lecturas dudosas. */
    private val catalogNames = listOf(
        "Paracetamol", "Acetaminofén", "Ibuprofeno", "Naproxeno", "Diclofenaco", "Ketorolaco",
        "Celecoxib", "Tramadol", "Ácido acetilsalicílico", "Metamizol", "Losartán",
        "Losartán potásico", "Enalapril", "Enalapril maleato", "Captopril", "Valsartán",
        "Telmisartán", "Amlodipino", "Amlodipino besilato", "Nifedipino", "Verapamilo",
        "Metoprolol", "Metoprolol succinato", "Metoprolol tartrato", "Carvedilol", "Propranolol",
        "Bisoprolol", "Atenolol", "Hidroclorotiazida", "Furosemida", "Espironolactona",
        "Atorvastatina", "Atorvastatina cálcica", "Rosuvastatina", "Rosuvastatina cálcica",
        "Simvastatina", "Pravastatina", "Metformina", "Metformina clorhidrato",
        "Clorhidrato de metformina", "Glibenclamida", "Glimepirida", "Sitagliptina",
        "Dapagliflozina", "Empagliflozina", "Levotiroxina", "Omeprazol", "Pantoprazol",
        "Esomeprazol", "Ranitidina", "Famotidina", "Sucralfato", "Metoclopramida", "Ondansetrón",
        "Domperidona", "Loperamida", "Salbutamol", "Beclometasona", "Budesonida", "Montelukast",
        "Loratadina", "Cetirizina", "Clorfeniramina", "Difenhidramina", "Amoxicilina",
        "Amoxicilina ácido clavulánico", "Azitromicina", "Claritromicina", "Eritromicina",
        "Cefalexina", "Cefuroxima", "Ceftriaxona", "Ciprofloxacino", "Levofloxacino",
        "Trimetoprima sulfametoxazol", "Metronidazol", "Nitrofurantoína", "Fluconazol",
        "Itraconazol", "Aciclovir", "Valaciclovir", "Oseltamivir", "Sertralina",
        "Sertralina clorhidrato", "Fluoxetina", "Paroxetina", "Escitalopram", "Citalopram",
        "Venlafaxina", "Duloxetina", "Amitriptilina", "Mirtazapina", "Alprazolam", "Clonazepam",
        "Lorazepam", "Diazepam", "Zolpidem", "Quetiapina", "Risperidona", "Olanzapina",
        "Haloperidol", "Levodopa carbidopa", "Carbamazepina", "Ácido valproico",
        "Valproato de sodio", "Lamotrigina", "Levetiracetam", "Fenitoína", "Gabapentina",
        "Pregabalina", "Sumatriptán", "Alopurinol", "Colchicina", "Prednisona", "Prednisolona",
        "Dexametasona", "Hidrocortisona", "Warfarina", "Rivaroxabán", "Apixabán", "Clopidogrel",
        "Ticagrelor", "Sildenafilo", "Tadalafilo", "Tamsulosina", "Tamsulosina clorhidrato",
        "Finasterida", "Oxibutinina", "Tolterodina", "Sulfato ferroso", "Ácido fólico",
        "Vitamina D", "Vitamina D3", "Vitamina B12", "Complejo B", "Carbonato de calcio",
        "Cloruro de potasio", "Hidróxido de aluminio", "Hidróxido de magnesio", "Lactulosa",
        "Bisacodilo", "Senósidos", "Glicerina", "Insulina glargina", "Insulina lispro",
        "Insulina NPH", "Donepezilo", "Memantina", "Rivastigmina",
        "Tempra", "Tafirol", "Advil", "Motrin", "Aspirina", "Buscapina", "Neomelubrina", "Tabcin",
        "Panadol", "Desenfriol", "Sal de Uvas Picot", "Voltaren", "Enantyum", "Cozaar", "Micardis",
        "Norvasc", "Glucophage", "Losec", "Amoxil", "Augmentin", "Zithromax", "Bactrim", "Flagyl",
        "Diflucan", "Zoloft", "Prozac", "Lexapro", "Rivotril", "Xanax", "Seroquel", "Eutirox",
        "Zocor", "Lipitor", "Crestor", "Januvia", "Jardiance", "Forxiga", "Plavix", "Eliquis",
        "Xarelto", "Ventolin", "Flixotide", "Singulair", "Zyrtec", "Claritin", "Allegra",
    )

    private val catalog: List<CatalogEntry> = catalogNames
        .map { display ->
            val key = tokenize(display)
            CatalogEntry(key, key.map(::foldOcr), display)
        }
        .filter { it.key.isNotEmpty() }
        .sortedWith(compareByDescending<CatalogEntry> { it.key.size }.thenByDescending { it.display.length })

    fun parse(lines: List<OcrLine>): MedicationLabelGuess {
        val cleaned = lines.mapNotNull { line ->
            val text = line.text
                .replace(whitespace, " ")
                .trim()
                .trim('|', '·', '-', ';')
                .replace(enumeration, "")
                .trim()
            if (text.length < 2) null else OcrLine(text, line.heightPx, line.topPx)
        }
        if (cleaned.isEmpty()) return MedicationLabelGuess()
        val joined = cleaned.joinToString("\n") { it.text }
        val name = pickName(cleaned)
        return MedicationLabelGuess(
            name = name,
            presentation = pickPresentation(cleaned),
            dose = pickDose(cleaned, name),
            frequency = pickFrequency(joined),
            anchorTime = pickAnchorTime(joined),
        )
    }

    /**
     * Ajusta un resultado externo (por ejemplo de un modelo en la nube) a los formatos que usa
     * el formulario: nombres sin mayúsculas gritadas, dosis normalizadas y frecuencia válida.
     */
    fun refine(guess: MedicationLabelGuess): MedicationLabelGuess = guess.copy(
        name = if (guess.name.isBlank()) "" else catalogName(guess.name) ?: tidyName(guess.name),
        presentation = tidyPresentation(guess.presentation),
        dose = tidyDose(guess.dose),
        frequency = normalizeFrequency(guess.frequency),
        anchorTime = pickAnchorTime(guess.anchorTime),
    )

    private fun tidyDose(raw: String): String {
        val value = raw.replace(whitespace, " ").trim()
        if (value.isEmpty()) return ""
        val match = strength.find(value)
        return if (match != null) formatStrength(match) else value.take(60)
    }

    private fun normalizeFrequency(raw: String): String {
        val value = raw.trim()
        if (value.isEmpty()) return ""
        MedicationSchedulePolicy.frequencyOptions.firstOrNull { it.equals(value, ignoreCase = true) }
            ?.let { return it }
        return pickFrequency(value)
    }

    private fun pickName(lines: List<OcrLine>): String {
        lines.forEach { line ->
            explicitName.find(line.text)?.groupValues?.get(1)?.let { explicit ->
                val value = tidyName(removeTrailingFormWord(brandPrefix(explicit) ?: explicit))
                if (value.length in 3..64) return value
            }
        }
        // El catálogo reconoce el medicamento aunque el OCR cambie una letra o venga la sal.
        val ranked = lines.withIndex().sortedWith(
            compareByDescending<IndexedValue<OcrLine>> { it.value.heightPx }
                .thenBy { it.value.topPx }
                .thenBy { it.index },
        )
        ranked.forEach { (_, line) -> catalogName(line.text)?.let { return it } }
        // Sin catálogo, la marca suele ser el texto más grande del empaque.
        ranked.forEach { (_, line) -> nameCandidate(line.text)?.let { return it } }
        return ""
    }

    private fun nameCandidate(raw: String): String? {
        val text = raw.trim().trim('®', '™', '.', ',', ':', '-', '|')
        if (text.length < 3 || text.length > 64) return null
        val prefix = brandPrefix(text) ?: return null
        val withoutForm = removeTrailingFormWord(prefix)
        if (withoutForm.length < 3 || withoutForm.any(Char::isDigit)) return null
        val tokens = tokenize(withoutForm)
        if (tokens.isEmpty() || tokens.size > 5) return null
        if (tokens.any { it in stopWords || it in formWords }) return null
        if (tokens.any { it.length > 24 }) return null
        if (tokens.all { it.length < 3 }) return null
        return tidyName(withoutForm)
    }

    private fun catalogName(raw: String): String? {
        val rawTokens = raw.split(Regex("[^\\p{L}\\p{N}]+")).filter(String::isNotBlank)
        if (rawTokens.isEmpty()) return null
        val tokens = rawTokens.map { foldOcr(normalize(it)) }
        var hit: CatalogEntry? = null
        var bestScore = 0
        catalog.forEach { entry ->
            val size = entry.folded.size
            if (size == 0 || size > tokens.size) return@forEach
            for (start in 0..tokens.size - size) {
                val slice = tokens.subList(start, start + size)
                val exact = slice == entry.folded
                val fuzzy = !exact && slice.indices.all { i -> closeEnough(slice[i], entry.folded[i]) }
                if (!exact && !fuzzy) continue
                val score = size * 100 + entry.display.length * 2 + if (exact) 60 else 0
                if (score > bestScore) {
                    bestScore = score
                    hit = entry
                }
            }
        }
        return hit?.display
    }

    /** Toma el texto desde el inicio hasta la primera unidad, número suelto o separador. */
    private fun brandPrefix(text: String): String? {
        val tokens = text.split(' ', '/', ',', ';', ':', '(', ')', '|').filter(String::isNotBlank)
        val kept = mutableListOf<String>()
        for (token in tokens) {
            val normalized = normalize(token)
            val numeric = token.all { it.isDigit() || it == '.' || it == ',' || it == '-' }
            if (numeric || normalized in setOf("mg", "ml", "mcg", "ug", "g", "ui", "%")) break
            if (token.none { it.isLetter() }) break
            kept.add(token)
            if (kept.size == 6) break
        }
        val result = kept.joinToString(" ").trim().trim('®', '™')
        return result.takeIf { it.any(Char::isLetter) }
    }

    private fun removeTrailingFormWord(value: String): String {
        var result = value.trim()
        while (true) {
            val tokens = tokenize(result)
            val last = tokens.lastOrNull() ?: return result
            if (last !in formWords) return result
            result = result.split(whitespace).dropLast(1).joinToString(" ").trim()
            if (result.isBlank()) return ""
        }
    }

    private fun pickPresentation(lines: List<OcrLine>): String {
        lines.forEach { line ->
            explicitPresentation.find(line.text)?.groupValues?.get(1)?.let { return tidyPresentation(it) }
        }
        val patterns = listOf(containerPresentation, unitCountPresentation)
        for (pattern in patterns) {
            lines.forEach { line ->
                if (presentationNoise.containsMatchIn(line.text)) return@forEach
                pattern.find(line.text)?.value?.let { match ->
                    val value = tidyPresentation(match)
                    if (value.length >= 4) return value
                }
            }
        }
        return ""
    }

    private fun pickDose(lines: List<OcrLine>, name: String): String {
        val nameTokens = tokenize(name)
        val candidates = lines.mapIndexedNotNull { index, line ->
            val match = strength.find(line.text) ?: return@mapIndexedNotNull null
            val unit = normalizeUnit(match.groupValues[2])
            val hasRatio = match.groupValues[3].isNotBlank() ||
                match.groupValues[4].isNotBlank() ||
                line.text.contains('/')
            // "Frasco con 120 ml" o "Tubo con 30 g" son la presentación, no la dosis.
            if (!hasRatio && unit in setOf("ml", "g") && containerWords.containsMatchIn(line.text)) {
                return@mapIndexedNotNull null
            }
            val lineTokens = tokenize(line.text)
            val nearName = nameTokens.isNotEmpty() && nameTokens.any { token -> lineTokens.any { it == token } }
            DoseCandidate(formatStrength(match), index, line.heightPx, nearName)
        }
        return candidates.sortedWith(
            compareByDescending<DoseCandidate> { it.nearName }
                .thenByDescending { it.height }
                .thenBy { it.index },
        ).firstOrNull()?.value.orEmpty()
    }

    private fun formatStrength(match: MatchResult): String {
        val base = "${match.groupValues[1]} ${normalizeUnit(match.groupValues[2])}"
        val perNumber = match.groupValues[3]
        val perUnit = match.groupValues[4]
        if (perUnit.isBlank()) return base
        val per = if (perNumber.isBlank()) normalizeUnit(perUnit) else "$perNumber ${normalizeUnit(perUnit)}"
        return "$base / $per"
    }

    private fun normalizeUnit(unit: String): String = when (val clean = unit.replace(".", "").lowercase(Locale.ROOT)) {
        "ui" -> "UI"
        "µg", "ug" -> "mcg"
        else -> clean
    }

    private fun pickFrequency(text: String): String {
        intervalHours.find(text)?.groupValues?.get(1)?.let { raw ->
            val hours = raw.toIntOrNull() ?: numberWords[raw.lowercase(Locale.ROOT)] ?: return@let
            MedicationSchedulePolicy.intervalFrequency(hours)?.let { return it }
        }
        listOf(compactInterval, qInterval).forEach { pattern ->
            pattern.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { hours ->
                MedicationSchedulePolicy.intervalFrequency(hours)?.let { return it }
            }
        }
        val dayWord = intervalDays.find(text)?.groupValues?.get(1)?.lowercase(Locale.ROOT)
        when (dayWord) {
            "dos", "2" -> return "Cada 2 días"
            "tres", "3" -> return "Cada 3 días"
        }
        if (weeklyText.containsMatchIn(text)) return "Semanal"
        if (dailyText.containsMatchIn(text)) return "Diaria"
        return ""
    }

    private fun pickAnchorTime(text: String): String {
        amPmTime.find(text)?.let { match ->
            val hour = match.groupValues[1].toIntOrNull() ?: return@let
            val minute = match.groupValues[2].toIntOrNull() ?: 0
            val isPm = match.groupValues[3].lowercase(Locale.ROOT).startsWith("p")
            val converted = when {
                isPm && hour < 12 -> hour + 12
                !isPm && hour == 12 -> 0
                else -> hour
            }
            if (converted in 0..23 && minute in 0..59) return formatTime(converted, minute)
        }
        atTime.find(text)?.let { match ->
            val hour = match.groupValues[1].toIntOrNull() ?: return@let
            val minute = match.groupValues[2].toIntOrNull() ?: 0
            val period = match.groupValues[3].ifBlank { null }
            val converted = periodHour(hour, period) ?: return@let
            if (minute in 0..59) return formatTime(converted, minute)
        }
        val clock = clockTime.find(text)?.value
        if (clock != null) {
            val parts = clock.split(':')
            return "%02d:%02d".format(Locale.ROOT, parts[0].toInt(), parts[1].toInt())
        }
        return ""
    }

    private fun periodHour(hour: Int, period: String?): Int? {
        if (hour !in 0..23) return null
        val normalized = normalize(period.orEmpty())
        return when {
            normalized.startsWith("tarde") && hour < 12 -> hour + 12
            normalized.startsWith("noche") && hour == 12 -> 0
            normalized.startsWith("noche") && hour < 12 -> hour + 12
            else -> hour
        }
    }

    private fun formatTime(hour: Int, minute: Int): String =
        "%02d:%02d".format(Locale.ROOT, hour, minute)

    private fun tidyPresentation(value: String): String {
        val trimmed = value
            .replace(whitespace, " ")
            .replace(trailingStrength, "$1")
            .trim()
            .trim('.', ',', ';', ':', '-')
        return titleIfMostlyUpperCase(trimmed)
    }

    private val trailingStrength = Regex(
        "(?i)^(.*\\b(?:tabletas?|grageas?|c[aá]psulas?|comprimidos?|sobres?|sachets?|parches?|supositorios?|ampolletas?|[aá]mpulas?|viales?)\\b.*?)\\s+(?:de|con)\\s+\\d.*$",
    )

    private fun tidyName(value: String): String {
        val cleaned = value.replace(whitespace, " ").trim().trim('.', ',', ';', ':', '-', '®', '™')
        return titleIfMostlyUpperCase(cleaned)
    }

    /** El empaque suele venir en mayúsculas; se pasa a mayúscula inicial para poder leerlo. */
    private fun titleIfMostlyUpperCase(value: String): String {
        val letters = value.filter(Char::isLetter)
        if (letters.length < 3) return value
        val upper = letters.count(Char::isUpperCase)
        if (upper * 10 < letters.length * 6) return value
        return value.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }
    }

    /** Corrige confusiones típicas del OCR antes de comparar contra el catálogo. */
    private fun foldOcr(token: String): String = token
        .replace("rn", "m")
        .replace('0', 'o')
        .replace('1', 'l')
        .replace('5', 's')
        .replace('8', 'b')

    private fun closeEnough(observed: String, expected: String): Boolean {
        if (observed == expected) return true
        val size = min(observed.length, expected.length)
        if (size < 6 || observed.first() != expected.first()) return false
        return levenshteinWithin(observed, expected, if (size >= 10) 2 else 1)
    }

    private fun levenshteinWithin(a: String, b: String, limit: Int): Boolean {
        if (abs(a.length - b.length) > limit) return false
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            var rowMin = current[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1, previous[j - 1] + cost)
                if (current[j] < rowMin) rowMin = current[j]
            }
            if (rowMin > limit) return false
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length] <= limit
    }

    private fun tokenize(value: String): List<String> =
        normalize(value).split(Regex("[^a-z0-9]+")).filter(String::isNotBlank)

    private fun normalize(value: String): String = Normalizer
        .normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
}

/** Ejecuta el reconocimiento de texto en el teléfono: la imagen no se sube ni se conserva. */
object MedicationLabelReader {
    fun read(context: Context, uri: Uri, onResult: (MedicationLabelGuess?, String?) -> Unit) {
        val image = try {
            InputImage.fromFilePath(context, uri)
        } catch (_: Exception) {
            onResult(null, "No se pudo abrir la imagen. Intenta de nuevo.")
            return
        }
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(image)
            .addOnSuccessListener { text ->
                val guess = MedicationLabelParser.parse(text.toOcrLines())
                if (guess.isEmpty) {
                    onResult(null, "No se detectó texto legible. Acerca la cámara al nombre y la presentación.")
                } else {
                    onResult(guess, null)
                }
            }
            .addOnFailureListener {
                onResult(null, "No se pudo leer la imagen. Revisa que tenga buena luz e intenta otra vez.")
            }
    }

    private fun Text.toOcrLines(): List<OcrLine> = textBlocks.flatMap { block -> block.lines }.map { line ->
        val box = line.boundingBox
        OcrLine(line.text, box?.height() ?: 0, box?.top ?: 0)
    }
}
