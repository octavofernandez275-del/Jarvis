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
    /** Contexto del modelo en tokens (antes 2048). Con context-shift la generación no se corta aunque se llene. */
    @Volatile var contexto = 8192

    /**
     * Contexto según la RAM del teléfono (el caché del modelo crece con el contexto): así rinde al máximo sin que Android
     * cierre la app por falta de memoria. Se puede forzar con el comando «contexto 16384» (y volver con «contexto automático»).
     */
    fun calcularContexto(ctx: Context): Int {
        val manual = Ajustes.contextoManual(ctx)
        contexto = if (manual >= 2048) manual else {
            val mi = android.app.ActivityManager.MemoryInfo()
            (ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(mi)
            val gb = mi.totalMem / 1_073_741_824.0
            when {
                gb >= 10.5 -> 32768
                gb >= 6.5 -> 16384
                gb >= 4.5 -> 8192
                else -> 4096
            }
        }
        if (manual < 2048) {      // con un modelo más grande queda menos RAM para el contexto
            val g = modeloActual(ctx).gb
            if (g >= 4.5) contexto = minOf(contexto, 8192) else if (g >= 2.2) contexto = minOf(contexto, 16384)
        }
        return contexto
    }
    const val MODEL_URL =
        "https://huggingface.co/Repasar-Labs/qwen3.5-2b-gguf/resolve/main/Qwen3.5-2B-Q4_K_M.gguf"
    private const val MODEL_NAME = "modelo.gguf"

    /** Modelos que se pueden elegir. El pequeño es el de siempre; los grandes responden mejor pero piden más RAM y tardan más. */
    class Modelo(val id: String, val nombre: String, val url: String, val archivo: String, val gb: Double, val ramMinGb: Double)

    val CATALOGO = listOf(
        Modelo("pequeno", "Qwen3.5 2B (el de siempre, rápido)", MODEL_URL, MODEL_NAME, 1.3, 3.0),
        Modelo("mediano", "Qwen3 4B (más listo)", "https://huggingface.co/unsloth/Qwen3-4B-GGUF/resolve/main/Qwen3-4B-Q4_K_M.gguf", "modelo_4b.gguf", 2.5, 5.5),
        Modelo("grande", "Qwen3 8B (el más listo, más lento)", "https://huggingface.co/unsloth/Qwen3-8B-GGUF/resolve/main/Qwen3-8B-Q4_K_M.gguf", "modelo_8b.gguf", 5.0, 7.5),
    )

    /** clave = id del catálogo o una dirección https://….gguf (cada una tiene su propio archivo, nunca se mezclan). */
    fun resolver(clave: String): Modelo =
        CATALOGO.firstOrNull { it.id == clave }
            ?: if (clave.startsWith("http")) Modelo("custom", "Modelo personalizado", clave, "modelo_" + Integer.toHexString(clave.hashCode()) + ".gguf", 0.0, 0.0)
            else CATALOGO[0]

    fun modeloActual(ctx: Context): Modelo = resolver(Ajustes.modeloElegido(ctx))

    fun ramGb(ctx: Context): Double {
        val mi = android.app.ActivityManager.MemoryInfo()
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(mi)
        return mi.totalMem / 1_073_741_824.0
    }
    private const val MIN_MODEL_BYTES = 100_000_000L

    private var proc: Process? = null
    private val tail = ArrayDeque<String>()

    /**
     * true si el servidor rechazó alguna opción opcional de ahorro (por ejemplo, una versión de llama.cpp que no la conoce):
     * desde entonces se arranca solo con las opciones básicas de siempre, para que el motor nunca deje de funcionar.
     */
    @Volatile private var opcionalesRechazados = false

    /** Se llama tras un arranque fallido: si el servidor se quejó de un argumento, el reintento va en modo básico. */
    fun marcarSiArgumentoRechazado() {
        val l = lastLog().lowercase()
        if (l.contains("invalid argument") || l.contains("unknown argument") || l.contains("unrecognized")) {
            opcionalesRechazados = true
        }
    }

    fun modelFile(ctx: Context) = File(ctx.getExternalFilesDir("models") ?: ctx.filesDir, modeloActual(ctx).archivo)

    fun lastLog(): String = synchronized(tail) { tail.joinToString("\n") }

    // ---------- Validación del modelo ----------

    /** Archivo con el tamaño exacto del modelo completo, escrito al terminar la descarga. */
    private fun sello(f: File) = File(f.path + ".ok")

    /** Los modelos GGUF empiezan con las letras "GGUF". */
    private fun esGguf(f: File): Boolean = try {
        f.inputStream().use { val b = ByteArray(4); it.read(b) == 4 && String(b, Charsets.US_ASCII) == "GGUF" }
    } catch (e: Exception) {
        false
    }

    /** El modelo existe, es un GGUF y tiene el tamaño con el que se descargó (no quedó a medias). */
    private fun modeloValido(f: File): Boolean {
        if (!f.exists() || f.length() < MIN_MODEL_BYTES || !esGguf(f)) return false
        val s = sello(f)
        if (!s.exists()) {                       // instalación anterior: se acepta y se registra su tamaño
            s.writeText(f.length().toString())
            return true
        }
        return s.readText().trim().toLongOrNull() == f.length()
    }

    /** Descarga con reanudación. Devuelve true si el modelo queda listo. */
    fun download(ctx: Context, onProgress: (Int) -> Unit): Boolean {
        val f = modelFile(ctx)
        if (modeloValido(f)) return true
        if (f.exists()) { f.delete(); sello(f).delete() }   // dañado o incompleto: se descarga de nuevo
        f.parentFile?.mkdirs()
        val part = File(f.path + ".part")
        val have = if (part.exists()) part.length() else 0L
        val c = URL(modeloActual(ctx).url).openConnection() as HttpURLConnection
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
        // Si la conexión se cortó sin error, el .part queda guardado para reanudar la próxima vez
        if (total > 0 && done < total) return false
        if (!esGguf(part)) { part.delete(); return false }
        if (!part.renameTo(f)) return false
        sello(f).writeText(f.length().toString())
        return true
    }

    /** True si la RAM disponible supera con holgura el modelo + el caché de contexto (se deja >= 1.5 GB libre). */
    private fun ramHolgada(ctx: Context): Boolean = try {
        val mi = android.app.ActivityManager.MemoryInfo()
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(mi)
        val modelo = modelFile(ctx).length()
        mi.availMem > modelo + (modelo / 2) + 1_500_000_000L
    } catch (_: Exception) { false }

    fun start(ctx: Context) {
        if (proc?.isAlive == true) return
        val basico = opcionalesRechazados
        if (!modelFile(ctx).exists()) throw java.io.FileNotFoundException("Falta el modelo: ${modelFile(ctx).path}")
        val bin = File(ctx.applicationInfo.nativeLibraryDir, "libllama_server.so")
        // Hilos de CPU: se pueden cambiar con el comando "hilos N" (ver Ajustes) para probar el más rápido
        val t = Ajustes.hilosEfectivos(ctx)
        val args = mutableListOf(
            bin.path, "-m", modelFile(ctx).path,
            "--host", "127.0.0.1", "--port", PORT.toString(),
            "-c", calcularContexto(ctx).toString(), "-t", t.toString(), "-np", "1",
            "--chat-template-kwargs", "{\"enable_thinking\":false}"
        )
        // Reparto por complejidad: pocos hilos para escribir la respuesta (límite de memoria, no de cálculo)
        // y más hilos para leer el prompt (cálculo puro, dura poco). Ver Cpu.perfil().
        val tb = Ajustes.hilosLecturaEfectivos(ctx)
        if (tb > 0) { args.add("-tb"); args.add(tb.toString()) }
        // Hilos fijados a los núcleos más rápidos, dejando 2 libres para Android, la voz y la pantalla (ver Cpu)
        if (Ajustes.nucleosFijos(ctx)) {
            val mascara = Cpu.mascara(t)
            if (mascara != null) { args.add("-C"); args.add(mascara); args.add("--cpu-strict"); args.add("1") }
            // La lectura del prompt tiene su propia máscara (antes heredaba una de tamaño distinto)
            if (!basico && tb > t) {
                val mascaraLectura = Cpu.mascara(tb)
                if (mascaraLectura != null) { args.add("-Cb"); args.add(mascaraLectura); args.add("--cpu-strict-batch"); args.add("1") }
            }
        }
        if (!basico) {
            // (50 = el valor de llama.cpp: despierta más rápido entre token y token; con 0 cada token esperaba a que el hilo despertara)
            args.add("--poll"); args.add("50")
            // Sin la página web integrada del servidor: menos memoria y nada expuesto (solo usamos la API)
            args.add("--no-webui")
            // Si la conversación llena el contexto, descarta lo más viejo y sigue escribiendo (respuestas sin límite)
            args.add("--context-shift")
            // Reutiliza trozos de caché aunque el prefijo cambie un poco (menos relectura del prompt)
            args.add("--cache-reuse"); args.add("256")
            // RAM libre de sobra: fija el modelo en memoria para que Android no lo expulse (primer token sin esperas)
            if (ramHolgada(ctx)) args.add("--mlock")
            // Opciones que se prueban con comandos ("lote 1024", "flash on"); por defecto no se tocan
            val lote = Ajustes.lote(ctx)
            if (lote > 0) { args.add("-ub"); args.add(lote.toString()); if (lote > 2048) { args.add("-b"); args.add(lote.toString()) } }
            val fa = Ajustes.flash(ctx)
            if (fa != "auto") { args.add("-fa"); args.add(fa) }
        }
        val pb = ProcessBuilder(args)
        pb.redirectErrorStream(true)
        val p = pb.start()
        proc = p
        // Prioridad MÁXIMA permanente (nice -8) para todos los hilos del servidor, o la de antes si se pidió ("prioridad baja").
        // El servidor crea hilos al cargar el modelo y otra vez al generar: se reaplica cada 0,5 s durante el primer minuto
        // y cada 4 s mientras el proceso viva; además, cada respuesta la reaplica antes de empezar (ver Chat.stream).
        val nice = when {
            Ajustes.prioridadMaxima(ctx) -> Hilos.PRIORIDAD_MAX
            Ajustes.prioridadBaja(ctx) -> 9
            else -> null
        }
        if (nice != null) {
            Thread {
                try {
                    val t0 = System.currentTimeMillis()
                    while (p.isAlive) {
                        priorizar(p, nice)
                        Thread.sleep(if (System.currentTimeMillis() - t0 < 60_000L) 500L else 4_000L)
                    }
                } catch (_: Exception) {
                }
            }.also { it.isDaemon = true }.start()
        }
        Thread {
            // Al detener el servidor se cierra su salida y esta lectura lanza una excepción;
            // sin este try/catch esa excepción cerraba toda la app.
            var rechazo = false
            try {
                p.inputStream.bufferedReader().forEachLine { line ->
                    // El registro solo guarda las últimas líneas: el aviso de "argumento no válido" se detecta aquí
                    if (line.contains("invalid argument", ignoreCase = true) || line.contains("unknown argument", ignoreCase = true)) rechazo = true
                    synchronized(tail) { tail.addLast(line); while (tail.size > 12) tail.removeFirst() }
                }
            } catch (_: Exception) {
            }
            if (rechazo && !basico) opcionalesRechazados = true   // el reintento arranca con las opciones básicas
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

    @Volatile private var niceActual: Int? = null

    /** PID del proceso hijo (Process.pid() solo existe desde Android 13: se lee el campo interno). */
    private fun pidDe(p: Process): Int? = try {
        val f = p.javaClass.getDeclaredField("pid"); f.isAccessible = true; f.getInt(p)
    } catch (_: Exception) { null }

    /** Aplica `nice` a TODOS los hilos del servidor (solo ve y toca procesos de la propia app). Lee solo /proc/<pid>/task: no recorre todo /proc. */
    private fun priorizar(p: Process, nice: Int) {
        try {
            niceActual = nice
            val tareas = File("/proc/" + (pidDe(p) ?: return) + "/task").listFiles() ?: return
            for (t in tareas) {
                val tid = t.name.toIntOrNull() ?: continue
                try { android.os.Process.setThreadPriority(tid, nice) } catch (_: Exception) {}
            }
        } catch (_: Exception) {
        }
    }

    /** Reaplica la prioridad al motor ya en marcha (la llama cada respuesta antes de empezar; no hace nada si no hay motor). */
    fun reforzarPrioridad() {
        val p = proc ?: return
        val n = niceActual ?: return
        if (p.isAlive) priorizar(p, n)
    }

    fun stop() { proc?.destroyForcibly(); proc = null }   // cierre inmediato: libera el puerto y la RAM al instante

    /**
     * Cierra servidores que quedaron vivos de una ejecución anterior (si Android mató la app de golpe,
     * llama-server seguía con ~2 GB de RAM y el puerto ocupado). Solo ve y cierra procesos de la propia app.
     * Llamar al iniciar el proceso, antes de arrancar el motor.
     */
    fun matarHuerfanos() {
        if (proc?.isAlive == true) return
        try {
            File("/proc").listFiles()?.forEach { d ->
                val pid = d.name.toIntOrNull() ?: return@forEach
                if (pid == android.os.Process.myPid()) return@forEach
                val cmd = try {
                    File(d, "cmdline").inputStream().use { String(it.readBytes(), Charsets.ISO_8859_1) }
                } catch (e: Exception) {
                    return@forEach
                }
                if (cmd.contains("libllama_server")) android.os.Process.killProcess(pid)
            }
        } catch (_: Exception) {
        }
    }

    /** Chat en streaming: llama onToken por cada fragmento de texto. */
    fun chat(messages: List<Pair<String, String>>, onToken: (String) -> Unit) {
        val arr = JSONArray()
        messages.forEach {
            arr.put(JSONObject().put("role", it.first).put("content", it.second))
        }

        val body = JSONObject()
            .put("stream", false)
            .put("temperature", 0.6)
            .put("max_tokens", -1)
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
