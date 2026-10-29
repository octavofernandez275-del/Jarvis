package com.octavo.jarvis

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.util.Locale
import java.util.ArrayDeque

/**
 * Voz de Jarvis: reconocimiento de voz del sistema y TTS, sin bibliotecas externas.
 * Todas las operaciones de Android se ejecutan en el hilo principal.
 */
class VoiceController(
    private val activity: Activity,
    private val onResult: (String) -> Unit,
    private val onStatus: (String) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var listening = false
    private var voiceOutputPaused = false
    private var released = false

    // Las frases quedan en cola si el sintetizador todavía se está inicializando.
    private val speechQueue = ArrayDeque<String>()
    private val streamBuffer = StringBuilder()
    private var utteranceNumber = 0

    init {
        // TextToSpeech usa la voz instalada en Android; no descarga una voz adicional.
        tts = TextToSpeech(activity) { result ->
            main.post {
                if (released) return@post
                if (result == TextToSpeech.SUCCESS) {
                    val spanish = tts?.setLanguage(Locale("es", "ES"))
                    ttsReady = spanish != TextToSpeech.LANG_MISSING_DATA &&
                        spanish != TextToSpeech.LANG_NOT_SUPPORTED
                    if (!ttsReady) {
                        val argentina = tts?.setLanguage(Locale("es", "AR"))
                        ttsReady = argentina != TextToSpeech.LANG_MISSING_DATA &&
                            argentina != TextToSpeech.LANG_NOT_SUPPORTED
                    }
                    if (ttsReady) drainSpeechQueue()
                    else onStatus("La voz española no está instalada. Puedes seguir usando el chat.")
                } else {
                    onStatus("No se pudo iniciar la voz de Android.")
                }
            }
        }
    }

    /** Inicia la escucha. El permiso RECORD_AUDIO lo solicita MainActivity. */
    fun startListening() {
        main.post {
            if (released) return@post
            voiceOutputPaused = true
            stopSpeakingNow()
            if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
                onStatus("Este dispositivo no tiene reconocimiento de voz disponible.")
                return@post
            }
            try {
                if (recognizer == null) {
                    recognizer = SpeechRecognizer.createSpeechRecognizer(activity).also {
                        it.setRecognitionListener(listener)
                    }
                } else {
                    recognizer?.cancel()
                }

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "es-ES")
                    putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                }
                listening = true
                recognizer?.startListening(intent)
                onStatus("Preparando micrófono…")
            } catch (e: Exception) {
                listening = false
                onStatus("No se pudo abrir el micrófono: ${e.message ?: "error"}")
            }
        }
    }

    /** Se llama con cada token de Engine.chat: las frases se hablan sin esperar el final. */
    fun acceptToken(token: String) {
        main.post {
            if (released) return@post
            streamBuffer.append(token)
            extractSentences(final = false)
        }
    }

    fun beginResponse() {
        main.post {
            streamBuffer.setLength(0)
            speechQueue.clear()
            voiceOutputPaused = false
            stopSpeakingNow()
        }
    }

    fun finishResponse() {
        main.post {
            if (released) return@post
            extractSentences(final = true)
        }
    }

    /** Detiene inmediatamente cualquier frase hablada. */
    fun stopSpeaking() {
        main.post {
            voiceOutputPaused = true
            stopSpeakingNow()
        }
    }

    private fun stopSpeakingNow() {
        tts?.stop()
        speechQueue.clear()
    }

    private fun extractSentences(final: Boolean) {
        // Separa por puntuación final o saltos de línea, conservando el resto para el siguiente token.
        while (streamBuffer.isNotEmpty()) {
            val current = streamBuffer.toString()
            val match = SENTENCE_BOUNDARY.find(current)
            if (match != null) {
                val end = match.range.first + 1
                val phrase = current.substring(0, end).trim()
                streamBuffer.delete(0, match.range.last + 1)
                queuePhrase(phrase)
            } else if (final) {
                queuePhrase(current.trim())
                streamBuffer.setLength(0)
            } else {
                break
            }
        }
    }

    private fun queuePhrase(raw: String) {
        val phrase = cleanForSpeech(raw)
        if (phrase.isBlank() || voiceOutputPaused) return
        speechQueue.addLast(phrase)
        drainSpeechQueue()
    }

    private fun drainSpeechQueue() {
        if (!ttsReady || released || voiceOutputPaused) return
        while (speechQueue.isNotEmpty()) {
            val phrase = speechQueue.removeFirst()
            val id = "jarvis-${++utteranceNumber}"
            // QUEUE_ADD conserva el orden de las frases conforme llegan los tokens.
            tts?.speak(phrase, TextToSpeech.QUEUE_ADD, Bundle(), id)
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (!released) onStatus("Escuchando…")
        }

        override fun onBeginningOfSpeech() {
            // Si el usuario empieza a hablar, corta la salida de voz.
            voiceOutputPaused = true
            stopSpeakingNow()
            if (!released) onStatus("Te escucho…")
        }

        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            listening = false
            if (!released) onStatus("Procesando voz…")
        }

        override fun onError(error: Int) {
            listening = false
            if (released) return
            val message = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                    "No te entendí. Toca el micrófono e inténtalo otra vez."
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    "Falta permiso para usar el micrófono."
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                    "El reconocimiento está ocupado. Inténtalo de nuevo."
                else -> "No se pudo reconocer la voz (código $error)."
            }
            onStatus(message)
        }

        override fun onResults(results: Bundle?) {
            listening = false
            if (released) return
            val words = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.trim().orEmpty()
            if (words.isNotEmpty()) onResult(words)
            else onStatus("No se detectaron palabras. Inténtalo otra vez.")
        }

        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    /** Libera los recursos al cerrar la pantalla; no mantiene el micrófono activo. */
    fun release() {
        if (released) return
        released = true
        listening = false
        speechQueue.clear()
        streamBuffer.setLength(0)
        try {
            recognizer?.cancel()
            recognizer?.destroy()
        } catch (_: Exception) {}
        recognizer = null
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {}
        tts = null
        ttsReady = false
    }

    private fun cleanForSpeech(input: String): String {
        var text = input
        // Markdown: enlaces [texto](url), bloques de código, encabezados y énfasis.
        text = text.replace(Regex("""\[([^\]]+)]\([^)]+\)"""), "$1")
        text = text.replace(Regex("""```[\s\S]*?```"""), " ")
        text = text.replace(Regex("""`([^`]*)`"""), "$1")
        text = text.replace(Regex("""(?m)^\s{0,3}#{1,6}\s*"""), "")
        text = text.replace(Regex("""(?m)^\s*>\s?"""), "")
        text = text.replace(Regex("""(\*\*|__|\*|_|~~)"""), "")
        text = text.replace(Regex("""https?://\S+"""), "")
        text = text.replace(Regex("""<[^>]*>"""), " ")

        // Elimina emojis y selectores de emoji sin depender de librerías externas.
        val clean = StringBuilder()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val type = Character.getType(cp)
            val emojiLike = cp in 0x1F000..0x1FAFF ||
                cp in 0x2600..0x27BF ||
                cp in 0x1F1E6..0x1F1FF ||
                cp == 0xFE0F || cp == 0x200D ||
                type == Character.FORMAT.toInt()
            if (!emojiLike) clean.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return clean.toString()
            .replace(Regex("""[\t ]+"""), " ")
            .replace(Regex("""\s*\n+\s*"""), ". ")
            .replace(Regex("""([*_#>`])"""), "")
            .trim()
    }

    companion object {
        private val SENTENCE_BOUNDARY = Regex("""[.!?…](?:\s+|$)|\n+""")
    }
}
