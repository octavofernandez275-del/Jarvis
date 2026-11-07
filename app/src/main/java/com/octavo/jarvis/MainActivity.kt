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
 * Pantalla de Jarvis: burbujas de conversación (tú en rojo, Jarvis en azul), reactor, estado en píldora,
 * barra de entrada redonda y accesos rápidos. Solo muestra y recoge toques: el cerebro está en Assistant,
 * así la conversación y el manos libres siguen aunque esta pantalla se cierre.
 */
class MainActivity : Activity(), Assistant.Ui {
    private lateinit var reactor: ReactorView
    private lateinit var status: TextView
    private lateinit var mensajes: LinearLayout
    private lateinit var vacio: View
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var sendBtn: Button
    private lateinit var micBtn: Button
    private lateinit var handsBtn: Button
    private lateinit var batBtn: Button
    private var pidioNotificaciones = false

    // Burbuja que se está escribiendo (la respuesta llega en fragmentos)
    private var burbuja: TextView? = null
    private var burbujaCont: View? = null
    private var burbujaTipo = SISTEMA
    private val burbujaTexto = StringBuilder()
    private var inicioLinea = true
    private var pulso: android.animation.ObjectAnimator? = null

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    /** El juego de boxeo abre jarvis://boxeo?r=<resumen>: se guarda en la memoria y se muestra en el chat. */
    private fun recibirEnlace(i: android.content.Intent?) {
        val d = i?.data ?: return
        if (d.scheme != "jarvis" || d.host != "boxeo") return
        val r = d.getQueryParameter("r")?.trim().orEmpty().take(280)
        if (r.isEmpty()) return
        Memory.agregar(this, r)
        nuevaBurbuja(SISTEMA)
        anadir("🥊 Anotado en mi memoria: $r")
        i.data = null
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recibirEnlace(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Assistant.iniciar(this)
        window.statusBarColor = ReactorView.FONDO
        window.navigationBarColor = ReactorView.FONDO

        reactor = ReactorView(this)
        status = TextView(this).apply {
            textSize = 11.5f
            typeface = Typeface.MONOSPACE
            setTextColor(ReactorView.AZUL_CLARO)
            gravity = Gravity.CENTER
            maxLines = 2
            setPadding(dp(14), dp(5), dp(14), dp(5))
            background = Tema.pildora(this@MainActivity, ReactorView.AZUL)
        }
        val estadoFila = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(2), dp(16), dp(6))
            addView(status, LinearLayout.LayoutParams(-2, -2))
        }

        // Conversación: lista de burbujas + pantalla de bienvenida con atajos cuando está vacía
        mensajes = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(10))
        }
        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(mensajes)
        }
        vacio = bienvenida()
        val centro = FrameLayout(this).apply {
            addView(scroll, FrameLayout.LayoutParams(-1, -1))
            addView(vacio, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        }

        // Barra de entrada: cápsula con el texto y dos botones redondos
        input = EditText(this).apply {
            hint = HINT
            isEnabled = false
            imeOptions = EditorInfo.IME_ACTION_SEND
            maxLines = 4
            textSize = 15.5f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.argb(140, 159, 232, 255))
            setPadding(dp(8), dp(10), dp(8), dp(10))
            background = null
        }
        micBtn = Button(this).apply { text = MIC; isEnabled = false }
        sendBtn = Button(this).apply { text = "➤"; isEnabled = false }
        Tema.circulo(micBtn, ReactorView.AZUL)
        Tema.circulo(sendBtn, ReactorView.AZUL, lleno = true)
        Tema.tactil(micBtn); Tema.tactil(sendBtn)
        val fila1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(3), dp(6), dp(3))
            background = Tema.caja(this@MainActivity, Tema.SUPERFICIE, Tema.transparente(ReactorView.AZUL, 110), 1, 28)
            addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(micBtn, LinearLayout.LayoutParams(dp(44), dp(44)).apply { leftMargin = dp(4) })
            addView(sendBtn, LinearLayout.LayoutParams(dp(44), dp(44)).apply { leftMargin = dp(6) })
        }
        handsBtn = Button(this).apply { text = MANOS_OFF; isEnabled = false }
        batBtn = Button(this).apply { text = "🔋 Batería" }
        val tareasBtn = Button(this).apply { text = "📋 Tareas" }
        val boxeoBtn = Button(this).apply { text = "🥊 Boxeo" }
        listOf(handsBtn, tareasBtn, boxeoBtn, batBtn).forEach { it.textSize = 12f }
        val fila2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(8), dp(10), dp(10))
            addView(handsBtn, LinearLayout.LayoutParams(0, dp(40), 1.5f).apply { rightMargin = dp(6) })
            addView(tareasBtn, LinearLayout.LayoutParams(0, dp(40), 1f).apply { rightMargin = dp(6) })
            addView(boxeoBtn, LinearLayout.LayoutParams(0, dp(40), 1f).apply { rightMargin = dp(6) })
            addView(batBtn, LinearLayout.LayoutParams(0, dp(40), 1f))
        }
        estilo(handsBtn, ReactorView.AZUL)
        estilo(batBtn, ReactorView.AZUL)
        estilo(tareasBtn, ReactorView.AZUL)
        estilo(boxeoBtn, ReactorView.ROJO)
        boxeoBtn.setOnClickListener { val r = Boxeo.abrir(this); r.despues?.invoke() ?: Toast.makeText(this, r.texto, Toast.LENGTH_LONG).show() }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Tema.fondo()
            addView(reactor, LinearLayout.LayoutParams(-1, dp(112)))
            addView(estadoFila)
            addView(centro, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(fila1, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(10), dp(4), dp(10), 0) })
            addView(fila2)
        }
        setContentView(root)
        recibirEnlace(intent)
        pintarEstado(Assistant.estadoTexto)

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

    /** Pantalla de bienvenida: saludo y atajos para empezar. */
    private fun bienvenida(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18), dp(10), dp(18), dp(10))
        }
        col.addView(TextView(this).apply {
            text = "¿En qué te ayudo?"
            textSize = 22f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(ReactorView.AZUL)
            gravity = Gravity.CENTER
        })
        col.addView(TextView(this).apply {
            text = "Escríbeme, háblame o toca un atajo"
            textSize = 13f
            setTextColor(Color.argb(170, 159, 232, 255))
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(14))
        })
        fun chip(texto: String, accion: () -> Unit) {
            val b = Button(this).apply { text = texto; textSize = 13f }
            Tema.boton(b, ReactorView.AZUL)
            b.setOnClickListener { accion() }
            col.addView(b, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(3), 0, dp(3)) })
        }
        fun enviar(t: String) {
            if (Assistant.modeloListo) Assistant.enviarTexto(t) else Toast.makeText(this, "Jarvis está arrancando, un momento…", Toast.LENGTH_SHORT).show()
        }
        fun escribir(t: String) { input.setText(t); input.setSelection(t.length); input.requestFocus() }
        chip("📰  Noticias de hoy en Argentina") { enviar("¿Cuáles son las noticias de hoy en Argentina?") }
        chip("🔎  Investiga un tema…") { escribir("Investiga ") }
        chip("📊  Haz una presentación sobre…") { escribir("Haz una presentación sobre ") }
        chip("🖼  Genera una imagen de…") { escribir("Genera una imagen de ") }
        return col
    }

    /** Botón con borde de neón del color de acento (azul o rojo). */
    private fun estilo(b: Button, acento: Int) { Tema.boton(b, acento); Tema.compacto(b, 12f) }

    private fun copiar(t: CharSequence) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("Jarvis", t))
        Toast.makeText(this, "Copiado", Toast.LENGTH_SHORT).show()
    }

    // ---------- Burbujas ----------

    private fun cerrarBurbuja() {
        if (burbuja != null && burbujaTipo == SISTEMA && burbuja?.text.isNullOrBlank()) mensajes.removeView(burbujaCont)
        burbuja = null
        burbujaCont = null
        burbujaTexto.setLength(0)
    }

    private fun nuevaBurbuja(tipo: Int) {
        cerrarBurbuja()
        burbujaTipo = tipo
        val ancho = resources.displayMetrics.widthPixels
        val tv = TextView(this).apply {
            textSize = if (tipo == SISTEMA) 11f else 15.5f
            if (tipo == SISTEMA) typeface = Typeface.MONOSPACE
            setLineSpacing(dp(2).toFloat(), 1f)
            setTextColor(if (tipo == SISTEMA) Color.argb(170, 159, 232, 255) else ReactorView.TEXTO)
            setLinkTextColor(ReactorView.AZUL_CLARO)
            maxWidth = (ancho * (if (tipo == USUARIO) 0.82 else 0.94)).toInt()
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = when (tipo) {
                USUARIO -> Tema.caja(this@MainActivity, Tema.transparente(ReactorView.ROJO, 38), Tema.transparente(ReactorView.ROJO, 150), 1, 18)
                JARVIS -> Tema.caja(this@MainActivity, Tema.transparente(ReactorView.AZUL, 26), Tema.transparente(ReactorView.AZUL, 120), 1, 18)
                else -> null
            }
            setOnLongClickListener { if (text.isNotBlank()) { copiar(text); true } else false }
        }
        val cont = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (tipo == USUARIO) Gravity.END else Gravity.START
            if (tipo == JARVIS) addView(TextView(this@MainActivity).apply {
                text = "J.A.R.V.I.S."
                textSize = 10f
                letterSpacing = 0.15f
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                setTextColor(ReactorView.AZUL)
                setPadding(dp(8), 0, 0, dp(2))
            })
            addView(tv, LinearLayout.LayoutParams(-2, -2))
            visibility = if (tipo == SISTEMA) View.GONE else View.VISIBLE
        }
        mensajes.addView(cont, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(10), dp(5), dp(10), dp(5)) })
        if (tipo != SISTEMA) { Tema.aparecer(cont); vacio.visibility = View.GONE }
        while (mensajes.childCount > 80) mensajes.removeViewAt(0)
        burbuja = tv
        burbujaCont = cont
        if (tipo == JARVIS) tv.text = "…"
    }

    private fun anadir(t: String) {
        if (burbuja == null) nuevaBurbuja(SISTEMA)
        val tv = burbuja ?: return
        burbujaTexto.append(t)
        val limpio = burbujaTexto.toString().trim()
        if (limpio.isEmpty()) {
            if (burbujaTipo == JARVIS) tv.text = "…"
            return
        }
        burbujaCont?.visibility = View.VISIBLE
        if (limpio.contains("http")) {
            val sp = android.text.SpannableString(limpio)
            android.text.util.Linkify.addLinks(sp, android.text.util.Linkify.WEB_URLS)
            tv.text = sp
            tv.movementMethod = android.text.method.LinkMovementMethod.getInstance()
        } else {
            tv.text = limpio
        }
    }

    /** Reparte el texto que llega: cada "Tú:" o "Jarvis:" al inicio de línea abre una burbuja nueva. */
    private fun alimentar(t: String) {
        if (t.isEmpty()) return
        val pre = if (inicioLinea) "\n" else ""
        val s = pre + t
        inicioLinea = s.endsWith("\n")
        var desde = pre.length
        for (m in ETIQUETA.findAll(s)) {
            if (m.range.first > desde) anadir(s.substring(desde, m.range.first))
            nuevaBurbuja(when (m.groupValues[1]) { "Tú" -> USUARIO; "Memoria" -> SISTEMA; else -> JARVIS })
            desde = m.range.last + 1
        }
        if (desde < s.length) anadir(s.substring(desde))
    }

    /** Si estás leyendo mensajes viejos no te arrastra al final; si estás abajo, te acompaña. */
    private fun alFinal(): Boolean {
        val h = scroll.getChildAt(0) ?: return true
        return h.bottom - (scroll.height + scroll.scrollY) < dp(120)
    }

    private fun pintarEstado(s: String) {
        val color = when {
            s.contains("Escuch", true) -> ReactorView.ROJO
            s.contains("Respond", true) || s.contains("Pensando", true) || s.contains("Buscando", true) ||
                s.contains("arranc", true) || s.contains("Reinici", true) || s.contains("Cargando", true) -> Tema.AMBAR
            s.contains("listo", true) -> Tema.VERDE
            else -> ReactorView.AZUL_CLARO
        }
        val sp = android.text.SpannableStringBuilder("● ").append(s.ifBlank { "…" })
        sp.setSpan(ForegroundColorSpan(color), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        status.text = sp
        status.background = Tema.pildora(this, color)
    }

    // ---------- Ciclo de vida ----------

    override fun onStart() {
        super.onStart()
        Assistant.ui = this
        Assistant.visible = true
        // Repintar lo ocurrido mientras la pantalla no estaba a la vista
        mensajes.removeAllViews()
        burbuja = null; burbujaCont = null; burbujaTexto.setLength(0); inicioLinea = true
        val previo = Assistant.transcript.toString().takeLast(8_000)
        vacio.visibility = if (previo.isBlank()) View.VISIBLE else View.GONE
        alimentar(previo)
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        pintarEstado(Assistant.estadoTexto)
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
        val abajo = alFinal()
        alimentar(t)
        if (abajo) scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    override fun estado(s: String) { pintarEstado(s) }

    override fun escuchando(on: Boolean) {
        micBtn.text = if (on) STOP else MIC
        Tema.circulo(micBtn, if (on) ReactorView.ROJO else ReactorView.AZUL, lleno = on)   // rojo y latiendo mientras escucha
        micBtn.layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { leftMargin = dp(4) }
        pulso?.cancel()
        micBtn.alpha = 1f
        if (on) pulso = android.animation.ObjectAnimator.ofFloat(micBtn, "alpha", 1f, 0.45f).apply {
            duration = 650; repeatMode = android.animation.ValueAnimator.REVERSE
            repeatCount = android.animation.ValueAnimator.INFINITE; start()
        }
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
        const val SISTEMA = 0
        const val USUARIO = 1
        const val JARVIS = 2
        const val MIC = "🎤"
        const val STOP = "⏹"
        const val HINT = "Escribe a Jarvis…"
        const val MANOS_OFF = "🎧 Manos: OFF"
        const val MANOS_ON = "🎧 Manos: ON"
        val ETIQUETA = Regex("""\n(Tú|Jarvis|Memoria): ?""")
    }
}
