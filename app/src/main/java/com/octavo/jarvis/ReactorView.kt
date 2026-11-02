package com.octavo.jarvis

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Reactor de Jarvis: círculos azules con acentos rojos, triángulo central y brillo que respira.
 * Reacciona a lo que hace Jarvis:
 *  - en reposo: gira lento y respira suave;
 *  - escuchando (o manos libres): los acentos pasan a rojo;
 *  - pensando: gira rápido;
 *  - hablando: el brillo late fuerte.
 * Se dibuja a ~30 cuadros por segundo, solo mientras la pantalla está a la vista.
 * Se puede apagar con el comando "apaga la animación" (ahorra batería).
 */
class ReactorView(context: Context) : View(context) {

    companion object {
        const val FONDO = 0xFF050B14.toInt()
        const val AZUL = 0xFF18B6FF.toInt()
        const val AZUL_CLARO = 0xFF9FE8FF.toInt()
        const val AZUL_OSCURO = 0xFF0B3C6B.toInt()
        const val ROJO = 0xFFFF2B3A.toInt()
        const val TEXTO = 0xFFE6F6FF.toInt()
    }

    private val reloj = Handler(Looper.getMainLooper())
    private val trazo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val relleno = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val brillo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val tri = Path()

    private var cx = 0f
    private var cy = 0f
    private var r = 0f
    private var ultimo = System.nanoTime()
    private var ang1 = 0f
    private var ang2 = 0f
    private var fase = 0f
    private var amplitud = 0.03f
    private var rojo = false

    private val bucle = object : Runnable {
        override fun run() {
            if (Ajustes.animacion(context)) invalidate()
            reloj.postDelayed(this, 33)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ultimo = System.nanoTime()
        reloj.removeCallbacks(bucle)
        reloj.post(bucle)
    }

    override fun onDetachedFromWindow() {
        reloj.removeCallbacks(bucle)
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        reloj.removeCallbacks(bucle)
        if (visibility == VISIBLE) {
            ultimo = System.nanoTime()
            reloj.post(bucle)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        cx = w / 2f
        cy = h / 2f
        r = minOf(w, h) / 2f * 0.92f
        brillo.shader = RadialGradient(
            cx, cy, r * 0.62f,
            intArrayOf(0xCC9FE8FF.toInt(), 0x6618B6FF, 0x0018B6FF),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP
        )
    }

    /** Lee qué está haciendo Jarvis y avanza la animación. */
    private fun actualizar() {
        var escuchando = false
        var pensando = false
        var hablando = false
        var manos = false
        try {
            escuchando = Assistant.voiceIn.isListening
            pensando = Assistant.ocupado
            hablando = Assistant.voiceOut.isSpeaking
            manos = HandsFree.activo
        } catch (e: Exception) {
        }
        val ahora = System.nanoTime()
        val dt = ((ahora - ultimo) / 1e9f).coerceIn(0f, 0.1f)
        ultimo = ahora
        val vel: Float
        val frec: Float
        when {
            pensando -> { vel = 140f; frec = 3.0f; amplitud = 0.04f }
            hablando -> { vel = 60f; frec = 2.4f; amplitud = 0.08f }
            escuchando -> { vel = 40f; frec = 1.6f; amplitud = 0.06f }
            else -> { vel = 12f; frec = 0.5f; amplitud = 0.03f }
        }
        ang1 = (ang1 + vel * dt) % 360f
        ang2 = (ang2 - vel * 0.6f * dt) % 360f
        fase = (fase + frec * dt) % 1f
        rojo = escuchando || manos
    }

    override fun onDraw(c: Canvas) {
        if (r <= 0f) return
        if (Ajustes.animacion(context)) actualizar()
        val acento = if (rojo) ROJO else AZUL
        val pulso = sin(fase * 2f * PI.toFloat())

        c.save()
        val esc = 1f + amplitud * pulso
        c.scale(esc, esc, cx, cy)

        // Brillo central que respira
        brillo.alpha = (190 + 60 * pulso).toInt().coerceIn(0, 255)
        c.drawCircle(cx, cy, r * 0.62f, brillo)

        // Anillo exterior con marcas
        trazo.color = AZUL
        trazo.alpha = 140
        trazo.strokeWidth = 2f
        c.drawCircle(cx, cy, r, trazo)
        trazo.strokeWidth = 1.5f
        c.save()
        c.rotate(ang1 * 0.25f, cx, cy)
        for (i in 0 until 72) {
            val largo = if (i % 6 == 0) 0.07f else 0.035f
            c.drawLine(cx, cy - r, cx, cy - r * (1f - largo), trazo)
            c.rotate(5f, cx, cy)
        }
        c.restore()

        // Dos arcos de acento (rojo al escuchar)
        rect.set(cx - r * 0.86f, cy - r * 0.86f, cx + r * 0.86f, cy + r * 0.86f)
        trazo.color = acento
        trazo.alpha = 235
        trazo.strokeWidth = r * 0.045f
        c.drawArc(rect, ang1, 70f, false, trazo)
        c.drawArc(rect, ang1 + 180f, 70f, false, trazo)

        // Anillo azul con tres huecos, gira al revés
        rect.set(cx - r * 0.72f, cy - r * 0.72f, cx + r * 0.72f, cy + r * 0.72f)
        trazo.color = AZUL
        trazo.alpha = 220
        trazo.strokeWidth = r * 0.035f
        for (k in 0 until 3) c.drawArc(rect, ang2 + k * 120f, 85f, false, trazo)

        // Anillo interior y diez segmentos
        trazo.color = AZUL_CLARO
        trazo.alpha = 255
        trazo.strokeWidth = r * 0.03f
        c.drawCircle(cx, cy, r * 0.46f, trazo)
        trazo.color = AZUL
        trazo.strokeWidth = r * 0.05f
        c.save()
        c.rotate(ang1 * 0.15f, cx, cy)
        for (i in 0 until 10) {
            c.drawLine(cx, cy - r * 0.40f, cx, cy - r * 0.30f, trazo)
            c.rotate(36f, cx, cy)
        }
        c.restore()

        // Triángulo central
        tri.reset()
        val rt = r * 0.24f
        for (i in 0 until 3) {
            val a = (-90.0 + i * 120.0) * PI / 180.0
            val x = cx + rt * cos(a).toFloat()
            val y = cy + rt * sin(a).toFloat()
            if (i == 0) tri.moveTo(x, y) else tri.lineTo(x, y)
        }
        tri.close()
        relleno.color = AZUL_CLARO
        relleno.alpha = 90
        c.drawPath(tri, relleno)
        trazo.color = Color.WHITE
        trazo.alpha = 235
        trazo.strokeWidth = r * 0.02f
        trazo.strokeJoin = Paint.Join.ROUND
        c.drawPath(tri, trazo)
        relleno.color = Color.WHITE
        relleno.alpha = 255
        c.drawCircle(cx, cy, r * 0.05f, relleno)

        c.restore()
    }
}
