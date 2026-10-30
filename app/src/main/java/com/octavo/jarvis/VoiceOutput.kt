package com.octavo.jarvis

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Voz de salida (TextToSpeech en español).
 * Habla frase por frase mientras llegan los tokens de Engine.chat.
 *
 * Uso:  val t = startReply();  feed(t, token)...;  endReply(t)
 *       stop() corta la voz al instante (si el usuario habla o toca el botón).
 *
 * Ahorro: el motor TTS se crea al primer uso y se libera solo tras
 * IDLE_RELEASE_MS sin hablar (o con release()).
 *
 * No depende de ninguna Activity (usa applicationContext): el bloque 4
 * podrá usarla desde un Service. Se puede llamar desde cualquier hilo.
 *
 * GANCHO PARA MANOS LIBRES (bloque 4): onIdle se llama cuando Jarvis terminó
 * de hablar toda la respuesta; ahí se volverá a activar el micrófono.
 * No se llama si la voz se cortó con stop().
 */
class VoiceOutput(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    var onIdle: (() -> Unit)? = null
    var onProblem: ((String) -> Unit)? = null   // por ejemplo: falta la voz en español

    // "Época": cada respuesta nueva o stop() la incrementa; lo que llega con una época vieja se ignora
    private val epoch = AtomicInteger(0)

    // Todo lo de abajo se toca solo desde el hilo principal
    private val splitter = SentenceSplitter()
    private val waiting = ArrayDeque<String>()   // frases en espera mientras arranca el TTS
    private var tts: TextToSpeech? = null
    private var ready = false
    private var initializing = false
    private var initToken = 0
    private var broken = false
    private var pending = 0        // frases enviadas al TTS que aún no terminan
    private var seq = 0
    private var replyEnded = true
    private var focus: AudioFocusRequest? = null

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /** Solo hilo principal. */
    val isSpeaking: Boolean get() = pending > 0 || waiting.isNotEmpty()

    /** Empieza una respuesta nueva (corta cualquier voz anterior). Devuelve el "ticket" para feed/endReply. */
    fun startReply(): Int {
        val t = epoch.incrementAndGet()
        runMain { resetState(); replyEnded = false; broken = false }
        return t
    }

    /** Llega un fragmento de texto de la respuesta. */
    fun feed(ticket: Int, token: String) {
        main.post {
            if (ticket != epoch.get()) return@post
            splitter.feed(token).forEach { enqueue(it) }
        }
    }

    /** La respuesta terminó de llegar: habla lo que quede. */
    fun endReply(ticket: Int) {
        main.post {
            if (ticket != epoch.get()) return@post
            splitter.flush().forEach { enqueue(it) }
            replyEnded = true
            checkIdle()
        }
    }

    /** Corta la voz ya y descarta lo pendiente. */
    fun stop() {
        epoch.incrementAndGet()
        runMain { resetState() }
    }

    /** Libera el motor TTS (RAM). Se vuelve a crear solo si hace falta hablar otra vez. */
    fun release() {
        epoch.incrementAndGet()
        runMain {
            resetState()
            main.removeCallbacks(releaseTask)
            shutdownTts()
        }
    }

    // ---------- interno (hilo principal) ----------

    private fun runMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post { block() }
    }

    private fun resetState() {
        tts?.stop()
        splitter.reset()
        waiting.clear()
        pending = 0
        replyEnded = true
        abandonFocus()
        scheduleRelease()
    }

    private fun enqueue(sentence: String) {
        if (broken) return
        main.removeCallbacks(releaseTask)
        if (!ready) {
            waiting.addLast(sentence)
            initTts()
        } else {
            speakNow(sentence)
        }
    }

    private fun speakNow(sentence: String) {
        val t = tts ?: return
        if (pending == 0) requestFocus()
        pending++
        val id = "${epoch.get()}:${seq++}"
        if (t.speak(sentence, TextToSpeech.QUEUE_ADD, null, id) == TextToSpeech.ERROR) pending--
    }

    private fun initTts() {
        if (initializing || tts != null) return
        initializing = true
        val mine = ++initToken
        tts = TextToSpeech(app) { status -> main.post { onTtsInit(mine, status) } }
    }

    private fun onTtsInit(mine: Int, status: Int) {
        if (mine != initToken) return          // este TTS ya fue liberado
        initializing = false
        val t = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            fail("No se pudo iniciar la voz del teléfono.")
            return
        }
        // Español: probar variantes de más a menos específica
        val idioma = listOf(
            Locale("es", "AR"), Locale("es", "MX"), Locale("es", "US"),
            Locale("es", "ES"), Locale("es")
        ).firstOrNull { t.isLanguageAvailable(it) >= TextToSpeech.LANG_AVAILABLE }
        if (idioma == null) {
            fail("Falta la voz en español. Instálala en Ajustes > Idioma > Salida de texto a voz.")
            return
        }
        t.setLanguage(idioma)
        t.setAudioAttributes(attrs)
        t.setOnUtteranceProgressListener(listener)
        ready = true
        while (waiting.isNotEmpty()) speakNow(waiting.removeFirst())
        checkIdle()
    }

    /** Error de voz: avisa una sola vez por respuesta y descarta el resto. */
    private fun fail(msg: String) {
        broken = true
        shutdownTts()
        waiting.clear()
        pending = 0
        onProblem?.invoke(msg)
        checkIdle()
    }

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}
        override fun onDone(utteranceId: String?) = finished(utteranceId)
        @Deprecated("Requerido por la API antigua")
        override fun onError(utteranceId: String?) = finished(utteranceId)
        override fun onStop(utteranceId: String?, interrupted: Boolean) = finished(utteranceId)
    }

    /** Llamado desde un hilo del TTS: una frase terminó (o falló / se cortó). */
    private fun finished(id: String?) {
        val e = id?.substringBefore(':')?.toIntOrNull() ?: return
        main.post {
            if (e != epoch.get()) return@post      // frase de una respuesta vieja
            if (pending > 0) pending--
            checkIdle()
        }
    }

    /** Si ya se dijo todo, suelta el audio y avisa (gancho de manos libres). */
    private fun checkIdle() {
        if (replyEnded && pending == 0 && waiting.isEmpty()) {
            abandonFocus()
            scheduleRelease()
            onIdle?.invoke()
        }
    }

    private fun requestFocus() {
        if (focus != null) return
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs).build()
        audio.requestAudioFocus(req)
        focus = req
    }

    private fun abandonFocus() {
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
    }

    private val releaseTask = Runnable {
        if (pending == 0 && waiting.isEmpty()) shutdownTts()
    }

    private fun scheduleRelease() {
        main.removeCallbacks(releaseTask)
        main.postDelayed(releaseTask, IDLE_RELEASE_MS)
    }

    private fun shutdownTts() {
        initToken++
        tts?.shutdown()
        tts = null
        ready = false
        initializing = false
    }

    private companion object {
        const val IDLE_RELEASE_MS = 20_000L
    }
}
