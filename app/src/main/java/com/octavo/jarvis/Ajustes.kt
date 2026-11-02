package com.octavo.jarvis

import android.content.Context

/**
 * Ajustes de rendimiento del motor que se cambian con comandos ("hilos 2"), sin recompilar.
 * Sirven para probar qué configuración es más rápida en este teléfono.
 * Los lee Engine.start() al arrancar el servidor.
 */
object Ajustes {
    private const val PREFS = "jarvis"
    const val HILOS_POR_DEFECTO = 4

    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Hilos de CPU para generar texto (-t). */
    fun hilos(ctx: Context): Int = p(ctx).getInt("hilos", HILOS_POR_DEFECTO)

    /** Hilos para leer el prompt (-tb). 0 = los mismos que para generar. */
    fun hilosLectura(ctx: Context): Int = p(ctx).getInt("hilos_lectura", 0)

    fun fijarHilos(ctx: Context, n: Int) { p(ctx).edit().putInt("hilos", n).apply() }
    fun fijarHilosLectura(ctx: Context, n: Int) { p(ctx).edit().putInt("hilos_lectura", n).apply() }
}
