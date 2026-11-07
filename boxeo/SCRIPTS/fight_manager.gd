extends Node
## Árbitro lógico del combate: rounds, resolución de golpes, daño, knockdowns y cuenta, puntuación 10-point-must y resultado.
## No dibuja nada: avisa a main.gd por señales (fx, announce, fight_finished...).

signal announce(text: String, seconds: float)
signal round_changed(round_n: int, total: int)
signal fight_finished(result: Dictionary)
signal fx(kind: String, pos: Vector3, extra: Dictionary)
signal score_updated(text: String)

enum Phase { PRE, ROUND, REST, COUNT, OVER }

var phase: int = Phase.PRE
var a: Node3D                      # jugador (esquina roja)
var b: Node3D                      # rival
var table: Dictionary = {}
var total_rounds := 3
var round_seconds := 60.0
var rest_seconds := 8.0
var round_n := 0
var time_left := 0.0
var rest_left := 0.0
var rp := [0.0, 0.0]               # puntos de golpeo del round (a, b)
var kd_round := [0, 0]             # veces que cayó cada uno en el round
var cards: Array = [[0, 0], [0, 0], [0, 0]]   # 3 jueces: total a, total b
var landed := [0, 0]
var thrown := [0, 0]
var downed: Node3D = null
var other: Node3D = null
var count_n := 0
var count_t := 0.0
var getup_t := -1.0
var mash := 0.0
var referee = null                 # staff.gd (opcional)
var rng := RandomNumberGenerator.new()


func setup(a_: Node3D, b_: Node3D, table_: Dictionary, cfg: Dictionary) -> void:
	a = a_
	b = b_
	table = table_
	total_rounds = int(cfg.get("rounds", 3))
	round_seconds = float(cfg.get("round_seconds", 60.0))
	rest_seconds = float(cfg.get("rest_seconds", 8.0))
	rng.randomize()
	a.opponent = b
	b.opponent = a
	for bx in [a, b]:
		bx.attack_active.connect(_on_attack_active)
		bx.action_started.connect(_on_action)
		bx.knocked_down.connect(_on_knockdown)


func start_fight() -> void:
	phase = Phase.PRE
	_reset_positions()
	for bx in [a, b]:
		bx.frozen = true
	announce.emit("%s   vs   %s" % [a.data["name"], b.data["name"]], 2.6)
	await get_tree().create_timer(3.0).timeout
	_start_round()


## El jugador machaca botones durante su cuenta: aumenta la probabilidad de levantarse.
func player_mash() -> void:
	if phase == Phase.COUNT and downed == a:
		mash += 1.0


func score_text() -> String:
	var parts: Array = []
	for c in cards:
		parts.append("%d-%d" % [c[0], c[1]])
	return "  ".join(parts)


# ---------------- rounds ----------------

## Esquinas y banquetas para el descanso entre rounds (los boxeadores se sientan).
const STOOL_POS := [Vector3(-2.0, 0, -2.2), Vector3(-2.0, 0, 2.2)]
var stools: Array = []


func _rest_sit() -> void:
	if stools.is_empty():
		for p in STOOL_POS:
			var m := MeshInstance3D.new()
			var c := CylinderMesh.new()
			c.top_radius = 0.2
			c.bottom_radius = 0.25
			c.height = 0.5
			m.mesh = c
			var mat := StandardMaterial3D.new()
			mat.albedo_color = Color(0.45, 0.28, 0.14)
			mat.roughness = 0.7
			m.material_override = mat
			m.position = p + Vector3(0, 0.25, 0)
			add_child(m)
			stools.append(m)
	for st in stools:
		st.visible = true
	var boxers := [a, b]
	for i in range(2):
		var bx = boxers[i]
		var tw := create_tween().set_parallel(true)
		tw.tween_property(bx, "position", STOOL_POS[i], 0.9)
		tw.tween_property(bx, "rotation:y", PI / 2.0, 0.9)          # mira al centro del ring
		tw.tween_property(bx.visual, "position:y", -0.42, 0.9)      # baja la cadera hasta la banqueta
		tw.tween_property(bx.visual, "rotation:x", -0.18, 0.9)      # se recuesta un poco


func _rest_stand() -> void:
	for st in stools:
		st.visible = false
	for bx in [a, b]:
		bx.visual.position = Vector3.ZERO
		bx.visual.rotation.x = 0.0


func _reset_positions() -> void:
	a.position = Vector3(0, 0, -1.0)
	b.position = Vector3(0, 0, 1.0)
	a.set_facing(1.0)
	b.set_facing(-1.0)


func _start_round() -> void:
	round_n += 1
	phase = Phase.ROUND
	time_left = round_seconds
	rp = [0.0, 0.0]
	kd_round = [0, 0]
	a.knockdowns_round = 0
	b.knockdowns_round = 0
	_rest_stand()
	_reset_positions()
	for bx in [a, b]:
		bx.frozen = false
		bx.to_neutral()
	if b.ai:
		var diff := float(cards[0][1] - cards[0][0])
		b.ai.set_context(diff, round_n >= total_rounds - 1)
	round_changed.emit(round_n, total_rounds)
	announce.emit("ROUND %d" % round_n, 1.5)
	fx.emit("bell_round_start", Vector3.ZERO, {})
	if referee:
		referee.play("idle_staff")


func _physics_process(delta: float) -> void:
	if phase == Phase.ROUND:
		time_left -= delta
		if time_left <= 0.0:
			_end_round()
	elif phase == Phase.REST:
		rest_left -= delta
		if rest_left <= 0.0:
			_start_round()
	elif phase == Phase.COUNT:
		_update_count(delta)


func _end_round() -> void:
	fx.emit("bell_round_end", Vector3.ZERO, {})
	_score_round()
	if round_n >= total_rounds:
		_finish_by_decision()
		return
	phase = Phase.REST
	rest_left = rest_seconds
	for bx in [a, b]:
		bx.to_neutral()
		bx.frozen = true
		bx.health = minf(100.0, bx.health + 8.0)
		bx.stamina = minf(100.0, bx.stamina + 40.0)
		bx.play("rest_breathe")
	_rest_sit()
	announce.emit("Fin del round %d" % round_n, 2.0)


## 10-point-must: 3 jueces con algo de ruido; las caídas restan puntos extra al caído.
func _score_round() -> void:
	for j in range(3):
		var diff: float = rp[0] - rp[1] + rng.randfn(0.0, 0.8)
		var ca := 10
		var cb := 10
		if absf(diff) < 0.4:
			if rng.randf() < 0.5:
				cb = 9
			else:
				ca = 9
		elif diff > 0.0:
			cb = 9
		else:
			ca = 9
		ca -= mini(2, int(kd_round[0]))
		cb -= mini(2, int(kd_round[1]))
		cards[j][0] += ca
		cards[j][1] += cb
	score_updated.emit(score_text())


func _finish_by_decision() -> void:
	var votes := [0, 0]
	for c in cards:
		if c[0] > c[1]:
			votes[0] += 1
		elif c[1] > c[0]:
			votes[1] += 1
	var winner := "draw"
	var method := "Empate"
	if votes[0] > votes[1]:
		winner = "a"
	elif votes[1] > votes[0]:
		winner = "b"
	if winner != "draw":
		var wv: int = int(votes[0]) if winner == "a" else int(votes[1])
		method = "Decisión unánime" if wv == 3 else "Decisión dividida"
	_end_fight(winner, method)


func _end_fight(winner: String, method: String) -> void:
	phase = Phase.OVER
	var ko := method == "KO" or method == "TKO"
	for bx in [a, b]:
		bx.frozen = false
		var wins: bool = (winner == "a" and bx == a) or (winner == "b" and bx == b)
		if winner == "draw":
			bx.to_neutral()
		elif wins:
			bx.win()
		elif not (ko and bx.is_down()):
			bx.lose()
	if referee:
		referee.play("ref_raise_winner" if winner != "draw" else "ref_break")
	if winner != "draw":
		fx.emit("win", Vector3(0, 2.5, 0), {"ko": ko})
	var res := {
		"winner": winner, "method": method, "round": round_n, "cards": cards.duplicate(true),
		"landed": landed.duplicate(), "thrown": thrown.duplicate(),
		"knockdowns": [a.knockdowns, b.knockdowns], "player_won": winner == "a", "ko": ko,
	}
	var name_w := "Empate"
	if winner == "a":
		name_w = String(a.data["name"])
	elif winner == "b":
		name_w = String(b.data["name"])
	announce.emit("%s\n%s" % [method.to_upper(), name_w], 5.0)
	fight_finished.emit(res)


# ---------------- acciones y golpes ----------------

func _on_action(boxer: Node3D, action: String) -> void:
	var idx := 0 if boxer == a else 1
	if table["attacks"].has(action):
		thrown[idx] += 1
	if boxer == a and b.ai:
		b.ai.observe(action)


func _on_attack_active(att: Node3D, atk_name: String) -> void:
	if phase != Phase.ROUND:
		return
	var df: Node3D = b if att == a else a
	var atk: Dictionary = table["attacks"][atk_name]
	var head: bool = String(atk["target"]) == "head"
	var pos := df.global_position + Vector3(0, 1.55 if head else 1.1, 0)
	var ai_att = att.ai
	# 1) fuera de alcance o rival caído
	if att.distance_to_opponent() > att.attack_range(atk_name) or df.is_down():
		fx.emit("whoosh", att.global_position, {})
		if ai_att:
			ai_att.notify_result(atk_name, "whiff")
		return
	# 2) esquivado
	if df.dodge_active():
		df.stamina = maxf(0.0, df.stamina - 2.0)
		fx.emit("whoosh", pos, {})
		if ai_att:
			ai_att.notify_result(atk_name, "dodged")
		return
	var power_f := 0.6 + float(att.stats["power"]) / 100.0 * 0.8
	var low := 0.75 if att.stamina < 15.0 else 1.0
	var dmg: float = float(atk["damage"]) * power_f * low
	# 3) bloqueado
	if df.is_blocking():
		var bm: float = float(table["defense"]["block_damage_mult"]) if head else float(table["defense"]["block_body_mult"])
		df.stamina = maxf(0.0, df.stamina - dmg * float(table["defense"]["block_stamina_mult"]))
		df.health = maxf(0.0, df.health - dmg * bm * 0.3)
		df.block_feedback()
		fx.emit("block", pos, {})
		if ai_att:
			ai_att.notify_result(atk_name, "blocked")
		return
	# 4) impacto
	var clean_p := clampf(0.55 + float(att.stats["accuracy"]) / 200.0 - float(df.stats["defense"]) / 400.0, 0.3, 0.95)
	var clean := rng.randf() < clean_p
	var counter: bool = df.is_attacking() and df.attack_phase() != "recovery"
	dmg *= (1.0 if clean else 0.5) * (1.3 if counter else 1.0)
	var chin_f := 1.25 - float(df.stats["chin"]) / 200.0
	var health_dmg := dmg * chin_f * (1.0 if head else 0.45)
	var stam_dmg := 0.0 if head else dmg * 1.6
	var new_health: float = df.health - health_dmg
	var knock := new_health <= 0.0
	var stagger := (not knock) and dmg >= 9.0 and new_health < 30.0 and rng.randf() < 0.35
	var idx := 0 if att == a else 1
	if clean:
		rp[idx] += (1.0 if head else 0.8) * (1.3 if counter else 1.0)
	landed[idx] += 1
	if ai_att:
		ai_att.notify_result(atk_name, "hit")
	if df.ai:
		df.ai.notify_got_hit(atk_name)
	fx.emit("hit_heavy" if dmg >= 9.0 else "hit_light", pos, {"head": head, "damage": dmg, "counter": counter})
	df.apply_hit({
		"health_damage": health_dmg, "stamina_damage": stam_dmg, "stun": float(atk["stun"]),
		"head": head, "knockdown": knock, "stagger": stagger, "push": 0.1 + dmg * 0.01,
	})


# ---------------- knockdown y cuenta ----------------

func _on_knockdown(bx: Node3D) -> void:
	if phase == Phase.OVER:
		return
	downed = bx
	other = b if bx == a else a
	phase = Phase.COUNT
	count_n = 0
	count_t = 0.0
	getup_t = -1.0
	mash = 0.0
	var idx := 0 if bx == a else 1
	kd_round[idx] += 1
	other.to_neutral()
	other.frozen = true
	announce.emit("¡KNOCKDOWN!", 1.5)
	fx.emit("knockdown", bx.global_position, {})
	if referee:
		referee.play("ref_count")
	# regla de las caídas: TKO automático
	if int(kd_round[idx]) >= int(table["knockdown"]["max_per_round"]) or bx.knockdowns >= int(table["knockdown"]["max_per_fight"]):
		_end_fight("b" if bx == a else "a", "TKO")


func _update_count(delta: float) -> void:
	if downed == null:
		return
	if getup_t >= 0.0:
		getup_t -= delta
		if getup_t <= 0.0:
			getup_t = -1.0
			phase = Phase.ROUND
			other.frozen = false
			other.to_neutral()
			downed.to_neutral()
			announce.emit("¡BOX!", 1.0)
			if referee:
				referee.play("idle_staff")
			if time_left <= 0.0:
				_end_round()
		return
	count_t += delta
	if count_t >= 1.0:
		count_t -= 1.0
		count_n += 1
		fx.emit("count_tick", Vector3.ZERO, {})
		announce.emit(str(count_n), 0.9)
		if count_n >= int(table["knockdown"]["count_seconds"]):
			_end_fight("b" if downed == a else "a", "KO")
			return
		if count_n >= 3:
			_try_getup()


func _try_getup() -> void:
	var chin := float(downed.stats["chin"])
	var p := 0.18 + chin / 300.0 - 0.08 * float(downed.knockdowns - 1)
	if downed == a:
		p = 0.25 + clampf(mash * 0.1, 0.0, 0.55) + chin / 400.0 - 0.06 * float(downed.knockdowns - 1)
		mash = 0.0
	if rng.randf() < clampf(p, 0.05, 0.95):
		downed.health = float(table["knockdown"]["health_after_getup"]) + chin * 0.1
		downed.get_up()
		getup_t = float(table["knockdown"]["getup_time"]) + 0.2
		announce.emit("¡Arriba!", 1.0)
		if referee:
			referee.play("ref_break")
