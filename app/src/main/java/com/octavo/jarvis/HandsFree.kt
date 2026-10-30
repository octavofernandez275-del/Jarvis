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
 * (Una palabra de activación "Jarvis" siempre atenta necesitaría un detector local en el
 *  dispositivo; el reconocedor de Android no sirve para eso sin gastar mucha batería y datos.)
 */
object HandsFree {
    private const val MAX_SILENCIOS = 2
    private const val ESPERA_ESCUCHAR_MS = 400L   // pequeña pausa tras hablar, para no captar el final de la voz

    private val rxCierre = Regex(
        """^(?:adios|chau|chao|hasta luego|nada mas|eso es todo|desactiva(?: el)? manos libres|termina|detente|duermete|apagate)\b"""
    )

    private val main = Handler(Looper.getMainLooper())

    @Volatile var activo = false
        private set

    private var silencios = 0
    private var cerrando = false
    private val arranque = Runnable { escucharAhora() }

    fun activar(ctx: Context) {
        if (activo) return
        if (!Assistant.listo) { Assistant.toast("Espera a que Jarvis termine de cargar."); return }
        activo = true
        silencios = 0
        cerrando = false
        EngineManager.retener()
        Assistant.renovarSueno()
        Assistant.ui?.manosLibres(true)
        // Sube el servicio al tipo "micrófono" (solo se permite con la app visible)
        if (!JarvisService.fijarMicrofono(ctx.applicationContext, true)) return
        Assistant.decir("Manos libres activado. Te escucho.")   // al terminar de hablar empieza a escuchar
    }

    fun desactivar(mensaje: String?) {
        if (!activo) return
        activo = false
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
        escuchar()
    }

    /** Llegó texto dictado. Devuelve true si era una frase de cierre (no se manda al modelo). */
    fun alResultado(texto: String): Boolean {
        if (!activo) return false
        silencios = 0
        if (rxCierre.containsMatchIn(Parsers.fold(texto.trim()))) {
            cerrando = true
            Assistant.decir("Hasta luego.")
            return true
        }
        return false
    }

    /** No hubo voz (o falló el reconocedor): tras varios turnos seguidos, se duerme. */
    fun alSilencio() {
        if (!activo) return
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

    private fun escuchar() {
        Assistant.renovarSueno()   // renueva el límite de seguridad del wakelock
        main.removeCallbacks(arranque)
        main.postDelayed(arranque, ESPERA_ESCUCHAR_MS)
    }

    private fun escucharAhora() {
        if (!activo || cerrando) return
        val a = Assistant
        if (a.ocupado || a.voiceIn.isListening || a.voiceOut.isSpeaking) return
        a.voiceIn.start()
    }
}
