package com.octavo.jarvis

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class Tarea(
    val id: Long,
    var titulo: String,
    var estado: Int = Tareas.PENDIENTE,
    var resultado: String = "",
    val archivos: MutableList<String> = mutableListOf(),   // "nombre|uri"
    val bitacora: MutableList<String> = mutableListOf(),   // lo que fue haciendo Jarvis, paso a paso
    var sugerida: Boolean = false,                         // la propuso Jarvis (no se hace sola hasta que la apruebes)
    var aprobada: Boolean = false,                         // el usuario autorizó una descarga delicada
    val creada: Long = System.currentTimeMillis()
)

/** Lista de tareas guardada en un archivo de la app. Se puede usar desde cualquier hilo. */
object Tareas {
    const val PENDIENTE = 0
    const val EN_CURSO = 1
    const val HECHA = 2
    const val FALLO = 3
    const val ESPERA_OK = 4

    private val lista = ArrayList<Tarea>()
    private var cargado = false
    private var app: Context? = null
    private val main = Handler(Looper.getMainLooper())

    /** La pantalla de tareas se suscribe aquí para repintarse cuando algo cambia (se llama en el hilo principal). */
    @Volatile var alCambiar: (() -> Unit)? = null

    private fun cargar(ctx: Context) {
        if (cargado) return
        cargado = true
        app = ctx.applicationContext
        try {
            val f = File(ctx.filesDir, "tareas.json")
            if (!f.exists()) return
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                var estado = o.optInt("estado", PENDIENTE)
                if (estado == EN_CURSO) estado = PENDIENTE   // si la app se cerró a mitad de una tarea, vuelve a pendiente
                val t = Tarea(
                    o.getLong("id"), o.getString("titulo"), estado,
                    o.optString("resultado", ""),
                    sugerida = o.optBoolean("sugerida", false),
                    aprobada = o.optBoolean("aprobada", false),
                    creada = o.optLong("creada", 0L)
                )
                o.optJSONArray("archivos")?.let { a -> for (j in 0 until a.length()) t.archivos.add(a.getString(j)) }
                o.optJSONArray("bitacora")?.let { a -> for (j in 0 until a.length()) t.bitacora.add(a.getString(j)) }
                lista.add(t)
            }
        } catch (e: Exception) {
            lista.clear()
        }
    }

    private fun guardar() {
        val ctx = app ?: return
        try {
            val arr = JSONArray()
            for (t in lista) {
                arr.put(
                    JSONObject()
                        .put("id", t.id).put("titulo", t.titulo).put("estado", t.estado)
                        .put("resultado", t.resultado).put("sugerida", t.sugerida)
                        .put("aprobada", t.aprobada).put("creada", t.creada)
                        .put("archivos", JSONArray(t.archivos))
                        .put("bitacora", JSONArray(t.bitacora))
                )
            }
            val f = File(ctx.filesDir, "tareas.json")
            val tmp = File(ctx.filesDir, "tareas.json.tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(f)) { f.writeText(tmp.readText()); tmp.delete() }
        } catch (e: Exception) {
        }
    }

    private fun avisar() {
        main.post { alCambiar?.invoke() }
    }

    @Synchronized fun todas(ctx: Context): List<Tarea> {
        cargar(ctx)
        return lista.toList()
    }

    /** Las que siguen abiertas, de la más vieja a la más nueva: así las numera la voz ("haz la tarea 2"). */
    @Synchronized fun abiertas(ctx: Context): List<Tarea> {
        cargar(ctx)
        return lista.filter { it.estado != HECHA }.sortedBy { it.creada }
    }

    @Synchronized fun buscar(ctx: Context, id: Long): Tarea? {
        cargar(ctx)
        return lista.firstOrNull { it.id == id }
    }

    @Synchronized fun agregar(ctx: Context, titulo: String, sugerida: Boolean = false): Tarea {
        cargar(ctx)
        val id = maxOf(System.currentTimeMillis(), (lista.maxOfOrNull { it.id } ?: 0L) + 1)
        val t = Tarea(id, titulo.trim().take(200), sugerida = sugerida)
        lista.add(t)
        guardar()
        avisar()
        return t
    }

    /** Cambia una tarea y guarda. */
    @Synchronized fun cambiar(ctx: Context, id: Long, cambio: (Tarea) -> Unit) {
        cargar(ctx)
        val t = lista.firstOrNull { it.id == id } ?: return
        cambio(t)
        guardar()
        avisar()
    }

    @Synchronized fun borrar(ctx: Context, id: Long) {
        cargar(ctx)
        if (lista.removeAll { it.id == id }) {
            guardar()
            avisar()
        }
    }

    /** Anota un paso en la bitácora de la tarea. */
    fun log(ctx: Context, id: Long, linea: String) {
        val hora = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        cambiar(ctx, id) {
            it.bitacora.add("$hora $linea")
            while (it.bitacora.size > 40) it.bitacora.removeAt(0)
        }
    }
}
