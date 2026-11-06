package com.octavo.jarvis

import android.content.Context
import java.io.File

/**
 * Memoria de Jarvis, en tres niveles y sin gastar IA ni batería:
 *  1. Datos: lo que le pides recordar ("recuerda que…") y lo que aprende solo de lo que cuentas de ti
 *     ("me llamo…", "vivo en…", "mi perro se llama…"). Hasta 300, en un archivo de texto privado.
 *  2. Búsqueda por relevancia: al contestar, al modelo le llegan los datos más NUEVOS y además los que tienen que ver
 *     con tu pregunta, aunque sean viejos.
 *  3. Temas: de las charlas anteriores que ya no caben en el historial queda una lista corta de lo que preguntaste,
 *     que sobrevive aunque cierres la app.
 * El tamaño del bloque que se pasa al modelo crece con el contexto (Engine.contexto: 4096 a 32768 según la RAM).
 */
object Memory {
    private const val ARCHIVO = "memoria.txt"
    private const val ARCHIVO_TEMAS = "temas.txt"
    private const val MAX_HECHOS = 300
    private const val MAX_LARGO = 300
    private const val MAX_TEMAS = 15
    private const val LARGO_TEMA = 110

    private val vacias = setOf("que", "los", "las", "una", "uno", "con", "del", "por", "para", "mis", "tus", "como", "esta", "este", "pero", "muy")
    private var hechos: MutableList<String>? = null   // se carga una sola vez
    private var temas: MutableList<String>? = null

    /** Caracteres de memoria que se pasan al modelo: ~12 % del contexto (entre 900 y 4500). */
    private fun presupuesto() = (Engine.contexto * 3 * 12 / 100).coerceIn(900, 4500)

    private fun cargar(ctx: Context): MutableList<String> {
        hechos?.let { return it }
        val f = File(ctx.filesDir, ARCHIVO)
        val l = if (f.exists()) f.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toMutableList() else mutableListOf()
        hechos = l
        return l
    }

    private fun guardarArchivo(ctx: Context, nombre: String, lineas: List<String>) {
        // Se escribe a un archivo temporal y se renombra, para no dejar la memoria a medias
        val f = File(ctx.filesDir, nombre)
        val tmp = File(ctx.filesDir, "$nombre.tmp")
        tmp.writeText(lineas.joinToString("\n"))
        if (!tmp.renameTo(f)) { f.writeText(tmp.readText()); tmp.delete() }
    }

    private fun guardar(ctx: Context) = guardarArchivo(ctx, ARCHIVO, hechos.orEmpty())

    @Synchronized fun todos(ctx: Context): List<String> = cargar(ctx).toList()

    /** Agrega un dato. Devuelve false si estaba vacío o ya existía. */
    @Synchronized fun agregar(ctx: Context, hecho: String): Boolean {
        val l = cargar(ctx)
        val h = hecho.replace('\n', ' ').trim().take(MAX_LARGO)
        if (h.isEmpty()) return false
        val plegado = Parsers.fold(h)
        if (l.any { Parsers.fold(it) == plegado }) return false
        l.add(h)
        while (l.size > MAX_HECHOS) l.removeAt(0)   // si se llena, se pierde el más viejo
        guardar(ctx)
        return true
    }

    /** Agrega un dato que sustituye a otro anterior del mismo tipo (el nombre, dónde vive…): lo nuevo manda. */
    @Synchronized private fun poner(ctx: Context, clave: String, hecho: String): Boolean {
        val l = cargar(ctx)
        val h = hecho.trim().take(MAX_LARGO)
        if (l.any { Parsers.fold(it) == Parsers.fold(h) }) return false
        l.removeAll { Parsers.fold(it).startsWith(Parsers.fold(clave)) }
        l.add(h)
        while (l.size > MAX_HECHOS) l.removeAt(0)
        guardar(ctx)
        return true
    }

    @Synchronized private fun quitar(ctx: Context, hecho: String) {
        val l = cargar(ctx)
        if (l.removeAll { Parsers.fold(it) == Parsers.fold(hecho) }) guardar(ctx)
    }

    private fun puntaje(h: String, palabras: List<String>): Int {
        val fh = Parsers.fold(h)
        return palabras.count { p -> fh.contains(p) || (p.length > 5 && fh.contains(p.take(p.length - 1))) }
    }

    private fun palabrasDe(consulta: String): List<String> =
        Parsers.fold(consulta).split(Regex("""[^\p{L}\p{N}]+""")).filter { it.length >= 3 && it !in vacias }.distinct()

    /** Datos que coinciden con la consulta: primero por frase, si no por palabras sueltas. */
    @Synchronized fun buscar(ctx: Context, consulta: String): List<String> {
        val q = Parsers.fold(consulta).trim()
        if (q.isEmpty()) return emptyList()
        val l = cargar(ctx)
        val exactos = l.filter { Parsers.fold(it).contains(q) }
        if (exactos.isNotEmpty()) return exactos
        val palabras = palabrasDe(q)
        if (palabras.isEmpty()) return emptyList()
        val minimo = (palabras.size + 1) / 2
        return l.filter { puntaje(it, palabras) >= minimo }
    }

    @Synchronized fun borrar(ctx: Context, hecho: String) {
        if (cargar(ctx).remove(hecho)) guardar(ctx)
    }

    @Synchronized fun borrarTodo(ctx: Context) {
        cargar(ctx).clear()
        guardar(ctx)
        cargarTemas(ctx).clear()
        guardarArchivo(ctx, ARCHIVO_TEMAS, emptyList())
    }

    // ---------- Aprender solo ----------

    private const val RESTO = """([^.,;!?\n]{2,45})"""
    private val NOMBRE_PROPIO = Regex("""(?i:me llamo|mi nombre es) (\p{L}{2,20})(?: ((?-i:[A-ZÁÉÍÓÚÑ])\p{L}{1,20}))?""")
    private val NO_NOMBRE = setOf("asi", "un", "una", "el", "la", "que", "muy", "por", "porque", "como", "tambien", "igual")
    private val VIVE = Regex("""(?i:vivo en) $RESTO""")
    private val TRABAJA = Regex("""(?i:trabajo) ((?i:en|de|como|para) [^.,;!?\n]{2,40})""")
    private val EDAD = Regex("""(?i:tengo) (\d{1,2}) (?i:años)(?! (?i:de|trabajando|viviendo|en))""")
    private val CUMPLE = Regex("""(?i:mi cumplea[ñn]os es(?: el)?) ([^.,;!?\n]{2,30})""")
    private val RELACION = Regex("""(?i:\bmis?) ((?i:perro|perra|gato|gata|hijo|hija|esposa|esposo|novia|novio|mamá|mama|papá|papa|madre|padre|hermano|hermana|jefe|jefa|amigo|amiga)) ((?i:se llama|es)) (\p{L}{2,20})""")
    private val GUSTA = Regex("""(?<![Nn][Oo] )(?i:me (?:gusta|encanta|fascina)(?: mucho)?) $RESTO""")
    private val NO_GUSTA = Regex("""(?i:no me gusta) $RESTO""")
    private val ES_DE = Regex("""(?i:\bsoy (?:de|oriundo de|nacido en)) ([^.,;!?\n]{2,30})""")
    private val FAVORITO = Regex("""(?i:\bmi) ((?i:comida|color|equipo|banda|canción|cancion|película|pelicula|serie|juego|música|musica|deporte|animal)) (?i:favorit[oa] es) $RESTO""")
    private val ESTUDIA = Regex("""^(?i:estudio) $RESTO""")
    private val DEMOSTRATIVOS = Regex("""^(?i:esa|ese|eso|esta|este|esto|tu|la idea|que)\b""")

    private fun limpiar(s: String) = s.trim().trim('.', ',', ';', '!', ' ')
    private fun mayus(s: String) = s.replaceFirstChar { it.uppercase() }

    /**
     * Mira lo que contó el usuario y anota datos estables sobre él ("Se llama Octavo", "Vive en Córdoba"…).
     * No hace nada con preguntas ni con comandos. Devuelve lo que anotó, para avisárselo.
     */
    fun aprender(ctx: Context, texto: String): List<String> {
        if (!Ajustes.memoriaAuto(ctx)) return emptyList()
        val t = texto.trim()
        if (t.length < 8 || t.length > 400 || t.contains('?') || t.startsWith("¿")) return emptyList()
        val nuevos = ArrayList<String>()
        fun uno(clave: String, hecho: String) { if (poner(ctx, clave, hecho)) nuevos.add(hecho) }
        fun suma(hecho: String) { if (agregar(ctx, hecho)) nuevos.add(hecho) }

        NOMBRE_PROPIO.find(t)?.let { m ->
            val p = m.groupValues[1]
            if (Parsers.fold(p) !in NO_NOMBRE) uno("Se llama ", "Se llama ${mayus(p)}" + m.groupValues[2].let { if (it.isNotEmpty()) " $it" else "" })
        }
        VIVE.find(t)?.let { uno("Vive en ", "Vive en ${limpiar(it.groupValues[1])}") }
        TRABAJA.find(t)?.let { uno("Trabaja ", "Trabaja ${limpiar(it.groupValues[1])}") }
        EDAD.find(t)?.let { uno("Tiene ", "Tiene ${it.groupValues[1]} años") }
        CUMPLE.find(t)?.let { uno("Su cumpleaños es ", "Su cumpleaños es ${limpiar(it.groupValues[1])}") }
        RELACION.find(t)?.let { m ->
            val rel = m.groupValues[1].lowercase()
            val verbo = m.groupValues[2].lowercase()
            val nombre = mayus(m.groupValues[3])
            uno("Su $rel $verbo ", "Su $rel $verbo $nombre")
        }
        ES_DE.find(t)?.let { uno("Es de ", "Es de ${limpiar(it.groupValues[1])}") }
        FAVORITO.find(t)?.let { m -> uno("Su ${m.groupValues[1].lowercase()} favorito es ", "Su ${m.groupValues[1].lowercase()} favorito es ${limpiar(m.groupValues[2])}") }
        ESTUDIA.find(t)?.let { suma("Estudia ${limpiar(it.groupValues[1])}") }
        NO_GUSTA.find(t)?.let { m -> limpiar(m.groupValues[1]).let { if (!DEMOSTRATIVOS.containsMatchIn(it)) { quitar(ctx, "Le gusta $it"); suma("No le gusta $it") } } }
        GUSTA.find(t)?.let { m -> limpiar(m.groupValues[1]).let { if (!DEMOSTRATIVOS.containsMatchIn(it)) { quitar(ctx, "No le gusta $it"); suma("Le gusta $it") } } }
        return nuevos
    }

    // ---------- Temas de charlas anteriores ----------

    private fun cargarTemas(ctx: Context): MutableList<String> {
        temas?.let { return it }
        val f = File(ctx.filesDir, ARCHIVO_TEMAS)
        val l = if (f.exists()) f.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toMutableList() else mutableListOf()
        temas = l
        return l
    }

    /** Guarda de qué se habló (lo que preguntó el usuario) cuando un mensaje sale del historial por falta de lugar. */
    @Synchronized fun agregarTema(ctx: Context, mensaje: String) {
        val t = mensaje.replace('\n', ' ').trim()
        if (t.length < 12) return
        val l = cargarTemas(ctx)
        val corto = t.take(LARGO_TEMA)
        if (l.any { Parsers.fold(it) == Parsers.fold(corto) }) return
        l.add(corto)
        while (l.size > MAX_TEMAS) l.removeAt(0)
        guardarArchivo(ctx, ARCHIVO_TEMAS, l)
    }

    @Synchronized fun temasPrevios(ctx: Context): List<String> = cargarTemas(ctx).toList()

    // ---------- Lo que se le pasa al modelo ----------

    /**
     * Texto para el prompt: la mitad del lugar para los datos más nuevos (estable entre preguntas, así el modelo reutiliza
     * su caché), el resto para datos viejos que tengan que ver con la pregunta, y al final los temas de charlas anteriores.
     */
    @Synchronized fun bloqueParaPrompt(ctx: Context, consulta: String = ""): String {
        val l = cargar(ctx)
        val prev = cargarTemas(ctx)
        if (l.isEmpty() && prev.isEmpty()) return ""
        val total = presupuesto()
        val elegidos = LinkedHashSet<String>()
        var usado = 0
        for (h in l.asReversed()) {
            if (usado + h.length + 3 > total / 2) break
            elegidos.add(h); usado += h.length + 3
        }
        val palabras = palabrasDe(consulta)
        if (palabras.isNotEmpty()) {
            val relevantes = l.filter { it !in elegidos }.map { it to puntaje(it, palabras) }.filter { it.second > 0 }
                .sortedByDescending { it.second }
            for ((h, _) in relevantes) {
                if (usado + h.length + 3 > total * 85 / 100) break
                elegidos.add(h); usado += h.length + 3
            }
        }
        val partes = ArrayList<String>()
        if (elegidos.isNotEmpty()) {
            // en el orden en que se anotaron
            val ordenados = l.filter { it in elegidos }
            partes.add("Datos que sabes del usuario y lo que te pidió recordar (úsalos solo si vienen al caso, sin repetirlos de más):\n" +
                ordenados.joinToString("\n") { "- $it" })
        }
        if (prev.isNotEmpty()) {
            var libre = (total - usado).coerceAtLeast(300)
            val ultimos = ArrayList<String>()
            for (t in prev.asReversed()) { if (libre - t.length - 3 < 0) break; ultimos.add(t); libre -= t.length + 3 }
            if (ultimos.isNotEmpty())
                partes.add("De charlas anteriores ya cerradas, el usuario te había preguntado (por si lo retoma): " + ultimos.reversed().joinToString(" | "))
        }
        return partes.joinToString("\n\n")
    }
}
