package com.octavo.jarvis

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.Button

/** Estilo compartido de las pantallas: fondo oscuro, azul y rojo, bordes de neón. */
object Tema {
    fun dp(ctx: Context, n: Int) = (n * ctx.resources.displayMetrics.density).toInt()

    /** Caja redondeada con relleno y borde. */
    fun caja(ctx: Context, relleno: Int, borde: Int, grosor: Int = 2, radio: Int = 14) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(ctx, radio).toFloat()
        setColor(relleno)
        setStroke(dp(ctx, grosor), borde)
    }

    fun transparente(color: Int, alfa: Int) = Color.argb(alfa, Color.red(color), Color.green(color), Color.blue(color))

    /** Botón con borde de neón del color de acento. */
    fun boton(b: Button, acento: Int) {
        val ctx = b.context
        b.background = StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), caja(ctx, transparente(acento, 10), transparente(acento, 70)))
            addState(intArrayOf(android.R.attr.state_pressed), caja(ctx, transparente(acento, 120), ReactorView.AZUL_CLARO))
            addState(intArrayOf(), caja(ctx, transparente(acento, 35), acento))
        }
        b.setTextColor(
            ColorStateList(
                arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(Color.argb(110, 255, 255, 255), Color.WHITE)
            )
        )
        b.isAllCaps = false
        b.stateListAnimator = null
        b.setPadding(dp(ctx, 10), dp(ctx, 6), dp(ctx, 10), dp(ctx, 6))
        tactil(b)
    }

    // ---------- Paleta ampliada ----------
    val SUPERFICIE = 0xFF0A1626.toInt()      // tarjetas y barra de entrada
    val VERDE = 0xFF2EE6A6.toInt()           // listo / correcto
    val AMBAR = 0xFFFFB02E.toInt()           // trabajando / en espera

    /** Fondo con un degradado muy suave (arriba un poco más claro) para dar profundidad. */
    fun fondo() = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF0A1830.toInt(), ReactorView.FONDO, ReactorView.FONDO)
    )

    /** Texto de botón de una sola línea con tamaño fijo en dp (no se agranda con la fuente del sistema), sin altura mínima. */
    fun compacto(b: Button, tam: Float) {
        b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, tam)
        b.minHeight = 0; b.minimumHeight = 0; b.minWidth = 0; b.minimumWidth = 0
        b.maxLines = 1
        b.ellipsize = android.text.TextUtils.TruncateAt.END
        b.setPadding(dp(b.context, 12), 0, dp(b.context, 12), 0)
    }

    /** Botón redondo (micrófono, enviar, volver). */
    fun circulo(b: Button, acento: Int, lleno: Boolean = false, tam: Int = 44, texto: Float = 18f) {
        val ctx = b.context
        fun ovalo(relleno: Int, borde: Int) = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(relleno)
            setStroke(dp(ctx, 2), borde)
        }
        b.background = StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), ovalo(transparente(acento, 10), transparente(acento, 60)))
            addState(intArrayOf(android.R.attr.state_pressed), ovalo(transparente(acento, 160), ReactorView.AZUL_CLARO))
            addState(intArrayOf(), ovalo(transparente(acento, if (lleno) 110 else 35), acento))
        }
        b.setTextColor(
            ColorStateList(
                arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(Color.argb(110, 255, 255, 255), Color.WHITE)
            )
        )
        b.isAllCaps = false
        b.stateListAnimator = null
        b.minWidth = 0; b.minHeight = 0; b.minimumWidth = 0; b.minimumHeight = 0
        b.setPadding(0, 0, 0, 0)
        b.textSize = texto
        b.layoutParams = android.widget.LinearLayout.LayoutParams(dp(ctx, tam), dp(ctx, tam))
    }

    /** Etiqueta con forma de píldora (estado de una tarea, estado del motor). */
    fun pildora(ctx: Context, color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(ctx, 20).toFloat()
        setColor(transparente(color, 32))
        setStroke(dp(ctx, 1), transparente(color, 140))
    }

    /** Al tocar, la vista se encoge un poquito y vibra suave: se siente que responde. No consume el toque. */
    fun tactil(v: View) {
        v.setOnTouchListener { vista, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    vista.animate().scaleX(0.95f).scaleY(0.95f).setDuration(80).start()
                    if (Build.VERSION.SDK_INT >= 27) vista.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> vista.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }
            false
        }
    }

    /** Entrada suave: aparece subiendo unos píxeles. */
    fun aparecer(v: View, retrasoMs: Long = 0) {
        v.alpha = 0f
        v.translationY = dp(v.context, 8).toFloat()
        v.animate().alpha(1f).translationY(0f).setStartDelay(retrasoMs).setDuration(220).start()
    }
}
