package com.octavo.jarvis

import android.content.Context
import java.io.File

/**
 * Memoria de Jarvis: cosas que el usuario le pide recordar ("recuerda que mi gato se llama Tomás").
 * Se guardan en un archivo de texto privado de la app (una por línea), sin IA ni base de datos.
 * Límites pequeños a propósito: el contexto del modelo es de solo 2048 tokens.
 */
object Memory {
    private const val ARCHIVO = "memoria.txt"
    private const val MAX_HECHOS = 30
    private const val MAX_LARGO = 200
    private const val MAX_PROMPT = 700   // caracteres que se pasan al modelo en cada pregunta

    private val vacias = setOf("que", "los", "las", "una", "uno", "con", "del", "por", "para", "mis", "tus", "como")
    private var hechos: MutableList<String>? = null   // se carga una sola vez

    private fun cargar(ctx: Context): MutableList<String> {
        hechos?.let { return it }
        val f = File(ctx.filesDir, ARCHIVO)
        val l = if (f.exists()) {
            f.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        } else {
            mutableListOf()
        }
        hechos = l
        return l
    }

    private fun guardar(ctx: Context) {
        // Se escribe a un archivo temporal y se renombra, para no dejar la memoria a medias
        val f = File(ctx.filesDir, ARCHIVO)
        val tmp = File(ctx.filesDir, "$ARCHIVO.tmp")
        tmp.writeText(hechos.orEmpty().joinToString("\n"))
        if (!tmp.renameTo(f)) { f.writeText(tmp.readText()); tmp.delete() }
    }

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

    /** Datos que coinciden con la consulta: primero por frase, si no por palabras sueltas. */
    @Synchronized fun buscar(ctx: Context, consulta: String): List<String> {
        val q = Parsers.fold(consulta).trim()
        if (q.isEmpty()) return emptyList()
        val l = cargar(ctx)
        val exactos = l.filter { Parsers.fold(it).contains(q) }
        if (exactos.isNotEmpty()) return exactos
        val palabras = q.split(Regex("""[^\p{L}\p{N}]+""")).filter { it.length >= 3 && it !in vacias }
        if (palabras.isEmpty()) return emptyList()
        val minimo = (palabras.size + 1) / 2
        return l.filter { h ->
            val fh = Parsers.fold(h)
            palabras.count { fh.contains(it) } >= minimo
        }
    }

    @Synchronized fun borrar(ctx: Context, hecho: String) {
        if (cargar(ctx).remove(hecho)) guardar(ctx)
    }

    @Synchronized fun borrarTodo(ctx: Context) {
        cargar(ctx).clear()
        guardar(ctx)
    }

    /** Texto para el prompt del modelo: los datos más nuevos que quepan en MAX_PROMPT. */
    @Synchronized fun bloqueParaPrompt(ctx: Context): String {
        val l = cargar(ctx)
        if (l.isEmpty()) return ""
        val elegidos = ArrayList<String>()
        var usado = 0
        for (h in l.asReversed()) {
            if (usado + h.length + 3 > MAX_PROMPT) break
            elegidos.add(h)
            usado += h.length + 3
        }
        elegidos.reverse()
        return "Datos que el usuario te pidió recordar (úsalos solo si vienen al caso):\n" +
            elegidos.joinToString("\n") { "- $it" }
    }
}
