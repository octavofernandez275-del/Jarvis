extends Node
## Voces del juego (texto a voz del sistema Android).
##
## Por qué existe (los fallos que arregla):
##  1) El motor de voz de Android arranca de forma ASÍNCRONA: si se habla en el primer instante, la frase se pierde.
##     -> se "calienta" al abrir el juego y las frases esperan en una cola hasta que haya voces.
##  2) Android describe el idioma como "spa_ESP" (3 letras), no "es": el filtro anterior no encontraba ninguna voz en español
##     y repartía voces de cualquier idioma. -> se aceptan es / spa / es-XX y se prefieren las voces locales (sin internet).
##  3) Si una voz concreta falla, se reintenta con la voz por defecto del sistema (en vez de quedarse mudo).
##  4) Cola propia, sin acumular frases viejas, y un vigilante que desatasca el motor si se queda colgado.
## Diagnóstico en pantalla: info() / estado() / pista() (se muestran en el HUD y en Menú > Probar voces).

# rol -> [tono, velocidad]
const AJUSTE := {
	"pres": [0.85, 0.95], "a": [1.15, 1.08], "b": [0.8, 1.0], "red": [0.7, 0.95],
	"blue": [1.3, 1.0], "jarvis": [0.86, 0.94], "ref": [0.95, 1.12],
}
const ORDEN := ["pres", "a", "b", "red", "blue", "jarvis", "ref"]

var soportado := false
var voces_todas: Array = []      # ids de todas las voces que ve Android
var voces_es: Array = []         # ids de voces en español (las mejores primero)
var asignadas := {}              # rol -> id de voz ("" = voz por defecto del sistema)
var cola: Array = []
var actual: Dictionary = {}      # frase que se está diciendo ahora
var siguiente_id := 0
var callbacks_ok := false        # ¿llegó alguna vez un aviso de inicio/fin del motor?
var usar_defecto := false        # si las voces elegidas fallan, se usa la del sistema
var exitos := 0
var fallos := 0
var fallos_seguidos := 0
var ultimo_error := ""
var mudo := false
var _t_primera := 0
var _t_recarga := 0


func _ready() -> void:
	process_mode = Node.PROCESS_MODE_ALWAYS     # sigue funcionando en cámara lenta y pausas
	soportado = DisplayServer.has_feature(DisplayServer.FEATURE_TEXT_TO_SPEECH)
	if not soportado:
		ultimo_error = "Este dispositivo no ofrece texto a voz."
		return
	DisplayServer.tts_set_utterance_callback(DisplayServer.TTS_UTTERANCE_STARTED, Callable(self, "_on_started"))
	DisplayServer.tts_set_utterance_callback(DisplayServer.TTS_UTTERANCE_ENDED, Callable(self, "_on_ended"))
	DisplayServer.tts_set_utterance_callback(DisplayServer.TTS_UTTERANCE_CANCELED, Callable(self, "_on_canceled"))
	cargar_voces()


# ---------------- voces disponibles ----------------

func _puntaje(id: String) -> int:
	var s := 0
	var l := id.to_lower()
	if l.contains("local"):
		s += 2
	if l.contains("network") or l.contains("online") or l.contains("cloud"):
		s -= 3
	return s


func _es_espanol(id: String, lang: String) -> bool:
	var g := lang.to_lower().replace("-", "_")
	var i := id.to_lower().replace("-", "_")
	return g.begins_with("es") or g.begins_with("spa") or i.begins_with("es_") or i.contains("_es_") or i.contains("spanish") or i.contains("espa")


func cargar_voces() -> void:
	if not soportado:
		return
	voces_todas = []
	voces_es = []
	var lista: Array = DisplayServer.tts_get_voices()
	for v in lista:
		var d: Dictionary = v
		var id := String(d.get("id", ""))
		if id == "":
			continue
		voces_todas.append(id)
		if _es_espanol(id, String(d.get("language", ""))):
			voces_es.append(id)
	voces_es.sort_custom(func(x, y): return _puntaje(String(x)) > _puntaje(String(y)))
	asignadas = {}
	var n := voces_es.size()
	for i in range(ORDEN.size()):
		asignadas[ORDEN[i]] = "" if n == 0 else String(voces_es[i % n])


func voces_listas() -> bool:
	return not voces_todas.is_empty()


# ---------------- hablar ----------------

func hablar(rol: String, texto: String, urgente := false) -> void:
	if not soportado or mudo or texto.strip_edges() == "":
		return
	if not AJUSTE.has(rol):
		rol = "pres"
	if cola.is_empty() and actual.is_empty():
		_t_primera = Time.get_ticks_msec()
	if urgente:
		cola.clear()
		if not actual.is_empty():
			DisplayServer.tts_stop()
			actual = {}
	elif cola.size() >= 4:
		cola.pop_front()          # no acumular narración vieja
	cola.append({"rol": rol, "texto": texto})
	_bombear()


## ¿Está hablando o tiene algo en cola? (para no pisar la narración en vivo)
func ocupada() -> bool:
	return not actual.is_empty() or not cola.is_empty()


func parar() -> void:
	cola.clear()
	actual = {}
	if soportado:
		DisplayServer.tts_stop()


func _bombear() -> void:
	if cola.is_empty() or not actual.is_empty() or not soportado:
		return
	# el motor de Android tarda en arrancar: se espera a que aparezcan voces (máx. 5 s) y entonces se habla igual
	if voces_todas.is_empty() and Time.get_ticks_msec() - _t_primera < 5000:
		return
	var it: Dictionary = cola.pop_front()
	var rol := String(it["rol"])
	var voz := ""
	if not usar_defecto and asignadas.has(rol):
		voz = String(asignadas[rol])
	siguiente_id += 1
	var aj: Array = AJUSTE[rol]
	actual = {"id": siguiente_id, "item": it, "t": Time.get_ticks_msec(), "empezo": false, "voz": voz}
	DisplayServer.tts_speak(String(it["texto"]), voz, 90, float(aj[0]), float(aj[1]), siguiente_id, false)


# ---------------- avisos del motor ----------------

func _on_started(id: int) -> void:
	callbacks_ok = true
	if not actual.is_empty() and int(actual["id"]) == id:
		actual["empezo"] = true
		fallos_seguidos = 0


func _on_ended(id: int) -> void:
	callbacks_ok = true
	if not actual.is_empty() and int(actual["id"]) == id:
		exitos += 1
		fallos_seguidos = 0
		actual = {}
		_bombear()


func _on_canceled(id: int) -> void:
	callbacks_ok = true
	if not actual.is_empty() and int(actual["id"]) == id:
		# cancelada sin haber empezado = error de la voz elegida
		if not bool(actual["empezo"]):
			_registrar_fallo()
			return
		actual = {}
		_bombear()


func _registrar_fallo() -> void:
	var it: Dictionary = actual.get("item", {})
	var voz := String(actual.get("voz", ""))
	actual = {}
	fallos += 1
	fallos_seguidos += 1
	if voz != "" and not usar_defecto:
		# la voz elegida no funciona: se reintenta la misma frase con la voz por defecto del sistema
		usar_defecto = true
		ultimo_error = "La voz '%s' falló; usando la voz por defecto." % voz
		if not it.is_empty():
			cola.push_front(it)
	else:
		ultimo_error = "El motor de voz no responde (¿falta el motor o la voz en español?)."
	_bombear()


# ---------------- vigilante ----------------

func _process(_delta: float) -> void:
	if not soportado:
		return
	var ahora := Time.get_ticks_msec()
	# Android carga las voces tarde: se reintenta cada segundo hasta que salgan (o 30 s)
	if voces_todas.is_empty() and ahora - _t_recarga > 1000:
		_t_recarga = ahora
		cargar_voces()
	if actual.is_empty():
		_bombear()
		return
	var dt: int = ahora - int(actual["t"])
	var largo := String(actual["item"]["texto"]).length()
	if callbacks_ok:
		# el motor avisa: sin aviso de inicio en 3 s = falló; atascado más de lo razonable = se corta
		if not bool(actual["empezo"]) and dt > 3000:
			DisplayServer.tts_stop()
			_registrar_fallo()
		elif dt > 8000 + largo * 150:
			DisplayServer.tts_stop()
			actual = {}
			_bombear()
	else:
		# este motor no manda avisos: se vigila con tts_is_speaking() y un tiempo máximo
		if dt > 700 and not DisplayServer.tts_is_speaking():
			actual = {}
			_bombear()
		elif dt > 6000 + largo * 130:
			DisplayServer.tts_stop()
			actual = {}
			_bombear()


# ---------------- diagnóstico ----------------

## "ok" (ha hablado), "espera" (aún no se ha usado), "error" (no suena).
func estado() -> String:
	if not soportado:
		return "error"
	if exitos > 0:
		return "ok"
	if fallos_seguidos >= 2 or (fallos > 0 and exitos == 0):
		return "error"
	return "espera"


func pista() -> String:
	if not soportado:
		return "Sin texto a voz en este dispositivo."
	if voces_todas.is_empty():
		return "Android no devuelve voces. Ajustes > Sistema > Idioma > Salida de texto a voz: instala «Servicios de voz de Google» y descarga el español."
	if voces_es.is_empty():
		return "No hay voces en español instaladas. Descárgalas en Ajustes > Salida de texto a voz > Idioma."
	return ""


func info() -> String:
	var t := "Texto a voz: %s\n" % ("sí" if soportado else "NO")
	t += "Voces vistas: %d  ·  en español: %d\n" % [voces_todas.size(), voces_es.size()]
	t += "Avisos del motor: %s  ·  dichas: %d  ·  fallos: %d\n" % ["sí" if callbacks_ok else "no", exitos, fallos]
	if usar_defecto:
		t += "Modo: voz por defecto del sistema\n"
	for i in range(mini(3, voces_es.size())):
		t += "  es: %s\n" % String(voces_es[i])
	if ultimo_error != "":
		t += "Último error: %s\n" % ultimo_error
	var p := pista()
	if p != "":
		t += p + "\n"
	return t


## Prueba: una frase corta con cada rol, una tras otra.
func probar() -> void:
	usar_defecto = false
	fallos = 0
	fallos_seguidos = 0
	cargar_voces()
	parar()
	_t_primera = Time.get_ticks_msec()
	for par in [["pres", "Damas y caballeros, esto es una prueba de voz."], ["a", "Paula Rey, comentarista, a la escucha."],
			["b", "Gonzalo Pardo, análisis técnico."], ["red", "Esquina roja, preparada."],
			["blue", "Esquina azul, lista."], ["jarvis", "Jarvis en línea."], ["ref", "¡Box!"]]:
		cola.append({"rol": par[0], "texto": par[1]})
	_bombear()
