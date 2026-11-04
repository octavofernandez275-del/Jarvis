package com.octavo.jarvis

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import android.view.KeyEvent
import android.widget.Toast
import java.util.Calendar

/**
 * Respuesta de un comando local.
 * - texto: lo que Jarvis muestra y dice.
 * - permiso: si no es null, falta ese permiso; hay que pedirlo y repetir el comando.
 * - despues: acción que abre otra app; se ejecuta DESPUÉS de hablar (al salir de la app se corta la voz).
 */
class Reply(val texto: String, val permiso: String? = null, val despues: (() -> Unit)? = null)

/**
 * Comandos del celular y memoria. Se resuelven con reglas simples (sin IA):
 * es instantáneo, no gasta batería y no falla como un modelo de 2B.
 * Si el texto no es un comando, run() devuelve null y la pregunta va al modelo.
 * Usa solo Context (no Activity): el bloque 4 podrá llamarlo desde un servicio.
 */
object Commands {
    private var esperandoBorrado = false
    private var linternaOn = false
    private var camaraId: String? = null

    // ---------- Reglas (se aplican al texto sin tildes y en minúsculas) ----------

    private val rxPrefijo = Regex("""^(?:(?:oye|ok|hey|hola)[,\s]+)?jarvis[,:\s]*|^por favor[,\s]*|^(?:puedes|podrias|podes)\s+""")
    private val rxSufijo = Regex("""[,\s]+por favor$""")
    private val rxSi = Regex("""^(?:si|sii|claro|confirmo|dale|borralo|adelante)\b""")
    private val rxNo = Regex("""^(?:no|cancela|cancelar|mejor no|deja)\b""")

    private val rxAyuda = Regex("""^(?:que puedes hacer|que sabes hacer|ayuda|comandos|que comandos (?:tienes|hay))$""")

    private val rxNueva = Regex("""^(?:nueva conversacion|empecemos de nuevo|empezar de nuevo|reinicia la conversacion|olvida la conversacion|borra la conversacion|olvidemos la conversacion)$""")

    // Rendimiento del motor (para probar qué configuración es más rápida)
    private val rxHilos = Regex("""^(?:usa |pon |ajusta )?(?:los )?hilos(?: de cpu)?(?: a| en)? (\d{1,2})$|^(?:usa |pon )?(\d{1,2}) hilos(?: de cpu)?$""")
    private val rxHilosLectura = Regex("""^(?:usa |pon |ajusta )?hilos de lectura(?: a| en)? (\d{1,2}|igual)$""")
    private val rxHilosPregunta = Regex("""^(?:cuantos hilos usas|que hilos usas|hilos actuales|cuantos hilos)$""")

    private val rxVozCambiar = Regex("""^(?:cambia|cambiar|cambiame)(?: de)?(?: la)? voz$""")
    private val rxVozNumero = Regex("""^(?:usa |pon )?(?:la )?voz (?:numero )?(\d{1,2})$""")
    private val rxVozLista = Regex("""^(?:lista|dime|muestra)(?: las| mis)? voces$|^que voces (?:tienes|hay)$""")
    private val rxVozRapida = Regex("""^(?:habla|hablas|lee) mas rapido$""")
    private val rxVozLenta = Regex("""^(?:habla|hablas|lee) mas lento$""")
    private val rxVozGrave = Regex("""^voz mas grave$""")
    private val rxVozAguda = Regex("""^voz mas aguda$""")
    private val rxVozNormal = Regex("""^voz normal$""")
    private val rxVozAjustes = Regex("""^(?:abre |abrir )?ajustes de (?:la )?voz$""")
    private val rxAnimacionNo = Regex("""^(?:apaga|desactiva|quita) (?:la )?animacion(?: del reactor)?$""")
    private val rxAnimacionSi = Regex("""^(?:enciende|prende|activa|pon) (?:la )?animacion(?: del reactor)?$""")
    private val rxTareaAgregar = Regex("""^(?:agrega|agregar|anade|anadir|crea|crear|apunta|anota)(?: una| otra)? tarea(?: de| para| que|:)? ?(.+)$""")
    private val rxTareaLista = Regex("""^(?:que tareas tengo|mis tareas|lista(?: de)?(?: mis)? tareas|tareas pendientes|que tengo pendiente)$""")
    private val rxTareaAbrir = Regex("""^(?:abre|abrir|muestrame|mostrar|ve a)(?: las| mis)? tareas$""")
    private val rxTareaHacer = Regex("""^(?:haz|hace|ejecuta|ejecutar|realiza|hazme)(?: la)? tarea (?:numero )?(\d{1,2})$""")
    private val rxTareaSugerir = Regex("""^(?:sugiere|sugiereme|sugerime)(?: me)?(?: algunas)? tareas(?: utiles)?$""")
    private val rxPiloto = Regex("""^(activa|enciende|prende|desactiva|apaga) (?:el )?piloto automatico$""")
    private val rxCuriosidad = Regex("""^(activa|enciende|prende|desactiva|apaga) (?:la )?curiosidad$""")
    private val rxVozFinal = Regex("""^(?:voz al final|(?:habla|lee|di|dictalo) (?:todo )?(?:al terminar|al final|despues de escribir|despues de responder))$""")
    private val rxVozVivo = Regex("""^(?:voz en vivo|(?:habla|lee|di|dictalo) (?:mientras (?:escribes|respondes|generas)|en vivo))$""")

    private val rxArranqueSi = Regex("""^(?:arranca|carga|prende)(?: el motor)? al (?:encender|prender)(?: el (?:celular|telefono))?$""")
    private val rxArranqueNo = Regex("""^no (?:arranques|cargues|prendas)(?: el motor)? al (?:encender|prender)(?: el (?:celular|telefono))?$""")
    private val rxMotorSiempre = Regex("""^(?:manten(?: el motor)? (?:encendido|prendido|activo)|manten el motor|no apagues(?: el motor)?|motor siempre encendido)$""")
    private val rxMotorTiempo = Regex("""^(?:apaga|apagar) el motor (?:tras|despues de|a los|en) (\d{1,3}) (?:minutos?|min)$""")

    private val rxPrioridadBaja = Regex("""^(?:usa |pon )?prioridad baja$""")
    private val rxPrioridadNormal = Regex("""^(?:usa |pon )?prioridad normal$""")
    private val rxNucleosRapidos = Regex("""^(?:usa |pon |fija )?(?:los )?nucleos rapidos$""")
    private val rxNucleosTodos = Regex("""^(?:usa |pon )?(?:todos los nucleos|nucleos libres|sin fijar nucleos)$""")

    // Memoria
    private val rxRecuerda = Regex("""^(?:recuerda|recorda|acuerdate|anota|memoriza|guarda)(?: en tu memoria)?(?: que)?\s+(.+)$""")
    private val rxListar = Regex("""^(?:que (?:recuerdas|sabes|tienes anotado|tienes en tu memoria)(?: de mi| sobre mi)?|que sabes de mi|dime lo que (?:recuerdas|sabes)(?: de mi)?|muestra(?:me)? tu memoria|lee tu memoria)$""")
    private val rxBorrarTodo = Regex("""^(?:borra|elimina|olvida|vacia|limpia)\s+(?:toda tu memoria|todo lo que (?:sabes|recuerdas)(?: de mi)?|todo|tu memoria|la memoria|todos los datos)$""")
    private val rxOlvida = Regex("""^(?:olvida|olvidate de|borra de tu memoria|elimina de tu memoria|quita de tu memoria)\s+(?:(?:que|lo de|lo que|lo)\s+)?(.+)$""")

    // Hora, fecha, batería
    private val rxHora = Regex("""^(?:(?:dime|decime|me dices|me puedes decir|me podrias decir) )?(?:la hora|que hora(?: es| son| tienes)?(?: ahora)?|cual es la hora)(?: actual)?$""")
    private val rxFecha = Regex("""^(?:(?:dime|decime|me dices) )?(?:que dia es(?: hoy)?|que fecha es(?: hoy)?|la fecha|cual es la fecha(?: de hoy)?|que dia estamos|a cuanto estamos|en que dia estamos)$""")
    private val rxBateria = Regex("""^(?:cuanta |que |cual es la |como esta la |dime la |dime el nivel de |nivel de |estado de la )?(?:bateria|carga)(?: me queda| tengo| tiene el celular| queda| tiene)?(?: de bateria)?$""")

    // Linterna, volumen, música
    private val rxLinterna = Regex("""^(?:(enciende|prende|activa|pon|apaga|desactiva|quita)(?: la)? )?(?:linterna|luz del (?:celular|telefono|movil)|flash)$""")
    private val rxVolSet = Regex("""^(?:(?:pon|pone|ajusta|sube|baja|subi|bajale|subele)(?: el)? )?volumen (?:al|a|en) (\d{1,3}|maximo|minimo|medio|mitad)(?: ?%| por ciento)?$""")
    private val rxVolSube = Regex("""^(?:(?:sube|subir|aumenta|subele|subi)(?: un poco| mucho)?(?: el| al)? (?:volumen|sonido)(?: un poco| mucho)?|mas volumen|mas fuerte|volumen mas alto)$""")
    private val rxVolBaja = Regex("""^(?:(?:baja|bajar|reduce|bajale|disminuye)(?: un poco| mucho)?(?: el| al)? (?:volumen|sonido)(?: un poco| mucho)?|menos volumen|mas bajo|volumen mas bajo)$""")
    private val rxMute = Regex("""^(?:silencia|silenciar|mutea|mutear|quita el sonido|pon en silencio)(?: el| al| la)?(?: celular| telefono| volumen| sonido| musica)?$""")
    private val rxUnmute = Regex("""^(?:(?:activa|pon|restaura|reactiva)(?: el)? (?:sonido|volumen)|quita el silencio|quita el mute|desmutea)$""")
    private val rxPausa = Regex("""^(?:pausa|pausar|deten|detener|para|frena)(?: la| el)? (?:musica|cancion|reproduccion|audio|video)$|^(?:pausa|pausar)$""")
    private val rxPlay = Regex("""^(?:reanuda|reanudar|continua|continuar|reproduce|pon|dale play a)(?: la| el)? (?:musica|cancion|reproduccion)$|^(?:play|reanuda|reanudar)$""")
    private val rxSig = Regex("""^(?:siguiente|proxima)(?: cancion| pista| tema)?$|^(?:pon|pasa a|salta a|cambia a) la (?:siguiente|proxima)(?: cancion| pista| tema)?$|^(?:pasa|salta|cambia) (?:la |de )?(?:cancion|pista|tema)$""")
    private val rxAnt = Regex("""^(?:anterior|cancion anterior)$|^(?:pon|vuelve a|regresa a) la (?:anterior|cancion anterior)$|^(?:vuelve|regresa) a la cancion anterior$""")

    // Temporizador y alarma
    private val rxTemp = Regex("""^(?:(?:pon|pone|ponme|programa|crea|configura|activa|inicia|empieza)(?: un| una| el| la)? )?(?:temporizador|timer|cronometro|cuenta regresiva|alarma)(?: de| por| para| en| dentro de)? (.+)$|^(?:avisame|despiertame)(?: en| dentro de) (.+)$""")
    private val rxTempSinDato = Regex("""^(?:(?:pon|pone|ponme|programa|crea|configura|activa|inicia|empieza)(?: un| el)? )?(?:temporizador|timer)$""")
    private val rxAlarma = Regex("""^(?:(?:pon|pone|ponme|programa|crea|configura|activa)(?: una| la| otra)? alarma|despiertame)(.*)$""")

    // Llamar, ajustes, mapas, búsqueda, abrir apps
    private val rxLlamar = Regex("""^(?:llama|llamar|llamale|marca|telefonea)(?: a| al| a la)?\s+(.+)$""")
    private val rxAjustes = Regex("""^(abre|abri|abrir|ve a|ir a|enciende|prende|activa|apaga|desactiva|configura)(?: los| las| el| la)? ?(ajustes|configuracion|configuraciones|wifi|wi fi|bluetooth)$""")
    private val rxRuta = Regex("""^(?:llevame a|como llego a|como llegar a|navega a|navegar a|ruta a|ruta hacia|dame la ruta a|indicame como llegar a)\s+(.+)$""")
    private val rxBuscar = Regex("""^(?:busca|buscar|googlea|investiga)(?: en (?:internet|google|la web))?(?: sobre| acerca de| de)?\s+(.+)$""")
    private val rxAbrir = Regex("""^(?:abre|abri|abrir|abreme|lanza|inicia|ejecuta|ve a|anda a|entra a|entra en)\s+(?:(?:la|el|los|las)\s+)?(?:(?:aplicacion|app)\s+)?(?:de\s+)?(.+)$""")

    private val alias = mapOf(
        "mapas" to "maps", "camara" to "camera", "calculadora" to "calculator", "calendario" to "calendar",
        "reloj" to "clock", "contactos" to "contacts", "telefono" to "phone", "mensajes" to "messages",
        "galeria" to "gallery", "fotos" to "photos", "ajustes" to "settings", "configuracion" to "settings",
        "navegador" to "chrome", "correo" to "gmail", "musica" to "music", "tienda" to "play store"
    )

    // ---------- Punto de entrada ----------

    /** Devuelve la respuesta si el texto era un comando; null si debe contestar el modelo. */
    fun run(ctx: Context, texto: String): Reply? {
        return try {
            interpretar(ctx, texto)
        } catch (e: Exception) {
            Reply("No pude hacerlo: ${e.message}")
        }
    }

    private fun interpretar(ctx: Context, texto: String): Reply? {
        var orig = texto.trim().trim('¿', '¡', ' ', '.', '!', '?', ',')
        var f = Parsers.fold(orig)
        // Quitar "Jarvis,", "por favor", "puedes"… (orig y f tienen siempre el mismo largo)
        repeat(3) {
            val m = rxPrefijo.find(f)
            if (m != null && m.value.isNotEmpty()) {
                orig = orig.substring(m.value.length)
                f = f.substring(m.value.length)
            }
        }
        rxSufijo.find(f)?.let {
            orig = orig.substring(0, it.range.first)
            f = f.substring(0, it.range.first)
        }
        orig = orig.trim()
        f = f.trim()
        if (f.isEmpty()) return null

        if (esperandoBorrado) {
            esperandoBorrado = false
            if (rxSi.containsMatchIn(f)) {
                Memory.borrarTodo(ctx)
                return Reply("Listo, borré toda mi memoria.")
            }
            if (rxNo.containsMatchIn(f)) return Reply("Está bien, no borro nada.")
            // Otra cosa: se trata como una orden normal
        }

        return ayuda(f)
            ?: conversacion(f)
            ?: rendimiento(ctx, f)
            ?: vozModo(ctx, f)
            ?: voz(ctx, f)
            ?: animacion(ctx, f)
            ?: motorModo(ctx, f)
            ?: nucleos(ctx, f)
            ?: tareas(ctx, orig, f)
            ?: memoria(ctx, orig, f)
            ?: horaFecha(f)
            ?: bateria(ctx, f)
            ?: linterna(ctx, f)
            ?: volumen(ctx, f)
            ?: musica(ctx, f)
            ?: temporizador(ctx, f)
            ?: alarma(ctx, f)
            ?: llamar(ctx, orig, f)
            ?: ajustes(ctx, f)
            ?: ruta(ctx, orig, f)
            ?: buscar(ctx, orig, f)
            ?: abrirApp(ctx, f)
    }

    // ---------- Ayuda ----------

    private fun ayuda(f: String): Reply? {
        if (!rxAyuda.matches(f)) return null
        return Reply(
            "Puedo abrir apps, llamar a tus contactos, poner alarmas y temporizadores, " +
                "controlar el volumen, la música y la linterna, decirte la hora, la fecha y la batería, " +
                "buscar en internet, llevarte a un lugar y recordar cosas que me pidas."
        )
    }

    // ---------- Rendimiento ----------

    private const val AVISO_MAX_HILOS = "Elige entre 1 y %d hilos: dejo 2 núcleos libres para que el teléfono no se sature."
    private const val AVISO_REINICIO = "Reinicio el motor ahora; en unos 30 segundos estará listo."

    private fun rendimiento(ctx: Context, f: String): Reply? {
        if (rxHilosPregunta.matches(f)) {
            val tb = Ajustes.hilosLecturaEfectivos(ctx)
            return Reply(
                "Uso ${Ajustes.hilosEfectivos(ctx)} hilos para generar y " +
                    (if (tb > 0) "$tb" else "los mismos") + " para leer el texto."
            )
        }
        rxHilosLectura.find(f)?.let { m ->
            val v = m.groupValues[1]
            val n = if (v == "igual") 0 else v.toInt()
            if (v != "igual" && n !in 1..Ajustes.maxHilos()) return Reply(AVISO_MAX_HILOS.format(Ajustes.maxHilos()))
            Ajustes.fijarHilosLectura(ctx, n)
            EngineManager.reiniciarYa(ctx)
            return Reply(
                (if (n == 0) "Listo: leeré el texto con los mismos hilos." else "Listo: $n hilos para leer el texto.") +
                    " $AVISO_REINICIO"
            )
        }
        val m = rxHilos.find(f) ?: return null
        val n = m.groupValues[1].ifEmpty { m.groupValues[2] }.toInt()
        if (n !in 1..Ajustes.maxHilos()) return Reply(AVISO_MAX_HILOS.format(Ajustes.maxHilos()))
        Ajustes.fijarHilos(ctx, n)
        EngineManager.reiniciarYa(ctx)
        return Reply("Listo: ahora uso $n hilos. $AVISO_REINICIO")
    }

    // ---------- Núcleos del procesador ----------

    private fun nucleos(ctx: Context, f: String): Reply? {
        if (rxPrioridadBaja.matches(f)) {
            Ajustes.fijarPrioridadBaja(ctx, true)
            EngineManager.reiniciarYa(ctx)
            return Reply("Listo: el modelo trabaja con prioridad baja, así la pantalla y el teclado van fluidos. $AVISO_REINICIO")
        }
        if (rxPrioridadNormal.matches(f)) {
            Ajustes.fijarPrioridadBaja(ctx, false)
            EngineManager.reiniciarYa(ctx)
            return Reply("Listo: el modelo trabaja con prioridad normal. Puede ser más rápido, pero el teclado se puede sentir lento. $AVISO_REINICIO")
        }
        if (rxNucleosRapidos.matches(f)) {
            val c = Cpu.rapidos() ?: return Reply("No pude distinguir los núcleos rápidos de este teléfono.")
            Ajustes.fijarHilos(ctx, minOf(c.cantidad, Ajustes.maxHilos()))
            Ajustes.fijarNucleosFijos(ctx, true)
            EngineManager.reiniciarYa(ctx)
            return Reply("Detecté ${c.cantidad} núcleos rápidos de ${c.total}. Uso solo esos. $AVISO_REINICIO")
        }
        if (rxNucleosTodos.matches(f)) {
            Ajustes.fijarNucleosFijos(ctx, false)
            EngineManager.reiniciarYa(ctx)
            return Reply("Listo: Android reparte los hilos entre todos los núcleos como quiera. $AVISO_REINICIO")
        }
        return null
    }

    // ---------- Cuándo se apaga el motor ----------

    private fun motorModo(ctx: Context, f: String): Reply? {
        if (rxArranqueSi.matches(f)) {
            Ajustes.fijarArrancarAlEncender(ctx, true)
            return Reply("Listo: cargaré el motor solo cuando enciendas el teléfono, para que ya esté listo.")
        }
        if (rxArranqueNo.matches(f)) {
            Ajustes.fijarArrancarAlEncender(ctx, false)
            return Reply("Listo: no cargaré el motor al encender el teléfono.")
        }
        if (rxMotorSiempre.matches(f)) {
            Ajustes.fijarMinutosInactivo(ctx, 0)
            return Reply("Listo: mantendré el motor encendido para responder al instante. Si Android necesita memoria, lo libero solo.")
        }
        val m = rxMotorTiempo.find(f) ?: return null
        val n = m.groupValues[1].toInt()
        if (n !in 1..240) return Reply("Elige entre 1 y 240 minutos.")
        Ajustes.fijarMinutosInactivo(ctx, n)
        return Reply("Listo: apagaré el motor tras $n minutos sin uso. Después, la primera respuesta tarda más.")
    }

    // ---------- La voz: elegir voz, velocidad y tono ----------

    private const val SIN_VOCES = "Todavía no cargué las voces. Espera unos segundos y pregúntame de nuevo."

    private fun voz(ctx: Context, f: String): Reply? {
        val v = Assistant.voiceOut
        if (rxVozLista.matches(f)) {
            val l = v.listaVoces()
            if (l.isEmpty()) return Reply(SIN_VOCES)
            val actual = v.indiceVozActual()
            return Reply(
                "Tengo ${l.size} voces en español:\n" +
                    l.mapIndexed { i, e -> "- ${i + 1}: $e" + if (i == actual) " (la actual)" else "" }.joinToString("\n")
            )
        }
        if (rxVozCambiar.matches(f)) {
            val l = v.listaVoces()
            if (l.isEmpty()) return Reply(SIN_VOCES)
            if (l.size == 1) return Reply("Solo tengo una voz en español instalada. Di ajustes de voz para instalar otras.")
            val i = (v.indiceVozActual() + 1) % l.size
            v.elegirVoz(i)
            return Reply("Listo: ahora hablo con la voz ${i + 1}, ${l[i]}. Si no te gusta, di cambia la voz.")
        }
        rxVozNumero.find(f)?.let { m ->
            val l = v.listaVoces()
            if (l.isEmpty()) return Reply(SIN_VOCES)
            val n = m.groupValues[1].toInt()
            if (n !in 1..l.size) return Reply("Elige un número entre 1 y ${l.size}.")
            v.elegirVoz(n - 1)
            return Reply("Listo: ahora hablo con la voz $n, ${l[n - 1]}.")
        }
        if (rxVozRapida.matches(f)) {
            Ajustes.fijarVelocidadVoz(ctx, (Ajustes.velocidadVoz(ctx) + 0.15f).coerceAtMost(2.0f))
            v.ajustarVoz()
            return Reply("Listo: hablo más rápido.")
        }
        if (rxVozLenta.matches(f)) {
            Ajustes.fijarVelocidadVoz(ctx, (Ajustes.velocidadVoz(ctx) - 0.15f).coerceAtLeast(0.5f))
            v.ajustarVoz()
            return Reply("Listo: hablo más lento.")
        }
        if (rxVozGrave.matches(f)) {
            Ajustes.fijarTonoVoz(ctx, (Ajustes.tonoVoz(ctx) - 0.1f).coerceAtLeast(0.5f))
            v.ajustarVoz()
            return Reply("Listo: mi voz es más grave.")
        }
        if (rxVozAguda.matches(f)) {
            Ajustes.fijarTonoVoz(ctx, (Ajustes.tonoVoz(ctx) + 0.1f).coerceAtMost(1.5f))
            v.ajustarVoz()
            return Reply("Listo: mi voz es más aguda.")
        }
        if (rxVozNormal.matches(f)) {
            Ajustes.fijarVelocidadVoz(ctx, 1.0f)
            Ajustes.fijarTonoVoz(ctx, 1.0f)
            v.ajustarVoz()
            return Reply("Listo: velocidad y tono normales.")
        }
        if (rxVozAjustes.matches(f)) {
            return Reply(
                "Abriendo los ajustes de voz del teléfono, donde puedes instalar otras voces.",
                despues = { lanzar(ctx, Intent("com.android.settings.TTS_SETTINGS")) }
            )
        }
        return null
    }

    // ---------- Tareas: anotar, listar, pedirle a Jarvis que las haga ----------

    private fun tareas(ctx: Context, orig: String, f: String): Reply? {
        rxTareaAgregar.find(f)?.let { m ->
            val titulo = orig.substring(m.groups[1]!!.range.first).trim()
            if (titulo.isEmpty()) return Reply("¿Qué tarea quieres que anote?")
            val t = Tareas.agregar(ctx, titulo)
            val numero = Tareas.abiertas(ctx).indexOfFirst { it.id == t.id } + 1
            return Reply("Anotada: ${titulo.trimEnd('.')}. Si quieres que la haga yo, di haz la tarea $numero.")
        }
        if (rxTareaLista.matches(f)) {
            val l = Tareas.abiertas(ctx)
            if (l.isEmpty()) return Reply("No tienes tareas pendientes.")
            return Reply(
                "Tienes ${l.size} tareas pendientes:\n" +
                    l.take(6).mapIndexed { i, t -> "- ${i + 1}: ${t.titulo.trimEnd('.')}" }.joinToString("\n") +
                    if (l.size > 6) "\n…y ${l.size - 6} más." else ""
            )
        }
        if (rxTareaAbrir.matches(f)) {
            return Reply("Abriendo tus tareas.", despues = { lanzar(ctx, Intent(ctx, TareasActivity::class.java)) })
        }
        rxTareaHacer.find(f)?.let { m ->
            val n = m.groupValues[1].toInt()
            val t = Tareas.abiertas(ctx).getOrNull(n - 1) ?: return Reply("No encuentro la tarea $n.")
            Agente.ejecutar(ctx, t.id)
            return Reply("Me pongo con la tarea $n: ${t.titulo.trimEnd('.')}. Te aviso cuando termine.")
        }
        if (rxTareaSugerir.matches(f)) {
            Agente.sugerirAhora(ctx)
            return Reply("Reviso tus pendientes y te propongo tareas nuevas. Míralas en la pantalla de tareas.")
        }
        rxPiloto.find(f)?.let { m ->
            val on = m.groupValues[1] in setOf("activa", "enciende", "prende")
            Ajustes.fijarPiloto(ctx, on)
            return Reply(
                if (on) "Piloto automático activado: haré solo tus tareas pendientes cuando haya batería y no estés hablando conmigo."
                else "Piloto automático desactivado."
            )
        }
        rxCuriosidad.find(f)?.let { m ->
            val on = m.groupValues[1] in setOf("activa", "enciende", "prende")
            Ajustes.fijarCuriosidad(ctx, on)
            return Reply(
                if (on) "Curiosidad activada: al terminar propondré temas relacionados y, con el piloto, investigaré hasta ${Ajustes.MAX_SEGUIMIENTOS_DIA} por día."
                else "Curiosidad desactivada."
            )
        }
        // "Haz una presentación sobre…", "genera una imagen de…", "descarga…": se crea la tarea y arranca sola
        if (Agente.esEncargo(f)) {
            val t = Tareas.agregar(ctx, orig)
            Agente.ejecutar(ctx, t.id)
            return Reply("Entendido. Me pongo a trabajar en eso por mi cuenta y te aviso cuando termine. Puedes ver el avance en tareas.")
        }
        return null
    }

    // ---------- Animación del reactor ----------

    private fun animacion(ctx: Context, f: String): Reply? {
        if (rxAnimacionNo.matches(f)) {
            Ajustes.fijarAnimacion(ctx, false)
            return Reply("Listo: el reactor queda quieto para ahorrar batería.")
        }
        if (rxAnimacionSi.matches(f)) {
            Ajustes.fijarAnimacion(ctx, true)
            return Reply("Listo: el reactor vuelve a moverse.")
        }
        return null
    }

    // ---------- Cuándo habla ----------

    private fun vozModo(ctx: Context, f: String): Reply? {
        if (rxVozFinal.matches(f)) {
            Ajustes.fijarVozEnVivo(ctx, false)
            return Reply("Listo: mostraré la respuesta completa cuando termine de generarse y recién entonces la leeré en voz alta.")
        }
        if (rxVozVivo.matches(f)) {
            Ajustes.fijarVozEnVivo(ctx, true)
            return Reply("Listo: el texto aparecerá y lo iré leyendo a medida que lo genero.")
        }
        return null
    }

    // ---------- Conversación ----------

    private fun conversacion(f: String): Reply? {
        if (!rxNueva.matches(f)) return null
        Assistant.nuevaConversacion()
        return Reply("Listo, empecemos de cero.")
    }

    // ---------- Memoria ----------

    private fun memoria(ctx: Context, orig: String, f: String): Reply? {
        rxRecuerda.find(f)?.let { m ->
            val hecho = orig.substring(m.groups[1]!!.range.first).trim()
            if (hecho.isEmpty()) return Reply("¿Qué quieres que recuerde?")
            return if (Memory.agregar(ctx, hecho)) Reply("Anotado: ${hecho.trimEnd('.')}.")
            else Reply("Eso ya lo tenía anotado.")
        }
        if (rxListar.matches(f)) {
            val l = Memory.todos(ctx)
            if (l.isEmpty()) return Reply("Todavía no tengo nada anotado.")
            return Reply("Tengo anotado:\n" + l.joinToString("\n") { "- ${it.trimEnd('.')}." })
        }
        if (rxBorrarTodo.matches(f)) {
            if (Memory.todos(ctx).isEmpty()) return Reply("Mi memoria ya está vacía.")
            esperandoBorrado = true
            return Reply("¿Seguro que borro toda mi memoria? Di sí para confirmar.")
        }
        rxOlvida.find(f)?.let { m ->
            val q = orig.substring(m.groups[1]!!.range.first)
            val hallados = Memory.buscar(ctx, q)
            return when {
                hallados.isEmpty() -> Reply("No tengo nada anotado sobre eso.")
                hallados.size == 1 -> {
                    Memory.borrar(ctx, hallados[0])
                    Reply("Listo, olvidé: ${hallados[0].trimEnd('.')}.")
                }
                else -> Reply(
                    "Tengo varias cosas con eso, dime cuál:\n" +
                        hallados.take(5).joinToString("\n") { "- ${it.trimEnd('.')}." }
                )
            }
        }
        return null
    }

    // ---------- Hora, fecha, batería ----------

    private fun horaFecha(f: String): Reply? {
        if (rxHora.matches(f)) {
            val c = Calendar.getInstance()
            val h = c.get(Calendar.HOUR_OF_DAY)
            val verbo = if (h == 1) "Es la" else "Son las"
            return Reply("$verbo ${Parsers.hhmm(h, c.get(Calendar.MINUTE))}.")
        }
        if (rxFecha.matches(f)) return Reply("Hoy es ${Parsers.fechaHoy()}.")
        return null
    }

    private fun bateria(ctx: Context, f: String): Reply? {
        if (!rxBateria.matches(f)) return null
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val estado = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val cargando = estado == BatteryManager.BATTERY_STATUS_CHARGING ||
            estado == BatteryManager.BATTERY_STATUS_FULL
        return Reply("Tienes $pct% de batería" + if (cargando) " y está cargando." else ".")
    }

    // ---------- Linterna ----------

    private fun linterna(ctx: Context, f: String): Reply? {
        val m = rxLinterna.find(f) ?: return null
        val verbo = m.groupValues[1]
        val encender = when (verbo) {
            "" -> !linternaOn                           // sin verbo: alternar
            "apaga", "desactiva", "quita" -> false
            else -> true
        }
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = camaraId
            ?: cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }.also { camaraId = it }
            ?: return Reply("Este teléfono no tiene linterna.")
        cm.setTorchMode(id, encender)   // no requiere permiso de cámara
        linternaOn = encender
        return Reply(if (encender) "Linterna encendida." else "Linterna apagada.")
    }

    // ---------- Volumen ----------

    private fun volumen(ctx: Context, f: String): Reply? {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        rxVolSet.find(f)?.let { m ->
            val v = m.groupValues[1]
            val pct = when (v) {
                "maximo" -> 100
                "minimo" -> 0
                "medio", "mitad" -> 50
                else -> v.toInt().coerceIn(0, 100)
            }
            am.setStreamVolume(AudioManager.STREAM_MUSIC, pct * max / 100, AudioManager.FLAG_SHOW_UI)
            return Reply("Volumen al $pct%.")
        }
        val dir = when {
            rxVolSube.matches(f) -> AudioManager.ADJUST_RAISE
            rxVolBaja.matches(f) -> AudioManager.ADJUST_LOWER
            rxMute.matches(f) -> AudioManager.ADJUST_MUTE
            rxUnmute.matches(f) -> AudioManager.ADJUST_UNMUTE
            else -> return null
        }
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
        val pct = am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
        return Reply(
            when (dir) {
                AudioManager.ADJUST_MUTE -> "Silenciado."
                AudioManager.ADJUST_UNMUTE -> "Sonido activado."
                else -> "Volumen al $pct%."
            }
        )
    }

    // ---------- Control de música (teclas multimedia, sin permisos) ----------

    private fun musica(ctx: Context, f: String): Reply? {
        val tecla = when {
            rxPausa.matches(f) -> KeyEvent.KEYCODE_MEDIA_PAUSE
            rxPlay.matches(f) -> KeyEvent.KEYCODE_MEDIA_PLAY
            rxSig.matches(f) -> KeyEvent.KEYCODE_MEDIA_NEXT
            rxAnt.matches(f) -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> return null
        }
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, tecla))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, tecla))
        return Reply("Listo.")
    }

    // ---------- Temporizador y alarma (app Reloj del teléfono) ----------

    private fun temporizador(ctx: Context, f: String): Reply? {
        if (rxTempSinDato.matches(f)) return Reply("¿De cuánto tiempo lo quieres?")
        val m = rxTemp.find(f) ?: return null
        val dato = m.groupValues[1].ifEmpty { m.groupValues[2] }
        val seg = Parsers.duracionSeg(dato) ?: return null   // sin duración: puede ser una alarma por hora
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seg)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return Reply("Temporizador de ${Parsers.duracionTexto(seg)}.", despues = { lanzar(ctx, i) })
    }

    private fun alarma(ctx: Context, f: String): Reply? {
        val m = rxAlarma.find(f) ?: return null
        val hm = Parsers.hora(m.groupValues[1]) ?: return Reply("¿A qué hora la pongo?")
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hm.first)
            .putExtra(AlarmClock.EXTRA_MINUTES, hm.second)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return Reply("Alarma a las ${Parsers.hhmm(hm.first, hm.second)}.", despues = { lanzar(ctx, i) })
    }

    // ---------- Llamar (abre el marcador; el usuario toca llamar) ----------

    private fun llamar(ctx: Context, orig: String, f: String): Reply? {
        val m = rxLlamar.find(f) ?: return null
        val destino = orig.substring(m.groups[1]!!.range.first).trim()
        val soloNumero = destino.all { it.isDigit() || it in " +-()" } && destino.count { it.isDigit() } >= 3
        if (soloNumero) {
            return marcador(ctx, destino.filter { it.isDigit() || it == '+' }, destino)
        }
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return Reply("", permiso = Manifest.permission.READ_CONTACTS)
        }
        val q = Parsers.fold(destino).trim()
        if (q.isEmpty()) return null
        var mejor: Pair<String, String>? = null
        var mejorPuntaje = 0
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val nombre = c.getString(0) ?: continue
                val n = Parsers.fold(nombre)
                val p = when {
                    n == q -> 3
                    n.startsWith(q) -> 2
                    n.contains(q) -> 1
                    else -> 0
                }
                if (p > mejorPuntaje) {
                    mejorPuntaje = p
                    mejor = nombre to (c.getString(1) ?: "")
                }
            }
        }
        val (nombre, numero) = mejor ?: return Reply("No encontré a $destino en tus contactos.")
        if (numero.isBlank()) return Reply("$nombre no tiene número guardado.")
        return marcador(ctx, numero, nombre)
    }

    private fun marcador(ctx: Context, numero: String, nombre: String): Reply {
        val i = Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(numero)))
        return Reply("Abriendo el marcador para llamar a $nombre.", despues = { lanzar(ctx, i) })
    }

    // ---------- Ajustes, mapas, búsqueda ----------

    private fun ajustes(ctx: Context, f: String): Reply? {
        val m = rxAjustes.find(f) ?: return null
        val verbo = m.groupValues[1]
        val que = m.groupValues[2]
        val (accion, nombre) = when {
            que.contains("wi") -> Settings.ACTION_WIFI_SETTINGS to "el wifi"
            que == "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS to "el bluetooth"
            else -> Settings.ACTION_SETTINGS to "los ajustes"
        }
        val abre = verbo in setOf("abre", "abri", "abrir", "ve a", "ir a")
        val texto = if (abre) "Abriendo $nombre."
        else "Android no me deja cambiar $nombre desde una app. Te abro los ajustes."
        return Reply(texto, despues = { lanzar(ctx, Intent(accion)) })
    }

    private fun ruta(ctx: Context, orig: String, f: String): Reply? {
        val m = rxRuta.find(f) ?: return null
        val lugar = orig.substring(m.groups[1]!!.range.first).trim()
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(lugar)))
        return Reply("Abriendo el mapa para ir a $lugar.", despues = { lanzar(ctx, i) })
    }

    private fun buscar(ctx: Context, orig: String, f: String): Reply? {
        val m = rxBuscar.find(f) ?: return null
        val q = orig.substring(m.groups[1]!!.range.first).trim()
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q)))
        return Reply("Buscando $q.", despues = { lanzar(ctx, i) })
    }

    // ---------- Abrir apps ----------

    private class App(val etiqueta: String, val plegada: String, val paquete: String)

    private fun abrirApp(ctx: Context, f: String): Reply? {
        val m = rxAbrir.find(f) ?: return null
        val q = m.groupValues[1].trim()
        if (q.length < 2) return null
        val pm = ctx.packageManager
        val lanzables = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        ).map {
            val et = it.loadLabel(pm).toString()
            App(et, Parsers.fold(et), it.activityInfo.packageName)
        }
        var mejor: App? = null
        var mejorPuntaje = 0
        for (buscado in listOfNotNull(q, alias[q])) {
            for (a in lanzables) {
                val p = when {
                    a.plegada == buscado -> 4
                    a.plegada.startsWith(buscado) -> 3
                    buscado.length >= 3 && a.plegada.contains(buscado) -> 2
                    a.plegada.length >= 4 && buscado.contains(a.plegada) -> 1
                    else -> 0
                }
                // A igual puntaje se prefiere el nombre más corto ("WhatsApp" antes que "WhatsApp Business")
                val mejorActual = mejor
                if (p > mejorPuntaje || (p == mejorPuntaje && p > 0 && mejorActual != null &&
                        a.plegada.length < mejorActual.plegada.length)
                ) {
                    mejorPuntaje = p
                    mejor = a
                }
            }
        }
        val app = mejor ?: return Reply("No encontré una app llamada $q.")
        val i = pm.getLaunchIntentForPackage(app.paquete)
            ?: return Reply("No pude abrir ${app.etiqueta}.")
        return Reply("Abriendo ${app.etiqueta}.", despues = { lanzar(ctx, i) })
    }

    // ---------- Lanzar otra app ----------

    private fun lanzar(ctx: Context, i: Intent) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!Assistant.visible) {
            // Android 10+ no deja abrir apps desde el segundo plano: se deja una notificación para tocar
            Assistant.notificarAccion(i)
            return
        }
        try {
            ctx.startActivity(i)
        } catch (e: Exception) {
            Toast.makeText(ctx, "No encontré una app para eso.", Toast.LENGTH_LONG).show()
        }
    }
}
