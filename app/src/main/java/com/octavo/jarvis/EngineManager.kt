package com.octavo.jarvis

import android.content.Context
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import java.net.HttpURLConnection
import java.net.URL

/**
 * Ciclo de vida del motor (llama-server) para cuidar la RAM y la batería.
 * El modelo ocupa ~2 GB: si nadie lo usa durante un rato, se apaga solo
 * (y con él el servicio en primer plano). Se vuelve a encender cuando hace falta.
 * Espera 5 min con batería y 20 min si está cargando.
 *
 * "Usar" el motor = retener() ... soltar(). Lo retienen: la pantalla visible,
 * una respuesta en curso y el modo manos libres.
 */
object EngineManager {
    // El arranque en frío tarda mucho más que cualquier respuesta: con el cargador se espera más
    private const val IDLE_BATERIA_MS = 5 * 60_000L
    private const val IDLE_CARGANDO_MS = 20 * 60_000L

    private val main = Handler(Looper.getMainLooper())
    private val lockUso = Any()        // contador de usos (nunca se bloquea mucho)
    private val lockArranque = Any()   // arrancar el motor (puede tardar; va aparte del contador)
    private var appCtx: Context? = null
    private var usos = 0

    private val apagar = Runnable {
        val enUso = synchronized(lockUso) { usos > 0 }
        if (!enUso) {
            Engine.stop()
            appCtx?.let { JarvisService.detener(it) }
            Assistant.estado("Motor en reposo. Se enciende solo cuando le hables.")
        }
    }

    fun iniciar(ctx: Context) { appCtx = ctx.applicationContext }

    /** Alguien necesita el motor: cancela el apagado programado. */
    fun retener() {
        synchronized(lockUso) { usos++ }
        main.removeCallbacks(apagar)
    }

    /** Ya no lo necesita: si nadie más lo usa, se apagará tras unos minutos. */
    fun soltar() {
        val libre = synchronized(lockUso) {
            if (usos > 0) usos--
            usos == 0
        }
        if (libre) main.postDelayed(apagar, if (cargando()) IDLE_CARGANDO_MS else IDLE_BATERIA_MS)
    }

    private fun cargando(): Boolean {
        val c = appCtx ?: return false
        return (c.getSystemService(Context.BATTERY_SERVICE) as BatteryManager).isCharging
    }

    /** Apaga el motor ya (botón Detener de la notificación). */
    fun apagarYa() {
        main.removeCallbacks(apagar)
        Engine.stop()
    }

    /** ¿El servidor ya responde? */
    private fun saludable(): Boolean = try {
        val c = URL("http://127.0.0.1:${Engine.PORT}/health").openConnection() as HttpURLConnection
        c.connectTimeout = 800
        c.readTimeout = 800
        c.responseCode == 200
    } catch (e: Exception) {
        false
    }

    /**
     * Deja el motor listo (lo arranca si estaba apagado o murió). BLOQUEA: llamar desde un hilo.
     * Si el puerto aún estaba ocupado por el proceso anterior, reintenta una vez.
     */
    fun asegurar(ctx: Context, estado: (String) -> Unit = {}): Boolean {
        return synchronized(lockArranque) {
            val c = ctx.applicationContext
            appCtx = c
            if (saludable()) return@synchronized true
            var listo = false
            for (intento in 1..2) {
                estado(if (intento == 1) "Despertando el motor…" else "Reintentando el motor…")
                if (intento == 2) {
                    Engine.stop()
                    Thread.sleep(2000)
                }
                Engine.start(c)
                if (Engine.waitReady()) { listo = true; break }
            }
            listo
        }
    }
}
