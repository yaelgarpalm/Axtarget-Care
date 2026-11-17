package com.example.guardian_iot

import android.app.Activity
import android.widget.LinearLayout
import org.json.JSONObject

/** Renders the caregiver's read-only view of a linked wearer's registered devices. */
internal class CaregiverDeviceLinksUi(
    private val activity: Activity,
    private val design: TzindaDesign,
    private val linked: Boolean,
    private val personName: String,
    private val fetchedAt: Long,
    private val devices: List<JSONObject>,
    private val deviceCopy: (JSONObject) -> TzindaCopy,
    private val lastSignal: (JSONObject) -> Long,
    private val onManageLink: () -> Unit,
    private val onOpenCameraEvents: () -> Unit,
    private val onRefresh: () -> Unit,
    private val onOpenSupervision: () -> Unit,
) {
    fun render(body: LinearLayout) {
        design.heading(body, "CONECTAR DISPOSITIVOS", "Dispositivos y enlaces", "Gestiona la pulsera, las cámaras y la vinculación con tu red de apoyo.")
        val link = design.plainCard(body)
        link.addView(pill(TzindaStatusCopy.link(linked, personName, fetchedAt)))
        link.addView(design.text(
            if (linked) "Consulta los equipos registrados por la persona protegida. El estado de cada equipo se muestra por separado del vínculo."
            else "Escanea el código de la persona protegida para acompañarla.",
            14,
            color = design.muted,
        ).apply { setPadding(0, dp(8), 0, 0) })
        link.addView(design.button(if (linked) "Administrar vínculo" else "Vincular persona", false, onManageLink))

        if (linked) {
            design.section(body, "Equipos de $personName", TzindaText.count(devices.size, "registrado", "registrados"))
            if (devices.isEmpty()) {
                design.emptyState(body, "Sin equipos registrados", "La cuenta vinculada aún no tiene una pulsera o teléfono registrado.")
            } else devices.forEach { device ->
                val last = lastSignal(device)
                val item = design.plainCard(body)
                item.addView(design.text(device.optString("name").ifBlank { "Dispositivo" }, 18, true))
                val kind = device.optString("device_type").replace('_', ' ').replaceFirstChar { it.uppercase() }
                item.addView(design.text(
                    kind + if (last > 0) " · Última señal ${TzindaStatusCopy.freshness(System.currentTimeMillis() - last)}" else " · Sin señal registrada",
                    13,
                    color = design.muted,
                ))
                item.addView(pill(deviceCopy(device)))
            }
            val cameras = devices.filter { it.optString("device_type") == "camera" }
            if (cameras.isNotEmpty()) {
                design.section(body, "Cámaras de $personName", TzindaText.count(cameras.size, "cámara", "cámaras"))
                cameras.forEachIndexed { index, camera ->
                    val item = design.plainCard(body)
                    item.addView(design.text(camera.optString("name").ifBlank { "Cámara" }, 18, true))
                    item.addView(pill(deviceCopy(camera)))
                    item.addView(design.text("Los eventos de esta cámara aparecen en el historial compartido.", 13, color = design.muted))
                    if (index == 0) item.addView(design.button("Ver eventos de cámara", false, onOpenCameraEvents))
                }
            }
            body.addView(design.button("Actualizar datos de la persona", false, onRefresh))
            body.addView(design.button("Abrir centro de supervisión", false, onOpenSupervision))
        }

        val limits = design.plainCard(body)
        limits.addView(design.text("Qué puedes administrar", 18, true))
        limits.addView(design.text(
            "Puedes cambiar el estado de las alertas y crear, editar o pausar las zonas seguras que la persona comparte. La pulsera, la vinculación de cámaras y los medicamentos siguen bajo el control de la persona protegida.",
            13,
            color = design.muted,
        ))
    }

    private fun pill(copy: TzindaCopy) = design.statusPill(copy.title, when (copy.tone) {
        TzindaTone.POSITIVE -> TzindaDesign.Tone.SUCCESS
        TzindaTone.INFO -> TzindaDesign.Tone.INFO
        TzindaTone.WARNING -> TzindaDesign.Tone.WARNING
        TzindaTone.CRITICAL -> TzindaDesign.Tone.DANGER
        TzindaTone.NEUTRAL -> TzindaDesign.Tone.NEUTRAL
    })

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
}
