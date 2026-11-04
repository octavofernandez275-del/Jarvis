package com.octavo.jarvis

import java.io.File

/**
 * Lee cómo es el procesador para repartir los hilos del modelo con criterio:
 * los núcleos más rápidos primero (p. ej. los 2 Cortex-A75 de un Unisoc T7200 antes que los 6 A55)
 * y dejando libres algunos para Android, la voz y la pantalla, para no saturar el teléfono.
 */
object Cpu {
    class Nucleos(val mascara: String, val cantidad: Int, val total: Int)

    private fun leer(ruta: String): Int? = try {
        File(ruta).readText().trim().toInt()
    } catch (e: Exception) {
        null
    }

    /** "Potencia" de cada núcleo (índice = número de núcleo), o null si el kernel no la informa. */
    private fun capacidades(): List<Int>? {
        val n = Runtime.getRuntime().availableProcessors()
        if (n < 2 || n > 62) return null
        // Primero la capacidad que declara el kernel; si no existe, la frecuencia máxima
        var valores = (0 until n).map { leer("/sys/devices/system/cpu/cpu$it/cpu_capacity") }
        if (valores.any { it == null }) {
            valores = (0 until n).map { leer("/sys/devices/system/cpu/cpu$it/cpufreq/cpuinfo_max_freq") }
        }
        if (valores.any { it == null }) return null
        return valores.map { it!! }
    }

    /**
     * Reparto de hilos según el procesador.
     *  - generacion: hilos para escribir la respuesta (limitada por memoria, no por cálculo: más hilos no la aceleran
     *    y solo suman calor). Los núcleos rápidos más 2 eficientes (p. ej. 2 A75 + 2 A55 = 4 de 8).
     *  - lectura: hilos para procesar el prompt (cálculo puro, y dura poco): todos menos los núcleos que se dejan libres.
     */
    class Perfil(val total: Int, val grandes: Int, val generacion: Int, val lectura: Int)

    private val perfilCalculado: Perfil by lazy { calcularPerfil() }

    fun perfil(): Perfil = perfilCalculado

    private fun calcularPerfil(): Perfil {
        val total = Runtime.getRuntime().availableProcessors()
        val tope = maxOf(1, total - Ajustes.NUCLEOS_LIBRES)
        val v = capacidades()
        if (v == null || v.isEmpty()) {
            // El kernel no informa los núcleos: se usa la mitad para generar
            return Perfil(total, 0, maxOf(1, minOf(tope, total / 2)), tope)
        }
        val max = v.maxOrNull() ?: 1
        val grandes = v.count { it.toLong() * 100 >= max.toLong() * 70 }   // núcleos con ≥70% de la potencia del más rápido
        val generacion = when {
            grandes >= total -> minOf(tope, 4)             // todos iguales: 4 hilos bastan para generar
            grandes >= 4 -> minOf(tope, grandes)           // 4+4: solo los rápidos
            else -> minOf(tope, grandes + 2)               // 2+6: los 2 rápidos y 2 eficientes
        }
        return Perfil(total, grandes, maxOf(1, generacion), tope)
    }

    private fun aHex(mascara: Long) = "0x" + java.lang.Long.toHexString(mascara).uppercase()

    /**
     * Máscara (hexadecimal) con los `hilos` núcleos más potentes. Entre núcleos iguales se prefieren los de
     * número más alto, porque el núcleo 0 suele atender las interrupciones del sistema.
     * Devuelve null si no se puede leer el procesador o si se usarían todos los núcleos (no hay nada que fijar).
     */
    fun mascara(hilos: Int): String? {
        val v = capacidades() ?: return null
        val n = v.size
        if (hilos < 1 || hilos >= n) return null
        val elegidos = (0 until n)
            .sortedWith(compareByDescending<Int> { v[it] }.thenByDescending { it })
            .take(hilos)
        var m = 0L
        for (i in elegidos) m = m or (1L shl i)
        return aHex(m)
    }

    /** Solo los núcleos más rápidos (por ejemplo 2 de 8). Null si todos son iguales o no se puede leer. */
    fun rapidos(): Nucleos? {
        val v = capacidades() ?: return null
        val max = v.maxOrNull() ?: return null
        var m = 0L
        var cantidad = 0
        v.forEachIndexed { i, x ->
            if (x == max) {
                m = m or (1L shl i)
                cantidad++
            }
        }
        if (cantidad == v.size) return null
        return Nucleos(aHex(m), cantidad, v.size)
    }
}
