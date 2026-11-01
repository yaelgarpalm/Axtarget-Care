package com.example.guardian_iot

import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

data class Medication(
    val id: String,
    val ownerUserId: String,
    val name: String,
    val prescribedDose: String,
    val presentation: String,
    val frequency: String,
    val scheduleTimes: List<String>,
    val startDate: String,
    val endDate: String?,
    val instructions: String,
    val isActive: Boolean,
    val shareWithCaregiver: Boolean = false,
    val isDeleted: Boolean = false,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis(),
    val syncState: String = "pending",
)

data class MedicationIntake(
    val id: String,
    val medicationId: String,
    val ownerUserId: String,
    val scheduleInstanceId: String,
    val scheduledAtMillis: Long,
    val actionAtMillis: Long?,
    val status: String,
    val snoozedUntilMillis: Long?,
    val snoozeCount: Int,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val syncState: String = "pending",
    /** Quién registró la acción (taken/skipped/snoozed). Null en tomas aún sin confirmar. */
    val actorUserId: String? = null,
)

object MedicationSchedulePolicy {
    private val timePattern = Regex("^(?:[01]\\d|2[0-3]):[0-5]\\d$")
    private val intervalHoursPattern = Regex("^cada (\\d{1,2}) horas?$")

    /** Frecuencias que el formulario ofrece; las de intervalo se repiten dentro del mismo día. */
    val frequencyOptions = listOf(
        "Diaria", "Cada 2 días", "Cada 3 días", "Semanal",
        "Cada 4 horas", "Cada 6 horas", "Cada 8 horas", "Cada 12 horas", "Cada 24 horas",
    )

    fun validate(
        name: String,
        dose: String,
        frequency: String,
        times: List<String>,
        startDate: String,
        endDate: String?,
    ): String? {
        if (name.trim().isEmpty()) return "Escribe el nombre del medicamento."
        if (name.trim().length > 120) return "El nombre no puede superar 120 caracteres."
        if (dose.trim().isEmpty()) return "Escribe la dosis tal como fue indicada."
        if (dose.trim().length > 120) return "La dosis no puede superar 120 caracteres."
        val interval = intervalHours(frequency)
        if (interval == null && frequencyIntervalDays(frequency) == null) return "Selecciona una frecuencia disponible."
        if (times.isEmpty() || times.size > 12 || times.any { !timePattern.matches(it) })
            return "Escribe uno o más horarios válidos en formato HH:mm, separados por coma."
        if (times.distinct().size != times.size) return "Quita los horarios repetidos."
        if (interval != null) {
            val expected = intervalTimes(times.minOrNull().orEmpty(), interval).orEmpty().sorted()
            if (times.sorted() != expected) {
                return "Con «$frequency» los horarios deben repetirse cada $interval horas. Vuelve a elegir la primera toma."
            }
        }
        val start = runCatching { LocalDate.parse(startDate) }.getOrNull()
            ?: return "La fecha de inicio debe usar el formato AAAA-MM-DD."
        if (endDate != null) {
            val end = runCatching { LocalDate.parse(endDate) }.getOrNull()
                ?: return "La fecha final debe usar el formato AAAA-MM-DD."
            if (end.isBefore(start)) return "La fecha final no puede ser anterior a la fecha de inicio."
        }
        return null
    }

    /**
     * Horas entre tomas cuando la receta dice "cada N horas". Solo se aceptan divisores de 24
     * para que los horarios se repitan igual todos los días.
     */
    fun intervalHours(frequency: String): Int? {
        val hours = intervalHoursPattern.matchEntire(frequency.trim().lowercase())?.groupValues?.get(1)?.toIntOrNull()
            ?: return null
        return hours.takeIf { it in 2..24 && 24 % it == 0 }
    }

    fun intervalFrequency(hours: Int): String? =
        "Cada $hours horas".takeIf { intervalHours(it) != null }

    /** Genera los horarios del día a partir de la primera toma (por ejemplo 10:00 cada 8 h). */
    fun intervalTimes(anchorTime: String, hours: Int): List<String>? {
        if (!timePattern.matches(anchorTime) || hours !in 2..24 || 24 % hours != 0) return null
        val anchor = LocalTime.parse(anchorTime)
        return (0 until 24 / hours).map { index ->
            anchor.plusHours((index * hours).toLong()).toString()
        }.sorted()
    }

    /** Primera toma de referencia al editar un medicamento por intervalo. */
    fun intervalAnchor(times: List<String>): String = times.minOrNull().orEmpty()

    fun frequencyIntervalDays(frequency: String): Int? = when (frequency.trim().lowercase()) {
        "diaria", "cada día", "cada dia", "daily" -> 1
        "cada 2 días", "cada 2 dias" -> 2
        "cada 3 días", "cada 3 dias" -> 3
        "semanal", "cada semana" -> 7
        else -> Regex("^cada ([2-9]|[1-9][0-9]{1,2}) días?$").matchEntire(frequency.trim().lowercase())?.groupValues?.get(1)?.toIntOrNull()
            ?.takeIf { it in 2..365 }
    }

    fun nextOccurrence(medication: Medication, timeText: String, afterMillis: Long, zone: ZoneId): Long? {
        if (!medication.isActive || medication.isDeleted || !timePattern.matches(timeText)) return null
        val start = runCatching { LocalDate.parse(medication.startDate) }.getOrNull() ?: return null
        val end = medication.endDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: if (medication.endDate == null) null else return null
        val intervalDays = frequencyIntervalDays(medication.frequency) ?: 1
        var date = LocalDate.ofInstant(java.time.Instant.ofEpochMilli(afterMillis), zone)
        if (date.isBefore(start)) date = start
        repeat(370) {
            if (end != null && date.isAfter(end)) return null
            val candidate = date.atTime(LocalTime.parse(timeText)).atZone(zone).toInstant().toEpochMilli()
            val daysFromStart = java.time.temporal.ChronoUnit.DAYS.between(start, date)
            if (daysFromStart >= 0 && daysFromStart % intervalDays == 0L && candidate > afterMillis) return candidate
            date = date.plusDays(1)
        }
        return null
    }

    fun scheduleInstanceId(medicationId: String, scheduledAtMillis: Long): String =
        UUID.nameUUIDFromBytes("$medicationId|$scheduledAtMillis".toByteArray(StandardCharsets.UTF_8)).toString()

    fun intakeId(medicationId: String, scheduledAtMillis: Long): String = scheduleInstanceId(medicationId, scheduledAtMillis)
}
