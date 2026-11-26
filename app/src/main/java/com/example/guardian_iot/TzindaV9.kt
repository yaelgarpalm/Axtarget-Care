package com.example.guardian_iot

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

internal enum class AuthMode { LOGIN, REGISTER, RECOVER, RESET }
internal const val FIRST_TUTORIAL_STEP = "v9_tutorial_step"

internal class TzindaV9(private val activity: Activity, private val v5: TzindaV5) {
    private val theme get() = v5.design

    private data class Intro(val art: Int, val title: String, val detail: String)

    private data class Lesson(
        val art: Int,
        val title: String,
        val detail: String,
        val tip: String,
        val tipDetail: String,
        val icon: Int,
        val warning: Boolean = false,
    )

    data class AccessFields(
        val email: EditText?,
        val name: EditText?,
        val password: EditText?,
        val confirmation: EditText?,
        val privacy: CheckBox?,
        val status: TextView,
        val submit: Button,
        val google: Button?,
    )

    fun welcome(body: LinearLayout, step: Int, next: () -> Unit, skip: () -> Unit, login: () -> Unit) {
        val page = introductions[step]
        brandBar(body, skip)
        v5.gap(body, 39)
        illustration(body, page.art, 250)
        val copy = v5.column().apply {
            minimumHeight = v5.dp(220)
            setPadding(v5.dp(2), v5.dp(34), v5.dp(2), 0)
        }
        copy.addView(v5.title(page.title, 35).apply { setLineSpacing(0f, 1f) })
        v5.gap(copy, 15)
        copy.addView(v5.text(page.detail, 15, theme.muted))
        body.addView(copy)
        val indicators = FrameLayout(activity)
        introductions.indices.forEach { index ->
            val selected = index == step
            indicators.addView(View(activity).apply {
                background = theme.rounded(if (selected) theme.primaryFill else 0xFFCCD6E2.toInt(), 99)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, FrameLayout.LayoutParams(v5.dp(if (selected) 24 else 7), v5.dp(7), Gravity.START or Gravity.CENTER_VERTICAL).apply {
                marginStart = v5.dp(15 + 30 * index)
            })
        }
        body.addView(indicators, LinearLayout.LayoutParams(v5.dp(100), v5.dp(36)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        v5.gap(body, 38)
        v5.action(body, if (step == introductions.lastIndex) "Comenzar" else "Siguiente", R.drawable.ic_chevron,
            margin = 13, action = next)
        v5.action(body, "Ya tengo una cuenta", fill = theme.cardColor, fg = theme.accent, margin = 24, action = login)
        body.addView(v5.text("Protección y cuidados, a tu ritmo.", 11, theme.muted).apply { gravity = Gravity.CENTER })
    }

    fun chooseMode(body: LinearLayout, select: (String) -> Unit, back: (() -> Unit)?) {
        brandBar(body)
        v5.gap(body, 33)
        body.addView(v5.label("PRIMER INGRESO", theme.accent))
        v5.gap(body, 13)
        body.addView(v5.title("¿Cómo usarás\nAxTarget Care?", 34))
        v5.gap(body, 22)
        body.addView(v5.text("Elige un modo para personalizar tu guía.", 13, theme.muted))
        v5.gap(body, 37)
        val roles = listOf(
            Triple(AppStore.ROLE_WEARER, "Persona protegida", "Mi monitoreo, mi salud\ny mi red de confianza."),
            Triple(AppStore.ROLE_CAREGIVER, "Cuidador", "Acompañar a una persona\ncon un vínculo autorizado."),
            Triple(AppStore.ROLE_CAMERA, "Cámara independiente", "Usar este teléfono para detectar\neventos en una estancia."),
        )
        val icons = listOf(R.drawable.v5_shield, R.drawable.v5_people, R.drawable.v5_camera)
        roles.forEachIndexed { index, (role, title, detail) ->
            val caregiver = role == AppStore.ROLE_CAREGIVER
            val card = v5.panel(body, radius = 28, padding = 0, margin = 18)
            v5.row(card, title, detail, icons[index], if (caregiver) theme.mint else theme.brandSoft,
                if (caregiver) theme.positive else theme.accent, minHeight = 110, inset = 16,
                emphasis = true, badgeSize = 48, iconGap = 17, titleSize = 17, detailSize = 12, detailGap = 13) { select(role) }
                .contentDescription = "Elegir modo: $title"
        }
        v5.gap(body, 26)
        tip(body, "Puedes cambiarlo en Ajustes.", "Los vínculos y permisos se autorizan por separado.", R.drawable.v5_settings)
        back?.let {
            v5.gap(body, 48)
            textAction(body, "Volver a Ajustes", action = it)
        }
    }

    fun tutorial(body: LinearLayout, role: TzindaRole, step: Int, previous: () -> Unit, next: () -> Unit, skip: () -> Unit) {
        val (eyebrow, finish, lessons) = when (role) {
            TzindaRole.WEARER -> Triple("PERSONA PROTEGIDA", "Ir a mi inicio", wearerLessons)
            TzindaRole.CAREGIVER -> Triple("CUIDADOR", "Ir a mi círculo", caregiverLessons)
            TzindaRole.CAMERA -> Triple("CÁMARA INDEPENDIENTE", "Ir a Cámara", cameraLessons)
        }
        val page = lessons[step]
        val last = step == lessons.lastIndex
        backBar(body, eyebrow, theme.accent, previous)
        v5.gap(body, 22)
        val progress = v5.horizontal().apply { minimumHeight = v5.dp(24) }
        progress.addView(v5.text("Conoce AxTarget Care", 12, theme.muted, bold = true))
        v5.push(progress)
        progress.addView(v5.text("${step + 1} de ${lessons.size}", 12, theme.accent, bold = true))
        body.addView(progress)
        v5.gap(body, 9)
        val bars = v5.horizontal()
        lessons.indices.forEach { index ->
            bars.addView(View(activity).apply {
                background = theme.rounded(if (index <= step) theme.primaryFill else 0xFFD3DFEF.toInt(), 99)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(0, v5.dp(4), 1f).apply { if (index > 0) marginStart = v5.dp(10) })
        }
        body.addView(bars)
        v5.gap(body, 24)
        illustration(body, page.art, if (page.warning) 280 else 232, if (page.warning) 0 else 13)
        val copy = v5.column().apply {
            minimumHeight = v5.dp(if (page.warning) 186 else 219)
            setPadding(v5.dp(2), v5.dp(if (page.warning) 16 else 31), v5.dp(2), v5.dp(14))
        }
        copy.addView(v5.title(page.title, 31).apply { setLineSpacing(0f, 1f) })
        v5.gap(copy, 13)
        copy.addView(v5.text(page.detail, 13, theme.muted))
        body.addView(copy)
        tip(body, page.tip, page.tipDetail, page.icon, margin = if (page.warning) 13 else 28, warning = page.warning)
        v5.action(body, if (last) finish else "Siguiente", if (last) R.drawable.v5_check else R.drawable.ic_chevron,
            margin = 10, action = next)
        textAction(body, "Omitir tutorial", action = skip)
    }

    fun access(
        body: LinearLayout,
        mode: AuthMode,
        emailDraft: String,
        back: () -> Unit,
        openMode: (AuthMode) -> Unit,
        showPrivacy: () -> Unit,
        useLocally: () -> Unit,
        useGoogle: () -> Unit,
        submit: () -> Unit,
    ): AccessFields {
        val recover = mode == AuthMode.RECOVER
        val register = mode == AuthMode.REGISTER
        val login = mode == AuthMode.LOGIN
        val reset = mode == AuthMode.RESET
        backBar(body, if (recover || reset) "RECUPERAR ACCESO" else "TU CUENTA", theme.muted, back)
        v5.gap(body, if (recover) 36 else if (reset) 29 else 31)
        if (recover) {
            body.addView(v5.badge(R.drawable.v9_mail, theme.brandSoft, theme.accent, 58, 24, 20))
            v5.gap(body, 28)
        }
        val title = when (mode) {
            AuthMode.LOGIN -> "Inicia sesión"
            AuthMode.REGISTER -> "Crea tu cuenta"
            AuthMode.RECOVER -> "Volvamos\na conectar."
            AuthMode.RESET -> "Nueva contraseña"
        }
        val detail = when (mode) {
            AuthMode.LOGIN -> "Tu círculo y tus cuidados te esperan."
            AuthMode.REGISTER -> "Un primer paso para conectar tu cuidado."
            AuthMode.RECOVER -> "Escribe el correo de tu cuenta para\nrecibir un enlace de recuperación."
            AuthMode.RESET -> "Elige una contraseña nueva. Después inicia sesión con ella."
        }
        body.addView(v5.title(title, 34).apply { if (recover) setLineSpacing(0f, 1f) })
        v5.gap(body, if (recover) 17 else 11)
        body.addView(v5.text(detail, 13, theme.muted))
        v5.gap(body, when (mode) { AuthMode.REGISTER -> 26; AuthMode.LOGIN -> 56; AuthMode.RECOVER -> 51; AuthMode.RESET -> 29 })

        val name = if (register) input(body, "Nombre", "¿Cómo te llamas?", "auth_name",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS, margin = 14) else null
        val email = if (reset) null else input(body, "Correo electrónico", "nombre@correo.com", "auth_email",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, value = emailDraft,
            margin = if (login) 28 else if (recover) 42 else 14)
        val password = if (recover) null else input(body, if (reset) "Nueva contraseña" else "Contraseña",
            if (login) "Escribe tu contraseña" else "Al menos 8 caracteres", "auth_password",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            secret = true, margin = if (login) 8 else 14)
        val confirmation = if (register || reset) input(body, "Confirmar contraseña", "Repite tu contraseña", "auth_confirmation",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, secret = true, margin = if (register) 25 else 26) else null
        val privacy = if (register) privacyChoice(body, showPrivacy) else null
        if (login) {
            textAction(body, "¿Olvidaste tu contraseña?", Gravity.END or Gravity.CENTER_VERTICAL, size = 12) { openMode(AuthMode.RECOVER) }
            v5.gap(body, 28)
        } else if (register) {
            v5.gap(body, 27)
        }
        val status = v5.text("", 13, theme.onCriticalSurface).apply {
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setPadding(0, 0, 0, v5.dp(10))
        }
        body.addView(status)
        val label = when (mode) {
            AuthMode.LOGIN -> "Iniciar sesión"
            AuthMode.REGISTER -> "Crear cuenta"
            AuthMode.RECOVER -> "Enviar enlace"
            AuthMode.RESET -> "Guardar contraseña"
        }
        val button = v5.action(body, label, if (recover) R.drawable.v9_mail else R.drawable.ic_chevron,
            margin = when (mode) { AuthMode.LOGIN -> 14; AuthMode.REGISTER -> 17; AuthMode.RECOVER -> 35; AuthMode.RESET -> 16 },
            action = submit)
        val google = if (login || register) {
            v5.action(body, "Continuar con Google", fill = theme.cardColor, fg = theme.ink, margin = 17, action = useGoogle)
        } else null
        when (mode) {
            AuthMode.LOGIN -> {
                textAction(body, "¿Es tu primera vez? Crea una cuenta") { openMode(AuthMode.REGISTER) }
                v5.gap(body, 46)
                tip(body, "Tu información, bajo tu control.", "Comparte solo lo que tú decidas.", R.drawable.v5_shield)
                v5.gap(body, 96)
                textAction(body, "Usar la app en este teléfono", action = useLocally)
            }
            AuthMode.REGISTER -> textAction(body, "Ya tengo una cuenta") { openMode(AuthMode.LOGIN) }
            AuthMode.RECOVER -> {
                tip(body, "Revisa también tu correo no deseado.", "El enlace te permitirá cambiar tu contraseña.", R.drawable.v5_info, margin = 46)
                textAction(body, "Volver al inicio de sesión") { openMode(AuthMode.LOGIN) }
            }
            AuthMode.RESET -> textAction(body, "Cancelar recuperación", action = back)
        }
        return AccessFields(email, name, password, confirmation, privacy, status, button, google)
    }

    private fun brandBar(parent: LinearLayout, skip: (() -> Unit)? = null) {
        val row = v5.horizontal().apply { minimumHeight = v5.dp(44) }
        row.addView(v5.art(R.drawable.axtarget_care_logo), LinearLayout.LayoutParams(v5.dp(44), v5.dp(44)))
        row.addView(v5.title(activity.getString(R.string.app_name), 20), LinearLayout.LayoutParams(-2, -2).apply { marginStart = v5.dp(11) })
        skip?.let {
            v5.push(row)
            row.addView(v5.text("Omitir", 13, theme.muted).apply {
                gravity = Gravity.CENTER
                minimumWidth = v5.dp(70)
                minimumHeight = v5.dp(44)
                v5.activate(this, it)
            })
        }
        parent.addView(row)
    }

    private fun backBar(body: LinearLayout, eyebrow: String, color: Int, back: () -> Unit) {
        val row = v5.horizontal().apply { translationY = v5.dp(2).toFloat() }
        row.addView(v5.iconButton(R.drawable.v5_back, "Volver", glyphSize = 29, action = back),
            LinearLayout.LayoutParams(v5.dp(44), v5.dp(44)))
        row.addView(v5.label(eyebrow, color), LinearLayout.LayoutParams(-2, -2).apply { marginStart = v5.dp(20) })
        body.addView(row)
    }

    private fun illustration(body: LinearLayout, res: Int, height: Int, inset: Int = 0) {
        body.addView(v5.art(res).apply { scaleType = ImageView.ScaleType.FIT_XY }, LinearLayout.LayoutParams(-1, v5.dp(height)).apply {
            marginStart = v5.dp(inset)
            marginEnd = v5.dp(inset)
        })
    }

    private fun tip(body: LinearLayout, title: String, detail: String, icon: Int, margin: Int = 0, warning: Boolean = false) {
        val color = if (warning) theme.warning else theme.accent
        val card = v5.panel(body, if (warning) theme.warningSurface else theme.brandSoft, radius = 23, padding = 0, margin = margin)
        val row = v5.horizontal().apply {
            minimumHeight = theme.controlScale(75)
            setPadding(v5.dp(16), v5.dp(5), v5.dp(14), v5.dp(15))
        }
        row.addView(v5.icon(icon, 28, color))
        row.addView(v5.column().apply {
            addView(v5.text(title, 14, color, bold = true))
            v5.gap(this, 6)
            addView(v5.text(detail, 12, theme.muted))
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = v5.dp(13) })
        card.addView(row)
    }

    private fun input(
        body: LinearLayout,
        label: String,
        hint: String,
        tagName: String,
        type: Int,
        value: String = "",
        secret: Boolean = false,
        margin: Int,
    ): EditText {
        body.addView(v5.text(label, 13, theme.muted).apply {
            setPadding(v5.dp(5), 0, 0, v5.dp(10))
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = v5.dp(30)
        })
        val wrapper = FrameLayout(activity).apply {
            background = theme.rounded(theme.cardColor, 17).apply { setStroke(v5.dp(1), theme.outline) }
        }
        val field = EditText(activity).apply {
            setSingleLine(true)
            inputType = type
            typeface = theme.regularTypeface()
            textSize = theme.textScale(13f)
            setTextColor(theme.ink)
            setHintTextColor(theme.muted)
            background = null
            includeFontPadding = false
            setPadding(v5.dp(16), v5.dp(9), v5.dp(16), v5.dp(9))
            minimumHeight = theme.controlScale(50)
            contentDescription = label
            tag = tagName
            isSaveEnabled = !secret
            this.hint = hint
            setText(value)
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                val hintType = when (tagName) {
                    "auth_email" -> View.AUTOFILL_HINT_EMAIL_ADDRESS
                    "auth_name" -> View.AUTOFILL_HINT_NAME
                    else -> View.AUTOFILL_HINT_PASSWORD
                }
                setAutofillHints(hintType)
            }
        }
        wrapper.addView(field, FrameLayout.LayoutParams(-1, -2).apply { if (secret) marginEnd = v5.dp(48) })
        if (secret) {
            var visible = false
            val toggle = v5.iconButton(R.drawable.v9_eye, "Mostrar $label", Color.TRANSPARENT, theme.muted, glyphSize = 23) {}
            toggle.setOnClickListener {
                visible = !visible
                val cursor = field.selectionStart.coerceAtLeast(0)
                field.transformationMethod = if (visible) null else PasswordTransformationMethod.getInstance()
                field.setSelection(cursor.coerceAtMost(field.length()))
                (toggle.getChildAt(0) as ImageView).setImageResource(if (visible) R.drawable.v9_eye_off else R.drawable.v9_eye)
                toggle.contentDescription = if (visible) "Ocultar $label" else "Mostrar $label"
            }
            wrapper.addView(toggle, FrameLayout.LayoutParams(v5.dp(48), v5.dp(48), Gravity.END or Gravity.CENTER_VERTICAL))
        }
        body.addView(wrapper, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = v5.dp(margin) })
        return field
    }

    private fun privacyChoice(body: LinearLayout, showPrivacy: () -> Unit): CheckBox {
        val row = v5.horizontal()
        val size = v5.dp(23)
        val off = theme.rounded(theme.cardColor, 7).apply {
            setStroke(v5.dp(1), theme.outline)
            setSize(size, size)
        }
        val on = theme.rounded(theme.primaryFill, 7).apply { setSize(size, size) }
        val mark = activity.getDrawable(R.drawable.v5_check)?.mutate()?.apply { setTint(Color.WHITE) }
        val checked = if (mark == null) on else LayerDrawable(arrayOf(on, mark)).apply {
            setLayerSize(0, size, size)
            setLayerSize(1, v5.dp(16), v5.dp(16))
            setLayerGravity(1, Gravity.CENTER)
        }
        val checkbox = CheckBox(activity).apply {
            buttonTintList = null
            buttonDrawable = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_checked), checked)
                addState(intArrayOf(), off)
            }
            contentDescription = "He leído el Aviso de privacidad"
            setPadding(v5.dp(4), 0, 0, 0)
        }
        row.addView(checkbox, LinearLayout.LayoutParams(v5.dp(36), v5.dp(44)))
        row.addView(v5.text("He leído el Aviso de privacidad", 12, theme.muted).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = v5.dp(44)
            contentDescription = "Leer aviso de privacidad y uso"
            v5.activate(this, showPrivacy)
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = v5.dp(3) })
        body.addView(row)
        return checkbox
    }

    private fun textAction(
        body: LinearLayout,
        label: String,
        alignment: Int = Gravity.CENTER,
        size: Int = 13,
        action: () -> Unit,
    ) {
        body.addView(v5.text(label, size, theme.accent, bold = true).apply {
            gravity = alignment
            minimumHeight = v5.dp(44)
            val endInset = if (alignment == Gravity.CENTER) 14 else 21
            setPaddingRelative(v5.dp(14), 0, v5.dp(endInset), v5.dp(4))
            v5.activate(this, action)
        }, LinearLayout.LayoutParams(-1, -2))
    }

    private companion object {
        val introductions = listOf(
            Intro(R.drawable.v9_intro_protect, "Un poco más\nde tranquilidad.", "Activa el monitoreo y conoce los avisos\nante una posible caída."),
            Intro(R.drawable.v9_intro_circle, "Cuidar también\nes estar cerca.", "Conecta con tu círculo y consulta\nla información que decidan compartir."),
            Intro(R.drawable.v9_intro_privacy, "Tus datos.\nTus decisiones.", "Elige qué compartes y con quién.\nLos permisos se piden cuando hacen falta."),
        )

        val wearerLessons = listOf(
            Lesson(R.drawable.v9_guide_protect, "Activa tu\nprotección.",
                "Desde Inicio, activa el monitoreo para\nque el teléfono esté atento a una posible caída.",
                "A tu ritmo", "Los permisos se solicitan al activar cada función.", R.drawable.v5_shield),
            Lesson(R.drawable.v9_guide_countdown, "Tu respuesta\nes lo primero.",
                "Si aparece una alerta, tendrás 20 segundos.\nPulsa «Estoy bien» para cancelarla\no pide ayuda si la necesitas.",
                "Si no respondes", "Se solicitarán SMS y llamadas a tus contactos.", R.drawable.v5_warning, warning = true),
            Lesson(R.drawable.v9_guide_trust, "Prepara tu red\nde confianza.",
                "Añade tus contactos y conecta tu pulsera.\nEn Salud verás las lecturas válidas\nque estén disponibles.",
                "Empieza por un contacto", "Confirma que esa persona puede ayudarte.", R.drawable.v5_people),
        )

        val caregiverLessons = listOf(
            Lesson(R.drawable.v9_guide_circle, "Conecta\ntu círculo.",
                "Pide un código a la persona protegida\ne introdúcelo en «Vincular persona».\nEl vínculo requiere su autorización.",
                "Un vínculo de confianza", "Los datos aparecen solo después de vincularse.", R.drawable.v5_link),
            Lesson(R.drawable.v9_guide_data, "Consulta lo\nque comparten.",
                "En Alertas verás los avisos autorizados.\nEn Salud, las lecturas y tomas\nque la persona decida compartir.",
                "Cada dato, con su contexto", "Un espacio vacío significa que no hay datos.", R.drawable.v5_pulse),
            Lesson(R.drawable.v9_guide_privacy, "Acompaña\ncon permiso.",
                "En Supervisión consulta el estado del vínculo\ny qué información está disponible.\nLa persona protegida administra los permisos.",
                "Su información sigue siendo suya.", "Tú acompañas; la persona decide qué compartir.", R.drawable.v5_lock),
        )

        val cameraLessons = listOf(
            Lesson(R.drawable.v9_guide_camera_place, "Encuentra\nel mejor lugar.",
                "Coloca el teléfono estable y con buena luz.\nDeja libre la vista de la estancia\ny comprueba el encuadre.",
                "Prepara el teléfono", "Revisa la batería y evita moverlo durante el uso.", R.drawable.v5_camera),
            Lesson(R.drawable.v9_guide_camera_start, "Inicia cuando\nlo necesites.",
                "Pulsa «Iniciar detección» para abrir la cámara\na pantalla completa. Podrás pausar\no detener la detección desde esa vista.",
                "El análisis ocurre en este teléfono.", "Este modo no transmite el video de la estancia.", R.drawable.v5_lock),
            Lesson(R.drawable.v9_guide_camera_link, "Conecta\nlos avisos.",
                "Vincula la cámara con un código o QR.\nDespués consulta sus eventos\nen el historial de detecciones.",
                "Comparte avisos autorizados.", "El vínculo y la detección se configuran por separado.", R.drawable.v5_link),
        )
    }
}
