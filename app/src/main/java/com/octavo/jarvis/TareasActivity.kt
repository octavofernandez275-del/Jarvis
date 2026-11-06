package com.octavo.jarvis

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.*

/**
 * Pantalla de tareas: lista con tarjetas, botones para que Jarvis las haga, piloto automático y curiosidad.
 * Mismo estilo que la pantalla principal (fondo oscuro, azul y rojo).
 */
class TareasActivity : Activity() {
    private lateinit var lista: LinearLayout
    private lateinit var resumen: TextView
    private lateinit var input: EditText
    private lateinit var pilotoBtn: Button
    private lateinit var curiosidadBtn: Button
    private val abiertas = HashSet<Long>()

    private fun dp(n: Int) = Tema.dp(this, n)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Assistant.iniciar(this)   // por si se abre desde una notificación con el proceso recién creado
        window.statusBarColor = ReactorView.FONDO
        window.navigationBarColor = ReactorView.FONDO

        // Cabecera: reactor pequeño + título + contadores
        val titulo = TextView(this).apply {
            text = "TAREAS"
            textSize = 22f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(ReactorView.AZUL)
        }
        resumen = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(ReactorView.AZUL_CLARO)
        }
        val textos = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(titulo)
            addView(resumen)
        }
        val cabecera = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(4))
            addView(Button(this@TareasActivity).apply {
                text = "←"
                Tema.circulo(this, ReactorView.AZUL, tam = 40, texto = 20f)
                setOnClickListener { finish() }
                Tema.tactil(this)
            }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { rightMargin = dp(10) })
            addView(ReactorView(this@TareasActivity), LinearLayout.LayoutParams(dp(60), dp(60)))
            addView(textos, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(12) })
        }

        // Atajos para empezar una tarea típica
        val atajos = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(2), dp(8), dp(2))
        }
        fun atajo(texto: String, prefijo: String) {
            val b = Button(this).apply { text = texto; textSize = 12f }
            Tema.boton(b, ReactorView.AZUL)
            b.setOnClickListener {
                input.setText(prefijo)
                input.setSelection(prefijo.length)
                input.requestFocus()
            }
            atajos.addView(b, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(6) })
        }
        atajo("📊 Presentación", "Haz una presentación sobre ")
        atajo("📄 Documento", "Haz un documento de Word sobre ")
        atajo("📕 PDF", "Haz un PDF sobre ")
        atajo("🖼 Imagen", "Genera una imagen de ")
        atajo("🔎 Investigar", "Investiga ")
        atajo("⬇ Descargar", "Descarga ")
        val atajosScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(atajos)
        }

        // Nueva tarea
        input = EditText(this).apply {
            hint = "Nueva tarea… (ej: haz una presentación sobre la energía solar)"
            maxLines = 3
            setTextColor(Color.WHITE)
            setHintTextColor(Color.argb(140, 159, 232, 255))
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = Tema.caja(this@TareasActivity, Color.argb(60, 24, 182, 255), Color.argb(170, 24, 182, 255))
        }
        val agregarBtn = Button(this).apply { text = "＋" }
        val hacerBtn = Button(this).apply { text = "▶" }
        Tema.boton(agregarBtn, ReactorView.AZUL)
        Tema.boton(hacerBtn, ReactorView.ROJO)
        val fila1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(4), dp(10), dp(4))
            addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(agregarBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(6) })
            addView(hacerBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(6) })
        }

        // Piloto automático, curiosidad y sugerencias
        pilotoBtn = Button(this).apply { textSize = 12f }
        curiosidadBtn = Button(this).apply { textSize = 12f }
        val sugerirBtn = Button(this).apply { text = "💡 Sugerir"; textSize = 12f }
        Tema.boton(sugerirBtn, ReactorView.AZUL)
        val fila2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), 0, dp(10), dp(6))
            addView(pilotoBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
            addView(curiosidadBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
            addView(sugerirBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

        lista = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(lista)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Tema.fondo()
            addView(cabecera)
            addView(atajosScroll)
            addView(fila1)
            addView(fila2)
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        setContentView(root)

        agregarBtn.setOnClickListener { nueva(false) }
        hacerBtn.setOnClickListener { nueva(true) }
        sugerirBtn.setOnClickListener { Agente.sugerirAhora(this) }
        pilotoBtn.setOnClickListener {
            Ajustes.fijarPiloto(this, !Ajustes.piloto(this))
            pintar()
        }
        curiosidadBtn.setOnClickListener {
            Ajustes.fijarCuriosidad(this, !Ajustes.curiosidad(this))
            pintar()
        }
    }

    override fun onStart() {
        super.onStart()
        Tareas.alCambiar = { pintar() }
        pintar()
    }

    override fun onStop() {
        super.onStop()
        Tareas.alCambiar = null
    }

    private fun nueva(hacerYa: Boolean) {
        val texto = input.text.toString().trim()
        if (texto.isEmpty()) return
        val t = Tareas.agregar(this, texto)
        input.setText("")
        if (hacerYa) Agente.ejecutar(this, t.id)
    }

    private fun rango(estado: Int) = when (estado) {
        Tareas.EN_CURSO -> 0
        Tareas.ESPERA_OK -> 1
        Tareas.PENDIENTE -> 2
        Tareas.FALLO -> 3
        else -> 4
    }

    private fun pintar() {
        val todas = Tareas.todas(this)
        val pendientes = todas.count { it.estado == Tareas.PENDIENTE || it.estado == Tareas.ESPERA_OK }
        val enCurso = todas.count { it.estado == Tareas.EN_CURSO }
        val hechas = todas.count { it.estado == Tareas.HECHA }
        resumen.text = "$pendientes pendientes · $enCurso en curso · $hechas listas"

        val piloto = Ajustes.piloto(this)
        pilotoBtn.text = if (piloto) "🤖 Piloto: ON" else "🤖 Piloto: OFF"
        Tema.boton(pilotoBtn, if (piloto) ReactorView.ROJO else ReactorView.AZUL)
        val curiosidad = Ajustes.curiosidad(this)
        curiosidadBtn.text = if (curiosidad) "🔭 Curiosidad: ON" else "🔭 Curiosidad: OFF"
        Tema.boton(curiosidadBtn, if (curiosidad) ReactorView.ROJO else ReactorView.AZUL)

        lista.removeAllViews()
        if (todas.isEmpty()) {
            lista.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(24), dp(48), dp(24), dp(16))
                addView(TextView(context).apply { text = "📋"; textSize = 46f; gravity = Gravity.CENTER })
                addView(TextView(context).apply {
                    text = "Sin tareas todavía"
                    textSize = 18f
                    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                    setTextColor(ReactorView.AZUL)
                    gravity = Gravity.CENTER
                    setPadding(0, dp(8), 0, dp(6))
                })
                addView(TextView(context).apply {
                    text = "Escribe una arriba o toca un atajo.\n\n• Haz una presentación sobre la energía solar\n• Descarga el manual de Python\n• Investiga cómo funciona un reactor"
                    textSize = 14f
                    setLineSpacing(dp(3).toFloat(), 1f)
                    setTextColor(Color.argb(190, 159, 232, 255))
                })
            })
            return
        }
        todas.sortedWith(compareBy<Tarea>({ rango(it.estado) }, { -it.creada })).forEach { lista.addView(tarjeta(it)) }
    }

    private fun tarjeta(t: Tarea): View {
        val ctx = this
        val color = when (t.estado) {
            Tareas.EN_CURSO -> Tema.AMBAR
            Tareas.FALLO, Tareas.ESPERA_OK -> ReactorView.ROJO
            Tareas.HECHA -> Tema.VERDE
            else -> ReactorView.AZUL
        }
        val etiqueta = when (t.estado) {
            Tareas.EN_CURSO -> "⏳ JARVIS TRABAJANDO…"
            Tareas.HECHA -> "✓ LISTA"
            Tareas.FALLO -> "✕ FALLÓ"
            Tareas.ESPERA_OK -> "⚠ ESPERA TU OK"
            else -> if (t.sugerida) "💡 SUGERIDA" else "● PENDIENTE"
        }
        val abierta = t.id in abiertas
        val hayDetalle = t.resultado.isNotBlank() || t.archivos.isNotEmpty() || t.bitacora.isNotEmpty()

        val contenido = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val tarjeta = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Tema.caja(ctx, Tema.SUPERFICIE, Tema.transparente(color, 90), 1, 14)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(10), dp(5), dp(10), dp(5)) }
            // franja de color a la izquierda: se ve de un vistazo el estado
            addView(View(ctx).apply { background = Tema.caja(ctx, color, color, 0, 3) }, LinearLayout.LayoutParams(dp(5), -1).apply { setMargins(dp(6), dp(8), 0, dp(8)) })
            addView(contenido, LinearLayout.LayoutParams(0, -2, 1f))
        }
        contenido.addView(TextView(ctx).apply {
            text = t.titulo
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setOnClickListener {
                if (!abiertas.add(t.id)) abiertas.remove(t.id)
                pintar()
            }
        })
        contenido.addView(TextView(ctx).apply {
            text = etiqueta + if (hayDetalle && !abierta) "  ·  toca el título para ver el detalle" else ""
            textSize = 10.5f
            typeface = Typeface.MONOSPACE
            setTextColor(color)
            setPadding(dp(8), dp(2), dp(8), dp(2))
            background = Tema.pildora(ctx, color)
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) }
        })
        // Mientras trabaja: barra de progreso y la última línea de la bitácora, para ver el avance sin abrir la tarjeta
        if (t.estado == Tareas.EN_CURSO) {
            contenido.addView(ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
                isIndeterminate = true
                indeterminateTintList = android.content.res.ColorStateList.valueOf(color)
            }, LinearLayout.LayoutParams(-1, dp(4)).apply { topMargin = dp(8) })
            t.bitacora.lastOrNull()?.let { ultima ->
                contenido.addView(TextView(ctx).apply {
                    text = ultima
                    textSize = 11f
                    typeface = Typeface.MONOSPACE
                    maxLines = 2
                    setTextColor(Color.argb(190, 159, 232, 255))
                    setPadding(0, dp(6), 0, 0)
                })
            }
        }

        // Botones
        val fila = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        fun boton(texto: String, acento: Int, accion: () -> Unit) {
            val b = Button(ctx).apply { text = texto; textSize = 12f }
            Tema.boton(b, acento)
            b.setOnClickListener { accion() }
            fila.addView(b, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(6) })
        }
        when (t.estado) {
            Tareas.EN_CURSO -> boton("⏹ Detener", ReactorView.ROJO) { Agente.cancelar(t.id) }
            Tareas.ESPERA_OK -> boton("✓ Aprobar", ReactorView.ROJO) {
                Tareas.cambiar(ctx, t.id) { it.aprobada = true }
                Agente.ejecutar(ctx, t.id)
            }
            Tareas.HECHA -> boton("↺ Repetir", ReactorView.AZUL) { Agente.ejecutar(ctx, t.id) }
            else -> boton("▶ Hacer", ReactorView.AZUL) { Agente.ejecutar(ctx, t.id) }
        }
        if (t.estado != Tareas.HECHA && t.estado != Tareas.EN_CURSO) {
            boton("✓ Hecha", ReactorView.AZUL) { Tareas.cambiar(ctx, t.id) { it.estado = Tareas.HECHA } }
        }
        boton("🗑", ReactorView.ROJO) {
            Tareas.borrar(ctx, t.id)
            abiertas.remove(t.id)
        }
        contenido.addView(fila)

        // Detalle: resultado, archivos y bitácora
        if (abierta) {
            if (t.resultado.isNotBlank()) {
                contenido.addView(TextView(ctx).apply {
                    text = t.resultado
                    textSize = 14f
                    setTextIsSelectable(true)
                    setTextColor(ReactorView.TEXTO)
                    setPadding(0, dp(10), 0, 0)
                })
            }
            for (a in t.archivos) {
                val partes = a.split("|", limit = 2)
                if (partes.size < 2) continue
                contenido.addView(TextView(ctx).apply {
                    text = "📎 ${partes[0]}  (toca para abrir)"
                    textSize = 14f
                    setTextColor(ReactorView.AZUL)
                    setPadding(0, dp(8), 0, 0)
                    setOnClickListener { abrirArchivo(partes[1], partes[0]) }
                })
            }
            if (t.bitacora.isNotEmpty()) {
                contenido.addView(TextView(ctx).apply {
                    text = "Bitácora:\n" + t.bitacora.takeLast(12).joinToString("\n")
                    textSize = 10.5f
                    typeface = Typeface.MONOSPACE
                    setTextColor(Color.argb(170, 159, 232, 255))
                    setPadding(0, dp(10), 0, 0)
                })
            }
        }
        return tarjeta
    }

    private fun abrirArchivo(uri: String, nombre: String) {
        val ext = nombre.substringAfterLast('.', "").lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (e: Exception) {
            Toast.makeText(this, "No encontré una app para abrir ese archivo. Está en Descargas/JARVIS.", Toast.LENGTH_LONG).show()
        }
    }
}
