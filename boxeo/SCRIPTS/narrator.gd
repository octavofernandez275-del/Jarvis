extends Node
## Narración en tiempo real con las voces TTS del dispositivo.
## Roles con voz/tono/velocidad distintos: presentador, comentarista A, comentarista B, entrenador rojo, entrenador azul.
## El análisis de AMBOS entrenadores lo genera Jarvis (con las estadísticas del round y lo que la IA aprendió del jugador).
## La voz real la maneja voz.gd (cola, reintentos, voces en español). Aquí solo se decide QUÉ se dice y CUÁNDO.
## Sin voces instaladas: no suena nada, pero los subtítulos siguen saliendo en el HUD.

const NOMBRES := {"pres": "Leo Marín", "a": "Paula Rey", "b": "Gonzalo Pardo", "red": "Ramón Ibarra", "blue": "Inés Valdés", "jarvis": "Jarvis", "ref": "Árbitro"}

var mgr
var hud
var a
var b
var voz            # voz.gd (compartida entre combates)
var enabled := true
var last_live := -10.0
var clock := 0.0
var hits := 0
var last_round_stats := {"landed": [0, 0], "thrown": [0, 0]}
var cola: Array = []


func setup(mgr_, a_, b_, voz_ = null) -> void:
	mgr = mgr_
	a = a_
	b = b_
	voz = voz_
	mgr.announce.connect(_on_announce)
	mgr.round_changed.connect(_on_round)
	mgr.fx.connect(_on_fx)
	mgr.fight_finished.connect(_on_finished)


func intro() -> void:
	decir("pres", "Damas y caballeros, esta noche, %s contra %s." % [String(a.data["name"]), String(b.data["name"])])
	decir("a", "Ojo: el rival lo controla Jarvis, y va aprendiendo cada patrón de %s." % String(a.data["name"]))
	if voz != null and voz.estado() == "error":
		_caption("Voz", voz.pista() if voz.pista() != "" else voz.ultimo_error, 8.0)


func _caption(who: String, texto: String, seg: float) -> void:
	if hud != null and is_instance_valid(hud):
		hud.caption(who, texto, seg)


func _process(delta: float) -> void:
	clock += delta


func _exit_tree() -> void:
	if voz != null and is_instance_valid(voz):
		voz.parar()


## Subtítulo + voz del rol (cada rol tiene voz, tono y velocidad distintos; ver voz.gd).
func decir(rol: String, texto: String, urgente := false, subt := true) -> void:
	if subt:
		_caption(String(NOMBRES[rol]), texto, 3.0 + texto.length() * 0.05)
	if voz != null:
		voz.hablar(rol, texto, urgente)


func _libre() -> bool:
	return (voz == null or not voz.ocupada()) and clock - last_live > 2.5


func _on_round(n: int, total: int) -> void:
	decir("pres", "Round %d de %d." % [n, total], true)


func _on_announce(text: String, _s: float) -> void:
	if text.begins_with("Fin del round"):
		_analisis_entrenadores()
	elif text.is_valid_int():
		decir("ref", text, true, false)             # cuenta del árbitro: 1, 2, 3...
	elif text == "¡BOX!":
		decir("ref", "¡Box!", false, false)
	elif text == "¡Arriba!":
		decir("ref", "¡Arriba! Continúen.", false, false)


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
