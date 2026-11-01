package com.octavo.jarvis

import android.content.Context

/**
 * Ajustes de rendimiento del motor que se cambian con comandos ("hilos 4"), sin recompilar.
 * Por defecto: 6 hilos con PRIORIDAD BAJA (la pantalla y el teclado siempre tienen preferencia) y sin fijar núcleos.
 * Los lee Engine.start() al arrancar el servidor.
 */
object Ajustes {
    private const val PREFS = "jarvis"
    const val HILOS_POR_DEFECTO = 6

    /** Núcleos que se dejan libres para no saturar el teléfono. */
    const val NUCLEOS_LIBRES = 2

    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Hilos de CPU para generar texto (-t). */
    fun hilos(ctx: Context): Int = p(ctx).getInt("hilos", HILOS_POR_DEFECTO)

    /** Hilos para leer el prompt (-tb). 0 = los mismos que para generar. */
    fun hilosLectura(ctx: Context): Int = p(ctx).getInt("hilos_lectura", 0)

    /**
     * false (por defecto): Jarvis escribe TODA la respuesta lo más rápido posible y recién entonces la lee en voz alta.
     * true: lee frase por frase mientras escribe (la voz compite por CPU con el modelo y puede cortarse).
     */
    fun vozEnVivo(ctx: Context): Boolean = p(ctx).getBoolean("voz_en_vivo", false)

    fun fijarVozEnVivo(ctx: Context, v: Boolean) { p(ctx).edit().putBoolean("voz_en_vivo", v).apply() }

    /** Minutos sin uso antes de apagar el motor para liberar RAM. 0 (por defecto) = no apagarlo nunca: responde al instante. */
    fun minutosInactivo(ctx: Context): Int = p(ctx).getInt("idle_min", 0)

    fun fijarMinutosInactivo(ctx: Context, n: Int) { p(ctx).edit().putInt("idle_min", n).apply() }

    /** Máximo de hilos sin saturar: todos los núcleos menos NUCLEOS_LIBRES (6 en un teléfono de 8 núcleos). */
    fun maxHilos(): Int = maxOf(1, Runtime.getRuntime().availableProcessors() - NUCLEOS_LIBRES)

    /** Hilos que realmente se usan al generar (nunca más de maxHilos). */
    fun hilosEfectivos(ctx: Context): Int = minOf(hilos(ctx), maxHilos())

    /** Hilos que realmente se usan al leer el prompt; 0 = los mismos que al generar. */
    fun hilosLecturaEfectivos(ctx: Context): Int {
        val t = hilosLectura(ctx)
        return if (t > 0) minOf(t, maxHilos()) else 0
    }

    /** false (por defecto): Android reparte los hilos. true: se fijan a los núcleos más rápidos (puede dejar lenta la pantalla). */
    fun nucleosFijos(ctx: Context): Boolean = p(ctx).getBoolean("nucleos_fijos", false)

    /** true (por defecto): el servidor del modelo trabaja con prioridad baja, así el teclado y la pantalla van fluidos. */
    fun prioridadBaja(ctx: Context): Boolean = p(ctx).getBoolean("prioridad_baja", true)

    fun fijarPrioridadBaja(ctx: Context, v: Boolean) { p(ctx).edit().putBoolean("prioridad_baja", v).apply() }

    fun fijarNucleosFijos(ctx: Context, v: Boolean) { p(ctx).edit().putBoolean("nucleos_fijos", v).apply() }

    fun fijarHilos(ctx: Context, n: Int) { p(ctx).edit().putInt("hilos", n).apply() }
    fun fijarHilosLectura(ctx: Context, n: Int) { p(ctx).edit().putInt("hilos_lectura", n).apply() }
}
