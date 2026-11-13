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
	_live(delta)


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
	lull = 0.0
	if n == 1:
		decir("a", "Suena la campana. %s, esquina roja, contra %s, esquina azul." % [_nom(a), _nom(b)])
	elif mgr.landed[0] > mgr.landed[1]:
		decir("b", "%s viene mejor. %s tiene que cambiar de plan." % [_nom(a), _nom(b)])
	else:
		decir("b", "%s manda en la pelea. %s debe salir a buscarlo." % [_nom(b), _nom(a)])
	if n == total:
		decir("a", "¡Último round! Todo se define aquí.", true)


func _on_announce(text: String, _s: float) -> void:
	if text.begins_with("Fin del round"):
		_analisis_entrenadores()
	elif text.is_valid_int():
		decir("ref", text, true, false)             # cuenta del árbitro: 1, 2, 3...
	elif text == "¡BOX!":
		decir("ref", "¡Box!", false, false)
	elif text == "¡Arriba!":
		decir("ref", "¡Arriba! Continúen.", false, false)


var ultimo := {}           # última frase usada por categoría (evita repetir)
var aviso_t := 0.0
var flags := {}
var lull := 0.0


func _pick(cat: String, opciones: Array) -> String:
	var t: String = String(opciones[randi() % opciones.size()])
	var n := 0
	while t == String(ultimo.get(cat, "")) and n < 6:
		t = String(opciones[randi() % opciones.size()])
		n += 1
	ultimo[cat] = t
	return t


func _nom(bx) -> String:
	var full := String(bx.data["name"])
	var parts := full.split(" ")
	return String(parts[parts.size() - 1]) if parts.size() > 1 else full


func _foe(bx):
	return b if bx == a else a


## Comentario de relleno y avisos del estado de la pelea (tiempo, cansancio, daño, ritmo).
func _live(delta: float) -> void:
	if mgr == null or mgr.phase != 1:
		return
	aviso_t += delta
	lull += delta
	if aviso_t < 1.0:
		return
	aviso_t = 0.0
	var key := "r%d" % mgr.round_n
	if mgr.time_left < 11.0 and not flags.has(key + "t"):
		flags[key + "t"] = true
		if _libre():
			last_live = clock
			decir("pres", _pick("t", ["Diez segundos para el final del round.", "Se acaba el round, ¡diez segundos!"]))
	for bx in [a, b]:
		var k2: String = key + String(bx.data["name"])
		if bx.health < 30.0 and not flags.has(k2 + "h"):
			flags[k2 + "h"] = true
			if _libre():
				last_live = clock
				decir("b", _pick("h", ["%s está muy tocado, ojo a la guardia.", "%s no está bien: las piernas ya no responden.", "Cuidado con %s, ese castigo se acumula."]) % _nom(bx))
		if bx.stamina < 18.0 and not flags.has(k2 + "s"):
			flags[k2 + "s"] = true
			if _libre():
				last_live = clock
				decir("b", _pick("s", ["A %s se le acaba el aire.", "%s respira por la boca, está agotado.", "Mucho desgaste en %s, necesita descansar."]) % _nom(bx))
	if lull > 9.0 and _libre():
		lull = 0.0
		last_live = clock
		var d: float = absf(a.position.z - b.position.z)
		var frases: Array = []
		if d > 1.6:
			frases = ["Se estudian a distancia, nadie quiere arriesgar.", "Mucho tanteo en el centro del ring.", "%s y %s miden la distancia." % [_nom(a), _nom(b)]]
		elif mgr.landed[0] > mgr.landed[1] + 3:
			frases = ["%s va ganando la pelea con la ventaja en golpes limpios." % _nom(a), "El ritmo lo marca %s, y %s no encuentra respuesta." % [_nom(a), _nom(b)]]
		elif mgr.landed[1] > mgr.landed[0] + 3:
			frases = ["%s domina el intercambio, y %s necesita reaccionar." % [_nom(b), _nom(a)], "Jarvis está leyendo muy bien a %s." % _nom(a)]
		else:
			frases = ["Pelea muy pareja, cualquiera puede llevarse el round.", "Intercambio parejo, el público está de pie."]
		decir("a" if randi() % 2 == 0 else "b", _pick("lull", frases))


func _on_fx(kind: String, _pos: Vector3, extra: Dictionary) -> void:
	var df = extra.get("defender", null)
	var at = _foe(df) if df != null else null
	var nd := _nom(df) if df != null else "el rival"
	var na := _nom(at) if at != null else "su rival"
	match kind:
		"knockdown":
			last_live = clock
			lull = 0.0
			decir("a", _pick("kd", ["¡Al suelo %s! ¡Qué golpe tan limpio de %s!" % [nd, na], "¡Cayó %s! ¡Esto es increíble!" % nd, "¡Derribo! %s lo mandó a la lona." % na]), true)
			decir("b", _pick("kd2", ["Ese golpe venía preparado desde hace rato.", "Se descuidó la guardia y lo pagó caro.", "Fíjate cómo cargó el cuerpo en ese golpe."]))
		"hit_heavy":
			if _libre():
				last_live = clock
				lull = 0.0
				decir("a", _pick("hh", ["¡Qué derechazo de %s!" % na, "¡Golpe durísimo a %s!" % nd, "¡Eso tiene que doler, %s lo sintió!" % nd, "¡Impacto limpio de %s!" % na]))
		"hit_light":
			hits += 1
			lull = 0.0
			if _libre() and hits % 4 == 0:
				last_live = clock
				decir("b", _pick("hl", ["Buen jab de %s, bien medido." % na, "%s sigue sumando puntos." % na, "%s castiga con la distancia." % na, "Gran trabajo de pies de %s." % na]))
		"block":
			if _libre() and randi() % 4 == 0:
				last_live = clock
				decir("b", _pick("bl", ["%s se cubre bien, no deja pasar nada." % nd, "Guardia cerrada de %s." % nd, "%s lee el golpe y lo bloquea." % nd]))
		"slip":
			if _libre() and randi() % 3 == 0:
				last_live = clock
				decir("a", _pick("sl", ["¡Qué esquiva de %s!" % nd, "%s ni se despeina, ¡qué reflejos!" % nd, "El golpe de %s se pierde en el aire." % na]))
		"parry":
			if _libre():
				last_live = clock
				decir("b", _pick("pa", ["¡Contragolpe de %s!" % nd, "%s desvía y responde, muy inteligente." % nd]))
		"combo":
			var n := int(extra.get("count", 3))
			var ow = extra.get("owner", null)
			if n >= 3 and _libre():
				last_live = clock
				lull = 0.0
				var nm := _nom(ow) if ow != null else "El peleador"
				decir("a", _pick("cb", ["¡Combinación de %d golpes de %s!" % [n, nm], "¡%s se enciende! ¡%d seguidos!" % [nm, n], "¡No para %s! Lleva %d golpes en racha." % [nm, n]]))
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
	if b.ai != null:
		decir("jarvis", b.ai.resumen())
	if w == "b":
		decir("jarvis", "Gracias por los patrones. Revancha cuando quieras.")
