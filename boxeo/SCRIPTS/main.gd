extends Node3D
## Punto de entrada: menús -> construye arena, boxeadores, IA, árbitro, HUD y controles -> combate -> resultado.
## Cámara con 5 modos (C o botón), efectos, cámara lenta, repetición y entrada al ring con presentación.

const Data = preload("res://SCRIPTS/data.gd")
const BoxerScript = preload("res://SCRIPTS/boxer.gd")
const StaffScript = preload("res://SCRIPTS/staff.gd")
const AI = preload("res://OPPONENTS/boxer_ai.gd")
const Manager = preload("res://SCRIPTS/fight_manager.gd")
const ArenaBuilder = preload("res://SCRIPTS/arena_builder.gd")
const Vfx = preload("res://SCRIPTS/vfx.gd")
const Sfx = preload("res://SCRIPTS/sfx.gd")
const Hud = preload("res://SCRIPTS/hud.gd")
const Narrator = preload("res://SCRIPTS/narrator.gd")
const Voz = preload("res://SCRIPTS/voz.gd")
const Polish = preload("res://SCRIPTS/polish.gd")
const Touch = preload("res://SCRIPTS/touch_controls.gd")
const Replay = preload("res://SCRIPTS/replay.gd")
const Menu = preload("res://SCRIPTS/menu.gd")

var table: Dictionary
var vfx_data: Dictionary
var layout: Dictionary
var profiles: Dictionary
var arenas_cfg: Dictionary
var referees: Array

var menu
var voz
var flashes: Node3D = null
var sfx
var cam: Camera3D
var world: Node3D
var a
var b
var mgr
var hud
var narr_ref
var touch
var touch_layer: CanvasLayer
var replay
var vfx
var last_config: Dictionary = {}
var last_result: Dictionary = {}
var career_summary := ""
var crowd_mat: ShaderMaterial = null
var crowd_base := 0.5

var in_fight := false
var in_intro := false
var camera_modes: Array = ["broadcast"]
var camera_index := 0
var shake := 0.0
var touch_move := 0.0
var touch_block := false


func _ready() -> void:
	table = Data.load_json("res://ANIMATIONS/animation_table.json")
	vfx_data = Data.load_json("res://VFX/vfx_presets.json")
	layout = Data.load_json("res://UI/ui_layout.json")
	profiles = Data.load_json("res://OPPONENTS/ai_profiles.json")
	arenas_cfg = Data.load_json("res://ARENAS/arenas.json")
	referees = Data.load_json("res://REFEREES/referees.json")["referees"]
	sfx = Sfx.new()
	add_child(sfx)
	voz = Voz.new()                  # se crea ya al abrir el juego: Android tarda en arrancar el motor de voz
	add_child(voz)
	cam = Camera3D.new()
	add_child(cam)
	cam.current = true
	cam.position = Vector3(5, 1.6, 0)
	menu = Menu.new()
	menu.voz = voz
	add_child(menu)
	menu.fight_requested.connect(start_fight)
	menu.open()


# ---------------- construcción del combate ----------------

func _teardown() -> void:
	in_fight = false
	in_intro = false
	Engine.time_scale = 1.0
	if hud:
		hud.queue_free()
		hud = null
	if touch_layer:
		touch_layer.queue_free()
		touch_layer = null
	if world:
		world.queue_free()
		world = null
	sfx.stop_ambience()
	flashes = null
	crowd_mat = null
	touch_move = 0.0
	touch_block = false


func _make_boxer(fighter: Dictionary, model: PackedScene, player: bool):
	var bx = BoxerScript.new()
	world.add_child(bx)
	bx.setup(fighter, table, model, player)
	return bx


func start_fight(config: Dictionary) -> void:
	_teardown()
	last_config = config
	menu.close()
	world = Node3D.new()
	add_child(world)
	var arena_cfg := Data.find_by_id(arenas_cfg["arenas"], String(config["arena"]))
	var built := ArenaBuilder.build(arena_cfg, world)
	crowd_mat = built["crowd_mat"]
	var pol := Polish.apply_arena(world, arena_cfg, ArenaBuilder.FLOOR_Y, crowd_mat != null, bool(built["has_ring"]))
	flashes = pol["flashes"]
	crowd_base = float(arena_cfg["crowd"]["excitement"])
	sfx.ambience(String(arena_cfg["ambience"]), -12.0)

	var model := load("res://CHARACTERS/boxer_base.glb") as PackedScene
	a = _make_boxer(config["player"], model, true)
	b = _make_boxer(config["opponent"], model, false)
	var prof_name := String(config["opponent"].get("ai_profile", "technical"))
	var ai = AI.new()
	ai.setup(b, a, profiles["profiles"][prof_name], float(config["skill"]))
	b.ai = ai

	var ref = StaffScript.new()
	world.add_child(ref)
	ref.setup(load("res://CHARACTERS/staff_base.glb") as PackedScene, referees[randi() % referees.size()]["look"], table)
	ref.position = Vector3(-1.6, 0, 0)
	ref.rotation.y = PI / 2.0

	mgr = Manager.new()
	world.add_child(mgr)
	mgr.referee = ref
	mgr.setup(a, b, table, {"rounds": int(config["rounds"]), "round_seconds": float(config["round_seconds"]), "rest_seconds": 10.0})
	mgr.fx.connect(_on_fx)
	mgr.fight_finished.connect(_on_finished)
	var narr = Narrator.new()
	world.add_child(narr)
	narr.setup(mgr, a, b, voz)
	narr_ref = narr

	vfx = Vfx.new()
	world.add_child(vfx)
	vfx.setup(vfx_data)
	replay = Replay.new()
	world.add_child(replay)
	replay.setup([a, b], float(vfx_data["replay"]["sample_hz"]), float(vfx_data["replay"]["buffer_seconds"]), float(vfx_data["replay"]["speed"]))

	hud = Hud.new()
	add_child(hud)
	hud.voz = voz
	hud.setup(mgr, a, b)
	narr_ref.hud = hud
	narr_ref.intro()
	hud.rematch_pressed.connect(_on_rematch)
	hud.menu_pressed.connect(_on_menu)
	hud.replay_pressed.connect(_on_replay)
	touch_layer = CanvasLayer.new()
	touch_layer.layer = 9
	add_child(touch_layer)
	touch = Touch.new()
	touch_layer.add_child(touch)
	touch.setup(layout)
	touch.action_pressed.connect(_on_touch)

	camera_modes = arena_cfg.get("cameras", ["broadcast"])
	camera_index = 0
	in_fight = true
	await _intro(arena_cfg)
	if in_fight:
		mgr.start_fight()


## Presentación: entrada de cada boxeador (desde el túnel en arenas), nombre y récord.
func _intro(arena_cfg: Dictionary) -> void:
	in_intro = true
	a.set_facing(1.0)
	b.set_facing(-1.0)
	a.frozen = true
	b.frozen = true
	var walk := String(arena_cfg["kind"]) in ["arena", "stadium"]
	var fy: float = ArenaBuilder.FLOOR_Y
	for bx in [a, b]:
		bx.play("entrance_taunt")
	if walk:
		a.position = Vector3(0, fy, -6.0)
		b.position = Vector3(0, fy, 6.0)
		for pair in [[a, -4.2, -1.0], [b, 4.2, 1.0]]:
			var tw := create_tween()
			tw.tween_property(pair[0], "position", Vector3(0, fy, pair[1]), 2.4)
			tw.tween_property(pair[0], "position", Vector3(0, 0, pair[2]), 1.4)
	else:
		a.position = Vector3(0, 0, -1.0)
		b.position = Vector3(0, 0, 1.0)
	var ra: Dictionary = a.data["record"]
	var rb: Dictionary = b.data["record"]
	hud.announce("%s\n%d-%d (%d KO)" % [a.data["name"], ra["w"], ra["l"], ra["ko"]], 2.0)
	await get_tree().create_timer(2.2).timeout
	if not in_fight:
		return
	hud.announce("%s\n%d-%d (%d KO)" % [b.data["name"], rb["w"], rb["l"], rb["ko"]], 2.0)
	await get_tree().create_timer(2.2 if walk else 1.8).timeout
	in_intro = false


# ---------------- entrada del jugador ----------------

func _player_attack(name: String) -> void:
	if not in_fight or in_intro:
		return
	if mgr.phase == Manager.Phase.COUNT and mgr.downed == a:
		mgr.player_mash()
		return
	a.cmd_attack(name)


func _player_dodge(side: String) -> void:
	if not in_fight or in_intro:
		return
	if mgr.phase == Manager.Phase.COUNT and mgr.downed == a:
		mgr.player_mash()
		return
	a.cmd_dodge(side)


func _on_touch(kind: String, id: String, pressed: bool) -> void:
	if kind == "attack" and pressed:
		_player_attack(id)
	elif kind == "tap" and pressed:
		if id == "camera":
			camera_index = (camera_index + 1) % camera_modes.size()
		else:
			_player_dodge(id)
	elif kind == "hold":
		if id == "block":
			touch_block = pressed
		elif id == "forward":
			touch_move = 1.0 if pressed else (0.0 if touch_move > 0.0 else touch_move)
		elif id == "back":
			touch_move = -1.0 if pressed else (0.0 if touch_move < 0.0 else touch_move)


func _process(delta: float) -> void:
	if not in_fight or a == null:
		return
	if not in_intro:
		a.cmd_move(touch_move)
		a.cmd_block(touch_block)
	_update_camera(delta)


# ---------------- cámara ----------------

func _update_camera(delta: float) -> void:
	var presets: Dictionary = arenas_cfg["camera_presets"]
	var p: Dictionary = presets[String(camera_modes[camera_index])]
	var mid: Vector3 = (a.position + b.position) * 0.5
	var target := mid
	var up := Vector3.UP
	match String(p["mode"]):
		"side":
			target = mid + Vector3(float(p["distance"]), float(p["height"]), 0)
		"behind":
			var dir: Vector3 = (b.position - a.position).normalized()
			target = a.position - dir * float(p["distance"]) + Vector3(0.7, float(p["height"]), 0)
		"corner":
			target = mid + Vector3(float(p["distance"]) * 0.8, float(p["height"]), -float(p["distance"]) * 0.7)
		"top":
			target = mid + Vector3(0.0, float(p["height"]), 0.0)
			up = Vector3(1, 0, 0)
	cam.position = cam.position.lerp(target, clampf(delta * 4.0, 0.0, 1.0))
	var look := mid + Vector3(0, 1.3, 0)
	if cam.position.distance_to(look) > 0.1:
		cam.look_at(look, up)
		# balanceo mínimo de cámara en mano (se recalcula cada fotograma, no se acumula)
		var t := Time.get_ticks_msec() / 1000.0
		cam.rotate_object_local(Vector3.UP, sin(t * 0.6) * 0.003)
		cam.rotate_object_local(Vector3.RIGHT, sin(t * 0.43 + 1.0) * 0.002)
	cam.fov = lerpf(cam.fov, float(p["fov"]), clampf(delta * 3.0, 0.0, 1.0))
	shake = move_toward(shake, 0.0, delta * 0.35)
	cam.h_offset = randf_range(-shake, shake)
	cam.v_offset = randf_range(-shake, shake)


# ---------------- efectos ----------------

func _on_fx(kind: String, pos: Vector3, extra: Dictionary) -> void:
	if vfx == null:
		return
	if kind == "hit_light" or kind == "hit_heavy":
		var head: bool = extra.get("head", true)
		sfx.play("punch_light" if kind == "hit_light" and head else ("punch_body" if not head else "punch_heavy"), 0.0, randf_range(0.95, 1.1))
		vfx.burst("hit_heavy" if kind == "hit_heavy" else "hit_spark", pos)
		if head:
			vfx.burst("sweat", pos)
		_shake("heavy" if kind == "hit_heavy" else "light")
		var df = extra.get("defender", null)
		if df != null and mgr != null:
			Polish.hit_flash(df, kind == "hit_heavy")
			if kind == "hit_heavy":
				Polish.wear(df, Polish.wear_level(df, mgr.round_n, mgr.total_rounds))
		if kind == "hit_heavy":
			sfx.play("crowd_cheer", -8.0)
			_excite(0.9, 1.5)
			cam.fov = maxf(30.0, cam.fov - 3.5)          # golpe de cámara (la FOV vuelve sola)
			Polish.flash_burst(flashes, 3)
	elif kind == "block":
		sfx.play("block_hit")
		vfx.burst("block_puff", pos)
	elif kind == "whoosh":
		sfx.play("whoosh", -6.0, randf_range(0.9, 1.15))
	elif kind == "knockdown":
		sfx.play("knockdown_thud")
		vfx.burst("dust", pos + Vector3(0, 0.1, 0))
		_shake("knockdown")
		_excite(1.0, 3.0)
		_slowmo("knockdown")
		Polish.flash_burst(flashes, 8)
		_capture_highlight()
	elif kind == "bell_round_start":
		sfx.play("bell_round_start")
		if mgr != null and a != null and b != null:
			Polish.wear(a, Polish.wear_level(a, mgr.round_n, mgr.total_rounds))
			Polish.wear(b, Polish.wear_level(b, mgr.round_n, mgr.total_rounds))
	elif kind == "bell_round_end":
		sfx.play("bell_round_end")
	elif kind == "count_tick":
		sfx.play("count_tick")
	elif kind == "win":
		vfx.burst("confetti", pos + Vector3(0, 1.5, 0))
		Polish.flash_burst(flashes, 8)
		_excite(1.0, 6.0)


func _shake(kind: String) -> void:
	shake = maxf(shake, float(vfx_data["camera_shake"][kind]["strength"]))


func _slowmo(kind: String) -> void:
	var sm: Dictionary = vfx_data["slow_motion"][kind]
	Engine.time_scale = float(sm["scale"])
	await get_tree().create_timer(float(sm["time"]), true, false, true).timeout
	Engine.time_scale = 1.0


func _excite(level: float, seconds: float) -> void:
	if crowd_mat == null:
		return
	crowd_mat.set_shader_parameter("excitement", level)
	await get_tree().create_timer(seconds).timeout
	if crowd_mat != null:
		crowd_mat.set_shader_parameter("excitement", crowd_base)


func _capture_highlight() -> void:
	await get_tree().create_timer(2.5).timeout
	if replay != null and is_instance_valid(replay):
		replay.capture_highlight()


# ---------------- fin del combate ----------------

func _on_finished(res: Dictionary) -> void:
	last_result = res
	if res["winner"] == "b":
		sfx.play("stinger_lose")
	elif res["winner"] == "a":
		sfx.play("stinger_win")
	if last_config.get("career", false):
		career_summary = menu.career.apply_result(res)
		res["career"] = true


func _on_rematch() -> void:
	if last_config.get("career", false):
		_teardown()
		menu.open()
		menu.show_hub(career_summary)
	else:
		start_fight(last_config)


func _on_menu() -> void:
	_teardown()
	menu.open()
	if last_config.get("career", false) and menu.career != null:
		menu.show_hub(career_summary)


func _on_replay() -> void:
	hud.hide_result()
	touch_layer.visible = false
	camera_index = mini(1, camera_modes.size() - 1)
	if not replay.start():
		touch_layer.visible = true
		hud.show_result(last_result)
		return
	await replay.finished
	camera_index = 0
	touch_layer.visible = true
	hud.show_result(last_result)
