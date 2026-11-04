package com.octavo.jarvis

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.regex.Pattern

private data class Mission(
    val id: String,
    val title: String,
    val description: String,
    var status: String = "PENDIENTE",
    var progress: Int = 0,
    var result: String = "",
    var updated: Long = System.currentTimeMillis(),
    var downloadId: Long = -1L
)

/** Persistencia local independiente del chat y del motor conversacional. */
private object MissionStore {
    private const val PREF = "jarvis_autopilot"
    private const val KEY = "missions"

    @Synchronized fun all(ctx: Context): MutableList<Mission> {
        val raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        val arr = JSONArray(raw)
        val out = mutableListOf<Mission>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(Mission(o.optString("id"), o.optString("title"), o.optString("description"),
                o.optString("status", "PENDIENTE"), o.optInt("progress"), o.optString("result"), o.optLong("updated"), o.optLong("downloadId", -1L)))
        }
        return out
    }

    @Synchronized fun save(ctx: Context, missions: List<Mission>) {
        val arr = JSONArray()
        missions.forEach { m ->
            arr.put(JSONObject().put("id", m.id).put("title", m.title).put("description", m.description)
                .put("status", m.status).put("progress", m.progress).put("result", m.result).put("updated", m.updated).put("downloadId", m.downloadId))
        }
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }
}

/** Búsqueda web básica: obtiene resultados públicos y guarda un informe local con las fuentes. */
private object WebResearcher {
    fun search(query: String): String {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val connection = URL("https://html.duckduckgo.com/html/?q=$encoded").openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 12000
        connection.readTimeout = 15000
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
        try {
            val html = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val anchors = Pattern.compile("<a[^>]*class=\\\"result__a\\\"[^>]*href=\\\"([^\\\"]+)\\\"[^>]*>(.*?)</a>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
            val matcher = anchors.matcher(html)
            val output = StringBuilder("INVESTIGACIÓN JARVIS AUTOPILOT\nConsulta: $query\nFecha: ${Date()}\n\n")
            var count = 0
            while (matcher.find() && count < 8) {
                val href = decodeHtml(matcher.group(1) ?: "")
                val title = cleanHtml(matcher.group(2) ?: "")
                if (title.isBlank()) continue
                count++
                output.append("$count. $title\n$href\n\n")
            }
            if (count == 0) output.append("El buscador no devolvió resultados legibles. Abrí la búsqueda en el navegador para continuar.\n")
            return output.toString()
        } finally { connection.disconnect() }
    }

    private fun cleanHtml(s: String) = decodeHtml(s.replace(Regex("<[^>]+>"), " ")).replace(Regex("\\s+"), " ").trim()
    private fun decodeHtml(s: String) = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#x27;", "'").replace("&lt;", "<").replace("&gt;", ">")
}

private object MissionRunner {
    fun run(ctx: Context, mission: Mission, update: (Mission) -> Unit) {
        mission.status = "ANALIZANDO"; mission.progress = 10; mission.updated = System.currentTimeMillis(); update(mission)
        val text = mission.description.trim()
        val url = Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE).find(text)?.value
        if (url != null && (text.contains("descarg", true) || text.contains("baj", true) || text.contains("guardar", true))) {
            mission.status = "DESCARGANDO"; mission.progress = 35; update(mission)
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle("JARVIS: ${mission.title}")
                .setDescription("Descarga solicitada desde AUTOPILOT")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(true).setAllowedOverRoaming(false)
                .setDestinationInExternalFilesDir(ctx, Environment.DIRECTORY_DOWNLOADS, Uri.parse(url).lastPathSegment ?: "jarvis_download")
            val manager = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val id = manager.enqueue(request)
            mission.downloadId = id
            mission.progress = 20
            mission.result = "Descarga enviada al gestor de Android. ID: $id. El sistema actualizará esta misión cuando termine."
            mission.status = "DESCARGA EN COLA"; mission.updated = System.currentTimeMillis(); update(mission)
            return
        }

        mission.status = "INVESTIGANDO"; mission.progress = 35; mission.updated = System.currentTimeMillis(); update(mission)
        var searchOk = true
        val report = try { WebResearcher.search(text) } catch (e: Exception) {
            searchOk = false
            "No se pudo completar la búsqueda automática: ${e.message}\n\nPodés abrir la búsqueda manualmente desde el botón Buscar en Internet."
        }
        mission.progress = 80; mission.status = "PREPARANDO INFORME"; update(mission)
        val folder = File(ctx.getExternalFilesDir(null), "Autopilot/Informes")
        folder.mkdirs()
        val file = File(folder, "informe_${mission.id}.txt")
        file.writeText(report, Charsets.UTF_8)
        mission.result = "Informe guardado en: ${file.absolutePath}\n\n" + report.take(1800)
        mission.status = if (searchOk) "COMPLETADA" else "REVISAR"
        mission.progress = if (searchOk) 100 else 80
        mission.updated = System.currentTimeMillis(); update(mission)
    }
}

/** Centro de mando visual Iron Man. */
class AutopilotActivity : Activity() {
    private val missions = mutableListOf<Mission>()
    private val executor = Hilos.unico("jarvis-misiones")
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var count: TextView
    private val cyan = Color.rgb(40, 205, 255)
    private val bg = Color.rgb(5, 12, 23)
    private val panel = Color.rgb(12, 27, 43)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg; window.navigationBarColor = bg
        missions.addAll(MissionStore.all(this))
        buildUi(); render()
    }

    override fun onResume() { super.onResume(); missions.clear(); missions.addAll(MissionStore.all(this)); render() }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg); setPadding(dp(16), dp(12), dp(16), dp(12)) }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val titleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleBox.addView(label("J.A.R.V.I.S.", 13f, cyan, true))
        titleBox.addView(label("AUTOPILOT", 27f, Color.WHITE, true))
        titleBox.addView(label("CENTRO DE OPERACIONES", 10f, 0xFF8AAFC4.toInt(), true))
        header.addView(titleBox, LinearLayout.LayoutParams(0, -2, 1f))
        val clock = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        header.addView(label("$clock  ·  ● EN LÍNEA", 11f, 0xFF53E6B1.toInt(), true))
        root.addView(header)

        val reactor = TextView(this).apply {
            text = "◉"; textSize = 64f; gravity = Gravity.CENTER; setTextColor(cyan)
            setPadding(0, dp(8), 0, dp(2))
        }
        root.addView(reactor, LinearLayout.LayoutParams(-1, dp(92)))
        status = label("SISTEMA PREPARADO · ESPERANDO MISIÓN", 11f, 0xFF8AAFC4.toInt(), true).apply { gravity = Gravity.CENTER }
        root.addView(status)

        val stats = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(14), 0, dp(14)) }
        count = label("0 PENDIENTES", 12f, Color.WHITE, true)
        stats.addView(statCard("MISIONES", count), LinearLayout.LayoutParams(0, dp(58), 1f).apply { marginEnd = dp(6) })
        stats.addView(statCard("MODO", label("AUTÓNOMO", 12f, cyan, true)), LinearLayout.LayoutParams(0, dp(58), 1f).apply { marginStart = dp(6) })
        root.addView(stats)

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(actionButton("+ NUEVA MISIÓN") { newMission() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(5) })
        actions.addView(actionButton("BUSCAR WEB") { openWebSearch() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(5) })
        root.addView(actions)
        root.addView(label("MISIONES Y ACTIVIDAD", 12f, cyan, true).apply { setPadding(0, dp(18), 0, dp(8)) })
        val scroll = ScrollView(this)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(actionButton("VOLVER AL CHAT") { finish() }, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(10) })
        setContentView(root)
    }

    private fun statCard(heading: String, value: TextView): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setBackgroundColor(panel); setPadding(dp(8), dp(5), dp(8), dp(5))
        addView(label(heading, 9f, 0xFF8AAFC4.toInt(), true)); addView(value)
    }

    private fun label(text: String, size: Float, color: Int, bold: Boolean) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun actionButton(text: String, click: () -> Unit) = Button(this).apply {
        this.text = text; textSize = 11f; setTextColor(Color.WHITE); setBackgroundColor(Color.rgb(13, 68, 96)); setOnClickListener { click() }
    }

    private fun newMission() {
        val input = EditText(this).apply { hint = "Ej.: Investiga herramientas gratuitas para..."; setTextColor(Color.WHITE); setHintTextColor(0xFF7894A8.toInt()); minLines = 2 }
        AlertDialog.Builder(this).setTitle("NUEVA MISIÓN").setMessage("Describí el objetivo. JARVIS lo investigará y preparará un informe.")
            .setView(input).setNegativeButton("Cancelar", null).setPositiveButton("CREAR Y EJECUTAR") { _, _ ->
                val desc = input.text.toString().trim()
                if (desc.isNotEmpty()) {
                    val m = Mission(UUID.randomUUID().toString().take(8), desc.take(70), desc)
                    missions.add(0, m); persist(); render(); execute(m)
                }
            }.show()
    }

    private fun execute(m: Mission) {
        if (m.status == "EN EJECUCIÓN") return
        m.status = "EN EJECUCIÓN"; persist(); render()
        status.text = "ANALIZANDO MISIÓN: ${m.title}"
        executor.execute {
            try { MissionRunner.run(applicationContext, m) { changed -> runOnUiThread { persist(); render(); status.text = "${changed.status}: ${changed.title}" } } }
            catch (e: Exception) { m.status = "ERROR"; m.result = e.message ?: "Error desconocido"; runOnUiThread { persist(); render(); status.text = "MISIÓN INTERRUMPIDA" } }
        }
    }

    private fun render() {
        list.removeAllViews()
        val pending = missions.count { it.status != "COMPLETADA" }
        count.text = "$pending PENDIENTES"
        if (missions.isEmpty()) list.addView(label("No hay misiones. Creá una para comenzar.", 14f, 0xFF8AAFC4.toInt(), false).apply { setPadding(dp(8), dp(20), dp(8), dp(20)) })
        missions.forEach { m ->
            val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(panel); setPadding(dp(12), dp(10), dp(12), dp(10)) }
            card.addView(label(m.title, 15f, Color.WHITE, true))
            card.addView(label("${m.status}  ·  ${m.progress}%", 11f, cyan, true).apply { setPadding(0, dp(5), 0, dp(4)) })
            val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progress = m.progress }
            card.addView(bar, LinearLayout.LayoutParams(-1, dp(5)))
            if (m.result.isNotBlank()) card.addView(label(m.result.take(700), 11f, 0xFFB4C9D7.toInt(), false).apply { setPadding(0, dp(7), 0, dp(5)) })
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val canView = m.status == "COMPLETADA" || m.status == "REVISAR" || m.status == "DESCARGA EN COLA" || m.status == "ERROR DE DESCARGA"
            row.addView(actionButton(if (canView) "VER RESULTADO" else "EJECUTAR") { if (canView) showResult(m) else execute(m) }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(5) })
            row.addView(actionButton("ELIMINAR") { missions.remove(m); persist(); render() }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(5) })
            card.addView(row)
            val lp = LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = dp(10); list.addView(card, lp)
        }
    }

    private fun showResult(m: Mission) = AlertDialog.Builder(this).setTitle(m.title).setMessage(m.result).setPositiveButton("Cerrar", null).show()

    private fun openWebSearch() {
        val input = EditText(this).apply { hint = "¿Qué querés investigar?" }
        AlertDialog.Builder(this).setTitle("INVESTIGACIÓN WEB").setView(input).setNegativeButton("Cancelar", null)
            .setPositiveButton("BUSCAR") { _, _ ->
                val q = input.text.toString().trim()
                if (q.isNotEmpty()) try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://duckduckgo.com/?q=" + Uri.encode(q)))) } catch (_: Exception) { }
            }.show()
    }

    private fun persist() = MissionStore.save(this, missions)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    override fun onDestroy() { super.onDestroy(); executor.shutdown() }
}


/** Recibe la confirmación real de Android cuando termina una descarga. */
class AutopilotDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (id < 0L) return
        val missions = MissionStore.all(context)
        val mission = missions.firstOrNull { it.downloadId == id } ?: return
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val cursor = manager.query(DownloadManager.Query().setFilterById(id))
        cursor?.use {
            if (!it.moveToFirst()) return
            val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    mission.status = "COMPLETADA"; mission.progress = 100
                    val uriCol = it.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                    val local = if (uriCol >= 0) it.getString(uriCol) else null
                    mission.result = "Descarga completada correctamente.\nArchivo: ${local ?: "guardado en la carpeta de descargas de JARVIS"}"
                }
                DownloadManager.STATUS_FAILED -> {
                    mission.status = "ERROR DE DESCARGA"
                    val reason = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                    mission.result = "Android no pudo completar la descarga. Código: $reason"
                }
                else -> return
            }
            mission.updated = System.currentTimeMillis()
            MissionStore.save(context, missions)
        }
    }
}
