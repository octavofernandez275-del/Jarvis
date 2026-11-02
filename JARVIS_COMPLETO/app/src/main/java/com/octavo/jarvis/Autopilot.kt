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
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.InetAddress
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

internal data class Mission(
    val id: String,
    val title: String,
    val description: String,
    var status: String = "PENDIENTE",
    var progress: Int = 0,
    var result: String = "",
    var updated: Long = System.currentTimeMillis(),
    var downloadId: Long = -1L,
    var steps: MutableList<String> = mutableListOf(),
    var stepIndex: Int = 0
)

/** Persistencia local independiente del chat y del motor conversacional. */
internal object MissionStore {
    private const val PREF = "jarvis_autopilot"
    private const val KEY = "missions"

    @Synchronized fun all(ctx: Context): MutableList<Mission> {
        val raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        val arr = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
        val out = mutableListOf<Mission>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(Mission(o.optString("id"), o.optString("title"), o.optString("description"),
                o.optString("status", "PENDIENTE"), o.optInt("progress"), o.optString("result"), o.optLong("updated"), o.optLong("downloadId", -1L),
                jsonSteps(o.optJSONArray("steps")), o.optInt("stepIndex", 0)))
        }
        return out
    }

    private fun jsonSteps(array: JSONArray?): MutableList<String> {
        val out = mutableListOf<String>()
        if (array != null) for (i in 0 until array.length()) array.optString(i).takeIf { it.isNotBlank() }?.let(out::add)
        return out
    }

    @Synchronized fun save(ctx: Context, missions: List<Mission>) {
        val arr = JSONArray()
        missions.take(100).forEach { m ->
            arr.put(JSONObject().put("id", m.id).put("title", m.title).put("description", m.description)
                .put("status", m.status).put("progress", m.progress).put("result", m.result).put("updated", m.updated).put("downloadId", m.downloadId)
                .put("steps", JSONArray(m.steps)).put("stepIndex", m.stepIndex))
        }
        // commit evita que el Worker arranque antes de que la misión nueva esté realmente persistida.
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).commit()
    }

    @Synchronized fun upsert(ctx: Context, mission: Mission) {
        val current = all(ctx)
        val index = current.indexOfFirst { it.id == mission.id }
        val safeCopy = mission.copy(steps = mission.steps.toMutableList())
        if (index >= 0) {
            val stored = current[index]
            // Un callback tardío del worker nunca debe sobrescribir una pausa/cancelación del usuario.
            if (stored.status == "PAUSADA" || stored.status == "CANCELADA") {
                safeCopy.status = stored.status
                safeCopy.result = stored.result
            }
            current[index] = safeCopy
        } else current.add(0, safeCopy)
        save(ctx, current)
    }
}

/** Planificador híbrido: reutiliza Qwen solo si el servidor local ya está activo; nunca arranca otro modelo. */
private object MissionPlanner {
    fun plan(context: Context, description: String): MutableList<String> {
        val local = planWithExistingModel(context, description)
        if (local.isNotEmpty()) {
            ExperienceMemory.add(context, "PLAN", "Plan generado con modelo local", "${local.size} pasos; no se inició ningún proceso de modelo nuevo")
            return local
        }
        val text = description.trim()
        val steps = mutableListOf("ANALIZAR OBJETIVO, ALCANCE Y RESTRICCIONES")
        val hasUrl = Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE).containsMatchIn(text)
        val asksDownload = listOf("descarg", "baj", "guardar archivo").any { text.contains(it, true) }
        when {
            hasUrl && asksDownload -> {
                steps.add("VALIDAR ENLACE Y SEGURIDAD")
                steps.add("DESCARGAR CON GESTOR ANDROID")
                steps.add("VERIFICAR RESULTADO DE DESCARGA")
            }
            else -> {
                steps.add("INVESTIGAR FUENTES PÚBLICAS")
                steps.add("FILTRAR DUPLICADOS Y ENLACES NO VÁLIDOS")
                steps.add("GENERAR Y GUARDAR INFORME")
            }
        }
        return steps
    }

    private fun planWithExistingModel(context: Context, description: String): MutableList<String> {
        return try {
            if (!Engine.modelFile(context).let { it.exists() && it.length() > 100_000_000L }) return mutableListOf()
            val health = (java.net.URL("http://127.0.0.1:${Engine.PORT}/health").openConnection() as HttpURLConnection).apply { connectTimeout = 500; readTimeout = 500 }
            val ready = try { health.responseCode == 200 } finally { health.disconnect() }
            if (!ready) return mutableListOf()
            val response = StringBuilder()
            Engine.chat(listOf(
                "system" to "Sos el planificador de JARVIS. Dividí el objetivo en 3 a 8 pasos concretos, ordenados y verificables. No propongas acciones ilegales, evasión de permisos, compras, compartir datos ni ejecutar archivos. Respondé SOLO un array JSON de strings en español.",
                "user" to description.take(3000)
            )) { token -> if (response.length < 5000) response.append(token) }
            val raw = response.toString().trim()
            val start = raw.indexOf('['); val end = raw.lastIndexOf(']')
            if (start < 0 || end <= start) return mutableListOf()
            val array = JSONArray(raw.substring(start, end + 1))
            val steps = mutableListOf<String>()
            for (i in 0 until minOf(array.length(), 8)) {
                val step = array.optString(i).trim().replace(Regex("\\s+"), " ").take(140)
                if (step.isNotBlank() && steps.none { it.equals(step, true) }) steps.add(step)
            }
            steps.takeIf { it.size >= 2 } ?: mutableListOf()
        } catch (_: Exception) { mutableListOf() }
    }
}

/** Control cooperativo: pausa/cancelación se aplican entre pasos de misión. */
internal object MissionControl {
    private val paused = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val cancelled = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    fun pause(id: String) { paused.add(id) }
    fun resume(id: String) { paused.remove(id); cancelled.remove(id) }
    fun cancel(id: String) { cancelled.add(id); paused.remove(id) }
    fun state(id: String): String = when { id in cancelled -> "CANCELADA"; id in paused -> "PAUSADA"; else -> "ACTIVA" }
    fun clear(id: String) { paused.remove(id); cancelled.remove(id) }
}

/** Investigación pública con límites de tamaño, validación HTTP(S) y deduplicación. */
private object WebResearcher {
    fun search(query: String): String {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val connection = URL("https://html.duckduckgo.com/html/?q=$encoded").openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 12000
        connection.readTimeout = 15000
        connection.setRequestProperty("User-Agent", "JARVIS-Android/2.0")
        connection.setRequestProperty("Accept", "text/html")
        try {
            // Límite real de lectura: readText().take() limitaría el texto final, no la RAM usada.
            val html = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var remaining = 1_000_000
                while (remaining > 0) {
                    val read = input.read(buffer, 0, minOf(buffer.size, remaining))
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    remaining -= read
                }
                output.toString(Charsets.UTF_8.name())
            }
            val anchors = Pattern.compile("<a[^>]*class=\\\"result__a\\\"[^>]*href=\\\"([^\\\"]+)\\\"[^>]*>(.*?)</a>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
            val matcher = anchors.matcher(html)
            val output = StringBuilder("INVESTIGACIÓN JARVIS AUTOPILOT 2.0\nConsulta: $query\nFecha: ${Date()}\n\n")
            val seen = HashSet<String>()
            val selected = mutableListOf<Pair<String, String>>()
            var count = 0
            while (matcher.find() && count < 8) {
                val href = unwrapSearchRedirect(decodeHtml(matcher.group(1) ?: ""))
                val title = cleanHtml(matcher.group(2) ?: "")
                val safe = safePublicUrl(href) ?: continue
                val key = safe.substringBefore('#').trimEnd('/').lowercase(Locale.ROOT)
                if (title.length < 4 || !seen.add(key)) continue
                count++
                selected.add(title to safe)
                output.append("$count. $title\n$safe\n\n")
            }
            if (count == 0) output.append("No se encontraron resultados públicos válidos o legibles. Podés abrir la búsqueda en el navegador para continuar.\n")
            else {
                output.append("\nVISTAS PREVIAS DE CONTENIDO (máximo 3 páginas):\n")
                selected.take(3).forEachIndexed { index, item ->
                    val preview = pagePreview(item.second)
                    output.append("${index + 1}. ${item.first}\n")
                    output.append(preview ?: "No se pudo obtener una vista previa segura; abrí la fuente para revisarla.")
                    output.append("\n\n")
                }
            }
            output.append("\nControl de calidad: $count fuentes únicas con URL HTTP(S) pública. Se filtraron duplicados y destinos privados/locales. Las vistas previas son parciales; no certifican reputación, exactitud ni ausencia de malware.")
            return output.toString()
        } finally { connection.disconnect() }
    }

    private fun unwrapSearchRedirect(raw: String): String {
        return try {
            val uri = java.net.URI(raw)
            if (!uri.host.orEmpty().contains("duckduckgo.com", ignoreCase = true)) return raw
            val query = uri.rawQuery ?: return raw
            val encodedTarget = query.split('&').firstOrNull { it.startsWith("uddg=") }?.substringAfter('=')
                ?: query.split('&').firstOrNull { it.startsWith("url=") }?.substringAfter('=')
                ?: return raw
            URLDecoder.decode(encodedTarget, "UTF-8")
        } catch (_: Exception) { raw }
    }

    fun safePublicUrl(raw: String): String? {
        return try {
            if (raw.length !in 8..2048) return null
            val uri = java.net.URI(raw)
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            val host = uri.host?.lowercase(Locale.ROOT) ?: return null
            if (scheme != "https" && scheme != "http") return null
            if (uri.rawUserInfo != null || host == "localhost" || host.endsWith(".local") || host.endsWith(".internal")) return null
            // Bloquea direcciones privadas/locales también cuando el host es IPv4 o IPv6.
            val addresses = InetAddress.getAllByName(host)
            if (addresses.isEmpty() || addresses.any { address ->
                    val bytes = address.address.map { it.toInt() and 0xFF }
                    val reservedV4 = bytes.size == 4 && (
                        bytes[0] == 0 || bytes[0] == 10 || bytes[0] == 127 ||
                            (bytes[0] == 169 && bytes[1] == 254) ||
                            (bytes[0] == 172 && bytes[1] in 16..31) ||
                            (bytes[0] == 192 && bytes[1] == 168) ||
                            (bytes[0] == 100 && bytes[1] in 64..127) ||
                            (bytes[0] == 198 && bytes[1] in 18..19) || bytes[0] >= 224
                        )
                    val reservedV6 = bytes.size == 16 && (
                        bytes[0] == 0xFC || bytes[0] == 0xFD ||
                            (bytes[0] == 0xFE && (bytes[1] and 0xC0) == 0x80)
                        )
                    address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
                        address.isSiteLocalAddress || address.isMulticastAddress || reservedV4 || reservedV6
                }) return null
            uri.toASCIIString()
        } catch (_: Exception) { null }
    }

    private fun pagePreview(rawUrl: String): String? {
        val safe = safePublicUrl(rawUrl) ?: return null
        val connection = try { URL(safe).openConnection() as HttpURLConnection } catch (_: Exception) { return null }
        return try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 3500
            connection.readTimeout = 4500
            connection.setRequestProperty("User-Agent", "JARVIS-Android/2.0")
            connection.setRequestProperty("Accept", "text/html")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            if (!connection.contentType.orEmpty().lowercase(Locale.ROOT).contains("text/html")) return null
            val html = connection.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                var remaining = 180_000
                while (remaining > 0) {
                    val read = input.read(buffer, 0, minOf(buffer.size, remaining))
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    remaining -= read
                }
                out.toString(Charsets.UTF_8.name())
            }
            val title = Regex("(?is)<title[^>]*>(.*?)</title>").find(html)?.groupValues?.getOrNull(1)?.let(::cleanHtml)
            val descriptionPatterns = listOf(
                Regex("(?is)<meta[^>]+name=[\"']description[\"'][^>]+content=[\"']([^\"']+)[\"']"),
                Regex("(?is)<meta[^>]+content=[\"']([^\"']+)[\"'][^>]+name=[\"']description[\"']"),
                Regex("(?is)<meta[^>]+property=[\"']og:description[\"'][^>]+content=[\"']([^\"']+)[\"']")
            )
            val description = descriptionPatterns.firstNotNullOfOrNull { it.find(html)?.groupValues?.getOrNull(1) }?.let(::cleanHtml)
            listOfNotNull(title?.takeIf { it.isNotBlank() }?.let { "Título: $it" }, description?.takeIf { it.length >= 35 }?.let { "Descripción: ${it.take(420)}" })
                .joinToString("\n").ifBlank { "La página no expuso metadatos descriptivos legibles." }
        } catch (_: Exception) { null } finally { connection.disconnect() }
    }

    private fun cleanHtml(s: String) = decodeHtml(s.replace(Regex("<[^>]+>"), " ")).replace(Regex("\\s+"), " ").trim()
    private fun decodeHtml(s: String) = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#x27;", "'").replace("&#39;", "'").replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
}

internal object MissionRunner {
    fun run(ctx: Context, mission: Mission, update: (Mission) -> Unit) {
        fun checkpoint(): Boolean {
            when {
                MissionControl.state(mission.id) == "CANCELADA" || mission.status == "CANCELADA" -> { mission.status = "CANCELADA"; mission.result = "Misión cancelada por el usuario."; mission.updated = System.currentTimeMillis(); update(mission); return false }
                MissionControl.state(mission.id) == "PAUSADA" || mission.status == "PAUSADA" -> { mission.status = "PAUSADA"; mission.result = "Progreso guardado. Podés reanudar esta misión cuando quieras."; mission.updated = System.currentTimeMillis(); update(mission); return false }
                else -> Unit
            }
            return true
        }
        if (!checkpoint()) return
        if (mission.steps.isEmpty()) mission.steps = MissionPlanner.plan(ctx, mission.description)
        if (!checkpoint()) return
        mission.status = "ANALIZANDO"; mission.progress = 5; mission.updated = System.currentTimeMillis(); update(mission)
        val text = mission.description.trim()
        val url = Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE).find(text)?.value?.trimEnd('.', ',', ')', ']')
        val isDownload = url != null && listOf("descarg", "baj", "guardar archivo").any { text.contains(it, true) }

        if (mission.stepIndex <= 0) {
            mission.stepIndex = 1; mission.progress = 10; mission.status = "PLANIFICADA"; update(mission)
        }
        if (!checkpoint()) return
        if (isDownload) {
            if (mission.stepIndex <= 1) {
                val safe = WebResearcher.safePublicUrl(url!!) 
                if (safe == null || !safe.startsWith("https://", true)) {
                    mission.status = "REVISAR"; mission.result = "Descarga bloqueada: se requiere un enlace HTTPS público y válido. No se ejecutó ninguna acción."; mission.progress = 25; update(mission); return
                }
                mission.stepIndex = 2; mission.progress = 25; mission.status = "ENLACE VALIDADO"; update(mission)
            }
            if (!checkpoint()) return
            if (mission.stepIndex <= 2) {
                val safe = WebResearcher.safePublicUrl(url!!) ?: run { mission.status = "REVISAR"; mission.result = "El enlace dejó de ser válido."; update(mission); return }
                val filename = (Uri.parse(safe).lastPathSegment ?: "jarvis_download")
                    .replace(Regex("[^A-Za-z0-9._-]"), "_").take(100).takeIf { it.isNotBlank() && it != "." && it != ".." } ?: "jarvis_download"
                val request = DownloadManager.Request(Uri.parse(safe))
                    .setTitle("JARVIS: ${mission.title}").setDescription("Descarga solicitada desde AUTOPILOT")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setAllowedOverMetered(true).setAllowedOverRoaming(false)
                    .setDestinationInExternalFilesDir(ctx, Environment.DIRECTORY_DOWNLOADS, filename)
                val manager = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                mission.downloadId = manager.enqueue(request)
                mission.stepIndex = 3; mission.progress = 40
                mission.result = "Descarga enviada al gestor de Android. ID: ${mission.downloadId}. Android verificará el estado al finalizar."
                mission.status = "DESCARGA EN COLA"; mission.updated = System.currentTimeMillis(); update(mission)
            }
            return
        }

        if (mission.stepIndex <= 1) { mission.stepIndex = 2; mission.progress = 15; mission.status = "INVESTIGANDO"; update(mission) }
        if (!checkpoint()) return
        var searchOk = true
        val report = try { WebResearcher.search(text) } catch (e: Exception) {
            searchOk = false
            "No se pudo completar la búsqueda automática: ${e.message}\n\nPodés abrir la búsqueda manualmente desde el botón Buscar en Internet."
        }
        val sourceCount = Regex("Control de calidad: (\\d+) fuentes").find(report)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (sourceCount == 0) searchOk = false
        if (!checkpoint()) return
        mission.stepIndex = 3; mission.progress = 80; mission.status = "PREPARANDO INFORME"; update(mission)
        val storageRoot = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val folder = File(storageRoot, "Autopilot/Informes")
        if (!folder.exists() && !folder.mkdirs()) throw IllegalStateException("No se pudo crear la carpeta de informes")
        val file = File(folder, "informe_${mission.id}.txt")
        file.writeText(report, Charsets.UTF_8)
        mission.stepIndex = mission.steps.size; mission.result = "Informe guardado en: ${file.absolutePath}\n\n" + report.take(1800)
        mission.status = if (searchOk) "COMPLETADA" else "REVISAR"
        mission.progress = if (searchOk) 100 else 80
        mission.updated = System.currentTimeMillis(); MissionControl.clear(mission.id)
        ExperienceMemory.add(ctx, if (searchOk) "COMPLETADA" else "REVISAR", mission.title, mission.result.take(900))
        update(mission)
    }
}

/** Ejecutor durable: WorkManager conserva la cola y reintenta tras interrupciones del proceso. */
internal object MissionExecution {
    private val active = ConcurrentHashMap.newKeySet<String>()
    fun isActive(context: Context, id: String): Boolean {
        if (id in active) return true
        return try {
            androidx.work.WorkManager.getInstance(context).getWorkInfosForUniqueWork("jarvis_mission_$id").get()
                .any { !it.state.isFinished }
        } catch (_: Exception) { false }
    }
    fun cancel(context: Context, id: String) {
        MissionControl.cancel(id)
        androidx.work.WorkManager.getInstance(context).cancelUniqueWork("jarvis_mission_$id")
        val mission = MissionStore.all(context).firstOrNull { it.id == id }
        if (mission != null && mission.downloadId >= 0L) {
            try {
                val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                manager.remove(mission.downloadId)
            } catch (_: Exception) { /* El estado cancelado se conserva aunque Android ya haya terminado la descarga. */ }
        }
    }
    fun submit(context: Context, mission: Mission) {
        if (!active.add(mission.id)) return
        try {
            val request = androidx.work.OneTimeWorkRequestBuilder<MissionWorker>()
                .setInputData(androidx.work.workDataOf(MissionWorker.KEY_ID to mission.id))
                .setConstraints(androidx.work.Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build())
                .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            // REPLACE permite reanudar una misión pausada aunque el Worker anterior aún esté cerrando.
            androidx.work.WorkManager.getInstance(context).enqueueUniqueWork(
                "jarvis_mission_${mission.id}", androidx.work.ExistingWorkPolicy.REPLACE, request
            )
        } finally {
            // WorkManager conserva la cola; este conjunto solo evita dobles encolados durante esta llamada.
            active.remove(mission.id)
        }
    }
}

/** Centro de mando visual Iron Man. */
class AutopilotActivity : Activity() {
    private val missions = mutableListOf<Mission>()
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var count: TextView
    private val cyan = Color.rgb(40, 205, 255)
    private val bg = Color.rgb(5, 12, 23)
    private val panel = Color.rgb(12, 27, 43)
    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            val latest = MissionStore.all(this@AutopilotActivity)
            missions.clear(); missions.addAll(latest); render()
            refreshHandler.postDelayed(this, 1500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg; window.navigationBarColor = bg
        missions.addAll(MissionStore.all(this))
        buildUi(); render()
    }

    override fun onResume() {
        super.onResume()
        missions.clear(); missions.addAll(MissionStore.all(this))
        missions.filter {
            (it.status == "EN EJECUCIÓN" || it.status == "INVESTIGANDO" || it.status == "PREPARANDO INFORME" || it.status == "ANALIZANDO") && !MissionExecution.isActive(this, it.id)
        }.forEach {
            it.status = "PAUSADA"; it.result = "La app se cerró o se interrumpió la ejecución. El progreso guardado puede reanudarse."; MissionControl.pause(it.id)
        }
        persist(); render()
        refreshHandler.removeCallbacks(refreshRunnable)
        refreshHandler.postDelayed(refreshRunnable, 1500L)
    }

    override fun onPause() {
        refreshHandler.removeCallbacks(refreshRunnable)
        super.onPause()
    }

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
        val toolsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        toolsRow.addView(actionButton("CREATIVE STUDIO") { startActivity(Intent(this@AutopilotActivity, CreativeStudioActivity::class.java)) }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(4) })
        toolsRow.addView(actionButton("DEVELOPER") { startActivity(Intent(this@AutopilotActivity, DeveloperEngineActivity::class.java)) }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(2); marginEnd = dp(2) })
        toolsRow.addView(actionButton("MEMORIA") { startActivity(Intent(this@AutopilotActivity, JarvisMemoryActivity::class.java)) }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(4) })
        root.addView(toolsRow)
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
        val activeStatuses = setOf("EN EJECUCIÓN", "PLANIFICANDO", "ANALIZANDO", "PLANIFICADA", "INVESTIGANDO", "PREPARANDO INFORME", "ENLACE VALIDADO", "DESCARGANDO")
        if (m.status in activeStatuses) {
            // Tras una muerte del proceso, el registro puede seguir marcado como activo aunque WorkManager ya terminó.
            Thread {
                val running = MissionExecution.isActive(applicationContext, m.id)
                runOnUiThread {
                    if (running) {
                        Toast.makeText(this, "La misión ya está en la cola de ejecución.", Toast.LENGTH_SHORT).show()
                    } else {
                        m.status = "INTERRUMPIDA"
                        m.result = "La ejecución anterior terminó o fue interrumpida. El progreso guardado se puede recuperar."
                        launchMission(m)
                    }
                }
            }.start()
            return
        }
        launchMission(m)
    }

    private fun launchMission(m: Mission) {
        MissionControl.resume(m.id)
        // El planificador local puede tardar; nunca debe ejecutarse en el hilo de interfaz.
        m.status = if (m.steps.isEmpty()) "PLANIFICANDO" else "EN EJECUCIÓN"
        persist(); render()
        status.text = "PREPARANDO MISIÓN: ${m.title}"
        MissionExecution.submit(applicationContext, m)
    }

    private fun render() {
        list.removeAllViews()
        val pending = missions.count { it.status !in setOf("COMPLETADA", "CANCELADA") }
        count.text = "$pending PENDIENTES"
        if (missions.isEmpty()) list.addView(label("No hay misiones. Creá una para comenzar.", 14f, 0xFF8AAFC4.toInt(), false).apply { setPadding(dp(8), dp(20), dp(8), dp(20)) })
        missions.forEach { m ->
            val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(panel); setPadding(dp(12), dp(10), dp(12), dp(10)) }
            card.addView(label(m.title, 15f, Color.WHITE, true))
            card.addView(label("${m.status}  ·  ${m.progress}%", 11f, cyan, true).apply { setPadding(0, dp(5), 0, dp(4)) })
            if (m.steps.isNotEmpty()) {
                val shownStep = m.steps.getOrNull((m.stepIndex - 1).coerceAtLeast(0)) ?: m.steps.last()
                card.addView(label("PLAN · ${m.stepIndex.coerceAtMost(m.steps.size)}/${m.steps.size} · $shownStep", 10f, 0xFF8AAFC4.toInt(), false))
            }
            val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progress = m.progress }
            card.addView(bar, LinearLayout.LayoutParams(-1, dp(5)))
            if (m.result.isNotBlank()) card.addView(label(m.result.take(700), 11f, 0xFFB4C9D7.toInt(), false).apply { setPadding(0, dp(7), 0, dp(5)) })
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val terminal = m.status == "COMPLETADA" || m.status == "REVISAR" || m.status == "ERROR DE DESCARGA" || m.status == "CANCELADA"
            val downloadQueued = m.status == "DESCARGA EN COLA"
            row.addView(actionButton(if (terminal || downloadQueued) "VER RESULTADO" else if (m.status == "PAUSADA") "REANUDAR" else "EJECUTAR") {
                if (terminal || downloadQueued) showResult(m) else { MissionControl.resume(m.id); execute(m) }
            }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(4) })
            if (!terminal && !downloadQueued && m.status != "PAUSADA") row.addView(actionButton("PAUSAR") { MissionControl.pause(m.id); m.status = "PAUSADA"; m.result = "Pausa solicitada. Se detendrá al finalizar el paso actual."; persist(); render() }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(2); marginEnd = dp(2) })
            if (!terminal) row.addView(actionButton("CANCELAR") { m.status = "CANCELADA"; m.result = "Cancelación solicitada."; persist(); MissionExecution.cancel(this@AutopilotActivity, m.id); render() }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(2); marginEnd = dp(2) })
            row.addView(actionButton("ELIMINAR") { MissionExecution.cancel(this@AutopilotActivity, m.id); missions.remove(m); persist(); render() }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(4) })
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
    override fun onDestroy() { super.onDestroy() }
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
            if (mission.status == "CANCELADA") return
            val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    val downloadedCol = it.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    val totalCol = it.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                    val downloaded = if (downloadedCol >= 0) it.getLong(downloadedCol) else -1L
                    val total = if (totalCol >= 0) it.getLong(totalCol) else -1L
                    if (downloaded == 0L || (total > 0L && downloaded != total)) {
                        mission.status = "REVISAR"
                        mission.result = "Android informó que terminó la descarga, pero el tamaño descargado no coincide con el esperado. Revisá el archivo antes de abrirlo."
                    } else {
                        mission.status = "COMPLETADA"; mission.progress = 100; mission.stepIndex = mission.steps.size
                        val uriCol = it.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                        val local = if (uriCol >= 0) it.getString(uriCol) else null
                        mission.result = "Descarga completada. Tamaño informado: ${if (downloaded >= 0) downloaded else "desconocido"} bytes.\nArchivo: ${local ?: "guardado en la carpeta de descargas de JARVIS"}\nNo se ejecutó el archivo. Revisalo antes de usarlo."
                    }
                }
                DownloadManager.STATUS_FAILED -> {
                    mission.status = "ERROR DE DESCARGA"
                    val reason = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                    mission.result = "Android no pudo completar la descarga. Código: $reason"
                }
                else -> return
            }
            mission.updated = System.currentTimeMillis()
            ExperienceMemory.add(context, mission.status, mission.title, mission.result.take(900))
            MissionStore.save(context, missions)
        }
    }
}
