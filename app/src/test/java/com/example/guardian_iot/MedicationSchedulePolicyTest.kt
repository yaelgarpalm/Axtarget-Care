package com.example.guardian_iot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class MedicationSchedulePolicyTest {
    private val zone = ZoneId.of("America/Mexico_City")

    @Test
    fun validatesRequiredFieldsAndDailyTimes() {
        assertNull(MedicationSchedulePolicy.validate("Medicamento", "1 tableta", "Diaria", listOf("08:00", "20:00"), "2026-10-24", null))
        assertNotNull(MedicationSchedulePolicy.validate("", "1 tableta", "Diaria", listOf("08:00"), "2026-10-24", null))
        assertNotNull(MedicationSchedulePolicy.validate("Medicamento", "", "Diaria", listOf("08:00"), "2026-10-24", null))
        assertNotNull(MedicationSchedulePolicy.validate("Medicamento", "1", "Diaria", listOf("08:00", "08:00"), "2026-10-24", null))
        assertNotNull(MedicationSchedulePolicy.validate("Medicamento", "1", "Diaria", listOf("25:65"), "2026-10-24", null))
        assertNotNull(MedicationSchedulePolicy.validate("Medicamento", "1", "Diaria", listOf("08:00"), "2026-10-25", "2026-10-24"))
    }

    @Test
    fun schedulesDailyAndEveryOtherDayFromStartDate() {
        val start = LocalDate.parse("2026-10-24")
        val firstMorning = start.atTime(7, 0).atZone(zone).toInstant().toEpochMilli()
        val daily = medication("Diaria")
        val dailyAtEight = MedicationSchedulePolicy.nextOccurrence(daily, "08:00", firstMorning, zone)
        assertEquals(start.atTime(8, 0).atZone(zone).toInstant().toEpochMilli(), dailyAtEight)

        val afterFirstEvening = start.atTime(21, 0).atZone(zone).toInstant().toEpochMilli()
        val everyOtherDay = MedicationSchedulePolicy.nextOccurrence(medication("Cada 2 días"), "08:00", afterFirstEvening, zone)
        assertEquals(start.plusDays(2).atTime(8, 0).atZone(zone).toInstant().toEpochMilli(), everyOtherDay)
    }

    @Test
    fun respectsEndDateAndDoesNotGenerateInvalidSchedules() {
        val afterEnd = LocalDate.parse("2026-10-25").atTime(21, 0).atZone(zone).toInstant().toEpochMilli()
        assertNull(MedicationSchedulePolicy.nextOccurrence(medication("Diaria", end = "2026-10-25"), "08:00", afterEnd, zone))
        assertNull(MedicationSchedulePolicy.nextOccurrence(medication("Diaria"), "25:00", System.currentTimeMillis(), zone))
    }

    @Test
    fun idsAreStablePerMedicationAndScheduledInstance() {
        val time = 1_798_000_000_000L
        val first = MedicationSchedulePolicy.scheduleInstanceId("med-1", time)
        assertEquals(first, MedicationSchedulePolicy.scheduleInstanceId("med-1", time))
        assertFalse(first == MedicationSchedulePolicy.scheduleInstanceId("med-2", time))
        assertFalse(first == MedicationSchedulePolicy.scheduleInstanceId("med-1", time + 60_000L))
        assertTrue(runCatching { java.util.UUID.fromString(first) }.isSuccess)
    }

    @Test
    fun acceptsOnlyIntervalsThatRepeatWithinADay() {
        assertEquals(8, MedicationSchedulePolicy.intervalHours("Cada 8 horas"))
        assertEquals(24, MedicationSchedulePolicy.intervalHours("cada 24 horas"))
        assertEquals(4, MedicationSchedulePolicy.intervalHours("Cada 4 horas"))
        assertNull(MedicationSchedulePolicy.intervalHours("Cada 5 horas"))
        assertNull(MedicationSchedulePolicy.intervalHours("Cada 48 horas"))
        assertNull(MedicationSchedulePolicy.intervalHours("Diaria"))
        assertEquals("Cada 12 horas", MedicationSchedulePolicy.intervalFrequency(12))
        assertNull(MedicationSchedulePolicy.intervalFrequency(5))
    }

    @Test
    fun generatesIntervalTimesFromTheFirstDose() {
        assertEquals(listOf("02:00", "10:00", "18:00"), MedicationSchedulePolicy.intervalTimes("10:00", 8))
        assertEquals(listOf("00:00", "12:00"), MedicationSchedulePolicy.intervalTimes("00:00", 12))
        assertEquals(listOf("07:30"), MedicationSchedulePolicy.intervalTimes("07:30", 24))
        assertNull(MedicationSchedulePolicy.intervalTimes("10:00", 5))
        assertNull(MedicationSchedulePolicy.intervalTimes("25:00", 8))
        assertEquals("02:00", MedicationSchedulePolicy.intervalAnchor(listOf("18:00", "10:00", "02:00")))
    }

    @Test
    fun validatesIntervalSchedulesAgainstTheGeneratedSlots() {
        val aligned = listOf("02:00", "10:00", "18:00")
        assertNull(MedicationSchedulePolicy.validate("Medicamento", "1 tableta", "Cada 8 horas", aligned, "2026-10-24", null))
        assertNotNull(MedicationSchedulePolicy.validate("Medicamento", "1 tableta", "Cada 8 horas", listOf("08:00", "20:00"), "2026-10-24", null))
        assertNull(MedicationSchedulePolicy.validate("Medicamento", "1 tableta", "Cada 24 horas", listOf("07:30"), "2026-10-24", null))
    }

    @Test
    fun schedulesIntervalFrequencyEveryDay() {
        val start = LocalDate.parse("2026-10-24")
        val intervalMedication = medication("Cada 8 horas", times = listOf("02:00", "10:00", "18:00"))
        val afterMorning = start.atTime(11, 0).atZone(zone).toInstant().toEpochMilli()
        val next = MedicationSchedulePolicy.nextOccurrence(intervalMedication, "18:00", afterMorning, zone)
        assertEquals(start.atTime(18, 0).atZone(zone).toInstant().toEpochMilli(), next)
        val afterNight = start.atTime(19, 0).atZone(zone).toInstant().toEpochMilli()
        val nextDay = MedicationSchedulePolicy.nextOccurrence(intervalMedication, "02:00", afterNight, zone)
        assertEquals(start.plusDays(1).atTime(2, 0).atZone(zone).toInstant().toEpochMilli(), nextDay)
    }

    private fun medication(frequency: String, end: String? = null, times: List<String> = listOf("08:00")) = Medication(
        id = "med-1", ownerUserId = "owner-1", name = "Prueba", prescribedDose = "Como indicada",
        presentation = "", frequency = frequency, scheduleTimes = times,
        startDate = "2026-10-24", endDate = end, instructions = "", isActive = true,
    )
}
