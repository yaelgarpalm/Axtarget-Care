package com.example.guardian_iot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MedicationLabelCloudTest {
    @Test fun unoQTokenRequiresAtLeastThirtyTwoNonWhitespaceBytes() {
        assertEquals(false, UnoQLocalAuth.isValidToken("short"))
        assertEquals(false, UnoQLocalAuth.isValidToken("x".repeat(31)))
        assertEquals(false, UnoQLocalAuth.isValidToken("x".repeat(31) + " "))
        assertEquals(true, UnoQLocalAuth.isValidToken("x".repeat(32)))
    }

    @Test fun acceptsEveryPrescriptionItemAndKeepsNamesDosesAndNotesAsWritten() {
        val result = NvidiaMedicationLabelReader.parseResponse(
            """{"scan":{"medications":[{"name":"LOSARTAN POTASICO","dose":"50MG","frequency":"cada 12 horas","anchor_time":"9:00 p.m.","instructions":"Leer también la nota general."},{"name":"AMOXICILINA + ÁCIDO CLAVULÁNICO","dose":"500 mg + 125 mg"}],"notes":"Nota de la receta"}}""",
            MedicationDocument.PRESCRIPTION,
        )!!
        assertEquals(2, result.entries.size)
        assertEquals("LOSARTAN POTASICO", result.entries[0].name)
        assertEquals("50MG", result.entries[0].dose)
        assertEquals("Cada 12 horas", result.entries[0].frequency)
        assertEquals("21:00", result.entries[0].anchorTime)
        assertEquals("Leer también la nota general.", result.entries[0].instructions)
        assertEquals("500 mg + 125 mg", result.entries[1].dose)
        assertEquals("", result.entries[1].frequency)
        assertEquals("Nota de la receta", result.notes)
    }

    @Test fun rejectsNonTextOrOversizedProxyFields() {
        assertNull(NvidiaMedicationLabelReader.parseResponse(
            """{"scan":{"medications":[{"name":{"text":"unexpected"}}]}}""", MedicationDocument.PRESCRIPTION,
        ))
        assertNull(NvidiaMedicationLabelReader.parseResponse(
            """{"scan":{"medications":[{"name":"${"x".repeat(257)}"}]}}""", MedicationDocument.PRESCRIPTION,
        ))
    }

    @Test
    fun rejectsALegacySingleItemResponseSoThePhoneUsesTheLocalPrescriptionReader() {
        assertNull(NvidiaMedicationLabelReader.parseResponse(
            """{"result":{"name":"Tempra"}}""", MedicationDocument.PRESCRIPTION,
        ))
    }

    @Test
    fun ignoresAnswersWithoutJson() {
        assertNull(
            NvidiaMedicationLabelReader.parseResponse("No se ve ningún medicamento", MedicationDocument.PRESCRIPTION),
        )
        assertNull(NvidiaMedicationLabelReader.parseResponse("", MedicationDocument.PRESCRIPTION))
    }

    @Test
    fun keepsAnAnswerThatOnlyHasTheName() {
        val raw = """{"scan":{"medications":[{"name":"Tempra","frequency":"Diaria","anchor_time":"08:00","instructions":"Publicidad"}],"notes":"Publicidad"}}"""
        val guess = NvidiaMedicationLabelReader.parseResponse(raw, MedicationDocument.LABEL)!!.entries.single()
        assertEquals("Tempra", guess.name)
        assertEquals("", guess.presentation)
        assertEquals("", guess.frequency)
        assertEquals("", guess.anchorTime)
        assertEquals("", guess.instructions)
    }
}
