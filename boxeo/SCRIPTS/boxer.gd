extends Node3D
## Boxeador: modelo con esqueleto + máquina de estados + estadísticas.
## Lo controla una persona (touch_controls / teclado) o una IA (OPPONENTS/boxer_ai.gd) con los MISMOS comandos cmd_*.

const Data = preload("res://SCRIPTS/data.gd")

signal action_started(boxer, action)     # "jab", "cross"... "block", "dodge_left", "dodge_right"
signal attack_active(boxer, attack)      # empieza la ventana de impacto de un golpe
signal knocked_down(boxer)

enum S { IDLE, MOVE, ATTACK, BLOCK, DODGE, HIT, STAGGER, DOWN, GETUP, WIN, LOSE }

var state: int = S.IDLE
var data: Dictionary = {}
var stats: Dictionary = {}
var table: Dictionary = {}
var is_player := false
var ai = null                  # objeto boxer_ai.gd (null si lo controla una persona)
var opponent = null
var facing := 1.0              # +1 mira hacia +Z, -1 hacia -Z
var southpaw := false

var health := 100.0
var stamina := 100.0
var stun := 0.0
var knockdowns := 0
var knockdowns_round := 0

var cur_attack := ""
var attack_t := 0.0
var attack_hit_done := false
var attack_id := 0
var time_scale := 1.0          # velocidad de golpes según el atributo speed

var dodge_t := 0.0
var want_block := false
var move_dir := 0.0            # -1 retroceder, +1 avanzar
var side_dir := 0.0            # -1 izquierda, +1 derecha
var frozen := false            # entre rounds / ceremonias
var replay_mode := false

var counter_window := 0.0      # >0 tras esquivar o parar con timing: el próximo golpe sale más rápido y más fuerte
var block_age := 0.0           # tiempo desde que levantó la guardia (guardia fresca = parada perfecta)
var atk_speed_mul := 1.0
var buffered := ""             # golpe pedido un instante antes de poder actuar (solo jugador)
var buffer_t := 0.0
const PARRY_WINDOW := 0.18

var visual: Node3D
var kb_vel := 0.0              # velocidad de retroceso por impactos (inercia)
var move_cur := 0.0            # avance/retroceso suavizado (aceleración)
var since_action := 0.0        # tiempo sin atacar ni recibir golpes (recuperación de aliento)
var since_hit := 0.0
var anim: AnimationPlayer
var last_clip := ""


func setup(fighter: Dictionary, table_: Dictionary, model: PackedScene, player: bool) -> void:
	data = fighter
	stats = fighter["stats"]
	table = table_
	is_player = player
	southpaw = String(fighter.get("stance", "orthodox")) == "southpaw"
	visual = Node3D.new()
	visual.name = "Visual"
	add_child(visual)
	var inst := model.instantiate()
	visual.add_child(inst)
	var h: float = float(fighter.get("height_cm", 182)) / 182.0
	# Zurdo = modelo espejado: la lógica no cambia (el golpe "jab" sale con la mano adelantada)
	visual.scale = Vector3(h * (-1.0 if southpaw else 1.0), h, h)
	Data.apply_look(inst, fighter.get("look", {}))
	anim = Data.find_anim(inst)
	if anim:
		anim.playback_default_blend_time = 0.1
		Data.set_loops(anim, table, "boxer")
	time_scale = 0.85 + float(stats["speed"]) / 100.0 * 0.35
	play("idle_guard")
	# luz de contorno para separar al boxeador del fondo
	var rim := OmniLight3D.new()
	rim.light_color = Color(0.72, 0.82, 1.0) if player else Color(1.0, 0.78, 0.62)
	rim.light_energy = 0.55
	rim.omni_range = 2.8
	rim.position = Vector3(0.9, 1.8, -0.7)
	add_child(rim)


func set_facing(dir: float) -> void:
	facing = dir
	rotation.y = 0.0 if dir > 0.0 else PI


# ---------------- consultas ----------------

func is_attacking() -> bool:
	return state == S.ATTACK

func is_blocking() -> bool:
	return state == S.BLOCK

func is_down() -> bool:
	return state == S.DOWN or state == S.GETUP

func is_stunned() -> bool:
	return state == S.HIT or state == S.STAGGER or state == S.DOWN or state == S.GETUP or state == S.WIN or state == S.LOSE

func distance_to_opponent() -> float:
	return absf(opponent.position.z - position.z) if opponent else 99.0

## Alcance real del golpe: distancia de la tabla escalada por el alcance (cm) del boxeador.
func attack_range(name: String) -> float:
	return float(table["attacks"][name]["range"]) * float(data.get("reach_cm", 180)) / 180.0

func attack_phase() -> String:
	if state != S.ATTACK:
		return ""
	var a: Dictionary = table["attacks"][cur_attack]
	if attack_t < float(a["startup"]):
		return "startup"
	if attack_t < float(a["startup"]) + float(a["active"]):
		return "active"
	return "recovery"

func dodge_active() -> bool:
	if state != S.DODGE:
		return false
	var w: Array = table["defense"]["dodge_iframes"]
	return dodge_t >= float(w[0]) and dodge_t <= float(w[1])

## Se puede actuar si está libre, o si está terminando la recuperación de un golpe (encadenar combinaciones).
func can_act() -> bool:
	if frozen or replay_mode:
		return false
	if state == S.IDLE or state == S.MOVE or state == S.BLOCK:
		return true
	if state == S.ATTACK:
		var a: Dictionary = table["attacks"][cur_attack]
		var total := float(a["startup"]) + float(a["active"]) + float(a["recovery"])
		return attack_t >= float(a["startup"]) + float(a["active"]) + float(a["recovery"]) * 0.55 and attack_t < total
	return false


# ---------------- comandos (jugador e IA) ----------------

func cmd_move(dir: float) -> void:
	move_dir = clampf(dir, -1.0, 1.0)

func cmd_side(dir: float) -> void:
	side_dir = clampf(dir, -1.0, 1.0)

func cmd_block(on: bool) -> void:
	want_block = on

func cmd_attack(name: String) -> bool:
	if not table["attacks"].has(name):
		return false
	if not can_act() or stamina < 3.0:
		if is_player and not frozen and not replay_mode and state != S.HIT and state != S.STAGGER and not is_down():
			buffered = name        # buffer de entrada: se dispara en cuanto se pueda actuar
			buffer_t = 0.28
		return false
	buffered = ""
	var a: Dictionary = table["attacks"][name]
	atk_speed_mul = 1.35 if counter_window > 0.0 else 1.0
	cur_attack = name
	attack_t = 0.0
	attack_hit_done = false
	attack_id += 1
	state = S.ATTACK
	since_action = 0.0
	stamina = maxf(0.0, stamina - float(a["stamina"]))
	play(name, time_scale * atk_speed_mul, 0.05)
	action_started.emit(self, name)
	return true

func cmd_dodge(side: String) -> bool:
	if not can_act() or stamina < 6.0:
		return false
	state = S.DODGE
	dodge_t = 0.0
	cur_attack = ""
	stamina = maxf(0.0, stamina - float(table["defense"]["dodge_stamina"]))
	play(side, 1.0, 0.04)
	action_started.emit(self, side)
	return true


# ---------------- reacciones (las dispara fight_manager.gd) ----------------

## info: health_damage, stamina_damage, stun, head (bool), knockdown (bool), stagger (bool), push (m)
func apply_hit(info: Dictionary) -> void:
	health = maxf(0.0, health - float(info.get("health_damage", 0.0)))
	stamina = maxf(0.0, stamina - float(info.get("stamina_damage", 0.0)))
	cur_attack = ""
	if info.get("knockdown", false):
		go_down()
		return
	if info.get("stagger", false):
		state = S.STAGGER
		stun = 1.1
		play("stagger", 1.0, 0.05)
	else:
		var wob := create_tween()          # la cabeza/torso se sacude según el golpe
		wob.tween_property(visual, "rotation:z", 0.16 if randf() < 0.5 else -0.16, 0.05)
		wob.tween_property(visual, "rotation:z", 0.0, 0.28)
		state = S.HIT
		stun = float(info.get("stun", 0.3))
		play("hit_head" if info.get("head", true) else "hit_body", 1.0, 0.03)
	kb_vel = -facing * sqrt(14.0 * float(info.get("push", 0.12)))     # el golpe empuja con inercia, no teletransporta
	since_hit = 0.0

## Parada perfecta: el atacante queda aturdido y el defensor gana ventana de contraataque.
func get_parried() -> void:
	cur_attack = ""
	state = S.HIT
	stun = 0.55
	play("hit_head", 1.0, 0.03)
	_move_z(-facing * 0.18)

func is_parrying() -> bool:
	return state == S.BLOCK and block_age <= PARRY_WINDOW

func block_feedback() -> void:
	if anim and anim.has_animation("block_high"):
		anim.play("block_high", 0.0)
	_move_z(-facing * 0.06)

func go_down() -> void:
	state = S.DOWN
	knockdowns += 1
	knockdowns_round += 1
	play("knockdown", 1.0, 0.05)
	knocked_down.emit(self)

func get_up() -> void:
	state = S.GETUP
	stun = float(table["knockdown"]["getup_time"])
	play("getup", 1.0, 0.05)

func win() -> void:
	state = S.WIN
	play("victory")

func lose() -> void:
	state = S.LOSE
	play("defeat")

func to_neutral() -> void:
	state = S.IDLE
	cur_attack = ""
	move_dir = 0.0
	side_dir = 0.0
	want_block = false
	play("idle_guard", 1.0, 0.2)

func play(clip: String, speed: float = 1.0, blend: float = -1.0) -> void:
	if anim == null or not anim.has_animation(clip):
		return
	if last_clip == clip and anim.is_playing() and anim.get_animation(clip).loop_mode != Animation.LOOP_NONE:
		anim.speed_scale = speed
		return
	last_clip = clip
	anim.play(clip, blend, speed)


# ---------------- actualización ----------------

func _physics_process(delta: float) -> void:
	if replay_mode or frozen:
		return
	if ai != null:
		ai.think(delta)
	_regen(delta)
	counter_window = maxf(0.0, counter_window - delta)
	move_cur = move_toward(move_cur, move_dir, 7.0 * delta)
	if absf(kb_vel) > 0.02:
		var before := position.z
		_move_z(kb_vel * delta)
		var lim: float = float(table["movement"]["ring_half"])
		if absf(position.z) >= lim - 0.01 and signf(kb_vel) == signf(position.z):
			kb_vel *= -0.35                 # rebote en las cuerdas
		kb_vel = move_toward(kb_vel, 0.0, 7.0 * delta)
	if buffered != "":
		buffer_t -= delta
		if buffer_t <= 0.0:
			buffered = ""
		elif can_act() and stamina >= 3.0:
			var nm := buffered
			buffered = ""
			cmd_attack(nm)
	if state == S.IDLE or state == S.MOVE:
		_update_free(delta)
	elif state == S.BLOCK:
		_update_block(delta)
	elif state == S.ATTACK:
		_update_attack(delta)
	elif state == S.DODGE:
		dodge_t += delta
		if dodge_t >= float(table["defense"]["dodge_duration"]):
			state = S.IDLE
			play("idle_guard", 1.0, 0.1)
	elif state == S.HIT or state == S.STAGGER or state == S.GETUP:
		stun -= delta
		if stun <= 0.0:
			state = S.IDLE
			play("idle_guard", 1.0, 0.15)


func _regen(delta: float) -> void:
	since_action += delta
	since_hit += delta
	var regen := 2.0
	if state == S.IDLE:
		regen = 14.0
	elif state == S.MOVE:
		regen = 8.0
	elif state == S.BLOCK:
		regen = 6.0
	elif state == S.DOWN or state == S.WIN or state == S.LOSE:
		regen = 0.0
	# "segundo aire": tras 1,5 s sin pelear el aliento vuelve más rápido
	if since_action > 1.5 and regen > 0.0:
		regen *= 1.6
	stamina = minf(100.0, stamina + regen * delta * (0.6 + float(stats["stamina"]) / 150.0))
	# la vida se recupera muy despacio si llevas un rato sin recibir golpes (tope menor con cada caída)
	var cap := 100.0 - 10.0 * float(knockdowns)
	if since_hit > 4.0 and health < cap and regen > 0.0:
		health = minf(cap, health + 0.5 * delta * (0.6 + float(stats["chin"]) / 150.0))


func _foot_speed() -> float:
	var foot := 0.85 + float(stats["footwork"]) / 100.0 * 0.4
	if ai != null:
		foot *= float(ai.profile.get("speed", 1.0))
	return foot


func _update_free(delta: float) -> void:
	var foot := _foot_speed()
	var lateral := absf(side_dir) > 0.1
	var longitudinal := absf(move_dir) > 0.1
	if lateral:
		_move_x(side_dir * float(table["movement"].get("lateral", 1.25)) * foot * delta)
	if want_block:
		state = S.BLOCK
		block_age = 0.0
		play("block_high", 1.0, 0.05)
		action_started.emit(self, "block")
		return
	if longitudinal:
		state = S.MOVE
		var v: float = float(table["movement"]["forward"]) if move_dir > 0.0 else -float(table["movement"]["back"])
		_move_z(facing * v * foot * delta * clampf(absf(move_cur) * 1.4, 0.35, 1.0))
		# Si combina avance/retroceso con paso lateral, prioriza el clip longitudinal.
		play("walk_forward" if move_dir > 0.0 else "walk_back", foot)
	elif lateral:
		state = S.MOVE
		var side_clip := "step_left" if side_dir < 0.0 else "step_right"
		play(side_clip, foot, 0.08)
	else:
		state = S.IDLE
		play("idle_guard")
		if absf(move_cur) > 0.2:      # desliza un poco al soltar (inercia)
			_move_z(facing * move_cur * 1.2 * foot * delta * 0.5)


func _update_block(delta: float) -> void:
	block_age += delta
	if not want_block:
		state = S.IDLE
		play("idle_guard", 1.0, 0.1)
		return
	if absf(move_dir) > 0.1:
		var v: float = float(table["movement"]["block"]) * move_dir
		_move_z(facing * v * _foot_speed() * delta)
	if absf(side_dir) > 0.1:
		_move_x(side_dir * float(table["movement"].get("lateral_block", 0.72)) * _foot_speed() * delta)
		play("step_left" if side_dir < 0.0 else "step_right", _foot_speed() * 0.82, 0.08)


func _update_attack(delta: float) -> void:
	var a: Dictionary = table["attacks"][cur_attack]
	attack_t += delta * time_scale * atk_speed_mul
	if not attack_hit_done and attack_t >= float(a["startup"]):
		attack_hit_done = true
		attack_active.emit(self, cur_attack)
	if attack_t >= float(a["startup"]) + float(a["active"]) + float(a["recovery"]):
		state = S.IDLE
		cur_attack = ""
		play("idle_guard", 1.0, 0.1)


func _move_x(dx: float) -> void:
	# Desplazamiento lateral dentro del ring; mantiene el eje de combate y evita salir del área útil.
	var lim := float(table["movement"].get("lateral_half", 1.75))
	var nx := clampf(position.x + dx, -lim, lim)
	if opponent != null:
		var sep := float(table["movement"].get("lateral_separation", 0.42))
		if absf(nx - opponent.position.x) < sep and absf(position.z - opponent.position.z) < 1.25:
			nx = position.x
	position.x = nx

func _move_z(dz: float) -> void:
	var lim: float = float(table["movement"]["ring_half"])
	var nz := clampf(position.z + dz, -lim, lim)
	if opponent != null:
		var sep: float = float(table["movement"]["min_separation"])
		var side := signf(position.z - opponent.position.z)
		if side == 0.0:
			side = -facing
		if absf(nz - opponent.position.z) < sep:
			nz = clampf(opponent.position.z + side * sep, -lim, lim)
	position.z = nz
