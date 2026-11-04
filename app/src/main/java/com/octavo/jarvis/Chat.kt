package com.octavo.jarvis

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Chat con el modelo local EN STREAMING: llama a onToken con cada fragmento apenas llega,
 * así se ve el texto y empieza la voz sin esperar la respuesta completa.
 * Es independiente de Engine.kt (que no se toca). Los valores de generación son los de tu Engine actual.
 *
 * Si onToken lanza una excepción (por ejemplo, el usuario interrumpe), se cierra la conexión
 * y llama-server deja de generar.
 */
object Chat {
    private const val TEMPERATURA = 0.6
    private const val MAX_TOKENS = 300

    /** Chat normal: usa la temperatura y el largo máximo de siempre. */
    fun stream(messages: List<Pair<String, String>>, onToken: (String) -> Unit): Unit =
        stream(messages, Termico.limiteTokens(MAX_TOKENS), TEMPERATURA, onToken)   // más corto si el teléfono está muy caliente

    /** Igual, pero con largo máximo y temperatura propios (lo usan las tareas de Jarvis). */
    fun completar(
        messages: List<Pair<String, String>>, maxTokens: Int, temperatura: Double,
        esquema: JSONObject? = null, onToken: (String) -> Unit = {}
    ): String {
        val sb = StringBuilder()
        stream(messages, maxTokens, temperatura, esquema) { t -> sb.append(t); onToken(t) }
        return sb.toString().trim()
    }

    fun stream(messages: List<Pair<String, String>>, maxTokens: Int, temperatura: Double, onToken: (String) -> Unit): Unit =
        stream(messages, maxTokens, temperatura, null, onToken)

    /** Con esquema, llama-server obliga al modelo a responder un JSON válido con esa forma (incluso un modelo chico). */
    fun stream(
        messages: List<Pair<String, String>>, maxTokens: Int, temperatura: Double,
        esquema: JSONObject?, onToken: (String) -> Unit
    ) {
        val arr = JSONArray()
        messages.forEach { arr.put(JSONObject().put("role", it.first).put("content", it.second)) }
        val body = JSONObject()
            .put("stream", true)
            .put("temperature", temperatura)
            .put("max_tokens", maxTokens)
            .put("cache_prompt", true)                                              // reutilizar lo ya procesado
            .put("chat_template_kwargs", JSONObject().put("enable_thinking", false)) // sin razonamiento extendido
            .put("messages", arr)
        if (esquema != null) body.put("response_format", JSONObject().put("type", "json_object").put("schema", esquema))

        val c = URL("http://127.0.0.1:${Engine.PORT}/v1/chat/completions").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 10_000
            c.readTimeout = 120_000
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Accept", "text/event-stream")
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = c.responseCode
            if (code !in 200..299) {
                val err = c.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw Exception("El servidor respondió $code: ${err.take(300)}")
            }

            var total = 0
            var fin = ""
            c.inputStream.bufferedReader(Charsets.UTF_8).use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.substring(5).trim()
                    if (data.isEmpty()) continue
                    if (data == "[DONE]") break
                    val j = try { JSONObject(data) } catch (e: Exception) { continue }
                    j.optJSONObject("error")?.let {
                        throw Exception("El servidor informó un error: ${it.optString("message")}")
                    }
                    val ch = j.optJSONArray("choices")?.optJSONObject(0) ?: continue
                    if (!ch.isNull("finish_reason")) fin = ch.optString("finish_reason")
                    val d = ch.optJSONObject("delta")
                    // El primer fragmento trae content = null: se ignora
                    if (d != null && !d.isNull("content")) {
                        val t = d.optString("content", "")
                        if (t.isNotEmpty()) {
                            total += t.length
                            onToken(t)
                        }
                    }
                }
            }
            if (total == 0) {
                throw Exception("El modelo no devolvió texto (motivo: ${fin.ifEmpty { "desconocido" }}).")
            }
        } finally {
            c.disconnect()
        }
    }
}
