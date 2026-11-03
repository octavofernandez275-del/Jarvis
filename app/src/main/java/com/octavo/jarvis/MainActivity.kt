package com.octavo.jarvis

import android.app.Activity
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

    private val system = "Eres J.A.R.V.I.S., el asistente personal del usuario. " +
        "Hablas en español natural, con respuestas breves y directas. " +
        "Si no sabes algo, lo dices con honestidad."
    private val history = mutableListOf<Pair<String, String>>()
    @Volatile private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (12 * resources.displayMetrics.density).toInt()

        status = TextView(this).apply { textSize = 13f; setPadding(pad, pad, pad, pad) }
        chatText = TextView(this).apply { textSize = 16f; setPadding(pad, pad, pad, pad) }
        scroll = ScrollView(this).apply { addView(chatText) }
        input = EditText(this).apply {
            hint = "Escribe a Jarvis…"
            isEnabled = false
            imeOptions = EditorInfo.IME_ACTION_SEND
            maxLines = 4
        }
        sendBtn = Button(this).apply { text = "Enviar"; isEnabled = false }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, 0, pad, pad)
            addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(sendBtn)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status)
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(row)
        }
        setContentView(root)

        sendBtn.setOnClickListener { send() }
        input.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { send(); true } else false }

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
                runOnUiThread { input.isEnabled = true; sendBtn.isEnabled = true }
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
        Thread {
            val reply = StringBuilder()
            try {
                Engine.chat(msgs) { reply.append(it); append(it) }
            } catch (e: Exception) {
                append("\n[error: ${e.message}]")
            }
            if (reply.isNotEmpty()) history.add("assistant" to reply.toString())
            append("\n")
            busy = false
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) Engine.stop()
    }
}
