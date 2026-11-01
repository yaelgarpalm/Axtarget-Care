package com.example.guardian_iot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MedicationLabelCloudTest {
    @Test
    fun readsTheModelJsonEvenWithMarkdownFences() {
        val raw = """{"choices":[{"message":{"content":"```json\n{\"name\": \"LOSARTAN POTASICO\", \"dose\": \"50MG\", \"presentation\": \"CAJA CON 30 TABLETAS\", \"frequency\": \"cada 12 horas\", \"anchor_time\": \"9:00 p.m.\"}\n```"}}]}"""
        val guess = NvidiaMedicationLabelReader.parseResponse(raw)!!
        assertEquals("Losartán potásico", guess.name)
        assertEquals("50 mg", guess.dose)
        assertEquals("Caja con 30 tabletas", guess.presentation)
        assertEquals("Cada 12 horas", guess.frequency)
        assertEquals("21:00", guess.anchorTime)
    }

    @Test
    fun ignoresAnswersWithoutJson() {
        assertNull(
            NvidiaMedicationLabelReader.parseResponse(
                """{"choices":[{"message":{"content":"No se ve ningún medicamento"}}]}""",
            ),
        )
        assertNull(NvidiaMedicationLabelReader.parseResponse(""))
    }

    @Test
    fun keepsAnAnswerThatOnlyHasTheName() {
        val raw = """{"choices":[{"message":{"content":"{\"name\": \"Tempra\", \"dose\": \"\", \"presentation\": \"\", \"frequency\": \"\", \"anchor_time\": \"\"}"}}]}"""
        val guess = NvidiaMedicationLabelReader.parseResponse(raw)!!
        assertEquals("Tempra", guess.name)
        assertEquals("", guess.presentation)
    }
}
