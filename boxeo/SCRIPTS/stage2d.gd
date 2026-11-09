extends Node2D
## Escenario 2D: dibuja el combate de lado con los fondos de ART/, el ring, las cuerdas y los boxeadores como muñecos
## articulados que siguen los huesos del esqueleto 3D (toda la lógica, animaciones e IA siguen siendo las mismas).
## Cabeza = cara del retrato. Efectos = sprites de ART/fx con mezcla aditiva.

const Art = preload("res://SCRIPTS/art.gd")
const Boxer = preload("res://SCRIPTS/boxer.gd")
const Manager = preload("res://SCRIPTS/fight_manager.gd")

const ROPE_HALF := 3.2
const ROPE_H := [0.55, 0.95, 1.35]
const OUTLINE := Color(0.04, 0.04, 0.07)
const BONES := ["Hips", "Spine", "Chest", "Neck", "Head", "UpperArm_L", "Forearm_L", "Hand_L", "UpperArm_R", "Forearm_R", "Hand_R",
	"Thigh_L", "Shin_L", "Foot_L", "Thigh_R", "Shin_R", "Foot_R"]
const ZOOMS := [1.0, 1.28, 0.82]

var mgr
var a
var b
var referee = null
var boxers: Array = []
var rig: Dictionary = {}                 # boxeador -> {skel, idx, face, colors}
var ref_rig: Dictionary = {}
var backdrop: Texture2D
var accent := Color(0.1, 0.4, 0.9)
var logo: Texture2D
var arena_name := ""
var cam_z := 0.0
var k := 220.0
var gy := 520.0
var zoom_idx := 0
var zoom_mul := 1.0
var kick := 0.0
var shake := 0.0
var t := 0.0
var rope_amp := {-1: 0.0, 1: 0.0}
var tint := [0.0, 0.0]
var tint_col := [Color.WHITE, Color.WHITE]
var wear := [0.0, 0.0]
var stars: Dictionary = {}
var flashes: Array = []                  # destellos de cámara en el público
var confetti: Array = []
var confetti_on := false
var seated := [false, false]


func setup(mgr_, a_, b_, arena_cfg: Dictionary, ref_ = null) -> bool:
	mgr = mgr_
	a = a_
	b = b_
	referee = ref_
	boxers = [a, b]
	backdrop = Art.tex(String(arena_cfg.get("backdrop", "")))
	accent = Color.html(String(arena_cfg.get("accent", "#1b5ae0")))
	logo = Art.ui("logo_alpha")
	arena_name = String(arena_cfg.get("name", ""))
	for bx in boxers:
		var r := _make_rig(bx, bx.visual, bx.data.get("look", {}), String(bx.data.get("portrait", "")), false)
		if r.is_empty():
			return false
		rig[bx] = r
	if referee != null:
		ref_rig = _make_rig(referee, referee.visual, {"skin": "#c9a487", "shorts": "#15151a", "gloves": "#c9a487", "shoes": "#111111", "hair": "#2b2018"}, "", true)
	for bx in boxers:
		var s := Sprite2D.new()
		s.texture = Art.fx("fx_stars")
		s.material = Art.additive()
		s.visible = false
		s.z_index = 20
		s.scale = Vector2(0.9, 0.9)
		add_child(s)
		stars[bx] = s
	z_index = 0
	return true


func _make_rig(owner_node, visual: Node, look: Dictionary, portrait: String, is_ref: bool) -> Dictionary:
	var sk: Skeleton3D = null
	for n in visual.find_children("*", "Skeleton3D", true, false):
		sk = n
		break
	if sk == null:
		return {}
	var idx := {}
	for nm in BONES:
		var i := sk.find_bone(nm)
		if i < 0:
			return {}
		idx[nm] = i
	var col := {
		"skin": Color.html(String(look.get("skin", "#b07a55"))), "shorts": Color.html(String(look.get("shorts", "#1f8a3c"))),
		"gloves": Color.html(String(look.get("gloves", "#c62828"))), "shoes": Color.html(String(look.get("shoes", "#111111"))),
		"hair": Color.html(String(look.get("hair", "#14100c"))), "ref": is_ref,
	}
	return {"skel": sk, "idx": idx, "face": Art.tex(portrait), "col": col}


# ---------------- entrada desde main.gd ----------------

func cycle_zoom() -> void:
	zoom_idx = (zoom_idx + 1) % ZOOMS.size()


func set_wear(bx, level: float) -> void:
	var i := boxers.find(bx)
	if i >= 0:
		wear[i] = level


func rope_bounce(bx, strength: float) -> void:
	rope_amp[1 if bx.position.z > 0.0 else -1] = strength


func on_fx(kind: String, pos: Vector3, extra: Dictionary) -> void:
	var p := to_screen(pos)
	if kind == "hit_light":
		_burst("fx_white_burst", p, 0.55, 0.3)
		_burst("fx_sparks", p, 0.7, 0.35)
		shake = maxf(shake, 4.0)
		_flash_fighter(extra.get("defender", null), Color(1, 0.6, 0.55), 0.14)
	elif kind == "hit_heavy":
		_burst("fx_orange_burst", p, 1.0, 0.45)
		_burst("fx_red_burst", p, 0.8, 0.5)
		_burst("fx_red_splat", p + Vector2(0, -8), 0.6, 0.5)
		shake = maxf(shake, 12.0)
		kick = 0.07
		_flash_fighter(extra.get("defender", null), Color(1, 0.35, 0.3), 0.28)
		_camera_flashes(3)
	elif kind == "block":
		_burst("fx_blue_burst", p, 0.5, 0.25)
	elif kind == "parry":
		_burst("fx_blue_lightning", p, 1.0, 0.45)
		_burst("fx_white_burst", p, 0.7, 0.3)
		shake = maxf(shake, 5.0)
	elif kind == "slip":
		_burst("fx_blue_swoosh", p + Vector2(0, -30), 0.9, 0.4)
	elif kind == "combo":
		var n := int(extra.get("count", 3))
		_burst("fx_fire", p + Vector2(0, -20), 0.7 + 0.1 * minf(float(n), 6.0), 0.6)
		if n >= 5:
			_burst("fx_gold_burst", p, 1.2, 0.6)
	elif kind == "knockdown":
		_burst("fx_smoke", Vector2(p.x, gy), 1.6, 0.9)
		_burst("fx_white_burst", p, 1.4, 0.5)
		_burst("fx_fire2", p, 1.2, 0.5)
		shake = maxf(shake, 20.0)
		kick = 0.12
		_camera_flashes(8)
	elif kind == "win":
		confetti_on = true
		_spawn_confetti()
		for i in range(4):
			_burst("fx_gold_burst", Vector2(randf_range(0.2, 0.8) * _vs().x, randf_range(0.15, 0.5) * _vs().y), 1.5, 0.9)
		_camera_flashes(10)
	elif kind == "bell_round_start":
		confetti_on = false
		confetti.clear()


func _flash_fighter(bx, col: Color, secs: float) -> void:
	var i := boxers.find(bx)
	if i >= 0:
		tint[i] = secs
		tint_col[i] = col


func _burst(name: String, p: Vector2, size: float, life: float) -> void:
	var tx := Art.fx(name)
	if tx == null:
		return
	var s := Sprite2D.new()
	s.texture = tx
	s.material = Art.additive()
	s.position = p
	s.z_index = 30
	s.rotation = randf_range(-0.4, 0.4)
	var base := (k * 0.9 * size) / maxf(float(tx.get_width()), 1.0)
	s.scale = Vector2(base, base) * 0.4
	add_child(s)
	var tw := create_tween().set_parallel(true)
	tw.tween_property(s, "scale", Vector2(base, base), life * 0.6).set_trans(Tween.TRANS_BACK).set_ease(Tween.EASE_OUT)
	tw.tween_property(s, "modulate:a", 0.0, life).set_delay(life * 0.35)
	tw.chain().tween_callback(s.queue_free)


func _camera_flashes(n: int) -> void:
	for i in range(n):
		flashes.append({"x": randf(), "y": randf_range(0.08, 0.38), "t": randf_range(0.0, 0.25), "life": 0.0})


func _spawn_confetti() -> void:
	var vs := _vs()
	for i in range(90):
		confetti.append({"p": Vector2(randf() * vs.x, randf_range(-vs.y, 0.0)), "v": Vector2(randf_range(-30, 30), randf_range(90, 220)),
			"c": [Color(1, 0.8, 0.2), Color(0.9, 0.2, 0.3), Color(0.2, 0.6, 1.0), Color.WHITE][i % 4], "r": randf() * 6.0, "w": randf_range(5, 10)})


# ---------------- proyección ----------------

func _vs() -> Vector2:
	return get_viewport_rect().size


func to_screen(v: Vector3) -> Vector2:
	var vs := _vs()
	return Vector2(vs.x * 0.5 + (v.z - cam_z) * k, gy - v.y * k)


func _pz(z: float, y: float) -> Vector2:
	var vs := _vs()
	return Vector2(vs.x * 0.5 + (z - cam_z) * k, gy - y * k)


func _process(delta: float) -> void:
	t += delta
	var vs := _vs()
	if a == null or b == null:
		return
	var mid := (a.position.z + b.position.z) * 0.5
	cam_z = lerpf(cam_z, mid, clampf(delta * 5.0, 0.0, 1.0))
	zoom_mul = lerpf(zoom_mul, ZOOMS[zoom_idx] + kick, clampf(delta * 4.0, 0.0, 1.0))
	kick = move_toward(kick, 0.0, delta * 0.25)
	var dist := absf(a.position.z - b.position.z)
	var target_k := clampf(vs.x / (dist + 4.6), vs.y * 0.22, vs.y * 0.34) * zoom_mul
	k = lerpf(k, target_k, clampf(delta * 5.0, 0.0, 1.0))
	gy = vs.y * 0.80
	shake = move_toward(shake, 0.0, delta * 40.0)
	position = Vector2(randf_range(-shake, shake), randf_range(-shake, shake))
	for s in rope_amp.keys():
		rope_amp[s] = move_toward(rope_amp[s], 0.0, delta * 1.6)
	for i in range(2):
		tint[i] = maxf(0.0, tint[i] - delta)
	for f in flashes:
		f["t"] -= delta
		if f["t"] <= 0.0:
			f["life"] += delta
	flashes = flashes.filter(func(f): return f["life"] < 0.18)
	if confetti_on:
		for c in confetti:
			c["p"] += c["v"] * delta
			c["p"].x += sin(t * 3.0 + c["r"]) * 20.0 * delta
			c["r"] += delta * 5.0
			if c["p"].y > vs.y + 20.0:
				c["p"].y = -10.0
	for bx in boxers:
		var s: Sprite2D = stars[bx]
		if s.texture == null:
			continue
		var dizzy: bool = bx.state == Boxer.S.STAGGER or bx.state == Boxer.S.DOWN
		s.visible = dizzy
		if dizzy:
			var hp := _joint_head_pos(bx)
			s.position = hp + Vector2(0, -0.32 * k)
			s.rotation = t * 2.5
			var base := (k * 0.9) / maxf(float(s.texture.get_width()), 1.0)
			s.scale = Vector2(base, base * 0.5)
	queue_redraw()


func _joint_head_pos(bx) -> Vector2:
	var j := _joints(bx, rig[bx])
	return j["Head"]["p"] if j.has("Head") else Vector2.ZERO


# ---------------- articulaciones ----------------

## Devuelve {hueso: {"p": pantalla, "x": profundidad}}. Sentado (descanso) usa una pose propia.
func _joints(bx, r: Dictionary) -> Dictionary:
	var out := {}
	var sk: Skeleton3D = r["skel"]
	var idx: Dictionary = r["idx"]
	var seat := false
	if bx in boxers:
		seat = mgr != null and mgr.phase == Manager.Phase.REST and bx.visual.position.y < -0.15
	if seat:
		return _seated(bx)
	var xf := sk.global_transform
	for nm in BONES:
		var w: Vector3 = xf * sk.get_bone_global_pose(int(idx[nm])).origin
		out[nm] = {"p": to_screen(Vector3(w.x, w.y - bx_base_y(bx), w.z)), "x": w.x}
	return out


func bx_base_y(bx) -> float:
	return 0.0


func _seated(bx) -> Dictionary:
	var f: float = bx.facing
	var z: float = bx.position.z
	var br := sin(t * 2.0) * 0.012
	var J := {}
	var hips := Vector2(z, 0.58)
	var def := {
		"Hips": hips, "Spine": hips + Vector2(-f * 0.03, 0.14), "Chest": hips + Vector2(-f * 0.06, 0.30 + br), "Neck": hips + Vector2(-f * 0.08, 0.55 + br),
		"Head": hips + Vector2(-f * 0.08, 0.63 + br),
		"UpperArm_L": hips + Vector2(-f * 0.07, 0.48), "Forearm_L": hips + Vector2(f * 0.04, 0.24), "Hand_L": hips + Vector2(f * 0.30, 0.10),
		"UpperArm_R": hips + Vector2(-f * 0.07, 0.48), "Forearm_R": hips + Vector2(f * 0.06, 0.26), "Hand_R": hips + Vector2(f * 0.34, 0.14),
		"Thigh_L": hips, "Shin_L": hips + Vector2(f * 0.40, -0.04), "Foot_L": hips + Vector2(f * 0.46, -0.50),
		"Thigh_R": hips, "Shin_R": hips + Vector2(f * 0.36, -0.02), "Foot_R": hips + Vector2(f * 0.54, -0.50),
	}
	for nm in def.keys():
		J[nm] = {"p": _pz(def[nm].x, def[nm].y), "x": 0.12 if nm.ends_with("_L") else -0.12}
	return J


# ---------------- dibujo ----------------

func _draw() -> void:
	var vs := _vs()
	_draw_backdrop(vs)
	_draw_floor(vs)
	_draw_ropes_far(vs)
	if not ref_rig.is_empty() and referee != null:
		_draw_figure(referee, ref_rig, 0.0, 1.0, Color.WHITE)
	if mgr != null and mgr.phase == Manager.Phase.REST:
		for i in range(2):
			_draw_stool(i)
	for i in range(2):
		var bx = boxers[i]
		_shadow(bx.position.z)
		_draw_figure(bx, rig[bx], wear[i], 1.0, tint_col[i].lerp(Color.WHITE, 1.0 - clampf(tint[i] / 0.28, 0.0, 1.0)) if tint[i] > 0.0 else Color.WHITE)
	_draw_ropes_near(vs)
	_draw_confetti()


func _draw_backdrop(vs: Vector2) -> void:
	draw_rect(Rect2(Vector2(-60, -60), vs + Vector2(120, 120)), Color(0.03, 0.04, 0.08))
	if backdrop != null:
		var tw := float(backdrop.get_width())
		var th := float(backdrop.get_height())
		var sc := maxf(vs.x * 1.1 / tw, vs.y * 0.9 / th)
		var sz := Vector2(tw, th) * sc
		var off := clampf(-cam_z * k * 0.05, -vs.x * 0.04, vs.x * 0.04)
		draw_texture_rect(backdrop, Rect2(Vector2((vs.x - sz.x) * 0.5 + off, (vs.y * 0.80 - sz.y) * 0.5 - 6), sz), false, Color(0.82, 0.82, 0.9))
	# oscurecer arriba y abajo para que los boxeadores resalten
	var c0 := Color(0, 0, 0, 0.55)
	var c1 := Color(0, 0, 0, 0.0)
	draw_polygon(PackedVector2Array([Vector2(-60, -60), Vector2(vs.x + 60, -60), Vector2(vs.x + 60, vs.y * 0.3), Vector2(-60, vs.y * 0.3)]), PackedColorArray([c0, c0, c1, c1]))
	for f in flashes:
		if f["t"] <= 0.0:
			var p := Vector2(float(f["x"]) * vs.x, float(f["y"]) * vs.y)
			var a_ := 1.0 - float(f["life"]) / 0.18
			draw_circle(p, 26.0 * a_ + 6.0, Color(1, 1, 1, 0.35 * a_))
			draw_line(p - Vector2(40, 0) * a_, p + Vector2(40, 0) * a_, Color(1, 1, 1, 0.8 * a_), 2.0)
			draw_line(p - Vector2(0, 40) * a_, p + Vector2(0, 40) * a_, Color(1, 1, 1, 0.8 * a_), 2.0)


func _draw_floor(vs: Vector2) -> void:
	var top := gy - 0.20 * k
	var mid := gy + 0.12 * k
	var cv := Color(0.12, 0.14, 0.24).lerp(accent, 0.22)
	draw_polygon(PackedVector2Array([Vector2(-60, top), Vector2(vs.x + 60, top), Vector2(vs.x + 60, mid), Vector2(-60, mid)]),
		PackedColorArray([cv.lightened(0.12), cv.lightened(0.12), cv, cv]))
	draw_line(Vector2(-60, top), Vector2(vs.x + 60, top), Color(1, 1, 1, 0.35), 2.0)
	# juntas de la lona
	var step := 1.0 * k
	var x0 := fmod(vs.x * 0.5 - cam_z * k, step)
	var xx := x0 - step
	while xx < vs.x + step:
		draw_line(Vector2(xx, top), Vector2(xx - 0.35 * k, mid), Color(0, 0, 0, 0.12), 1.5)
		xx += step
	# faldón del ring con el color de la arena y el logo
	draw_rect(Rect2(Vector2(-60, mid), Vector2(vs.x + 120, vs.y - mid + 60)), accent.darkened(0.45))
	draw_rect(Rect2(Vector2(-60, mid), Vector2(vs.x + 120, 5.0)), accent.lightened(0.25))
	if logo != null:
		var lh := minf(vs.y - mid - 14.0, 0.7 * k)
		if lh > 20.0:
			var lw := lh * float(logo.get_width()) / float(logo.get_height())
			var sp := lw * 1.8
			var lx := fmod(vs.x * 0.5 - cam_z * k * 1.0, sp) - sp
			while lx < vs.x + sp:
				draw_texture_rect(logo, Rect2(Vector2(lx, mid + 10.0), Vector2(lw, lh)), false, Color(1, 1, 1, 0.8))
				lx += sp


func _shadow(z: float) -> void:
	var p := _pz(z, 0.0)
	draw_set_transform(p + Vector2(0, 4), 0.0, Vector2(1.0, 0.16))
	draw_circle(Vector2.ZERO, 0.42 * k, Color(0, 0, 0, 0.35))
	draw_set_transform(Vector2.ZERO, 0.0, Vector2.ONE)


func _rope_col(i: int) -> Color:
	return [Color(0.92, 0.18, 0.22), Color(0.96, 0.96, 0.96), Color(0.2, 0.45, 0.98)][i]


func _draw_ropes_far(vs: Vector2) -> void:
	var yb := gy - 0.20 * k
	for s in [-1, 1]:
		var px := _pz(float(s) * ROPE_HALF, 0.0).x
		var amp: float = rope_amp[s]
		var sway := sin(t * 28.0) * amp * 0.05 * k
		# poste lejano
		draw_line(Vector2(px, yb), Vector2(px, yb - 1.5 * k), Color(0.15, 0.15, 0.2), 0.09 * k)
		draw_rect(Rect2(Vector2(px - 0.07 * k, yb - 1.45 * k), Vector2(0.14 * k, 1.0 * k)), (Color(0.9, 0.15, 0.2) if s < 0 else Color(0.15, 0.4, 0.95)).darkened(0.2))
	# cuerdas largas del fondo, de poste a poste
	var xa := _pz(-ROPE_HALF, 0.0).x
	var xb := _pz(ROPE_HALF, 0.0).x
	for i in range(3):
		var y := yb - ROPE_H[i] * k * 0.95
		draw_line(Vector2(xa, y), Vector2(xb, y), OUTLINE, 0.05 * k)
		draw_line(Vector2(xa, y), Vector2(xb, y), _rope_col(i).darkened(0.25), 0.036 * k)


func _draw_ropes_near(vs: Vector2) -> void:
	var yn := gy + 0.12 * k
	for s in [-1, 1]:
		var px := _pz(float(s) * ROPE_HALF, 0.0).x
		var amp: float = rope_amp[s]
		var sway := sin(t * 30.0) * amp * 0.06 * k
		var pcol := Color(0.9, 0.15, 0.2) if s < 0 else Color(0.15, 0.4, 0.95)
		# cuerdas de los extremos: del poste cercano al lejano (profundidad)
		for i in range(3):
			var h: float = ROPE_H[i]
			var p0 := Vector2(px, yn - h * k * 1.08 + sway)
			var p1 := Vector2(px, gy - 0.20 * k - h * k * 0.95 + sway)
			draw_line(p0, p1, _rope_col(i).darkened(0.1), 0.04 * k)
		draw_rect(Rect2(Vector2(px - 0.09 * k, yn - 1.5 * k), Vector2(0.18 * k, 1.5 * k)), Color(0.12, 0.12, 0.16))
		draw_rect(Rect2(Vector2(px - 0.12 * k, yn - 1.42 * k), Vector2(0.24 * k, 1.0 * k)), pcol.darkened(0.15))
		draw_rect(Rect2(Vector2(px - 0.12 * k, yn - 1.42 * k), Vector2(0.06 * k, 1.0 * k)), pcol.lightened(0.25))
	# cuerda cercana (semitransparente para no tapar la acción)
	var xa := _pz(-ROPE_HALF, 0.0).x
	var xb := _pz(ROPE_HALF, 0.0).x
	for i in range(3):
		var y := yn - ROPE_H[i] * k * 1.08
		var c := _rope_col(i)
		c.a = 0.4
		draw_line(Vector2(xa, y), Vector2(xb, y), c, 0.045 * k)


func _draw_stool(i: int) -> void:
	var z: float = (-2.2 if i == 0 else 2.2)
	var p := _pz(z, 0.0)
	var c := Color(0.8, 0.12, 0.14) if i == 0 else Color(0.14, 0.32, 0.85)
	for dx in [-0.17, 0.17]:
		draw_line(p + Vector2(dx * k, 0), p + Vector2(dx * 0.7 * k, -0.5 * k), OUTLINE, 0.06 * k)
		draw_line(p + Vector2(dx * k, 0), p + Vector2(dx * 0.7 * k, -0.5 * k), c.darkened(0.2), 0.04 * k)
	draw_rect(Rect2(p + Vector2(-0.23 * k, -0.54 * k), Vector2(0.46 * k, 0.06 * k)), c)
	draw_rect(Rect2(p + Vector2(-0.23 * k, -0.54 * k), Vector2(0.46 * k, 0.015 * k)), c.lightened(0.4))
	# cubo y toalla del entrenador
	var tw := Art.gear("towel")
	if tw != null:
		var sx := 0.34 * k
		draw_texture_rect(tw, Rect2(p + Vector2((0.5 if i == 0 else -0.8) * k, -0.5 * k), Vector2(sx * 0.7, sx)), false, Color(1, 1, 1, 0.9))


func _draw_confetti() -> void:
	if not confetti_on:
		return
	for c in confetti:
		draw_set_transform(c["p"], c["r"], Vector2.ONE)
		draw_rect(Rect2(Vector2(-c["w"] * 0.5, -2), Vector2(c["w"], 4)), c["c"])
	draw_set_transform(Vector2.ZERO, 0.0, Vector2.ONE)


# ---- muñeco ----

func _tc(col: Color, depth_far: bool, tintc: Color) -> Color:
	var c := col * tintc
	c.a = col.a
	return c.darkened(0.16) if depth_far else c


func _limb(p0: Vector2, p1: Vector2, w: float, col: Color) -> void:
	draw_line(p0, p1, OUTLINE, w + 0.028 * k)
	draw_circle(p0, (w + 0.028 * k) * 0.5, OUTLINE)
	draw_circle(p1, (w + 0.028 * k) * 0.5, OUTLINE)
	draw_line(p0, p1, col, w)
	draw_circle(p0, w * 0.5, col)
	draw_circle(p1, w * 0.5, col)
	var n := (p1 - p0).orthogonal().normalized()
	draw_line(p0 - n * w * 0.18, p1 - n * w * 0.18, col.lightened(0.22), w * 0.22)


func _draw_figure(bx, r: Dictionary, wear_lv: float, _s: float, tintc: Color) -> void:
	var J := _joints(bx, r)
	var col: Dictionary = r["col"]
	var is_ref: bool = col["ref"]
	var f: float = bx.facing if "facing" in bx else 1.0
	if is_ref:
		f = 1.0
	var skin: Color = (col["skin"] as Color).lerp(Color(0.78, 0.3, 0.26), wear_lv * 0.22)
	var shorts: Color = col["shorts"]
	var gl: Color = col["gloves"]
	var shoes: Color = col["shoes"]
	var shirt: Color = Color(0.94, 0.94, 0.96)
	var P := func(n): return J[n]["p"]
	var X := func(n): return float(J[n]["x"])
	var far_L: bool = X.call("UpperArm_L") > X.call("UpperArm_R")
	var legs: Array = ["L", "R"] if X.call("Thigh_L") > X.call("Thigh_R") else ["R", "L"]
	var arms: Array = ["L", "R"] if far_L else ["R", "L"]
	# piernas: la lejana primero
	for i in range(2):
		var s: String = legs[i]
		var far := i == 0
		var th: Vector2 = P.call("Thigh_" + s)
		var kn: Vector2 = P.call("Shin_" + s)
		var an: Vector2 = P.call("Foot_" + s)
		_limb(kn, an, 0.115 * k, _tc(skin, far, tintc))
		var toe := an + Vector2(f * 0.17 * k, 0.03 * k)
		_limb(an + Vector2(0, -0.02 * k), toe, 0.085 * k, _tc(shoes, far, tintc).lightened(0.05))
		_limb(th, kn, 0.17 * k, _tc(shorts if not is_ref else Color(0.1, 0.1, 0.13), far, tintc))
	# brazo lejano
	_arm(J, arms[0], skin, gl, tintc, true, is_ref, shirt)
	# torso
	_torso(J, skin, shorts, tintc, is_ref, shirt)
	# cabeza
	_head(bx, J, r, f, skin, tintc, wear_lv, is_ref)
	# brazo cercano
	_arm(J, arms[1], skin, gl, tintc, false, is_ref, shirt)


func _arm(J: Dictionary, s: String, skin: Color, gl: Color, tintc: Color, far: bool, is_ref: bool, shirt: Color) -> void:
	var sh: Vector2 = J["UpperArm_" + s]["p"]
	var el: Vector2 = J["Forearm_" + s]["p"]
	var wr: Vector2 = J["Hand_" + s]["p"]
	var arm_col := _tc(shirt if is_ref else skin, far, tintc)
	_limb(sh, el, 0.115 * k, arm_col)
	_limb(el, wr, 0.095 * k, _tc(skin, far, tintc))
	if not is_ref:
		var dir := (wr - el).normalized()
		var wrap := wr - dir * 0.08 * k
		_limb(wrap, wr, 0.1 * k, _tc(Color(0.93, 0.93, 0.9), far, tintc))     # vendas
		var gc := wr + dir * 0.075 * k
		var gcol := _tc(gl, far, tintc)
		draw_circle(gc, 0.135 * k, OUTLINE)
		draw_circle(gc, 0.11 * k, gcol)
		draw_circle(gc + Vector2(-0.035, -0.04) * k, 0.045 * k, gcol.lightened(0.35))
		draw_arc(gc, 0.10 * k, 0.4, 2.6, 10, gcol.darkened(0.35), 0.015 * k)
		draw_line(wr - dir * 0.02 * k - dir.orthogonal() * 0.08 * k, wr - dir * 0.02 * k + dir.orthogonal() * 0.08 * k, Color(0.95, 0.95, 0.95, 0.9), 0.03 * k)
	else:
		draw_circle(wr, 0.06 * k, skin)


func _torso(J: Dictionary, skin: Color, shorts: Color, tintc: Color, is_ref: bool, shirt: Color) -> void:
	var hips: Vector2 = J["Hips"]["p"]
	var sp: Vector2 = J["Spine"]["p"]
	var ch: Vector2 = J["Chest"]["p"]
	var nk: Vector2 = J["Neck"]["p"]
	var axis := (nk - hips)
	if axis.length() < 1.0:
		return
	var n := axis.orthogonal().normalized()
	var body := _tc(shirt if is_ref else skin, false, tintc)
	var pts := PackedVector2Array([hips + n * 0.15 * k, sp + n * 0.165 * k, ch + n * 0.19 * k, nk + n * 0.09 * k,
		nk - n * 0.09 * k, ch - n * 0.19 * k, sp - n * 0.165 * k, hips - n * 0.15 * k])
	var big := PackedVector2Array()
	for p in pts:
		big.append(p + (p - (hips + nk) * 0.5).normalized() * 0.014 * k)
	draw_colored_polygon(big, OUTLINE)
	draw_colored_polygon(pts, body)
	if not is_ref:
		# abdominales y pectorales sugeridos
		var mid := (sp + ch) * 0.5
		draw_line(mid - axis.normalized() * 0.1 * k, mid + axis.normalized() * 0.12 * k, body.darkened(0.25), 0.012 * k)
		for i in range(3):
			var c := hips + axis * (0.18 + 0.14 * i)
			draw_line(c - n * 0.07 * k, c + n * 0.07 * k, body.darkened(0.18), 0.01 * k)
		draw_arc(ch + n * 0.07 * k, 0.07 * k, 0.0, PI, 10, body.darkened(0.2), 0.012 * k)
		# pantalón corto con cinturilla
		var sc := _tc(shorts, false, tintc)
		var low := hips - axis.normalized() * 0.20 * k
		var sp2 := PackedVector2Array([hips + n * 0.175 * k + axis.normalized() * 0.07 * k, hips - n * 0.175 * k + axis.normalized() * 0.07 * k,
			low - n * 0.2 * k, low + n * 0.2 * k])
		draw_colored_polygon(sp2, sc)
		draw_line(sp2[0], sp2[1], sc.lightened(0.55), 0.045 * k)
		draw_line(sp2[0] - axis.normalized() * 0.03 * k, sp2[1] - axis.normalized() * 0.03 * k, sc.darkened(0.3), 0.012 * k)
	else:
		draw_line(hips + n * 0.15 * k, hips - n * 0.15 * k, Color(0.1, 0.1, 0.13), 0.12 * k)


func _head(bx, J: Dictionary, r: Dictionary, f: float, skin: Color, tintc: Color, wear_lv: float, is_ref: bool) -> void:
	var nk: Vector2 = J["Neck"]["p"]
	var hd: Vector2 = J["Head"]["p"]
	var dir := hd - nk
	if dir.length() < 1.0:
		dir = Vector2(0, -0.07 * k)
	var rot := Vector2(0, -1).angle_to(dir)
	var c := hd + dir.normalized() * 0.075 * k
	var rad := 0.135 * k
	# cuello
	_limb(nk, hd, 0.09 * k, _tc(skin, false, tintc))
	var face: Texture2D = r["face"]
	draw_circle(c, rad + 0.014 * k, OUTLINE)
	if face != null and not is_ref:
		var pts := PackedVector2Array()
		var uvs := PackedVector2Array()
		var fw := float(face.get_width())
		var fh := float(face.get_height())
		var ucx := (0.5 + 0.03 * f) * fw
		var ucy := 0.37 * fh
		var ur := 0.36 * fw
		for i in range(32):
			var an := TAU * float(i) / 32.0
			var v := Vector2(cos(an), sin(an))
			pts.append(c + v.rotated(rot) * rad)
			uvs.append(Vector2(ucx + v.x * ur, ucy + v.y * ur * 1.05) / Vector2(fw, fh))
		draw_colored_polygon(pts, tintc, uvs, face)
		draw_arc(c, rad, 0.0, TAU, 28, Color(0, 0, 0, 0.25), 0.02 * k)
	else:
		var hair: Color = r["col"]["hair"]
		draw_circle(c, rad, _tc(skin, false, tintc))
		draw_arc(c, rad * 0.92, PI * (1.05 if f > 0.0 else -0.05), PI * (1.95 if f > 0.0 else 0.95), 12, hair, rad * 0.4)
		draw_circle(c + Vector2(f * 0.06 * k, -0.01 * k), 0.012 * k, Color(0.1, 0.1, 0.1))
	if wear_lv > 0.35 and not is_ref:
		draw_circle(c + Vector2(f * 0.05 * k, 0.045 * k), 0.025 * k, Color(0.55, 0.12, 0.12, (wear_lv - 0.35) * 0.9))
	if not is_ref:
		# sudor brillante
		draw_circle(c + Vector2(-f * 0.04 * k, -0.05 * k), 0.016 * k, Color(1, 1, 1, 0.25 + 0.4 * wear_lv))
