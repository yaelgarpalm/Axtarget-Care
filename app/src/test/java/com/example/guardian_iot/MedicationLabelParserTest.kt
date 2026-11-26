package com.example.guardian_iot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MedicationLabelParserTest {
    @Test
    fun readsAllPrescriptionEntriesWithoutMovingDosesOrNotesBetweenThem() {
        val result = MedicationLabelParser.readDocument(
            listOf(
                "RECETA MÉDICA",
                "Paciente: Persona de prueba",
                "1. Metformina 850 mg",
                "Tomar 1 tableta por vía oral cada 12 horas durante 10 días",
                "Primera toma a las 8:30 p.m.",
                "2. Losartán 50 mg",
                "Tomar 1 tableta diariamente",
                "3. Vitamed 10 mg",
                "Administrar 2 gotas cada 8 horas",
                "Notas: Llevar un registro para la próxima consulta.",
                "Observaciones: Revisar las instrucciones impresas.",
                "Firma del médico",
            ).map { OcrLine(it) },
            MedicationDocument.PRESCRIPTION,
        )
        assertEquals(listOf("Metformina", "Losartán", "Vitamed"), result.entries.map { it.name })
        assertEquals(listOf("1 tableta", "1 tableta", "2 gotas"), result.entries.map { it.dose })
        assertEquals(listOf("850 mg", "50 mg", "10 mg"), result.entries.map { it.presentation })
        assertEquals(listOf("Cada 12 horas", "Diaria", "Cada 8 horas"), result.entries.map { it.frequency })
        assertEquals(listOf("20:30", "", ""), result.entries.map { it.anchorTime })
        assertTrue(result.entries.first().instructions.contains("durante 10 días"))
        assertTrue(result.entries.none { it.instructions.contains("próxima consulta") })
        assertTrue(result.notes.contains("próxima consulta"))
        assertTrue(result.notes.contains("instrucciones impresas"))
        assertTrue(!result.notes.contains("Firma"))
    }

    @Test
    fun labelReportsTheMedicationAndNeverCreatesDirectionsFromTheBox() {
        val result = MedicationLabelParser.readDocument(
            listOf(OcrLine("LOSARTÁN 50 mg", 44), OcrLine("Caja con 30 tabletas"), OcrLine("Podría tomarse cada 8 horas")),
            MedicationDocument.LABEL,
        )
        assertEquals(listOf(MedicationLabelGuess(name = "Losartán", dose = "50 mg", presentation = "Caja con 30 tabletas")), result.entries)
        assertEquals("", result.notes)
        assertEquals("", result.recognizedText)
    }

    @Test
    fun prescriptionWithOnlyUnmatchedTextCanStillBeReviewed() {
        val result = MedicationLabelParser.readDocument(
            listOf(OcrLine("Receta médica"), OcrLine("Texto parcialmente legible"), OcrLine("Notas: volver a revisión")),
            MedicationDocument.PRESCRIPTION,
        )
        assertTrue(result.entries.isEmpty())
        assertTrue(result.recognizedText.contains("Texto parcialmente legible"))
        assertEquals("Notas: volver a revisión", result.notes)
    }

    @Test
    fun readsTheLargestTextAsNameAndTheBoxSizeAsPresentation() {
        val guess = MedicationLabelParser.parse(
            listOf(
                OcrLine("CAJA CON 30 TABLETAS", 18),
                OcrLine("LOSARTÁN", 62),
                OcrLine("50 mg", 30),
                OcrLine("VÍA ORAL", 14),
            ),
        )
        assertEquals("Losartán", guess.name)
        assertEquals("Caja con 30 tabletas", guess.presentation)
        assertEquals("50 mg", guess.dose)
        assertEquals("", guess.frequency)
    }

    @Test
    fun readsAPrescriptionWithExplicitMedicineAndInterval() {
        val guess = MedicationLabelParser.parse(
            listOf(
                OcrLine("Consultorio Médico", 40),
                OcrLine("Paciente: Ana López", 20),
                OcrLine("Medicamento: Metformina 850 mg", 20),
                OcrLine("Tomar 1 tableta cada 12 horas por 30 días", 20),
                OcrLine("Primera dosis 8:30 p.m.", 20),
            ),
        )
        assertEquals("Metformina", guess.name)
        assertEquals("850 mg", guess.dose)
        assertEquals("Cada 12 horas", guess.frequency)
        assertEquals("20:30", guess.anchorTime)
    }

    @Test
    fun readsUnitCountWhenTheBoxDoesNotSayCaja() {
        val guess = MedicationLabelParser.parse(
            listOf(
                OcrLine("IBUPROFENO", 48),
                OcrLine("SUSPENSIÓN 100 mg/5 ml", 18),
                OcrLine("FRASCO CON 120 ml", 16),
            ),
        )
        assertEquals("Ibuprofeno", guess.name)
        assertEquals("Frasco con 120 ml", guess.presentation)
        assertEquals("100 mg / 5 ml", guess.dose)
    }

    @Test
    fun ignoresPackageNoiseAndDoesNotInventData() {
        val guess = MedicationLabelParser.parse(
            listOf(
                OcrLine("MANTÉNGASE FUERA DEL ALCANCE DE LOS NIÑOS", 16),
                OcrLine("LOTE ABC123", 12),
                OcrLine("12345", 12),
            ),
        )
        assertTrue(guess.isEmpty)
        assertTrue(MedicationLabelParser.parse(emptyList()).isEmpty)
    }

    @Test
    fun readsCompactStrengthAndIntervalShortcuts() {
        val guess = MedicationLabelParser.parse(
            listOf(
                OcrLine("PARACETAMOL 500mg", 48),
                OcrLine("Tomar c/8 h", 18),
            ),
        )
        assertEquals("Paracetamol", guess.name)
        assertEquals("500 mg", guess.dose)
        assertEquals("Cada 8 horas", guess.frequency)
    }

    @Test
    fun readsIntervalWrittenInWordsAndTimeOfDay() {
        val guess = MedicationLabelParser.parse(
            listOf(
                OcrLine("IBUPROFENO", 50),
                OcrLine("Tomar cada ocho horas", 18),
                OcrLine("Primera dosis a las 10 de la mañana", 18),
            ),
        )
        assertEquals("Ibuprofeno", guess.name)
        assertEquals("Cada 8 horas", guess.frequency)
        assertEquals("10:00", guess.anchorTime)
    }

    @Test
    fun prefersTheMedicineOverTheLaboratoryName() {
        val guess = MedicationLabelParser.parse(
            listOf(
                OcrLine("GENOMMA LAB", 40),
                OcrLine("METFORMINA 850 mg", 20),
            ),
        )
        assertEquals("Metformina", guess.name)
        assertEquals("850 mg", guess.dose)
    }

    @Test
    fun correctsTypicalOcrConfusionsWithTheCatalog() {
        val guess = MedicationLabelParser.parse(
            listOf(
                OcrLine("L0SARTAN", 62),
                OcrLine("50 mg", 30),
            ),
        )
        assertEquals("Losartán", guess.name)
        assertEquals("50 mg", guess.dose)
    }

    @Test
    fun keepsConcentrationAsDoseAndContainerVolumeAsPresentation() {
        val guess = MedicationLabelParser.parse(
            listOf(
                OcrLine("AMOXICILINA", 48),
                OcrLine("Suspensión 250 mg/5 ml", 18),
                OcrLine("Frasco con 75 ml", 16),
            ),
        )
        assertEquals("Amoxicilina", guess.name)
        assertEquals("250 mg / 5 ml", guess.dose)
        assertEquals("Frasco con 75 ml", guess.presentation)
    }

    @Test
    fun trimsTheStrengthRepeatedInsideThePresentation() {
        val guess = MedicationLabelParser.parse(
            listOf(
                OcrLine("IBUPROFENO", 50),
                OcrLine("CAJA CON 20 TABLETAS DE 500 MG", 18),
            ),
        )
        assertEquals("Ibuprofeno", guess.name)
        assertEquals("Caja con 20 tabletas", guess.presentation)
        assertEquals("500 mg", guess.dose)
    }

    @Test
    fun readsTheSaltVariantAsPartOfTheName() {
        val guess = MedicationLabelParser.parse(listOf(OcrLine("LOSARTÁN POTÁSICO", 62)))
        assertEquals("Losartán potásico", guess.name)
    }

    @Test
    fun ignoresAdvertisingCopyAsName() {
        val guess = MedicationLabelParser.parse(
            listOf(
                OcrLine("ALIVIO RÁPIDO DEL DOLOR", 30),
                OcrLine("NUEVA FÓRMULA", 18),
            ),
        )
        assertTrue(guess.isEmpty)
    }

    @Test
    fun doesNotUseTheContainerWeightAsDose() {
        val guess = MedicationLabelParser.parse(listOf(OcrLine("TUBO CON 30 g", 20)))
        assertEquals("Tubo con 30 g", guess.presentation)
        assertEquals("", guess.dose)
    }

    @Test
    fun keepsUnitOnlyConcentration() {
        val guess = MedicationLabelParser.parse(
            listOf(
                OcrLine("INSULINA GLARGINA", 50),
                OcrLine("100 UI/ml", 20),
            ),
        )
        assertEquals("Insulina glargina", guess.name)
        assertEquals("100 UI / ml", guess.dose)
    }

    @Test
    fun fixesAMisspelledMedicineName() {
        val guess = MedicationLabelParser.parse(listOf(OcrLine("METFORNINA 850 mg", 50)))
        assertEquals("Metformina", guess.name)
        assertEquals("850 mg", guess.dose)
    }

    @Test
    fun readsPresentationWhenTheBoxPrintsEverythingOnOneLine() {
        val guess = MedicationLabelParser.parse(
            listOf(OcrLine("IBUPROFENO 400 MG CAJA CON 10 TABLETAS", 40)),
        )
        assertEquals("Ibuprofeno", guess.name)
        assertEquals("400 mg", guess.dose)
        assertEquals("Caja con 10 tabletas", guess.presentation)
    }

    @Test
    fun refinesValuesComingFromTheCloud() {
        val refined = MedicationLabelParser.refine(
            MedicationLabelGuess(
                name = "losartan potasico",
                presentation = "CAJA CON 30 TABLETAS",
                dose = "50MG",
                frequency = "cada 12 horas",
                anchorTime = "9:00 p.m.",
            ),
        )
        assertEquals("Losartán potásico", refined.name)
        assertEquals("Caja con 30 tabletas", refined.presentation)
        assertEquals("50 mg", refined.dose)
        assertEquals("Cada 12 horas", refined.frequency)
        assertEquals("21:00", refined.anchorTime)
    }

    @Test
    fun refinesConcentrationAndKeepsFreeTextDose() {
        assertEquals("100 mg / 5 ml", MedicationLabelParser.refine(MedicationLabelGuess(dose = "100 mg/5ml")).dose)
        assertEquals("1 tableta", MedicationLabelParser.refine(MedicationLabelGuess(dose = "1 tableta")).dose)
    }

    @Test
    fun leavesUnknownFrequencyEmpty() {
        assertEquals("", MedicationLabelParser.refine(MedicationLabelGuess(frequency = "cuando haga falta")).frequency)
    }
}
