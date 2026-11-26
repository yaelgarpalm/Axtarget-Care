package com.example.guardian_iot

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.CompoundButton
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import android.widget.Switch
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout

/** Android components from the Cobalto V5 design. The hosts supply real state and actions. */
internal class TzindaV5(private val activity: Activity, val design: TzindaDesign) {
    val onBlue = Color.WHITE
    val onBlueMuted = Color.rgb(222, 235, 255)
    val darkHero = Color.rgb(13, 13, 13)
    val cameraSurface = Color.rgb(22, 48, 56)
    val teal = Color.rgb(2, 191, 108)

    fun dp(dp: Int) = design.dp(dp)

    fun column() = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

    fun horizontal() = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun text(
        value: String,
        size: Int,
        color: Int = design.ink,
        bold: Boolean = false,
    ) = design.text(value, size, bold, color).apply {
        visibility = View.VISIBLE
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        setPadding(0, 0, 0, 0)
        setLineSpacing(dp(2).toFloat(), 1f)
        ViewCompat.setAccessibilityHeading(this, false)
    }

    fun label(value: String, color: Int = design.muted) = text(value, 11, color, bold = true)

    fun title(value: String, size: Int = 34, color: Int = design.ink) =
        text(value, size, color, bold = true).apply { ViewCompat.setAccessibilityHeading(this, true) }

    fun gap(parent: LinearLayout, height: Int) = design.addGap(parent, height)

    fun header(
        parent: LinearLayout,
        title: String,
        subtitle: String = "",
        eyebrow: String = "",
        gapBelow: Int = 24,
        eyebrowGap: Int = 5,
        titleSize: Int = 34,
        settings: (() -> Unit)? = null,
    ) {
        val row = horizontal().apply { gravity = Gravity.TOP }
        val labels = column()
        if (eyebrow.isNotBlank()) {
            labels.addView(label(eyebrow))
            gap(labels, eyebrowGap)
        }
        labels.addView(title(title, titleSize))
        if (subtitle.isNotBlank()) {
            gap(labels, 7)
            labels.addView(text(subtitle, 13, design.muted))
        }
        row.addView(labels, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        if (settings != null) {
            row.addView(iconButton(R.drawable.v5_settings, "Ajustes", action = settings),
                LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                    gravity = if (eyebrowGap > 10 || subtitle.isNotBlank()) Gravity.TOP else Gravity.CENTER_VERTICAL
                })
        }
        parent.addView(row)
        gap(parent, gapBelow)
    }

    fun section(
        parent: LinearLayout,
        label: String,
        actionLabel: String? = null,
        titleSize: Int = 20,
        action: (() -> Unit)? = null,
    ) {
        val row = horizontal()
        row.addView(title(label, titleSize), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        if (actionLabel != null && action != null) {
            val actionView = text(actionLabel, 13, design.accent).apply {
                gravity = Gravity.CENTER
                minimumWidth = dp(56)
                minimumHeight = dp(34)
                setPadding(dp(8), 0, dp(8), 0)
                activate(this, action)
            }
            row.addView(actionView)
        }
        parent.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            bottomMargin = dp(if (action == null) 13 else 7)
        })
    }

    fun panel(
        parent: LinearLayout,
        fill: Int = design.cardColor,
        radius: Int = 26,
        padding: Int = 16,
        margin: Int = 16,
    ): LinearLayout = column().apply {
        background = design.rounded(fill, radius)
        val inset = design.space(padding)
        setPadding(inset, inset, inset, inset)
        parent.addView(this, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            bottomMargin = design.space(margin)
        })
    }

    fun timeline(parent: LinearLayout, margin: Int): LinearLayout {
        val card = panel(parent, padding = 0, margin = margin)
        val rows = object : LinearLayout(activity) {
            private val line = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = design.outline
                strokeWidth = dp(2).toFloat()
            }

            override fun onDraw(canvas: android.graphics.Canvas) {
                super.onDraw(canvas)
                if (childCount < 2) return
                val first = getChildAt(0)
                val last = getChildAt(childCount - 1)
                val x = if (layoutDirection == View.LAYOUT_DIRECTION_RTL) width - dp(33) else dp(33)
                canvas.drawLine(x.toFloat(), first.top + first.height / 2f,
                    x.toFloat(), last.top + last.height / 2f, line)
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            setWillNotDraw(false)
        }
        card.addView(rows)
        return rows
    }

    fun icon(res: Int, size: Int, color: Int? = null) = ImageView(activity).apply {
        setImageResource(res)
        if (color != null) setColorFilter(color)
        scaleType = ImageView.ScaleType.FIT_CENTER
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }

    fun iconButton(
        res: Int,
        description: String,
        fill: Int = design.cardColor,
        fg: Int? = design.accent,
        glyphSize: Int = 22,
        action: () -> Unit,
    ): FrameLayout = FrameLayout(activity).apply {
        addView(icon(res, glyphSize, fg), FrameLayout.LayoutParams(dp(glyphSize), dp(glyphSize), Gravity.CENTER))
        background = RippleDrawable(ColorStateList.valueOf(0x240047AB), design.rounded(fill, 999), null)
        contentDescription = description
        minimumHeight = dp(48)
        minimumWidth = dp(48)
        activate(this, action)
    }

    fun badge(res: Int, fill: Int, fg: Int, size: Int = 38, glyphSize: Int = 21, corner: Int = 13) =
        FrameLayout(activity).apply {
            background = design.rounded(fill, corner)
            addView(icon(res, glyphSize, fg), FrameLayout.LayoutParams(dp(glyphSize), dp(glyphSize), Gravity.CENTER))
            layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

    fun art(res: Int) = ImageView(activity).apply {
        setImageResource(res)
        scaleType = ImageView.ScaleType.FIT_CENTER
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun row(
        parent: LinearLayout,
        label: String,
        detail: String,
        res: Int,
        fill: Int = design.brandSoft,
        fg: Int = design.accent,
        minHeight: Int = 65,
        round: Boolean = false,
        divider: Boolean = false,
        inset: Int = 14,
        iconGap: Int = 13,
        titleColor: Int = design.ink,
        subtitleColor: Int = design.muted,
        emphasis: Boolean = false,
        badgeSize: Int = 38,
        trailing: View? = null,
        titleSize: Int = if (emphasis) 16 else 15,
        detailSize: Int = 13,
        detailGap: Int = 4,
        action: (() -> Unit)? = null,
    ): LinearLayout {
        val row = horizontal().apply {
            minimumHeight = design.controlScale(minHeight)
            setPadding(dp(inset), dp(9), dp(16), dp(9))
        }
        row.addView(badge(res, fill, fg, size = badgeSize, glyphSize = if (badgeSize > 38) 27 else 21,
            corner = if (round) 99 else 13))
        row.addView(column().apply {
            addView(text(label, titleSize, titleColor, bold = emphasis))
            if (detail.isNotBlank()) {
                gap(this, detailGap)
                addView(text(detail, detailSize, subtitleColor))
            }
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(iconGap) })
        if (trailing != null) row.addView(trailing)
        if (action != null) {
            if (trailing == null) row.addView(icon(R.drawable.ic_chevron, 12, subtitleColor))
            row.contentDescription = design.safeDescription(label, detail)
            activate(row, action)
        }
        parent.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        if (divider) {
            parent.addView(View(activity).apply {
                setBackgroundColor(design.outline)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(MATCH_PARENT, dp(1)).apply {
                marginStart = dp(65)
                marginEnd = dp(18)
            })
        }
        return row
    }

    fun action(
        parent: LinearLayout,
        label: String,
        icon: Int? = null,
        fill: Int = design.primaryFill,
        fg: Int = onBlue,
        height: Int = 52,
        radius: Int = 19,
        margin: Int = 12,
        centeredIcon: Boolean = false,
        trailingIcon: Boolean = false,
        action: () -> Unit,
    ): Button {
        val button = design.button(label, action = action, iconRes = icon).apply {
            background = RippleDrawable(
                ColorStateList.valueOf(if (fg == onBlue) 0x33FFFFFF else 0x1F0047AB),
                design.rounded(fill, radius).apply {
                    if (fill == design.cardColor) setStroke(dp(1), design.outline)
                }, null,
            )
            setTextColor(fg)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, design.textScale(15f))
            minimumHeight = design.controlScale(height)
            minHeight = design.controlScale(height)
            val glyph = compoundDrawables[0]
            glyph?.setTint(fg)
            val endInset = if (glyph != null && !centeredIcon && !trailingIcon) 32 else 18
            setPaddingRelative(dp(18), dp(4), dp(endInset), dp(8))
            if (trailingIcon) {
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                setCompoundDrawablesRelative(null, null, glyph, null)
                typeface = design.regularTypeface()
            }
            if (centeredIcon && glyph != null) {
                doOnLayout {
                    val gap = dp(34)
                    val desired = ((width - paint.measureText(label) - glyph.bounds.width() - gap) / 2).toInt()
                    compoundDrawablePadding = if (desired > dp(16)) gap else dp(8)
                    val inset = desired.coerceAtLeast(dp(16))
                    setPaddingRelative(inset, paddingTop, inset, paddingBottom)
                }
            }
        }
        parent.addView(button, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            bottomMargin = design.space(margin)
        })
        return button
    }

    fun note(
        parent: LinearLayout,
        message: String,
        res: Int = R.drawable.v5_info,
        margin: Int = 16,
        fill: Int = design.softSurface,
        padding: Int = 12,
    ) {
        val card = panel(parent, fill = fill, radius = 18, padding = padding, margin = margin)
        val row = horizontal()
        row.addView(icon(res, 18, design.muted))
        row.addView(text(message, 12, design.muted), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
            marginStart = dp(13)
        })
        card.addView(row)
    }

    fun twoColumns(parent: LinearLayout, left: View, right: View, space: Int = 12) {
        val row = horizontal().apply { gravity = Gravity.TOP }
        row.addView(left, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(space) })
        row.addView(right, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        parent.addView(row)
    }

    fun toggleRow(
        parent: LinearLayout,
        title: String,
        detail: String,
        glyph: Int,
        checked: Boolean,
        height: Int = 56,
        offColor: Int = 0xFFA6B0B7.toInt(),
        onChange: (Boolean) -> Unit = {},
    ): CompoundButton {
        val row = horizontal().apply {
            minimumHeight = design.controlScale(height)
            isBaselineAligned = false
        }
        row.addView(badge(glyph, design.brandSoft, design.accent, 36))
        row.addView(column().apply {
            addView(text(title, 15))
            gap(this, 5)
            addView(text(detail, 12, design.muted))
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
        val toggle = object : CompoundButton(activity) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

            override fun getAccessibilityClassName(): CharSequence = Switch::class.java.name

            override fun onDraw(canvas: Canvas) {
                val trackHalfWidth = dp(46) / 2f
                val trackRadius = dp(27) / 2f
                val centerX = this.width / 2f
                val centerY = this.height / 2f
                paint.color = if (isChecked) teal else offColor
                canvas.drawRoundRect(centerX - trackHalfWidth, centerY - trackRadius,
                    centerX + trackHalfWidth, centerY + trackRadius, trackRadius, trackRadius, paint)
                paint.color = Color.WHITE
                val offset = trackHalfWidth - trackRadius
                val right = isChecked != (layoutDirection == View.LAYOUT_DIRECTION_RTL)
                canvas.drawCircle(centerX + if (right) offset else -offset, centerY, dp(21) / 2f, paint)
            }

            override fun drawableStateChanged() {
                super.drawableStateChanged()
                invalidate()
            }
        }.apply {
            setButtonDrawable(null)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 0)
            background = RippleDrawable(ColorStateList.valueOf(0x260047AB), null, design.rounded(Color.WHITE, 99))
            isFocusable = true
            contentDescription = design.safeDescription(title, detail)
            isChecked = checked
            setOnCheckedChangeListener { _, enabled -> onChange(enabled) }
        }
        row.addView(toggle, LinearLayout.LayoutParams(dp(48), dp(48)))
        row.setOnClickListener { toggle.toggle() }
        parent.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return toggle
    }

    fun showFullScreen(
        dialog: Dialog,
        content: View,
        top: Int = design.paper,
        bottom: Int = design.paper,
        lightStatus: Boolean = !design.dark,
    ) {
        val outer = column().apply { setBackgroundColor(bottom) }
        val status = View(activity).apply { setBackgroundColor(top) }
        val navigation = View(activity).apply { setBackgroundColor(bottom) }
        outer.addView(status, LinearLayout.LayoutParams(MATCH_PARENT, 0))
        outer.addView(content, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        outer.addView(navigation, LinearLayout.LayoutParams(MATCH_PARENT, 0))
        ViewCompat.setOnApplyWindowInsetsListener(outer) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            outer.setPadding(bars.left, 0, bars.right, 0)
            status.layoutParams = status.layoutParams.apply { height = bars.top }
            navigation.layoutParams = navigation.layoutParams.apply { height = maxOf(bars.bottom, ime.bottom) }
            WindowInsetsCompat.CONSUMED
        }
        dialog.setContentView(outer)
        dialog.window?.let { window ->
            window.setBackgroundDrawable(ColorDrawable(bottom))
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = lightStatus
                isAppearanceLightNavigationBars = !design.dark
            }
        }
        dialog.show()
        dialog.window?.setLayout(MATCH_PARENT, MATCH_PARENT)
        ViewCompat.requestApplyInsets(outer)
    }

    fun field(
        parent: LinearLayout,
        label: String,
        value: String,
        hintText: String,
        input: Int = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
        trailingIcon: Int? = null,
        tag: String? = null,
        marginBelow: Int = 16,
    ): android.widget.EditText {
        parent.addView(text(label, 13, design.muted).apply { setPadding(dp(3), 0, 0, 0) })
        gap(parent, 9)
        val field = android.widget.EditText(activity).apply {
            setSingleLine(true)
            inputType = input
            hint = hintText
            setText(value)
            setTextColor(design.ink)
            setHintTextColor(design.muted)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, design.textScale(13f))
            typeface = design.regularTypeface()
            includeFontPadding = false
            minHeight = design.controlScale(52)
            setPadding(dp(16), dp(9), dp(16), dp(9))
            background = design.rounded(design.cardColor, 17).apply { setStroke(dp(1), design.outline) }
            contentDescription = label
            this.tag = tag
            if (trailingIcon != null) {
                val glyph = activity.getDrawable(trailingIcon)?.mutate()
                glyph?.setTint(design.accent)
                glyph?.setBounds(0, 0, dp(23), dp(23))
                setCompoundDrawablesRelative(null, null, glyph, null)
                compoundDrawablePadding = dp(10)
            }
        }
        parent.addView(field, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(marginBelow) })
        return field
    }

    fun segments(parent: LinearLayout, labels: List<String>, selected: Int, blue: Boolean = false, onSelect: (Int) -> Unit) {
        val rail = horizontal().apply {
            background = design.rounded(design.softSurface, 16)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        labels.forEachIndexed { i, label ->
            val active = selected == i
            val tab = design.button(label, primary = active, action = { onSelect(i) }).apply {
                isSelected = active
                minHeight = design.controlScale(36)
                minimumHeight = design.controlScale(36)
                minWidth = 0
                includeFontPadding = false
                setPadding(dp(1), dp(4), dp(1), dp(4))
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, design.textScale(12f))
                typeface = if (active) design.boldTypeface() else design.regularTypeface()
                setTextColor(when {
                    active && blue -> onBlue
                    active -> design.accent
                    else -> design.muted
                })
                background = design.rounded(when {
                    active && blue -> design.primaryFill
                    active -> design.cardColor
                    else -> Color.TRANSPARENT
                }, 13)
                contentDescription = if (active) "$label, seleccionado" else label
            }
            rail.addView(tab, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        parent.addView(rail, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    fun push(row: LinearLayout) = row.addView(Space(activity), LinearLayout.LayoutParams(0, 1, 1f))

    fun activate(view: View, action: () -> Unit) {
        view.isFocusable = true
        view.setOnClickListener { action() }
    }
}
