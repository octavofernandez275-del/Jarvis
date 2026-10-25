package com.jarvis.chat

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Reúne clima, agenda, pendientes y noticias para poner al usuario al tanto. */
object Brief {
    private const val DEFAULT_URL = "https://generativelanguage.googleapis.com/v1beta/openai"
    private const val DEFAULT_MODEL = "gemini-2.5-flash"

    private fun prefs(c: Context) = c.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun get(url: String, max: Int = 200000): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 15000
        c.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
        )
        return c.inputStream.bufferedReader().use { r ->
            val sb = StringBuilder()
            val buf = CharArray(8192)
            while (sb.length < max) {
                val n = r.read(buf)
                if (n < 0) break
                sb.append(buf, 0, n)
            }
            sb.toString()
        }
    }

    private fun clean(h: String) = h
        .replace(Regex("(?s)<[^>]+>"), " ")
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#x27;", "'").replace("&nbsp;", " ")
        .replace(Regex("\\s+"), " ").trim()

    private fun search(q: String, n: Int): List<String> {
        val html = get("https://html.duckduckgo.com/html/?q=" + enc(q), 250000)
        val links = Regex("(?s)class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>")
            .findAll(html).toList()
        return links.take(n).map { "- " + clean(it.groupValues[2]) }
    }

    // ---------- Clima (Open-Meteo, sin clave) ----------

    private fun weather(c: Context): String {
        val city = (prefs(c).getString("city", "") ?: "").trim()
        if (city.isEmpty()) return "Clima: el usuario no ha configurado su ciudad en Ajustes."
        val g = JSONObject(
            get("https://geocoding-api.open-meteo.com/v1/search?name=${enc(city)}&count=1&language=es&format=json")
        )
        val res = g.optJSONArray("results") ?: return "Clima: no encontré la ciudad $city."
        val p = res.getJSONObject(0)
        val w = JSONObject(
            get(
                "https://api.open-meteo.com/v1/forecast?latitude=${p.getDouble("latitude")}" +
                    "&longitude=${p.getDouble("longitude")}&current=temperature_2m" +
                    "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                    "&timezone=auto&forecast_days=2"
            )
        )
        val d = w.getJSONObject("daily")
        val mx = d.getJSONArray("temperature_2m_max")
        val mn = d.getJSONArray("temperature_2m_min")
        val rain = d.getJSONArray("precipitation_probability_max")
        fun day(i: Int) = "máx ${mx.optDouble(i).toInt()}°, mín ${mn.optDouble(i).toInt()}°, lluvia ${rain.optInt(i)}%"
        val now = w.getJSONObject("current").optDouble("temperature_2m").toInt()
        return "Clima en ${p.optString("name", city)}: ahora $now°. Hoy: ${day(0)}. Mañana: ${day(1)}."
    }

    // ---------- Agenda (calendario del teléfono, sincronizado con tu cuenta de Google) ----------

    fun agenda(c: Context, days: Int): String {
        if (c.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return "Agenda: falta el permiso para leer el calendario."
        }
        val now = System.currentTimeMillis()
        val span = days.coerceIn(1, 14)
        val b = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(b, now)
        ContentUris.appendId(b, now + span * 86400000L)
        val out = mutableListOf<String>()
        val fmt = SimpleDateFormat("EEE d HH:mm", Locale.forLanguageTag("es-MX"))
        c.contentResolver.query(
            b.build(),
            arrayOf(
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.ALL_DAY
            ),
            null, null, CalendarContract.Instances.BEGIN + " ASC"
        )?.use { cur ->
            while (cur.moveToNext() && out.size < 15) {
                val whenText = if (cur.getInt(2) == 1) "todo el día" else fmt.format(Date(cur.getLong(1)))
                out.add("- $whenText: ${cur.getString(0) ?: "(sin título)"}")
            }
        }
        return if (out.isEmpty()) "Agenda: no hay eventos en los próximos $span días."
        else "Agenda:\n" + out.joinToString("\n")
    }

    // ---------- Pendientes ----------

    private fun load(c: Context): JSONArray = try {
        JSONArray(prefs(c).getString("tasks", "[]"))
    } catch (e: Exception) {
        JSONArray()
    }

    fun addTask(c: Context, t: String): String {
        val a = load(c)
        a.put(t.trim())
        prefs(c).edit().putString("tasks", a.toString()).apply()
        return "Anotado: ${t.trim()}"
    }

    fun taskList(c: Context): String {
        val a = load(c)
        if (a.length() == 0) return "Pendientes: no hay ninguno."
        return "Pendientes:\n" + (0 until a.length()).joinToString("\n") { "- " + a.getString(it) }
    }

    fun doneTask(c: Context, t: String): String {
        val a = load(c)
        val out = JSONArray()
        var removed: String? = null
        for (i in 0 until a.length()) {
            val s = a.getString(i)
            if (removed == null && s.contains(t, ignoreCase = true)) removed = s else out.put(s)
        }
        prefs(c).edit().putString("tasks", out.toString()).apply()
        return if (removed == null) "No encontré ese pendiente." else "Completado: $removed"
    }

    // ---------- Noticias ----------

    private fun news(c: Context): String {
        val topics = (prefs(c).getString("topics", "") ?: "")
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }.take(3)
        if (topics.isEmpty()) return "Noticias: el usuario no ha elegido temas en Ajustes."
        return "Noticias:\n" + topics.joinToString("\n") { t ->
            val found = try {
                search("$t noticias hoy", 3).joinToString("\n")
            } catch (e: Exception) {
                "(sin resultados)"
            }
            "$t:\n$found"
        }
    }

    // ---------- Todo junto ----------

    fun gather(c: Context): String {
        fun safe(f: () -> String): String = try {
            f()
        } catch (e: Exception) {
            "(no disponible: ${e.message})"
        }
        val today = SimpleDateFormat("EEEE d 'de' MMMM yyyy, HH:mm", Locale.forLanguageTag("es-MX")).format(Date())
        return listOf(
            "Fecha: $today",
            safe { weather(c) },
            safe { agenda(c, 2) },
            safe { taskList(c) },
            safe { news(c) }
        ).joinToString("\n\n")
    }

    /** Pide al modelo que convierta los datos en un resumen corto (una sola llamada, sin herramientas). */
    fun summarize(c: Context, data: String): String {
        val p = prefs(c)
        val base = (p.getString("url", DEFAULT_URL) ?: DEFAULT_URL).trimEnd('/')
        val key = p.getString("key", "") ?: ""
        val model = (p.getString("model", DEFAULT_MODEL) ?: "").ifEmpty { "default" }
        val msgs = JSONArray()
            .put(
                JSONObject().put("role", "system").put(
                    "content",
                    "Eres Jarvis, el asistente personal del usuario. Con los datos que recibes, escribe en " +
                        "español un resumen breve y amable (máximo 8 líneas): clima con un consejo útil, " +
                        "agenda, pendientes y una línea por tema de noticias. No inventes nada que no esté en los datos."
                )
            )
            .put(JSONObject().put("role", "user").put("content", data))
        val con = URL("$base/chat/completions").openConnection() as HttpURLConnection
        con.requestMethod = "POST"
        con.doOutput = true
        con.connectTimeout = 15000
        con.readTimeout = 60000
        con.setRequestProperty("Content-Type", "application/json")
        if (key.isNotEmpty()) con.setRequestProperty("Authorization", "Bearer $key")
        con.outputStream.use {
            it.write(JSONObject().put("model", model).put("messages", msgs).toString().toByteArray())
        }
        if (con.responseCode !in 200..299) throw Exception("Error ${con.responseCode}")
        return JSONObject(con.inputStream.bufferedReader().readText())
            .getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
    }
}
