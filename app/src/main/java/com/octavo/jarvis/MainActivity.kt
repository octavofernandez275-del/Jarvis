package com.octavo.jarvis

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.*

/**
 * Pantalla de Jarvis: fondo oscuro, reactor azul con acentos rojos y botones con borde de neón.
 * Solo muestra y recoge toques: el cerebro está en Assistant, así la conversación y el manos libres
 * siguen aunque esta pantalla se cierre.
 */
class MainActivity : Activity(), Assistant.Ui {
    private lateinit var reactor: ReactorView
    private lateinit var status: TextView
    private lateinit var chatText: TextView
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var sendBtn: Button
    private lateinit var micBtn: Button
    private lateinit var handsBtn: Button
    private lateinit var batBtn: Button
    private var pidioNotificaciones = false

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Assistant.iniciar(this)
        window.statusBarColor = ReactorView.FONDO
        window.navigationBarColor = ReactorView.FONDO

        reactor = ReactorView(this)
        status = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(ReactorView.AZUL_CLARO)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(2), dp(12), dp(6))
        }
        chatText = TextView(this).apply {
            textSize = 16f
            setTextColor(ReactorView.TEXTO)
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(chatText)
        }
        input = EditText(this).apply {
            hint = HINT
            isEnabled = false
            imeOptions = EditorInfo.IME_ACTION_SEND
            maxLines = 4
            setTextColor(Color.WHITE)
            setHintTextColor(Color.argb(140, 159, 232, 255))
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.argb(60, 24, 182, 255))
                setStroke(dp(2), Color.argb(170, 24, 182, 255))
            }
        }
        micBtn = Button(this).apply { text = MIC; isEnabled = false }
        sendBtn = Button(this).apply { text = "Enviar"; isEnabled = false }
        val fila1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(4), dp(10), dp(4))
            addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(micBtn, botonParams())
            addView(sendBtn, botonParams())
        }
        handsBtn = Button(this).apply { text = MANOS_OFF; isEnabled = false }
        batBtn = Button(this).apply { text = "🔋 Batería" }
        val tareasBtn = Button(this).apply { text = "📋 Tareas" }
        listOf(handsBtn, tareasBtn, batBtn).forEach { it.textSize = 12f }
        val fila2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), 0, dp(10), dp(10))
            addView(handsBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
            addView(tareasBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
            addView(batBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        estilo(micBtn, ReactorView.AZUL)
        estilo(sendBtn, ReactorView.AZUL)
        estilo(handsBtn, ReactorView.AZUL)
        estilo(batBtn, ReactorView.AZUL)
        estilo(tareasBtn, ReactorView.AZUL)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ReactorView.FONDO)
            addView(reactor, LinearLayout.LayoutParams(-1, dp(130)))
            addView(status)
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(fila1)
            addView(fila2)
        }
        setContentView(root)

        // Con el teclado abierto no queda lugar para el reactor: se oculta hasta que se cierre
        root.viewTreeObserver.addOnGlobalLayoutListener {
            val bajo = root.height < resources.displayMetrics.heightPixels * 0.62
            reactor.visibility = if (bajo) View.GONE else View.VISIBLE
        }

        sendBtn.setOnClickListener { send() }
        micBtn.setOnClickListener { onMicClick() }
        handsBtn.setOnClickListener { onManosLibres() }
        batBtn.setOnClickListener { ahorroBateria() }
        tareasBtn.setOnClickListener { startActivity(Intent(this, TareasActivity::class.java)) }
        input.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { send(); true } else false }
    }

    private fun botonParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { leftMargin = dp(6) }

    /** Botón con borde de neón del color de acento (azul o rojo). */
    private fun estilo(b: Button, acento: Int) {
        val rojo = Color.red(acento)
        val verde = Color.green(acento)
        val azul = Color.blue(acento)
        fun caja(relleno: Int, borde: Int) = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(14).toFloat()
            setColor(relleno)
            setStroke(dp(2), borde)
        }
        b.background = StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), caja(Color.argb(10, rojo, verde, azul), Color.argb(70, rojo, verde, azul)))
            addState(intArrayOf(android.R.attr.state_pressed), caja(Color.argb(120, rojo, verde, azul), ReactorView.AZUL_CLARO))
            addState(intArrayOf(), caja(Color.argb(35, rojo, verde, azul), acento))
        }
        b.setTextColor(
            ColorStateList(
                arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(Color.argb(110, 255, 255, 255), Color.WHITE)
            )
        )
        b.isAllCaps = false
        b.stateListAnimator = null
        b.setPadding(dp(12), dp(8), dp(12), dp(8))
    }

    /** Colorea las etiquetas de la conversación: "Tú:" en rojo y "Jarvis:" en azul. */
    private fun coloreado(s: String): CharSequence {
        val sb = SpannableStringBuilder(s)
        for (m in ETIQUETA.findAll(s)) {
            val color = if (m.groupValues[1] == "Tú") ReactorView.ROJO else ReactorView.AZUL
            val fin = m.range.last + 1
            sb.setSpan(ForegroundColorSpan(color), m.range.first, fin, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(StyleSpan(Typeface.BOLD), m.range.first, fin, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    // ---------- Ciclo de vida ----------

    override fun onStart() {
        super.onStart()
        Assistant.ui = this
        Assistant.visible = true
        // Repintar lo ocurrido mientras la pantalla no estaba a la vista
        chatText.text = coloreado(Assistant.transcript.toString().takeLast(8_000))
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        status.text = Assistant.estadoTexto
        manosLibres(HandsFree.activo)
        EngineManager.retener()                       // mientras la pantalla está visible, el motor no se apaga
        JarvisService.iniciar(this, HandsFree.activo) // servicio en primer plano (solo se puede arrancar con la app visible)
        if (Assistant.modeloListo) habilitar()
        if (Assistant.listo) Assistant.despertar() else Assistant.arrancar()
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
        chatText.append(coloreado(t))
        // Un texto enorme vuelve lenta la pantalla y el teclado: en pantalla solo queda lo último
        if (chatText.length() > 12_000) chatText.text = coloreado(chatText.text.toString().takeLast(8_000))
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    override fun estado(s: String) { status.text = s }

    override fun escuchando(on: Boolean) {
        micBtn.text = if (on) STOP else MIC
        estilo(micBtn, if (on) ReactorView.ROJO else ReactorView.AZUL)   // rojo mientras escucha
        input.hint = if (on) "Escuchando…" else HINT
    }

    override fun parcial(t: String) {
        input.setText(t)
        input.setSelection(t.length)
    }

    override fun manosLibres(on: Boolean) {
        handsBtn.text = if (on) MANOS_ON else MANOS_OFF
        estilo(handsBtn, if (on) ReactorView.ROJO else ReactorView.AZUL)
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
        val ETIQUETA = Regex("""(?m)^(Tú|Jarvis):""")
    }
}
