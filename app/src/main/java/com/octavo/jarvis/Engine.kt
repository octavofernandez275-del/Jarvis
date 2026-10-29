package com.octavo.jarvis

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Motor local: corre llama-server (empaquetado en la APK) y habla con él por HTTP en 127.0.0.1. */
object Engine {
    const val PORT = 8001
    const val MODEL_URL =
        "https://huggingface.co/Repasar-Labs/qwen3.5-2b-gguf/resolve/main/Qwen3.5-2B-Q4_K_M.gguf"
    private const val MODEL_NAME = "modelo.gguf"

    private var proc: Process? = null
    private val tail = ArrayDeque<String>()

    fun modelFile(ctx: Context) = File(ctx.getExternalFilesDir("models") ?: ctx.filesDir, MODEL_NAME)

    fun lastLog(): String = synchronized(tail) { tail.joinToString("\n") }

    /** Descarga con reanudación. Devuelve true si el modelo queda listo. */
    fun download(ctx: Context, onProgress: (Int) -> Unit): Boolean {
        val f = modelFile(ctx)
        if (f.exists() && f.length() > 100_000_000) return true
        f.parentFile?.mkdirs()
        val part = File(f.path + ".part")
        val have = if (part.exists()) part.length() else 0L
        val c = URL(MODEL_URL).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = true
        c.connectTimeout = 15000
        c.readTimeout = 30000
        if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
        c.connect()
        val code = c.responseCode
        if (code != 200 && code != 206) return false
        val resumed = code == 206
        val base = if (resumed) have else 0L
        val total = base + c.contentLengthLong
        var done = base
        var lastPct = -1
        FileOutputStream(part, resumed).use { out ->
            c.inputStream.use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    val pct = if (total > 0) (done * 100 / total).toInt() else 0
                    if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                }
            }
        }
        return part.renameTo(f)
    }

    fun start(ctx: Context) {
        if (proc?.isAlive == true) return
        val bin = File(ctx.applicationInfo.nativeLibraryDir, "libllama_server.so")
        val pb = ProcessBuilder(
            bin.path, "-m", modelFile(ctx).path,
            "--host", "127.0.0.1", "--port", PORT.toString(),
            "-c", "2048", "-t", "4", "-np", "1"
        )
        pb.redirectErrorStream(true)
        val p = pb.start()
        proc = p
        Thread {
            p.inputStream.bufferedReader().forEachLine { line ->
                synchronized(tail) { tail.addLast(line); while (tail.size > 12) tail.removeFirst() }
            }
        }.start()
    }

    fun waitReady(timeoutMs: Long = 180_000): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (proc?.isAlive != true) return false
            try {
                val c = URL("http://127.0.0.1:$PORT/health").openConnection() as HttpURLConnection
                c.connectTimeout = 1000; c.readTimeout = 1000
                if (c.responseCode == 200) return true
            } catch (_: Exception) {}
            Thread.sleep(1000)
        }
        return false
    }

    fun stop() { proc?.destroy(); proc = null }

    /** Chat en streaming: llama onToken por cada fragmento de texto. */
    fun chat(messages: List<Pair<String, String>>, onToken: (String) -> Unit) {
        val arr = JSONArray()
        messages.forEach {
            arr.put(JSONObject().put("role", it.first).put("content", it.second))
        }

        val body = JSONObject()
            .put("stream", false)
            .put("temperature", 0.6)
            .put("max_tokens", 300)
            .put("messages", arr)
            .toString()

        val c = URL("http://127.0.0.1:$PORT/v1/chat/completions")
            .openConnection() as HttpURLConnection

        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 10000
        c.readTimeout = 180000
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("Accept", "application/json")

        try {
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

            if (code !in 200..299) {
                throw java.io.IOException("Servidor HTTP $code: ${raw.take(700)}")
            }

            val root = JSONObject(raw)
            val content = root.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                .orEmpty()

            if (content.isBlank()) {
                throw java.io.IOException("El servidor respondió sin texto: ${raw.take(500)}")
            }

            onToken(content)
        } finally {
            c.disconnect()
        }
    }
}
