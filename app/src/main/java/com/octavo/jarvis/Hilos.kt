package com.octavo.jarvis

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Reparto del trabajo de Jarvis según su complejidad:
 *  - Pantalla y voz: hilo principal (prioridad normal).
 *  - Pensar (el modelo): proceso aparte con hilos fijados por Engine/Cpu (núcleos rápidos para leer el prompt).
 *  - Tareas de fondo (agente, misiones, crear documentos) y red: prioridad BACKGROUND. Android las
 *    agrupa en los núcleos eficientes y no le quitan CPU a la pantalla ni al modelo.
 * Los hilos son daemon y los grupos de red se reutilizan (antes se creaba y destruía uno en cada búsqueda).
 */
object Hilos {
    private class Fabrica(private val nombre: String) : ThreadFactory {
        private val n = AtomicInteger(0)
        override fun newThread(r: Runnable): Thread {
            val t = Thread({
                try {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                } catch (_: Exception) {
                }
                r.run()
            }, nombre + "-" + n.incrementAndGet())
            t.isDaemon = true
            return t
        }
    }

    /** Un solo hilo de fondo con prioridad baja (cola ordenada). */
    fun unico(nombre: String): ExecutorService = Executors.newSingleThreadExecutor(Fabrica(nombre))

    /** Grupo compartido para descargas y búsquedas en paralelo (máx. 16 a la vez; los ociosos se cierran a los 20 s). */
    val red: ExecutorService by lazy {
        val p = ThreadPoolExecutor(16, 16, 20L, TimeUnit.SECONDS, LinkedBlockingQueue<Runnable>(), Fabrica("jarvis-red"))
        p.allowCoreThreadTimeOut(true)
        p
    }
}
