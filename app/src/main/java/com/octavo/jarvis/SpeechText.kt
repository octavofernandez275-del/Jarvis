package com.octavo.jarvis

/**
 * Limpia el texto de la IA para que suene bien al hablarlo:
 * sin markdown, sin emojis, sin enlaces ni etiquetas.
 */
object TextCleaner {
    private val imagen = Regex("""!\[([^\]]*)]\([^)]*\)""")
    private val enlace = Regex("""\[([^\]]+)]\([^)]*\)""")
    private val url = Regex("""https?://\S+""")
    private val etiquetaHtml = Regex("""</?[a-zA-Z][^>]*>""")
    private val encabezado = Regex("""^\s{0,3}#{1,6}\s*""")
    private val vineta = Regex("""^\s*(?:[-*+•]|\d{1,2}[.)])(?:\s+|$)""")
    private val cita = Regex("""^\s*>+\s?""")
    private val separador = Regex("""^[\s:|\-]+$""")
    private val enfasis = Regex("""\*{1,3}|~~|`+""")
    private val guionBajo = Regex("""(?<![\p{L}\p{N}])_+|_+(?![\p{L}\p{N}])""")
    private val tuberia = Regex("""\s*\|\s*""")
    private val espacios = Regex("""\s+""")

    /** Devuelve el texto listo para hablar, o "" si no queda nada que leer. */
    fun clean(raw: String): String {
        var s = raw.trim()
        if (s.isEmpty() || separador.matches(s)) return ""
        s = imagen.replace(s, "\$1")
        s = enlace.replace(s, "\$1")
        s = url.replace(s, "un enlace")
        s = etiquetaHtml.replace(s, "")
        s = encabezado.replace(s, "")
        s = vineta.replace(s, "")
        s = cita.replace(s, "")
        s = tuberia.replace(s, ", ")
        s = enfasis.replace(s, "")
        s = guionBajo.replace(s, "")
        s = sinEmojis(s)
        s = espacios.replace(s, " ").trim { it == ',' || it.isWhitespace() }
        return if (s.any { it.isLetterOrDigit() }) s else ""
    }

    /** Quita emojis y símbolos gráficos (por rango de code point). */
    private fun sinEmojis(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            val esEmoji = cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF ||
                cp in 0x2300..0x23FF || cp in 0x2B00..0x2BFF ||
                cp in 0xFE00..0xFE0F || cp == 0x200D || cp == 0x20E3 ||
                cp in 0xE0020..0xE007F
            if (!esEmoji) sb.appendCodePoint(cp)
        }
        return sb.toString()
    }
}

/**
 * Corta el texto que llega token a token en frases listas para hablar.
 * - Corta en . ! ? … (seguidos de espacio) y en saltos de línea.
 * - Junta frases muy cortas para que la voz no suene entrecortada.
 * - Salta los bloques de código (```), solo avisa que quedan en pantalla.
 * Se usa siempre desde el hilo principal (lo garantiza VoiceOutput).
 */
class SentenceSplitter {
    private val buf = StringBuilder()   // texto recibido aún sin cortar
    private val acc = StringBuilder()   // frases limpias esperando alcanzar el largo mínimo
    private var enCodigo = false
    private var primera = true      // la primera frase de la respuesta sale antes (menos espera para oír a Jarvis)

    fun feed(token: String): List<String> {
        buf.append(token)
        return drain(false)
    }

    /** Fin de la respuesta: entrega lo que quede. */
    fun flush(): List<String> = drain(true)

    fun reset() {
        buf.setLength(0)
        acc.setLength(0)
        enCodigo = false
        primera = true
    }

    private fun drain(fin: Boolean): List<String> {
        val out = ArrayList<String>()
        var start = 0
        var i = 0
        while (i < buf.length) {
            val c = buf[i]
            val sigueEspacio = i + 1 < buf.length && buf[i + 1].isWhitespace()
            val corte = c == '\n' ||
                ((c == '.' || c == '!' || c == '?' || c == '…') && sigueEspacio) ||
                // Solo en la primera frase: también se corta en coma, punto y coma o dos puntos
                (primera && (c == ',' || c == ';' || c == ':') && sigueEspacio)
            if (corte) {
                tomar(buf.substring(start, i + 1), out)
                start = i + 1
            } else if (i - start >= MAX_CHARS) {
                // Frase larguísima sin puntuación: cortar en el último espacio
                var j = buf.lastIndexOf(" ", i)
                if (j <= start) j = i
                tomar(buf.substring(start, j + 1), out)
                start = j + 1
                i = start
                continue
            }
            i++
        }
        buf.delete(0, start)
        if (fin) {
            tomar(buf.toString(), out)
            buf.setLength(0)
            if (acc.isNotBlank()) out.add(acc.toString().trim())
            acc.setLength(0)
            enCodigo = false
        }
        return out
    }

    /** Limpia un trozo y lo acumula; si ya es suficientemente largo, lo entrega. */
    private fun tomar(raw: String, out: MutableList<String>) {
        var c = TextCleaner.clean(sinCodigo(raw))
        if (c.isEmpty()) return
        // Un renglón de lista sin punto final: agregar punto para que haya pausa
        if (raw.endsWith("\n") && c.last().isLetterOrDigit()) c += "."
        // La limpieza quita las comas del final; se devuelve una para que haya pausa al hablar
        if (primera && raw.trimEnd().let { it.endsWith(",") || it.endsWith(";") || it.endsWith(":") }) c += ","
        if (acc.isNotEmpty()) acc.append(' ')
        acc.append(c)
        if (acc.length >= (if (primera) MIN_PRIMERA else MIN_CHARS)) {
            out.add(acc.toString())
            acc.setLength(0)
            primera = false
        }
    }

    /** Quita lo que esté dentro de ``` ... ``` (el estado se mantiene entre trozos). */
    private fun sinCodigo(seg: String): String {
        if (!enCodigo && !seg.contains("```")) return seg
        val sb = StringBuilder()
        seg.split("```").forEachIndexed { n, parte ->
            if (n > 0) {
                enCodigo = !enCodigo
                if (!enCodigo) sb.append(" Te dejo el código en pantalla. ")
            }
            if (!enCodigo) sb.append(parte)
        }
        return sb.toString()
    }

    private companion object {
        const val MIN_CHARS = 18
        const val MIN_PRIMERA = 12
        const val MAX_CHARS = 220
    }
}
