package com.example.guardian_iot

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Space
import android.widget.Switch
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat

/**
 * Sistema de diseño unificado e identidad visual para TzindaApp (Estilo Premium Apple HIG).
 *
 * Reglas de aplicación estricta:
 * - NO se usan tarjetas con fondos de color verde, rojo o amarillo saturado.
 * - En Modo Claro: Fondo #F8FAFC, tarjetas en blanco puro #FFFFFF con borde sutil #E2E8F0.
 * - En Modo Oscuro: Fondo casi negro #07090D y tarjetas carbón #15181F con borde blanco al 9%;
 *   el azul queda reservado para acentos, nunca para teñir toda la superficie.
 * - Badges y estados: Se presentan como pequeñas píldoras/chips compactas traslúcidas.
 */
class TzindaDesign(private val activity: Activity, val dark: Boolean, val simple: Boolean) {

    enum class Tone { SUCCESS, INFO, WARNING, DANGER, NEUTRAL, BRAND }

    // Paleta oficial del rediseño Apple HIG (design-reference/tzindaapp/DESIGN.md)
    val imperialRed = color("#FF1A23")
    val coolWhite = color("#F8FAFC")
    val selectiveYellow = color("#9A6200")
    val forestGreen = color("#1E5E3A")
    val persianBlue = color("#0E48D6")
    val royalBlue = color("#0A1128")
    val deepBlue = color("#0A1128")
    val accentGreen = color("#008744")
    val warningGold = color("#FFB800")

    // Tokens semánticos neutros y de alto contraste
    // El fondo de página es gris frío; las tarjetas son la única superficie elevada.
    val paper = color(if (dark) "#07090D" else "#F8FAFC")
    val cardColor = color(if (dark) "#15181F" else "#FFFFFF")
    val surfaceSunken = color(if (dark) "#0C0E13" else "#F1F5F9")
    val softSurface = color(if (dark) "#1F232B" else "#F1F5F9")
    // Los colores de estado solo aparecen en iconos, chips, líneas y acciones.
    val brandSoft = color(if (dark) "#1A2434" else "#E8EFFD")
    val mint = color(if (dark) "#12241B" else "#E6F4EC")
    val mintStrong = forestGreen
    // En oscuro el verde de texto necesita un tono más claro para conservar contraste.
    val successText = if (dark) color("#5BD38D") else forestGreen
    val positive = successText
    val ink = color(if (dark) "#F8FAFC" else "#0F172A")
    val muted = color(if (dark) "#94A3B8" else "#64748B")
    val metaGray = if (dark) color("#94A3B8") else color("#64748B")
    val accent = if (dark) color("#8FA9FF") else persianBlue
    val primaryDeep = if (dark) color("#8FA9FF") else persianBlue
    // DESIGN.md mantiene el azul #0E48D6 como relleno de acciones primarias en ambos temas;
    // en oscuro solo el texto/enlace necesita el tono claro para conservar contraste.
    val primaryFill = persianBlue
    val onAccent = color("#FFFFFF")
    // Bordes sutiles: hairline gris en claro, blanco al 9% en oscuro.
    val outline = if (dark) color("#17FFFFFF") else color("#E2E8F0")
    val controlOutline = if (dark) color("#3A3F4A") else color("#CBD5E1")
    val divider = outline
    val critical = if (dark) color("#FF6B70") else color("#FF1A23")
    val criticalDeep = imperialRed
    val criticalSurface = color(if (dark) "#2A1416" else "#FDECEC")
    val onCriticalSurface = if (dark) color("#FF8A8F") else color("#B3121A")
    // #FFB800 no alcanza contraste como texto en claro; se usa en fondos e iconos rellenos.
    val warning = if (dark) color("#FFC94D") else selectiveYellow
    val warningSurface = color(if (dark) "#2E2410" else "#FFF7E0")
    val warningBright = warningGold
    val info = accent
    val infoSurface = brandSoft
    val coral = critical
    val coralSoft = criticalSurface
    val coralDeep = criticalDeep
    val pink = accent
    val pinkSoft = brandSoft
    val pinkDeep = deepBlue
    val navSelected = color(if (dark) "#232833" else "#E8EFFD")
    val navInactive = muted
    val scrim = colorWithAlpha(deepBlue, if (dark) .80f else .40f)

    // Apple HIG neutral grotesque: use the platform's system sans (SF-like proportions on iOS,
    // Roboto on Android) instead of the old bundled display face. This keeps large titles wide
    // and calm rather than heavy and condensed.
    private val familyRegular: Typeface by lazy { Typeface.create("sans-serif", Typeface.NORMAL) }
    private val familyBold: Typeface by lazy { Typeface.create("sans-serif", Typeface.BOLD) }

    fun regularTypeface(): Typeface = familyRegular
    fun boldTypeface(): Typeface = familyBold

    fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()
    fun dp(value: Float): Int = (value * activity.resources.displayMetrics.density).toInt()
    fun dimension(value: Int): Int = dp(value)
    /** The accessibility mode follows the requested 1.25x text scale. */
    fun textScale(size: Float): Float = if (simple) size * 1.25f else size
    fun controlScale(value: Int): Int = if (simple) dp((value * 1.12f).toInt()) else dp(value)
    fun space(value: Int): Int = dp(if (simple) (value * 1.12f).toInt() else value)

    fun toneBackground(tone: Tone): Int = when (tone) {
        Tone.SUCCESS -> mint
        Tone.INFO -> infoSurface
        Tone.WARNING -> warningSurface
        Tone.DANGER -> criticalSurface
        Tone.NEUTRAL -> softSurface
        Tone.BRAND -> brandSoft
    }

    fun toneForeground(tone: Tone): Int = when (tone) {
        Tone.SUCCESS -> positive
        Tone.INFO -> info
        Tone.WARNING -> warning
        // Rojo oscuro para texto pequeño sobre chip; el rojo de marca queda para iconos y acciones.
        Tone.DANGER -> onCriticalSurface
        Tone.NEUTRAL -> muted
        Tone.BRAND -> accent
    }

    fun toneIcon(tone: Tone): Int = when (tone) {
        Tone.SUCCESS, Tone.BRAND -> R.drawable.ic_check_circle
        Tone.INFO -> R.drawable.ic_info
        Tone.WARNING -> R.drawable.ic_warning
        Tone.DANGER -> R.drawable.ic_warning
        Tone.NEUTRAL -> R.drawable.ic_info
    }

    fun text(
        value: String,
        size: Int = 16,
        bold: Boolean = false,
        color: Int = ink,
        align: Int = Gravity.START,
        contentDescriptionText: String? = null,
    ): TextView = TextView(activity).apply {
        text = value
        setTextColor(color)
        gravity = align
        includeFontPadding = false
        setLineSpacing(dp(2).toFloat(), 1.08f)
        typeface = if (bold) familyBold else familyRegular
        setTextSize(TypedValue.COMPLEX_UNIT_SP, textScale(size.toFloat()))
        if (contentDescriptionText != null) contentDescription = contentDescriptionText
        setPadding(0, dp(2), 0, dp(2))
        // Hide only intentional product-explainer copy. Long readings, notes and error messages
        // remain visible, because their length alone does not make them secondary.
        if (!bold && color == muted && isProductExplainer(value)) {
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        if (Build.VERSION.SDK_INT >= 28) {
            ViewCompat.setAccessibilityHeading(this, size >= 19)
        }
        isFocusable = false
    }

    fun title(value: String, size: Int = 26, color: Int = ink): TextView =
        text(value, size, true, color).apply {
            // Signature iOS large-title tracking is slightly tighter than body copy.
            letterSpacing = -0.02f
            setLineSpacing(0f, 1.02f)
            setPadding(0, dp(2), 0, dp(5))
        }

    fun body(value: String, color: Int = muted): TextView = text(value, 16, false, color)

    fun label(value: String, color: Int = muted): TextView = text(value, 14, true, color)

    fun eyebrow(value: String, color: Int = metaGray): TextView = text(value.uppercase(), 12, true, color).apply {
        letterSpacing = .08f
        setPadding(0, dp(2), 0, dp(4))
    }

    fun heading(parent: LinearLayout, eyebrowText: String?, titleText: String, subtitle: String, compact: Boolean = false) {
        if (!eyebrowText.isNullOrBlank()) parent.addView(eyebrow(eyebrowText))
        // Título de pantalla HIG: 32sp en el flujo normal y 26sp en contextos compactos.
        parent.addView(title(titleText, if (compact) 26 else 32, ink))
        if (subtitle.isNotBlank()) {
            parent.addView(text(subtitle, 15, false, muted).apply {
                setLineSpacing(dp(1).toFloat(), 1.06f)
                setPadding(0, dp(2), 0, 0)
            })
        }
        parent.setPadding(parent.paddingLeft, 0, parent.paddingRight, space(12))
    }

    fun section(
        parent: LinearLayout,
        titleText: String,
        subtitle: String? = null,
        actionLabel: String? = null,
        action: (() -> Unit)? = null,
    ): LinearLayout {
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, space(18), 0, space(6))
        }
        val labels = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(eyebrow(titleText))
        if (!subtitle.isNullOrBlank()) labels.addView(text(subtitle, 14, false, muted))
        header.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (!actionLabel.isNullOrBlank() && action != null) {
            val actionView = text(actionLabel, 13, true, accent).apply {
                gravity = Gravity.CENTER
                minHeight = dp(48)
                minWidth = dp(48)
                setPadding(dp(10), dp(10), dp(4), dp(10))
                isFocusable = true
                isClickable = true
                background = ripple(0x220E48D6, null, 18)
                setOnClickListener { action() }
                contentDescription = actionLabel
            }
            header.addView(actionView)
        }
        parent.addView(header)
        return header
    }

    /**
     * Card siempre neutra (Blanco #FFFFFF en claro / Azul noche #172033 en oscuro).
     * Los colores saturados nunca se usan como fondo completo de tarjetas.
     */
    fun plainCard(parent: LinearLayout, fill: Int = cardColor, elevated: Boolean = false): LinearLayout {
        // Superficie neutra con hairline; el estado vive en chip, icono o dato, nunca en el fondo.
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(fill, 20).apply { setStroke(dp(1), outline) }
            if (elevated) elevation = dp(1).toFloat()
            setPadding(space(18), space(18), space(18), space(18))
            isFocusable = false
        }
        parent.addView(card, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = space(14)
        })
        return card
    }

    fun card(parent: LinearLayout, fill: Int = cardColor): LinearLayout = plainCard(parent, fill)

    fun tintedCard(parent: LinearLayout, tone: Tone): LinearLayout =
        plainCard(parent, cardColor)

    fun addGap(parent: LinearLayout, height: Int = 8) {
        parent.addView(Space(activity), LinearLayout.LayoutParams(1, space(height)))
    }

    fun divider(parent: LinearLayout) {
        // iOS grouped tables inset the hairline so it never touches the card edges.
        parent.addView(View(activity).apply { setBackgroundColor(divider) },
            LinearLayout.LayoutParams(-1, dp(1)).apply {
                leftMargin = space(12); rightMargin = space(12)
                topMargin = space(8); bottomMargin = space(8)
            })
    }

    fun button(
        labelText: String,
        primary: Boolean = true,
        action: () -> Unit,
        iconRes: Int? = null,
    ): Button = Button(activity).apply {
        text = labelText
        isAllCaps = false
        typeface = familyBold
        setTextSize(TypedValue.COMPLEX_UNIT_SP, textScale(16f))
        minHeight = controlScale(52)
        setMinHeight(controlScale(52))
        stateListAnimator = null
        elevation = 0f
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(10), dp(16), dp(10))
        setTextColor(if (primary) onAccent else ink)
        // Secundario: superficie de campo con hairline, radio 14 según DESIGN.md.
        val fill = when {
            primary -> primaryFill
            dark -> softSurface
            else -> cardColor
        }
        val shape = rounded(fill, 14).apply {
            if (!primary) setStroke(dp(1), controlOutline)
        }
        background = ripple(if (primary) 0x33FFFFFF else 0x220E48D6, shape, 14)
        if (iconRes != null) {
            val icon = ResourcesCompat.getDrawable(activity.resources, iconRes, activity.theme)?.mutate()
            icon?.setTint(if (primary) onAccent else accent)
            icon?.setBounds(0, 0, dp(20), dp(20))
            setCompoundDrawables(icon, null, null, null)
            compoundDrawablePadding = dp(8)
        }
        layoutParams = LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = space(8)
        }
        setOnClickListener { action() }
        contentDescription = labelText
        isFocusable = true
    }

    fun dangerButton(labelText: String, iconRes: Int? = R.drawable.ic_emergency, action: () -> Unit): Button =
        button(labelText, true, action, iconRes).apply {
            background = ripple(0x33FFFFFF, rounded(imperialRed, 14), 14)
            setTextColor(Color.WHITE)
            setOnClickListener { action() }
        }

    fun textAction(labelText: String, action: () -> Unit, iconRes: Int? = null): TextView {
        val view = text(labelText, 15, true, accent).apply {
            minHeight = dp(48)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(10), dp(4), dp(10))
            isClickable = true
            isFocusable = true
            background = ripple(0x1F0E48D6, null, 12)
            setOnClickListener { action() }
            contentDescription = labelText
        }
        if (iconRes != null) {
            val icon = ResourcesCompat.getDrawable(activity.resources, iconRes, activity.theme)?.mutate()
            icon?.setTint(accent); icon?.setBounds(0, 0, dp(18), dp(18))
            view.setCompoundDrawables(icon, null, null, null)
            view.compoundDrawablePadding = dp(6)
        }
        return view
    }

    /**
     * Píldora/chip compacto traslúcido estilo Apple HIG, ideal para colocarse arriba a la derecha.
     */
    fun statusPill(value: String, tone: Tone = Tone.NEUTRAL, withDot: Boolean = true, iconRes: Int? = null): TextView {
        val bg = toneBackground(tone)
        val fg = toneForeground(tone)
        return text(value, 12, true, fg).apply {
            gravity = Gravity.CENTER
            background = rounded(bg, 999)
            setPadding(space(12), dp(6), space(12), dp(6))
            minHeight = dp(28)
            // Keep the pill compact even when the parent is a vertical LinearLayout.
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            // A real 6dp emitter dot, not a full-size text bullet.
            val dot = if (withDot) rounded(fg, 999).apply { setSize(dp(6), dp(6)) } else null
            dot?.setBounds(0, 0, dp(6), dp(6))
            val icon = iconRes?.let { res ->
                ResourcesCompat.getDrawable(activity.resources, res, activity.theme)?.mutate()?.apply {
                    setTint(fg); setBounds(0, 0, dp(13), dp(13))
                }
            }
            if (dot != null || icon != null) {
                setCompoundDrawables(dot, null, icon, null)
                compoundDrawablePadding = dp(6)
            }
            contentDescription = value
        }
    }

    fun banner(
        parent: LinearLayout,
        titleText: String,
        message: String,
        tone: Tone,
        actionLabel: String? = null,
        action: (() -> Unit)? = null,
        iconRes: Int? = null,
    ): LinearLayout {
        val fg = toneForeground(tone)
        // Superficie apenas teñida por el estado: aporta color en claro y se mantiene neutra en oscuro.
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(toneBackground(tone), 20).apply { setStroke(dp(1), colorWithAlpha(fg, 0.32f)) }
            setPadding(space(16), space(16), space(16), space(14))
        }
        val header = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP }
        val badge = iconBadge(iconRes ?: toneIcon(tone), tone, size = 38)
        header.addView(badge, LinearLayout.LayoutParams(dp(38), dp(38)))
        val copy = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        copy.addView(text(titleText, 16, true, ink))
        copy.addView(text(message, 14, false, ink).apply {
            alpha = .78f
            setPadding(0, dp(3), 0, 0)
        })
        header.addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = space(12) })
        box.addView(header)
        if (!actionLabel.isNullOrBlank() && action != null) {
            val actionView = textAction(actionLabel, action).apply {
                minHeight = dp(40)
                setPadding(0, dp(6), 0, dp(0))
            }
            box.addView(actionView, LinearLayout.LayoutParams(-2, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = space(4)
            })
        }
        parent.addView(box, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = space(14)
        })
        return box
    }

    fun emptyState(
        parent: LinearLayout,
        titleText: String,
        message: String,
        actionLabel: String? = null,
        action: (() -> Unit)? = null,
        iconRes: Int = R.drawable.ic_info,
    ): LinearLayout {
        val box = plainCard(parent, cardColor)
        box.gravity = Gravity.CENTER_HORIZONTAL
        box.setPadding(space(20), space(22), space(20), space(20))
        box.addView(iconBadge(iconRes, Tone.NEUTRAL, size = 48), LinearLayout.LayoutParams(dp(48), dp(48)))
        box.addView(text(titleText, 18, true, ink).apply {
            gravity = Gravity.CENTER
            setPadding(0, space(12), 0, 0)
        }, LinearLayout.LayoutParams(-1, -2))
        box.addView(text(message, 14, false, muted).apply {
            gravity = Gravity.CENTER
            setLineSpacing(dp(1).toFloat(), 1.05f)
        }, LinearLayout.LayoutParams(-1, -2))
        if (actionLabel != null && action != null) box.addView(button(actionLabel, true, action))
        return box
    }

    fun loadingState(parent: LinearLayout, message: String = "Cargando información…") {
        val box = plainCard(parent, cardColor)
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(ProgressBar(activity).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(accent)
        }, LinearLayout.LayoutParams(dp(26), dp(26)))
        row.addView(text(message, 15, true, muted).apply { setPadding(dp(12), 0, 0, 0) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(row)
    }

    fun errorState(parent: LinearLayout, titleText: String, message: String, retry: (() -> Unit)? = null) {
        val box = plainCard(parent, cardColor)
        val header = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP }
        header.addView(iconBadge(R.drawable.ic_warning, Tone.DANGER, size = 36), LinearLayout.LayoutParams(dp(36), dp(36)))
        val copy = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        copy.addView(text(titleText, 16, true, critical))
        copy.addView(text(message, 14, false, muted))
        header.addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = space(10) })
        box.addView(header)
        if (retry != null) box.addView(button("Reintentar", false, retry))
    }

    fun iconBadge(iconRes: Int, tone: Tone = Tone.BRAND, size: Int = 44): View = coloredBadge(
        iconRes,
        when (tone) {
            Tone.BRAND -> brandSoft
            Tone.SUCCESS -> mint
            Tone.DANGER -> criticalSurface
            Tone.WARNING -> warningSurface
            Tone.INFO -> infoSurface
            Tone.NEUTRAL -> softSurface
        },
        toneForeground(tone).let { if (tone == Tone.NEUTRAL) accent else it },
        size,
    )

    fun coloredBadge(iconRes: Int, bgColor: Int, fgColor: Int, size: Int = 44): View {
        val frame = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = rounded(bgColor, (size * 0.36f).toInt())
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val image = ImageView(activity).apply {
            setImageResource(iconRes)
            setColorFilter(fgColor)
            contentDescription = null
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        frame.addView(image, LinearLayout.LayoutParams(dp((size * 0.50f).toInt()), dp((size * 0.50f).toInt())))
        return frame
    }

    fun avatar(initials: String, size: Int = 48, tone: Tone = Tone.SUCCESS): TextView {
        val bg = toneBackground(tone)
        val fg = toneForeground(tone)
        return text(initials.ifBlank { "T" }.take(2).uppercase(), if (size >= 48) 17 else 14, true, fg).apply {
            gravity = Gravity.CENTER
            background = rounded(bg, 999).apply { setStroke(dp(1), colorWithAlpha(fg, .22f)) }
            setPadding(0, 0, 0, 0)
            contentDescription = "Perfil de $initials"
        }
    }

    fun field(
        parent: LinearLayout,
        labelText: String,
        value: String,
        input: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
        tag: String? = null,
        helper: String? = null,
        hintText: String? = null,
    ): EditText {
        val labelView = text(labelText, 14, true, ink).apply { setPadding(0, space(8), 0, 0) }
        parent.addView(labelView)
        val edit = EditText(activity).apply {
            hint = hintText ?: exampleFor(labelText, input, tag)
            setText(value)
            this.inputType = input
            setTextColor(ink)
            setHintTextColor(muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, textScale(16f))
            typeface = familyRegular
            minHeight = controlScale(56)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(if (dark) softSurface else Color.WHITE, 14).apply {
                setStroke(dp(1), controlOutline)
            }
            this.tag = tag
            contentDescription = labelText
            isFocusable = true
        }
        parent.addView(edit, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6)
        })
        if (!helper.isNullOrBlank()) {
            parent.addView(text(helper, 12, false, muted).apply { setPadding(dp(2), dp(4), dp(2), 0) })
        }
        return edit
    }

    private fun exampleFor(label: String, input: Int, tag: String?): String = when {
        input == InputType.TYPE_CLASS_PHONE -> "Ej. +52 55 1234 5678"
        input and InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS != 0 -> "nombre@correo.com"
        input and InputType.TYPE_TEXT_VARIATION_PASSWORD != 0 -> "Escribe tu contraseña"
        tag == "fullName" -> "Nombre y apellidos"
        tag == "address" -> "Calle, número y ciudad"
        tag == "emergencyContactName" -> "Nombre y apellidos"
        tag?.startsWith("contact_name_") == true -> "Nombre del contacto"
        label.contains("latitud", true) -> "Ej. 19.4326"
        label.contains("longitud", true) -> "Ej. -99.1332"
        label.contains("radio", true) -> "Entre 25 y 20000 metros"
        label.contains("nombre", true) -> "Escribe un nombre"
        else -> "Escribe aquí"
    }

    fun switchRow(
        parent: LinearLayout,
        titleText: String,
        subtitle: String? = null,
        value: Boolean,
        action: (Boolean) -> Unit,
        enabled: Boolean = true,
    ): Switch {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = controlScale(64)
            setPadding(space(2), space(6), space(2), space(6))
        }
        val copy = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        copy.addView(text(titleText, 16, false, ink))
        if (!subtitle.isNullOrBlank()) copy.addView(text(subtitle, 13, false, muted).apply { setPadding(0, dp(2), dp(8), 0) })
        row.addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val description = if (subtitle.isNullOrBlank()) titleText else "$titleText. $subtitle"
        val control = switchControl(titleText, value, action, enabled).apply {
            showText = false
            text = ""
            contentDescription = description
            minWidth = dp(48)
        }
        row.addView(control, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = space(10) })
        row.setOnClickListener { if (enabled) control.toggle() }
        parent.addView(row)
        return control
    }

    fun switchControl(
        titleText: String,
        value: Boolean,
        action: (Boolean) -> Unit,
        enabled: Boolean = true,
    ): Switch = Switch(activity).apply {
        text = titleText
        isChecked = value
        isEnabled = enabled
        showText = true
        typeface = familyRegular
        setTextColor(if (enabled) ink else muted)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, textScale(if (simple) 18f else 16f))
        minHeight = controlScale(62)
        setPadding(space(4), space(8), space(4), space(8))
        switchPadding = dp(16)
        thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(primaryFill, Color.WHITE))
        trackTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(colorWithAlpha(primaryFill, if (dark) .65f else 1f), controlOutline),
        )
        contentDescription = titleText
        setOnCheckedChangeListener { _, checked -> action(checked) }
    }

    fun segmented(
        parent: LinearLayout,
        options: List<String>,
        selected: Int,
        onSelect: (Int) -> Unit,
    ): LinearLayout {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(softSurface, 12)
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        options.forEachIndexed { index, label ->
            val active = index == selected
            val button = Button(activity).apply {
                text = label
                isAllCaps = false
                typeface = familyBold
                setTextSize(TypedValue.COMPLEX_UNIT_SP, textScale(14f))
                setTextColor(if (active) ink else muted)
                minHeight = controlScale(42)
                setMinHeight(controlScale(42))
                stateListAnimator = null
                elevation = 0f
                val surface = rounded(if (active) cardColor else Color.TRANSPARENT, 9).apply {
                    if (active && !dark) setStroke(dp(1), colorWithAlpha(outline, .70f))
                }
                background = ripple(0x1F0E48D6, surface, 9)
                contentDescription = "$label${if (active) ", seleccionado" else ""}"
                setOnClickListener { onSelect(index) }
            }
            row.addView(button, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (index > 0) leftMargin = dp(3)
            })
        }
        parent.addView(row, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = space(10)
        })
        return row
    }

    fun filterChip(parent: LinearLayout, labelText: String, selected: Boolean, onSelect: () -> Unit): Button =
        Button(activity).apply {
            text = labelText
            isAllCaps = false
            typeface = familyBold
            setTextSize(TypedValue.COMPLEX_UNIT_SP, textScale(13f))
            setTextColor(if (selected) onAccent else muted)
            minHeight = controlScale(44)
            setMinHeight(controlScale(44))
            stateListAnimator = null
            elevation = 0f
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = ripple(
                0x220E48D6,
                rounded(if (selected) primaryFill else softSurface, 999),
                999,
            )
            contentDescription = "$labelText${if (selected) ", seleccionado" else ""}"
            setOnClickListener { onSelect() }
        }.also { parent.addView(it, LinearLayout.LayoutParams(-2, ViewGroup.LayoutParams.WRAP_CONTENT)) }

    fun labeledValue(parent: LinearLayout, labelText: String, value: String, meta: String? = null): LinearLayout {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, space(6), 0, space(6))
        }
        row.addView(text(labelText, 13, true, muted))
        row.addView(text(value, 17, true, ink))
        if (!meta.isNullOrBlank()) row.addView(text(meta, 13, false, muted))
        parent.addView(row, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        return row
    }

    fun callButton(labelText: String, phone: String, onCall: () -> Unit): Button =
        button(labelText.ifBlank { "Llamar" }, false, onCall, R.drawable.ic_phone).apply {
            contentDescription = "$labelText, $phone"
        }

    fun metricTile(
        parent: LinearLayout,
        labelText: String,
        value: String,
        unit: String,
        meta: String,
        tone: Tone = Tone.SUCCESS,
    ): LinearLayout {
        val tile = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(cardColor, 20).apply { setStroke(dp(1), outline) }
            setPadding(space(14), space(14), space(14), space(12))
        }
        val top = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(iconBadge(toneIcon(tone), tone, size = 32), LinearLayout.LayoutParams(dp(32), dp(32)))
        top.addView(text(labelText, 13, true, ink).apply { setPadding(space(8), 0, 0, 0) },
            LinearLayout.LayoutParams(0, -2, 1f))
        tile.addView(top)
        val valueRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        valueRow.addView(text(value, 34, true, ink).apply {
            fontFeatureSettings = "tnum"
            letterSpacing = -0.02f
        })
        if (unit.isNotBlank()) valueRow.addView(text(" $unit", 13, true, muted).apply { setPadding(0, 0, 0, dp(5)) })
        tile.addView(valueRow, LinearLayout.LayoutParams(-2, -2).apply { topMargin = space(4) })
        if (meta.isNotBlank()) {
            tile.addView(statusPill(meta, tone, withDot = false),
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = space(8) })
        }
        parent.addView(tile, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return tile
    }

    fun twoColumns(parent: LinearLayout): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = space(10) })
    }

    fun actionRow(
        parent: LinearLayout,
        titleText: String,
        subtitle: String? = null,
        leadingIcon: Int? = null,
        leadingText: String? = null,
        trailingText: String? = null,
        showDivider: Boolean = true,
        statusText: String? = null,
        statusTone: Tone = Tone.NEUTRAL,
        onClick: (() -> Unit)? = null,
    ): LinearLayout {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = controlScale(60)
            setPadding(space(2), space(8), space(2), space(8))
            if (onClick != null) {
                isClickable = true
                isFocusable = true
                background = ripple(0x1F0E48D6, null, 12)
            }
        }
        val badge = if (leadingText != null) avatar(leadingText, 42, Tone.BRAND)
        else if (leadingIcon != null) iconBadge(leadingIcon, Tone.BRAND, 42) else null
        if (badge != null) row.addView(badge, LinearLayout.LayoutParams(dp(42), dp(42)).apply { rightMargin = space(12) })
        val copy = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = false
        }
        copy.addView(text(titleText, 16, true, ink))
        if (!subtitle.isNullOrBlank()) copy.addView(text(subtitle, 14, false, muted))
        // A long title would otherwise share its wrapped lines with the state label. Keeping the
        // state inside the text column gives the title the full row width and a stable reading order.
        if (!statusText.isNullOrBlank()) {
            copy.addView(
                statusPill(statusText, statusTone),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = space(6) },
            )
        }
        row.addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (!trailingText.isNullOrBlank()) {
            row.addView(text(trailingText, 12, true, accent).apply {
                setPadding(dp(6), 0, dp(4), 0)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                maxWidth = dp(112)
            })
        }
        if (onClick != null) {
            val chevron = ImageView(activity).apply {
                setImageResource(R.drawable.ic_chevron)
                setColorFilter(muted)
                contentDescription = null
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            row.addView(chevron, LinearLayout.LayoutParams(dp(18), dp(18)).apply { leftMargin = dp(4) })
            row.setOnClickListener { onClick() }
            row.contentDescription = listOfNotNull(titleText, subtitle, statusText ?: trailingText).joinToString(", ")
        }
        parent.addView(row, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        if (showDivider) divider(parent)
        return row
    }

    fun progress(value: Int, max: Int = 100, height: Int = 8): ProgressBar =
        ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            this.max = max.coerceAtLeast(1)
            progress = value.coerceIn(0, max)
            progressTintList = ColorStateList.valueOf(accent)
            progressBackgroundTintList = ColorStateList.valueOf(softSurface)
            minimumHeight = dp(height)
            contentDescription = "$value por ciento"
        }

    fun radioRow(parent: LinearLayout, titleText: String, subtitle: String, checked: Boolean, onSelect: () -> Unit): LinearLayout {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = controlScale(64)
            background = rounded(cardColor, 20).apply {
                setStroke(dp(if (checked) 2 else 1), if (checked) accent else outline)
            }
            setPadding(space(14), space(10), space(14), space(10))
            isClickable = true
            isFocusable = true
            setOnClickListener { onSelect() }
            contentDescription = "$titleText. $subtitle. ${if (checked) "Seleccionado" else "No seleccionado"}"
        }
        val copy = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        copy.addView(text(titleText, 16, true, ink))
        copy.addView(text(subtitle, 13, false, muted))
        row.addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(iconBadge(if (checked) R.drawable.ic_check_circle else R.drawable.ic_info, if (checked) Tone.SUCCESS else Tone.NEUTRAL, 28),
            LinearLayout.LayoutParams(dp(28), dp(28)))
        parent.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = space(8) })
        return row
    }

    fun rounded(fill: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
    }

    fun ripple(rippleColor: Int, content: GradientDrawable?, radius: Int): RippleDrawable =
        RippleDrawable(ColorStateList.valueOf(rippleColor), content, if (content == null) rounded(Color.WHITE, radius) else null)

    fun color(hex: String): Int = Color.parseColor(hex)

    private fun isProductExplainer(value: String): Boolean {
        val copy = value.trim().lowercase()
        return listOf(
            "necesitas un código",
            "contactos, eventos, lecturas válidas",
            "no puedes editar medicamentos ni confirmar tomas",
            "la detección usa",
            "la detección es experimental",
            "la cámara no se activa",
            "la cámara se activa",
            "el video se procesa",
            "solo se envía el evento técnico",
            "los avisos dependen",
            "los sms se envían",
            "con tu autorización",
            "la ubicación se actualiza",
            "la ubicación se adjunta",
            "la prueba inicia",
            "los cambios se conservan",
            "puedes usar tzindaapp",
            "tienes acceso de solo lectura",
            "vínculo activo y separado",
            "registros guardados primero",
            "cada detección se guarda",
            "el modo oscuro y el modo simplificado",
            "aumenta texto y controles",
        ).any { copy.startsWith(it) }
    }
    fun colorWithAlpha(color: Int, alpha: Float): Int = Color.argb(
        (alpha.coerceIn(0f, 1f) * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color),
    )

    fun setAccessibleHeading(view: TextView) {
        if (Build.VERSION.SDK_INT >= 28) ViewCompat.setAccessibilityHeading(view, true)
    }

    fun contentDescriptionFor(prefix: String, value: String): String = "$prefix. $value"

    fun announce(view: View, message: String) {
        if (Build.VERSION.SDK_INT >= 28) {
            view.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        view.contentDescription = message
    }

    fun iconOnlyButton(iconRes: Int, description: String, size: Int = 48, onClick: () -> Unit): ImageView =
        ImageView(activity).apply {
            setImageResource(iconRes)
            setColorFilter(accent)
            contentDescription = description
            isClickable = true
            isFocusable = true
            background = ripple(0x1F0E48D6, rounded(softSurface, 999), 999)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { onClick() }
            minimumWidth = dp(size)
            minimumHeight = dp(size)
        }

    fun navIcon(iconRes: Int, selected: Boolean): android.graphics.drawable.Drawable? =
        ResourcesCompat.getDrawable(activity.resources, iconRes, activity.theme)?.mutate()?.apply {
            setTint(if (selected) accent else navInactive)
            setBounds(0, 0, dp(24), dp(24))
        }

    fun safeDescription(vararg parts: String?): String =
        parts.filterNotNull().filter { it.isNotBlank() }.joinToString(". ")

    /** iOS Settings-style card header with a tinted leading icon box. */
    fun cardHeader(
        parent: LinearLayout,
        iconRes: Int,
        titleText: String,
        subtitleText: String? = null,
        tone: Tone = Tone.BRAND,
    ): LinearLayout {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, space(4))
        }
        row.addView(iconBadge(iconRes, tone, 34), LinearLayout.LayoutParams(dp(34), dp(34)))
        val copy = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        copy.addView(text(titleText, 19, true, ink))
        if (!subtitleText.isNullOrBlank()) copy.addView(text(subtitleText, 13, false, muted))
        row.addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = space(11)
        })
        parent.addView(row)
        return row
    }

    fun horizontalRule(parent: LinearLayout) {
        parent.addView(View(activity).apply { setBackgroundColor(outline) }, LinearLayout.LayoutParams(-1, dp(1)))
    }

    fun sectionSpacer(parent: LinearLayout) {
        parent.addView(Space(activity), LinearLayout.LayoutParams(1, space(16)))
    }

    fun cardDividerInset(parent: LinearLayout) = divider(parent)

    fun statusDot(color: Int): View = View(activity).apply {
        background = rounded(color, 999)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun wrapText(textView: TextView, maxWidthPx: Int? = null) {
        textView.ellipsize = null
        textView.setSingleLine(false)
        textView.maxLines = Int.MAX_VALUE
        if (maxWidthPx != null) textView.maxWidth = maxWidthPx
    }
}
