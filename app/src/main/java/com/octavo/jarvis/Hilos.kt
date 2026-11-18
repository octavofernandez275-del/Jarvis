package com.octavo.jarvis

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Prioridad de hilos de Jarvis: MÁXIMA y permanente (por defecto; se puede volver atrás con "prioridad baja").
 *  - Modelo (llama-server): todos sus hilos a PRIORIDAD_MAX, reaplicada mientras el proceso viva (ver Engine.priorizar).
 *  - Camino de la respuesta (búsqueda en internet, lectura de la respuesta, red): PRIORIDAD_MAX.
 *  - Tareas de fondo (agente, misiones): un escalón menos (PRIORIDAD_FONDO), para que no le quiten CPU a la respuesta.
 *  - Pantalla y voz: hilo principal, sin tocar (siempre fluida). Los hilos son daemon y los grupos de red se reutilizan.
 * Nota: en Android la prioridad negativa solo sube el turno de CPU del hilo; no sube la frecuencia del procesador.
 */
object Hilos {
    /** THREAD_PRIORITY_URGENT_DISPLAY (-8): lo más alto que se usa sin competir con el audio del sistema (-16). */
    const val PRIORIDAD_MAX = android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY
    /** THREAD_PRIORITY_DISPLAY (-4): tareas de fondo, igual de por encima de lo normal pero bajo la respuesta. */
    const val PRIORIDAD_FONDO = android.os.Process.THREAD_PRIORITY_DISPLAY

    /** Sube el hilo actual a la prioridad máxima. Seguro de llamar en cualquier momento (ignora errores). */
    fun maxima() {
        try {
            android.os.Process.setThreadPriority(PRIORIDAD_MAX)
        } catch (_: Exception) {
        }
    }

    private class Fabrica(private val nombre: String, private val prioridad: Int) : ThreadFactory {
        private val n = AtomicInteger(0)
        override fun newThread(r: Runnable): Thread {
            val t = Thread({
                try {
                    android.os.Process.setThreadPriority(prioridad)
                } catch (_: Exception) {
                }
                r.run()
            }, nombre + "-" + n.incrementAndGet())
            t.isDaemon = true
            return t
        }
    }

    /** Un solo hilo de fondo (cola ordenada), un escalón por debajo de la respuesta. */
    fun unico(nombre: String): ExecutorService = Executors.newSingleThreadExecutor(Fabrica(nombre, PRIORIDAD_FONDO))

    /** Grupo compartido para descargas y búsquedas en paralelo (máx. 16 a la vez; los ociosos se cierran a los 20 s). */
    val red: ExecutorService by lazy {
        val p = ThreadPoolExecutor(16, 16, 20L, TimeUnit.SECONDS, LinkedBlockingQueue<Runnable>(), Fabrica("jarvis-red", PRIORIDAD_MAX))
        p.allowCoreThreadTimeOut(true)
        p
    }
}
