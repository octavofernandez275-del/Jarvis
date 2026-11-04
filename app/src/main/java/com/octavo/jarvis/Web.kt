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

    /** Descarga el texto de una página (hasta maxBytes). */
    fun get(url: String, maxBytes: Int = 400_000): String {
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

    private fun decodificarHtml(s: String) = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
        .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'")

    fun textoPlano(h: String): String =
        decodificarHtml(h.replace(Regex("(?s)<[^>]+>"), " ")).replace(Regex("\\s+"), " ").trim()

    /** Lo importante de una página: sus párrafos largos. */
    fun textoPrincipal(html: String, max: Int): String {
        val limpio = html.replace(Regex("(?is)<(script|style|noscript|svg)[^>]*>.*?</\\1>"), " ")
        val parrafos = Regex("(?is)<p[^>]*>(.*?)</p>").findAll(limpio)
            .map { textoPlano(it.groupValues[1]) }.filter { it.length > 60 }.toList()
        val base = if (parrafos.size >= 2) parrafos.joinToString("\n") else textoPlano(limpio)
        return base.take(max)
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

    private fun host(u: String) = try { URL(u).host.removePrefix("www.") } catch (e: Exception) { u }

    /**
     * Busca en todos los buscadores a la vez (DuckDuckGo, Bing, Wikipedia en español e inglés) y junta los resultados
     * sin repetir sitios. Si alguno falla, los demás siguen: basta con que uno responda.
     */
    fun buscarTodo(consultas: List<String>, porBuscador: Int = 5): List<Resultado> {
        val trabajos = ArrayList<() -> List<Resultado>>()
        for (q in consultas) {
            trabajos.add { buscarWeb(q, porBuscador) }
            trabajos.add { buscarBing(q, porBuscador) }
        }
        val principal = consultas.first()
        trabajos.add { buscarWikipedia(principal, 2, "es") }
        trabajos.add { buscarWikipedia(principal, 1, "en") }
        // Grupo compartido de prioridad baja (antes se creaba y destruía uno en cada búsqueda); invokeAll cancela lo que no llegó a tiempo
        val futuros = Hilos.red.invokeAll(trabajos.map { t -> java.util.concurrent.Callable { try { t() } catch (e: Exception) { emptyList<Resultado>() } } }, 40, java.util.concurrent.TimeUnit.SECONDS)
        // Intercala: el primero de cada buscador, luego el segundo… así ningún buscador domina
        val listas = futuros.map { f -> try { if (f.isCancelled) emptyList() else f.get() } catch (e: Exception) { emptyList<Resultado>() } }
        val vistos = HashSet<String>()
        val salida = ArrayList<Resultado>()
        for (i in 0 until (listas.maxOfOrNull { it.size } ?: 0)) {
            for (l in listas) {
                val r = l.getOrNull(i) ?: continue
                if (vistos.add(host(r.url) + r.url.substringAfterLast('/').take(30))) salida.add(r)
            }
        }
        return salida
    }

    /** Lee varias páginas a la vez; devuelve (resultado, texto principal) de las que se pudieron leer. */
    fun leerVarias(rs: List<Resultado>, max: Int): List<Pair<Resultado, String>> {
        if (rs.isEmpty()) return emptyList()
        val futuros = Hilos.red.invokeAll(rs.map { r -> java.util.concurrent.Callable {
            try { if (r.texto.isNotBlank()) r to r.texto else r to textoPrincipal(get(r.url), max) } catch (e: Exception) { r to "" }
        } }, 30, java.util.concurrent.TimeUnit.SECONDS)
        return futuros.mapNotNull { f -> try { if (f.isCancelled) null else f.get().takeIf { it.second.length > 200 } } catch (e: Exception) { null } }
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
