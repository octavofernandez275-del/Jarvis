package com.octavo.jarvis

import java.text.Normalizer

/**
 * Corrige lo que el reconocedor de voz del teléfono entiende mal cuando dices "Jarvis"
 * (por ejemplo "llaves", "yarvis", "charvis") y elige, entre las alternativas que devuelve, la que mejor suena a Jarvis.
 */
object CorreccionVoz {
    private const val NOMBRE = "Jarvis"

    // Cómo suele entenderse "Jarvis" en español rioplatense
    private val VARIANTES = setOf(
        "yarvis", "yarbis", "jarbis", "jarvi", "jarvys", "jarves", "jarvez", "jarviz", "jarvish", "charvis", "charbis", "garvis",
        "harvis", "darvis", "yarvi", "yarvez", "llarvis", "llarbis", "yavis", "javis", "jabis", "gervis", "chavis", "llavis",
        "yaris", "yarbi", "jarvis", "jarvis", "jervis", "gerbis", "yerbis", "llerbis", "yarves", "jarbi", "arvis", "harbis", "darbis"
    )
    // Palabras reales que suenan parecido: solo se corrigen al inicio de la frase y si lo que sigue parece una orden
    private val AMBIGUAS = setOf("llaves", "llave", "travis", "yaves", "yave", "chaves", "clave", "claves")
    private val SALUDOS = setOf("oye", "hey", "hola", "ok", "okey", "okay", "eh", "che", "dime", "buenas", "epa", "ey")
    // Si "llaves" va seguido de esto, es la palabra real ("llaves del auto", "llaves y billetera")
    private val SIGUE_REAL = setOf("de", "del", "y", "para", "en", "inglesas", "maestras", "con", "o", "por", "mias", "tuyas", "estan")

    private fun plano(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").filter { it.isLetter() }

    private fun distancia(a: String, b: String): Int {
        if (a == b) return 0
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
    }

    private fun suenaAJarvis(p: String): Boolean =
        p in VARIANTES || (p.length in 5..8 && p !in AMBIGUAS && p[0] in "jyllchgdh" && distancia(p, "jarvis") <= 1)

    /** Cambia la palabra mal entendida por "Jarvis" si está al principio de la frase (o justo después de "oye", "hola"…). */
    fun corregir(texto: String): String {
        val t = texto.trim()
        if (t.isEmpty()) return t
        val partes = t.split(Regex("\\s+")).toMutableList()
        var i = 0
        if (partes.size > 1 && plano(partes[0]) in SALUDOS) i = 1
        val p = plano(partes[i])
        if (p.isEmpty()) return t
        val siguiente = partes.getOrNull(i + 1)?.let { plano(it) }.orEmpty()
        val corregir = when {
            p == NOMBRE.lowercase() -> false
            suenaAJarvis(p) -> true
            p in AMBIGUAS -> i == 1 || siguiente.isEmpty() || siguiente !in SIGUE_REAL   // "oye llaves…", "llaves, abre…" o una sola palabra
            else -> false
        }
        if (!corregir) return t
        val signo = partes[i].takeLastWhile { !it.isLetter() }   // conserva la coma o el punto final
        partes[i] = NOMBRE + signo
        return partes.joinToString(" ")
    }

    /** De las alternativas del reconocedor, prefiere la que empieza diciendo Jarvis; si ninguna, la primera, ya corregida. */
    fun elegir(alternativas: List<String>): String {
        val corregidas = alternativas.map { corregir(it) }.filter { it.isNotBlank() }
        if (corregidas.isEmpty()) return ""
        return corregidas.firstOrNull { plano(it.split(Regex("\\s+")).firstOrNull().orEmpty()) == NOMBRE.lowercase() } ?: corregidas.first()
    }
}
