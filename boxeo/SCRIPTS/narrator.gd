extends Node
## Narración en tiempo real con las voces TTS del dispositivo.
## Roles con voz/tono/velocidad distintos: presentador, comentarista A, comentarista B, entrenador rojo, entrenador azul.
## El análisis de AMBOS entrenadores lo genera Jarvis (con las estadísticas del round y lo que la IA aprendió del jugador).
## Sin voces instaladas: no hace nada (el HUD sigue mostrando los textos).

const NOMBRES := {"pres": "Leo Marín", "a": "Paula Rey", "b": "Gonzalo Pardo", "red": "Ramón Ibarra", "blue": "Inés Valdés", "jarvis": "Jarvis"}
# rol -> [pitch, rate]
const AJUSTE := {"pres": [0.85, 0.95], "a": [1.15, 1.08], "b": [0.8, 1.0], "red": [0.7, 0.95], "blue": [1.3, 1.0], "jarvis": [0.86, 0.94]}

var mgr
var hud
var a
var b
var voces: Array = []
var enabled := true
var last_live := -10.0
var clock := 0.0
var hits := 0
var last_round_stats := {"landed": [0, 0], "thrown": [0, 0]}
var cola: Array = []


func setup(mgr_, a_, b_) -> void:
	mgr = mgr_
	a = a_
	b = b_
	_cargar_voces()
	mgr.announce.connect(_on_announce)
	mgr.round_changed.connect(_on_round)
	mgr.fx.connect(_on_fx)
	mgr.fight_finished.connect(_on_finished)


func intro() -> void:
	decir("pres", "Damas y caballeros, esta noche, %s contra %s." % [String(a.data["name"]), String(b.data["name"])])
	decir("a", "Ojo: el rival lo controla Jarvis, y va aprendiendo cada patrón de %s." % String(a.data["name"]))
	if voces.is_empty():
		_caption("Aviso", "Tu celular no tiene voces de texto a voz disponibles. Instala o activa un motor TTS en Ajustes.", 6.0)


## En Android las voces se cargan tarde: se reintenta en cada línea hasta que aparezcan.
func _cargar_voces() -> void:
	if not DisplayServer.has_feature(DisplayServer.FEATURE_TEXT_TO_SPEECH):
		return
	voces = DisplayServer.tts_get_voices_for_language("es")
	if voces.is_empty():
		voces = DisplayServer.tts_get_voices_for_language("")
	if voces.is_empty():
		voces = []
		for v in DisplayServer.tts_get_voices():
			voces.append(v["id"])


func _caption(who: String, texto: String, seg: float) -> void:
	if hud != null and is_instance_valid(hud):
		hud.caption(who, texto, seg)


func _process(delta: float) -> void:
	clock += delta


func _exit_tree() -> void:
	if DisplayServer.has_feature(DisplayServer.FEATURE_TEXT_TO_SPEECH):
		DisplayServer.tts_stop()


## Voz distinta por rol: reparte las voces disponibles y diferencia con tono y velocidad.
func decir(rol: String, texto: String, urgente := false, subt := true) -> void:
	if subt:
		_caption(String(NOMBRES[rol]), texto, 3.0 + texto.length() * 0.05)
	if voces.is_empty():
		_cargar_voces()
	if voces.is_empty():
		return
	var roles := AJUSTE.keys()
	var i := roles.find(rol)
	var voz: String = String(voces[i % voces.size()])
	var aj: Array = AJUSTE[rol]
	if urgente:
		DisplayServer.tts_stop()
	DisplayServer.tts_speak(texto, voz, 80, aj[0], aj[1], 0, false)


func _libre() -> bool:
	return not DisplayServer.tts_is_speaking() and clock - last_live > 2.5


func _on_round(n: int, total: int) -> void:
	decir("pres", "Round %d de %d." % [n, total], true)


func _on_announce(text: String, _s: float) -> void:
	if text.begins_with("Fin del round"):
		_analisis_entrenadores()


func _on_fx(kind: String, _pos: Vector3, extra: Dictionary) -> void:
	match kind:
		"knockdown":
			last_live = clock
			decir("a", ["¡Al suelo! ¡Qué golpe tan limpio!", "¡Cayó! ¡Esto es increíble!"][randi() % 2], true)
		"hit_heavy":
			if _libre():
				last_live = clock
				decir("a", ["¡Qué derechazo!", "¡Golpe durísimo!", "¡Eso tiene que doler!"][randi() % 3])
		"hit_light":
			hits += 1
			if _libre() and hits % 4 == 0:
				last_live = clock
				decir("b", ["Buen jab, bien medido.", "Sigue sumando puntos.", "Castiga con la distancia."][randi() % 3])
		"block":
			if _libre() and randi() % 4 == 0:
				last_live = clock
				decir("b", "Se cubre bien, no deja pasar nada.")
		"count_tick":
			pass


## Jarvis analiza el round para los dos entrenadores (esquina roja = jugador, azul = rival).
func _analisis_entrenadores() -> void:
	var la: int = mgr.landed[0] - int(last_round_stats["landed"][0])
	var lb: int = mgr.landed[1] - int(last_round_stats["landed"][1])
	var ta: int = mgr.thrown[0] - int(last_round_stats["thrown"][0])
	var tb: int = mgr.thrown[1] - int(last_round_stats["thrown"][1])
	last_round_stats = {"landed": mgr.landed.duplicate(), "thrown": mgr.thrown.duplicate()}
	var acc_a := int(100.0 * la / maxf(1.0, ta))
	var acc_b := int(100.0 * lb / maxf(1.0, tb))
	var yo := String(a.data["name"])
	var rival := String(b.data["name"])

	# Esquina roja (jugador)
	var rojo := "Jarvis informa a la esquina roja. Conectaste %d golpes, %d por ciento de acierto." % [la, acc_a]
	if mgr.kd_round[0] > 0:
		rojo += " Te derribaron, protege la cabeza."
	elif acc_a < 35:
		rojo += " Estás fallando mucho, mide más la distancia."
	elif lb > la:
		rojo += " El rival conecta más, cierra la guardia."
	else:
		rojo += " Vas bien, mantén el ritmo."
	decir("red", rojo, false, false)

	# Esquina azul (rival controlado por Jarvis): usa lo aprendido
	var azul := "Jarvis informa a la esquina azul. %s conecta %d, %d por ciento." % [rival, lb, acc_b]
	var patron := _patron_del_jugador()
	if patron != "":
		azul += " Ya detecté que %s suele hacer %s. Lo voy a anticipar." % [yo, patron]
	var ai = b.ai
	if ai != null:
		if ai.foe_stats["blocks"] > ai.foe_stats["attacks"]:
			azul += " Se tapa mucho: iré al cuerpo."
		elif ai.foe_stats["dodges"] > ai.foe_stats["attacks"] * 0.5:
			azul += " Esquiva bastante: probaré ganchos."
	decir("blue", azul, false, false)
	_caption("Jarvis · análisis", "ROJA: " + rojo.substr(rojo.find(". ") + 2) + "\nAZUL: " + azul.substr(azul.find(". ") + 2), 9.0)


## Secuencia más repetida del jugador, aprendida por la IA (boxer_ai.trans).
func _patron_del_jugador() -> String:
	var ai = b.ai
	if ai == null:
		return ""
	var mejor := ""
	var veces := 2
	for ctx in ai.trans:
		for sig in ai.trans[ctx]:
			var n: int = int(ai.trans[ctx][sig])
			if n > veces:
				veces = n
				mejor = "%s y luego %s" % [String(ctx).replace(">", " y luego "), String(sig)]
	return mejor.replace("_", " ")


func _on_finished(res: Dictionary) -> void:
	var w := String(res.get("winner", "draw"))
	var rival := String(b.data["name"])
	var yo := String(a.data["name"])
	var frase := "Empate. Qué pelea."
	if w == "a":
		frase = "¡Gana %s por %s!" % [yo, String(res.get("method", ""))]
	elif w == "b":
		frase = "¡Gana %s por %s! Jarvis se lleva la pelea." % [rival, String(res.get("method", ""))]
	decir("pres", frase, true)
	if w == "b":
		decir("jarvis", "Gracias por los patrones. Revancha cuando quieras.")
