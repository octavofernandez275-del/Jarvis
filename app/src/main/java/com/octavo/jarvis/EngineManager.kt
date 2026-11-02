package com.octavo.jarvis

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.net.HttpURLConnection
import java.net.URL

/**
 * Ciclo de vida del motor (llama-server) para cuidar la RAM y la batería.
 * El modelo ocupa ~2 GB: si nadie lo usa durante IDLE_APAGADO_MS, se apaga solo
 * (y con él el servicio en primer plano). Se vuelve a encender cuando hace falta.
 *
 * "Usar" el motor = retener() ... soltar(). Lo retienen: la pantalla visible,
 * una respuesta en curso y el modo manos libres.
 */
object EngineManager {
    private const val IDLE_APAGADO_MS = 3 * 60_000L

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

    /** Ya no lo necesita: si nadie más lo usa, se apagará tras IDLE_APAGADO_MS. */
    fun soltar() {
        val libre = synchronized(lockUso) {
            if (usos > 0) usos--
            usos == 0
        }
        if (libre) main.postDelayed(apagar, IDLE_APAGADO_MS)
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
