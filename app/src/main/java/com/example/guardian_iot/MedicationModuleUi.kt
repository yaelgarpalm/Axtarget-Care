package com.example.guardian_iot

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.Dialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.InputType
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.Switch
import android.widget.TextView
import androidx.core.app.ActivityCompat
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Small view/controller module kept outside MainActivity to avoid growing the main screen logic. */
class MedicationModuleUi(
    private val activity: Activity,
    private val repository: MedicationRepository,
    private val ownerId: String,
    private val readOnly: Boolean,
    private val dark: Boolean,
    private val sessionActive: Boolean = true,
    private val currentUserId: String = "",
    private val scanner: MedicationLabelScanner? = null,
    private val onBack: () -> Unit,
) {
    private val db = repository.database
    private val tz = TzindaDesign(activity, dark, AccountStorage.capture(activity).getSharedPreferences("tzinda_native", Context.MODE_PRIVATE).getBoolean("simple_accessibility", false))
    private val ink get() = tz.ink
    private val muted get() = tz.muted
    private val accent get() = tz.accent
    private val paper get() = tz.paper
    private val cardColor get() = tz.cardColor
    private val mint get() = tz.mint
    private val softSurface get() = tz.softSurface
    private val outline get() = tz.outline
    private var syncState: TextView? = null
    private var parent: LinearLayout? = null
    private var initialSyncStarted = false
    private var adherenceDays = 7

    fun render(target: LinearLayout) {
        parent = target
        if (!readOnly) {
            MedicationReminderScheduler.synchronizeAll(activity)
            db.markOverdue(ownerId)
        }
        heading(
            target,
            if (readOnly) "CUIDADO COMPARTIDO" else "CUIDADO Y RUTINA",
            if (readOnly) "Medicamentos compartidos" else "Mis medicamentos",
            if (readOnly) {
                "Consulta los horarios y tomas que la persona protegida decidió compartir. Puedes registrar las tomas compartidas; los medicamentos y horarios los edita su propietario."
            } else {
                "Guarda los horarios indicados y lleva un registro de cada toma."
            },
        )
        if (readOnly && ownerId.isNotBlank() && !sessionActive) {
            tz.banner(
                target,
                "Sesión vencida",
                "Inicia sesión desde Ajustes para consultar o actualizar los medicamentos compartidos.",
                TzindaDesign.Tone.WARNING,
                iconRes = R.drawable.ic_lock,
            )
        }
        if (readOnly && ownerId.isBlank()) {
            if (sessionActive) {
                tz.emptyState(
                    target,
                    "Sin persona vinculada",
                    "Vincula primero una cuenta de persona protegida para consultar sus medicamentos compartidos.",
                    iconRes = R.drawable.ic_user,
                )
            } else {
                tz.emptyState(
                    target,
                    "Inicia sesión otra vez",
                    "Tu vínculo con la persona protegida sigue activo, pero la sesión terminó. Vuelve a iniciar sesión para consultar los medicamentos compartidos.",
                    iconRes = R.drawable.ic_lock,
                )
            }
            return
        }
        if (readOnly) {
            tz.banner(
                target,
                "Tomas compartidas",
                "Solo aparecen los medicamentos compartidos. Puedes confirmar sus tomas; la persona protegida conserva el control de horarios y medicamentos.",
                TzindaDesign.Tone.INFO,
                iconRes = R.drawable.ic_lock,
            )
        }
        if (!readOnly && !ownerId.startsWith("local:")) {
            // Sharing used to live at the very bottom of this screen, so the caregiver's list stayed
            // empty without the wearer ever noticing there was anything left to share.
            val unshared = db.medications(ownerId).count { !it.shareWithCaregiver && !it.isDeleted && it.isActive }
            if (unshared > 0) {
                val shareCard = card(target)
                shareCard.addView(label("Tu cuidador/a no puede verlos todavía", ink, 18, true))
                shareCard.addView(label(
                    "Tienes ${TzindaText.count(unshared, "medicamento")} sin compartir. El cuidador o cuidadora solo ve los que marques como compartidos.",
                    muted, 14,
                ))
                shareCard.addView(button("Compartir con mi cuidador/a", false, R.drawable.ic_user) { confirmShareAll() })
            }
        }

        val next = nextIntakes()
        val summary = card(target)
        val summaryHeader = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        summaryHeader.addView(
            label(if (readOnly) "PRÓXIMAS TOMAS COMPARTIDAS" else "PRÓXIMAS TOMAS", accent, 12, true),
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        summaryHeader.addView(tz.statusPill(TzindaText.count(next.size, "programada", "programadas"), TzindaDesign.Tone.BRAND, withDot = false))
        summary.addView(summaryHeader)
        if (next.isEmpty()) {
            summary.addView(label("No hay tomas programadas próximas.", ink, 17, true).apply { setPadding(0, dp(8), 0, 0) })
        }
        next.take(3).forEach { (med, at) ->
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(10), 0, 0)
            }
            row.addView(label(formatTime(at), ink, 20, true))
            row.addView(label(med.name, ink, 16, true))
            row.addView(label(doseLine(med), muted, 14))
            row.contentDescription = "Próxima toma a las ${spokenTime(at)}: ${med.name}, ${doseLine(med)}"
            summary.addView(row)
        }

        if (!readOnly) {
            target.addView(
                button("Agregar medicamento", true, R.drawable.ic_plus) { showMedicationForm(null) },
            )
            gap(target, 8)
        }

        val adherence = adherenceSummary(adherenceDays)
        val adherenceCard = card(target)
        adherenceCard.addView(label("Seguimiento de adherencia", ink, 19, true))
        adherenceCard.addView(label("Últimos $adherenceDays días · solo horarios ya vencidos", muted, 14))
        tz.segmented(
            adherenceCard,
            listOf("7 días", "14 días", "30 días"),
            listOf(7, 14, 30).indexOf(adherenceDays).coerceAtLeast(0),
        ) { index ->
            adherenceDays = listOf(7, 14, 30)[index]
            rerender()
        }
        val percentage = adherence.percentage
        if (percentage != null) {
            val percentageRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, 0)
            }
            percentageRow.addView(label("$percentage%", accent, 30, true), LinearLayout.LayoutParams(0, -2, 1f))
            percentageRow.addView(
                tz.statusPill(
                    adherenceToneLabel(adherence),
                    adherenceTone(adherence),
                    withDot = true,
                ),
            )
            adherenceCard.addView(percentageRow)
            adherenceCard.addView(tz.progress(percentage, height = 10), LinearLayout.LayoutParams(-1, dp(10)).apply { topMargin = dp(8) })
            adherenceCard.addView(
                label(
                    "${TzindaText.count(adherence.taken, "tomada", "tomadas")} · ${TzindaText.count(adherence.pending, "pendiente", "pendientes")} · ${TzindaText.count(adherence.omitted, "omitida", "omitidas")} de ${TzindaText.count(adherence.scheduledDue, "programada", "programadas")}",
                    muted, 14,
                ),
            )
            adherenceCard.contentDescription = "Adherencia de los últimos $adherenceDays días: $percentage por ciento. " +
                "${adherence.taken} tomas registradas como tomadas, ${adherence.pending} pendientes y ${adherence.omitted} omitidas."
        } else {
            adherenceCard.addView(label("Aún no hay tomas vencidas en este periodo.", ink, 16, true).apply { setPadding(0, dp(10), 0, 0) })
            adherenceCard.addView(label("La adherencia se calcula solo con horarios que ya pasaron; las tomas futuras no la reducen.", muted, 14))
        }

        val today = card(target)
        today.addView(label("Tomas de hoy", ink, 20, true))
        val todayIntakes = todayIntakes()
        if (todayIntakes.isEmpty()) {
            today.addView(label("Todavía no hay horarios registrados para hoy.", muted, 15).apply { setPadding(0, dp(4), 0, dp(8)) })
        }
        todayIntakes.forEach { intake -> intakeCard(today, intake) }

        target.addView(label("Lista de medicamentos", ink, 21, true).apply { setPadding(dp(4), dp(12), 0, dp(4)) })
        val meds = visibleMedications(includeDeleted = false)
        if (meds.isEmpty()) {
            tz.emptyState(
                target,
                if (readOnly) "Sin medicamentos compartidos" else "Aún no hay medicamentos",
                if (readOnly) {
                    "La persona protegida no ha compartido medicamentos contigo. Solo verás los que autorice."
                } else {
                    "Agrega el primer medicamento para programar recordatorios y registrar cada toma."
                },
                if (readOnly) null else "Agregar medicamento",
                if (readOnly) null else ({ showMedicationForm(null) }),
                R.drawable.ic_medication,
            )
        }
        meds.forEach { medicationCard(target, it) }
        target.addView(button("Ver historial de tomas", false, R.drawable.ic_calendar) { showHistory() })

        val syncCard = card(target)
        syncCard.addView(label(if (readOnly) "Datos compartidos" else "Guardado y sincronización", ink, 18, true))
        syncState = label(
            when {
                readOnly -> "Puedes confirmar las tomas compartidas; la persona protegida controla horarios y medicamentos."
                ownerId.startsWith("local:") -> "Disponible sin Internet. Se guarda en este teléfono."
                else -> "Los cambios se guardan primero en este teléfono y se sincronizan con Supabase."
            }, muted, 14,
        ).also(syncCard::addView)
        if (!readOnly && Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            syncCard.addView(button("Permitir recordatorios", false, R.drawable.ic_alert) {
                ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
            })
        }
        if (!readOnly && Build.VERSION.SDK_INT >= 31 && !MedicationReminderScheduler.canUseExactAlarms(activity)) {
            syncCard.addView(label("Android puede retrasar avisos. Si quieres horarios más precisos, permite alarmas exactas; si no, la app usará avisos aproximados.", muted, 14))
            syncCard.addView(button("Configurar horarios precisos", false) { requestExactAlarmPermission() })
        }
        if (readOnly && ownerId.isNotBlank()) {
            // Without this action the caregiver could only wait for the automatic sync, with no way
            // to tell an empty list apart from a failed download.
            syncCard.addView(button("Actualizar medicamentos compartidos", false, R.drawable.ic_sync) { syncNow() })
        }
        if (!readOnly) {
            syncCard.addView(button("Sincronizar ahora", false, R.drawable.ic_sync) { syncNow() })
            if (!ownerId.startsWith("local:")) {
                syncCard.addView(button("Compartir todos con mi cuidador/a", false, R.drawable.ic_user) { confirmShareAll() })
            }
        }

        target.addView(label("TzindaApp registra la dosis que tú escribes. No recomienda ni cambia tratamientos.", muted, 13).apply { setPadding(dp(4), dp(16), dp(4), 0) })
        if (!initialSyncStarted && ownerId.isNotBlank() && !ownerId.startsWith("local:")) {
            initialSyncStarted = true
            syncNow(silent = true)
        }
    }

    private fun medicationCard(target: LinearLayout, medication: Medication) {
        val item = card(target)
        val tone = when {
            medication.isDeleted -> TzindaDesign.Tone.NEUTRAL
            medication.isActive -> TzindaDesign.Tone.SUCCESS
            else -> TzindaDesign.Tone.WARNING
        }
        val tag = when {
            medication.isDeleted -> "Archivado"
            medication.isActive -> "Activo"
            else -> "Pausado"
        }
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titles = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(label(medication.name, ink, 20, true))
        titles.addView(label(doseLine(medication), accent, 15, true))
        header.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(tz.statusPill(tag, tone))
        item.addView(header)
        item.addView(label(medication.frequency, muted, 14).apply { setPadding(0, dp(4), 0, 0) })

        item.addView(label("Horario", muted, 12, true).apply { setPadding(0, dp(10), 0, 0) })
        val scheduleValue = medication.scheduleTimes.joinToString(" · ").ifBlank { "Sin horario definido" }
        item.addView(label(scheduleValue, ink, 17, true))
        MedicationSchedulePolicy.intervalHours(medication.frequency)?.let { hours ->
            item.addView(
                label(
                    "Se repite cada $hours horas desde las ${MedicationSchedulePolicy.intervalAnchor(medication.scheduleTimes)}",
                    muted, 13,
                ),
            )
        }
        item.addView(
            label("Desde ${formatLocalDate(medication.startDate)}${medication.endDate?.let { " hasta ${formatLocalDate(it)}" }.orEmpty()}", muted, 14),
        )
        if (medication.instructions.isNotBlank()) {
            item.addView(label("Indicaciones", muted, 12, true).apply { setPadding(0, dp(8), 0, 0) })
            item.addView(label(medication.instructions, muted, 14))
        }
        val shareRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        shareRow.addView(
            tz.statusPill(
                if (medication.shareWithCaregiver) "Compartido con cuidador/a" else "Privado en esta cuenta",
                if (medication.shareWithCaregiver) TzindaDesign.Tone.INFO else TzindaDesign.Tone.NEUTRAL,
                withDot = false,
                iconRes = if (medication.shareWithCaregiver) R.drawable.ic_user else R.drawable.ic_lock,
            ),
        )
        item.addView(shareRow)
        item.contentDescription = buildString {
            append("${medication.name}. ${doseLine(medication)}. $tag. ${medication.frequency}. ")
            append("Horario: ${spokenSchedule(medication.scheduleTimes)}. ")
            append(if (medication.shareWithCaregiver) "Compartido con cuidador o cuidadora." else "Privado en esta cuenta.")
        }
        item.addView(button("Consultar detalles", false, R.drawable.ic_info) { showDetails(medication) })
        if (!readOnly && !medication.isDeleted) {
            item.addView(button("Editar", false, R.drawable.ic_edit) { showMedicationForm(medication) })
            item.addView(
                button("Eliminar medicamento", false, R.drawable.ic_trash) { confirmDelete(medication) }.apply {
                    setTextColor(tz.critical)
                },
            )
        }
    }

    private fun intakeCard(target: LinearLayout, intake: MedicationIntake) {
        val medication = db.getMedication(ownerId, intake.medicationId) ?: return
        val tile = card(target)
        val status = displayStatus(intake)
        val taken = intake.status == "taken"
        // El cuidador vinculado también puede confirmar tomas desde su teléfono, siempre que su
        // sesión esté activa (necesita auth.uid() para firmar la acción con actor_user_id).
        val actionable = intake.status in setOf("pending", "snoozed", "overdue") && repository.canActOnIntake(intake.id)
        val tone = when {
            taken -> TzindaDesign.Tone.SUCCESS
            intake.status == "skipped" || intake.status == "overdue" -> TzindaDesign.Tone.DANGER
            intake.status == "snoozed" || intake.status == "pending" -> TzindaDesign.Tone.WARNING
            else -> TzindaDesign.Tone.NEUTRAL
        }
        val shortStatus = when (intake.status) {
            "taken" -> "Tomada"
            "skipped" -> "Omitida"
            "overdue" -> "Vencida"
            "snoozed" -> "Pospuesta"
            "pending" -> "Pendiente"
            else -> "Sin estado"
        }
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        // Circular tactile check, mirroring the iOS Health dose control.
        val checkBadge = tz.coloredBadge(
            when {
                taken -> R.drawable.ic_check_circle
                intake.status == "skipped" -> R.drawable.ic_close
                else -> R.drawable.ic_circle
            },
            when {
                taken -> mint
                intake.status == "skipped" -> tz.criticalSurface
                intake.status == "snoozed" || intake.status == "overdue" -> tz.warningSurface
                else -> softSurface
            },
            when {
                taken -> tz.positive
                intake.status == "skipped" -> tz.critical
                intake.status == "snoozed" || intake.status == "overdue" -> tz.warning
                else -> accent
            },
            44,
        )
        val check = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = tz.ripple(0x1F0E48D6, tz.rounded(Color.TRANSPARENT, 999), 999)
            isClickable = actionable
            isFocusable = actionable
            if (actionable) {
                setOnClickListener { actOnIntake(intake.id, "taken") }
                contentDescription = "Registrar ${medication.name} como tomada"
            }
        }
        check.addView(checkBadge, LinearLayout.LayoutParams(dp(44), dp(44)))
        header.addView(check, LinearLayout.LayoutParams(dp(52), dp(52)))
        val copy = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        copy.addView(label(medication.name, ink, 17, true))
        copy.addView(label(doseLine(medication), muted, 13))
        copy.addView(label(formatTime(intake.scheduledAtMillis), accent, 13, true))
        header.addView(copy, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
        header.addView(tz.statusPill(shortStatus, tone))
        tile.addView(header)
        tile.contentDescription = "${spokenTime(intake.scheduledAtMillis)}: ${medication.name}, ${doseLine(medication)}. Estado: $status."
        if (taken) {
            tile.alpha = 0.78f
            val subject = actorSubject(intake)
            val at = intake.actionAtMillis
            val line = buildString {
                if (at != null) append("Registrada a las ${formatTime(at)}") else append("Registrada")
                if (subject != null) append(" · por $subject")
            }
            tile.addView(label(line, muted, 12).apply { setPadding(0, dp(4), 0, 0) })
        } else if (intake.status == "skipped") {
            tile.alpha = 0.72f
            actorSubject(intake)?.let { who ->
                tile.addView(label("Omitida por $who", muted, 12).apply { setPadding(0, dp(4), 0, 0) })
            }
        }
        if (actionable) {
            tile.addView(button("Registrar toma", true, R.drawable.ic_check_circle) { actOnIntake(intake.id, "taken") })
            val secondary = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            secondary.addView(button("Omitir", false) { actOnIntake(intake.id, "skipped") }, LinearLayout.LayoutParams(0, -2, 1f))
            if (intake.snoozeCount == 0) {
                secondary.addView(button("Posponer 10 min", false) { actOnIntake(intake.id, "snoozed") },
                    LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
            }
            tile.addView(secondary, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        } else if (readOnly && !sessionActive && intake.status in setOf("pending", "snoozed", "overdue")) {
            tile.addView(label("Inicia sesión para poder confirmar esta toma.", muted, 12).apply { setPadding(0, dp(6), 0, 0) })
        }
    }

    /** Sujeto legible de quién confirmó la toma, para la trazabilidad visible en la tarjeta. */
    private fun actorSubject(intake: MedicationIntake): String? {
        val actor = intake.actorUserId?.takeIf(String::isNotBlank) ?: return null
        return when {
            currentUserId.isNotBlank() && actor == currentUserId -> "ti"
            actor == ownerId -> if (readOnly) "la persona protegida" else "ti"
            else -> "otra cuenta vinculada"
        }
    }

    private fun showMedicationForm(existing: Medication?) {
        if (readOnly) return
        var selectedStart = existing?.startDate ?: LocalDate.now().toString()
        var selectedEnd = existing?.endDate
        val selectedTimes = (existing?.scheduleTimes ?: emptyList()).toMutableList()
        val dialog = Dialog(activity)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(12))
            background = GradientDrawable().apply { setColor(cardColor); cornerRadius = dp(24).toFloat(); setStroke(dp(1), outline) }
        }
        val header = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val headerCopy = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        headerCopy.addView(label("MEDICAMENTO", accent, 12, true))
        headerCopy.addView(label(if (existing == null) "Nuevo medicamento" else "Editar medicamento", ink, 22, true))
        header.addView(headerCopy, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(
            tz.iconOnlyButton(R.drawable.ic_close, "Cerrar formulario", size = 48) { dialog.dismiss() },
            LinearLayout.LayoutParams(dp(48), dp(48)),
        )
        root.addView(header)

        val scroll = ScrollView(activity).apply { isFillViewport = false; clipToPadding = false }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(8))
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        content.addView(label("MEDICAMENTO", accent, 12, true))
        val scanSlot = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        content.addView(scanSlot)
        val name = input(content, "Nombre del medicamento", existing?.name.orEmpty(), hintText = "Ej. Losartán")
        val dose = input(content, "Dosis indicada", existing?.prescribedDose.orEmpty(), hintText = "Ej. 1 tableta de 50 mg")
        val presentation = input(content, "Presentación o unidad (opcional)", existing?.presentation.orEmpty(), hintText = "Ej. caja con 30 tabletas")
        gap(content, 6)
        content.addView(label("DÍAS Y HORARIOS", accent, 12, true))
        content.addView(label("Elige cuándo empieza y cómo se repite. TzindaApp usará la frecuencia desde ese día.", muted, 14))
        content.addView(label("Frecuencia", ink, 15, true).apply { setPadding(0, dp(10), 0, dp(4)) })
        val frequencyOptions = MedicationSchedulePolicy.frequencyOptions
        val existingFrequencyIndex = existing?.frequency?.let(frequencyOptions::indexOf)?.takeIf { it >= 0 } ?: 0
        val frequency = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, frequencyOptions)
            setSelection(existingFrequencyIndex)
            background = GradientDrawable().apply { setColor(cardColor); cornerRadius = dp(14).toFloat(); setStroke(dp(1), outline) }
            setPadding(dp(12), dp(8), dp(12), dp(8))
            contentDescription = "Frecuencia del medicamento"
        }
        content.addView(frequency, LinearLayout.LayoutParams(-1, dp(54)).apply { bottomMargin = dp(10) })
        val startButton = button("", false) {}
        fun refreshStartButton() { startButton.text = "Comienza · ${formatLocalDate(selectedStart)}" }
        refreshStartButton()
        startButton.setOnClickListener {
            showDatePicker(selectedStart) { chosen -> selectedStart = chosen; refreshStartButton() }
        }
        content.addView(startButton, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        val endRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val endButton = button("", false) {}
        var removeEndButton: Button? = null
        fun refreshEndButton() {
            endButton.text = selectedEnd?.let { "Termina · ${formatLocalDate(it)}" } ?: "Agregar fecha de fin (opcional)"
            removeEndButton?.visibility = if (selectedEnd == null) View.GONE else View.VISIBLE
        }
        val clearEndButton = button("Quitar", false) { selectedEnd = null; refreshEndButton() }
        removeEndButton = clearEndButton
        refreshEndButton()
        endButton.setOnClickListener {
            showDatePicker(selectedEnd ?: selectedStart) { chosen -> selectedEnd = chosen; refreshEndButton() }
        }
        endRow.addView(endButton, LinearLayout.LayoutParams(0, -2, 1f))
        endRow.addView(clearEndButton, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })
        content.addView(endRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        content.addView(label("RECORDATORIOS", accent, 12, true))
        val scheduleHint = label("", muted, 14)
        content.addView(scheduleHint)
        val timesSection = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        content.addView(timesSection)
        var selectedAnchor = existing?.let { MedicationSchedulePolicy.intervalAnchor(it.scheduleTimes) }
            ?.takeIf { MedicationSchedulePolicy.intervalHours(frequencyOptions[existingFrequencyIndex]) != null }
            ?: selectedTimes.minOrNull().orEmpty().ifBlank { "08:00" }
        fun intervalTimesFor(hours: Int): List<String> =
            MedicationSchedulePolicy.intervalTimes(selectedAnchor, hours).orEmpty()
        var renderSection: () -> Unit = {}

        fun renderManualTimes() {
            scheduleHint.text = "Agrega al menos un horario. Se pedirá confirmar cada toma; la app nunca la marca como tomada por su cuenta."
            val timesContainer = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(4), 0, 0)
            }
            fun renderTimes() {
                timesContainer.removeAllViews()
                if (selectedTimes.isEmpty()) {
                    timesContainer.addView(label("Aún no agregas horarios.", muted, 14).apply { setPadding(0, dp(6), 0, dp(4)) })
                } else selectedTimes.sorted().forEach { time ->
                    val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                    row.addView(label(time, ink, 18, true), LinearLayout.LayoutParams(0, -2, 1f))
                    row.addView(tz.textAction("Quitar", { selectedTimes.remove(time); renderTimes() }, R.drawable.ic_trash))
                    row.contentDescription = "Horario ${time.replace(':', ' ')}. Quitar."
                    row.minimumHeight = dp(48)
                    timesContainer.addView(row, LinearLayout.LayoutParams(-1, -2))
                }
            }
            renderTimes()
            timesSection.addView(timesContainer)
            timesSection.addView(button("Agregar horario", false, R.drawable.ic_plus) {
                val last = selectedTimes.lastOrNull()?.split(":")
                val hour = last?.getOrNull(0)?.toIntOrNull() ?: 8
                val minute = last?.getOrNull(1)?.toIntOrNull() ?: 0
                TimePickerDialog(activity, { _, chosenHour, chosenMinute ->
                    val time = "%02d:%02d".format(Locale.ROOT, chosenHour, chosenMinute)
                    if (time in selectedTimes) toast("Ese horario ya está agregado.")
                    else { selectedTimes.add(time); selectedTimes.sort(); renderTimes() }
                }, hour, minute, DateFormat.is24HourFormat(activity)).show()
            })
        }

        fun renderIntervalTimes(hours: Int) {
            scheduleHint.text = "Los avisos se repiten cada $hours horas desde la primera toma. Si tomas a otra hora, podrás ajustar los próximos avisos desde esa toma."
            val firstRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            firstRow.addView(label("Primera toma", ink, 15, true), LinearLayout.LayoutParams(0, -2, 1f))
            val anchorButton = button(selectedAnchor, false) {}
            firstRow.addView(anchorButton, LinearLayout.LayoutParams(dp(132), -2))
            timesSection.addView(firstRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            val generated = intervalTimesFor(hours)
            timesSection.addView(label(generated.joinToString(" · "), ink, 17, true))
            timesSection.addView(
                label(
                    "Cada $hours horas · ${TzindaText.count(generated.size, "toma al día", "tomas al día")} · horarios generados automáticamente",
                    muted, 13,
                ),
            )
            anchorButton.setOnClickListener {
                val parts = selectedAnchor.split(":")
                TimePickerDialog(activity, { _, chosenHour, chosenMinute ->
                    selectedAnchor = "%02d:%02d".format(Locale.ROOT, chosenHour, chosenMinute)
                    renderSection()
                }, parts.getOrNull(0)?.toIntOrNull() ?: 8, parts.getOrNull(1)?.toIntOrNull() ?: 0, DateFormat.is24HourFormat(activity)).show()
            }
        }

        var intervalMode = MedicationSchedulePolicy.intervalHours(frequencyOptions[existingFrequencyIndex]) != null
        renderSection = {
            timesSection.removeAllViews()
            val hours = MedicationSchedulePolicy.intervalHours(frequency.selectedItem?.toString().orEmpty())
            if (hours != null) renderIntervalTimes(hours) else renderManualTimes()
        }
        renderSection()
        frequency.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val hours = MedicationSchedulePolicy.intervalHours(frequencyOptions[position])
                if (hours != null && !intervalMode) {
                    // Al pasar a un intervalo, la hora de referencia es el primer horario que ya tenía.
                    selectedAnchor = selectedTimes.minOrNull().orEmpty().ifBlank { selectedAnchor }
                }
                intervalMode = hours != null
                renderSection()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        gap(content, 4)
        content.addView(label("DETALLES OPCIONALES", accent, 12, true))
        val instructions = input(content, "Indicaciones personales (opcional)", existing?.instructions.orEmpty(), multiline = true, hintText = "Ej. tomar con agua después del desayuno")
        val active = tz.switchRow(
            content,
            "Activar recordatorios",
            "Los avisos se programan solo para este medicamento.",
            value = existing?.isActive ?: true,
            action = {},
        )
        val shareWithCaregiver = tz.switchRow(
            content,
            "Compartir con cuidador/a",
            "Permite que la persona vinculada vea este medicamento, sus horarios y sus tomas. Empieza desactivado.",
            value = existing?.shareWithCaregiver ?: false,
            action = {},
        )

        val labelScanner = scanner
        fun applyGuess(guess: MedicationLabelGuess) {
            if (guess.name.isNotBlank()) name.setText(guess.name)
            if (guess.presentation.isNotBlank()) presentation.setText(guess.presentation)
            if (guess.dose.isNotBlank()) dose.setText(guess.dose)
            val index = frequencyOptions.indexOf(guess.frequency)
            if (index >= 0) {
                frequency.setSelection(index)
                if (guess.anchorTime.isNotBlank()) selectedAnchor = guess.anchorTime
                intervalMode = MedicationSchedulePolicy.intervalHours(guess.frequency) != null
                renderSection()
            }
            toast("Revisa los datos detectados antes de guardar.")
        }

        var scanAgain: () -> Unit = {}
        fun showGuessReview(guess: MedicationLabelGuess) {
            val details = buildString {
                if (guess.name.isNotBlank()) appendLine("Nombre: ${guess.name}")
                if (guess.presentation.isNotBlank()) appendLine("Presentación: ${guess.presentation}")
                if (guess.dose.isNotBlank()) appendLine("Dosis leída: ${guess.dose}")
                if (guess.frequency.isNotBlank()) appendLine("Frecuencia leída: ${guess.frequency}")
                if (guess.anchorTime.isNotBlank()) appendLine("Hora leída: ${guess.anchorTime}")
            }.trim()
            AlertDialog.Builder(activity)
                .setTitle("Datos detectados")
                .setMessage("$details\n\nLa lectura automática puede confundir nombres parecidos. Compara con la caja o la receta: todo se puede editar antes de guardar y TzindaApp no interpreta ni cambia la indicación médica.")
                .setNegativeButton("No usar", null)
                .setNeutralButton("Escanear otra vez") { _, _ -> scanAgain() }
                .setPositiveButton("Usar estos datos") { _, _ -> applyGuess(guess) }
                .show()
        }

        fun handleScanResult(guess: MedicationLabelGuess?, error: String?) {
            if (guess != null) {
                showGuessReview(guess)
            } else {
                AlertDialog.Builder(activity)
                    .setTitle("No se pudo leer la imagen")
                    .setMessage(error ?: "No se detectó texto. Escribe el nombre y la presentación a mano.")
                    .setNegativeButton("Escribir a mano", null)
                    .setPositiveButton("Intentar otra vez") { _, _ -> scanAgain() }
                    .show()
            }
        }

        fun showScanOptions() {
            val labelScannerValue = labelScanner ?: return
            if (labelScannerValue.cloudAvailable) {
                val dialog = Dialog(activity)
                val panel = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(18), dp(14), dp(18), dp(14))
                    background = GradientDrawable().apply { setColor(cardColor); cornerRadius = dp(24).toFloat(); setStroke(dp(1), outline) }
                }
                val header = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                header.addView(label("Escanear caja o receta", ink, 22, true), LinearLayout.LayoutParams(0, -2, 1f))
                header.addView(
                    tz.iconOnlyButton(R.drawable.ic_close, "Cerrar", size = 48) { dialog.dismiss() },
                    LinearLayout.LayoutParams(dp(48), dp(48)),
                )
                panel.addView(header)
                panel.addView(
                    label(
                        "Al elegir la nube, la foto pasa por Supabase y NVIDIA para leer nombre, dosis y presentación; TzindaApp no la guarda. Revisa siempre lo detectado.",
                        muted,
                        13,
                    ).apply { setPadding(0, dp(4), 0, 0) },
                )
                val deviceOnly = tz.switchRow(
                    panel,
                    "Leer en este teléfono",
                    "No envía la foto, pero puede confundir letras.",
                    value = false,
                    action = { },
                )
                gap(panel, 8)
                panel.addView(
                    button("Tomar foto", true, R.drawable.ic_camera) {
                        val mode = if (deviceOnly.isChecked) MedicationScanMode.DEVICE else MedicationScanMode.CLOUD
                        dialog.dismiss()
                        labelScannerValue.takePhoto(mode, ::handleScanResult)
                    },
                )
                gap(panel, 8)
                panel.addView(
                    button("Elegir imagen", false) {
                        val mode = if (deviceOnly.isChecked) MedicationScanMode.DEVICE else MedicationScanMode.CLOUD
                        dialog.dismiss()
                        labelScannerValue.pickFromGallery(mode, ::handleScanResult)
                    },
                )
                gap(panel, 6)
                panel.addView(button("Cancelar", false) { dialog.dismiss() })
                dialog.setContentView(panel)
                dialog.show()
                dialog.window?.apply {
                    setBackgroundDrawableResource(android.R.color.transparent)
                    setLayout((activity.resources.displayMetrics.widthPixels - dp(28)).coerceAtLeast(dp(280)), -2)
                }
                return
            }
            AlertDialog.Builder(activity)
                .setTitle("Escanear caja o receta")
                .setMessage("Toma una foto donde se vean el nombre y la presentación, o elige una imagen. El texto se lee en este teléfono y la foto no se guarda ni se envía.")
                .setNegativeButton("Cancelar", null)
                .setNeutralButton("Elegir imagen") { _, _ -> labelScannerValue.pickFromGallery(MedicationScanMode.DEVICE, ::handleScanResult) }
                .setPositiveButton("Tomar foto") { _, _ -> labelScannerValue.takePhoto(MedicationScanMode.DEVICE, ::handleScanResult) }
                .show()
        }
        scanAgain = { showScanOptions() }

        if (labelScanner != null) {
            scanSlot.addView(button("Escanear caja o receta", false, R.drawable.ic_camera) { showScanOptions() })
            scanSlot.addView(
                label(
                    if (labelScanner.cloudAvailable) {
                        "Al elegir la nube, la foto pasa por Supabase y NVIDIA; la opción en el teléfono no envía nada. Revisa siempre lo detectado."
                    } else {
                        "Lee el nombre, la dosis y la presentación de una foto y rellena el formulario. Revisa siempre lo detectado."
                    },
                    muted,
                    13,
                ),
            )
            gap(content, 6)
        }

        val formError = label("", tz.critical, 14, true).apply {
            visibility = View.GONE
            setPadding(dp(2), dp(8), dp(2), 0)
        }
        root.addView(formError)

        val footer = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        footer.addView(button("Cancelar", false) { dialog.dismiss() }, LinearLayout.LayoutParams(0, -2, 1f))
        var saveButton: Button? = null
        saveButton = button(if (existing == null) "Guardar" else "Guardar cambios") {
            val buttonView = saveButton ?: return@button
            val chosenFrequency = frequency.selectedItem.toString()
            val chosenInterval = MedicationSchedulePolicy.intervalHours(chosenFrequency)
            val schedule = if (chosenInterval != null) {
                MedicationSchedulePolicy.intervalTimes(selectedAnchor, chosenInterval).orEmpty()
            } else {
                selectedTimes.distinct().sorted()
            }
            val validation = MedicationSchedulePolicy.validate(name.text.toString(), dose.text.toString(), chosenFrequency, schedule, selectedStart, selectedEnd)
            if (validation != null) {
                formError.text = validation
                formError.visibility = View.VISIBLE
                tz.announce(formError, validation)
                name.requestFocus()
                return@button
            }
            formError.visibility = View.GONE
            buttonView.isEnabled = false
            buttonView.text = "Guardando…"
            val saved = runCatching {
                repository.createMedication(ownerId, name.text.toString(), dose.text.toString(), presentation.text.toString(),
                    chosenFrequency, schedule, selectedStart, selectedEnd,
                    instructions.text.toString(), active.isChecked, shareWithCaregiver.isChecked, existing)
            }
            saved.onFailure {
                buttonView.isEnabled = true
                buttonView.text = if (existing == null) "Guardar" else "Guardar cambios"
                val message = it.message ?: "No se pudo guardar el medicamento."
                formError.text = message
                formError.visibility = View.VISIBLE
                tz.announce(formError, message)
            }
            saved.onSuccess { item ->
                if (existing != null) MedicationReminderScheduler.cancelMedication(activity, existing)
                if (item.isActive) MedicationReminderScheduler.scheduleMedication(activity, item)
                db.markOverdue(ownerId)
                SyncScheduler.enqueue(activity)
                dialog.dismiss()
                toast(if (ownerId.startsWith("local:")) "Medicamento guardado en este teléfono." else "Medicamento guardado. Se sincronizará con la cuenta.")
                rerender()
            }
        }
        footer.addView(saveButton, LinearLayout.LayoutParams(0, -2, 1.2f).apply { leftMargin = dp(8) })
        root.addView(footer, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        dialog.setContentView(root)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout((activity.resources.displayMetrics.widthPixels - dp(28)).coerceAtLeast(dp(280)), (activity.resources.displayMetrics.heightPixels * .88f).toInt())
        }
    }

    private fun showDatePicker(initial: String, onSelected: (String) -> Unit) {
        val date = runCatching { LocalDate.parse(initial) }.getOrDefault(LocalDate.now())
        DatePickerDialog(activity, { _, year, month, day ->
            onSelected(LocalDate.of(year, month + 1, day).toString())
        }, date.year, date.monthValue - 1, date.dayOfMonth).show()
    }

    private fun confirmShareAll() {
        val privateCount = db.medications(ownerId).count { !it.shareWithCaregiver && !it.isDeleted }
        if (privateCount == 0) {
            toast("Todos tus medicamentos actuales ya están compartidos.")
        } else AlertDialog.Builder(activity)
            .setTitle("Compartir medicamentos")
            .setMessage("Tu cuidador/a vinculado/a podrá consultar ${TzindaText.count(privateCount, "medicamento")}, sus horarios y el historial de tomas. Podrás dejar de compartir cada uno después al editarlo.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Compartir") { _, _ ->
                repository.shareAllWithCaregiver(ownerId)
                syncNow(silent = true)
                toast("Medicamentos marcados para compartir.")
                rerender()
            }.show()
    }

    private fun confirmDelete(medication: Medication) {
        AlertDialog.Builder(activity)
            .setTitle("Eliminar ${medication.name}?")
            .setMessage("Se cancelarán sus próximos recordatorios. El historial de tomas anteriores se conservará.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Eliminar") { _, _ ->
                MedicationReminderScheduler.cancelMedication(activity, medication)
                repository.deleteMedication(ownerId, medication.id)
                SyncScheduler.enqueue(activity)
                rerender()
            }.show()
    }

    private fun showDetails(medication: Medication) {
        val latest = db.intakes(ownerId, limit = 500).filter { it.medicationId == medication.id }.maxByOrNull { it.scheduledAtMillis }
        val message = buildString {
            append("Dosis indicada: ${medication.prescribedDose}\n")
            if (medication.presentation.isNotBlank()) append("Presentación: ${medication.presentation}\n")
            append("Frecuencia: ${medication.frequency}\nHorarios: ${medication.scheduleTimes.joinToString(" · ")}\n")
            append("Inicio: ${medication.startDate}\n")
            medication.endDate?.let { append("Fin: $it\n") }
            if (medication.instructions.isNotBlank()) append("Instrucciones: ${medication.instructions}\n")
            append("Estado: ${if (medication.isActive && !medication.isDeleted) "activo" else "pausado o archivado"}\n")
            if (latest != null) append("Último horario registrado: ${formatDateTime(latest.scheduledAtMillis)} · ${displayStatus(latest)}")
        }
        AlertDialog.Builder(activity).setTitle(medication.name).setMessage(message).setPositiveButton("Listo", null).show()
    }

    private fun showHistory() {
        val all = visibleIntakes(limit = 500).filter { it.status != "cancelled" }
        val history = all.mapNotNull { intake ->
            val medication = db.getMedication(ownerId, intake.medicationId) ?: return@mapNotNull null
            Triple(intake, medication.name, displayStatus(intake))
        }
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), dp(8)) }
        if (history.isEmpty()) content.addView(label("Aún no hay tomas en el historial.", muted, 15))
        history.forEach { (intake, name, status) ->
            content.addView(label("${formatDateTime(intake.scheduledAtMillis)} · $name", ink, 16, true))
            content.addView(label("$status${intake.actionAtMillis?.let { " · acción ${formatTime(it)}" }.orEmpty()}", muted, 14))
            gap(content, 8)
        }
        AlertDialog.Builder(activity).setTitle("Historial de tomas")
            .setView(ScrollView(activity).apply { addView(content) })
            .setPositiveButton("Cerrar", null).show()
    }

    private fun actOnIntake(id: String, status: String) {
        val apply = {
            val ok = repository.acceptIntakeAction(id, status)
            if (ok && status == "snoozed") {
                val intake = db.getIntake(id)
                if (intake?.snoozedUntilMillis != null) MedicationReminderScheduler.scheduleSnooze(activity, id, intake.snoozedUntilMillis)
            }
            if (ok && status != "snoozed") MedicationReminderScheduler.cancelOverdueCheck(activity, id)
            if (ok) MedicationReminderReceiver.dismissNotification(activity, id)
            if (!ok) toast("Esta toma ya tiene un estado registrado.")
            rerender()
            if (ok && status == "taken") offerIntervalRealign(id)
        }
        if (status == "taken") AlertDialog.Builder(activity).setTitle("¿Confirmar esta toma como realizada?")
            .setMessage("La acción se guardará en el historial con tu nombre para saber quién la registró.")
            .setNegativeButton("Volver", null).setPositiveButton("Sí, marcar tomada") { _, _ -> apply() }.show()
        else if (status == "skipped") AlertDialog.Builder(activity).setTitle("¿Registrar esta toma como omitida?")
            .setMessage("La acción se guardará en el historial con tu nombre para saber quién la registró.")
            .setNegativeButton("Volver", null).setPositiveButton("Registrar") { _, _ -> apply() }.show()
        else apply()
    }

    /**
     * Cuando una toma se registra lejos de la hora prevista, los intervalos fijos dejan de
     * coincidir con la administración real. Se propone mover los próximos avisos, nunca se hace solo.
     */
    private fun offerIntervalRealign(intakeId: String) {
        if (readOnly) return
        val intake = db.getIntake(intakeId) ?: return
        val medication = db.getMedication(ownerId, intake.medicationId) ?: return
        val hours = MedicationSchedulePolicy.intervalHours(medication.frequency) ?: return
        val actual = intake.actionAtMillis ?: return
        if (kotlin.math.abs(actual - intake.scheduledAtMillis) < INTERVAL_REALIGN_MIN_MS) return
        val anchor = formatTime(actual)
        if (anchor in medication.scheduleTimes) return
        val newTimes = MedicationSchedulePolicy.intervalTimes(anchor, hours) ?: return
        AlertDialog.Builder(activity)
            .setTitle("¿Ajustar los próximos avisos?")
            .setMessage(
                "Registraste ${medication.name} a las $anchor y el recordatorio era a las ${formatTime(intake.scheduledAtMillis)}. " +
                    "Si ajustas, los avisos se repetirán cada $hours horas desde las $anchor: ${newTimes.joinToString(" · ")}.\n\n" +
                    "Solo cambia los recordatorios de la app; la indicación médica sigue igual.",
            )
            .setNegativeButton("Mantener horario", null)
            .setPositiveButton("Ajustar avisos") { _, _ -> realignSchedule(medication, newTimes) }
            .show()
    }

    private fun realignSchedule(medication: Medication, newTimes: List<String>) {
        val saved = runCatching {
            repository.createMedication(
                ownerId, medication.name, medication.prescribedDose, medication.presentation, medication.frequency,
                newTimes, medication.startDate, medication.endDate, medication.instructions,
                medication.isActive, medication.shareWithCaregiver, existing = medication,
            )
        }
        saved.onFailure { failure ->
            val message = failure.message ?: "No se pudo ajustar el horario."
            toast(message)
            parent?.let { tz.announce(it, message) }
        }
        saved.onSuccess { updated ->
            MedicationReminderScheduler.cancelMedication(activity, medication)
            if (updated.isActive) MedicationReminderScheduler.scheduleMedication(activity, updated)
            db.markOverdue(ownerId)
            SyncScheduler.enqueue(activity)
            toast("Próximos avisos desde las ${MedicationSchedulePolicy.intervalAnchor(updated.scheduleTimes)}.")
            rerender()
        }
    }

    private fun syncNow(silent: Boolean = false) {
        if (readOnly && ownerId.isBlank()) return
        val sessionId = runCatching { EncryptedSessionStore(activity).read()?.userId }.getOrNull()
        if (sessionId.isNullOrBlank()) {
            syncState?.text = if (readOnly)
                "Sesión vencida: inicia sesión desde Ajustes para actualizar los medicamentos compartidos."
            else "Modo local: al iniciar sesión y recuperar Internet podrás sincronizar."
            return
        }
        syncState?.text = "Sincronizando de forma segura…"
        syncExecutor.execute {
            val result = runCatching { repository.synchronize(ownerId, readOnly) }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                syncState?.text = when {
                    result.isSuccess && readOnly -> "Datos compartidos actualizados desde Supabase."
                    result.isSuccess -> if (db.syncPendingCount(ownerId) == 0) "Sincronizado con Supabase." else "Hay cambios locales pendientes de sincronizar."
                    result.exceptionOrNull() is BackendFailure && (result.exceptionOrNull() as BackendFailure).category == BackendFailure.Category.NETWORK -> "Sin Internet. Los medicamentos y recordatorios siguen guardados aquí."
                    else -> "No se pudo sincronizar: ${result.exceptionOrNull()?.message?.takeIf { it.isNotBlank() }?.take(140) ?: "revisa la conexión"}. Tus datos locales se conservaron."
                }
                if (!silent) toast(if (result.isSuccess) "Actualizado" else "Sincronización pendiente")
                rerender(keepSyncState = true)
            }
        }
    }

    private fun rerender(keepSyncState: Boolean = false) {
        val target = parent ?: return
        val previous = syncState?.text?.toString()
        // Rebuilding the list after an automatic sync used to throw the reader back to the top.
        val scroll = target.parent as? ScrollView
        val scrollY = scroll?.scrollY ?: 0
        target.removeAllViews()
        render(target)
        if (keepSyncState && !previous.isNullOrBlank()) syncState?.text = previous
        scroll?.post { scroll.scrollTo(0, scrollY) }
    }

    /**
     * A caregiver only ever sees medications explicitly shared by the linked person. Private
     * medications, their intakes and their adherence never leave the protected person's account.
     */
    private fun visibleMedications(includeDeleted: Boolean = true): List<Medication> =
        db.medications(ownerId).filter { medication ->
            (if (readOnly) repository.canReadSharedOwner(ownerId) else repository.canManageOwner(ownerId)) &&
            (includeDeleted || (!medication.isDeleted && medication.isActive)) &&
                (!readOnly || medication.shareWithCaregiver)
        }

    private fun visibleIntakes(fromMillis: Long? = null, toMillis: Long? = null, limit: Int = 500): List<MedicationIntake> {
        // Las tomas de un medicamento archivado/borrado no deben seguir apareciendo en "Hoy"
        // ni contar para la adherencia; solo se muestran las de medicamentos visibles.
        val sharedIds = visibleMedications(includeDeleted = false).mapTo(HashSet()) { it.id }
        val intakes = when {
            fromMillis != null && toMillis != null -> db.intakes(ownerId, fromMillis, toMillis)
            else -> db.intakes(ownerId, limit = limit)
        }
        return intakes.filter { it.medicationId in sharedIds }
    }

    private fun todayIntakes(): List<MedicationIntake> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val start = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return visibleIntakes(start, end).filter { it.status != "cancelled" }.sortedBy { it.scheduledAtMillis }
    }

    private fun nextIntakes(): List<Pair<Medication, Long>> {
        val now = System.currentTimeMillis()
        return visibleMedications(includeDeleted = false).flatMap { medication ->
            medication.scheduleTimes.mapNotNull { time ->
                MedicationSchedulePolicy.nextOccurrence(medication, time, now, ZoneId.systemDefault())?.let { medication to it }
            }
        }.sortedBy { it.second }
    }

    private fun adherenceSummary(days: Int): MedicationAdherenceSummary {
        val now = System.currentTimeMillis()
        val from = now - days * 24L * 60L * 60L * 1000L
        val due = visibleIntakes(from, now + 1, limit = 2_000)
            .filter { it.status != "cancelled" && it.scheduledAtMillis <= now }
        return MedicationAdherenceSummary(
            scheduledDue = due.size,
            taken = due.count { it.status == "taken" },
            pending = due.count { it.status in setOf("pending", "snoozed") },
            omitted = due.count { it.status in setOf("skipped", "overdue") },
        )
    }

    private fun adherenceTone(summary: MedicationAdherenceSummary): TzindaDesign.Tone =
        when {
            summary.scheduledDue == 0 -> TzindaDesign.Tone.NEUTRAL
            (summary.percentage ?: 0) >= 80 -> TzindaDesign.Tone.SUCCESS
            (summary.percentage ?: 0) >= 50 -> TzindaDesign.Tone.WARNING
            else -> TzindaDesign.Tone.DANGER
        }

    private fun adherenceToneLabel(summary: MedicationAdherenceSummary): String =
        when {
            summary.scheduledDue == 0 -> "Sin datos"
            (summary.percentage ?: 0) >= 80 -> "Buen seguimiento"
            (summary.percentage ?: 0) >= 50 -> "Seguimiento parcial"
            else -> "Requiere atención"
        }

    private fun doseLine(medication: Medication): String =
        medication.prescribedDose + medication.presentation.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()

    private fun spokenTime(millis: Long): String = formatTime(millis).split(":")
        .let { parts -> "${parts.getOrNull(0)?.toIntOrNull() ?: 0}:${parts.getOrNull(1)?.toString()?.padStart(2, '0') ?: "00"}" }

    private fun spokenSchedule(times: List<String>): String =
        if (times.isEmpty()) "sin horarios" else times.joinToString(", ") { it.replace(':', ' ') }

    private fun displayStatus(intake: MedicationIntake): String {
        if (intake.status == "pending" && System.currentTimeMillis() - intake.scheduledAtMillis > MedicationDatabase.OVERDUE_AFTER_MS) return "Vencida · sin confirmar"
        return when (intake.status) {
            "pending" -> "Pendiente"
            "taken" -> "Tomada · confirmada"
            "skipped" -> "No tomada · confirmada"
            "overdue" -> "Vencida · sin confirmar"
            "snoozed" -> "Pospuesta · aviso pendiente"
            else -> "Estado: ${intake.status}"
        }
    }

    private fun requestExactAlarmPermission() {
        AlertDialog.Builder(activity).setTitle("Horarios precisos")
            .setMessage("Android puede exigir un permiso especial para avisar cerca de la hora indicada. Si no lo autorizas, TzindaApp usará recordatorios aproximados.")
            .setNegativeButton("Seguir con avisos aproximados", null)
            .setPositiveButton("Abrir ajustes") { _, _ ->
                if (Build.VERSION.SDK_INT >= 31) runCatching {
                    activity.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${activity.packageName}")))
                }.onFailure { toast("Abre Ajustes de Android para permitir alarmas y recordatorios.") }
            }.show()
    }

    private fun isSimple() = tz.simple

    private fun input(
        parent: LinearLayout,
        title: String,
        value: String,
        multiline: Boolean = false,
        hintText: String? = null,
    ): EditText {
        val input = tz.field(
            parent,
            title,
            value,
            if (multiline) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
            hintText = hintText,
        )
        if (multiline) {
            input.minLines = 2
            input.setSingleLine(false)
        }
        return input
    }

    private fun heading(parent: LinearLayout, eyebrow: String, title: String, subtitle: String) {
        tz.heading(parent, eyebrow, title, subtitle)
    }

    private fun card(parent: LinearLayout) = tz.plainCard(parent)

    private fun label(value: String, color: Int = ink, size: Int = 16, bold: Boolean = false) =
        tz.text(value, size, bold, color)

    private fun button(title: String, primary: Boolean = true, iconRes: Int? = null, action: () -> Unit) =
        tz.button(title, primary, action, iconRes)

    private fun gap(parent: LinearLayout, height: Int) { tz.addGap(parent, height) }
    private fun formatTime(millis: Long) = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))
    private fun formatDateTime(millis: Long) = SimpleDateFormat("EEE d MMM · HH:mm", Locale("es", "MX")).format(Date(millis))
    private fun formatLocalDate(value: String): String = runCatching {
        LocalDate.parse(value).format(DateTimeFormatter.ofPattern("d 'de' MMM yyyy", Locale("es", "MX")))
    }.getOrDefault(value)
    private fun toast(message: String) = android.widget.Toast.makeText(activity, message, android.widget.Toast.LENGTH_LONG).show()
    private fun dp(value: Int) = tz.dp(value)

    companion object {
        private const val REQUEST_NOTIFICATIONS = 503
        private const val INTERVAL_REALIGN_MIN_MS = 20 * 60 * 1000L
        private val syncExecutor = Executors.newSingleThreadExecutor()
    }
}
