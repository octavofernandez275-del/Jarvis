package com.octavo.jarvis

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Voz de entrada (SpeechRecognizer de Android, en español).
 * Se usa SOLO desde el hilo principal (así lo exige SpeechRecognizer).
 *
 * Ahorro: el reconocedor se crea en cada start() y se destruye al terminar,
 * así no queda ningún servicio ni micrófono abierto entre usos.
 *
 * No depende de ninguna Activity (usa applicationContext).
 * GANCHO PARA MANOS LIBRES (bloque 4): onEnd se llama siempre al terminar de
 * escuchar (con resultado, error o cancelación); desde ahí se podrá re-armar start().
 */
class VoiceInput(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var rec: SpeechRecognizer? = null
    private var session = 0   // cada start() es una sesión; callbacks de sesiones viejas se ignoran

    var isListening = false
        private set

    var onStart: (() -> Unit)? = null          // empezó a escuchar
    var onSpeechStart: (() -> Unit)? = null    // el usuario empezó a hablar (para cortar la voz de Jarvis)
    var onPartial: ((String) -> Unit)? = null  // texto parcial mientras habla
    var onResult: ((String) -> Unit)? = null   // texto final
    var onNoSpeech: (() -> Unit)? = null       // no se oyó voz (si es null, se avisa por onError)
    var onError: ((String) -> Unit)? = null    // mensaje para mostrar al usuario
    var onEnd: (() -> Unit)? = null            // terminó de escuchar, pase lo que pase

    fun isAvailable() = SpeechRecognizer.isRecognitionAvailable(app)

    /** Empieza a escuchar. Requiere el permiso RECORD_AUDIO ya concedido. */
    fun start() {
        if (isListening) return
        if (!isAvailable()) {
            onError?.invoke("Este teléfono no tiene reconocimiento de voz.")
            return
        }
        val r = SpeechRecognizer.createSpeechRecognizer(app)
        val mine = ++session
        r.setRecognitionListener(listener(mine))
        rec = r
        isListening = true
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-AR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, app.packageName)
        }
        r.startListening(intent)
        onStart?.invoke()
    }

    /** Termina de escuchar y entrega lo captado hasta ahora (segundo toque del botón). */
    fun stop() { rec?.stopListening() }

    /** Aborta sin entregar nada y libera todo (por ejemplo al salir de la app). */
    fun cancel() { if (isListening) end() }

    /** Cierra la sesión: libera el reconocedor y avisa onEnd una sola vez. */
    private fun end() {
        session++                       // ignora cualquier callback que llegue después
        val r = rec
        rec = null
        val estaba = isListening
        isListening = false
        if (r != null) {
            try { r.cancel() } catch (_: Exception) {}
            main.post { r.destroy() }   // se destruye fuera del callback actual
        }
        if (estaba) onEnd?.invoke()
    }

    private fun mensaje(code: Int): String? = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No te escuché. Toca el micrófono y habla."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Falta el permiso del micrófono."
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        SpeechRecognizer.ERROR_SERVER -> "El reconocedor de voz no tiene conexión."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "El reconocedor está ocupado, reintenta."
        SpeechRecognizer.ERROR_AUDIO -> "Error de audio del micrófono."
        SpeechRecognizer.ERROR_CLIENT -> null   // suele ser por cancelar nosotros mismos
        12, 13 -> "El reconocimiento en español no está disponible (idioma no instalado)."
        else -> "Error de voz ($code)."
    }

    private fun listener(mine: Int) = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {
            if (mine == session) onSpeechStart?.invoke()
        }
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            if (mine != session) return
            val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull().orEmpty()
            if (t.isNotEmpty()) onPartial?.invoke(t)
        }

        override fun onResults(results: Bundle?) {
            if (mine != session) return
            val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.trim().orEmpty()
            end()
            if (t.isNotEmpty()) onResult?.invoke(t)
            else onError?.invoke("No te entendí, intenta de nuevo.")
        }

        override fun onError(error: Int) {
            if (mine != session) return
            end()
            val sinVoz = onNoSpeech
            if ((error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) && sinVoz != null) {
                sinVoz()
                return
            }
            mensaje(error)?.let { onError?.invoke(it) }
        }
    }
}
