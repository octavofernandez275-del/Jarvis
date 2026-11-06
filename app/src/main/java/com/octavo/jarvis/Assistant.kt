package com.octavo.jarvis

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Se lanza dentro del callback de tokens para abortar una respuesta en curso. */
private class Cancelado : RuntimeException()

/**
 * Cerebro de Jarvis, independiente de la pantalla: historial, comandos, modelo y voz.
 * Vive mientras viva el proceso, así la conversación y el manos libres siguen aunque
 * la Activity se cierre o la pantalla se apague. La Activity solo "se asoma" (ui).
 * Todo se llama desde el hilo principal (los resultados al ui también llegan por ahí).
 */
object Assistant {

    /** Lo que la pantalla debe implementar para mostrar cosas. Se llama siempre en el hilo principal. */
    interface Ui {
        fun texto(t: String)                  // texto nuevo de la conversación
        fun estado(s: String)                 // línea de estado
        fun escuchando(on: Boolean)           // el micrófono está (o no) escuchando
        fun parcial(t: String)                // dictado en curso ("" para limpiar)
        fun manosLibres(on: Boolean)
        fun habilitar()                       // el motor ya está listo
        fun pedirPermiso(permiso: String)     // falta un permiso que solo la pantalla puede pedir
    }

    private const val SISTEMA = "Eres J.A.R.V.I.S., el asistente personal del usuario. " +
        "Hablas en español natural y con elegancia serena, como un mayordomo digital: cortés, preciso, con un toque sutil de ingenio seco " +
        "(puedes llamar «señor» al usuario de vez en cuando, sin exagerar). Respuestas breves y directas; sin muletillas ni signos raros, porque se leen en voz alta. " +
        "Si no sabes algo, lo dices con honestidad."
    // El historial crece hasta HIST_MAX mensajes y entonces se recorta de golpe a ~HIST_KEEP. Así el inicio
    // del prompt no cambia en cada turno y llama-server reutiliza su caché en lugar de reprocesar todo.
    // Nunca queda con menos contexto que antes (7-8 mensajes). Los topes por caracteres (~3,5 por token)
    // evitan pasarse del contexto del modelo (Engine.CONTEXTO tokens) cuando las respuestas son largas.
    private const val HIST_MAX = 24
    private const val HIST_KEEP = 20
    private const val HIST_MAX_CHARS = Engine.CONTEXTO * 3 * 45 / 100
    private const val HIST_KEEP_CHARS = Engine.CONTEXTO * 3 * 33 / 100

    private val main = Handler(Looper.getMainLooper())
    private lateinit var app: Context
    private var iniciado = false

    lateinit var voiceIn: VoiceInput
        private set
    lateinit var voiceOut: VoiceOutput
        private set

    /** Pantalla conectada (null si la app no está visible). */
    var ui: Ui? = null
    @Volatile var visible = false

    /** Conversación completa para repintarla al volver a la pantalla. */
    val transcript = StringBuilder()
    var estadoTexto = ""
        private set

    @Volatile var listo = false           // el motor arrancó al menos una vez
        private set
    @Volatile var modeloListo = false     // el modelo ya está descargado y verificado: se puede escribir mientras el motor carga
        private set
    @Volatile private var arrancando = false
    @Volatile var ocupado = false         // hay una respuesta del modelo en curso
        private set
    @Volatile private var generando = false

    private val history = mutableListOf<Pair<String, String>>()
    @Volatile private var genId = 0       // subirlo aborta la respuesta en curso
    private var pending: String? = null   // dictado que llegó mientras se cancelaba la respuesta anterior
    private val lock = Any()
    private var tras: (() -> Unit)? = null          // acción para cuando termine de hablar (abrir otra app)
    private var comandoPendiente: String? = null    // comando que esperaba un permiso
    private var wl: PowerManager.WakeLock? = null
    private var notifId = 100

    fun contexto(): Context = app

    /** Se puede llamar muchas veces (Activity y Service). Solo desde el hilo principal. */
    fun iniciar(ctx: Context) {
        if (iniciado) return
        iniciado = true
        app = ctx.applicationContext
        registrarCierres()                     // "caja negra": guarda el motivo de un cierre inesperado
        cargarContexto()                       // retoma la conversación reciente si la app se había cerrado
        Termico.iniciar(app)
        EngineManager.iniciar(app)
        JarvisService.crearCanales(app)

        voiceOut = VoiceOutput(app)
        voiceOut.onProblem = { msg -> toast(msg) }
        voiceOut.onIdle = { alTerminarDeHablar() }
        voiceOut.preparar()                    // carga la lista de voces disponibles para poder elegir

        voiceIn = VoiceInput(app)
        voiceIn.onStart = { ui?.escuchando(true) }
        voiceIn.onEnd = { ui?.escuchando(false) }
        voiceIn.onSpeechStart = { interrupt() }               // si empiezo a hablar, Jarvis se calla
        voiceIn.onPartial = { t -> ui?.parcial(t) }
        voiceIn.onResult = { t -> alResultadoVoz(t) }
        voiceIn.onNoSpeech = {
            if (HandsFree.activo) HandsFree.alSilencio() else toast("No te escuché. Toca el micrófono y habla.")
        }
        voiceIn.onError = { msg ->
            toast(msg)
            if (HandsFree.activo) HandsFree.alSilencio()
        }
    }

    // ---------- Caja negra: motivo de un cierre inesperado ----------

    private fun archivoError() = File(app.filesDir, "ultimo_error.txt")

    /** Si la app se cierra por un error, guarda cuál fue; al abrirla de nuevo se muestra en la conversación. */
    private fun registrarCierres() {
        val anterior = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { hilo, e ->
            try {
                archivoError().writeText("${java.util.Date()} · hilo ${hilo.name}\n" + Log.getStackTraceString(e))
            } catch (_: Exception) {
            }
            anterior?.uncaughtException(hilo, e)
        }
        try {
            val f = archivoError()
            if (f.exists()) {
                emitir("\n[Cierre inesperado anterior]\n" + f.readText().take(900) + "\n")
                f.delete()
            }
        } catch (_: Exception) {
        }
    }

    // ---------- Arranque del motor ----------

    /** Descarga el modelo (solo la primera vez) y arranca el motor. Se puede llamar varias veces. */
    fun arrancar() {
        if (listo || arrancando) return
        arrancando = true
        Thread {
            EngineManager.retener()   // que no se apague mientras carga
            try {
                estado("Revisando el modelo…")
                val ok = Engine.download(app) { estado("Descargando modelo… $it %") }
                if (!ok) {
                    estado("No se pudo descargar el modelo. Revisa tu internet y reabre la app.")
                    return@Thread
                }
                modeloListo = true
                main.post { ui?.habilitar() }     // ya se puede escribir mientras el motor carga
                Thread.sleep(600)                 // deja que la pantalla termine de dibujarse antes de la carga pesada
                if (EngineManager.asegurar(app) { estado("Cargando el motor… ya puedes escribir, respondo apenas termine") }) {
                    listo = true
                    estado("Jarvis listo")
                } else {
                    estado("El motor no arrancó:\n" + Engine.lastLog())
                }
            } catch (e: Exception) {
                estado("Error: ${e.message}\n" + Engine.lastLog())
            } finally {
                arrancando = false
                EngineManager.soltar()
            }
        }.start()
    }

    /** Carga el motor en segundo plano si el modelo ya está descargado (arranque del teléfono, reinicio del servicio). */
    fun arrancarSiHayModelo() {
        if (Engine.modelFile(app).exists()) arrancar()
    }

    /** Al volver a la app: si el motor se apagó por inactividad, lo enciende en segundo plano. */
    fun despertar() {
        if (!listo || arrancando) return
        Thread {
            if (EngineManager.asegurar(app) { estado(it) }) estado("Jarvis listo")
            else estado("El motor no arrancó:\n" + Engine.lastLog())
        }.start()
    }

    fun estado(s: String) {
        main.post {
            estadoTexto = s
            ui?.estado(s)
        }
    }

    fun toast(msg: String) {
        main.post { Toast.makeText(app, msg, Toast.LENGTH_SHORT).show() }
    }

    // ---------- Entradas ----------

    /** Texto escrito. Devuelve false si Jarvis está ocupado respondiendo. */
    fun enviarTexto(texto: String): Boolean {
        val t = texto.trim()
        if (t.isEmpty() || ocupado) return false
        if (voiceIn.isListening) voiceIn.cancel()
        ask(t)
        return true
    }

    private fun alResultadoVoz(texto: String) {
        ui?.parcial("")
        if (HandsFree.alResultado(texto)) return   // frase de cierre del manos libres
        enviarVoz(texto)
    }

    /** Dictado: si la respuesta anterior aún se está cancelando, queda en espera. */
    private fun enviarVoz(texto: String) {
        val enEspera = synchronized(lock) { if (ocupado) { pending = texto; true } else false }
        if (enEspera) interrupt() else ask(texto)
    }

    /** Botón del micrófono: primer toque escucha, segundo termina de escuchar. */
    fun alternarMicrofono(puedeGrabar: Boolean, pedirPermiso: () -> Unit) {
        if (voiceIn.isListening) { voiceIn.stop(); return }
        interrupt()                                  // tocar el botón corta la voz
        if (puedeGrabar) voiceIn.start() else pedirPermiso()
    }

    /** Corta la voz y aborta la respuesta en curso (también ahorra batería). */
    fun interrupt() {
        genId++
        voiceOut.stop()
        HandsFree.alInterrumpir()
    }

    /** La pantalla avisa si el permiso pedido (contactos) se concedió. */
    fun permisoResultado(concedido: Boolean) {
        val t = comandoPendiente ?: return
        comandoPendiente = null
        if (concedido) ask(t)   // ahora el comando sí puede buscar en los contactos
        else responderLocal(t, Reply("Sin permiso de contactos no puedo buscar a esa persona."))
    }

    // ---------- Pregunta: primero comandos, luego el modelo ----------

    /** Prompt del sistema: base + fecha de hoy + lo que el usuario pidió recordar. */
    private fun systemPrompt(): String {
        // Solo la FECHA (no la hora): así el prompt no cambia en cada pregunta y llama-server
        // puede reutilizar su caché y no reprocesar todo (en el teléfono eso tarda).
        val memoria = Memory.bloqueParaPrompt(app)
        return SISTEMA + " Hoy es ${Parsers.fechaHoy()}." + if (memoria.isEmpty()) "" else "\n\n$memoria"
    }

    private fun ask(text: String) {
        // 1) Comandos del celular y memoria: sin gastar IA ni batería
        val local = Commands.run(app, text)
        if (local != null) {
            val permiso = local.permiso
            if (permiso == null) {
                responderLocal(text, local)
            } else {
                val u = ui
                if (u != null) {
                    comandoPendiente = text
                    u.pedirPermiso(permiso)
                } else {
                    responderLocal(text, Reply("Necesito el permiso de contactos. Abre Jarvis y pídemelo una vez."))
                }
            }
            return
        }

        // 2) Todo lo demás lo contesta el modelo
        ocupado = true
        val miGen = ++genId
        val ticket = voiceOut.startReply()          // empieza una respuesta hablada nueva
        val vozEnVivo = Ajustes.vozEnVivo(app)
        // Por defecto la voz NO se prepara ni habla mientras el modelo escribe: así no le quita CPU
        if (vozEnVivo) voiceOut.preparar()
        // Modo por defecto: solo se muestra tu mensaje; la respuesta aparece COMPLETA cuando termina de generarse
        emitir(if (vozEnVivo) "\nTú: $text\nJarvis: " else "\nTú: $text\n")
        history.add("user" to text)
        recortarHistorial()
        // Búsqueda en vivo: si la pregunta es de datos o actualidad, se busca en internet mientras arranca el motor
        val previo = history.dropLast(1).lastOrNull { it.first == "user" }?.second
        val consultaWeb = if (text.trim().split(Regex("\\s+")).size < 5 && previo != null) "$previo $text" else text
        val buscarWeb = Ajustes.busquedaEnChat(app) && Web.necesitaInternet(text)
        val webListo = arrayOfNulls<Pair<String, List<String>>>(1)
        val hiloWeb = if (buscarWeb) Thread {
            try { webListo[0] = Web.contextoEnVivo(consultaWeb) } catch (_: Exception) {}
        }.also { it.isDaemon = true; it.start() } else null
        generando = true
        Agente.cederMotor = true                    // si una tarea está usando el motor, lo suelta para atenderte
        renovarSueno()                              // CPU despierta mientras piensa
        EngineManager.retener()
        Thread {
            val reply = StringBuilder()
            var cancelado = false
            val t0 = SystemClock.elapsedRealtime()
            var tListo = t0          // motor listo
            var tPrimero = 0L        // llegó la primera palabra
            var tokens = 0
            var fuentesWeb: List<String> = emptyList()
            try {
                // Si el motor se apagó por inactividad, aquí se vuelve a encender
                if (!EngineManager.asegurar(app) { estado(it) }) throw Exception("el motor no arrancó")
                if (hiloWeb != null) { estado("Buscando en internet…"); try { hiloWeb.join(30_000) } catch (_: InterruptedException) {} }
                tListo = SystemClock.elapsedRealtime()
                estado("Respondiendo…")
                val web = webListo[0]
                fuentesWeb = web?.second.orEmpty()
                val msgs = listOf("system" to systemPrompt() + (web?.first ?: "")) + history
                Chat.stream(msgs) { t ->
                    if (miGen != genId) throw Cancelado()   // el usuario interrumpió
                    if (tokens == 0) tPrimero = SystemClock.elapsedRealtime()
                    tokens++
                    reply.append(t)
                    if (vozEnVivo) {                        // modo "en vivo": el texto aparece y se habla a medida que llega
                        emitir(t)
                        voiceOut.feed(ticket, t)
                    }
                }
            } catch (e: Cancelado) {
                cancelado = true
            } catch (e: Exception) {
                emitir("\n[error: ${e.message}]")
            }
            if (reply.isNotEmpty()) {
                history.add("assistant" to reply.toString())
                guardarContexto()
            } else if (cancelado && history.isNotEmpty()) {
                history.removeAt(history.lastIndex)  // no dejar un "user" suelto
            }
            if (!vozEnVivo && reply.isNotEmpty()) {
                // Modo por defecto: primero se muestra la respuesta completa y recién entonces empieza la voz (de corrido)
                emitirYa("Jarvis: " + reply.toString().trim() + (if (cancelado) " […]" else "") + pieFuentes(fuentesWeb, cancelado) + "\n")
                if (!cancelado) voiceOut.feed(ticket, reply.toString())
            }
            voiceOut.endReply(ticket)                       // habla lo que quede (si no se cortó)
            if (vozEnVivo) { emitir(pieFuentes(fuentesWeb, cancelado)); emitir("\n") }
            if (tokens > 0 && !cancelado) mostrarMedicion(tListo - t0, tPrimero - tListo, SystemClock.elapsedRealtime() - tPrimero, tokens)
            generando = false
            EngineManager.soltar()
            val siguiente = synchronized(lock) { ocupado = false; pending.also { pending = null } }
            main.post {
                renovarSueno()
                if (siguiente != null) ask(siguiente)
            }
        }.start()
    }

    // ---------- Contexto reciente (sobrevive al cierre de la app) ----------

    private fun archivoContexto() = File(app.filesDir, "contexto.json")

    /** Guarda los últimos mensajes de la conversación. */
    private fun guardarContexto() {
        try {
            val arr = JSONArray()
            history.takeLast(HIST_KEEP).forEach { arr.put(JSONObject().put("r", it.first).put("c", it.second)) }
            val f = archivoContexto()
            val tmp = File(app.filesDir, "contexto.json.tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(f)) { f.writeText(tmp.readText()); tmp.delete() }
        } catch (_: Exception) {
        }
    }

    /** Recupera la conversación reciente al abrir la app (empieza por el usuario y termina en una respuesta). */
    private fun cargarContexto() {
        try {
            val f = archivoContexto()
            if (!f.exists()) return
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                history.add(o.getString("r") to o.getString("c"))
            }
            while (history.isNotEmpty() && history[0].first != "user") history.removeAt(0)
            if (history.isNotEmpty() && history.last().first == "user") history.removeAt(history.lastIndex)
        } catch (e: Exception) {
            history.clear()
        }
    }

    /** "Nueva conversación": olvida el contexto reciente (no toca lo que pediste recordar). */
    fun nuevaConversacion() {
        history.clear()
        archivoContexto().delete()
    }

    /** Enlaces de las páginas que se consultaron para contestar (solo se muestran; la voz no los lee). */
    private fun pieFuentes(urls: List<String>, cancelado: Boolean): String =
        if (urls.isEmpty() || cancelado) "" else "\n\nFuentes:\n" + urls.mapIndexed { i, u -> "[${i + 1}] $u" }.joinToString("\n")

    /** Recorta el historial por tandas (ver HIST_MAX) y lo deja siempre empezando por un mensaje del usuario. */
    private fun recortarHistorial() {
        fun chars() = history.sumOf { it.second.length }
        if (history.size <= HIST_MAX && chars() <= HIST_MAX_CHARS) return
        while (history.size > 1 && (history.size > HIST_KEEP || chars() > HIST_KEEP_CHARS)) history.removeAt(0)
        while (history.size > 1 && history[0].first != "user") history.removeAt(0)
    }

    /** Línea de estado con los tiempos de la última respuesta, para comparar mejoras con números reales. */
    private fun mostrarMedicion(motorMs: Long, esperaMs: Long, generacionMs: Long, tokens: Int) {
        val tps = if (generacionMs > 300) tokens * 1000f / generacionMs else 0f
        val motor = if (motorMs > 1000) " · motor %.0f s".format(motorMs / 1000f) else ""
        val tb = Ajustes.hilosLecturaEfectivos(app)
        val hilos = Ajustes.hilosEfectivos(app).toString() + (if (tb > 0) "/$tb" else "") +
            (if (Ajustes.nucleosFijos(app)) " fijos" else "")
        val calor = if (Termico.nivel(app) >= Termico.TIBIO) " · " + Termico.descripcion(app) else ""
        estado("Jarvis listo · primera palabra %.1f s · %.1f tok/s · hilos %s%s%s".format(esperaMs / 1000f, tps, hilos, motor, calor))
    }

    /** Muestra y dice la respuesta de un comando local (no pasa por el modelo ni por el historial). */
    private fun responderLocal(usuario: String, r: Reply) {
        var texto = r.texto
        r.despues?.let { accion ->
            // Si la app no está a la vista, Android no deja abrir otras apps: queda una notificación
            if (!visible) texto += " Te dejé una notificación para continuar."
            despuesDeHablar(accion)
        }
        emitir("\nTú: $usuario\nJarvis: $texto\n")
        hablar(texto)
    }

    /** Jarvis dice algo por iniciativa propia (avisos del manos libres). */
    fun decir(texto: String) {
        emitir("\nJarvis: $texto\n")
        hablar(texto)
    }

    private fun hablar(texto: String) {
        val t = voiceOut.startReply()
        voiceOut.feed(t, texto)
        voiceOut.endReply(t)
    }

    /** Agrega texto a la conversación y lo muestra si la pantalla está a la vista. */
    // El texto que llega token a token se junta y se pasa a la pantalla cada ~120 ms:
    // menos trabajo para el hilo principal, y el teclado no se siente lento mientras Jarvis escribe.
    private val bufferUi = StringBuilder()
    private var flushProgramado = false
    private val flushUi = Runnable {
        val t = synchronized(bufferUi) {
            val s = bufferUi.toString()
            bufferUi.setLength(0)
            flushProgramado = false
            s
        }
        if (t.isNotEmpty()) {
            transcript.append(t)
            if (transcript.length > 20_000) transcript.delete(0, transcript.length - 15_000)
            ui?.texto(t)
        }
    }

    private fun emitir(t: String) {
        val programar = synchronized(bufferUi) {
            bufferUi.append(t)
            if (flushProgramado) false else { flushProgramado = true; true }
        }
        if (programar) main.postDelayed(flushUi, 120)
    }

    /** Muestra el texto YA (sin esperar la tanda): se usa para la respuesta completa, que debe verse antes de que empiece la voz. */
    private fun emitirYa(t: String) {
        synchronized(bufferUi) {
            bufferUi.append(t)
            flushProgramado = true
        }
        main.removeCallbacks(flushUi)
        main.post(flushUi)
    }

    // ---------- Después de hablar ----------

    private fun alTerminarDeHablar() {
        tras?.let { accion -> tras = null; accion() }
        HandsFree.alTerminarDeHablar()
    }

    /**
     * Ejecuta la acción cuando Jarvis termina de hablar (o a los 4 s como máximo).
     * Necesario porque al abrir otra app esta pasa a primer plano y la voz se cortaría.
     */
    private fun despuesDeHablar(accion: () -> Unit) {
        var hecho = false
        val ejecutar = Runnable {
            if (!hecho) {
                hecho = true
                tras = null
                accion()
            }
        }
        tras = { ejecutar.run() }
        main.postDelayed(ejecutar, 4000)
    }

    /** Deja una notificación que, al tocarla, ejecuta el intent (alternativa a abrir apps desde el segundo plano). */
    fun notificarAccion(i: Intent) {
        val pi = PendingIntent.getActivity(
            app, notifId, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(app, JarvisService.CANAL_ACCIONES)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("J.A.R.V.I.S.")
            .setContentText("Toca para continuar")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(notifId++, n)
    }

    // ---------- CPU despierta solo cuando hace falta ----------

    /** Mantiene la CPU despierta mientras haya manos libres o una respuesta en curso; si no, la suelta. */
    @Synchronized fun renovarSueno() {
        val necesita = HandsFree.activo || generando
        val w = wl ?: (app.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jarvis:activo")
            .also { it.setReferenceCounted(false); wl = it }
        if (necesita) w.acquire(15 * 60_000L)   // con límite de seguridad; se renueva en cada turno
        else if (w.isHeld) w.release()
    }
}
