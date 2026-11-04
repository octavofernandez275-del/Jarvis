package com.octavo.jarvis

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
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
    }
}
