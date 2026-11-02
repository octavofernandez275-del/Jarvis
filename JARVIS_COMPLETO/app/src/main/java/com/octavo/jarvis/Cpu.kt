package com.octavo.jarvis

import java.io.File

/**
 * Detecta los núcleos "rápidos" del procesador (por ejemplo los 2 Cortex-A75 de un Unisoc T7200,
 * frente a los 6 Cortex-A55). Fijar los hilos del modelo a esos núcleos evita que los lentos frenen al resto.
 */
object Cpu {
    class Nucleos(val mascara: String, val cantidad: Int, val total: Int)

    private fun leer(ruta: String): Int? = try {
        File(ruta).readText().trim().toInt()
    } catch (e: Exception) {
        null
    }

    /** Devuelve la máscara (hexadecimal) de los núcleos más rápidos, o null si no se pueden distinguir. */
    fun rapidos(): Nucleos? {
        val n = Runtime.getRuntime().availableProcessors()
        if (n < 2 || n > 62) return null
        // Primero la "capacidad" que declara el kernel; si no existe, la frecuencia máxima
        var valores = (0 until n).map { leer("/sys/devices/system/cpu/cpu$it/cpu_capacity") }
        if (valores.any { it == null }) {
            valores = (0 until n).map { leer("/sys/devices/system/cpu/cpu$it/cpufreq/cpuinfo_max_freq") }
        }
        if (valores.any { it == null }) return null
        val v = valores.map { it!! }
        val max = v.maxOrNull() ?: return null
        var mascara = 0L
        var cantidad = 0
        v.forEachIndexed { i, x ->
            if (x == max) {
                mascara = mascara or (1L shl i)
                cantidad++
            }
        }
        if (cantidad == n) return null   // todos iguales: no hay nada que fijar
        return Nucleos("0x" + java.lang.Long.toHexString(mascara).uppercase(), cantidad, n)
    }
}
