package com.octavo.jarvis

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.*

/**
 * Pantalla de Jarvis. Solo muestra y recoge toques: el cerebro está en Assistant,
 * así la conversación y el manos libres siguen aunque esta pantalla se cierre.
 */
class MainActivity : Activity(), Assistant.Ui {
    private lateinit var status: TextView
    private lateinit var chatText: TextView
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var sendBtn: Button
    private lateinit var micBtn: Button
    private lateinit var handsBtn: Button
    private lateinit var batBtn: Button
    private var pidioNotificaciones = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Assistant.iniciar(this)
        val pad = (12 * resources.displayMetrics.density).toInt()

        status = TextView(this).apply { textSize = 13f; setPadding(pad, pad, pad, pad) }
        chatText = TextView(this).apply { textSize = 16f; setPadding(pad, pad, pad, pad) }
        scroll = ScrollView(this).apply { addView(chatText) }
        input = EditText(this).apply {
            hint = HINT
            isEnabled = false
            imeOptions = EditorInfo.IME_ACTION_SEND
            maxLines = 4
        }
        micBtn = Button(this).apply { text = MIC; isEnabled = false }
        sendBtn = Button(this).apply { text = "Enviar"; isEnabled = false }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, 0, pad, 0)
            addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(micBtn)
            addView(sendBtn)
        }
        handsBtn = Button(this).apply { text = MANOS_OFF; isEnabled = false }
        batBtn = Button(this).apply { text = "🔋 Batería" }
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(pad, 0, pad, pad)
            addView(handsBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(batBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status)
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(row)
            addView(row2)
        }
        setContentView(root)

        sendBtn.setOnClickListener { send() }
        micBtn.setOnClickListener { onMicClick() }
        handsBtn.setOnClickListener { onManosLibres() }
        batBtn.setOnClickListener { ahorroBateria() }
        input.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { send(); true } else false }
    }

    // ---------- Ciclo de vida ----------

    override fun onStart() {
        super.onStart()
        Assistant.ui = this
        Assistant.visible = true
        // Repintar lo ocurrido mientras la pantalla no estaba a la vista
        chatText.text = Assistant.transcript.toString()
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        status.text = Assistant.estadoTexto
        manosLibres(HandsFree.activo)
        EngineManager.retener()                       // mientras la pantalla está visible, el motor no se apaga
        JarvisService.iniciar(this, HandsFree.activo) // servicio en primer plano (solo se puede arrancar con la app visible)
        if (Assistant.listo) { habilitar(); Assistant.despertar() } else Assistant.arrancar()
    }

    override fun onStop() {
        super.onStop()
        Assistant.visible = false
        Assistant.ui = null
        EngineManager.soltar()
        // Sin manos libres no hay micrófono en segundo plano; la respuesta y la voz sí pueden seguir
        if (!HandsFree.activo) Assistant.voiceIn.cancel()
    }

    // ---------- Botones ----------

    private fun send() {
        val t = input.text.toString()
        if (Assistant.enviarTexto(t)) input.setText("")
    }

    private fun tieneMicrofono() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun onMicClick() {
        Assistant.alternarMicrofono(tieneMicrofono()) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
        }
    }

    private fun onManosLibres() {
        if (HandsFree.activo) { HandsFree.desactivar("Manos libres desactivado."); return }
        if (!tieneMicrofono()) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC_MANOS_LIBRES)
            return
        }
        pasoNotificaciones()
    }

    /** Android 13+: sin este permiso no se ve la notificación (con sus botones Detener/Interrumpir). */
    private fun pasoNotificaciones() {
        if (Build.VERSION.SDK_INT >= 33 && !pidioNotificaciones &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pidioNotificaciones = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
            return
        }
        HandsFree.activar(this)
    }

    /** Abre la lista de apps con ahorro de batería para que el usuario elija "Sin restricciones". */
    private fun ahorroBateria() {
        Toast.makeText(this, "Busca J.A.R.V.I.S. y elige «Sin restricciones» o «No optimizar».", Toast.LENGTH_LONG).show()
        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val ok = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        when (requestCode) {
            REQ_MIC -> if (ok) Assistant.voiceIn.start() else Assistant.toast("Sin permiso de micrófono no puedo escucharte.")
            REQ_MIC_MANOS_LIBRES ->
                if (ok) pasoNotificaciones() else Assistant.toast("Sin permiso de micrófono no hay manos libres.")
            REQ_NOTIF -> HandsFree.activar(this)   // con o sin notificación visible, el servicio funciona
            REQ_CONTACTOS -> Assistant.permisoResultado(ok)
        }
    }

    // ---------- Assistant.Ui (siempre en el hilo principal) ----------

    override fun texto(t: String) {
        chatText.append(t)
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    override fun estado(s: String) { status.text = s }

    override fun escuchando(on: Boolean) {
        micBtn.text = if (on) STOP else MIC
        input.hint = if (on) "Escuchando…" else HINT
    }

    override fun parcial(t: String) {
        input.setText(t)
        input.setSelection(t.length)
    }

    override fun manosLibres(on: Boolean) {
        handsBtn.text = if (on) MANOS_ON else MANOS_OFF
    }

    override fun habilitar() {
        input.isEnabled = true
        sendBtn.isEnabled = true
        micBtn.isEnabled = true
        handsBtn.isEnabled = true
    }

    override fun pedirPermiso(permiso: String) {
        requestPermissions(arrayOf(permiso), REQ_CONTACTOS)
    }

    private companion object {
        const val REQ_MIC = 1
        const val REQ_CONTACTOS = 2
        const val REQ_MIC_MANOS_LIBRES = 3
        const val REQ_NOTIF = 4
        const val MIC = "🎤"
        const val STOP = "⏹"
        const val HINT = "Escribe a Jarvis…"
        const val MANOS_OFF = "🎧 Manos libres: OFF"
        const val MANOS_ON = "🎧 Manos libres: ON"
    }
}
