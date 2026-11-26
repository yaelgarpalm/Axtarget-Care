package com.example.guardian_iot

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentDialog
import androidx.activity.OnBackPressedCallback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

internal data class MapPerson(val name: String, val fix: MapFix?, val zone: MapZone?) {
    val firstName get() = name.substringBefore(' ').takeUnless { it == "Persona" } ?: "la persona"
}

internal enum class LocationAlertType { HELP, EXIT, ARRIVAL }

internal data class LocationAlert(
    val type: LocationAlertType,
    val title: String,
    val detail: String,
    val zone: MapZone? = null,
)

internal class TzindaV11(
    private val activity: Activity,
    private val v5: TzindaV5,
    private val tiles: TzindaMapService,
    private val deviceLocation: TzindaMapLocation,
    private val requestFix: ((MapFix?) -> Unit) -> Unit,
    private val sharedFix: (() -> MapFix?)? = null,
    private val authorized: () -> Boolean = { true },
) {
    private val theme get() = v5.design
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var dialog: ComponentDialog? = null
    private var job: Job? = null
    private var backAction: () -> Unit = ::close
    private var pageVersion = 0
    private var positionStillAvailable: (() -> Boolean)? = null
    private var positionUnavailable: (() -> Unit)? = null
    private var refreshZoneLocation: (() -> Unit)? = null
    private var stopZoneLocation: (() -> Unit)? = null
    private var usesDeviceLocation = false
    private var foreground = true
    private var example = false
    private var leaveExample: (() -> Unit)? = null
    private var exampleZone = DEFAULT_ZONE

    fun isShowing() = dialog?.isShowing == true

    fun setForeground(visible: Boolean) {
        foreground = visible
        if (visible) checkAccess() else stopObservingZoneLocation()
    }

    private fun stopObservingZoneLocation() {
        stopZoneLocation?.invoke()
        stopZoneLocation = null
    }

    private fun clearZoneLocation() {
        refreshZoneLocation = null
        usesDeviceLocation = false
        stopObservingZoneLocation()
    }

    private fun refreshZonePosition() {
        val update = refreshZoneLocation ?: return
        if (!foreground || !isShowing()) return
        if (usesDeviceLocation && stopZoneLocation == null) {
            stopZoneLocation = deviceLocation.observe {
                if (foreground) checkAccess()
            }
        }
        update()
    }

    fun close() {
        clearZoneLocation()
        work.cancel()
        dialog?.dismiss()
        dialog = null
    }

    fun checkAccess() {
        if (!isShowing() || example) return
        if (!authorized()) { close(); return }
        refreshZonePosition()
        if (positionStillAvailable?.invoke() == false) {
            val replace = positionUnavailable
            positionStillAvailable = null
            positionUnavailable = null
            replace?.invoke()
        }
    }

    private fun show(view: View, back: () -> Unit) {
        if (activity.isFinishing || activity.isDestroyed || !example && !authorized()) {
            close()
            return
        }
        job?.cancel()
        clearZoneLocation()
        pageVersion++
        positionStillAvailable = null
        positionUnavailable = null
        backAction = back
        val window = dialog ?: ComponentDialog(activity, if (theme.dark) android.R.style.Theme_Material_NoActionBar
            else android.R.style.Theme_Material_Light_NoActionBar).also { created ->
            created.onBackPressedDispatcher.addCallback(created, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = backAction()
            })
            created.setOnDismissListener {
                clearZoneLocation()
                work.cancel()
                dialog = null
            }
            dialog = created
        }
        v5.showFullScreen(window, view, top = theme.paper, bottom = theme.cardColor)
    }

    private fun dp(n: Int) = v5.dp(n)
    private fun caption(s: String, size: Int = 12) = v5.text(s, size, theme.muted)
    private fun toast(text: String) = Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()
    private fun map(kind: ExampleMap? = null, choose: ((MapPoint) -> Unit)? = null) = TzindaMapView(activity, tiles, kind, choose)
    private fun go(uri: Uri, action: String = Intent.ACTION_VIEW) {
        runCatching { activity.startActivity(Intent(action, uri)) }.onFailure { toast("No hay una aplicación disponible.") }
    }

    private fun tag(label: String, fill: Int = theme.brandSoft, color: Int = theme.accent) =
        v5.text(label, 11, color, bold = true).apply {
            background = theme.rounded(fill, 99)
            minimumHeight = dp(26)
            setPadding(dp(11), dp(4), dp(13), dp(4))
            gravity = Gravity.CENTER_VERTICAL
        }

    private fun rowParams(gap: Int = 0) = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(gap) }

    private fun mapHeader(title: String, detail: String, back: () -> Unit, actions: (() -> Unit)?): View =
        v5.horizontal().apply {
            minimumHeight = dp(63)
            setPadding(dp(4), dp(6), dp(9), dp(6))
            background = theme.rounded(theme.cardColor, 22)
            addView(v5.iconButton(R.drawable.v5_back, "Volver", Color.TRANSPARENT, glyphSize = 30, action = back),
                LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(v5.column().apply {
                addView(v5.text(title, 18, bold = true))
                v5.gap(this, 4)
                addView(caption(detail, 10))
            }, rowParams(7).apply { marginStart = dp(7) })
            val shield = v5.badge(R.drawable.v5_shield, theme.brandSoft, theme.accent, 36, 20, 99)
            if (actions != null) {
                shield.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                shield.contentDescription = "Opciones del mapa"
                v5.activate(shield, actions)
            }
            addView(shield)
        }

    private fun panelPosition(gravity: Int, horizontal: Int, top: Int = 0, bottom: Int = 0, width: Int = -2) =
        FrameLayout.LayoutParams(if (width >= 0) dp(width) else width, -2, gravity).apply {
            setMargins(dp(horizontal), dp(top), dp(horizontal), dp(bottom))
        }

    private fun mapPage(
        map: TzindaMapView,
        title: String,
        detail: String,
        sheet: LinearLayout,
        back: () -> Unit,
        query: String? = null,
        search: (() -> Unit)? = null,
        status: View? = null,
        controls: Boolean = true,
        controlsBottom: Int = 38,
        menu: (() -> Unit)? = null,
        customHeader: View? = null,
        useLocation: (() -> Unit)? = null,
    ) {
        val outer = FrameLayout(activity).apply {
            setBackgroundColor(theme.paper)
            setPadding(0, dp(19), 0, 0)
        }
        val overlap = 28
        val mapPanel = FrameLayout(activity)
        mapPanel.addView(map, FrameLayout.LayoutParams(-1, -1))
        val header = customHeader ?: mapHeader(title, detail, back, menu)
        mapPanel.addView(header, panelPosition(Gravity.TOP, 16, top = 13, width = -1))
        if (query != null && search != null) {
            val searchBar = v5.horizontal().apply {
                background = theme.rounded(theme.cardColor, 18)
                minimumHeight = dp(48)
                setPadding(dp(14), 0, dp(14), 0)
                addView(v5.icon(R.drawable.v11_search, 22, theme.accent))
                addView(caption(query, 13), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) })
                contentDescription = query
                v5.activate(this, search)
            }
            mapPanel.addView(searchBar, panelPosition(Gravity.TOP or Gravity.START, 16, top = 88, width = if (query.length > 25) 300 else 264))
        }
        status?.let { mapPanel.addView(it, panelPosition(Gravity.TOP or Gravity.START, 16, top = if (query == null) 94 else 151)) }
        if (controls) {
            val buttons = v5.column()
            val zoom = v5.column().apply {
                background = theme.rounded(theme.cardColor, 23)
                addView(mapControl("Acercar mapa", R.drawable.ic_plus) { map.zoomBy(1.0) })
                addView(mapControl("Alejar mapa", R.drawable.v11_minus) { map.zoomBy(-1.0) })
            }
            buttons.addView(zoom)
            v5.gap(buttons, 12)
            buttons.addView(mapControl(if (useLocation == null) "Centrar mapa" else "Usar mi ubicación",
                R.drawable.v11_target, theme.cardColor, useLocation ?: map::centerMap))
            mapPanel.addView(buttons, panelPosition(Gravity.END or Gravity.BOTTOM, 20, bottom = overlap + controlsBottom))
        }
        val attribution = caption(if (title.isBlank() || title.startsWith("Llegar")) "© OpenStreetMap contributors · OSRM" else "© OpenStreetMap contributors", 10).apply {
            background = theme.rounded(theme.cardColor, 5)
            setPadding(dp(7), dp(3), dp(6), dp(3))
            minimumWidth = dp(241)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            v5.activate(this) { showAttribution() }
        }
        mapPanel.addView(attribution, panelPosition(Gravity.END or Gravity.BOTTOM, 16, bottom = overlap + 7))
        outer.addView(mapPanel, FrameLayout.LayoutParams(-1, -1))
        outer.addView(sheet, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        sheet.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val params = mapPanel.layoutParams as FrameLayout.LayoutParams
            val inset = (bottom - top - dp(overlap)).coerceAtLeast(0)
            if (params.bottomMargin != inset) {
                params.bottomMargin = inset
                mapPanel.layoutParams = params
            }
        }
        show(outer, back)
    }

    private fun mapControl(label: String, icon: Int, fill: Int = Color.TRANSPARENT, action: () -> Unit): View =
        v5.iconButton(icon, label, fill, glyphSize = 23, action = action).apply {
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        }

    private fun showAttribution() {
        AlertDialog.Builder(activity).setTitle("Cartografía y rutas")
            .setMessage("© OpenStreetMap contributors · ODbL\nIndicaciones calculadas con OSRM.")
            .setNegativeButton("Cerrar", null)
            .setNeutralButton("Informar de un error") { _, _ -> go(Uri.parse("https://www.openstreetmap.org/fixthemap")) }
            .setPositiveButton("Licencia") { _, _ -> go(Uri.parse("https://www.openstreetmap.org/copyright")) }.show()
    }

    private fun sheet(height: Int, compose: (LinearLayout) -> Unit): LinearLayout {
        val panel = v5.column()
        panel.background = GradientDrawable().apply {
            setColor(theme.cardColor)
            val radius = dp(31).toFloat()
            cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
        }
        panel.addView(View(activity).apply { background = theme.rounded(theme.outline, 99) },
            LinearLayout.LayoutParams(dp(54), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(11)
                bottomMargin = dp(14)
            })
        val content = v5.column().apply {
            minimumHeight = dp(height - 29)
            setPadding(dp(20), 0, dp(20), dp(3))
        }
        compose(content)
        panel.addView(object : ScrollView(activity) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val available = MeasureSpec.getSize(heightMeasureSpec)
                val limit = (activity.window.decorView.height * .56f).toInt().coerceAtLeast(dp(220))
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(
                    if (available > 0) minOf(available, limit) else limit, MeasureSpec.AT_MOST))
            }
        }.apply {
            isVerticalScrollBarEnabled = false
            addView(content)
        }, LinearLayout.LayoutParams(-1, -2))
        return panel
    }

    fun zones(
        read: () -> List<MapZone>,
        save: ((MapZone) -> Boolean)?,
        delete: ((MapZone) -> Boolean)?,
        back: () -> Unit = ::close,
        selectedId: String? = null,
        readFix: () -> MapFix? = deviceLocation::latest,
        shared: Boolean = false,
    ) {
        val zones = if (example) listOf(exampleZone, SAMPLE_HEALTH, SAMPLE_PARK) else read()
        val selected = zones.firstOrNull { it.id == selectedId } ?: zones.firstOrNull()
        var fix = if (example) null else readFix()?.takeIf { it.isRecent() }
        var followPosition = selectedId == null
        fun focus(position: MapFix?) = if (followPosition) position?.point ?: selected?.point
            else selected?.point ?: position?.point
        fun marker(position: MapFix?) = position?.let {
            MapMarker(it.point, if (shared) "Ubicación" else "Tú", TzindaMapView.BLUE)
        }
        val view = map(if (example) ExampleMap.ZONES else null)
        view.exampleRadius = selected?.radius ?: 5000
        var shownFocus = focus(fix)
        view.display(shownFocus, listOfNotNull(marker(fix)), selected, maxInitialZoom = 14.0)
        val reload: (String?) -> Unit = { id -> zones(read, save, delete, back, id, readFix, shared) }
        val update: (MapZone) -> Unit = { draft ->
            editZone(draft, zones, onSave = { zone ->
                if (example) { exampleZone = zone; reload(zone.id); true }
                else if (save?.invoke(zone) == true) { reload(zone.id); true } else false
            }, back = { reload(draft.id) })
        }
        val find: () -> Unit = {
            search(zones, { place ->
                val existing = zones.firstOrNull { it.point == place.point }
                update(existing ?: MapZone(place.name.takeIf { it !in setOf("Coordenadas", "Mi ubicación") }.orEmpty(), place.point, shared = shared))
            }) { reload(selectedId) }
        }
        lateinit var statusText: TextView
        val panel = sheet(266) { body ->
            val heading = v5.horizontal()
            heading.addView(v5.title("Tus lugares seguros", 22), rowParams(8))
            val list = tag(TzindaText.count(zones.size, "lugar", "lugares")).apply { minimumWidth = dp(90) }
            if (zones.isNotEmpty()) v5.activate(list) {
                AlertDialog.Builder(activity).setTitle("Tus lugares seguros").setItems(zones.map(MapZone::name).toTypedArray()) { _, n ->
                    reload(zones[n].id)
                }.setNegativeButton("Cerrar", null).show()
            }
            heading.addView(list)
            body.addView(heading)
            v5.gap(body, 31)
            val tile = v5.panel(body, theme.paper, radius = 22, padding = 0, margin = 14)
            if (selected == null) {
                v5.row(tile, "Sin zonas configuradas", "Busca una dirección o usa tu ubicación.", R.drawable.v5_pin, minHeight = 74)
            } else {
                val summary = if (example) "Ubicación de demostración" else if (selected.enabled) "Zona activa" else "Zona pausada"
                val card = v5.row(tile, selected.name, summary, R.drawable.v5_home, fill = Color.TRANSPARENT, minHeight = 75,
                    emphasis = true, titleSize = 18, subtitleColor = theme.positive, inset = 11, iconGap = 11, detailGap = 8,
                    trailing = tag(formatMapDistance(selected.radius.toDouble())).apply { minimumWidth = dp(68) }) {
                    if (save == null && !example) return@row
                    val actions = arrayOf("Editar", if (selected.enabled) "Pausar" else "Activar", "Eliminar")
                    if (example) update(selected)
                    else AlertDialog.Builder(activity).setTitle(selected.name).setItems(actions) { _, index ->
                        when (index) {
                            0 -> update(selected)
                            1 -> if (save?.invoke(selected.copy(enabled = !selected.enabled)) == true) reload(selected.id)
                            2 -> AlertDialog.Builder(activity).setMessage("¿Eliminar esta zona?")
                                .setNegativeButton("Cancelar", null).setPositiveButton("Eliminar") { _, _ ->
                                    if (delete?.invoke(selected) == true) reload(null)
                                }.show()
                        }
                    }.show()
                }
                if (save == null && !example) { card.isClickable = false; card.isFocusable = false }
            }
            statusText = infoLine(body, if (example) "Hace 1 min · Precisión ±25 m" else "", R.drawable.v5_clock)
            v5.gap(body, 14)
            if (save != null || example) v5.action(body, "Añadir lugar seguro", R.drawable.ic_plus, margin = 0, action = find)
        }
        val badge = if (example) tag("Dentro de Casa", theme.mint, theme.positive).apply { minimumWidth = dp(134) }
            else tag("").apply { visibility = View.GONE }
        var lastWithin: Boolean? = null
        fun showLocation() {
            val next = readFix()?.takeIf { it.isRecent() }
            val target = focus(next)
            if (next != fix || target != shownFocus) {
                view.updatePosition(marker(next), target)
                fix = next
                shownFocus = target
            }
            val status = next?.zoneStatus() ?: if (shared) "Sin ubicación compartida reciente"
                else deviceLocation.unavailableMessage() ?: "Buscando tu ubicación…"
            if (statusText.text != status) statusText.text = status
            val within = selected?.takeIf { it.enabled }?.let { zone -> next?.let { positionInZone(it, zone) } }
            if (within != lastWithin) {
                lastWithin = within
                badge.visibility = if (within == null) View.GONE else View.VISIBLE
                if (within != null) {
                    badge.text = "${if (within) "Dentro de" else "Fuera de"} ${selected?.name.orEmpty()}"
                    badge.setTextColor(if (within) theme.positive else theme.warning)
                    badge.background = theme.rounded(if (within) theme.mint else theme.warningSurface, 99)
                }
            }
        }
        val recenter: () -> Unit = {
            followPosition = true
            refreshZonePosition()
            view.centerMap()
            if (!shared && deviceLocation.unavailableMessage() != null) {
                withFix { refreshZonePosition(); view.centerMap() }
            }
        }
        mapPage(view, "Zonas seguras", if (example) "TZINDA · Ubicación de ejemplo" else if (shared) "TZINDA · Zonas compartidas" else "TZINDA · Tus lugares de confianza",
            panel, back, "Buscar un lugar seguro", if (save != null || example) find else null, status = badge,
            menu = {
                if (example) exampleMenu { reload(selected?.id) }
                else AlertDialog.Builder(activity).setTitle("Mapas de Tzinda")
                    .setItems(arrayOf(if (shared) "Centrar ubicación compartida" else "Usar mi ubicación",
                        "Ver ejemplo de mapas", "Cartografía y rutas")) { _, selectedAction ->
                        when (selectedAction) {
                            0 -> recenter()
                            1 -> {
                                val leave: () -> Unit = { example = false; leaveExample = null; reload(selectedId) }
                                leaveExample = leave
                                example = true
                                zones(read, save, delete, back = leave, readFix = readFix, shared = shared)
                            }
                            2 -> showAttribution()
                        }
                    }.show()
            },
            useLocation = if (example || shared) null else recenter)
        if (!example && isShowing()) {
            usesDeviceLocation = !shared
            refreshZoneLocation = ::showLocation
            refreshZonePosition()
        }
    }

    private fun positionInZone(fix: MapFix, zone: MapZone): Boolean? {
        val accuracy = fix.accuracyM ?: return null
        val distance = GeoFencePolicy.distanceMeters(fix.point.latitude, fix.point.longitude, zone.point.latitude, zone.point.longitude)
        return GeoFencePolicy.classify(distance, zone.radius.toDouble(), accuracy, null)
    }

    private fun withFix(complete: (MapFix) -> Unit) {
        val version = pageVersion
        requestFix { fix ->
            if (!isShowing() || pageVersion != version || !example && !authorized()) return@requestFix
            if (fix == null) toast("Activa la ubicación o busca una dirección.") else complete(fix)
        }
    }

    private fun editZone(
        zone: MapZone,
        zones: List<MapZone>,
        onSave: (MapZone) -> Boolean,
        back: () -> Unit,
    ) {
        var selected = zone
        lateinit var mapView: TzindaMapView
        val name = form(zone.name, "Nombre del lugar", "Ej. Casa")
        fun changePoint(point: MapPoint) {
            selected = selected.copy(point = point)
            mapView.perimeter(selected)
        }
        mapView = map(if (example) ExampleMap.ZONES else null, ::changePoint)
        mapView.display(zone.point, deviceLocation.latest()?.let { listOf(MapMarker(it.point, "Tú", TzindaMapView.BLUE)) }.orEmpty(), zone)
        mapView.exampleRadius = zone.radius
        val low = minOf(100, zone.radius)
        val high = maxOf(10_000, zone.radius)
        fun labeledRadius(meters: Int): CharSequence {
            val label = formatMapDistance(meters.toDouble())
            val unit = label.lastIndexOf(' ') + 1
            return android.text.SpannableString(label).apply {
                setSpan(android.text.style.RelativeSizeSpan(.62f), unit, length, 0)
                setSpan(android.text.style.ForegroundColorSpan(theme.muted), unit, length, 0)
            }
        }
        val radiusText = v5.title("", 23, theme.accent).apply { text = labeledRadius(zone.radius) }
        lateinit var slider: SeekBar
        fun changeRadius(radius: Int) {
            selected = selected.copy(radius = radius)
            radiusText.text = labeledRadius(radius)
            mapView.exampleRadius = radius
            mapView.perimeter(selected)
        }
        val panel = sheet(322) { body ->
            body.addView(v5.title("Tu perímetro de confianza", 21))
            v5.gap(body, 13)
            body.addView(caption("Nombre del lugar", 13))
            v5.gap(body, 10)
            body.addView(formBox(name, R.drawable.v5_home), LinearLayout.LayoutParams(-1, -2))
            v5.gap(body, 18)
            val radius = v5.horizontal().apply { setPadding(dp(16), 0, dp(15), 0) }
            radius.addView(v5.text("Radio de seguridad", 14, bold = true), rowParams(8))
            radius.addView(radiusText, LinearLayout.LayoutParams(dp(118), -2))
            v5.activate(radiusText) { numberInput(selected.radius) { meters ->
                changeRadius(meters)
                slider.max = maxOf(high, meters) - minOf(low, meters)
                slider.tag = minOf(low, meters)
                slider.progress = meters - (slider.tag as Int)
            } }
            body.addView(radius)
            slider = SeekBar(activity).apply {
                tag = low
                max = high - low
                progress = zone.radius - low
                progressTintList = ColorStateList.valueOf(theme.primaryFill)
                progressBackgroundTintList = ColorStateList.valueOf(theme.outline)
                thumb = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(theme.cardColor)
                    setStroke(dp(3), theme.primaryFill)
                    setSize(dp(22), dp(22))
                }
                thumbTintList = null
                contentDescription = "Radio de seguridad"
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                        if (fromUser) changeRadius((bar.tag as Int) + progress)
                    }
                    override fun onStartTrackingTouch(bar: SeekBar) = Unit
                    override fun onStopTrackingTouch(bar: SeekBar) = Unit
                })
            }
            body.addView(slider, LinearLayout.LayoutParams(-1, dp(38)).apply { topMargin = dp(6) })
            val scales = v5.horizontal().apply { setPadding(dp(17), 0, dp(17), 0) }
            scales.addView(caption(formatMapDistance(low.toDouble()), 11))
            v5.push(scales)
            scales.addView(caption(formatMapDistance(high.toDouble()), 11))
            body.addView(scales)
            v5.gap(body, 9)
            v5.action(body, "Guardar zona segura", R.drawable.v5_check, margin = 0) {
                val title = name.text.toString().trim()
                if (title.isBlank()) { name.error = "Escribe un nombre para la zona."; name.requestFocus() }
                else onSave(selected.copy(name = title.take(100)))
            }
        }
        mapPage(mapView, if (zone.id.isNotBlank()) "Editar zona segura" else "Configurar zona segura",
            "TZINDA · Ajusta el radio en el mapa", panel, back,
            "Buscar dirección o lugar", { search(zones, { place -> editZone(selected.copy(point = place.point, name = name.text.toString().ifBlank { place.name }), zones, onSave, back) },
                { editZone(selected.copy(name = name.text.toString()), zones, onSave, back) }) },
            controlsBottom = 56, menu = {
                val values = booleanArrayOf(selected.enabled, selected.shared)
                AlertDialog.Builder(activity).setTitle("Opciones de zona")
                    .setMultiChoiceItems(arrayOf("Seguimiento activo", "Compartir con el cuidador"), values) { _, index, checked -> values[index] = checked }
                    .setNegativeButton("Cancelar", null).setPositiveButton("Aplicar") { _, _ ->
                        selected = selected.copy(enabled = values[0], shared = values[1])
                    }.show()
            })
    }

    private fun numberInput(current: Int, result: (Int) -> Unit) {
        val input = form(current.toString(), "Radio en metros", "Entre 25 y 20 000").apply { inputType = InputType.TYPE_CLASS_NUMBER }
        val box = AlertDialog.Builder(activity).setTitle("Radio en metros")
            .setView(input).setNegativeButton("Cancelar", null).setPositiveButton("Aplicar", null).create()
        box.setOnShowListener {
            box.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text.toString().toIntOrNull()
                if (value == null || value !in 25..20_000) input.error = "Usa un radio entre 25 y 20 000 metros."
                else { result(value); box.dismiss() }
            }
        }
        box.show()
    }

    private fun form(value: String, label: String, hint: String) = EditText(activity).apply {
        setSingleLine()
        setText(value)
        this.hint = hint
        contentDescription = label
        setTextColor(theme.ink)
        setHintTextColor(theme.muted)
        textSize = theme.textScale(14f)
        typeface = theme.regularTypeface()
        background = null
        filters = arrayOf(InputFilter.LengthFilter(160))
        setPadding(dp(16), dp(10), dp(10), dp(10))
        minimumHeight = theme.controlScale(52)
    }

    private fun formBox(field: EditText, icon: Int, action: (() -> Unit)? = null) = v5.horizontal().apply {
        background = theme.rounded(theme.cardColor, 17).apply { setStroke(dp(1), theme.outline) }
        addView(field, rowParams())
        if (action == null) {
            addView(v5.icon(icon, 25, theme.accent), LinearLayout.LayoutParams(dp(25), dp(25)).apply { marginEnd = dp(15) })
        } else addView(v5.iconButton(icon, "Buscar", Color.TRANSPARENT, glyphSize = 25, action = action))
    }

    private fun infoLine(parent: LinearLayout, text: String, icon: Int, color: Int = theme.muted, bold: Boolean = false, size: Int = if (bold) 14 else 12): TextView {
        val caption = v5.text(text, size, if (bold) theme.ink else color, bold)
        parent.addView(v5.horizontal().apply {
            addView(v5.icon(icon, 22, color))
            addView(caption, rowParams().apply { marginStart = dp(9) })
        })
        return caption
    }

    private fun pageBody(): LinearLayout = v5.column().apply {
        setBackgroundColor(theme.paper)
        setPadding(dp(20), dp(29), dp(20), dp(10))
        isFocusableInTouchMode = true
    }

    private fun scroll(body: LinearLayout) = ScrollView(activity).apply {
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        addView(body)
    }

    private fun locationEyebrow(body: LinearLayout, back: () -> Unit) {
        val row = v5.horizontal()
        row.addView(v5.iconButton(R.drawable.v5_back, "Volver", glyphSize = 30, action = back),
            LinearLayout.LayoutParams(dp(44), dp(44)))
        row.addView(v5.label("TZINDA / UBICACIÓN", theme.accent), rowParams().apply { marginStart = dp(17) })
        if (example) row.addView(tag("Ejemplo").apply { minimumWidth = dp(80) })
        body.addView(row)
        v5.gap(body, 15)
    }

    private fun search(zones: List<MapZone>, select: (MapPlace) -> Unit, back: () -> Unit) {
        val body = pageBody()
        locationEyebrow(body, back)
        body.addView(v5.title("Encuentra tu lugar", 31))
        v5.gap(body, 7)
        body.addView(caption("Escribe una dirección o el nombre del sitio.", 13))
        v5.gap(body, 27)
        body.addView(caption("Buscar lugar", 13).apply { setPadding(dp(4), 0, 0, 0) })
        v5.gap(body, 10)
        val field = form("", "Buscar lugar", "Buscar nombre o dirección")
        field.imeOptions = EditorInfo.IME_ACTION_SEARCH
        val results = v5.column()
        val suggestions = zones.map { MapPlace(it.name,
            if (example) when (it.id) { "home" -> "Av. Central · Zona centro"; "health" -> "Calle del Parque · Zona centro"; else -> "Av. del Bosque · Zona centro" }
            else "Zona segura guardada", it.point) }
        fun options(places: List<MapPlace>, empty: String = "") {
            results.removeAllViews()
            if (places.isEmpty() && empty.isNotBlank()) results.addView(caption(empty, 13))
            places.forEachIndexed { index, place ->
                val card = v5.panel(results, radius = 23, padding = 0, margin = 13)
                val icon = if (example && index == 0) R.drawable.v5_home else if (example && index == 1) R.drawable.v11_health else if (example) R.drawable.v11_park else R.drawable.v5_pin
                v5.row(card, place.name, place.detail, icon, minHeight = 67, emphasis = true, titleSize = 15, detailSize = 12) { select(place) }
            }
        }
        fun submit() {
            val query = field.text.toString().trim()
            if (query.isBlank()) { field.error = "Escribe una dirección o coordenadas."; return }
            (activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(field.windowToken, 0)
            val found = suggestions.filter { it.name.contains(query, true) || it.detail.contains(query, true) }
            if (example) { options(found, "Sin coincidencias en los lugares de ejemplo."); return }
            options(found, "Buscando…")
            job?.cancel()
            job = work.launch {
                val matches = try { tiles.search(query) }
                    catch (error: CancellationException) { throw error }
                    catch (_: Exception) { emptyList() }
                if (isShowing() && authorized()) options((found + matches).distinctBy(MapPlace::point),
                    "Sin resultados. Puedes probar otra dirección o introducir latitud, longitud.")
            }
        }
        field.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEARCH) { submit(); true } else false
        }
        body.addView(formBox(field, R.drawable.v11_search, ::submit))
        v5.gap(body, 25)
        v5.row(v5.panel(body, radius = 22, padding = 0, margin = 27), "Usar mi ubicación",
            if (example) "Punto actual del ejemplo" else "Ubicación de este teléfono", R.drawable.v11_target,
            minHeight = 67, emphasis = true, titleSize = 15, detailSize = 12) {
            if (example) select(MapPlace("Casa", "Punto actual del ejemplo", exampleZone.point))
            else withFix { select(MapPlace("Mi ubicación", it.point.coordinates(), it.point)) }
        }
        v5.section(body, "Lugares sugeridos", titleSize = 19)
        v5.gap(body, 12)
        body.addView(results)
        options(suggestions, "Tus zonas seguras aparecerán aquí.")
        show(scroll(body), back)
        body.requestFocus()
    }

    private fun samplePerson() = MapPerson("Elena M.", MapFix(DEFAULT_ZONE.point, System.currentTimeMillis() - 60_000, 25.0), exampleZone)

    private fun sampleAlerts() = listOf(
        LocationAlert(LocationAlertType.HELP, "Elena necesita ayuda", "Hace 1 min · Ubicación disponible"),
        LocationAlert(LocationAlertType.EXIT, "Elena salió de Casa", "Hoy · 09:36 · Ubicación hace 1 min", exampleZone),
        LocationAlert(LocationAlertType.ARRIVAL, "Llegó al centro de salud", "Ayer · 13:10 · Zona segura", SAMPLE_HEALTH),
    )

    private fun exampleMenu(back: () -> Unit) {
        fun showMissing() {
            unavailable(samplePerson().copy(fix = null), ::showMissing, back)
        }
        AlertDialog.Builder(activity).setTitle("Mapas de ejemplo")
            .setItems(arrayOf("Zonas seguras", "Ubicación de Elena", "Alertas compartidas", "Ubicación no disponible", "Salir del ejemplo")) { _, index ->
                when (index) {
                    0 -> zones({ emptyList() }, null, null, back)
                    1 -> person(::samplePerson, {}, { zones({ emptyList() }, null, null, back) }, back)
                    2 -> exampleAlerts(back)
                    3 -> showMissing()
                    4 -> leaveExample?.invoke()
                }
            }.show()
    }

    private fun exampleAlerts(back: () -> Unit) {
        val body = pageBody()
        alertFeed(body, samplePerson(), sampleAlerts(), back) { alert -> incident(::samplePerson, alert, {}, { exampleAlerts(back) }) }
        val full = v5.column().apply { setBackgroundColor(theme.paper) }
        full.addView(scroll(body), LinearLayout.LayoutParams(-1, 0, 1f))
        val nav = v5.horizontal().apply {
            background = theme.rounded(theme.cardColor, 35).apply { setStroke(dp(1), theme.outline) }
            setPadding(dp(5), dp(4), dp(5), dp(4))
        }
        TzindaNavigationPolicy.items(TzindaRole.CAREGIVER).forEachIndexed { index, item ->
            val selected = index == 1
            val color = if (selected) Color.WHITE else theme.muted
            val tab = v5.column().apply {
                gravity = Gravity.CENTER
                minimumHeight = dp(55)
                background = theme.rounded(if (selected) theme.primaryFill else theme.cardColor, 99)
                addView(v5.icon(item.iconRes, 23, color))
                addView(v5.text(item.label, 11, color, selected))
                isSelected = selected
                contentDescription = item.label
                v5.activate(this) { if (index == 3) person(::samplePerson, {}, { zones({ emptyList() }, null, null, back) }, { exampleAlerts(back) })
                    else if (index == 0) back() else if (index == 2) exampleMenu(back) }
            }
            nav.addView(tab, rowParams())
        }
        full.addView(nav, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(12), 0, dp(12), dp(1)) })
        show(full, back)
    }

    fun alertFeed(
        body: LinearLayout,
        person: MapPerson,
        alerts: List<LocationAlert>,
        back: () -> Unit,
        select: (LocationAlert) -> Unit,
    ) {
        locationEyebrow(body, back)
        body.addView(v5.title("Alertas compartidas", 31))
        v5.gap(body, 7)
        body.addView(caption("La información necesaria para acompañar.", 13))
        v5.gap(body, 26)
        val intro = v5.panel(body, theme.primaryFill, radius = 23, padding = 0, margin = 24)
        intro.addView(v5.horizontal().apply {
            minimumHeight = dp(75)
            setPadding(dp(15), dp(9), dp(12), dp(9))
            addView(v5.icon(R.drawable.v5_bell, 33, Color.WHITE))
            addView(v5.column().apply {
                addView(v5.text("Tu círculo te mantiene al tanto.", 16, Color.WHITE, true))
                v5.gap(this, 8)
                addView(v5.text("${person.name} · Vínculo activo", 12, v5.onBlueMuted))
            }, rowParams().apply { marginStart = dp(13) })
        })
        v5.section(body, "Requieren tu atención", titleSize = 19)
        v5.gap(body, 9)
        alerts.forEach { alert ->
            if (alert.type == LocationAlertType.ARRIVAL) {
                v5.row(v5.panel(body, radius = 22, padding = 0, margin = 8), alert.title, alert.detail,
                    R.drawable.v5_check, theme.mint, theme.positive, minHeight = 66, emphasis = true, detailSize = 12) { select(alert) }
            } else {
                val help = alert.type == LocationAlertType.HELP
                val color = if (help) theme.onCriticalSurface else theme.warning
                val box = FrameLayout(activity)
                box.addView(View(activity).apply { background = theme.rounded(color, 4) }, FrameLayout.LayoutParams(dp(5), -1))
                box.addView(v5.column().apply {
                    background = theme.rounded(theme.cardColor, 25)
                    minimumHeight = dp(168)
                    setPadding(dp(12), dp(15), dp(15), dp(11))
                    addView(tag(if (help) "Solicitud de ayuda" else "Salida de zona segura",
                        if (help) theme.criticalSurface else theme.warningSurface, color),
                        LinearLayout.LayoutParams(-2, -2))
                    v5.gap(this, 10)
                    addView(v5.title(alert.title, 21))
                    v5.gap(this, 8)
                    addView(caption(alert.detail, 12))
                    v5.gap(this, 9)
                    val row = v5.horizontal().apply {
                        minimumHeight = dp(43)
                        val tint = if (help) color else theme.accent
                        addView(v5.icon(R.drawable.v5_pin, 22, tint))
                        addView(v5.text(if (help) "Ver ubicación y cómo llegar" else "Consultar ubicación", 13, tint, true),
                            rowParams().apply { marginStart = dp(10) })
                        contentDescription = if (help) "Ver ubicación y cómo llegar" else "Consultar ubicación"
                        v5.activate(this) { select(alert) }
                    }
                    addView(row)
                }, FrameLayout.LayoutParams(-1, -2).apply { marginStart = dp(5) })
                body.addView(box, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(if (help) 17 else 5) })
            }
        }
    }

    fun person(read: () -> MapPerson, retry: (() -> Unit) -> Unit, zones: () -> Unit, back: () -> Unit = ::close) {
        val person = read()
        val fix = person.fix?.takeIf { it.isRecent() }
        if (fix == null) { unavailable(person, retryLocation(retry) { person(read, retry, zones, back) }, back); return }
        val zone = person.zone
        val view = map(if (example) ExampleMap.SUPERVISION else null)
        view.display(zone?.point ?: fix.point, listOf(MapMarker(fix.point, person.firstName, TzindaMapView.BLUE)), zone)
        val panel = sheet(258) { body ->
            val identity = v5.horizontal()
            val initials = person.name.split(' ').take(2).mapNotNull { it.firstOrNull()?.uppercaseChar() }.joinToString("")
            identity.addView(tag(initials).apply {
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 0)
            }, LinearLayout.LayoutParams(dp(44), dp(44)))
            identity.addView(v5.column().apply {
                addView(v5.title(person.name, 22))
                v5.gap(this, 5)
                addView(v5.text("Ubicación compartida con permiso", 11, theme.positive))
            }, rowParams().apply { marginStart = dp(16) })
            body.addView(identity)
            v5.gap(body, 20)
            infoLine(body, if (example) "San Ángel · Ciudad de México" else fix.point.coordinates(), R.drawable.v5_pin, theme.accent, true)
            v5.gap(body, 8)
            body.addView(caption(fix.freshness() + if (example) " · Ejemplo" else "", 11))
            v5.gap(body, 14)
            v5.action(body, "Cómo llegar a ${person.firstName}", R.drawable.v11_route, height = 48, margin = 10) {
                startRoute(person, fix) { person(read, retry, zones, back) }
            }
            v5.action(body, "Ver zonas compartidas", R.drawable.v5_shield, theme.cardColor, theme.accent, height = 48, margin = 0, action = zones)
        }
        val inZone = zone?.takeIf { it.enabled }?.let { positionInZone(fix, it) } == true
        mapPage(view, "Ubicación de ${person.firstName}", "TZINDA · Supervisión autorizada", panel, back,
            status = if (inZone || example) tag("Dentro de ${zone?.name ?: "Casa"}", theme.mint, theme.positive) else null,
            menu = if (example) ({ exampleMenu(back) }) else (::showAttribution))
        watchPosition(fix) { person(read, retry, zones, back) }
    }

    fun incident(read: () -> MapPerson, alert: LocationAlert, retry: (() -> Unit) -> Unit, back: () -> Unit = ::close) {
        val person = read()
        val fix = person.fix?.takeIf { it.isRecent() }
        if (fix == null) { unavailable(person, retryLocation(retry) { incident(read, alert, retry, back) }, back); return }
        val help = alert.type == LocationAlertType.HELP
        val arrived = alert.type == LocationAlertType.ARRIVAL
        val header = if (help) "Solicitud de ayuda" else if (arrived) "Entrada a zona" else "Salida de zona"
        val view = map(if (example) if (help) ExampleMap.HELP else ExampleMap.EXIT else null)
        val zone = if (help) null else alert.zone
        view.display(zone?.point ?: fix.point, listOf(MapMarker(fix.point, person.firstName,
            if (arrived) theme.positive else TzindaMapView.RED)), zone)
        val panel = sheet(263) { body ->
            body.addView(v5.title(alert.title, 24))
            v5.gap(body, 8)
            body.addView(caption(when {
                help && example -> "Ubicación recibida con la alerta."
                example -> "Consulta su posición antes de dirigirte."
                else -> "Ubicación compartida más reciente."
            }, 13))
            v5.gap(body, 15)
            val place = if (!example) fix.point.coordinates() else if (help) "San Ángel · Cerca de la Bombilla" else "Al norte de Casa · Ciudad de México"
            infoLine(body, place, R.drawable.v5_pin, if (help) theme.onCriticalSurface else theme.accent, true)
            v5.gap(body, 7)
            body.addView(caption(fix.freshness() + if (example) " · Ejemplo" else "", 11))
            v5.gap(body, 14)
            v5.action(body, "Cómo llegar", R.drawable.v11_route, height = 48, margin = 10) { startRoute(person, fix) { incident(read, alert, retry, back) } }
            v5.action(body, "Llamar a ${person.firstName}", R.drawable.v5_phone, theme.cardColor, theme.accent, height = 48, margin = 0) { contact() }
        }
        val status = if (help) tag(alert.title, theme.criticalSurface, theme.onCriticalSurface)
            else tag(when {
                arrived -> "Entrada a zona segura"
                example -> "Fuera del perímetro de ${zone?.name ?: "la zona"}"
                else -> "Aviso de salida de zona"
            },
                if (arrived) theme.mint else theme.warningSurface, if (arrived) theme.positive else theme.warning)
        mapPage(view, header, "TZINDA · ${person.name}", panel, back, status = status,
            menu = if (example) ({ exampleMenu(back) }) else (::showAttribution))
        watchPosition(fix) { incident(read, alert, retry, back) }
    }

    private fun watchPosition(fix: MapFix, replace: () -> Unit) {
        positionStillAvailable = { fix.isRecent() && (sharedFix ?: deviceLocation::latest).invoke() != null }
        positionUnavailable = replace
    }

    private fun retryLocation(retry: (() -> Unit) -> Unit, refreshed: () -> Unit): () -> Unit = {
        val version = pageVersion
        retry { if (isShowing() && pageVersion == version && (example || authorized())) refreshed() }
    }

    private fun unavailable(person: MapPerson, retry: () -> Unit, back: () -> Unit) {
        val panel = sheet(265) { body ->
            infoLine(body, "Sin una posición reciente", R.drawable.v5_pin, theme.muted, true, 21)
            v5.gap(body, 20)
            body.addView(caption("${person.firstName.replaceFirstChar(Char::uppercase)} necesita autorizar la ubicación\ny tener conexión para actualizarla.", 14))
            v5.gap(body, 15)
            body.addView(caption("No se muestra una ubicación antigua como actual.", 12))
            v5.gap(body, 9)
            v5.action(body, "Reintentar", R.drawable.v5_sync, height = 48, margin = 10, action = retry)
            v5.action(body, "Contactar a ${person.firstName}", R.drawable.v5_phone, theme.cardColor, theme.accent, height = 48, margin = 0) { contact() }
        }
        mapPage(map(ExampleMap.UNAVAILABLE), "Ubicación no disponible", "TZINDA · Supervisión", panel, back, controls = false,
            menu = if (example) ({ exampleMenu(back) }) else (::showAttribution))
    }

    private fun contact() {
        if (example) { toast("Contacto de demostración."); return }
        go(Uri.parse("tel:"), Intent.ACTION_DIAL)
    }

    private fun startRoute(person: MapPerson, fix: MapFix, back: () -> Unit) {
        if (!fix.isRecent() || !example && !authorized()) { unavailable(person, back, back); return }
        if (example) {
            routePicker(person, fix.point, ROUTE_ORIGIN, MapTravelMode.CAR, 0, back)
            return
        }
        AlertDialog.Builder(activity).setTitle("Calcular ruta")
            .setMessage("Para calcular el recorrido, el servicio de rutas de OpenStreetMap recibirá la ubicación de origen y el destino.")
            .setNegativeButton("Cancelar", null)
            .setNeutralButton("Google Maps") { _, _ -> openGoogleMaps(fix, MapTravelMode.CAR) }
            .setPositiveButton("Calcular") { _, _ ->
                withFix { origin -> if (fix.isRecent()) routePicker(person, fix.point, origin.point, MapTravelMode.CAR, 0, back)
                    else unavailable(person, back, back) }
            }.show()
    }

    private fun openGoogleMaps(fix: MapFix?, mode: MapTravelMode) {
        if (!example && !authorized()) {
            close()
            return
        }
        val destination = (if (!example && sharedFix != null) sharedFix.invoke() else fix)
            ?.takeIf { example || it.isRecent() }
        if (destination == null) {
            checkAccess()
            toast("La ubicación compartida dejó de estar disponible.")
            return
        }
        val uri = Uri.parse(googleMapsDirectionsUrl(destination.point, mode))
        val native = Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.apps.maps")
        try {
            activity.startActivity(native)
        } catch (_: ActivityNotFoundException) {
            go(uri)
        }
    }

    private fun suggestedRoutes(mode: MapTravelMode): List<MapRoute> {
        val minutes = if (mode == MapTravelMode.CAR) 9 else 36
        val instructions = listOf("Toma Av. San Jerónimo hacia el norte", "Continúa por Avenida Revolución", "Gira a la derecha hacia el punto de encuentro")
        return listOf(MapRoute(4400.0, minutes * 60.0, emptyList(), instructions),
            MapRoute(4800.0, (minutes + 3) * 60.0, emptyList(), instructions))
    }

    private fun routePicker(
        person: MapPerson, destination: MapPoint, origin: MapPoint, mode: MapTravelMode, selected: Int,
        back: () -> Unit, loaded: List<MapRoute>? = null,
    ) {
        val routes = if (example) suggestedRoutes(mode) else loaded
        val current = routes?.getOrNull(selected)
        val view = map(if (example) ExampleMap.ROUTE else null)
        view.showAlternative(selected == 1 || mode == MapTravelMode.WALK)
        drawRoute(view, person, destination, origin, current)
        val panel = sheet(271) { body ->
            body.addView(v5.title("Elige tu ruta", 22))
            v5.gap(body, 5)
            body.addView(caption(if (example) "Ruta de ejemplo · Sin tráfico en vivo" else "Ruta calculada · Sin tráfico en vivo", 12))
            v5.gap(body, 15)
            val modes = v5.horizontal()
            MapTravelMode.entries.forEach { option ->
                val active = option == mode
                val item = chipButton(option.label, if (option == MapTravelMode.CAR) R.drawable.v11_car else R.drawable.v11_walk, active) {
                    if (mode != option) routePicker(person, destination, origin, option, 0, back)
                }
                item.isSelected = active
                modes.addView(item, rowParams(if (option == MapTravelMode.CAR) 10 else 0))
            }
            body.addView(modes)
            v5.gap(body, 16)
            if (routes == null) body.addView(caption("Calculando rutas…", 14))
            else if (routes.isEmpty()) {
                body.addView(caption("No se pudo obtener una ruta. Comprueba tu conexión.", 13))
                v5.gap(body, 6)
                v5.action(body, "Reintentar", height = 43) { routePicker(person, destination, origin, mode, 0, back) }
                v5.action(body, "Abrir en Google Maps", fill = theme.cardColor, fg = theme.accent, height = 43) {
                    openGoogleMaps(person.fix, mode)
                }
            } else {
                val alternatives = v5.horizontal()
                repeat(2) { index ->
                    val route = routes.getOrNull(index)
                    val active = selected == index
                    val card = v5.column().apply {
                        setPadding(dp(12), dp(9), dp(9), dp(9))
                        minimumHeight = dp(59)
                        background = theme.rounded(if (active) theme.brandSoft else theme.paper, 18).apply {
                            if (active) setStroke(dp(1), theme.accent)
                        }
                        val title = if (route != null && (index == 0 || active)) "${route.timeLabel()} · ${route.distanceLabel()}" else "Otra ruta"
                        addView(v5.text(title, 17, if (active) theme.accent else theme.muted, true))
                        v5.gap(this, 5)
                        addView(caption(if (index == 0) "Ruta sugerida" else if (route == null || example && !active) "Según disponibilidad" else route.timeLabel(), 11))
                        isSelected = active
                        if (route != null) v5.activate(this) { routePicker(person, destination, origin, mode, index, back, routes) }
                    }
                    alternatives.addView(card, rowParams(if (index == 0) 11 else 0))
                }
                body.addView(alternatives)
                v5.gap(body, 13)
                v5.action(body, "Iniciar indicaciones", R.drawable.v11_route, margin = 0) {
                    current?.let { guidance(person, destination, origin, it, mode, selected == 1, back) {
                        routePicker(person, destination, origin, mode, selected, back, routes)
                    } }
                }
            }
        }
        mapPage(view, "Llegar a ${person.firstName}", "TZINDA · Última ubicación compartida", panel, back,
            controlsBottom = 66, menu = ::showAttribution)
        person.fix?.let { watchPosition(it, back) }
        if (routes == null) job = work.launch {
            val result = try { tiles.routes(origin, destination, mode) }
                catch (error: CancellationException) { throw error }
                catch (_: Exception) { emptyList() }
            if (isShowing() && authorized()) routePicker(person, destination, origin, mode, 0, back, result)
        }
    }

    private fun chipButton(label: String, icon: Int, selected: Boolean, action: () -> Unit) = v5.horizontal().apply {
        minimumHeight = dp(36)
        gravity = Gravity.CENTER
        val tint = if (selected) theme.accent else theme.muted
        background = theme.rounded(if (selected) theme.brandSoft else theme.cardColor, 13)
        addView(v5.icon(icon, 23, tint))
        addView(v5.text(label, 13, tint, selected), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) })
        contentDescription = label
        v5.activate(this, action)
    }

    private fun drawRoute(view: TzindaMapView, person: MapPerson, destination: MapPoint, origin: MapPoint, route: MapRoute?) {
        val delta = MapProjection.wrappedDelta(MapProjection.x(destination), MapProjection.x(origin))
        val center = MapProjection.unproject(MapProjection.x(origin) + delta / 2,
            (MapProjection.y(origin) + MapProjection.y(destination)) / 2)
        view.display(center, listOf(MapMarker(destination, person.firstName, TzindaMapView.RED),
            MapMarker(origin, "Tú", TzindaMapView.BLUE)), route = route?.path.orEmpty())
    }

    private fun guidance(person: MapPerson, destination: MapPoint, origin: MapPoint, route: MapRoute, mode: MapTravelMode, alternative: Boolean,
        finishRoute: () -> Unit, back: () -> Unit,
    ) {
        val view = map(if (example) ExampleMap.ROUTE else null)
        view.showAlternative(alternative || mode == MapTravelMode.WALK)
        drawRoute(view, person, destination, origin, route)
        val header = v5.horizontal().apply {
            background = theme.rounded(theme.primaryFill, 24)
            minimumHeight = dp(92)
            setPadding(dp(16), dp(10), dp(14), dp(10))
            addView(v5.icon(R.drawable.v11_turn, 40, Color.WHITE))
            addView(v5.column().apply {
                addView(v5.text("Sigue la ruta indicada", 13, v5.onBlueMuted, true))
                v5.gap(this, 6)
                addView(v5.title("Avanza hacia ${person.firstName}", 20, Color.WHITE))
                v5.gap(this, 5)
                addView(v5.text(if (example) "Recorrido de ejemplo" else "Recorrido calculado", 11, v5.onBlueMuted))
            }, rowParams().apply { marginStart = dp(16) })
        }
        val panel = sheet(201) { body ->
            val top = v5.horizontal()
            top.addView(v5.title(route.timeLabel(), 31), rowParams(7))
            top.addView(tag("A ${person.firstName}").apply { minimumWidth = dp(96) })
            body.addView(top)
            v5.gap(body, 8)
            body.addView(caption("${route.distanceLabel()} · ${if (alternative) "Ruta alternativa" else "Ruta sugerida"}", 13))
            v5.gap(body, 12)
            val details = v5.horizontal()
            details.addView(caption(if (example) "Posición de ejemplo · Sin GPS ni tráfico en vivo" else "Ruta calculada · Sin seguimiento en vivo", 11),
                rowParams(8))
            details.addView(v5.text("Ver pasos", 12, theme.accent, true).apply {
                minHeight = dp(44)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), 0, dp(8), 0)
                v5.activate(this) {
                    val steps = route.instructions.ifEmpty { listOf("Sigue el recorrido trazado en el mapa.") }
                    AlertDialog.Builder(activity).setTitle("Indicaciones")
                        .setItems(steps.mapIndexed { i, step -> "${i + 1}. $step" }.toTypedArray(), null)
                        .setPositiveButton("Cerrar", null).show()
                }
            })
            body.addView(details)
            v5.gap(body, 4)
            val actions = v5.horizontal()
            val available = v5.column()
            v5.action(available, "Ver indicaciones", R.drawable.v5_journal, margin = 0) {
                openGoogleMaps(person.fix, mode)
            }
            val finish = v5.column()
            v5.action(finish, "Terminar", fill = theme.cardColor, fg = theme.accent, margin = 0, action = finishRoute)
            actions.addView(available, LinearLayout.LayoutParams(0, -2, 1.8f).apply { marginEnd = dp(9) })
            actions.addView(finish, rowParams())
            body.addView(actions)
        }
        mapPage(view, "", "", panel, back, controlsBottom = 90, customHeader = header)
        person.fix?.let { watchPosition(it, finishRoute) }
    }

    companion object {
        private val DEFAULT_ZONE = MapZone("Casa", MapPoint(19.346, -99.192), 5000, id = "home")
        private val SAMPLE_HEALTH = MapZone("Centro de salud", MapPoint(19.355, -99.183), 700, id = "health")
        private val SAMPLE_PARK = MapZone("Parque del centro", MapPoint(19.316, -99.202), 800, id = "park")
        private val ROUTE_ORIGIN = MapPoint(19.334, -99.193)
    }
}
