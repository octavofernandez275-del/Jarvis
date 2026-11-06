package com.octavo.jarvis

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

/** Internet para las tareas de Jarvis: buscar, leer páginas y descargar archivos. Sin claves ni dependencias. */
object Web {
    class Resultado(val titulo: String, val url: String, val texto: String = "")
    class Sonda(val nombre: String, val tamano: Long, val mime: String) {
        val extension: String get() = Web.extension(nombre)
    }
    class Archivo(val nombre: String, val uri: String)

    /** Tipos de archivo que Jarvis descarga solo. Cualquier otro (programas, comprimidos…) pide tu OK. */
    val PERMITIDAS = setOf(
        "pdf", "epub", "txt", "md", "csv", "jpg", "jpeg", "png", "webp", "gif",
        "mp3", "m4a", "ogg", "mp4", "docx", "xlsx", "pptx"
    )
    const val MAX_SIN_OK = 100L * 1024 * 1024   // más de esto también pide tu OK

    private const val UA = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

    fun extension(s: String): String = s.substringBefore('?').substringAfterLast('.', "").lowercase().take(5)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun decodificar(s: String) = try { URLDecoder.decode(s, "UTF-8") } catch (e: Exception) { s }

    /** Abre la conexión siguiendo redirecciones (también de http a https). */
    private fun abrir(url0: String, lecturaMs: Int = 25_000): HttpURLConnection {
        var url = url0
        for (i in 0..5) {
            val c = URL(url).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15_000
            c.readTimeout = lecturaMs
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Accept-Language", "es-AR,es;q=0.9,en;q=0.5")
            c.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
            val code = c.responseCode
            if (code in 300..399) {
                val destino = c.getHeaderField("Location")
                c.disconnect()
                if (destino == null) break
                url = URL(URL(url), destino).toString()
                continue
            }
            if (code !in 200..299) {
                c.disconnect()
                throw Exception("el sitio respondió $code")
            }
            return c
        }
        throw Exception("demasiadas redirecciones")
    }

    private val cachePaginas = object : LinkedHashMap<String, Pair<Long, String>>(64, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Pair<Long, String>>?) = size > 80
    }
    private const val CACHE_MS = 10 * 60 * 1000L

    /** Descarga el texto de una página (hasta maxBytes), con reintento y memoria de 10 min para no repetir pedidos. */
    fun get(url: String, maxBytes: Int = 1_500_000): String {
        val k = "$maxBytes|$url"
        synchronized(cachePaginas) { cachePaginas[k]?.let { if (System.currentTimeMillis() - it.first < CACHE_MS) return it.second } }
        var fallo: Exception? = null
        for (intento in 0..1) {
            try {
                val t = getRed(url, maxBytes)
                synchronized(cachePaginas) { cachePaginas[k] = System.currentTimeMillis() to t }
                return t
            } catch (e: Exception) {
                fallo = e
                val m = e.message.orEmpty()
                if (m.contains("403") || m.contains("404") || m.contains("410") || m.contains("429")) break   // reintentar no sirve
                try { Thread.sleep(700) } catch (_: InterruptedException) { break }
            }
        }
        throw fallo ?: Exception("no pude leer la página")
    }

    private fun getRed(url: String, maxBytes: Int): String {
        val c = abrir(url)
        try {
            val cs = Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE).find(c.contentType ?: "")?.groupValues?.get(1) ?: "UTF-8"
            val buf = ByteArrayOutputStream()
            c.inputStream.use { ins ->
                val b = ByteArray(8192)
                var total = 0
                while (total < maxBytes) {
                    val n = ins.read(b)
                    if (n < 0) break
                    buf.write(b, 0, n)
                    total += n
                }
            }
            return try { String(buf.toByteArray(), charset(cs)) } catch (e: Exception) { String(buf.toByteArray(), Charsets.UTF_8) }
        } finally {
            c.disconnect()
        }
    }

    // ---------- Texto ----------

    private fun decodificarHtml(s: String): String {
        var r = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
            .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'")
            .replace("&ndash;", "–").replace("&mdash;", "—").replace("&hellip;", "…").replace("&laquo;", "«").replace("&raquo;", "»")
        if (r.contains("&#")) {
            r = Regex("&#(x?)([0-9a-fA-F]{1,6});").replace(r) { m ->
                val cp = m.groupValues[2].toIntOrNull(if (m.groupValues[1].isEmpty()) 10 else 16)
                if (cp != null && cp in 32..0x10FFFF) String(Character.toChars(cp)) else " "
            }
        }
        return r
    }

    fun textoPlano(h: String): String =
        decodificarHtml(h.replace(Regex("(?s)<[^>]+>"), " ")).replace(Regex("\\s+"), " ").trim()

    /**
     * Lo importante de una página: título, descripción, y el cuerpo (párrafos, listas, subtítulos) de <article>/<main>
     * si existen. Sin tope artificial salvo `max` (que ahora es solo una protección de memoria).
     */
    fun textoPrincipal(html: String, max: Int = Int.MAX_VALUE): String {
        val limpio = html.replace(Regex("(?is)<!--.*?-->"), " ")
            .replace(Regex("(?is)<(script|style|noscript|svg|nav|footer|header|aside|form|iframe|button)[^>]*>.*?</\\1>"), " ")
        val descripcion = Regex("""(?is)<meta[^>]+(?:name|property)=["'](?:description|og:description)["'][^>]+content=["']([^"']{40,})["']""")
            .find(html)?.groupValues?.get(1)?.let { textoPlano(it) }.orEmpty()
        val cuerpo = Regex("(?is)<(article|main)[^>]*>(.*?)</\\1>").findAll(limpio).map { it.groupValues[2] }
            .maxByOrNull { it.length } ?: limpio
        val bloques = Regex("(?is)<(p|li|h[1-4]|blockquote|td)[^>]*>(.*?)</\\1>").findAll(cuerpo).map { m ->
            val t = textoPlano(m.groupValues[2])
            if (m.groupValues[1].startsWith("h")) "## $t" else t
        }.filter { it.length > 40 || it.startsWith("## ") }.toList()
        // si el <article> no tenía nada útil, probar con toda la página
        val usables = if (bloques.count { !it.startsWith("## ") } >= 2) bloques else
            Regex("(?is)<(p|li)[^>]*>(.*?)</\\1>").findAll(limpio).map { textoPlano(it.groupValues[2]) }.filter { it.length > 60 }.toList()
        val base0 = if (usables.count { !it.startsWith("## ") } >= 2) usables.distinct().joinToString("\n") else textoPlano(limpio)
        // Muchos sitios modernos traen el artículo entero en datos estructurados (JSON-LD)
        val estructurado = Regex(""""articleBody"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(html)?.groupValues?.get(1)?.let {
            try { JSONObject("{\"a\":\"$it\"}").getString("a") } catch (e: Exception) { "" }
        }?.let { textoPlano(it) }.orEmpty()
        val base = if (estructurado.length > base0.length * 1.2) estructurado else base0
        val completo = if (descripcion.isNotEmpty() && !base.startsWith(descripcion.take(40))) descripcion + "\n" + base else base
        return completo.take(max)
    }

    private val PALABRAS_VACIAS = setOf(
        "de", "la", "el", "los", "las", "un", "una", "unos", "unas", "y", "o", "en", "con", "por", "para", "del", "al", "que", "se",
        "es", "son", "como", "mas", "pero", "sus", "su", "lo", "le", "me", "mi", "te", "tu", "hay", "esta", "este", "esto", "ese",
        "the", "of", "and", "to", "in", "is", "for", "on", "with", "an", "or", "hazme", "dame", "busca", "sobre", "acerca"
    )

    private fun plegar(s: String): String =
        java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    /** Palabras clave de la consulta, sin tildes ni vacías; se comparan por prefijo (plurales, conjugaciones). */
    fun claves(consulta: String): List<String> =
        Regex("[a-z0-9]{3,}").findAll(plegar(consulta)).map { it.value }.filter { it !in PALABRAS_VACIAS }
            .map { if (it.length > 5 && !it.all { c -> c.isDigit() }) it.take(it.length - 1).take(7) else it }.distinct().toList()

    private val DOMINIOS_BUENOS = listOf(
        "wikipedia.org" to 1.0, ".gov" to 0.9, ".gob." to 0.9, ".edu" to 0.9, "nih.gov" to 1.0, "who.int" to 1.0, "nature.com" to 0.9,
        "britannica.com" to 0.9, "bbc." to 0.7, "reuters.com" to 0.8, "apnews.com" to 0.8, "nationalgeographic" to 0.7,
        "stackoverflow.com" to 0.6, "arxiv.org" to 0.7, "scielo" to 0.7, "sciencedirect.com" to 0.6, "mayoclinic.org" to 0.8,
        "infobae.com" to 0.4, "lanacion.com.ar" to 0.4, "clarin.com" to 0.4, "elpais.com" to 0.5, "github.com" to 0.5
    )
    private val DOMINIOS_MALOS = listOf(
        "pinterest.", "facebook.com", "instagram.com", "tiktok.com", "quora.com", "youtube.com", "twitter.com", "x.com/",
        "linkedin.com", "amazon.", "mercadolibre.", "aliexpress.", "temu.", "ebay."
    )

    /** Reputación del sitio: favorece fuentes serias y descarta redes sociales, tiendas y sitios que no se pueden leer como texto. */
    fun calidadDominio(url: String): Double {
        val u = url.lowercase()
        DOMINIOS_BUENOS.firstOrNull { u.contains(it.first) }?.let { return it.second }
        if (DOMINIOS_MALOS.any { u.contains(it) }) return -2.0
        return 0.0
    }

    /** Páginas que no son contenido: captcha, aviso de cookies, "activa JavaScript", acceso denegado… */
    fun esContenidoUtil(texto: String): Boolean {
        if (texto.length < 200) return false
        val t = plegar(texto.take(1500))
        val muros = listOf("enable javascript", "habilita javascript", "activa javascript", "are you a robot", "verify you are human",
            "access denied", "acceso denegado", "just a moment", "checking your browser", "captcha", "unusual traffic", "cloudflare")
        val hits = muros.count { it in t }
        return !(hits >= 1 && texto.length < 1500) && hits < 3
    }

    /** ¿Dos textos dicen casi lo mismo? (sitios que copian a otros). Similitud de palabras > 70 %. */
    fun sonParecidos(a: String, b: String): Boolean {
        fun palabras(x: String) = Regex("[a-z0-9]{4,}").findAll(plegar(x.take(2500))).map { it.value }.toSet()
        val pa = palabras(a); val pb = palabras(b)
        if (pa.size < 20 || pb.size < 20) return false
        return pa.intersect(pb).size.toDouble() / minOf(pa.size, pb.size) > 0.7
    }

    /** La consulta pide algo reciente (precios, noticias, resultados, "hoy"…): conviene priorizar noticias. */
    fun esUrgente(q: String): Boolean = Regex("""\b(hoy|ayer|ultim[oa]s?|actual(es|idad)?|reciente|nuevo|nueva|202\d|precio|cotizaci[oó]n|noticias?|resultado|ahora|esta semana)\b""")
        .containsMatchIn(plegar(q))

    /** ¿Conviene buscar en internet antes de contestar en el chat? Preguntas de hechos o actualidad sí; saludos, creatividad, cálculos y charla no. */
    fun necesitaInternet(q: String): Boolean {
        val t = plegar(q).trim()
        val n = t.split(Regex("\\s+")).size
        if (n < 3) return false
        if (Regex("""\b(escribe|redacta|traduce|cuentame|chiste|poema|cancion|inventa|recuerdame|recuerda que|resume|corrige|programa|codigo|calcula|como estas|como te llamas|quien eres|tu nombre|que hora|que dia es)\b""").containsMatchIn(t)) return false
        if (esUrgente(q)) return true
        return t.contains("?") || Regex("""\b(que|quien|quienes|cuando|donde|cuanto|cuantos|cuantas|cual|cuales|como funciona|como se|por que|para que|existe|significa|definicion|historia|diferencia|explica|explicame|dime)\b""").containsMatchIn(t)
    }

    /**
     * Búsqueda rápida para el chat: todos los buscadores a la vez con tiempo corto, lee las mejores páginas y devuelve
     * (texto para el prompt, enlaces usados). Vacío si no hubo nada útil.
     */
    fun contextoEnVivo(q: String, maxCaracteres: Int = 6000): Pair<String, List<String>> {
        val sinTexto = setOf("pdf", "jpg", "jpeg", "png", "gif", "webp", "mp3", "mp4", "zip", "docx", "xlsx", "pptx")
        val res = buscarTodo(listOf(q), 5, 14).filter { calidadDominio(it.url) > -1.0 && extension(it.url) !in sinTexto }
        if (res.isEmpty()) return "" to emptyList()
        val por = (maxCaracteres / 4).coerceAtLeast(800)
        val partes = ArrayList<String>()
        val usados = ArrayList<Resultado>()
        for ((r, t) in leerVarias(res.take(7), Int.MAX_VALUE, 14)) {
            if (partes.size >= 4) break
            val p = mejoresPasajes(t, listOf(q), por)
            if (partes.any { sonParecidos(it, p) }) continue
            partes.add(p); usados.add(r)
        }
        if (partes.isEmpty()) return "" to emptyList()
        val txt = "\n\nINFORMACIÓN ACTUAL DE INTERNET (úsala para responder con datos correctos y menciona [n] la fuente; si no alcanza para contestar, dilo):\n" +
            partes.indices.joinToString("\n\n") { "[${it + 1}] ${usados[it].titulo}\n${partes[it]}" }
        return txt to usados.map { it.url }
    }

    /** Sugerencia de ortografía de Wikipedia ("einstain" -> "einstein"); null si no hay. */
    fun sugerencia(q: String): String? = try {
        val j = JSONObject(get("https://es.wikipedia.org/w/api.php?action=query&format=json&list=search&srsearch=${enc(q)}&srinfo=suggestion&srlimit=1", 100_000))
        j.optJSONObject("query")?.optJSONObject("searchinfo")?.optString("suggestion")?.takeIf { it.isNotBlank() }
    } catch (e: Exception) { null }

    /** Segunda ronda sin gastar el modelo: nuevas consultas con los términos más repetidos en los títulos hallados. */
    fun consultasDeRefuerzo(consultas: List<String>, hallados: List<Resultado>): List<String> {
        val base = claves(consultas.first()).toSet()
        val frec = HashMap<String, Int>()
        for (r in hallados.take(15)) for (w in Regex("[a-zA-ZáéíóúñÁÉÍÓÚÑ0-9]{5,}").findAll(r.titulo).map { it.value.lowercase() }.toSet()) {
            if (plegar(w).take(6) !in base.map { it.take(6) } && plegar(w) !in PALABRAS_VACIAS) frec[w] = (frec[w] ?: 0) + 1
        }
        val top = frec.entries.filter { it.value >= 2 }.sortedByDescending { it.value }.take(3).map { it.key }
        val salida = ArrayList<String>()
        if (top.isNotEmpty()) salida.add(consultas.first() + " " + top.take(2).joinToString(" "))
        salida.add(consultas.first() + " guía completa")
        salida.add(consultas.first() + " explicación datos")
        return salida.distinct()
    }

    private fun puntaje(texto: String, claves: List<String>): Double {
        if (claves.isEmpty() || texto.isEmpty()) return 0.0
        val t = plegar(texto)
        var p = 0.0
        for (k in claves) {
            val veces = Regex(Regex.escape(k)).findAll(t).take(4).count()
            if (veces > 0) p += 1.0 + 0.25 * (veces - 1)
        }
        return p / claves.size
    }

    /**
     * Recuperación de pasajes: parte el texto en trozos, puntúa cada uno según las palabras de la consulta
     * y se queda con los más relevantes (en su orden original) hasta llenar `max` caracteres.
     * Así entra al modelo lo que contesta la pregunta, no solo el principio de la página.
     */
    fun mejoresPasajes(texto: String, consultas: List<String>, max: Int): String {
        if (texto.length <= max) return texto
        val palabras = claves(consultas.joinToString(" "))
        val trozos = ArrayList<String>()
        for (parrafo in texto.split('\n')) {
            val p = parrafo.trim()
            if (p.isEmpty()) continue
            if (p.length <= 600) { trozos.add(p); continue }
            var actual = StringBuilder()
            for (f in p.split(Regex("(?<=[.!?])\\s+"))) {
                if (actual.length + f.length > 500 && actual.isNotEmpty()) { trozos.add(actual.toString()); actual = StringBuilder() }
                actual.append(f).append(' ')
            }
            if (actual.isNotBlank()) trozos.add(actual.toString().trim())
        }
        val puntuados = trozos.mapIndexed { i, t ->
            // un pequeño bonus a lo primero de la página (suele ser la definición o el resumen)
            Triple(i, t, puntaje(t, palabras) + (if (i < 3) 0.15 else 0.0) + (if (t.startsWith("## ")) 0.05 else 0.0))
        }
        val elegidos = HashSet<Int>()
        var usado = 0
        for ((i, t, _) in puntuados.sortedByDescending { it.third }) {
            if (usado + t.length > max && elegidos.isNotEmpty()) continue
            elegidos.add(i); usado += t.length + 1
            if (usado >= max) break
        }
        return puntuados.filter { it.first in elegidos }.joinToString("\n") { it.second }.take(max)
    }

    // ---------- Búsquedas ----------

    /** Wikipedia en español: título, enlace y resumen de los mejores artículos. */
    fun buscarWikipedia(q: String, n: Int = 3, idioma: String = "es"): List<Resultado> {
        val url = "https://$idioma.wikipedia.org/w/api.php?action=query&format=json&generator=search&gsrsearch=${enc(q)}" +
            "&gsrlimit=$n&prop=extracts%7Cinfo&exintro=1&explaintext=1&exsentences=6&inprop=url&redirects=1"
        val paginas = JSONObject(get(url, 200_000)).optJSONObject("query")?.optJSONObject("pages") ?: return emptyList()
        val salida = ArrayList<Pair<Int, Resultado>>()
        val claves = paginas.keys()
        while (claves.hasNext()) {
            val p = paginas.getJSONObject(claves.next())
            salida.add(p.optInt("index", 99) to Resultado(p.optString("title"), p.optString("fullurl"), p.optString("extract")))
        }
        return salida.sortedBy { it.first }.map { it.second }
    }

    /** Buscador web general (DuckDuckGo, versión simple). Puede fallar o bloquear: quien lo use debe tener plan B. */
    fun buscarWeb(q: String, n: Int = 6): List<Resultado> {
        val html = get("https://html.duckduckgo.com/html/?q=${enc(q)}", 300_000)
        val rx = Regex("""<a[^>]+class="result__a"[^>]+href="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        val rxDestino = Regex("""[?&]uddg=([^&]+)""")
        val salida = ArrayList<Resultado>()
        for (m in rx.findAll(html)) {
            var href = decodificarHtml(m.groupValues[1])
            if (href.startsWith("//")) href = "https:$href"
            val u = rxDestino.find(href)?.groupValues?.get(1)?.let { decodificar(it) } ?: href
            if (!u.startsWith("http") || u.contains("duckduckgo.com/y.js")) continue
            salida.add(Resultado(textoPlano(m.groupValues[2]), u))
            if (salida.size >= n) break
        }
        return salida
    }

    /** Segundo buscador (Bing, versión HTML) para cuando DuckDuckGo falla o bloquea. */
    fun buscarBing(q: String, n: Int = 6): List<Resultado> {
        val html = get("https://www.bing.com/search?q=${enc(q)}&setlang=es", 300_000)
        val rx = Regex("""<li class="b_algo".*?<h2[^>]*><a[^>]+href="(https?://[^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        val salida = ArrayList<Resultado>()
        for (m in rx.findAll(html)) {
            val u = decodificarHtml(m.groupValues[1])
            if (u.contains("bing.com/")) continue
            salida.add(Resultado(textoPlano(m.groupValues[2]), u))
            if (salida.size >= n) break
        }
        return salida
    }

    /** Tercer buscador independiente (Mojeek, HTML simple y sin bloqueos agresivos). */
    fun buscarMojeek(q: String, n: Int = 6): List<Resultado> {
        val html = get("https://www.mojeek.com/search?q=${enc(q)}&lb=es", 300_000)
        val rx = Regex("""<a[^>]+class="title"[^>]+href="(https?://[^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        val salida = ArrayList<Resultado>()
        for (m in rx.findAll(html)) {
            salida.add(Resultado(textoPlano(m.groupValues[2]), decodificarHtml(m.groupValues[1])))
            if (salida.size >= n) break
        }
        return salida
    }

    /** DuckDuckGo versión "lite": otra página y otro formato, por si la versión normal bloquea. */
    fun buscarDuckLite(q: String, n: Int = 6): List<Resultado> {
        val html = get("https://lite.duckduckgo.com/lite/?q=${enc(q)}", 300_000)
        val rx = Regex("""<a[^>]+href="([^"]+)"[^>]*class=['"]result-link['"][^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        val rxDestino = Regex("""[?&]uddg=([^&]+)""")
        val salida = ArrayList<Resultado>()
        for (m in rx.findAll(html)) {
            var href = decodificarHtml(m.groupValues[1])
            if (href.startsWith("//")) href = "https:$href"
            val u = rxDestino.find(href)?.groupValues?.get(1)?.let { decodificar(it) } ?: href
            if (!u.startsWith("http") || u.contains("duckduckgo.com")) continue
            salida.add(Resultado(textoPlano(m.groupValues[2]), u))
            if (salida.size >= n) break
        }
        return salida
    }

    /** Artículo COMPLETO de Wikipedia (no solo la introducción) para un título exacto. */
    fun wikipediaCompleta(titulo: String, idioma: String = "es", maxCaracteres: Int = 12_000): String = try {
        val url = "https://$idioma.wikipedia.org/w/api.php?action=query&format=json&prop=extracts&explaintext=1&redirects=1&titles=${enc(titulo)}"
        val paginas = JSONObject(get(url, 600_000)).optJSONObject("query")?.optJSONObject("pages")
        val k = paginas?.keys()
        val txt = if (k != null && k.hasNext()) paginas.getJSONObject(k.next()).optString("extract") else ""
        txt.replace(Regex("\\n=+ *(.*?) *=+\\n")) { "\n## ${it.groupValues[1]}\n" }.take(maxCaracteres)
    } catch (e: Exception) { "" }

    // ---------- Más fuentes: buscadores extra, noticias, foros, ciencia, libros ----------

    private fun tag(xml: String, nombre: String): String =
        Regex("(?is)<$nombre[^>]*>(.*?)</$nombre>").find(xml)?.groupValues?.get(1)?.replace("<![CDATA[", "")?.replace("]]>", "")?.trim().orEmpty()

    /** Yahoo (HTML): otro índice distinto de los anteriores. */
    fun buscarYahoo(q: String, n: Int = 6): List<Resultado> {
        val html = get("https://search.yahoo.com/search?p=${enc(q)}&ei=UTF-8", 300_000)
        val rx = Regex("""<h3 class="title[^"]*"[^>]*>\s*<a[^>]+href="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        val salida = ArrayList<Resultado>()
        for (m in rx.findAll(html)) {
            var u = decodificarHtml(m.groupValues[1])
            Regex("""/RU=([^/]+)/""").find(u)?.let { u = decodificar(it.groupValues[1]) }
            if (!u.startsWith("http") || u.contains("yahoo.com/")) continue
            salida.add(Resultado(textoPlano(m.groupValues[2]), u))
            if (salida.size >= n) break
        }
        return salida
    }

    /** Brave Search (HTML). */
    fun buscarBrave(q: String, n: Int = 6): List<Resultado> {
        val html = get("https://search.brave.com/search?q=${enc(q)}&source=web", 400_000)
        val rx = Regex("""<a[^>]+href="(https?://[^"]+)"[^>]*class="[^"]*heading-serpresult[^"]*"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        val salida = ArrayList<Resultado>()
        for (m in rx.findAll(html)) {
            val u = decodificarHtml(m.groupValues[1])
            if (u.contains("brave.com/")) continue
            salida.add(Resultado(textoPlano(m.groupValues[2]), u))
            if (salida.size >= n) break
        }
        return salida
    }

    /** Noticias recientes (Google News, RSS). */
    fun buscarNoticias(q: String, n: Int = 6): List<Resultado> {
        val xml = get("https://news.google.com/rss/search?q=${enc(q)}&hl=es-419&gl=AR&ceid=AR:es-419", 400_000)
        val salida = ArrayList<Resultado>()
        for (m in Regex("(?is)<item>(.*?)</item>").findAll(xml)) {
            val item = m.groupValues[1]
            val u = tag(item, "link")
            if (!u.startsWith("http")) continue
            salida.add(Resultado(textoPlano(tag(item, "title")), u, textoPlano(decodificarHtml(tag(item, "description")))))
            if (salida.size >= n) break
        }
        return salida
    }

    /** Preguntas y respuestas técnicas (Stack Exchange) con las respuestas aceptadas. */
    fun buscarStack(q: String, n: Int = 4): List<Resultado> {
        val url = "https://api.stackexchange.com/2.3/search/advanced?order=desc&sort=relevance&q=${enc(q)}&accepted=True&site=stackoverflow&pagesize=$n&filter=withbody"
        val items = JSONObject(get(url, 600_000)).optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).map { items.getJSONObject(it) }.map {
            Resultado(decodificarHtml(it.optString("title")), it.optString("link"), textoPlano(it.optString("body")))
        }
    }

    /** Artículos científicos (arXiv). */
    fun buscarArxiv(q: String, n: Int = 4): List<Resultado> {
        val xml = get("https://export.arxiv.org/api/query?search_query=all:${enc(q)}&start=0&max_results=$n", 400_000)
        return Regex("(?is)<entry>(.*?)</entry>").findAll(xml).map { it.groupValues[1] }.map {
            Resultado(textoPlano(tag(it, "title")), tag(it, "id"), textoPlano(tag(it, "summary")))
        }.filter { it.url.startsWith("http") }.toList()
    }

    /** Discusiones y experiencias de la gente (Reddit). */
    fun buscarReddit(q: String, n: Int = 5): List<Resultado> {
        val hijos = JSONObject(get("https://www.reddit.com/search.json?q=${enc(q)}&limit=$n&sort=relevance", 600_000))
            .optJSONObject("data")?.optJSONArray("children") ?: return emptyList()
        return (0 until hijos.length()).map { hijos.getJSONObject(it).optJSONObject("data") }.filterNotNull().map {
            Resultado(it.optString("title"), "https://www.reddit.com" + it.optString("permalink"), it.optString("selftext").take(3000))
        }
    }

    /** Hacker News (Algolia). */
    fun buscarHackerNews(q: String, n: Int = 4): List<Resultado> {
        val hits = JSONObject(get("https://hn.algolia.com/api/v1/search?query=${enc(q)}&hitsPerPage=$n&tags=story", 300_000)).optJSONArray("hits") ?: return emptyList()
        return (0 until hits.length()).map { hits.getJSONObject(it) }.filter { it.optString("url").startsWith("http") }
            .map { Resultado(it.optString("title"), it.optString("url")) }
    }

    /** Libros (Open Library) y documentos del Internet Archive. */
    fun buscarLibros(q: String, n: Int = 4): List<Resultado> {
        val docs = JSONObject(get("https://openlibrary.org/search.json?q=${enc(q)}&limit=$n", 300_000)).optJSONArray("docs") ?: return emptyList()
        return (0 until docs.length()).map { docs.getJSONObject(it) }.map {
            val autor = it.optJSONArray("author_name")?.optString(0).orEmpty()
            Resultado(it.optString("title") + (if (autor.isNotEmpty()) " — $autor" else ""), "https://openlibrary.org" + it.optString("key"),
                "Libro de $autor, primera edición ${it.optString("first_publish_year")}.")
        }
    }

    fun buscarArchive(q: String, n: Int = 4): List<Resultado> {
        val docs = JSONObject(get("https://archive.org/advancedsearch.php?q=${enc(q)}&fl%5B%5D=identifier&fl%5B%5D=title&fl%5B%5D=description&rows=$n&output=json", 300_000))
            .optJSONObject("response")?.optJSONArray("docs") ?: return emptyList()
        return (0 until docs.length()).map { docs.getJSONObject(it) }.map {
            Resultado(it.optString("title"), "https://archive.org/details/" + it.optString("identifier"), textoPlano(it.optString("description")))
        }
    }

    /** Wikipedia, Wikinoticias y Wikcionario en varios idiomas para temas internacionales. */
    fun buscarWikis(q: String): List<Resultado> {
        val salida = ArrayList<Resultado>()
        for (i in listOf("pt", "fr", "de", "it")) try { salida.addAll(buscarWikipedia(q, 1, i)) } catch (_: Exception) {}
        return salida
    }

    private fun host(u: String) = try { URL(u).host.removePrefix("www.") } catch (e: Exception) { u }

    /**
     * Busca en todos los buscadores a la vez (DuckDuckGo, Bing, Wikipedia en español e inglés) y junta los resultados
     * sin repetir sitios. Si alguno falla, los demás siguen: basta con que uno responda.
     */
    fun buscarTodo(consultas: List<String>, porBuscador: Int = 8, segundos: Long = 60): List<Resultado> {
        val trabajos = ArrayList<() -> List<Resultado>>()
        for (q in consultas) {
            trabajos.add { buscarWeb(q, porBuscador) }
            trabajos.add { buscarBing(q, porBuscador) }
            trabajos.add { buscarMojeek(q, porBuscador) }
        }
        val principal = consultas.first()
        trabajos.add { buscarDuckLite(principal, porBuscador) }
        for (q in consultas.take(2)) {
            trabajos.add { buscarYahoo(q, porBuscador) }
            trabajos.add { buscarBrave(q, porBuscador) }
        }
        trabajos.add { buscarNoticias(principal, 5) }
        trabajos.add { buscarStack(principal) }
        trabajos.add { buscarArxiv(principal) }
        trabajos.add { buscarReddit(principal) }
        trabajos.add { buscarHackerNews(principal) }
        trabajos.add { buscarLibros(principal) }
        trabajos.add { buscarArchive(principal) }
        trabajos.add { buscarWikis(principal) }
        trabajos.add { buscarWikipedia(principal, 3, "es") }
        trabajos.add { buscarWikipedia(principal, 2, "en") }
        val futuros = Hilos.red.invokeAll(trabajos.map { t -> java.util.concurrent.Callable { try { t() } catch (e: Exception) { emptyList<Resultado>() } } }, segundos, java.util.concurrent.TimeUnit.SECONDS)
        val listas = futuros.map { f -> try { if (f.isCancelled) emptyList() else f.get() } catch (e: Exception) { emptyList<Resultado>() } }
        // Cada URL cuenta cuántos buscadores la devolvieron: que varios coincidan es señal de calidad
        val votos = HashMap<String, Int>()
        val clave = { r: Resultado -> host(r.url) + "/" + r.url.substringAfter(host(r.url)).substringBefore('?').trimEnd('/').take(60) }
        for (l in listas) for (r in l) votos[clave(r)] = (votos[clave(r)] ?: 0) + 1
        val palabras = claves(consultas.joinToString(" "))
        val vistos = HashSet<String>()
        val candidatos = ArrayList<Pair<Double, Resultado>>()
        for (l in listas) l.forEachIndexed { pos, r ->
            if (!vistos.add(clave(r))) return@forEachIndexed
            // posición en su buscador + coincidencias entre buscadores + palabras de la consulta en título/dirección
            val p = 1.0 / (1 + pos) + 0.6 * ((votos[clave(r)] ?: 1) - 1) + 1.5 * puntaje(r.titulo + " " + r.url, palabras) +
                (if (r.texto.isNotBlank()) 0.3 else 0.0) + 0.8 * calidadDominio(r.url)
            candidatos.add(p to r)
        }
        return candidatos.sortedByDescending { it.first }.map { it.second }
    }

    /** Lee varias páginas a la vez; devuelve (resultado, texto principal) de las que se pudieron leer. */
    fun leerVarias(rs: List<Resultado>, max: Int, segundos: Long = 30): List<Pair<Resultado, String>> {
        if (rs.isEmpty()) return emptyList()
        val futuros = Hilos.red.invokeAll(rs.map { r -> java.util.concurrent.Callable {
            if (r.texto.length > 400) r to r.texto else
                try { r to textoPrincipal(get(r.url), max).ifEmpty { r.texto } } catch (e: Exception) { r to r.texto }
        } }, segundos, java.util.concurrent.TimeUnit.SECONDS)
        return futuros.mapNotNull { f -> try { if (f.isCancelled) null else f.get().takeIf { it.second.length > 200 && esContenidoUtil(it.second) } } catch (e: Exception) { null } }
    }

    // ---------- Descargas ----------

    private fun extPorMime(m: String) = when {
        m.contains("pdf") -> "pdf"
        m.contains("epub") -> "epub"
        m.contains("jpeg") -> "jpg"
        m.contains("png") -> "png"
        m.contains("mpeg") -> "mp3"
        m.startsWith("text/plain") -> "txt"
        else -> ""
    }

    /** Mira nombre, tamaño y tipo del archivo SIN descargarlo (para decidir si hace falta tu OK). */
    fun sondear(url: String): Sonda {
        val c = abrir(url)
        try {
            val disp = c.getHeaderField("Content-Disposition") ?: ""
            val deDisp = Regex("""filename\*?=(?:UTF-8'')?"?([^";]+)"?""", RegexOption.IGNORE_CASE)
                .find(disp)?.groupValues?.get(1)?.let { decodificar(it) }
            var nombre = (deDisp ?: decodificar(c.url.path.substringAfterLast('/'))).ifEmpty { "archivo" }
            val mime = (c.contentType ?: "application/octet-stream").substringBefore(';').trim()
            if (extension(nombre).isEmpty()) {
                val e = extPorMime(mime)
                if (e.isNotEmpty()) nombre += ".$e"
            }
            nombre = nombre.replace(Regex("""[\\/:*?"<>|]"""), "_").take(120)
            return Sonda(nombre, c.contentLengthLong, mime)
        } finally {
            c.disconnect()
        }
    }

    /** Guarda un archivo ya armado en Descargas/JARVIS. */
    fun guardarBytes(ctx: Context, nombre: String, mime: String, datos: ByteArray): Archivo {
        if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, nombre)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/JARVIS")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                ?: throw Exception("no pude crear el archivo en Descargas")
            try {
                (ctx.contentResolver.openOutputStream(uri) ?: throw Exception("no pude abrir el archivo")).use { it.write(datos) }
            } catch (e: Exception) {
                try { ctx.contentResolver.delete(uri, null, null) } catch (_: Exception) {}
                throw e
            }
            val listo = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            ctx.contentResolver.update(uri, listo, null, null)
            return Archivo(nombre, uri.toString())
        }
        val dir = ctx.getExternalFilesDir("descargas") ?: ctx.filesDir
        val f = File(dir, nombre)
        f.writeBytes(datos)
        return Archivo(nombre, Uri.fromFile(f).toString())
    }

    /** Descarga a Descargas/JARVIS. progreso recibe 0..100; si cancelado() devuelve true se corta y se borra lo bajado. */
    fun descargar(
        ctx: Context, url: String, nombre: String, progreso: (Int) -> Unit, cancelado: () -> Boolean,
        lecturaMs: Int = 25_000, soloImagen: Boolean = false
    ): Archivo {
        val c = abrir(url, lecturaMs)
        try {
            val total = c.contentLengthLong
            val mime = (c.contentType ?: "application/octet-stream").substringBefore(';').trim()
            if (soloImagen && !mime.startsWith("image/")) throw Exception("el servicio no devolvió una imagen")
            var uri: Uri? = null
            var archivo: File? = null
            val salida: OutputStream
            if (Build.VERSION.SDK_INT >= 29) {
                val cv = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, nombre)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/JARVIS")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val creado = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                    ?: throw Exception("no pude crear el archivo en Descargas")
                uri = creado
                salida = ctx.contentResolver.openOutputStream(creado) ?: throw Exception("no pude abrir el archivo")
            } else {
                val dir = ctx.getExternalFilesDir("descargas") ?: ctx.filesDir
                val f = File(dir, nombre)
                archivo = f
                salida = FileOutputStream(f)
            }
            var hecho = 0L
            var ultimo = -20
            try {
                salida.use { out ->
                    val b = ByteArray(32 * 1024)
                    c.inputStream.use { ins ->
                        while (true) {
                            if (cancelado()) throw Exception("descarga cancelada")
                            val n = ins.read(b)
                            if (n < 0) break
                            out.write(b, 0, n)
                            hecho += n
                            if (total > 0) {
                                val p = (hecho * 100 / total).toInt()
                                if (p - ultimo >= 20) { ultimo = p; progreso(p) }
                            }
                        }
                    }
                }
                if (total > 0 && hecho < total) throw Exception("la descarga quedó incompleta")
            } catch (e: Exception) {
                uri?.let { try { ctx.contentResolver.delete(it, null, null) } catch (_: Exception) {} }
                archivo?.delete()
                throw e
            }
            val u = uri
            if (u != null) {
                val cv = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                ctx.contentResolver.update(u, cv, null, null)
                return Archivo(nombre, u.toString())
            }
            return Archivo(nombre, Uri.fromFile(archivo ?: throw Exception("no pude guardar el archivo")).toString())
        } finally {
            c.disconnect()
        }
    }
}
