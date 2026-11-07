package com.octavo.jarvis

import android.content.Context

/**
 * Ajustes de rendimiento del motor que se cambian con comandos ("hilos 4"), sin recompilar.
 * Por defecto: reparto AUTOMÁTICO según el procesador (ver Cpu.perfil: pocos hilos para escribir la respuesta y más para
 * leer el prompt), PRIORIDAD BAJA (la pantalla y el teclado siempre tienen preferencia), sin fijar núcleos, y con
 * menos hilos si el teléfono se calienta (ver Termico). Si eliges "hilos N" a mano, se respeta tal cual.
 * Los lee Engine.start() al arrancar el servidor.
 */
object Ajustes {
    private const val PREFS = "jarvis"
    const val HILOS_POR_DEFECTO = 6

    /** Núcleos que se dejan libres para no saturar el teléfono. */
    const val NUCLEOS_LIBRES = 2

    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Hilos de CPU para generar texto (-t). */
    fun hilos(ctx: Context): Int {
        val pr = p(ctx)
        // Si nunca se eligió a mano, se adapta al procesador (en 2 rápidos + 6 eficientes: 4 hilos para generar)
        return if (pr.contains("hilos")) pr.getInt("hilos", HILOS_POR_DEFECTO) else Cpu.perfil().generacion
    }

    /** Hilos para leer el prompt (-tb). 0 = los mismos que para generar. */
    fun hilosLectura(ctx: Context): Int {
        val pr = p(ctx)
        // Por defecto, leer el prompt usa todos los núcleos menos los libres: es cálculo puro y termina pronto
        return if (pr.contains("hilos_lectura")) pr.getInt("hilos_lectura", 0) else Cpu.perfil().lectura
    }

    /** Vuelve al reparto automático de hilos (borra lo elegido a mano). */
    fun hilosAutomaticos(ctx: Context) { p(ctx).edit().remove("hilos").remove("hilos_lectura").apply() }

    /** ¿Los hilos son automáticos (no elegidos a mano)? Solo entonces se reducen con el calor. */
    private fun hilosAuto(ctx: Context): Boolean = !p(ctx).contains("hilos")

    /** Hilos que se restan por calor o ahorro de energía (solo en modo automático). */
    private fun reduccionTermica(ctx: Context): Int {
        if (!hilosAuto(ctx)) return 0
        val n = Termico.nivel(ctx)
        return when {
            n >= Termico.CRITICO -> 2
            n >= Termico.CALIENTE || Termico.ahorroEnergia(ctx) -> 1
            else -> 0
        }
    }

    /**
     * false (por defecto): Jarvis genera TODA la respuesta, la muestra completa y recién entonces la lee en voz alta.
     * true: el texto aparece y se lee frase por frase mientras se genera (la voz compite por CPU con el modelo).
     */
    fun vozEnVivo(ctx: Context): Boolean = p(ctx).getBoolean("voz_en_vivo", false)

    fun fijarVozEnVivo(ctx: Context, v: Boolean) { p(ctx).edit().putBoolean("voz_en_vivo", v).apply() }

    /** Minutos sin uso antes de apagar el motor para liberar RAM. 0 (por defecto) = no apagarlo nunca: responde al instante. */
    fun minutosInactivo(ctx: Context): Int = p(ctx).getInt("idle_min", 0)

    fun fijarMinutosInactivo(ctx: Context, n: Int) { p(ctx).edit().putInt("idle_min", n).apply() }

    /** Máximo de hilos sin saturar: todos los núcleos menos NUCLEOS_LIBRES (6 en un teléfono de 8 núcleos). */
    fun maxHilos(): Int = maxOf(1, Runtime.getRuntime().availableProcessors() - NUCLEOS_LIBRES)

    /** Aprender solo datos del usuario (nombre, dónde vive, gustos…) de lo que cuenta en la charla. Por defecto sí. */
    fun memoriaAuto(ctx: Context): Boolean = p(ctx).getBoolean("memoria_auto", true)
    fun fijarMemoriaAuto(ctx: Context, v: Boolean) { p(ctx).edit().putBoolean("memoria_auto", v).apply() }

    /** Contexto fijado a mano (0 = automático según la RAM). */
    /** Modelo de lenguaje elegido: id del catálogo ("pequeno", "mediano", "grande") o una dirección de descarga. */
    fun modeloElegido(ctx: Context): String = p(ctx).getString("modelo", "pequeno") ?: "pequeno"
    fun fijarModelo(ctx: Context, v: String) { p(ctx).edit().putString("modelo", v).apply() }
    fun contextoManual(ctx: Context): Int = p(ctx).getInt("contexto_manual", 0)
    fun fijarContextoManual(ctx: Context, v: Int) { p(ctx).edit().putInt("contexto_manual", v).apply() }

    /** Hilos que realmente se usan al generar (nunca más de maxHilos). */
    fun hilosEfectivos(ctx: Context): Int = maxOf(1, minOf(hilos(ctx), maxHilos()) - reduccionTermica(ctx))

    /** Hilos que realmente se usan al leer el prompt; 0 = los mismos que al generar. */
    fun hilosLecturaEfectivos(ctx: Context): Int {
        val t = hilosLectura(ctx)
        return if (t > 0) maxOf(1, minOf(t, maxHilos()) - reduccionTermica(ctx)) else 0
    }

    /** false (por defecto): Android reparte los hilos. true: se fijan a los núcleos más rápidos (puede dejar lenta la pantalla). */
    fun nucleosFijos(ctx: Context): Boolean = p(ctx).getBoolean("nucleos_fijos", false)

    /** true (por defecto): el servidor del modelo trabaja con prioridad baja, así el teclado y la pantalla van fluidos. */
    fun prioridadBaja(ctx: Context): Boolean = p(ctx).getBoolean("prioridad_baja", true)

    fun fijarPrioridadBaja(ctx: Context, v: Boolean) { p(ctx).edit().putBoolean("prioridad_baja", v).apply() }

    fun fijarNucleosFijos(ctx: Context, v: Boolean) { p(ctx).edit().putBoolean("nucleos_fijos", v).apply() }

    /** true (por defecto): al encender el teléfono o actualizar la app, Jarvis carga el motor solo para estar listo siempre. */
    fun arrancarAlEncender(ctx: Context): Boolean = p(ctx).getBoolean("arrancar_al_encender", true)

    fun fijarArrancarAlEncender(ctx: Context, v: Boolean) { p(ctx).edit().putBoolean("arrancar_al_encender", v).apply() }

    /** Nombre interno de la voz elegida (vacío = la predeterminada del teléfono). */
    fun vozNombre(ctx: Context): String = p(ctx).getString("voz_nombre", "") ?: ""

    fun fijarVozNombre(ctx: Context, n: String) { p(ctx).edit().putString("voz_nombre", n).apply() }

    /** Velocidad de la voz (1.0 = normal). */
    fun velocidadVoz(ctx: Context): Float = p(ctx).getFloat("voz_velocidad", 0.94f)

    fun fijarVelocidadVoz(ctx: Context, v: Float) { p(ctx).edit().putFloat("voz_velocidad", v).apply() }

    /** Tono de la voz (1.0 = normal; menos = más grave). */
    fun tonoVoz(ctx: Context): Float = p(ctx).getFloat("voz_tono", 0.86f)

    fun fijarTonoVoz(ctx: Context, v: Float) { p(ctx).edit().putFloat("voz_tono", v).apply() }

    /** true (por defecto): el reactor de la pantalla está animado. false: queda quieto (ahorra batería). */
    fun animacion(ctx: Context): Boolean = p(ctx).getBoolean("animacion", true)

    fun fijarAnimacion(ctx: Context, v: Boolean) { p(ctx).edit().putBoolean("animacion", v).apply() }

    /** Piloto automático: Jarvis toma solo las tareas pendientes (si hay batería y el chat está libre). */
    fun piloto(ctx: Context): Boolean = p(ctx).getBoolean("piloto", false)

    fun fijarPiloto(ctx: Context, v: Boolean) { p(ctx).edit().putBoolean("piloto", v).apply() }

    /** Curiosidad: al terminar, Jarvis propone y (con el piloto) investiga temas relacionados, hasta un cupo diario. */
    /** Buscar en internet antes de contestar preguntas de hechos o actualidad en el chat (por defecto sí). */
    fun busquedaEnChat(ctx: Context): Boolean = p(ctx).getBoolean("busqueda_chat", true)
    fun fijarBusquedaEnChat(ctx: Context, v: Boolean) { p(ctx).edit().putBoolean("busqueda_chat", v).apply() }
    fun curiosidad(ctx: Context): Boolean = p(ctx).getBoolean("curiosidad", false)

    fun fijarCuriosidad(ctx: Context, v: Boolean) { p(ctx).edit().putBoolean("curiosidad", v).apply() }

    const val MAX_SEGUIMIENTOS_DIA = 3

    private fun hoy() = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())

    fun seguimientosRestantes(ctx: Context): Int {
        val pr = p(ctx)
        return if (pr.getString("seg_dia", "") != hoy()) MAX_SEGUIMIENTOS_DIA
        else MAX_SEGUIMIENTOS_DIA - pr.getInt("seg_n", 0)
    }

    fun gastarSeguimiento(ctx: Context) {
        val pr = p(ctx)
        val n = if (pr.getString("seg_dia", "") == hoy()) pr.getInt("seg_n", 0) + 1 else 1
        pr.edit().putString("seg_dia", hoy()).putInt("seg_n", n).apply()
    }

    fun fijarHilos(ctx: Context, n: Int) { p(ctx).edit().putInt("hilos", n).apply() }
    fun fijarHilosLectura(ctx: Context, n: Int) { p(ctx).edit().putInt("hilos_lectura", n).apply() }
}
