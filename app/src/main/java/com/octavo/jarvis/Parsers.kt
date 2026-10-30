package com.octavo.jarvis

import java.util.Calendar

/** Utilidades de texto en español: normalizar, leer horas y duraciones, fecha y hora actuales. */
object Parsers {

    /**
     * Minúsculas y sin tildes, SIN cambiar el largo del texto.
     * Así se puede buscar en la versión "plegada" y cortar el texto original por posición.
     */
    fun fold(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            val c = ch.lowercaseChar()
            sb.append(
                when (c) {
                    'á', 'à', 'ä', 'â' -> 'a'
                    'é', 'è', 'ë', 'ê' -> 'e'
                    'í', 'ì', 'ï', 'î' -> 'i'
                    'ó', 'ò', 'ö', 'ô' -> 'o'
                    'ú', 'ù', 'ü', 'û' -> 'u'
                    else -> c
                }
            )
        }
        return sb.toString()
    }

    private val numeros = mapOf(
        "un" to 1, "uno" to 1, "una" to 1, "dos" to 2, "tres" to 3, "cuatro" to 4,
        "cinco" to 5, "seis" to 6, "siete" to 7, "ocho" to 8, "nueve" to 9, "diez" to 10,
        "once" to 11, "doce" to 12, "quince" to 15, "veinte" to 20, "treinta" to 30,
        "cuarenta" to 40, "cincuenta" to 50, "sesenta" to 60
    )

    /** "10" -> 10, "cinco" -> 5, otra cosa -> null. */
    fun numero(s: String): Int? = s.toIntOrNull() ?: numeros[s]

    // ---------- Duraciones: "10 minutos", "1 hora y media", "media hora" ----------

    private val rxDur = Regex("""(\d+|[a-z]+)\s*(horas?|hs|h|minutos?|mins?|segundos?|segs?)\b""")
    private val rxMediaHora = Regex("""\bmedia hora\b""")
    private val rxYMedia = Regex("""\by media\b""")
    private val rxCuartoHora = Regex("""\bcuarto de hora\b""")

    /** Texto ya "plegado" (fold). Devuelve segundos o null si no hay duración. */
    fun duracionSeg(f: String): Int? {
        var total = 0
        var hayHoras = false
        for (m in rxDur.findAll(f)) {
            val n = numero(m.groupValues[1]) ?: continue
            val u = m.groupValues[2]
            if (u.startsWith("h")) {
                total += n * 3600
                hayHoras = true
            } else if (u.startsWith("m")) {
                total += n * 60
            } else {
                total += n
            }
        }
        if (rxMediaHora.containsMatchIn(f)) total += 1800
        else if (hayHoras && rxYMedia.containsMatchIn(f)) total += 1800
        if (rxCuartoHora.containsMatchIn(f)) total += 900
        return if (total > 0) total else null
    }

    fun duracionTexto(seg: Int): String {
        val h = seg / 3600
        val m = seg % 3600 / 60
        val s = seg % 60
        val p = ArrayList<String>()
        if (h > 0) p.add(if (h == 1) "1 hora" else "$h horas")
        if (m > 0) p.add(if (m == 1) "1 minuto" else "$m minutos")
        if (s > 0) p.add(if (s == 1) "1 segundo" else "$s segundos")
        return p.joinToString(" y ")
    }

    // ---------- Horas: "7:30", "7 y media", "8 menos cuarto", "7 de la tarde", "19 horas" ----------

    private val rxHora = Regex(
        """(\d{1,2})(?:[:.](\d{2}))?(?:\s*(?:horas|hs|h)\b)?(?:\s+y\s+(media|cuarto|\d{1,2}))?""" +
            """(?:\s+menos\s+(cuarto|\d{1,2}))?""" +
            """(?:\s*(am|pm|a\.\s?m\.?|p\.\s?m\.?)|\s+de la\s+(manana|tarde|noche|madrugada)|\s+del\s+(mediodia))?"""
    )

    /** Texto "plegado". Devuelve (hora 0-23, minutos) o null. */
    fun hora(f: String): Pair<Int, Int>? {
        val m = rxHora.find(f) ?: return null
        var h = m.groupValues[1].toInt()
        var min = m.groupValues[2].toIntOrNull() ?: 0
        val y = m.groupValues[3]
        if (y == "media") min = 30
        else if (y == "cuarto") min = 15
        else if (y.isNotEmpty()) min = y.toIntOrNull() ?: min
        val menos = m.groupValues[4]
        if (menos.isNotEmpty()) {
            val q = if (menos == "cuarto") 15 else (menos.toIntOrNull() ?: 0)
            min = 60 - q          // "8 menos cuarto" = 7:45
            h -= 1
            if (h < 0) h = 23
        }
        val ampm = m.groupValues[5]
        val per = m.groupValues[6]
        val pm = ampm.startsWith("p") || per == "tarde" || per == "noche"
        val am = ampm.startsWith("a") || per == "manana" || per == "madrugada"
        if (pm && h in 1..11) h += 12
        if (am && h == 12) h = 0
        if (per == "noche" && h == 12) h = 0
        if (h !in 0..23 || min !in 0..59) return null
        return h to min
    }

    // ---------- Fecha y hora actuales ----------

    private val dias = arrayOf("domingo", "lunes", "martes", "miércoles", "jueves", "viernes", "sábado")
    private val meses = arrayOf(
        "enero", "febrero", "marzo", "abril", "mayo", "junio",
        "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre"
    )

    /** Ej.: "jueves 29 de octubre de 2026". */
    fun fechaHoy(): String {
        val c = Calendar.getInstance()
        return "${dias[c.get(Calendar.DAY_OF_WEEK) - 1]} ${c.get(Calendar.DAY_OF_MONTH)} " +
            "de ${meses[c.get(Calendar.MONTH)]} de ${c.get(Calendar.YEAR)}"
    }

    fun hhmm(h: Int, m: Int): String = "%d:%02d".format(h, m)

    fun horaAhora(): String {
        val c = Calendar.getInstance()
        return hhmm(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }
}
