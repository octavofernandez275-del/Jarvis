package com.jarvis.chat

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.Settings
import android.provider.CalendarContract
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.Editable
import android.text.TextWatcher
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val BG = Color.parseColor("#02060B")
private val PANEL = Color.parseColor("#071626")
private val TEXT = Color.parseColor("#D9F2FF")
private val MUTE = Color.parseColor("#7FCBFA")
private val USER = Color.parseColor("#078DEB")
private val USER_TEXT = Color.parseColor("#FFFFFF")
private val ERR = Color.parseColor("#FF7D91")

private val PRESETS = listOf(
    Triple("Gemini (con plan gratuito)", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.5-flash"),
    Triple("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini"),
    Triple("Anthropic (Claude)", "https://api.anthropic.com/v1", "claude-sonnet-5-5"),
    Triple("jarvis serve (Termux)", "http://127.0.0.1:8000/v1", ""),
    Triple("Personalizado", "", "")
)

private const val SYSTEM =
    "Eres Jarvis, un asistente personal que vive en el celular del usuario. " +
    "Responde en español, de forma clara y breve, salvo que te pidan otra cosa. " +
    "Tienes herramientas para buscar en internet, leer páginas, abrir apps y enlaces, " +
    "crear alarmas, temporizadores, eventos de calendario, borradores de correo y abrir mapas. " +
    "Úsalas cuando ayuden. Después de buscar, resume lo encontrado e indica la fuente. " +
    "También llevas la lista de pendientes del usuario, lees su calendario y lo pones al tanto " +
    "con un resumen de clima, agenda, pendientes y noticias."

class MainActivity : Activity() {
    private lateinit var log: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var sendBtn: Button
    private lateinit var status: TextView
    private var history = JSONArray()
    private var busy = false
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var fromVoice = false
    private val prefs by lazy { getSharedPreferences("jarvis", MODE_PRIVATE) }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, r: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(r).toFloat()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(2, 6, 11)
        window.navigationBarColor = Color.rgb(2, 6, 11)
        window.decorView.systemUiVisibility = 0

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            fitsSystemWindows = true
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }

        fun label(text: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(null, Typeface.BOLD)
        }
        fun hudPanel() = GradientDrawable().apply {
            setColor(Color.argb(225, 3, 15, 28))
            cornerRadius = dp(12).toFloat()
            setStroke(dp(1), Color.rgb(0, 117, 196))
        }
        fun hudButton(text: String, action: () -> Unit) = Button(this).apply {
            this.text = text
            isAllCaps = false
            textSize = 12f
            setTextColor(TEXT)
            background = GradientDrawable().apply {
                setColor(Color.rgb(5, 24, 41)); cornerRadius = dp(9).toFloat()
                setStroke(dp(1), Color.rgb(0, 105, 180))
            }
            minWidth = 0; minimumWidth = 0
            setPadding(dp(8), dp(2), dp(8), dp(2))
            stateListAnimator = null
            setOnClickListener { action() }
        }

        // Encabezado HUD
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = hudPanel()
            setPadding(dp(14), dp(8), dp(8), dp(8))
        }
        val heading = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        heading.addView(label("J.A.R.V.I.S.", 20f, TEXT, true))
        heading.addView(label("ASISTENTE PERSONAL · INTERFAZ ACTIVA", 9f, MUTE))
        header.addView(heading, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        status = label("Inicializando…", 11f, MUTE)
        status.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        header.addView(status)
        val settingsButton = hudButton("⚙") { settings() }
        header.addView(settingsButton, LinearLayout.LayoutParams(dp(44), dp(42)).apply { leftMargin = dp(5) })
        root.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(8) })
        setStatus("Listo")

        // Núcleo holográfico central, adaptado a pantalla vertical
        val coreWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = hudPanel()
            setPadding(dp(8), dp(10), dp(8), dp(10))
        }
        val core = TextView(this).apply {
                        // Un núcleo compacto para móvil; el texto horizontal se mantiene en pantallas anchas.
            text = "J A R V I S"
            gravity = Gravity.CENTER
            textSize = 19f
            letterSpacing = 0.18f
            setTextColor(Color.WHITE)
            setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(2, 19, 39), Color.rgb(0, 71, 132), Color.rgb(2, 10, 20))).apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(2), Color.rgb(31, 166, 255))
            }
            elevation = dp(8).toFloat()
        }
        coreWrap.addView(core, LinearLayout.LayoutParams(dp(132), dp(132)))
        coreWrap.addView(label("S I S T E M A   A C T I V O", 10f, Color.rgb(71, 190, 255), true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(1))
        })
        coreWrap.addView(label("Núcleo de asistencia", 10f, MUTE).apply { gravity = Gravity.CENTER })
        root.addView(coreWrap, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(8) })

        // Indicadores visuales de referencia; no fingen lecturas reales del hardware.
        val system = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = hudPanel()
            setPadding(dp(5), dp(8), dp(5), dp(8))
        }
        val indicators = listOf("● VOZ", "◇ IA", "◈ HERRAMIENTAS")
        indicators.forEachIndexed { index, item ->
            val cell = label(item, 10f, if (index == 0) Color.rgb(65, 201, 255) else MUTE, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(2), 0, dp(2), 0)
            }
            system.addView(cell, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        root.addView(system, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(8) })

        // Accesos rápidos conservan acciones reales ya presentes en la aplicación.
        val shortcuts = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        lateinit var voiceToggle: Button
        shortcuts.addView(hudButton("🎙 HABLAR") { listen() }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { rightMargin = dp(4) })
        shortcuts.addView(hudButton("＋ NUEVO") { newChat() }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { leftMargin = dp(2); rightMargin = dp(2) })
        voiceToggle = hudButton(if (speakOn()) "🔊 VOZ" else "🔇 VOZ") {
            prefs.edit().putBoolean("speak", !speakOn()).apply()
            voiceToggle.text = if (speakOn()) "🔊 VOZ" else "🔇 VOZ"
            if (!speakOn()) tts?.stop()
        }
        shortcuts.addView(voiceToggle, LinearLayout.LayoutParams(0, dp(42), 1f).apply { leftMargin = dp(4) })
        root.addView(shortcuts, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(8) })

        // Conversación
        scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setPadding(dp(2), dp(2), dp(2), dp(4))
        }
        log = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(12))
        }
        scroll.addView(log)

        // Entrada inferior estilo panel tecnológico
        val inputPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = hudPanel()
            setPadding(dp(8), dp(5), dp(7), dp(5))
        }
        input = EditText(this).apply {
            hint = "Escribe una orden para Jarvis…"
            setHintTextColor(Color.rgb(93, 142, 174))
            setTextColor(TEXT)
            textSize = 15f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 4
            background = GradientDrawable().apply {
                setColor(Color.rgb(3, 12, 22)); cornerRadius = dp(22).toFloat()
                setStroke(dp(1), Color.rgb(12, 72, 112))
            }
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        sendBtn = Button(this).apply {
            textSize = 19f
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(0, 156, 255), Color.rgb(0, 74, 155))).apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(1), Color.rgb(91, 207, 255))
            }
            minWidth = 0; minHeight = 0; minimumWidth = 0; minimumHeight = 0
            setPadding(0, 0, 0, 0)
            stateListAnimator = null
            setOnClickListener { if (input.text.toString().trim().isEmpty()) listen() else send() }
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { refreshAction() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        refreshAction()
        inputPanel.addView(input, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        inputPanel.addView(sendBtn, LinearLayout.LayoutParams(dp(48), dp(48)).apply { leftMargin = dp(7) })

        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        root.addView(label("CANAL SEGURO · ESPAÑOL", 9f, Color.rgb(44, 134, 190)).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(3), 0, dp(3))
        })
        root.addView(inputPanel)
        setContentView(root)

        tts = TextToSpeech(this) { st ->
            if (st == TextToSpeech.SUCCESS) {
                tts?.language = Locale.forLanguageTag("es-MX")
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) { runOnUiThread { setStatus("Hablando…") } }
                    override fun onDone(id: String?) { runOnUiThread { setStatus("Listo") } }
                    override fun onError(id: String?) { runOnUiThread { setStatus("Listo") } }
                })
                ttsReady = true
            }
        }
        welcome()
        if (serviceWanted()) startBg()
        handleIntent(intent)
    }

    private fun flat(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 20f
        minWidth = dp(48)
        minimumWidth = dp(48)
        setTextColor(TEXT)
        setBackgroundColor(Color.TRANSPARENT)
        setOnClickListener { action() }
    }

    private fun welcome() = hint(
        if ((prefs.getString("key", "") ?: "").isEmpty())
            "Toca Ajustes, elige un proveedor y pega tu API key para empezar."
        else "Escribe algo para empezar a hablar con Jarvis."
    )

    private fun hint(msg: String) {
        val tv = TextView(this).apply {
            text = msg
            setTextColor(MUTE)
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(48), dp(24), dp(24))
        }
        log.addView(tv, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    private fun bubble(msg: String, user: Boolean): TextView {
        val tv = TextView(this).apply {
            text = msg
            textSize = 16f
            setTextIsSelectable(true)
            setTextColor(if (user) USER_TEXT else TEXT)
            background = GradientDrawable().apply { setColor(if (user) USER else PANEL); cornerRadius = dp(16).toFloat(); setStroke(dp(1), if (user) Color.rgb(44, 180, 255) else Color.rgb(0, 82, 130)) }
            setPadding(dp(14), dp(10), dp(14), dp(10))
            maxWidth = (resources.displayMetrics.widthPixels * 0.85).toInt()
        }
        val lp = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            topMargin = dp(8)
            gravity = if (user) Gravity.END else Gravity.START
        }
        log.addView(tv, lp)
        scrollDown()
        return tv
    }

    private fun scrollDown() {
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun newChat() {
        if (busy) return
        tts?.stop()
        history = JSONArray()
        log.removeAllViews()
        welcome()
    }

    private fun settings() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, PRESETS.map { it.first }
        )
        box.addView(spinner)
        fun field(label: String, key: String, def: String, secret: Boolean): EditText {
            val e = EditText(this)
            e.hint = label
            e.setText(prefs.getString(key, def))
            e.inputType = InputType.TYPE_CLASS_TEXT or
                (if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_URI)
            box.addView(e)
            return e
        }
        val url = field("Dirección de la API", "url", PRESETS[0].second, false)
        val model = field("Modelo", "model", PRESETS[0].third, false)
        val key = field("API key", "key", "", true)
        val about = EditText(this)
        about.hint = "Sobre mí (opcional): datos y preferencias para Jarvis"
        about.setText(prefs.getString("about", ""))
        about.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        about.minLines = 3
        box.addView(about)
        val bg = android.widget.Switch(this)
        bg.text = "Jarvis siempre atento: di «Jarvis» y tu orden"
        bg.isChecked = prefs.getBoolean("bg", false)
        bg.setPadding(0, dp(16), 0, dp(8))
        box.addView(bg)
        val city = EditText(this)
        city.hint = "Tu ciudad (para el clima)"
        city.setText(prefs.getString("city", ""))
        box.addView(city)
        val topics = EditText(this)
        topics.hint = "Temas de noticias, separados por coma"
        topics.setText(prefs.getString("topics", ""))
        box.addView(topics)
        val briefHour = EditText(this)
        briefHour.hint = "Hora del resumen diario (0 a 23)"
        briefHour.inputType = InputType.TYPE_CLASS_NUMBER
        briefHour.setText(prefs.getString("brief_hour", "8"))
        box.addView(briefHour)
        val briefOn = android.widget.Switch(this)
        briefOn.text = "Resumen diario automático"
        briefOn.isChecked = prefs.getBoolean("brief_on", false)
        briefOn.setPadding(0, dp(12), 0, dp(8))
        box.addView(briefOn)
        val saved = url.text.toString()
        val idx = PRESETS.indexOfFirst { it.second == saved }
        spinner.setSelection(if (idx < 0) PRESETS.size - 1 else idx)
        var ready = false
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (!ready) { ready = true; return }
                if (PRESETS[pos].second.isNotEmpty()) {
                    url.setText(PRESETS[pos].second)
                    model.setText(PRESETS[pos].third)
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        AlertDialog.Builder(this)
            .setTitle("Ajustes")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Guardar") { _, _ ->
                prefs.edit()
                    .putString("url", url.text.toString().trim().ifEmpty { PRESETS[0].second })
                    .putString("model", model.text.toString().trim())
                    .putString("key", key.text.toString().trim())
                    .putString("about", about.text.toString().trim())
                    .putBoolean("bg", bg.isChecked)
                    .putString("city", city.text.toString().trim())
                    .putString("topics", topics.text.toString().trim())
                    .putString("brief_hour", briefHour.text.toString().trim().ifEmpty { "8" })
                    .putBoolean("brief_on", briefOn.isChecked)
                    .apply()
                applyBackground()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun open(path: String, post: Boolean): HttpURLConnection {
        val base = (prefs.getString("url", PRESETS[0].second) ?: "").trimEnd('/')
        val key = prefs.getString("key", "") ?: ""
        val c = URL(base + path).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 300000
        if (key.isNotEmpty()) c.setRequestProperty("Authorization", "Bearer $key")
        if (post) {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
        }
        return c
    }

    private fun pickModel(): String {
        val saved = prefs.getString("model", PRESETS[0].third) ?: ""
        if (saved.isNotEmpty()) return saved
        return try {
            val c = open("/models", false)
            JSONObject(c.inputStream.bufferedReader().readText())
                .getJSONArray("data").getJSONObject(0).getString("id")
        } catch (e: Exception) {
            "default"
        }
    }

    private fun withSystem(): JSONArray {
        val about = prefs.getString("about", "") ?: ""
        val now = SimpleDateFormat("EEEE d 'de' MMMM yyyy, HH:mm (zzzz)", Locale.forLanguageTag("es-MX")).format(Date())
        var sys = SYSTEM + "\n\nFecha y hora actual del usuario: " + now
        if (about.isNotEmpty()) sys += "\n\nLo que sabes del usuario:\n" + about
        val all = JSONArray().put(JSONObject().put("role", "system").put("content", sys))
        for (i in 0 until history.length()) all.put(history.get(i))
        return all
    }

    // ---------- Estado y segundo plano ----------

    private fun setStatus(t: String) {
        status.text = t
        status.setTextColor(if (t == "Listo") MUTE else USER)
        JarvisService.busy = t != "Listo"
        if (t == "Listo") JarvisService.busyUntil = System.currentTimeMillis() + 1500
    }

    private fun refreshAction() {
        sendBtn.text = if (input.text.toString().trim().isEmpty()) "🎤" else "↑"
    }

    private fun setBusy(b: Boolean) {
        busy = b
        sendBtn.isEnabled = !b
        sendBtn.alpha = if (b) 0.5f else 1f
    }

    private fun handleIntent(i: Intent?) {
        val br = i?.getStringExtra("briefing")
        if (i != null && !br.isNullOrEmpty()) {
            i.removeExtra("briefing")
            input.post {
                if (history.length() == 0) log.removeAllViews()
                history.put(JSONObject().put("role", "user").put("content", "Dame mi resumen del día."))
                history.put(JSONObject().put("role", "assistant").put("content", br))
                bubble(br, false)
                speak(br)
            }
        }
        val cmd = i?.getStringExtra("command")
        if (i != null && !cmd.isNullOrEmpty()) {
            i.removeExtra("command")
            fromVoice = true
            input.post {
                if (busy) return@post
                input.setText(cmd)
                send()
            }
        }
        if (i?.getBooleanExtra("listen", false) == true) {
            i.removeExtra("listen")
            input.post { listen() }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun startBg() {
        val i = Intent(this, JarvisService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
    }

    private fun serviceWanted() = prefs.getBoolean("bg", false) || prefs.getBoolean("brief_on", false)

    private fun applyBackground() {
        if (!serviceWanted()) {
            stopService(Intent(this, JarvisService::class.java))
            return
        }
        val need = mutableListOf<String>()
        fun want(perm: String) {
            if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) need.add(perm)
        }
        if (prefs.getBoolean("bg", false)) want(android.Manifest.permission.RECORD_AUDIO)
        if (prefs.getBoolean("brief_on", false)) want(android.Manifest.permission.READ_CALENDAR)
        if (Build.VERSION.SDK_INT >= 33) want(android.Manifest.permission.POST_NOTIFICATIONS)
        if (need.isNotEmpty()) {
            requestPermissions(need.toTypedArray(), 5)
        } else {
            startBg()
            if (prefs.getBoolean("bg", false)) askOverlay()
        }
    }

    private fun askOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
            } catch (e: Exception) {
            }
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code == 5 && serviceWanted()) {
            startBg()
            if (prefs.getBoolean("bg", false)) askOverlay()
        }
    }

    private fun ensureCalendar() {
        if (checkSelfPermission(android.Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            runOnUiThread { requestPermissions(arrayOf(android.Manifest.permission.READ_CALENDAR), 6) }
        }
    }

    // ---------- Voz ----------

    private fun speakOn() = prefs.getBoolean("speak", true)

    private fun speak(t: String) {
        if (!speakOn() || !ttsReady) return
        tts?.speak(t.replace(Regex("[*#_`>]"), ""), TextToSpeech.QUEUE_FLUSH, null, "jv")
    }

    private fun listen() {
        tts?.stop()
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-MX")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Habla con Jarvis")
        try {
            setStatus("Escuchando…")
            startActivityForResult(i, 77)
        } catch (e: Exception) {
            bubble("Tu celular no tiene reconocimiento de voz disponible.", false).setTextColor(ERR)
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 77) setStatus("Listo")
        if (requestCode == 77 && resultCode == RESULT_OK) {
            val t = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return
            input.setText(t)
            send()
        }
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }

    // ---------- Herramientas ----------

    private fun str(desc: String) = JSONObject().put("type", "string").put("description", desc)
    private fun num(desc: String) = JSONObject().put("type", "number").put("description", desc)

    private fun tool(name: String, desc: String, props: JSONObject, required: List<String>) =
        JSONObject().put("type", "function").put(
            "function", JSONObject().put("name", name).put("description", desc).put(
                "parameters", JSONObject().put("type", "object").put("properties", props)
                    .put("required", JSONArray(required))
            )
        )

    private fun tools() = JSONArray()
        .put(tool("web_search", "Busca en internet y devuelve los mejores resultados.",
            JSONObject().put("query", str("Qué buscar")), listOf("query")))
        .put(tool("fetch_url", "Descarga una página web y devuelve su texto.",
            JSONObject().put("url", str("Dirección completa")), listOf("url")))
        .put(tool("open_url", "Abre una dirección web en el navegador del celular.",
            JSONObject().put("url", str("Dirección completa")), listOf("url")))
        .put(tool("open_app", "Abre una app instalada, por su nombre.",
            JSONObject().put("name", str("Nombre de la app")), listOf("name")))
        .put(tool("set_alarm", "Crea una alarma.",
            JSONObject().put("hour", num("Hora, 0 a 23")).put("minute", num("Minuto, 0 a 59"))
                .put("label", str("Etiqueta")), listOf("hour", "minute")))
        .put(tool("set_timer", "Inicia un temporizador.",
            JSONObject().put("seconds", num("Duración en segundos")).put("label", str("Etiqueta")),
            listOf("seconds")))
        .put(tool("add_calendar_event", "Prepara un evento en el calendario; el usuario solo confirma.",
            JSONObject().put("title", str("Título"))
                .put("start", str("Inicio en hora local, formato yyyy-MM-dd'T'HH:mm"))
                .put("end", str("Fin, mismo formato (opcional)"))
                .put("location", str("Lugar (opcional)")), listOf("title", "start")))
        .put(tool("compose_email", "Prepara un correo en la app de correo; el usuario solo lo envía.",
            JSONObject().put("to", str("Destinatario (opcional)")).put("subject", str("Asunto"))
                .put("body", str("Texto")), listOf("subject", "body")))
        .put(tool("open_maps", "Busca un lugar o una ruta en el mapa.",
            JSONObject().put("query", str("Lugar o dirección")), listOf("query")))
        .put(tool("briefing", "Reúne clima, agenda, pendientes y noticias del usuario para ponerlo al tanto.",
            JSONObject().put("nota", str("Opcional")), listOf()))
        .put(tool("read_calendar", "Lee los próximos eventos del calendario del usuario.",
            JSONObject().put("days", num("Cuántos días hacia adelante, de 1 a 14")), listOf()))
        .put(tool("add_task", "Anota un pendiente en la lista del usuario.",
            JSONObject().put("text", str("El pendiente")), listOf("text")))
        .put(tool("list_tasks", "Muestra la lista de pendientes del usuario.",
            JSONObject().put("nota", str("Opcional")), listOf()))
        .put(tool("complete_task", "Marca como hecho un pendiente, buscándolo por texto.",
            JSONObject().put("text", str("Parte del texto del pendiente")), listOf("text")))

    private val pending = mutableListOf<Intent>()

    private fun runTool(name: String, a: JSONObject): String {
        return try {
            when (name) {
                "web_search" -> webSearch(a.getString("query"))
                "fetch_url" -> fetchUrl(a.getString("url"))
                "open_url" -> {
                    pending.add(Intent(Intent.ACTION_VIEW, Uri.parse(a.getString("url"))))
                    "Se abrirá en el navegador."
                }
                "open_app" -> openApp(a.getString("name"))
                "set_alarm" -> {
                    pending.add(
                        Intent(AlarmClock.ACTION_SET_ALARM)
                            .putExtra(AlarmClock.EXTRA_HOUR, a.getInt("hour"))
                            .putExtra(AlarmClock.EXTRA_MINUTES, a.getInt("minute"))
                            .putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label", "Jarvis"))
                            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    )
                    "Alarma preparada."
                }
                "set_timer" -> {
                    pending.add(
                        Intent(AlarmClock.ACTION_SET_TIMER)
                            .putExtra(AlarmClock.EXTRA_LENGTH, a.getInt("seconds"))
                            .putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label", "Jarvis"))
                            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    )
                    "Temporizador preparado."
                }
                "briefing" -> {
                    ensureCalendar()
                    Brief.gather(this)
                }
                "read_calendar" -> {
                    ensureCalendar()
                    Brief.agenda(this, a.optInt("days", 2))
                }
                "add_task" -> Brief.addTask(this, a.getString("text"))
                "list_tasks" -> Brief.taskList(this)
                "complete_task" -> Brief.doneTask(this, a.getString("text"))
                "add_calendar_event" -> calendarEvent(a)
                "compose_email" -> {
                    val i = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
                        .putExtra(Intent.EXTRA_SUBJECT, a.getString("subject"))
                        .putExtra(Intent.EXTRA_TEXT, a.getString("body"))
                    val to = a.optString("to", "")
                    if (to.isNotEmpty()) i.putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
                    pending.add(i)
                    "Borrador de correo preparado."
                }
                "open_maps" -> {
                    pending.add(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(a.getString("query")))))
                    "Se abrirá el mapa."
                }
                else -> "Herramienta desconocida: $name"
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    private fun calendarEvent(a: JSONObject): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US)
        val start = fmt.parse(a.getString("start"))!!.time
        val endText = a.optString("end", "")
        val end = if (endText.isEmpty()) start + 3600000L else fmt.parse(endText)!!.time
        pending.add(
            Intent(Intent.ACTION_INSERT)
                .setData(CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
                .putExtra(CalendarContract.Events.TITLE, a.getString("title"))
                .putExtra(CalendarContract.Events.EVENT_LOCATION, a.optString("location", ""))
        )
        return "Evento preparado; el usuario debe confirmarlo en el calendario."
    }

    private fun openApp(name: String): String {
        val pm = packageManager
        val list = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val hit = list.firstOrNull { it.loadLabel(pm).toString().contains(name, ignoreCase = true) }
            ?: return "No encontré una app llamada $name."
        val i = pm.getLaunchIntentForPackage(hit.activityInfo.packageName)
            ?: return "No se pudo abrir $name."
        pending.add(i)
        return "Se abrirá ${hit.loadLabel(pm)}."
    }

    private fun get(url: String, max: Int): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 15000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
        return c.inputStream.bufferedReader().use { r ->
            val sb = StringBuilder()
            val buf = CharArray(8192)
            while (sb.length < max) {
                val n = r.read(buf)
                if (n < 0) break
                sb.append(buf, 0, n)
            }
            sb.toString()
        }
    }

    private fun clean(h: String) = h
        .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
        .replace(Regex("(?s)<[^>]+>"), " ")
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#x27;", "'").replace("&nbsp;", " ")
        .replace(Regex("\\s+"), " ").trim()

    private fun fetchUrl(u: String): String {
        val url = if (u.startsWith("http")) u else "https://$u"
        return clean(get(url, 300000)).take(6000)
    }

    private fun webSearch(q: String): String {
        val html = get("https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(q, "UTF-8"), 250000)
        val links = Regex("(?s)class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>").findAll(html).toList()
        val snips = Regex("(?s)class=\"result__snippet\"[^>]*>(.*?)</a>").findAll(html).toList()
        if (links.isEmpty()) return "No obtuve resultados."
        return links.take(5).mapIndexed { i, m ->
            var u = m.groupValues[1]
            val k = u.indexOf("uddg=")
            if (k >= 0) u = URLDecoder.decode(u.substring(k + 5).substringBefore("&"), "UTF-8")
            val snip = snips.getOrNull(i)?.let { clean(it.groupValues[1]) } ?: ""
            "${i + 1}. ${clean(m.groupValues[2])}\n$u\n$snip"
        }.joinToString("\n\n")
    }

    // ---------- Envío (bucle de agente) ----------

    private fun send() {
        val text = input.text.toString().trim()
        if (text.isEmpty() || busy) return
        val local = (prefs.getString("url", PRESETS[0].second) ?: "").contains("127.0.0.1")
        if (!local && (prefs.getString("key", "") ?: "").isEmpty()) {
            if (history.length() == 0) log.removeAllViews()
            bubble("Falta la API key. Toca Ajustes, elige un proveedor y pega tu clave.", false)
                .setTextColor(ERR)
            return
        }
        tts?.stop()
        input.setText("")
        setBusy(true)
        setStatus("Pensando…")
        if (history.length() == 0) log.removeAllViews()
        history.put(JSONObject().put("role", "user").put("content", text))
        bubble(text, true)
        val bot = bubble("…", false)

        Thread {
            pending.clear()
            var answer = ""
            try {
                val model = pickModel()
                val msgs = withSystem()
                for (step in 0 until 6) {
                    val body = JSONObject().put("model", model).put("messages", msgs).put("tools", tools())
                    val c = open("/chat/completions", true)
                    c.outputStream.use { it.write(body.toString().toByteArray()) }
                    if (c.responseCode !in 200..299) {
                        val err = c.errorStream?.bufferedReader()?.readText()?.take(300) ?: ""
                        throw Exception("Error ${c.responseCode}: $err")
                    }
                    val m = JSONObject(c.inputStream.bufferedReader().readText())
                        .getJSONArray("choices").getJSONObject(0).getJSONObject("message")
                    val calls = m.optJSONArray("tool_calls")
                    val content = if (m.isNull("content")) "" else m.getString("content")
                    if (calls == null || calls.length() == 0) {
                        answer = content
                        break
                    }
                    msgs.put(
                        JSONObject().put("role", "assistant")
                            .put("content", if (content.isEmpty()) JSONObject.NULL else content)
                            .put("tool_calls", calls)
                    )
                    for (i in 0 until calls.length()) {
                        val call = calls.getJSONObject(i)
                        val fn = call.getJSONObject("function")
                        val name = fn.getString("name")
                        val args = try {
                            JSONObject(fn.optString("arguments", "{}").ifEmpty { "{}" })
                        } catch (e: Exception) {
                            JSONObject()
                        }
                        runOnUiThread { bot.text = "Usando $name…" }
                        val result = runTool(name, args)
                        msgs.put(
                            JSONObject().put("role", "tool")
                                .put("tool_call_id", call.optString("id", "call_$i"))
                                .put("name", name)
                                .put("content", result)
                        )
                    }
                    if (step == 5) answer = "Hice demasiados pasos seguidos. Intenta pedirlo de otra forma."
                }
                if (answer.isEmpty()) answer = "(Respuesta vacía)"
                history.put(JSONObject().put("role", "assistant").put("content", answer))
                val shown = answer
                runOnUiThread { bot.text = shown; scrollDown(); speak(shown) }
            } catch (e: Exception) {
                history.remove(history.length() - 1)
                runOnUiThread {
                    bot.text = "No se pudo obtener respuesta: ${e.message}"
                    bot.setTextColor(ERR)
                }
            } finally {
                val toLaunch = pending.toList()
                pending.clear()
                runOnUiThread {
                    setBusy(false)
                    setStatus("Listo")
                    if (fromVoice) {
                        fromVoice = false
                        if (toLaunch.isEmpty()) moveTaskToBack(true)
                    }
                    for (i in toLaunch) {
                        try {
                            startActivity(i)
                        } catch (e: Exception) {
                            bubble("No pude abrir la acción: ${e.message}", false).setTextColor(ERR)
                        }
                    }
                }
            }
        }.start()
    }
}
