package com.octavo.jarvis

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock

/**
 * Vigila el calor y el ahorro de energía del teléfono para no forzar el procesador cuando está caliente.
 * - Android 10+: usa el estado térmico oficial del sistema (PowerManager).
 * - Siempre: lee la temperatura de la batería (sirve también en Android 8 y 9).
 * El resultado se guarda 10 s para que consultarlo sea gratis.
 */
object Termico {
    const val NORMAL = 0
    const val TIBIO = 1
    const val CALIENTE = 2
    const val CRITICO = 3

    private const val VIGENCIA_MS = 10_000L

    @Volatile private var app: Context? = null
    @Volatile private var ultimoMs = -VIGENCIA_MS
    @Volatile private var ultimoNivel = NORMAL

    fun iniciar(ctx: Context) {
        app = ctx.applicationContext
    }

    /** Temperatura de la batería en °C (0 si no se puede leer). */
    fun temperaturaBateria(ctx: Context): Float {
        return try {
            val i = ctx.applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?: return 0f
            i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
        } catch (e: Exception) {
            0f
        }
    }

    /** Modo ahorro de batería de Android activado. */
    fun ahorroEnergia(ctx: Context): Boolean = try {
        (ctx.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode
    } catch (e: Exception) {
        false
    }

    /** NORMAL, TIBIO, CALIENTE o CRITICO. */
    fun nivel(ctx: Context): Int {
        val ahora = SystemClock.elapsedRealtime()
        if (ahora - ultimoMs < VIGENCIA_MS) return ultimoNivel
        var n = NORMAL
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val pm = ctx.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
                val estado = pm.currentThermalStatus
                n = when {
                    estado <= PowerManager.THERMAL_STATUS_NONE -> NORMAL
                    estado == PowerManager.THERMAL_STATUS_LIGHT -> TIBIO
                    estado == PowerManager.THERMAL_STATUS_MODERATE -> CALIENTE
                    else -> CRITICO
                }
            }
        } catch (_: Exception) {
        }
        val t = temperaturaBateria(ctx)
        if (t > 0f) {
            val porBateria = when {
                t >= 43f -> CRITICO
                t >= 40.5f -> CALIENTE
                t >= 38f -> TIBIO
                else -> NORMAL
            }
            n = maxOf(n, porBateria)
        }
        ultimoNivel = n
        ultimoMs = ahora
        return n
    }

    /** El teléfono está en condiciones de hacer trabajo opcional en segundo plano. */
    fun permiteFondo(ctx: Context): Boolean = nivel(ctx) < CALIENTE && !ahorroEnergia(ctx)

    /** Reduce el largo máximo de una respuesta cuando el teléfono está muy caliente (menos CPU sostenida). */
    fun limiteTokens(base: Int): Int {
        val c = app ?: return base
        return base   // sin tope: el calor se maneja con pausas y menos hilos, no recortando respuestas
    }

    /**
     * Descanso antes de pedirle algo al modelo desde una tarea de fondo: una pausa corta siempre
     * (el procesador respira entre peticiones) y, si el teléfono está crítico, espera a que se enfríe (hasta 10 min).
     * `revisar` se llama en cada espera por si la tarea fue cancelada. BLOQUEA: llamar desde un hilo de fondo.
     */
    fun esperarEnfriar(ctx: Context, revisar: () -> Unit) {
        var vueltas = 0
        while (nivel(ctx) >= CRITICO && vueltas < 120) {
            revisar()
            Thread.sleep(5000)
            vueltas++
        }
        val pausa = when (nivel(ctx)) {
            CALIENTE, CRITICO -> 4000L
            TIBIO -> 1500L
            else -> 300L
        }
        Thread.sleep(pausa)
    }

    fun descripcion(ctx: Context): String {
        val t = temperaturaBateria(ctx)
        val estado = when (nivel(ctx)) {
            NORMAL -> "normal"
            TIBIO -> "tibio"
            CALIENTE -> "caliente"
            else -> "muy caliente"
        }
        val grados = if (t > 0f) "batería a %.1f °C, ".format(t) else ""
        return grados + "estado $estado" + (if (ahorroEnergia(ctx)) ", ahorro de energía activo" else "")
    }
}
