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
    private val rxImagen = Regex("""^(?:genera|generar|crea|crear|dibuja|dibujar|haz|hazme|hace|disena)\s+(?:me\s+)?(?:una\s+|un\s+)?(?:imagen|foto|ilustracion|dibujo|logo|poster|afiche|wallpaper)\b""")

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
        """^(?:genera|generar|crea|crear|dibuja|dibujar|haz|hazme|hace|diseña|disena)\s+(?:me\s+)?""",
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
                    val texto = Web.textoPrincipal(Web.get(plan.dato), 1200)
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

    // ---------- Reunir información ----------

    private fun dominio(u: String) = u.substringAfter("//").substringBefore('/').removePrefix("www.")

    /** De un texto largo, las frases que más se parecen a la consulta (en su orden original), hasta `max` caracteres. */
    private fun relevantes(texto: String, consulta: String, max: Int): String {
        val claves = Parsers.fold(consulta).split(Regex("[^a-z0-9]+")).filter { it.length > 3 }.toSet()
        val frases = texto.split(Regex("(?<=[.!?])\\s+")).filter { it.length > 40 }
        if (frases.isEmpty()) return texto.take(max)
        val orden = frases.indices.sortedWith(compareByDescending<Int> { i -> val fo = Parsers.fold(frases[i]); claves.count { fo.contains(it) } }.thenBy { it })
        val elegidas = ArrayList<Int>()
        var largo = 0
        for (i in orden) {
            if (largo + frases[i].length > max) continue
            elegidas.add(i)
            largo += frases[i].length + 1
        }
        return elegidas.sorted().joinToString(" ") { frases[it] }.ifEmpty { texto.take(max) }
    }

    /** Busca en toda la web a la vez (varios buscadores + Wikipedia), lee hasta 5 páginas en paralelo y las devuelve completas. */
    private fun reunirFuentes(ctx: Context, id: Long, q: String): Pair<List<Fuente>, List<String>> {
        log(ctx, id, "Buscando en toda la web: $q")
        val res = try { Web.buscarTodo(listOf(q, "$q datos clave")) } catch (e: Exception) {
            log(ctx, id, "La búsqueda falló (${e.message})"); emptyList()
        }
        revisar(id)
        if (res.isEmpty()) return emptyList<Fuente>() to emptyList()
        log(ctx, id, "Leyendo ${minOf(8, res.size)} fuentes a la vez…")
        val leidas = Web.leerVarias(res.take(8), 4000)
        revisar(id)
        val fuentes = leidas.map { (r, t) -> Fuente(r.titulo.ifBlank { dominio(r.url) }, r.url, t) }.take(5)
        log(ctx, id, "Pude leer ${fuentes.size} fuentes.")
        return fuentes to (fuentes.map { it.url } + res.map { it.url }).distinct().take(6)
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
        val fuentesTxt = fuentes.take(3).mapIndexed { i, f -> "[${i + 1}] ${f.titulo}\n${relevantes(f.texto, t.titulo, 550)}" }.joinToString("\n\n")
        val resumen = try {
            llm(
                ctx, id,
                listOf(
                    "system" to "Eres un asistente que resume información en español de forma clara y breve.",
                    "user" to "Tarea: ${t.titulo}\n\nFuentes:\n$fuentesTxt\n\n" +
                        "Responde la tarea en español con 4 a 6 viñetas breves basadas solo en las fuentes. " +
                        "Si las fuentes no alcanzan, dilo."
                ),
                220
            )
        } catch (e: Cancelacion) {
            throw e
        } catch (e: Exception) {
            log(ctx, id, "No pude resumir con el modelo (${e.message}); dejo los textos originales")
            fuentes.first().texto.take(600)
        }
        val links = (enlaces.ifEmpty { fuentes.map { it.url } }).joinToString("\n")
        // Curiosidad: propone temas relacionados para seguir
        val seguir = if (Ajustes.curiosidad(ctx)) temasParaSeguir(ctx, id, t.titulo, resumen) else emptyList()
        Tareas.cambiar(ctx, id) {
            it.estado = Tareas.HECHA
            it.resultado = resumen + "\n\nFuentes:\n" + links +
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

    private val ESQUEMA_INDICE = """{"type":"object","properties":{"titulo":{"type":"string"},"secciones":{"type":"array","minItems":4,"maxItems":6,"items":{"type":"string"}}},"required":["titulo","secciones"]}"""
    private val ESQUEMA_PUNTOS = """{"type":"object","properties":{"puntos":{"type":"array","minItems":3,"maxItems":4,"items":{"type":"string"}}},"required":["puntos"]}"""
    private val ESQUEMA_PARRAFOS = """{"type":"object","properties":{"parrafos":{"type":"array","minItems":2,"maxItems":2,"items":{"type":"string"}}},"required":["parrafos"]}"""
    private val ESQUEMA_CONCLUSION = """{"type":"object","properties":{"conclusiones":{"type":"array","minItems":2,"maxItems":3,"items":{"type":"string"}},"seguir":{"type":"array","minItems":1,"maxItems":3,"items":{"type":"string"}}},"required":["conclusiones","seguir"]}"""
    private val ESQUEMA_SEGUIR = """{"type":"object","properties":{"seguir":{"type":"array","minItems":1,"maxItems":3,"items":{"type":"string"}}},"required":["seguir"]}"""

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
        // 1) Índice corto y luego cada sección por separado, con los datos más relevantes de las fuentes
        //    (el modelo local tiene poca memoria: pedirle todo junto da texto flojo; por partes sale mucho mejor)
        val resumenFuentes = fuentes.take(4).joinToString("\n") { "- ${it.titulo}: ${relevantes(it.texto, tema, 220)}" }
            .ifEmpty { "(sin fuentes: usa tu conocimiento general y sé prudente)" }
        var titulo = tema.replaceFirstChar { it.uppercase() }
        val cuantas = if (esPresentacion) 6 else 5
        log(ctx, id, "Armando el índice (el modelo local tarda unos minutos en total)…")
        val indice = try {
            json(
                llm(
                    ctx, id,
                    listOf(
                        "system" to "Eres un asistente que organiza contenido claro, correcto y útil en español.",
                        "user" to "Tema: $tema\n\nFuentes:\n$resumenFuentes\n\n" +
                            "Devuelve JSON con 'titulo' y 'secciones': $cuantas títulos cortos (máximo 6 palabras), sin repetir, " +
                            "que cubran el tema de lo básico a lo más importante."
                    ),
                    200, JSONObject(ESQUEMA_INDICE)
                )
            )
        } catch (e: Cancelacion) {
            throw e
        } catch (e: Exception) {
            log(ctx, id, "El modelo no pudo armar el índice (${e.message})")
            null
        }
        val tIdx = indice?.optString("titulo").orEmpty()
        if (tIdx.isNotBlank()) titulo = tIdx
        val titulos = lista(indice?.optJSONArray("secciones")).take(cuantas)
        var secciones: List<Documentos.Seccion> = emptyList()
        if (titulos.size >= 3) {
            val clave = if (esPresentacion) "puntos" else "parrafos"
            val hechas = ArrayList<Documentos.Seccion>()
            titulos.forEachIndexed { i, st ->
                revisar(id)
                log(ctx, id, "Escribiendo ${i + 1} de ${titulos.size}: ${st.take(40)}")
                val base = fuentes.map { relevantes(it.texto, "$tema $st", 350) }.filter { it.isNotBlank() }.take(3)
                val datos = if (base.isEmpty()) "(sin fuentes: usa tu conocimiento general y sé prudente)"
                else base.mapIndexed { k, b -> "[${k + 1}] $b" }.joinToString("\n")
                val pedido = if (esPresentacion) {
                    "Diapositiva '$st' de una presentación sobre: $tema.\nFuentes:\n$datos\n\n" +
                        "Devuelve JSON con 'puntos': 3 o 4 frases de máximo 16 palabras, con datos concretos de las fuentes " +
                        "(cifras, nombres, fechas). Sin relleno."
                } else {
                    "Sección '$st' de un documento sobre: $tema.\nFuentes:\n$datos\n\n" +
                        "Devuelve JSON con 'parrafos': 2 párrafos de máximo 60 palabras con datos concretos de las fuentes " +
                        "(cifras, nombres, fechas). Sin relleno."
                }
                val puntos = try {
                    lista(
                        json(
                            llm(
                                ctx, id,
                                listOf("system" to "Eres un asistente que redacta contenido claro, correcto y concreto en español.", "user" to pedido),
                                if (esPresentacion) 230 else 320,
                                JSONObject(if (esPresentacion) ESQUEMA_PUNTOS else ESQUEMA_PARRAFOS)
                            )
                        )?.optJSONArray(clave)
                    )
                } catch (e: Cancelacion) {
                    throw e
                } catch (e: Exception) {
                    log(ctx, id, "No pude escribir esa parte con el modelo (${e.message}); uso las fuentes")
                    emptyList()
                }
                val finales = puntos.ifEmpty {
                    (base.firstOrNull() ?: "").split(Regex("(?<=[.!?])\\s+")).filter { it.length > 30 }.take(3).map { it.take(140) }
                }
                if (finales.isNotEmpty()) hechas.add(Documentos.Seccion(st, finales))
            }
            secciones = hechas
        }
        if (secciones.isEmpty()) {
            log(ctx, id, "No obtuve contenido usable del modelo; armo uno con las fuentes.")
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
                    220, JSONObject(ESQUEMA_CONCLUSION)
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
            it.resultado = "Listo. Creé:\n" + creados.joinToString("\n") { a -> "• ${a.nombre}" } +
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
        val prompt = try {
            llm(
                ctx, id,
                listOf(
                    "system" to "You write short image-generation prompts in English.",
                    "user" to "Turn this idea into one detailed English image prompt (max 40 words, one line, no quotes): $descripcion"
                ),
                70
            ).lines().first().trim().trim('"').ifEmpty { descripcion }
        } catch (e: Cancelacion) {
            throw e
        } catch (e: Exception) {
            log(ctx, id, "No pude mejorar la descripción (${e.message}); uso la tuya")
            descripcion
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
            val res = Web.buscarWeb("$dato filetype:$ext", 8)
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
                    100, JSONObject(ESQUEMA_SEGUIR)
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
