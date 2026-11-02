package com.example.guardian_iot

import org.json.JSONArray
import org.json.JSONObject

/** A failed, malformed or truncated read is never an authoritative empty snapshot. */
internal object RemoteSnapshot {
    fun readAll(fetchAfter: (String?) -> JSONArray): JSONArray {
        val rows = JSONArray()
        val seen = mutableSetOf<String>()
        var cursor: String? = null
        while (true) {
            val page = fetchAfter(cursor)
            if (page.length() == 0) return rows
            for (index in 0 until page.length()) {
                val row = page.getJSONObject(index)
                val id = row.getString("id")
                check(id.isNotBlank() && seen.add(id) && (cursor == null || id > cursor!!)) {
                    "Respuesta remota incompleta o desordenada; se conserva la copia local."
                }
                rows.put(row)
                cursor = id
            }
            check(rows.length() <= 100_000) { "Consulta demasiado grande; se conserva la copia local." }
        }
    }

    fun <T> parse(rows: JSONArray, parseRow: (JSONObject) -> T?): List<T> =
        (0 until rows.length()).map { index ->
            parseRow(rows.getJSONObject(index))
                ?: error("Registro remoto inválido; se conserva la copia local.")
        }

    fun <M, I> download(
        medications: () -> List<M>, intakes: () -> List<I>, apply: (List<M>, List<I>) -> Unit,
    ) {
        val medicationRows = medications()
        val intakeRows = intakes()
        apply(medicationRows, intakeRows)
    }
}

internal object DeviceRegistration {
    fun <T> confirm(writeState: (String) -> Unit, register: () -> T): T {
        writeState("pending")
        return try {
            register().also { writeState("confirmed") }
        } catch (failure: Exception) {
            writeState("error")
            throw failure
        }
    }
}

internal object SyncOverview {
    fun total(modules: Collection<SyncStateSummary>): SyncStateSummary = SyncStateSummary(
        modules.sumOf { it.pending.coerceAtLeast(0) }, modules.sumOf { it.failed.coerceAtLeast(0) },
    )
}
