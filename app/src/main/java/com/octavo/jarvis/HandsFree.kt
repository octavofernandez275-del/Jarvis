package com.octavo.jarvis

import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * Modo manos libres por TURNOS: escucha -> responde -> vuelve a escuchar.
 * - No escucha mientras Jarvis habla (así no se oye a sí mismo).
 * - Para cuidar la batería y la privacidad, se duerme solo tras MAX_SILENCIOS turnos sin que le hables.
 * - Se cierra con frases como "adiós", "chau" o "desactiva manos libres".
 * Todo se usa desde el hilo principal.
 *
 * ESCUCHA ACTIVA (pulsación larga en el botón de manos libres): el reconocedor queda siempre escuchando y
 * solo reacciona si dices "Oye Jarvis" (o "Jarvis"), o dentro de 25 s de la última charla. El micrófono
 * se APAGA mientras Jarvis piensa o habla (así no se oye a sí mismo por el parlante) y se reactiva solo al
 * terminar. Para callarlo mientras habla, toca la pantalla/botón. Cuesta batería (usa el reconocedor de
 * Android en bucle, no un detector local de palabra clave).
 */
object HandsFree {
    private const val MAX_SILENCIOS = 2
    private const val ESPERA_ESCUCHAR_MS = 400L   // pequeña pausa tras hablar, para no captar el final de la voz
    private const val ESPERA_TRAS_VOZ_VIGIA_MS = 1000L   // en escucha activa: más margen para que muera el eco del parlante
    private var finHabla = 0L

    private val rxCierre = Regex(
        """^(?:adios|chau|chao|hasta luego|nada mas|eso es todo|desactiva(?: el)? manos libres|termina|detente|duermete|apagate)\b"""
    )

    private val main = Handler(Looper.getMainLooper())

    @Volatile var activo = false
        private set

    /** Escucha activa: siempre atento + palabra de activación + interrumpir hablando. */
    @Volatile var vigia = false
        private set
    /** La pulsación larga lo pone en true antes de activar (el permiso puede pedirse en medio). */
    @Volatile var vigiaPedida = false
    private const val VENTANA_CONVERSACION_MS = 25_000L
    private const val MAX_ERRORES_VIGIA = 6
    /** El micrófono ya se apaga mientras Jarvis habla; cortar por voz queda desactivado (evita el eco). */
    private const val permitirCortarPorVoz = false
    private var ultimaInteraccion = 0L
    private var interrumpidoPorVoz = false
    private var errores = 0
    private val rxDespertar = Regex("""\b(?:jarvis|yarvis|jarvi|yarvi)\b""")
    private val rxParar = Regex("""^\s*(?:(?:oye|hey|ok)\s+)?(?:jarvis|yarvis)?[\s,]*(?:para|basta|espera|callate|silencio|stop|alto|jarvis|yarvis)\b""")
    private val rxQuitarNombre = Regex("""(?i)^\s*(?:(?:oye|hey|hola|ok)[\s,]+)?(?:jarvis|yarvis)[\s,.:!¿?]*""")
    private val rxQuitarCorte = Regex("""(?i)^\s*(?:(?:oye|hey|ok)[\s,]+)?(?:(?:jarvis|yarvis)[\s,]*)?(?:pará|para|basta|esperá|espera|cállate|callate|silencio|stop|alto)?[\s,.:!¿?]*""")
    private val rxEscuchaOff = Regex("""\b(?:desactiva|apaga|quita|termina|deja)\w*(?: la)? escucha activa\b""")
    private val rxEscuchaOn = Regex("""\bescucha activa\b""")

    private var silencios = 0
    private var cerrando = false
    private val arranque = Runnable { escucharAhora() }

    fun activar(ctx: Context) {
        if (activo) return
        vigia = vigiaPedida
        vigiaPedida = false
        errores = 0
        interrumpidoPorVoz = false
        if (!Assistant.listo) { Assistant.toast("Espera a que Jarvis termine de cargar."); return }
        activo = true
        silencios = 0
        cerrando = false
        EngineManager.retener()
        Assistant.renovarSueno()
        Assistant.ui?.manosLibres(true)
        // Sube el servicio al tipo "micrófono" (solo se permite con la app visible)
        if (!JarvisService.fijarMicrofono(ctx.applicationContext, true)) return
        Assistant.decir(if (vigia) "Escucha activa lista. Dime «Oye Jarvis» cuando me necesites." else "Manos libres activado. Te escucho.")   // al terminar de hablar empieza a escuchar
    }

    fun desactivar(mensaje: String?) {
        if (!activo) return
        activo = false
        vigia = false
        vigiaPedida = false
        cerrando = false
        main.removeCallbacks(arranque)
        Assistant.voiceIn.cancel()
        EngineManager.soltar()
        Assistant.renovarSueno()                       // suelta el wakelock si nadie más lo necesita
        JarvisService.fijarMicrofono(Assistant.contexto(), false)   // el servicio vuelve al tipo base
        Assistant.ui?.manosLibres(false)
        if (mensaje != null) Assistant.decir(mensaje)
    }

    // ---------- Eventos que llegan desde Assistant ----------

    /** Jarvis terminó de hablar: tocar volver a escuchar (o cerrar). */
    fun alTerminarDeHablar() {
        if (!activo) return
        if (cerrando) { desactivar(null); return }
        ultimaInteraccion = System.currentTimeMillis()   // sigue la charla sin repetir "Jarvis" durante un rato
        finHabla = ultimaInteraccion
        escuchar(if (vigia) ESPERA_TRAS_VOZ_VIGIA_MS else ESPERA_ESCUCHAR_MS)
    }

    /**
     * Jarvis va a hablar: se APAGA el micrófono hasta que termine, para que no se capte a sí mismo
     * (el parlante y el micrófono son el mismo teléfono). Se reactiva solo al terminar de hablar.
     */
    fun alEmpezarAHablar() {
        if (!activo) return
        main.removeCallbacks(arranque)
        if (Assistant.voiceIn.isListening) Assistant.voiceIn.cancel()
        Assistant.ui?.parcial("")   // limpia cualquier texto parcial captado
    }

    /**
     * Llegó texto dictado. Devuelve lo que hay que mandar al modelo, o null si no se manda
     * (frase de cierre, eco de Jarvis, o algo que no iba dirigido a él en escucha activa).
     */
    fun filtrar(texto: String): String? {
        if (!activo) return texto
        val f = Parsers.fold(texto.trim())
        if (!vigia) {
            silencios = 0
            if (rxCierre.containsMatchIn(f)) { cerrando = true; Assistant.decir("Hasta luego."); return null }
            return texto
        }
        errores = 0
        silencios = 0
        val cortado = interrumpidoPorVoz
        interrumpidoPorVoz = false
        // Si Jarvis sigue hablando o pensando y no hubo palabra de corte, lo captado era su propia voz o ruido
        if (!cortado && (Assistant.voiceOut.isSpeaking || Assistant.ocupado)) return null
        if (rxEscuchaOff.containsMatchIn(f)) {
            vigia = false
            Assistant.ui?.manosLibres(true)
            Assistant.decir("Escucha activa desactivada. Sigo en manos libres.")
            return null
        }
        val conNombre = rxDespertar.containsMatchIn(f)
        val enVentana = System.currentTimeMillis() - ultimaInteraccion < VENTANA_CONVERSACION_MS
        if (!conNombre && !enVentana && !cortado) { escuchar(); return null }   // no era para mí
        if (rxCierre.containsMatchIn(f.replace(rxDespertar, "").trim())) {
            cerrando = true; Assistant.decir("Hasta luego."); return null
        }
        var limpio = texto.replaceFirst(rxQuitarNombre, "").trim()
        if (cortado) limpio = texto.replaceFirst(rxQuitarCorte, "").trim()
        ultimaInteraccion = System.currentTimeMillis()
        if (limpio.isEmpty()) {
            if (cortado) escuchar() else Assistant.decir("Dime.")   // "Dime." al terminar vuelve a escuchar solo
            return null
        }
        return limpio
    }

    /** Texto parcial mientras el usuario habla: en escucha activa, una palabra de corte calla a Jarvis. */
    fun alParcial(parcial: String) {
        if (!activo || !vigia || interrumpidoPorVoz) return
        if (!Assistant.voiceOut.isSpeaking && !Assistant.ocupado) return
        if (!permitirCortarPorVoz) return
        if (rxParar.containsMatchIn(Parsers.fold(parcial))) {
            interrumpidoPorVoz = true
            Assistant.interrupt()
        }
    }

    /** True si el reconocedor está en bucle y no hay que cortar la voz de Jarvis por cualquier sonido. */
    val ignoraInicioDeVoz: Boolean get() = activo && vigia

    /** El reconocedor terminó (con o sin resultado): en escucha activa se vuelve a armar solo. */
    fun alTerminarEscucha() {
        if (activo && vigia && !cerrando) escuchar(600L)
    }

    /** Pulsación larga con manos libres ya activo: enciende o apaga la escucha activa. */
    fun cambiarVigia(on: Boolean) {
        if (!activo) return
        vigia = on
        errores = 0
        Assistant.ui?.manosLibres(true)   // repinta el botón (manos libres / escucha activa)
        Assistant.decir(if (on) "Escucha activa lista. Dime «Oye Jarvis» cuando me necesites." else "Escucha activa desactivada. Sigo en manos libres.")
    }

    /** No hubo voz (o falló el reconocedor): tras varios turnos seguidos, se duerme (salvo en escucha activa). */
    fun alSilencio(error: Boolean = false) {
        if (!activo) return
        if (vigia) {
            if (!error) { errores = 0; escuchar(); return }
            errores++
            if (errores >= MAX_ERRORES_VIGIA) {
                desactivar("La escucha activa no pudo seguir. Revisa el micrófono y el reconocimiento de voz.")
                return
            }
            escuchar(2000L * minOf(errores, 3))
            return
        }
        silencios++
        if (silencios >= MAX_SILENCIOS) {
            desactivar("Me duermo para ahorrar batería. Toca manos libres para volver a hablarme.")
        } else {
            escuchar()
        }
    }

    /** El usuario cortó la voz o la respuesta (botón, notificación): vuelve a escuchar. */
    fun alInterrumpir() {
        if (activo && !Assistant.voiceIn.isListening) escuchar()
    }

    // ---------- Interno ----------

    private fun escuchar(espera: Long = ESPERA_ESCUCHAR_MS) {
        Assistant.renovarSueno()   // renueva el límite de seguridad del wakelock
        main.removeCallbacks(arranque)
        main.postDelayed(arranque, espera)
    }

    private fun escucharAhora() {
        if (!activo || cerrando) return
        val a = Assistant
        if (a.voiceIn.isListening) return
        // Nunca se escucha mientras Jarvis piensa o habla: el micrófono captaría su propia voz.
        if (a.ocupado || a.voiceOut.isSpeaking) {
            if (vigia) escuchar(700L)   // escucha activa: reintenta hasta que termine
            return
        }
        // Margen tras la última palabra hablada (eco / reverberación del parlante)
        if (vigia) {
            val falta = ESPERA_TRAS_VOZ_VIGIA_MS - (System.currentTimeMillis() - finHabla)
            if (falta > 0) { escuchar(falta); return }
        }
        a.voiceIn.start()
    }
}
