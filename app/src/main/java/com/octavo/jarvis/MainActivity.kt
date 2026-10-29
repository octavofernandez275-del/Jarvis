package com.octavo.jarvis

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.*

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var chatText: TextView
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var sendBtn: Button
    private lateinit var micBtn: Button
    private lateinit var voice: VoiceController

    private val system = "Eres J.A.R.V.I.S., el asistente personal del usuario. " +
        "Hablas en español natural, con respuestas breves y directas. " +
        "Si no sabes algo, lo dices con honestidad."
    private val history = mutableListOf<Pair<String, String>>()
    @Volatile private var busy = false

    companion object {
        private const val REQUEST_RECORD_AUDIO = 4102
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (12 * resources.displayMetrics.density).toInt()

        status = TextView(this).apply { textSize = 13f; setPadding(pad, pad, pad, pad) }
        chatText = TextView(this).apply { textSize = 16f; setPadding(pad, pad, pad, pad) }
        scroll = ScrollView(this).apply { addView(chatText) }
        input = EditText(this).apply {
            hint = "Escribe o dicta a Jarvis…"
            isEnabled = false
            imeOptions = EditorInfo.IME_ACTION_SEND
            maxLines = 4
        }
        sendBtn = Button(this).apply { text = "Enviar"; isEnabled = false }
        micBtn = Button(this).apply { text = "🎙"; contentDescription = "Hablar con Jarvis"; isEnabled = false }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, 0, pad, pad)
            addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(micBtn)
            addView(sendBtn)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status)
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(row)
        }
        setContentView(root)

        // El controlador administra el reconocimiento y la voz sintetizada.
        voice = VoiceController(
            activity = this,
            onResult = { spokenText ->
                input.setText(spokenText)
                input.setSelection(input.text.length)
                send()
            },
            onStatus = { message -> setStatus(message) }
        )

        sendBtn.setOnClickListener { voice.stopSpeaking(); send() }
        micBtn.setOnClickListener {
            // Al tocar el micrófono, se corta cualquier frase que Jarvis esté diciendo.
            voice.stopSpeaking()
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                voice.startListening()
            } else {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
            }
        }
        input.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEND) { voice.stopSpeaking(); send(); true } else false
        }

        Thread { boot() }.start()
    }

    private fun setStatus(s: String) = runOnUiThread { status.text = s }

    private fun boot() {
        try {
            setStatus("Revisando el modelo…")
            val ok = Engine.download(this) { setStatus("Descargando modelo… $it %") }
            if (!ok) { setStatus("No se pudo descargar el modelo. Revisa tu internet y reabre la app."); return }
            setStatus("Iniciando motor…")
            Engine.start(this)
            if (Engine.waitReady()) {
                setStatus("Jarvis listo")
                runOnUiThread {
                    input.isEnabled = true
                    sendBtn.isEnabled = true
                    micBtn.isEnabled = true
                }
            } else {
                setStatus("El motor no arrancó:\n" + Engine.lastLog())
            }
        } catch (e: Exception) {
            setStatus("Error: ${e.message}\n" + Engine.lastLog())
        }
    }

    private fun append(t: String) = runOnUiThread {
        chatText.append(t)
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun send() {
        val text = input.text.toString().trim()
        if (text.isEmpty() || busy) return
        busy = true
        input.setText("")
        append("\nTú: $text\nJarvis: ")
        history.add("user" to text)
        while (history.size > 8) history.removeAt(0)
        val msgs = listOf("system" to system) + history
        voice.beginResponse()

        Thread {
            val reply = StringBuilder()
            try {
                // Los fragmentos se muestran y se entregan al sintetizador en tiempo real.
                Engine.chat(msgs) {
                    reply.append(it)
                    append(it)
                    voice.acceptToken(it)
                }
            } catch (e: Exception) {
                append("\n[error: ${e.message}]")
            } finally {
                voice.finishResponse()
            }
            if (reply.isNotEmpty()) history.add("assistant" to reply.toString())
            append("\n")
            busy = false
        }.start()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                voice.startListening()
            } else {
                setStatus("Permiso de micrófono denegado. Puedes seguir escribiendo.")
            }
        }
    }

    override fun onDestroy() {
        if (::voice.isInitialized) voice.release()
        super.onDestroy()
        if (isFinishing) Engine.stop()
    }
}
