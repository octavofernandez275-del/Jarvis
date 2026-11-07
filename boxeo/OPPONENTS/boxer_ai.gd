extends RefCounted
## IA del rival: personalidad (ai_profiles.json) + memoria de patrones del jugador + adaptación durante el combate.
##
## Qué hace:
##  - Reconoce el golpe del jugador mientras "arranca" (startup) y decide bloquear, esquivar o recibirlo.
##  - Aprende secuencias del jugador (contextos de 1 y 2 acciones) y las anticipa: reacciona más rápido y con más acierto.
##  - Contraataca durante la recuperación del jugador.
##  - Elige combinaciones del perfil; los pesos de cada golpe suben o bajan según si conectó, fue bloqueado o esquivado.
##  - Si el jugador se tapa mucho, tira más al cuerpo/uppercut; si esquiva mucho, más ganchos.
##  - Gestiona su aguante (descansa si está cansado) y cambia de agresividad según el marcador.
## Uso: ai.setup(boxer_ia, boxer_jugador, perfil, dificultad 0..1)   y   boxer_ia.ai = ai

const ATTACKS := ["jab", "cross", "lead_hook", "rear_uppercut", "body_hook"]

var me                       # boxer.gd propio
var foe                      # boxer.gd del jugador
var profile: Dictionary = {}
var skill := 0.55
var rng := RandomNumberGenerator.new()

# --- memoria del jugador ---
var history: Array = []              # últimas acciones del jugador
var trans: Dictionary = {}           # contexto ("a>b" o "a") -> {siguiente: veces}
var foe_stats := {"attacks": 0, "blocks": 0, "dodges": 0}
var weights: Dictionary = {}         # golpe -> peso adaptativo
var danger: Dictionary = {}          # golpe del jugador -> veces que me conectó

# --- estado interno ---
var clock := 0.0
var think_timer := 0.5
var combo: Array = []
var combo_gap := 0.0
var reacted_attack_id := -1
var counter_checked_id := -1
var react_choice := ""
var react_delay := 0.0
var counter_pending := false
var guard_timer := 0.0
var guard_on := false
var anticipating := ""
var anticipate_until := 0.0
var behind_bonus := 0.0              # >0: va perdiendo y arriesga más


func setup(me_, foe_, profile_: Dictionary, skill_: float) -> void:
	me = me_
	foe = foe_
	profile = profile_
	skill = clampf(skill_, 0.0, 1.0)
	rng.randomize()
	for a in ATTACKS:
		weights[a] = 1.0


## Lo llama fight_manager al iniciar cada round: diff = puntos de la IA - puntos del jugador.
func set_context(score_diff: float, late_round: bool) -> void:
	var s := float(profile.get("learning", 0.4))
	if score_diff < -0.5:
		behind_bonus = (0.25 + (0.15 if late_round else 0.0)) * (0.4 + s)
	elif score_diff > 1.5:
		behind_bonus = -0.15 * (0.4 + s)
	else:
		behind_bonus = 0.0


# ---------------- aprendizaje ----------------

## fight_manager avisa cada vez que el jugador empieza una acción.
func observe(action: String) -> void:
	if history.size() >= 2:
		_count(String(history[-2]) + ">" + String(history[-1]), action)
	if history.size() >= 1:
		_count(String(history[-1]), action)
	history.append(action)
	if history.size() > 40:
		history.pop_front()
	if action in ATTACKS:
		foe_stats["attacks"] += 1
	elif action == "block":
		foe_stats["blocks"] += 1
	elif action.begins_with("dodge"):
		foe_stats["dodges"] += 1
	var p := predict()
	if p["conf"] >= 0.4 and rng.randf() < float(profile.get("learning", 0.4)) * (0.4 + skill * 0.6):
		anticipating = p["action"]
		anticipate_until = clock + 1.2


func _count(ctx: String, nxt: String) -> void:
	if not trans.has(ctx):
		trans[ctx] = {}
	trans[ctx][nxt] = int(trans[ctx].get(nxt, 0)) + 1


## Predice la próxima acción del jugador (prefiere el contexto más largo con datos suficientes).
func predict() -> Dictionary:
	var best := {"action": "", "conf": 0.0}
	var ctxs: Array = []
	if history.size() >= 2:
		ctxs.append(String(history[-2]) + ">" + String(history[-1]))
	if history.size() >= 1:
		ctxs.append(String(history[-1]))
	for c in ctxs:
		if not trans.has(c):
			continue
		var total := 0
		for k in trans[c]:
			total += int(trans[c][k])
		if total < 3:
			continue
		for k in trans[c]:
			var conf := float(trans[c][k]) / float(total)
			if conf > float(best["conf"]):
				best = {"action": k, "conf": conf}
		if float(best["conf"]) > 0.0:
			return best
	return best


## Resultado de un golpe de la IA: "hit", "blocked", "dodged" o "whiff".
func notify_result(attack: String, outcome: String) -> void:
	if not weights.has(attack):
		return
	var rate := 0.12 * float(profile.get("learning", 0.4)) * (0.5 + skill)
	if outcome == "hit":
		weights[attack] += rate * 2.0
	elif outcome == "blocked":
		weights[attack] -= rate
	elif outcome == "dodged":
		weights[attack] -= rate * 1.5
	weights[attack] = clampf(float(weights[attack]), 0.3, 3.0)


## El golpe del jugador me conectó: lo respeto más la próxima vez.
func notify_got_hit(attack: String) -> void:
	danger[attack] = int(danger.get(attack, 0)) + 1


## Texto para el HUD/depuración: qué aprendió la IA.
func get_insight() -> String:
	var p := predict()
	if float(p["conf"]) >= 0.4 and String(p["action"]) != "":
		return "IA espera: %s (%d%%)" % [p["action"], int(float(p["conf"]) * 100.0)]
	return "IA observando..."


# ---------------- decisión por frame ----------------

func think(delta: float) -> void:
	clock += delta
	if me.frozen or me.is_stunned() or foe == null:
		me.cmd_move(0.0)
		me.cmd_side(0.0)
		return
	var dist: float = me.distance_to_opponent()
	if _handle_reaction(delta, dist):
		return
	_maintain_guard(delta, dist)
	if me.is_attacking() and combo.is_empty():
		me.cmd_move(0.0)
		me.cmd_side(0.0)
		return
	_offense_and_movement(delta, dist)


func _foe_threat(dist: float) -> String:
	if foe.is_attacking() and foe.attack_phase() == "startup":
		if dist <= foe.attack_range(foe.cur_attack) * 1.15:
			return foe.cur_attack
	return ""


## Reconoce el ataque entrante y decide (una sola vez por ataque) si lo defiende y cómo.
func _handle_reaction(delta: float, dist: float) -> bool:
	var threat := _foe_threat(dist)
	if threat != "" and foe.attack_id != reacted_attack_id:
		reacted_attack_id = foe.attack_id
		var a: Dictionary = foe.table["attacks"][threat]
		var time_left: float = float(a["startup"]) - foe.attack_t
		var rt: float = float(profile.get("react_time", 0.25)) * (1.3 - skill) * 0.55   # difícil: ~0.08 s, fácil: ~0.15 s
		var predicted := anticipating == threat and clock < anticipate_until
		if predicted:
			rt *= 0.4
		var p: float = float(profile.get("reaction", 0.5)) * (0.4 + 0.6 * skill)
		if predicted:
			p += 0.25 * float(profile.get("learning", 0.4))
		p += minf(0.2, float(danger.get(threat, 0)) * 0.04)
		if rt < time_left * 0.9 and rng.randf() < clampf(p, 0.0, 0.95):
			var dodge_p: float = float(profile.get("dodge_pref", 0.3))
			var block_p: float = float(profile.get("block_pref", 0.5))
			react_choice = "dodge" if rng.randf() * (dodge_p + block_p) < dodge_p else "block"
			react_delay = rt
			counter_pending = rng.randf() < float(profile.get("counter", 0.4)) * (0.5 + skill)
		else:
			react_choice = ""
	if react_choice != "":
		me.cmd_side(0.0)
		react_delay -= delta
		if react_delay <= 0.0:
			if react_choice == "dodge":
				me.cmd_dodge("dodge_left" if rng.randf() < 0.5 else "dodge_right")
			else:
				guard_on = true
				guard_timer = 0.5
				me.cmd_block(true)
			react_choice = ""
		return true
	return false


## Mantiene la guardia alta según la personalidad (los defensivos casi siempre).
func _maintain_guard(delta: float, dist: float) -> void:
	if me.is_attacking() or not combo.is_empty():
		me.cmd_block(false)
		return
	guard_timer -= delta
	if guard_timer <= 0.0:
		var extra := 0.25 if me.stamina < float(profile.get("stamina_rest", 25)) else 0.0
		guard_on = rng.randf() < float(profile.get("guard_hold", 0.4)) + extra and dist < 1.9
		guard_timer = rng.randf_range(0.5, 1.2)
	me.cmd_block(guard_on)


func _aggression() -> float:
	var a := float(profile.get("aggression", 0.5)) * (0.6 + 0.6 * skill) + behind_bonus
	return clampf(a, 0.05, 0.98)


func _offense_and_movement(delta: float, dist: float) -> void:
	var pressure := float(profile.get("pressure", 0.4))
	var rest_mode: bool = me.stamina < float(profile.get("stamina_rest", 25))

	# 1) Contraataque: el jugador está en la recuperación de su golpe
	if foe.is_attacking() and foe.attack_phase() == "recovery" and foe.attack_id != counter_checked_id:
		counter_checked_id = foe.attack_id
		var pc := float(profile.get("counter", 0.4)) * (0.4 + 0.6 * skill) + (0.25 if counter_pending else 0.0)
		if not rest_mode and rng.randf() < pc and _start_combo(dist):
			counter_pending = false
			return

	# 2) Continuar la combinación en curso
	if not combo.is_empty():
		combo_gap -= delta
		if combo_gap <= 0.0 and me.can_act():
			var nxt: String = combo[0]
			if dist <= me.attack_range(nxt) * 1.08:
				_fire_next()
			else:
				combo.clear()
		me.cmd_move(0.0)
		return

	# 3) Iniciativa
	think_timer -= delta
	if think_timer <= 0.0:
		think_timer = float(profile.get("tempo", 0.7)) * (1.35 - 0.6 * skill) * rng.randf_range(0.7, 1.3)
		_adapt()
		if not rest_mode and rng.randf() < _aggression():
			_start_combo(dist)

	# 4) Desplazamiento: mantener la distancia preferida del perfil
	var desired := float(profile.get("range_pref", 1.0))
	if rest_mode:
		desired = 1.9
	var slack := lerpf(0.5, 0.08, pressure)
	var mv := 0.0
	if dist > desired + slack:
		mv = 1.0
	elif dist < desired - 0.2 and (pressure < 0.7 or rest_mode):
		mv = -1.0
	me.cmd_move(mv)

	# Footwork orbital: el rival deja de perseguir en línea recta y cambia el ángulo.
	# La dirección se renueva de forma irregular para evitar un patrón mecánico.
	orbit_timer -= delta
	if orbit_timer <= 0.0:
		orbit_timer = rng.randf_range(1.1, 2.6) * (1.25 - skill * 0.25)
		var offset_x := me.position.x - foe.position.x
		if absf(offset_x) > 1.15:
			orbit_dir = -signf(offset_x) # vuelve hacia el centro del ring
		elif rng.randf() < 0.72:
			orbit_dir *= -1.0
	var orbit_factor := float(profile.get("orbit", 0.35))
	var side := orbit_dir * orbit_factor
	if rest_mode or dist > desired + 1.0:
		side *= 0.45
	if me.position.x < -1.35 and side < 0.0:
		side = 0.35
	elif me.position.x > 1.35 and side > 0.0:
		side = -0.35
	if dist < 0.72:
		side *= 0.35
	me.cmd_side(side)


## Ajusta los pesos según los hábitos del jugador.
func _adapt() -> void:
	var learning := float(profile.get("learning", 0.4)) * (0.5 + skill)
	var tot := float(foe_stats["attacks"] + foe_stats["blocks"] + foe_stats["dodges"])
	if tot < 6.0:
		return
	if float(foe_stats["blocks"]) / tot > 0.4:
		weights["body_hook"] = minf(3.0, float(weights["body_hook"]) + 0.05 * learning)
		weights["rear_uppercut"] = minf(3.0, float(weights["rear_uppercut"]) + 0.05 * learning)
	if float(foe_stats["dodges"]) / tot > 0.3:
		weights["lead_hook"] = minf(3.0, float(weights["lead_hook"]) + 0.05 * learning)
		weights["cross"] = minf(3.0, float(weights["cross"]) + 0.03 * learning)


func _start_combo(dist: float) -> bool:
	var options: Array = []
	var total := 0.0
	var blocks_ratio := float(foe_stats["blocks"]) / maxf(1.0, float(foe_stats["attacks"] + foe_stats["blocks"] + foe_stats["dodges"]))
	for c in profile.get("combos", []):
		var seq: Array = c["seq"]
		var first: String = seq[0]
		if dist > me.attack_range(first) * 1.02:
			continue
		var w := float(c.get("w", 1.0))
		var wsum := 0.0
		for atk in seq:
			wsum += float(weights.get(atk, 1.0))
		w *= wsum / float(seq.size())
		if blocks_ratio > 0.4 and (seq.has("body_hook") or seq.has("rear_uppercut")):
			w *= 1.5
		options.append([seq, w])
		total += w
	if options.is_empty():
		return false
	var r := rng.randf() * total
	for o in options:
		r -= float(o[1])
		if r <= 0.0:
			combo = (o[0] as Array).duplicate()
			_fire_next()
			return true
	combo = (options[0][0] as Array).duplicate()
	_fire_next()
	return true


func _fire_next() -> void:
	var nxt: String = combo.pop_front()
	me.cmd_block(false)
	if me.cmd_attack(nxt):
		combo_gap = rng.randf_range(0.0, 0.1) * (1.4 - skill)
	else:
		combo.clear()
