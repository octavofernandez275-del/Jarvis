package com.octavo.jarvis

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * El "obrero" de Jarvis: hace las tareas por su cuenta, una a la vez.
 *  - Investigar: busca en Wikipedia y en la web, lee las mejores páginas y las resume con el modelo local.
 *  - Descargar: encuentra el archivo (o usa tu enlace) y lo baja a Descargas/JARVIS.
 *  - Crear: presentaciones (.pptx), documentos (.docx), PDF e imágenes. Investiga, arma el esquema con el modelo,
 *    saca conclusiones y propone temas relacionados para seguir investigando.
 *  - Sugerir: lee tus pendientes y propone tareas nuevas.
 * Decide qué hacer con reglas simples sobre el texto de la tarea (un modelo de 2B no es fiable planificando solo);
 * el modelo se usa para escribir, resumir y sacar conclusiones (con formato JSON garantizado por el servidor).
 * Cada paso queda en la bitácora. Lo delicado (programas, comprimidos, archivos enormes) espera tu OK.
 * El chat tuyo tiene prioridad sobre el motor. El piloto y la curiosidad tienen límites (batería y cupo diario).
 */
object Agente {
    private class Cancelacion : RuntimeException()
    private class Fuente(val titulo: String, val url: String, val texto: String)
    private class Plan(val tipo: Int, val dato: String, val formato: Int = 0)

    private const val INVESTIGAR = 0
    private const val DESCARGAR = 1
    private const val SUGERIR = 2
    private const val LEER = 3
    private const val PROYECTO = 4
    private const val IMAGEN = 5

    private const val F_PPTX = 1
    private const val F_DOCX = 2
    private const val F_PDF = 4

    private val pool = Executors.newSingleThreadExecutor()
    @Volatile private var actual = -1L
    @Volatile private var cancelarId = -1L

    /** El chat del usuario lo pide: la tarea en curso suelta el motor y se reintenta después. */
    @Volatile var cederMotor = false

    private val rxUrl = Regex("""https?://\S+""")
    private val rxSugerir = Regex("""^(?:sugiere|sugerime|sugiereme|analiza mis (?:tareas|pendientes)|sugerir tareas)""")
    private val rxDescarga = Regex("""^(?:descarga|descargar|baja|bajar|bajame|consigue|conseguir)\b""")
    private val rxCrear = Regex("""^(?:haz|hazme|hace|crea|crear|genera|generar|arma|armar|prepara|preparame|escribe|redacta)\b""")
    private val rxFormato = Regex("""\b(presentacion|powerpoint|ppt|pptx|diapositivas|word|docx|informe|documento|reporte|pdf)\b""")
    private val rxPresentacion = Regex("""\b(presentacion|powerpoint|ppt|pptx|diapositivas)\b""")
    private val rxWord = Regex("""\b(word|docx|informe|documento|reporte)\b""")
    private val rxPdf = Regex("""\bpdf\b""")
    private val rxImagen = Regex("""^(?:ahora\s+|y\s+)?(?:genera|generar|crea|crear|dibuja|dibujar|haz|hazme|hace|disena)\s+(?:me\s+)?(?:una\s+|un\s+|las\s+|unas\s+)?(?:imagenes|imagen|fotos|foto|ilustraciones|ilustracion|dibujos|dibujo|logo|poster|afiche|wallpaper)\b""")

    private val rxPrefijoDescarga = Regex("""^(?:descarga|descargar|baja|bajar|bajame|consigue|conseguir)\s+(?:me\s+)?""", RegexOption.IGNORE_CASE)
    private val rxPrefijoBusca = Regex("""^(?:busca|buscar|investiga|investigar|averigua|encuentra|resume|resumi)\s+(?:sobre\s+|acerca de\s+|de\s+|me\s+)?""", RegexOption.IGNORE_CASE)
    private val rxPrefijoProyecto = Regex(
        """^(?:haz|hazme|hace|crea|crear|genera|generar|arma|armar|prepara|preparame|escribe|redacta)\s+(?:me\s+)?(?:una\s+|un\s+)?""" +
            """(?:presentaci[oó]n|powerpoint|ppt|pptx|diapositivas|documento|informe|reporte|word|docx|pdf)""" +
            """(?:\s+(?:en|de|con)\s+(?:word|pdf|powerpoint))?(?:\s+(?:acerca de|sobre|del|de|para)\b)?\s*""",
        RegexOption.IGNORE_CASE
    )
    // Para las imágenes solo se quita el verbo: "una imagen de un robot azul" le sirve al modelo para armar el prompt
    private val rxPrefijoImagen = Regex(
        """^(?:ahora\s+|y\s+)?(?:genera|generar|crea|crear|dibuja|dibujar|haz|hazme|hace|diseña|disena)\s+(?:me\s+)?""",
        RegexOption.IGNORE_CASE
    )

    // ---------- Entradas ----------

    /** ¿El texto es un encargo para el agente (crear algo, descargar)? Lo usa la voz para crear la tarea y arrancarla. */
    fun esEncargo(f: String): Boolean =
        (rxCrear.containsMatchIn(f) && rxFormato.containsMatchIn(f)) || rxImagen.containsMatchIn(f) || rxDescarga.containsMatchIn(f)

    /** Pone la tarea en marcha en segundo plano. */
    fun ejecutar(ctx: Context, id: Long) {
        val app = ctx.applicationContext
        val t = Tareas.buscar(app, id) ?: return
        if (t.estado == Tareas.EN_CURSO) return
        Tareas.cambiar(app, id) { it.estado = Tareas.EN_CURSO; it.resultado = "" }
        pool.execute { hacer(app, id) }
    }

    /** Detiene la tarea en curso (si es esa). */
    fun cancelar(id: Long) { cancelarId = id }

    /** Crea y lanza una tarea de "sugerir tareas útiles". */
    fun sugerirAhora(ctx: Context) {
        val t = Tareas.agregar(ctx, "Sugerir tareas útiles según mis pendientes")
        ejecutar(ctx, t.id)
    }

    /**
     * Lo llama el servicio cada minuto. Con el piloto automático toma la siguiente tarea pendiente; con la curiosidad,
     * cuando no queda nada, investiga un tema relacionado que Jarvis mismo propuso (hasta el cupo diario).
     */
    fun tic(ctx: Context) {
        if (!Ajustes.piloto(ctx)) return
        if (!Assistant.listo || Assistant.ocupado || HandsFree.activo) return
        if (!bateriaOk(ctx)) return
        val todas = Tareas.todas(ctx)
        if (todas.any { it.estado == Tareas.EN_CURSO }) return
        val propia = todas.filter { it.estado == Tareas.PENDIENTE && !it.sugerida }.minByOrNull { it.creada }
        if (propia != null) {
            ejecutar(ctx, propia.id)
            return
        }
        if (Ajustes.curiosidad(ctx) && Ajustes.seguimientosRestantes(ctx) > 0) {
            val idea = todas.filter { it.estado == Tareas.PENDIENTE && it.sugerida && planear(it.titulo).tipo == INVESTIGAR }
                .minByOrNull { it.creada } ?: return
            Ajustes.gastarSeguimiento(ctx)
            ejecutar(ctx, idea.id)
        }
    }

    private fun bateriaOk(ctx: Context): Boolean {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return true
        val estado = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val cargando = estado == BatteryManager.BATTERY_STATUS_CHARGING || estado == BatteryManager.BATTERY_STATUS_FULL
        val nivel = i.getIntExtra(BatteryManager.EXTRA_LEVEL, 100) * 100 / maxOf(1, i.getIntExtra(BatteryManager.EXTRA_SCALE, 100))
        return cargando || nivel >= 30
    }

    // ---------- Ejecución ----------

    private fun revisar(id: Long) {
        if (cancelarId == id) throw Cancelacion()
    }

    private fun log(ctx: Context, id: Long, s: String) = Tareas.log(ctx, id, s)

    private fun hacer(ctx: Context, id: Long) {
        actual = id
        if (cancelarId == id) cancelarId = -1L
        try {
            val t = Tareas.buscar(ctx, id) ?: return
            log(ctx, id, "Analizando la tarea…")
            val plan = planear(t.titulo)
            when (plan.tipo) {
                DESCARGAR -> descargar(ctx, t, plan.dato)
                SUGERIR -> sugerir(ctx, t)
                PROYECTO -> proyecto(ctx, t, plan)
                IMAGEN -> imagen(ctx, t, plan.dato)
                LEER -> {
                    log(ctx, id, "Leyendo la página…")
                    val texto = Web.mejoresPasajes(Web.textoPrincipal(Web.get(plan.dato)), listOf(t.titulo), PRESUPUESTO_FUENTES)
                    if (texto.length < 150) throw Exception("la página casi no tiene texto")
                    terminarConResumen(ctx, t, listOf(Fuente(plan.dato, plan.dato, texto)), emptyList())
                }
                else -> investigar(ctx, t, plan.dato)
            }
        } catch (e: Cancelacion) {
            Tareas.cambiar(ctx, id) { it.estado = Tareas.PENDIENTE }
            log(ctx, id, if (cederMotor) "Pausada para atender tu pregunta. Se reintenta sola." else "Detenida.")
        } catch (e: Exception) {
            Tareas.cambiar(ctx, id) { it.estado = Tareas.FALLO; it.resultado = "No pude completarla: ${e.message}" }
            log(ctx, id, "Falló: ${e.message}")
            notificar(ctx, id, "Falló una tarea", Tareas.buscar(ctx, id)?.titulo ?: "")
        } finally {
            actual = -1L
            if (cancelarId == id) cancelarId = -1L
        }
    }

    private fun planear(titulo: String): Plan {
        val f = Parsers.fold(titulo).trim()
        val url = rxUrl.find(titulo)?.value?.trimEnd('.', ',', ')')
        if (rxSugerir.containsMatchIn(f)) return Plan(SUGERIR, titulo)
        if (rxDescarga.containsMatchIn(f)) return Plan(DESCARGAR, url ?: titulo.replace(rxPrefijoDescarga, "").trim())
        if (rxImagen.containsMatchIn(f)) return Plan(IMAGEN, titulo.replace(rxPrefijoImagen, "").trim().ifEmpty { titulo })
        if (rxCrear.containsMatchIn(f) && rxFormato.containsMatchIn(f)) {
            val formato = when {
                rxPresentacion.containsMatchIn(f) -> F_PPTX or F_PDF
                rxWord.containsMatchIn(f) -> F_DOCX or F_PDF
                rxPdf.containsMatchIn(f) -> F_PDF
                else -> F_DOCX or F_PDF
            }
            return Plan(PROYECTO, titulo.replace(rxPrefijoProyecto, "").trim().ifEmpty { titulo }, formato)
        }
        if (url != null) return Plan(LEER, url)
        return Plan(INVESTIGAR, titulo.replace(rxPrefijoBusca, "").trim().ifEmpty { titulo })
    }

    // ---------- Contraste de fuentes ----------

    /**
     * El dato es creíble en esa fuente si sus cifras aparecen tal cual y la mayoría de sus palabras clave también.
     * Es una verificación en código (sin modelo): evita que el modelo diga "aparece en 2 fuentes" sin que sea cierto.
     */
    private fun respalda(dato: String, texto: String): Boolean {
        val d = Parsers.fold(dato)
        val t = Parsers.fold(texto)
        val cifras = Regex("""\d{2,}""").findAll(d).map { it.value }.toList()
        if (cifras.any { it !in t }) return false
        val palabras = Regex("""[a-z]{5,}""").findAll(d).map { it.value }.distinct().toList()
        if (palabras.isEmpty()) return true
        return palabras.count { it in t } * 100 / palabras.size >= 60
    }

    /**
     * El modelo marca qué datos aparecen en 2 o más fuentes; el código lo comprueba y deja solo esos.
     * Devuelve null si no se pudo contrastar (menos de 2 fuentes, error o muy pocos datos confirmados):
     * en ese caso se sigue como antes, con las fuentes completas.
     */
    private fun contrastar(ctx: Context, id: Long, tema: String, fuentes: List<Fuente>): List<String>? {
        if (fuentes.size < 2) return null
        return try {
            revisar(id)
            log(ctx, id, "Contrastando datos entre ${fuentes.size} fuentes…")
            val txt = fuentes.mapIndexed { i, f -> "[${i + 1}] ${f.titulo}\n${f.texto.take(1400)}" }.joinToString("\n\n")
            val j = json(
                llm(
                    ctx, id,
                    listOf(
                        "system" to "Eres un verificador de datos. Solo usas lo que dicen las fuentes.",
                        "user" to "Tema: $tema\n\nFuentes:\n$txt\n\n" +
                            "Lista los datos concretos (cifras, fechas, nombres, hechos) útiles para el tema. " +
                            "En 'fuentes' pon los números de TODAS las fuentes donde aparece ese dato; " +
                            "si aparece en una sola, pon solo esa. No inventes nada. Devuelve JSON con 'datos'."
                    ),
                    600, JSONObject(ESQUEMA_CONTRASTE)
                )
            )
            val arr = j?.optJSONArray("datos") ?: return null
            val confirmados = ArrayList<String>()
            var descartados = 0
            for (k in 0 until arr.length()) {
                val o = arr.optJSONObject(k) ?: continue
                val dato = o.optString("dato").trim()
                val nums = o.optJSONArray("fuentes")
                val citadas = (0 until (nums?.length() ?: 0)).map { nums!!.optInt(it) }.filter { it in 1..fuentes.size }.distinct()
                // Cuenta solo las fuentes donde el dato de verdad se encuentra
                val validas = citadas.filter { respalda(dato, fuentes[it - 1].texto) }
                if (dato.length >= 10 && validas.size >= 2) confirmados.add(dato) else descartados++
            }
            log(ctx, id, "Datos confirmados: ${confirmados.size}; descartados por aparecer en una sola fuente: $descartados")
            if (confirmados.size >= 2) confirmados.take(8) else null
        } catch (e: Cancelacion) {
            throw e
        } catch (e: Exception) {
            log(ctx, id, "No pude contrastar las fuentes (${e.message}); sigo con ellas completas")
            null
        }
    }

    // ---------- Tema recordado entre pedidos ----------

    private const val PREFS_TEMA = "jarvis_tema"
    private const val TEMA_VIGENTE_MS = 6 * 60 * 60 * 1000L   // pasadas 6 horas se olvida

    /** Guarda de qué se acaba de hablar (tema + datos clave) para pedidos como "ahora hazme las imágenes". */
    fun recordarTema(ctx: Context, tema: String, contexto: String) {
        ctx.getSharedPreferences(PREFS_TEMA, Context.MODE_PRIVATE).edit()
            .putString("tema", tema.take(150)).putString("contexto", contexto.take(600))
            .putLong("hora", System.currentTimeMillis()).apply()
    }

    /** El tema del último pedido (si es reciente) o null. */
    fun temaReciente(ctx: Context): String? {
        val sp = ctx.getSharedPreferences(PREFS_TEMA, Context.MODE_PRIVATE)
        if (System.currentTimeMillis() - sp.getLong("hora", 0L) > TEMA_VIGENTE_MS) return null
        return sp.getString("tema", null)?.takeIf { it.isNotBlank() }
    }

    /** Datos clave del último tema (vacío si no hay o ya pasó mucho tiempo). */
    fun contextoReciente(ctx: Context): String =
        if (temaReciente(ctx) == null) "" else ctx.getSharedPreferences(PREFS_TEMA, Context.MODE_PRIVATE).getString("contexto", "").orEmpty()

    private val rxRelleno = Regex("""\b(ahora|y|las|los|unas|unos|una|un|de|del|la|el|para|sobre|me|imagen|imagenes|foto|fotos|ilustracion|ilustraciones|dibujo|dibujos|logo|poster|afiche|wallpaper|ese|esa|eso|este|esta|tema|mismo|misma|anterior)\b""")

    /** "las imágenes", "de eso": no dice de qué; entonces se usa el tema recordado. */
    private fun esVaga(descripcion: String): Boolean =
        Regex("""[a-z]{3,}""").findAll(rxRelleno.replace(Parsers.fold(descripcion), " ")).count() < 2

    // ---------- Reunir información ----------

    /** El modelo reescribe el pedido en 3 búsquedas claras (aclaran de qué se habla); la original queda de respaldo. */
    private fun consultasMejoradas(ctx: Context, id: Long, q: String): List<String> {
        return try {
            log(ctx, id, "Pensando mejores búsquedas…")
            val j = json(
                llm(
                    ctx, id,
                    listOf(
                        "system" to "Eres un experto en buscar información en internet.",
                        "user" to "Pedido: $q\n\nEscribe 3 consultas de búsqueda, de 3 a 7 palabras cada una (una en español, una en inglés si el tema es internacional, y una con términos técnicos o nombres propios), " +
                            "que aclaren de qué se trata exactamente (persona, lugar, objeto, época) y cubran ángulos distintos. " +
                            "No repitas el pedido tal cual. Devuelve JSON con 'consultas'."
                    ),
                    120, JSONObject(ESQUEMA_CONSULTAS)
                )
            )
            val mejoradas = lista(j?.optJSONArray("consultas")).map { it.trim().trim('"') }.filter { it.length in 4..80 }
            (mejoradas.take(3) + q).distinctBy { Parsers.fold(it) }
        } catch (e: Cancelacion) {
            throw e
        } catch (e: Exception) {
            listOf(q)
        }
    }

    private fun buscarWebConReintento(ctx: Context, id: Long, c: String): List<Web.Resultado> =
        try { Web.buscarWeb(c, 5) } catch (e: Exception) {
            log(ctx, id, "El buscador no respondió (${e.message}); reintento…")
            try { Thread.sleep(1500); Web.buscarWeb(c, 5) } catch (e2: Exception) {
                log(ctx, id, "El buscador sigue sin responder; sigo con lo que tengo"); emptyList()
            }
        }

    /** Caracteres de fuentes que caben en el contexto (~11 000 de ~24 000) dejando lugar a instrucciones y respuesta. */
    private val EXT_NO_TEXTO = setOf("pdf", "jpg", "jpeg", "png", "gif", "webp", "mp3", "mp4", "zip", "docx", "xlsx", "pptx")
    private const val PRESUPUESTO_FUENTES = 12_000   // fijo: leer prompts enormes en el teléfono tardaría minutos

    /**
     * Busca con TODOS los buscadores a la vez (DuckDuckGo, Bing, Mojeek, Wikipedia es/en), ordena por relevancia,
     * lee las mejores páginas en paralelo y de cada una se queda con los pasajes que contestan la consulta.
     * Devuelve hasta maxFuentes con texto y los enlaces encontrados.
     */
    private fun reunirFuentes(ctx: Context, id: Long, q: String, maxFuentes: Int = 8): Pair<List<Fuente>, List<String>> {
        val consultas = consultasMejoradas(ctx, id, q)
        revisar(id)
        log(ctx, id, "Buscando en ${consultas.size} consultas y +15 fuentes a la vez (web, noticias, foros, ciencia, libros)…")
        var web: List<Web.Resultado> = try { Web.buscarTodo(consultas) } catch (e: Exception) { emptyList() }
        if (web.isEmpty()) {
            log(ctx, id, "Los buscadores no respondieron; reintento…")
            Thread.sleep(2000)
            web = try { Web.buscarTodo(consultas) } catch (e: Exception) { emptyList() }
        }
        if (web.size < 4) {
            // Quizá hay un error de ortografía: se prueba con la corrección sugerida
            val sug = Web.sugerencia(q)
            if (sug != null) {
                log(ctx, id, "Probando con la ortografía corregida: $sug")
                web = (web + (try { Web.buscarTodo(listOf(sug)) } catch (e: Exception) { emptyList() })).distinctBy { it.url }
            }
        }
        log(ctx, id, "Encontré ${web.size} resultados; leyendo los mejores…")
        val porFuente = (PRESUPUESTO_FUENTES / maxFuentes).coerceAtLeast(1200)
        val fuentes = ArrayList<Fuente>()
        // 0) Si pide algo reciente, titulares de noticias como una fuente más
        if (Web.esUrgente(q)) try {
            val nots = Web.buscarNoticias(consultas.first(), 8).filter { it.titulo.length > 15 }
            if (nots.size >= 2) fuentes.add(Fuente("Titulares recientes", nots.first().url,
                nots.joinToString("\n") { "• " + it.titulo + (if (it.texto.length > 40) " — " + it.texto.take(200) else "") }))
        } catch (e: Exception) {}
        // 1) Wikipedia completa del mejor artículo (lo más confiable para definiciones y datos duros)
        val wiki = web.firstOrNull { it.url.contains("wikipedia.org") }
        if (wiki != null) {
            val idioma = wiki.url.substringAfter("://").substringBefore('.')
            val completo = Web.wikipediaCompleta(wiki.titulo, idioma)
            val txt = if (completo.length > wiki.texto.length) completo else wiki.texto
            if (txt.length > 100) fuentes.add(Fuente(wiki.titulo, wiki.url, Web.mejoresPasajes(txt, consultas, porFuente)))
        }
        // 2) Páginas web, leídas en paralelo, de mejor a peor, en tandas hasta juntar las necesarias
        var candidatas = web.filter { !it.url.contains("wikipedia.org") && Web.extension(it.url) !in EXT_NO_TEXTO && Web.calidadDominio(it.url) > -1.0 }
        var desde = 0
        var reforzado = false
        while (fuentes.size < maxFuentes && (desde < candidatas.size || (!reforzado && candidatas.isEmpty())) && desde < 24) {
            revisar(id)
            val tanda = if (desde < candidatas.size) candidatas.subList(desde, minOf(desde + maxFuentes + 2, candidatas.size)) else emptyList()
            desde += tanda.size
            for ((r, texto) in Web.leerVarias(tanda, Int.MAX_VALUE)) {
                if (fuentes.size >= maxFuentes) break
                if (fuentes.any { it.url == r.url }) continue
                val pasajes = Web.mejoresPasajes(texto, consultas, porFuente)
                if (fuentes.any { Web.sonParecidos(it.texto, pasajes) }) continue   // copia de otra fuente
                log(ctx, id, "Leyendo: ${r.titulo.take(50)}")
                fuentes.add(Fuente(r.titulo, r.url, pasajes))
            }
            // Segunda ronda: si tras la primera tanda faltan fuentes, se buscan términos nuevos sacados de lo hallado
            if (!reforzado && fuentes.size < 4 && desde >= minOf(candidatas.size, 8)) {
                reforzado = true
                val extra = Web.consultasDeRefuerzo(consultas, web)
                log(ctx, id, "Pocas fuentes; busco con términos nuevos: ${extra.first()}")
                val mas = try { Web.buscarTodo(extra) } catch (e: Exception) { emptyList() }
                candidatas = candidatas + mas.filter { m -> !m.url.contains("wikipedia.org") && candidatas.none { it.url == m.url } &&
                    Web.extension(m.url) !in EXT_NO_TEXTO && Web.calidadDominio(m.url) > -1.0 }
            }
        }
        // Si hay otros resultados de Wikipedia con resumen, sirven de apoyo cuando faltan fuentes
        if (fuentes.size < 3) for (r in web) {
            if (fuentes.size >= 3) break
            if (r.texto.length > 200 && fuentes.none { it.url == r.url }) fuentes.add(Fuente(r.titulo, r.url, r.texto))
        }
        return fuentes to (fuentes.map { it.url } + web.map { it.url }).distinct().take(8)
    }

    private fun investigar(ctx: Context, t: Tarea, q: String) {
        val (fuentes, enlaces) = reunirFuentes(ctx, t.id, q)
        if (fuentes.isEmpty()) throw Exception("no encontré información; revisa tu conexión o prueba otras palabras")
        terminarConResumen(ctx, t, fuentes, enlaces)
    }

    private fun terminarConResumen(ctx: Context, t: Tarea, fuentes: List<Fuente>, enlaces: List<String>) {
        val id = t.id
        revisar(id)
        log(ctx, id, "Resumiendo con el modelo local (puede tardar un par de minutos)…")
        val fuentesTxt = fuentes.mapIndexed { i, f -> "[${i + 1}] ${f.titulo}\n${f.texto}" }.joinToString("\n\n")
        // Contraste: si se pudo, el resumen se escribe SOLO con los datos que aparecen en 2 o más fuentes
        val confirmados = contrastar(ctx, id, t.titulo, fuentes)
        val resumen = try {
            llm(
                ctx, id,
                listOf(
                    "system" to "Eres un asistente que resume información en español de forma clara y breve.",
                    "user" to if (confirmados != null)
                        "Tarea: ${t.titulo}\n\nDatos confirmados por 2 o más fuentes:\n" + confirmados.joinToString("\n") { "- $it" } + "\n\n" +
                            "Responde la tarea en español con 4 a 6 viñetas breves usando SOLO esos datos. No agregues nada más."
                    else
                        "Tarea: ${t.titulo}\n\nFuentes:\n$fuentesTxt\n\n" +
                            "Responde la tarea en español con 4 a 6 viñetas breves basadas solo en las fuentes. " +
                            "Si las fuentes no alcanzan, dilo."
                ),
                1500
            )
        } catch (e: Cancelacion) {
            throw e
        } catch (e: Exception) {
            log(ctx, id, "No pude resumir con el modelo (${e.message}); dejo los textos originales")
            fuentes.first().texto.take(600)
        }
        val links = (enlaces.ifEmpty { fuentes.map { it.url } }).joinToString("\n")
        val aviso = when {
            confirmados != null -> "✔ Solo incluí datos confirmados por 2 o más fuentes.\n\n"
            fuentes.size >= 2 -> "⚠ No pude confirmar los datos entre fuentes; verifícalos antes de usarlos.\n\n"
            else -> ""
        }
        // Lo recuerda para pedidos posteriores ("ahora hazme las imágenes")
        if (!rxUrl.containsMatchIn(t.titulo)) {   // si el pedido era una dirección web, no hay un "tema" que recordar
            recordarTema(ctx, t.titulo.replace(rxPrefijoBusca, "").trim().ifEmpty { t.titulo },
                (confirmados ?: fuentes.map { it.texto.take(150) }).take(4).joinToString(" | "))
        }
        // Curiosidad: propone temas relacionados para seguir
        val seguir = if (Ajustes.curiosidad(ctx)) temasParaSeguir(ctx, id, t.titulo, resumen) else emptyList()
        Tareas.cambiar(ctx, id) {
            it.estado = Tareas.HECHA
            it.resultado = aviso + resumen + "\n\nFuentes:\n" + links +
                (if (seguir.isNotEmpty()) "\n\nTemas relacionados que dejé como sugeridos 💡:\n" + seguir.joinToString("\n") { s -> "• $s" } else "")
        }
        log(ctx, id, "Lista.")
        notificar(ctx, id, "Tarea lista", t.titulo)
    }

    // ---------- Crear presentaciones, documentos y PDF ----------

    private fun json(texto: String): JSONObject? = try {
        val i = texto.indexOf('{')
        val j = texto.lastIndexOf('}')
        if (i >= 0 && j > i) JSONObject(texto.substring(i, j + 1)) else null
    } catch (e: Exception) {
        null
    }

    private fun lista(a: JSONArray?): List<String> =
        if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optString(it).trim().takeIf { s -> s.isNotEmpty() } }

    private val ESQUEMA_PRESENTACION = """{"type":"object","properties":{"titulo":{"type":"string"},"diapositivas":{"type":"array","minItems":3,"maxItems":5,"items":{"type":"object","properties":{"titulo":{"type":"string"},"puntos":{"type":"array","minItems":3,"maxItems":4,"items":{"type":"string"}}},"required":["titulo","puntos"]}}},"required":["titulo","diapositivas"]}"""
    private val ESQUEMA_DOCUMENTO = """{"type":"object","properties":{"titulo":{"type":"string"},"secciones":{"type":"array","minItems":3,"maxItems":4,"items":{"type":"object","properties":{"titulo":{"type":"string"},"parrafos":{"type":"array","minItems":1,"maxItems":2,"items":{"type":"string"}}},"required":["titulo","parrafos"]}}},"required":["titulo","secciones"]}"""
    private val ESQUEMA_CONCLUSION = """{"type":"object","properties":{"conclusiones":{"type":"array","minItems":2,"maxItems":3,"items":{"type":"string"}},"seguir":{"type":"array","minItems":1,"maxItems":3,"items":{"type":"string"}}},"required":["conclusiones","seguir"]}"""
    private val ESQUEMA_SEGUIR = """{"type":"object","properties":{"seguir":{"type":"array","minItems":1,"maxItems":3,"items":{"type":"string"}}},"required":["seguir"]}"""

    // Contraste de fuentes: cada dato lleva los números de las fuentes donde aparece
    private val ESQUEMA_CONTRASTE = """{"type":"object","properties":{"datos":{"type":"array","minItems":1,"maxItems":8,"items":{"type":"object","properties":{"dato":{"type":"string"},"fuentes":{"type":"array","minItems":1,"maxItems":4,"items":{"type":"integer"}}},"required":["dato","fuentes"]}}},"required":["datos"]}"""
    // Imagen: el sujeto (en inglés) y el prompt que debe mostrarlo
    private val ESQUEMA_IMAGEN = """{"type":"object","properties":{"sujeto":{"type":"string"},"prompt":{"type":"string"}},"required":["sujeto","prompt"]}"""

    private const val SISTEMA_PROYECTO = "Eres un asistente que organiza y redacta contenido en español: claro, correcto, breve y basado solo en las fuentes dadas."
    private val ESQUEMA_CONSULTAS = """{"type":"object","properties":{"consultas":{"type":"array","minItems":2,"maxItems":2,"items":{"type":"string"}}},"required":["consultas"]}"""
    private val ESQUEMA_TITULOS_PRES = """{"type":"object","properties":{"titulo":{"type":"string"},"secciones":{"type":"array","minItems":4,"maxItems":5,"items":{"type":"string"}}},"required":["titulo","secciones"]}"""
    private val ESQUEMA_TITULOS_DOC = """{"type":"object","properties":{"titulo":{"type":"string"},"secciones":{"type":"array","minItems":3,"maxItems":4,"items":{"type":"string"}}},"required":["titulo","secciones"]}"""
    private val ESQUEMA_PUNTOS = """{"type":"object","properties":{"puntos":{"type":"array","minItems":3,"maxItems":4,"items":{"type":"string"}}},"required":["puntos"]}"""
    private val ESQUEMA_PARRAFOS = """{"type":"object","properties":{"parrafos":{"type":"array","minItems":1,"maxItems":2,"items":{"type":"string"}}},"required":["parrafos"]}"""

    /**
     * Calidad: primero solo los títulos y después UNA llamada corta por sección.
     * Un modelo chico rinde mucho mejor con tareas pequeñas que con un pedido grande.
     * Devuelve null si algo falla, y entonces se usa el método de una sola llamada.
     */
    private fun generarPorPartes(ctx: Context, id: Long, tema: String, esPresentacion: Boolean, fuentesTxt: String): Pair<String, List<Documentos.Seccion>>? {
        log(ctx, id, "Armando el esquema…")
        val r = llm(
            ctx, id,
            listOf(
                "system" to SISTEMA_PROYECTO,
                "user" to "Fuentes:\n$fuentesTxt\n\nTema: $tema\n\n" +
                    "Devuelve JSON con 'titulo' (máximo 8 palabras) y 'secciones': " +
                    (if (esPresentacion) "de 4 a 5 títulos de diapositiva" else "de 3 a 4 títulos de sección") +
                    ", cortos (máximo 6 palabras), distintos entre sí y que cubran el tema de punta a punta."
            ),
            300, JSONObject(if (esPresentacion) ESQUEMA_TITULOS_PRES else ESQUEMA_TITULOS_DOC)
        )
        val j = json(r) ?: return null
        val titulos = lista(j.optJSONArray("secciones"))
            .map { it.trim().trim('.', ':') }
            .filter { it.length in 3..70 }
            .distinctBy { Parsers.fold(it) }
            .take(if (esPresentacion) 5 else 4)
        if (titulos.size < 2) return null
        val titulo = j.optString("titulo").trim().ifBlank { tema.replaceFirstChar { it.uppercase() } }

        val salida = ArrayList<Documentos.Seccion>()
        titulos.forEachIndexed { i, t ->
            revisar(id)
            log(ctx, id, "Escribiendo ${i + 1} de ${titulos.size}: ${t.take(40)}")
            val previos = titulos.take(i).joinToString("; ")
            val pedido = "Fuentes:\n$fuentesTxt\n\nTema general: $tema\n" +
                (if (esPresentacion) "Diapositiva: «$t»" else "Sección: «$t»") + "\n\n" +
                (if (esPresentacion)
                    "Devuelve JSON con 'puntos': 3 o 4 frases completas de máximo 14 palabras para esa diapositiva, "
                else
                    "Devuelve JSON con 'parrafos': 1 o 2 párrafos de máximo 45 palabras para esa sección, ") +
                "usando solo datos de las fuentes." +
                (if (previos.isNotEmpty()) " No repitas lo que ya cubren estas partes: $previos." else "")
            val rs = try {
                llm(
                    ctx, id,
                    listOf("system" to SISTEMA_PROYECTO, "user" to pedido),
                    if (esPresentacion) 350 else 600,
                    JSONObject(if (esPresentacion) ESQUEMA_PUNTOS else ESQUEMA_PARRAFOS)
                )
            } catch (e: Cancelacion) {
                throw e
            } catch (e: Exception) {
                log(ctx, id, "No pude escribir «${t.take(30)}» (${e.message})")
                null
            }
            val puntos = lista(rs?.let { json(it) }?.optJSONArray(if (esPresentacion) "puntos" else "parrafos"))
            if (puntos.isNotEmpty()) salida.add(Documentos.Seccion(t, puntos))
        }
        return if (salida.size >= 2) titulo to salida else null
    }

    /** Limpieza sin modelo: quita citas [1], viñetas y repetidos; cada frase queda con mayúscula inicial. */
    private fun pulir(secciones: List<Documentos.Seccion>): List<Documentos.Seccion> {
        val vistos = HashSet<String>()
        val rxCita = Regex("\\[\\d+\\]")
        val rxVineta = Regex("^\\s*(?:[-•*·]+|\\d{1,2}[.)])\\s*")
        val salida = ArrayList<Documentos.Seccion>()
        for (s in secciones) {
            val puntos = ArrayList<String>()
            for (p in s.puntos) {
                var t = p.replace(rxCita, "").replace(rxVineta, "").replace(Regex("\\s+"), " ").trim()
                if (t.length < 15) continue
                t = t.replaceFirstChar { it.uppercase() }
                if (!vistos.add(Parsers.fold(t).take(60))) continue
                puntos.add(t)
            }
            if (puntos.isNotEmpty()) salida.add(Documentos.Seccion(s.titulo.trim().trimEnd('.', ':'), puntos))
        }
        return salida
    }

    private fun dominio(url: String): String =
        try { java.net.URI(url).host?.removePrefix("www.") ?: url } catch (e: Exception) { url }

    /** Esquema de emergencia si el modelo no devolvió algo usable: un tema por cada fuente, con sus frases principales. */
    private fun respaldo(tema: String, fuentes: List<Fuente>): List<Documentos.Seccion> {
        val salida = ArrayList<Documentos.Seccion>()
        for (f in fuentes) {
            val frases = f.texto.split(Regex("(?<=[.!?])\\s+")).filter { it.length > 30 }.map { it.take(140) }
            frases.chunked(3).take(2).forEachIndexed { i, grupo ->
                salida.add(Documentos.Seccion(if (i == 0) f.titulo.take(60) else "Más sobre ${f.titulo.take(40)}", grupo))
            }
        }
        if (salida.isEmpty()) salida.add(Documentos.Seccion(tema.take(60), listOf("No pude reunir información suficiente sobre este tema.")))
        return salida
    }

    private fun proyecto(ctx: Context, t: Tarea, plan: Plan) {
        val id = t.id
        val tema = plan.dato
        val esPresentacion = (plan.formato and F_PPTX) != 0
        val (fuentes, enlaces) = reunirFuentes(ctx, id, tema)
        revisar(id)
        // Contraste: los textos se escriben solo con datos que aparecen en 2 o más fuentes (si se pudo contrastar)
        val confirmados = contrastar(ctx, id, tema, fuentes)
        revisar(id)
        val fuentesTxt = when {
            fuentes.isEmpty() -> "(sin fuentes: usa tu conocimiento general y sé prudente)"
            confirmados != null -> "Datos confirmados por 2 o más fuentes (usa solo estos; si no alcanzan, escribe menos):\n" +
                confirmados.joinToString("\n") { "- $it" }
            else -> fuentes.mapIndexed { i, f -> "[${i + 1}] ${f.titulo}\n${f.texto.take(1800)}" }.joinToString("\n\n")
        }
        recordarTema(ctx, tema, (confirmados ?: fuentes.map { it.texto.take(150) }).take(4).joinToString(" | "))

        // 1) Esquema + contenido con el modelo (primero por partes; si falla, una sola llamada)
        var titulo = tema.replaceFirstChar { it.uppercase() }
        var secciones: List<Documentos.Seccion> = emptyList()
        val porPartes = try {
            generarPorPartes(ctx, id, tema, esPresentacion, fuentesTxt)
        } catch (e: Cancelacion) {
            throw e
        } catch (e: Exception) {
            log(ctx, id, "No pude escribir por partes (${e.message}); pruebo con una sola llamada")
            null
        }
        if (porPartes != null) {
            titulo = porPartes.first
            secciones = porPartes.second
        }
        if (secciones.isEmpty()) {
            log(ctx, id, if (esPresentacion) "Armando el esquema de la presentación (el modelo local tarda unos minutos)…"
            else "Escribiendo el documento (el modelo local tarda unos minutos)…")
            val pedido = if (esPresentacion) {
                "Crea una presentación en español sobre: $tema.\nUsa las fuentes.\n\nFuentes:\n$fuentesTxt\n\n" +
                    "Devuelve JSON con 'titulo' y 'diapositivas' (de 3 a 5), cada una con 'titulo' y 'puntos' " +
                    "(3 o 4 frases cortas de máximo 14 palabras)."
            } else {
                "Escribe un documento en español sobre: $tema.\nUsa las fuentes.\n\nFuentes:\n$fuentesTxt\n\n" +
                    "Devuelve JSON con 'titulo' y 'secciones' (de 3 a 4), cada una con 'titulo' y 'parrafos' " +
                    "(1 o 2 párrafos de máximo 45 palabras)."
            }
            val respuesta = try {
                llm(
                    ctx, id,
                    listOf("system" to "Eres un asistente que redacta contenido claro, correcto y breve en español.", "user" to pedido),
                    if (esPresentacion) 1000 else 1200,
                    JSONObject(if (esPresentacion) ESQUEMA_PRESENTACION else ESQUEMA_DOCUMENTO)
                )
            } catch (e: Cancelacion) {
                throw e
            } catch (e: Exception) {
                log(ctx, id, "El modelo no pudo armar el esquema (${e.message}); uso las fuentes directamente")
                ""
            }
            val j = json(respuesta)
            if (j != null) {
                titulo = j.optString("titulo").ifBlank { titulo }
                val arr = j.optJSONArray(if (esPresentacion) "diapositivas" else "secciones")
                if (arr != null) {
                    secciones = (0 until arr.length()).mapNotNull { i ->
                        val o = arr.optJSONObject(i) ?: return@mapNotNull null
                        val puntos = lista(o.optJSONArray(if (esPresentacion) "puntos" else "parrafos"))
                        if (puntos.isEmpty()) null else Documentos.Seccion(o.optString("titulo").ifBlank { "Punto ${i + 1}" }, puntos)
                    }
                }
            }
        }
        secciones = pulir(secciones)
        if (secciones.isEmpty()) {
            log(ctx, id, "No obtuve un esquema usable del modelo; armo uno con las fuentes.")
            secciones = respaldo(tema, fuentes)
        }

        // 2) Conclusiones y temas para seguir
        revisar(id)
        log(ctx, id, "Sacando conclusiones…")
        val resumenContenido = secciones.joinToString("\n") { "- ${it.titulo}: ${it.puntos.firstOrNull() ?: ""}" }.take(900)
        val c = try {
            json(
                llm(
                    ctx, id,
                    listOf(
                        "system" to "Eres un analista que saca conclusiones prácticas y propone qué investigar después.",
                        "user" to "Tema: $tema\n\nContenido:\n$resumenContenido\n\n" +
                            "Devuelve JSON con 'conclusiones' (2 o 3 frases cortas) y 'seguir' " +
                            "(1 a 3 temas relacionados que valga la pena investigar después, cada uno en pocas palabras)."
                    ),
                    400, JSONObject(ESQUEMA_CONCLUSION)
                )
            )
        } catch (e: Cancelacion) {
            throw e
        } catch (e: Exception) {
            log(ctx, id, "No pude sacar conclusiones con el modelo (${e.message})")
            null
        }
        val conclusiones = lista(c?.optJSONArray("conclusiones"))
        val seguir = lista(c?.optJSONArray("seguir")).take(3)
        if (conclusiones.isNotEmpty()) {
            secciones = secciones + Documentos.Seccion("Conclusiones", conclusiones)
        }
        if (fuentes.isNotEmpty()) {
            secciones = secciones + Documentos.Seccion("Fuentes", fuentes.map { "${it.titulo.take(60)} (${dominio(it.url)})" })
        }

        // 3) Crear los archivos
        revisar(id)
        val subtitulo = "Preparado por Jarvis · " + SimpleDateFormat("d/M/yyyy", Locale.getDefault()).format(Date())
        val creados = ArrayList<Web.Archivo>()
        log(ctx, id, "Creando los archivos…")
        if ((plan.formato and F_PPTX) != 0) creados.add(Documentos.presentacionPptx(ctx, titulo, subtitulo, secciones))
        if ((plan.formato and F_DOCX) != 0) creados.add(Documentos.documentoDocx(ctx, titulo, secciones))
        if ((plan.formato and F_PDF) != 0) {
            creados.add(
                if (esPresentacion) Documentos.presentacionPdf(ctx, titulo, subtitulo, secciones)
                else Documentos.documentoPdf(ctx, titulo, secciones)
            )
        }
        crearSeguimientos(ctx, seguir)

        Tareas.cambiar(ctx, id) {
            creados.forEach { a -> it.archivos.add("${a.nombre}|${a.uri}") }
            it.estado = Tareas.HECHA
            it.resultado = (if (confirmados != null) "✔ Solo incluí datos confirmados por 2 o más fuentes.\n\n" else "") + "Listo. Creé:\n" + creados.joinToString("\n") { a -> "• ${a.nombre}" } +
                "\n(en Descargas/JARVIS)" +
                (if (conclusiones.isNotEmpty()) "\n\nConclusiones:\n" + conclusiones.joinToString("\n") { s -> "• $s" } else "") +
                (if (enlaces.isNotEmpty()) "\n\nFuentes:\n" + enlaces.joinToString("\n") else "") +
                (if (seguir.isNotEmpty()) "\n\nTemas que dejé como sugeridos 💡:\n" + seguir.joinToString("\n") { s -> "• $s" } else "")
        }
        log(ctx, id, "Lista.")
        notificar(ctx, id, "Archivos listos", titulo)
    }

    // ---------- Imagen ----------

    private fun imagen(ctx: Context, t: Tarea, descripcion: String) {
        val id = t.id
        log(ctx, id, "Preparando la descripción para el generador de imágenes…")
        // Si el pedido no dice de qué ("las imágenes"), se usa el tema de la última investigación
        val tema = if (esVaga(descripcion)) temaReciente(ctx) else null
        val sujetoBase = tema ?: descripcion
        var contexto = if (tema != null) contextoReciente(ctx) else ""
        if (contexto.isEmpty()) {
            // Sin tema recordado: un dato de Wikipedia ayuda a que el modelo entienda quién o qué es
            contexto = try { Web.buscarWikipedia(sujetoBase, 1).firstOrNull()?.texto?.take(300).orEmpty() } catch (e: Exception) { "" }
        }
        val respaldo = tema ?: descripcion
        val prompt = try {
            val j = json(
                llm(
                    ctx, id,
                    listOf(
                        "system" to "You write short image-generation prompts in English.",
                        "user" to "Idea: $descripcion\nTema de fondo: $sujetoBase\nDatos de contexto: $contexto\n\n" +
                            "Devuelve JSON con 'sujeto': el protagonista de la imagen, en inglés, 2 a 5 palabras " +
                            "(la persona, animal, lugar u objeto DEL QUE TRATA el tema; si el tema es una persona, el sujeto es la persona, no sus cosas), " +
                            "y 'prompt': un prompt de imagen en inglés, detallado, máximo 40 palabras, una sola línea, sin comillas, que muestre a ese sujeto."
                    ),
                    160, JSONObject(ESQUEMA_IMAGEN)
                )
            )
            val sujeto = j?.optString("sujeto")?.trim().orEmpty()
            var p = j?.optString("prompt")?.lines()?.firstOrNull()?.trim()?.trim('"').orEmpty()
            if (p.isEmpty()) throw Exception("el modelo no devolvió un prompt")
            // Garantía en código: el sujeto tiene que estar en el prompt
            if (sujeto.isNotEmpty() && !p.contains(sujeto, ignoreCase = true)) p = "$sujeto, $p"
            p
        } catch (e: Cancelacion) {
            throw e
        } catch (e: Exception) {
            log(ctx, id, "No pude mejorar la descripción (${e.message}); uso la tuya")
            respaldo
        }
        revisar(id)
        log(ctx, id, "Generando la imagen en un servicio en línea gratuito (puede tardar hasta un minuto)…")
        val url = "https://image.pollinations.ai/prompt/" + URLEncoder.encode(prompt.take(300), "UTF-8").replace("+", "%20") +
            "?width=768&height=768&nologo=true&seed=" + (System.currentTimeMillis() % 100000)
        val nombre = "imagen_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
        val a = Web.descargar(ctx, url, nombre, { }, { cancelarId == id }, 120_000, true)
        Tareas.cambiar(ctx, id) {
            it.archivos.add("${a.nombre}|${a.uri}")
            it.estado = Tareas.HECHA
            it.resultado = "Imagen creada con un servicio en línea gratuito (Pollinations); no se genera en el teléfono.\n" +
                "Descripción usada: $prompt\nLa guardé en Descargas/JARVIS."
        }
        log(ctx, id, "Lista.")
        notificar(ctx, id, "Imagen lista", t.titulo)
    }

    // ---------- Descargar ----------

    private fun tamano(b: Long) = if (b <= 0) "tamaño desconocido" else "%.1f MB".format(b / 1048576f)

    private fun tipoDeArchivo(q: String): String {
        val f = Parsers.fold(q)
        return when {
            Regex("""\b(imagen|foto|wallpaper|fondo de pantalla)\b""").containsMatchIn(f) -> "jpg"
            Regex("""\b(epub)\b""").containsMatchIn(f) -> "epub"
            else -> "pdf"
        }
    }

    private fun descargar(ctx: Context, t: Tarea, dato: String) {
        val id = t.id
        var url: String? = rxUrl.find(dato)?.value?.trimEnd('.', ',', ')')
        if (url == null) {
            val ext = tipoDeArchivo(dato)
            log(ctx, id, "Buscando un archivo $ext de: $dato")
            val res = Web.buscarTodo(listOf("$dato filetype:$ext", "$dato $ext descargar"), 8)
            url = res.firstOrNull { Web.extension(it.url) in Web.PERMITIDAS }?.url
            if (url == null) {
                val enlaces = res.take(5).joinToString("\n") { it.titulo + "\n" + it.url }
                Tareas.cambiar(ctx, id) {
                    it.estado = Tareas.HECHA
                    it.resultado = if (enlaces.isEmpty()) "No encontré nada para descargar." else
                        "No encontré un archivo directo. Estos enlaces pueden servir:\n$enlaces"
                }
                log(ctx, id, "Sin archivo directo; dejé los enlaces encontrados.")
                return
            }
        }
        revisar(id)
        val sonda = Web.sondear(url)
        log(ctx, id, "Archivo: ${sonda.nombre} (${tamano(sonda.tamano)})")
        val delicado = sonda.extension !in Web.PERMITIDAS || sonda.tamano > Web.MAX_SIN_OK
        val aprobada = Tareas.buscar(ctx, id)?.aprobada == true
        if (delicado && !aprobada) {
            Tareas.cambiar(ctx, id) {
                it.estado = Tareas.ESPERA_OK
                it.resultado = "Necesito tu OK para descargar ${sonda.nombre} (${tamano(sonda.tamano)}) desde ${url.substringAfter("//").substringBefore('/')}. " +
                    "Es un tipo de archivo delicado o muy grande. Toca Aprobar si lo quieres."
            }
            log(ctx, id, "Espera tu OK.")
            notificar(ctx, id, "Jarvis necesita tu OK", sonda.nombre)
            return
        }
        log(ctx, id, "Descargando…")
        val a = Web.descargar(ctx, url, sonda.nombre, { p -> log(ctx, id, "Descargando $p%") }, { cancelarId == id })
        Tareas.cambiar(ctx, id) {
            it.archivos.add("${a.nombre}|${a.uri}")
            it.estado = Tareas.HECHA
            it.resultado = "Descargado: ${a.nombre}\nLo encuentras en Descargas/JARVIS."
        }
        log(ctx, id, "Lista.")
        notificar(ctx, id, "Descarga lista", a.nombre)
    }

    // ---------- Sugerir y seguir ----------

    private fun crearSeguimientos(ctx: Context, temas: List<String>) {
        val existentes = Tareas.todas(ctx).map { Parsers.fold(it.titulo) }.toSet()
        for (tema in temas) {
            val titulo = if (Regex("""^(?:busca|descarga|investiga)\b""", RegexOption.IGNORE_CASE).containsMatchIn(tema)) tema else "Investiga $tema"
            if (Parsers.fold(titulo.take(150)) !in existentes) Tareas.agregar(ctx, titulo.take(150), sugerida = true)
        }
    }

    /** Con el resumen en la mano, el modelo propone temas relacionados; quedan como tareas sugeridas. */
    private fun temasParaSeguir(ctx: Context, id: Long, titulo: String, resumen: String): List<String> {
        return try {
            log(ctx, id, "Pensando qué más valdría la pena investigar…")
            val j = json(
                llm(
                    ctx, id,
                    listOf(
                        "system" to "Eres un asistente curioso que propone qué investigar después.",
                        "user" to "Tarea: $titulo\nResultado:\n${resumen.take(700)}\n\n" +
                            "Devuelve JSON con 'seguir': 1 a 3 temas relacionados y útiles para investigar después, en pocas palabras."
                    ),
                    200, JSONObject(ESQUEMA_SEGUIR)
                )
            )
            val temas = lista(j?.optJSONArray("seguir")).take(3)
            crearSeguimientos(ctx, temas)
            temas
        } catch (e: Cancelacion) {
            throw e
        } catch (e: Exception) {
            log(ctx, id, "No pude proponer temas (${e.message})")
            emptyList()
        }
    }

    private fun sugerir(ctx: Context, t: Tarea) {
        val id = t.id
        val pendientes = Tareas.todas(ctx).filter { it.estado != Tareas.HECHA && it.id != id }.map { it.titulo }.take(8)
        if (pendientes.isEmpty()) {
            Tareas.cambiar(ctx, id) {
                it.estado = Tareas.HECHA
                it.resultado = "No tienes otras tareas pendientes que analizar. Agrega algunas y vuelvo a intentarlo."
            }
            return
        }
        log(ctx, id, "Leyendo tus ${pendientes.size} pendientes…")
        val texto = llm(
            ctx, id,
            listOf(
                "system" to "Eres un asistente práctico que ayuda a organizar tareas.",
                "user" to "Mis tareas pendientes:\n" + pendientes.joinToString("\n") { "- $it" } +
                    "\n\nSugiere hasta 3 tareas nuevas, útiles y relacionadas, una por línea, " +
                    "empezando cada una con Busca, Descarga o Investiga. Escribe solo las líneas."
            ),
            120
        )
        val existentes = Tareas.todas(ctx).map { Parsers.fold(it.titulo) }.toSet()
        val rx = Regex("""^[\s\-*\d.)]*((?:busca|descarga|investiga)\b.+)$""", RegexOption.IGNORE_CASE)
        val nuevas = texto.lines().mapNotNull { rx.find(it.trim())?.groupValues?.get(1)?.trim()?.take(150) }
            .filter { Parsers.fold(it) !in existentes }.distinct().take(3)
        nuevas.forEach { Tareas.agregar(ctx, it, sugerida = true) }
        Tareas.cambiar(ctx, id) {
            it.estado = Tareas.HECHA
            it.resultado = if (nuevas.isEmpty()) "No se me ocurrió nada nuevo por ahora." else
                "Sugerí ${nuevas.size} tareas (marcadas con 💡). Revísalas y toca Hacer en las que quieras:\n" +
                    nuevas.joinToString("\n") { "• $it" }
        }
        log(ctx, id, "Lista.")
        notificar(ctx, id, "Jarvis sugirió tareas", "${nuevas.size} nuevas")
    }

    // ---------- Modelo local ----------

    /** Pide algo al modelo sin pisar tu chat: espera a que lo sueltes y se corta si tú preguntas. */
    private fun llm(ctx: Context, id: Long, mensajes: List<Pair<String, String>>, maxTokens: Int, esquema: JSONObject? = null): String {
        var espera = 0
        while (Assistant.ocupado || HandsFree.activo) {
            revisar(id)
            Thread.sleep(2000)
            if (++espera > 450) throw Exception("el motor estuvo ocupado demasiado tiempo")
        }
        cederMotor = false
        EngineManager.retener()
        try {
            if (!EngineManager.asegurar(ctx)) throw Exception("el motor no está listo")
            return Chat.completar(mensajes, maxTokens, 0.3, esquema) {
                if (cederMotor || cancelarId == id) throw Cancelacion()
            }
        } finally {
            EngineManager.soltar()
        }
    }

    // ---------- Avisos ----------

    private fun notificar(ctx: Context, id: Long, titulo: String, texto: String) {
        try {
            val pi = PendingIntent.getActivity(
                ctx, 7,
                Intent(ctx, TareasActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = Notification.Builder(ctx, JarvisService.CANAL_ACCIONES)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(titulo)
                .setContentText(texto.take(120))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(2000 + (id % 1000).toInt(), n)
        } catch (e: Exception) {
        }
    }
}
