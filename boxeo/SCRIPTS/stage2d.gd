extends Node2D
## Escenario 2D por capas: fondo/público -> luces -> lona del ring -> cuerdas traseras -> boxeadores (sprites de ART/sprites,
## animados con transformaciones según el estado del combate) -> postes y cuerdas de los extremos -> efectos -> HUD/controles.
## La lógica, la IA y las animaciones siguen en el esqueleto 3D (solo se leen los huesos para ubicar el brazo que golpea).

const Art = preload("res://SCRIPTS/art.gd")
const Boxer = preload("res://SCRIPTS/boxer.gd")
const Manager = preload("res://SCRIPTS/fight_manager.gd")

const ROPE_HALF := 3.2
const ROPE_H := [0.55, 0.95, 1.35]
const OUTLINE := Color(0.04, 0.04, 0.07)
const BONES := ["Hips", "Spine", "Chest", "Neck", "Head", "UpperArm_L", "Forearm_L", "Hand_L", "UpperArm_R", "Forearm_R", "Hand_R",
	"Thigh_L", "Shin_L", "Foot_L", "Thigh_R", "Shin_R", "Foot_R"]
const ZOOMS := [1.0, 1.15, 0.85]
const BODY_M := 1.85               # altura del sprite en metros (para 182 cm)

var mgr
var a
var b
var referee = null
var boxers: Array = []
var rig: Dictionary = {}                 # boxeador -> {skel, idx, face, col}
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
var sprites: Array = [null, null]        # texturas de los sprites
var glove_tex: Array = [null, null]
var skin_col: Array = [Color(0.7, 0.5, 0.4), Color(0.7, 0.5, 0.4)]
var an: Array = []                       # animación suavizada de cada boxeador
var trail: Array = [[], []]
var spot_tex: Texture2D
# --- animación por fotogramas (lámina maestra) ---
const ANIM_DIR := "res://ART/anim/"
const IMPACT := {"jab": 3, "cross": 1, "hook_l": 2, "hook_r": 2, "upper": 2}
const CLIP_SHADER := "shader_type canvas_item;
uniform sampler2D mask : filter_linear;
uniform vec4 shorts_c : source_color;
uniform vec4 glove_c : source_color;
uniform vec4 skin_t : source_color;
void fragment() {
	vec4 c = texture(TEXTURE, UV);
	vec3 m = texture(mask, UV).rgb;
	float l = dot(c.rgb, vec3(0.3, 0.59, 0.11));
	vec3 r = c.rgb;
	r = mix(r, shorts_c.rgb * (0.30 + l * 1.5), m.r * 0.75);
	r = mix(r, glove_c.rgb * (0.30 + l * 1.5), m.g * 0.8);
	r = mix(r, r * skin_t.rgb, m.b * 0.0);
	COLOR = vec4(min(r * 1.12, vec3(1.0)), c.a) * COLOR;
}"
var clips_ok := false
var clip_meta: Dictionary = {}
var clip_tex: Dictionary = {}
var clip_mask: Dictionary = {}
var fig_h := 250.0
var spr: Array = [null, null]
var st_prev := [-1, -1]
var st_t := [0.0, 0.0]
var cur_clip := ["idle", "idle"]
var cur_frame := [0, 0]


func _new_an() -> Dictionary:
	return {"off": Vector2.ZERO, "rot": 0.0, "sx": 1.0, "sy": 1.0, "squash": 0.0}


func _nearest_glove(hexc: String) -> Texture2D:
	var c := Color.html(hexc)
	var best := "glove_red"
	var bd := 9.0
	for key in Art.GLOVES:
		var d := absf(c.r - Color.html(Art.GLOVES[key]).r) + absf(c.g - Color.html(Art.GLOVES[key]).g) + absf(c.b - Color.html(Art.GLOVES[key]).b)
		if d < bd:
			bd = d
			best = key
	return Art.gear(best)


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
	an = [_new_an(), _new_an()]
	for i in range(2):
		var bx = boxers[i]
		var r := _make_rig(bx, bx.visual, bx.data.get("look", {}), String(bx.data.get("portrait", "")), false)
		if r.is_empty():
			return false
		rig[bx] = r
		sprites[i] = Art.tex(String(bx.data.get("sprite", "")))
		glove_tex[i] = _nearest_glove(String(bx.data.get("look", {}).get("gloves", "#c62828")))
		skin_col[i] = Color.html(String(bx.data.get("look", {}).get("skin", "#b07a55")))
		if sprites[i] != null:
			var img := (sprites[i] as Texture2D).get_image()
			if img != null and not img.is_empty():
				var px := img.get_pixel(int(img.get_width() * 0.5), int(img.get_height() * 0.27))
				if px.a > 0.5:
					skin_col[i] = px
	if referee != null:
		ref_rig = _make_rig(referee, referee.visual, {"skin": "#c9a487", "shorts": "#15151a", "gloves": "#c9a487", "shoes": "#111111", "hair": "#2b2018"}, "", true)
	for bx in boxers:
		var s := Sprite2D.new()
		s.texture = Art.fx("fx_stars")
		s.material = Art.additive()
		s.visible = false
		s.z_index = 20
		add_child(s)
		stars[bx] = s
	spot_tex = _make_spot()
	_load_clips()
	z_index = 0
	return true


func _load_clips() -> void:
	var jf := FileAccess.open(ANIM_DIR + "anim.json", FileAccess.READ)
	if jf == null:
		return
	var meta = JSON.parse_string(jf.get_as_text())
	if typeof(meta) != TYPE_DICTIONARY:
		return
	clip_meta = meta["clips"]
	fig_h = float(meta.get("fig_h", 250.0))
	for nm in clip_meta.keys():
		var t1 = load(ANIM_DIR + nm + ".png")
		var t2 = load(ANIM_DIR + nm + "_m.png")
		if t1 == null or t2 == null:
			return
		clip_tex[nm] = t1
		clip_mask[nm] = t2
	var sh := Shader.new()
	sh.code = CLIP_SHADER
	for i in range(2):
		var sp := Sprite2D.new()
		sp.centered = false
		sp.region_enabled = true
		sp.z_index = 2
		var mat := ShaderMaterial.new()
		mat.shader = sh
		var lk: Dictionary = boxers[i].data.get("look", {})
		mat.set_shader_parameter("shorts_c", Color.html(String(lk.get("shorts", "#c62828"))))
		mat.set_shader_parameter("glove_c", Color.html(String(lk.get("gloves", "#c62828"))))
		var sk: Color = skin_col[i]
		var ref := Color(0.78, 0.52, 0.40)
		mat.set_shader_parameter("skin_t", Color(clampf(sk.r / ref.r, 0.4, 1.4), clampf(sk.g / ref.g, 0.4, 1.4), clampf(sk.b / ref.b, 0.4, 1.4)))
		sp.material = mat
		sp.visible = false
		add_child(sp)
		spr[i] = sp
	clips_ok = true


## Elige clip y fotograma según el estado real del boxeador (máquina de estados del combate).
func _pick_clip(i: int) -> Array:
	var bx = boxers[i]
	var st_s: float = st_t[i]
	var clip := "idle"
	var idx := 0
	var f: float = bx.facing
	match bx.state:
		Boxer.S.IDLE:
			clip = "idle"
			idx = int(t * 6.0 + float(i) * 3.0)
		Boxer.S.MOVE:
			var fwd: bool = float(bx.move_cur) * f >= 0.0 or true
			var mc: float = float(bx.move_cur)
			if absf(mc) > 0.55:
				clip = "run"
				idx = int(t * 11.0)
			else:
				clip = "walk"
				idx = int(t * 7.0)
			if mc < 0.0:
				idx = int(clip_meta[clip]["n"]) - 1 - (idx % int(clip_meta[clip]["n"]))
		Boxer.S.ATTACK:
			var at: Dictionary = bx.table["attacks"][bx.cur_attack]
			var kind := String(bx.cur_attack)
			var hand := String(at.get("hand", "L"))
			if kind.contains("upper"):
				clip = "upper"
			elif kind.contains("hook") or kind.contains("overhand"):
				clip = "hook_l" if hand == "L" else "hook_r"
			else:
				clip = "jab" if hand == "L" else "cross"
			var n: int = int(clip_meta[clip]["n"])
			var p: int = mini(int(IMPACT.get(clip, 2)), n - 1)
			var su: float = float(at["startup"])
			var ac: float = float(at["active"])
			var rc: float = float(at["recovery"])
			var tt: float = float(bx.attack_t)
			if tt < su:
				idx = int(roundf(lerpf(0.0, float(maxi(p - 1, 0)), clampf(tt / maxf(su, 0.01), 0.0, 1.0))))
			elif tt < su + ac:
				idx = p
			else:
				var r := clampf((tt - su - ac) / maxf(rc, 0.01), 0.0, 1.0)
				idx = p + 1 + int(r * float(n - 2 - p))
		Boxer.S.BLOCK:
			clip = "block"
			idx = mini(int(st_s * 16.0), 2)
		Boxer.S.DODGE:
			clip = "dodge"
			idx = int(clampf(st_s / 0.35, 0.0, 0.99) * float(clip_meta[clip]["n"]))
		Boxer.S.HIT:
			clip = "hit"
			idx = mini(int(st_s * 16.0), int(clip_meta[clip]["n"]) - 1)
		Boxer.S.STAGGER:
			clip = "stagger"
			idx = int(t * 5.0)
		Boxer.S.DOWN:
			clip = "down"
			idx = mini(int(st_s / 0.12), int(clip_meta[clip]["n"]) - 1)
		Boxer.S.GETUP:
			clip = "down"
			var nn: int = int(clip_meta[clip]["n"])
			idx = nn - 1 - mini(int(st_s / 0.12), nn - 1)
		Boxer.S.WIN:
			clip = "win"
			idx = int(t * 5.0)
		Boxer.S.LOSE:
			clip = "lose"
			idx = mini(int(st_s / 0.2), int(clip_meta[clip]["n"]) - 1)
	var cnt: int = int(clip_meta[clip]["n"])
	idx = clampi(idx % cnt if bx.state in [Boxer.S.IDLE, Boxer.S.MOVE, Boxer.S.STAGGER, Boxer.S.WIN] else idx, 0, cnt - 1)
	return [clip, idx]



func _make_spot() -> Texture2D:
	var g := Gradient.new()
	g.colors = PackedColorArray([Color(1, 1, 1, 0.55), Color(1, 1, 1, 0.0)])
	var gt := GradientTexture2D.new()
	gt.gradient = g
	gt.fill = GradientTexture2D.FILL_RADIAL
	gt.fill_from = Vector2(0.5, 0.5)
	gt.fill_to = Vector2(1.0, 0.5)
	gt.width = 128
	gt.height = 128
	return gt


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

func _rope_col(i: int) -> Color:
	return [Color(0.92, 0.18, 0.22), Color(0.96, 0.96, 0.96), Color(0.2, 0.45, 0.98)][i]


func _draw_confetti() -> void:
	if not confetti_on:
		return
	for c in confetti:
		draw_set_transform(c["p"], c["r"], Vector2.ONE)
		draw_rect(Rect2(Vector2(-c["w"] * 0.5, -2), Vector2(c["w"], 4)), c["c"])
	draw_set_transform(Vector2.ZERO, 0.0, Vector2.ONE)



func _process(delta: float) -> void:
	t += delta
	var vs := _vs()
	if a == null or b == null:
		return
	var mid: float = (a.position.z + b.position.z) * 0.5
	cam_z = lerpf(cam_z, mid, clampf(delta * 5.0, 0.0, 1.0))
	zoom_mul = lerpf(zoom_mul, ZOOMS[zoom_idx] + kick, clampf(delta * 4.0, 0.0, 1.0))
	kick = move_toward(kick, 0.0, delta * 0.25)
	var dist := absf(a.position.z - b.position.z)
	var target_k := clampf(vs.x / (dist + 4.6), vs.y * 0.21, vs.y * 0.29) * zoom_mul
	k = lerpf(k, target_k, clampf(delta * 5.0, 0.0, 1.0))
	gy = vs.y * 0.74
	shake = move_toward(shake, 0.0, delta * 40.0)
	position = Vector2(randf_range(-shake, shake), randf_range(-shake, shake))
	for s in rope_amp.keys():
		rope_amp[s] = move_toward(rope_amp[s], 0.0, delta * 1.6)
	for i in range(2):
		tint[i] = maxf(0.0, tint[i] - delta)
		_animate(i, delta)
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
	for i in range(2):
		var bx = boxers[i]
		var s: Sprite2D = stars[bx]
		if s.texture == null:
			continue
		var dizzy: bool = bx.state == Boxer.S.STAGGER or bx.state == Boxer.S.DOWN
		s.visible = dizzy
		if dizzy:
			var hp := _sprite_head(i)
			s.position = hp + Vector2(0, -0.08 * k)
			s.rotation = t * 2.5
			var base := (k * 0.9) / maxf(float(s.texture.get_width()), 1.0)
			s.scale = Vector2(base, base * 0.5)
	queue_redraw()


# ---------------- animación de los sprites ----------------

func _height_px(i: int) -> float:
	var hm: float = float(boxers[i].data.get("height_cm", 182)) / 182.0
	return BODY_M * hm * k


func _sprite_head(i: int) -> Vector2:
	var bx = boxers[i]
	var foot := _pz(bx.position.z, 0.0)
	return _xf(i, foot + Vector2(0, -0.93 * _height_px(i)), foot)


## Aplica la pose animada (desplazamiento, giro y escala alrededor de los pies) a un punto de pantalla.
func _xf(i: int, p: Vector2, foot: Vector2) -> Vector2:
	var d: Dictionary = an[i]
	var rel := (p - foot) * Vector2(float(d["sx"]), float(d["sy"]))
	return foot + d["off"] + rel.rotated(float(d["rot"]))


func _animate(i: int, delta: float) -> void:
	var bx = boxers[i]
	if st_prev[i] != bx.state:
		st_prev[i] = bx.state
		st_t[i] = 0.0
	st_t[i] += delta
	var f: float = bx.facing
	var H := _height_px(i)
	var o := Vector2.ZERO
	var rot := 0.0
	var sx := 1.0
	var sy := 1.0
	var rate := 16.0
	var ph := t * 3.2 + float(i) * 1.7
	match bx.state:
		Boxer.S.IDLE:
			sy = 1.0 + sin(ph) * 0.012
			o.x = sin(ph * 0.5) * 0.008 * H
			rot = sin(ph * 0.5) * 0.012
		Boxer.S.MOVE:
			var sp := t * 9.0 + float(i)
			o.y = -absf(sin(sp)) * 0.020 * H
			rot = f * clampf(float(bx.move_cur), -1.0, 1.0) * 0.05
			sy = 1.0 - absf(sin(sp)) * 0.012
		Boxer.S.ATTACK:
			rate = 26.0
			var at: Dictionary = bx.table["attacks"][bx.cur_attack]
			var su: float = float(at["startup"])
			var ac: float = float(at["active"])
			var rc: float = float(at["recovery"])
			var tt: float = float(bx.attack_t)
			var kind := String(bx.cur_attack)
			var body := String(at.get("target", "head")) == "body"
			var up := kind.contains("upper")
			var hook := kind.contains("hook") or kind.contains("overhand")
			var w := 0.0              # 0 = sin golpe, 1 = golpe a fondo
			var pre := 0.0            # carga previa
			if tt < su:
				pre = clampf(tt / maxf(su, 0.01), 0.0, 1.0)
			elif tt < su + ac:
				w = 1.0
			else:
				w = clampf(1.0 - (tt - su - ac) / maxf(rc, 0.01), 0.0, 1.0)
			var lunge := 0.24
			var lean := 0.14
			if hook:
				lunge = 0.13
				lean = 0.20
			if up:
				lunge = 0.07
				lean = 0.07
			o.x = f * (lunge * H * w - 0.035 * H * pre)
			rot = f * (lean * w - 0.045 * pre)
			if body:
				sy = 1.0 - 0.10 * maxf(w, pre)
				rot = f * (0.20 * w - 0.03 * pre)
				o.y = 0.0
			if up:
				sy = 1.0 - 0.09 * pre + 0.06 * w
				o.y = -0.02 * H * w
			elif hook:
				sx = 1.0 + 0.03 * w
		Boxer.S.BLOCK:
			o.x = -f * 0.025 * H
			rot = -f * 0.035
			sx = 0.97
			sy = 0.985
		Boxer.S.DODGE:
			rate = 24.0
			var dt: float = clampf(float(bx.dodge_t) / 0.35, 0.0, 1.0)
			var cur := String(bx.last_clip)
			var k2 := sin(dt * PI)
			sy = 1.0 - 0.14 * k2
			o.x = -f * 0.10 * H * k2 * (1.0 if cur.ends_with("left") else -1.0 if cur.ends_with("right") else 1.0)
			rot = -f * 0.10 * k2
		Boxer.S.HIT:
			rate = 30.0
			o.x = -f * 0.075 * H
			rot = -f * 0.13
			sy = 0.985
		Boxer.S.STAGGER:
			o.x = -f * 0.04 * H + sin(t * 12.0) * 0.012 * H
			rot = -f * 0.10 + sin(t * 11.0) * 0.045
		Boxer.S.DOWN:
			rate = 9.0
			rot = -f * (PI * 0.5 - 0.08)
			o.y = -0.30 * (H * 0.5)
			o.x = -f * 0.05 * H
		Boxer.S.GETUP:
			rate = 7.0
			rot = -f * 0.55
			o.y = -0.10 * H
			sy = 0.93
		Boxer.S.WIN:
			o.y = -absf(sin(t * 5.0 + float(i))) * 0.05 * H
			sy = 1.02
		Boxer.S.LOSE:
			rot = f * 0.16
			sy = 0.94
			o.x = f * 0.02 * H
	if clips_ok:
		# la postura la dan los fotogramas; aquí solo queda desplazamiento físico (avance, retroceso, empuje)
		rot *= 0.25
		sx = 1.0 + (sx - 1.0) * 0.2
		sy = 1.0 + (sy - 1.0) * 0.2
		if bx.state == Boxer.S.DOWN or bx.state == Boxer.S.GETUP or bx.state == Boxer.S.LOSE:
			rot = 0.0
			o.y = 0.0
			sy = 1.0
		if bx.state == Boxer.S.IDLE:        # juego de pies en guardia: balanceo y cambio de peso
			o.x += sin(t * 1.7 + float(i) * 2.0) * 0.035 * H
		if bx.state == Boxer.S.DOWN:
			o.x = -f * 0.10 * H
	var d: Dictionary = an[i]
	var w2 := 1.0 - exp(-rate * delta)
	d["off"] = (d["off"] as Vector2).lerp(o, w2)
	d["rot"] = lerpf(float(d["rot"]), rot, w2)
	d["sx"] = lerpf(float(d["sx"]), sx, w2)
	d["sy"] = lerpf(float(d["sy"]), sy, w2)
	# estela de movimiento durante los golpes
	var tr: Array = trail[i]
	if not clips_ok and bx.state == Boxer.S.ATTACK and bx.attack_phase() != "startup":
		tr.append({"off": d["off"], "rot": d["rot"], "life": 0.12})
	for e in tr:
		e["life"] -= delta
	trail[i] = tr.filter(func(e): return e["life"] > 0.0)


# ---------------- dibujo por capas ----------------

func _draw() -> void:
	var vs := _vs()
	_draw_backdrop(vs)            # 1. fondo y público
	_draw_lights(vs)              # 2. iluminación
	_draw_floor(vs)               # 3. lona y faldón del ring
	_draw_ropes_back(vs)          # 4. cuerdas y postes traseros
	if mgr != null and mgr.phase == Manager.Phase.REST:
		pass
	_draw_ropes_front(vs)         # 5. postes y cuerdas de los extremos (detrás de los boxeadores para no taparlos)
	var order := [0, 1]
	if boxers[1].position.z < boxers[0].position.z:
		order = [1, 0]
	for i in order:                # 5. boxeadores
		_draw_fighter(i)           # 6. boxeadores
	_draw_confetti()
	_draw_corner_logo(vs)         # un solo logotipo discreto


func _draw_backdrop(vs: Vector2) -> void:
	draw_rect(Rect2(Vector2(-60, -60), vs + Vector2(120, 120)), Color(0.03, 0.04, 0.08))
	if backdrop != null:
		var tw := float(backdrop.get_width())
		var th := float(backdrop.get_height())
		var sc := maxf(vs.x * 1.06 / tw, (gy + 10.0) / th)
		var sz := Vector2(tw, th) * sc
		var off := clampf(-cam_z * k * 0.04, -vs.x * 0.025, vs.x * 0.025)
		draw_texture_rect(backdrop, Rect2(Vector2((vs.x - sz.x) * 0.5 + off, gy - sz.y + 24.0), sz), false, Color(0.80, 0.80, 0.86))
	# contraste: vignette superior e inferior
	var c0 := Color(0, 0, 0, 0.50)
	var c1 := Color(0, 0, 0, 0.0)
	draw_polygon(PackedVector2Array([Vector2(-60, -60), Vector2(vs.x + 60, -60), Vector2(vs.x + 60, vs.y * 0.34), Vector2(-60, vs.y * 0.34)]), PackedColorArray([c0, c0, c1, c1]))
	var sd := Color(0, 0, 0, 0.35)
	draw_polygon(PackedVector2Array([Vector2(-60, gy - 0.5 * k), Vector2(vs.x + 60, gy - 0.5 * k), Vector2(vs.x + 60, gy), Vector2(-60, gy)]), PackedColorArray([c1, c1, sd, sd]))
	for f in flashes:
		if f["t"] <= 0.0:
			var p := Vector2(float(f["x"]) * vs.x, float(f["y"]) * vs.y * 0.8)
			var a_ := 1.0 - float(f["life"]) / 0.18
			draw_circle(p, 22.0 * a_ + 5.0, Color(1, 1, 1, 0.35 * a_))
			draw_line(p - Vector2(36, 0) * a_, p + Vector2(36, 0) * a_, Color(1, 1, 1, 0.8 * a_), 2.0)
			draw_line(p - Vector2(0, 36) * a_, p + Vector2(0, 36) * a_, Color(1, 1, 1, 0.8 * a_), 2.0)


func _draw_lights(vs: Vector2) -> void:
	if spot_tex == null:
		return
	# cono de luz cenital sobre el ring (aditivo, muy suave)
	var cx := vs.x * 0.5
	var glow := accent.lerp(Color.WHITE, 0.65)
	glow.a = 0.07
	for s in [-0.28, 0.0, 0.28]:
		var top := Vector2(cx + s * vs.x, -10.0)
		var foot := Vector2(cx + s * vs.x * 0.55, gy - 0.1 * k)
		draw_colored_polygon(PackedVector2Array([top - Vector2(30, 0), top + Vector2(30, 0), foot + Vector2(0.55 * k, 0), foot - Vector2(0.55 * k, 0)]), glow)
	# charco de luz sobre la lona bajo los boxeadores
	for i in range(2):
		var p := _pz(boxers[i].position.z, 0.0)
		draw_set_transform(p, 0.0, Vector2(1.0, 0.22))
		draw_texture_rect(spot_tex, Rect2(Vector2(-0.9 * k, -0.9 * k), Vector2(1.8 * k, 1.8 * k)), false, Color(1, 0.95, 0.85, 0.30))
		draw_set_transform(Vector2.ZERO, 0.0, Vector2.ONE)


func _draw_floor(vs: Vector2) -> void:
	var top := gy - 0.28 * k
	var mid := gy + 0.14 * k
	var cv := Color(0.14, 0.16, 0.26).lerp(accent, 0.20)
	draw_polygon(PackedVector2Array([Vector2(-60, top), Vector2(vs.x + 60, top), Vector2(vs.x + 60, mid), Vector2(-60, mid)]),
		PackedColorArray([cv.lightened(0.14), cv.lightened(0.14), cv, cv]))
	draw_line(Vector2(-60, top), Vector2(vs.x + 60, top), Color(1, 1, 1, 0.28), 2.0)
	var step := 1.0 * k
	var x0 := fmod(vs.x * 0.5 - cam_z * k, step)
	var xx := x0 - step
	while xx < vs.x + step:
		draw_line(Vector2(xx, top), Vector2(xx - 0.30 * k, mid), Color(0, 0, 0, 0.10), 1.5)
		xx += step
	# faldón con el color de la arena (sin logotipos repetidos)
	var ap := accent.darkened(0.5)
	draw_polygon(PackedVector2Array([Vector2(-60, mid), Vector2(vs.x + 60, mid), Vector2(vs.x + 60, vs.y + 60), Vector2(-60, vs.y + 60)]),
		PackedColorArray([ap.lightened(0.10), ap.lightened(0.10), ap.darkened(0.35), ap.darkened(0.35)]))
	draw_rect(Rect2(Vector2(-60, mid), Vector2(vs.x + 120, 4.0)), accent.lightened(0.3))


func _draw_corner_logo(vs: Vector2) -> void:
	if logo == null:
		return
	var h := vs.y * 0.10
	var w := h * float(logo.get_width()) / float(logo.get_height())
	draw_set_transform(-position, 0.0, Vector2.ONE)
	draw_texture_rect(logo, Rect2(Vector2(vs.x - w - 16.0, vs.y * 0.155), Vector2(w, h)), false, Color(1, 1, 1, 0.55))
	draw_set_transform(Vector2.ZERO, 0.0, Vector2.ONE)


func _draw_ropes_back(vs: Vector2) -> void:
	var yb := gy - 0.28 * k
	var xa := _pz(-ROPE_HALF, 0.0).x
	var xb := _pz(ROPE_HALF, 0.0).x
	for s in [-1, 1]:
		var px := _pz(float(s) * ROPE_HALF, 0.0).x
		var pc := Color(0.82, 0.14, 0.18) if s < 0 else Color(0.14, 0.36, 0.88)
		draw_line(Vector2(px, yb), Vector2(px, yb - 1.55 * k), Color(0.10, 0.10, 0.14), 0.085 * k)
		draw_rect(Rect2(Vector2(px - 0.06 * k, yb - 1.45 * k), Vector2(0.12 * k, 1.0 * k)), pc.darkened(0.25))
	for i in range(3):
		var y: float = yb - ROPE_H[i] * k * 0.95
		draw_line(Vector2(xa, y), Vector2(xb, y), OUTLINE, 0.048 * k)
		draw_line(Vector2(xa, y), Vector2(xb, y), _rope_col(i).darkened(0.15), 0.032 * k)
		draw_line(Vector2(xa, y - 0.008 * k), Vector2(xb, y - 0.008 * k), _rope_col(i).lightened(0.35), 0.008 * k)


func _draw_ropes_front(vs: Vector2) -> void:
	# solo los postes y las cuerdas de los extremos (no cruzan a los boxeadores)
	var yn := gy + 0.14 * k
	var yb := gy - 0.28 * k
	for s in [-1, 1]:
		var px := _pz(float(s) * ROPE_HALF, 0.0).x
		var amp: float = rope_amp[s]
		var sway := sin(t * 30.0) * amp * 0.06 * k
		var pcol := Color(0.82, 0.14, 0.18) if s < 0 else Color(0.14, 0.36, 0.88)
		for i in range(3):
			var h: float = ROPE_H[i]
			var p0 := Vector2(px, yn - h * k * 1.08 + sway)
			var p1 := Vector2(px, yb - h * k * 0.95 + sway)
			draw_line(p0, p1, OUTLINE, 0.05 * k)
			draw_line(p0, p1, _rope_col(i).darkened(0.1), 0.034 * k)
		draw_rect(Rect2(Vector2(px - 0.085 * k, yn - 1.55 * k), Vector2(0.17 * k, 1.55 * k)), Color(0.10, 0.10, 0.14))
		draw_rect(Rect2(Vector2(px - 0.115 * k, yn - 1.45 * k), Vector2(0.23 * k, 1.0 * k)), pcol.darkened(0.12))
		draw_rect(Rect2(Vector2(px - 0.115 * k, yn - 1.45 * k), Vector2(0.06 * k, 1.0 * k)), pcol.lightened(0.28))


func _draw_referee() -> void:
	# figura de fondo, más lejos y más tenue, para que no compita con los boxeadores
	var k0 := k
	var gy0 := gy
	k = k0 * 0.82
	gy = gy0 - 0.20 * k0
	_draw_figure(referee, ref_rig, 0.0, 1.0, Color(0.58, 0.60, 0.74))
	k = k0
	gy = gy0


## Boxeador = sprite de cuerpo entero con pose animada + brazo/guante que sigue al golpe.
func _draw_fighter(i: int) -> void:
	var bx = boxers[i]
	var tex: Texture2D = sprites[i]
	if tex == null:
		_draw_figure(bx, rig[bx], wear[i], 1.0, Color.WHITE)
		return
	var f: float = bx.facing
	var H := _height_px(i)
	var W := H * float(tex.get_width()) / float(tex.get_height())
	var foot := _pz(bx.position.z, 0.0) + Vector2(0, 0.05 * H + clampf(bx.position.x, -1.6, 1.6) * 0.05 * k)
	var d: Dictionary = an[i]
	var seat: bool = mgr != null and mgr.phase == Manager.Phase.REST and bx.visual.position.y < -0.15
	# sombra de contacto
	var lying: bool = bx.state == Boxer.S.DOWN
	draw_set_transform(foot + Vector2(0, -0.012 * H), 0.0, Vector2(1.0, 0.14))
	draw_circle(Vector2.ZERO, (0.26 * H if not lying else 0.5 * H), Color(0, 0, 0, 0.5))
	draw_set_transform(Vector2.ZERO, 0.0, Vector2.ONE)
	var col := Color.WHITE
	if tint[i] > 0.0:
		col = tint_col[i].lerp(Color.WHITE, 1.0 - clampf(tint[i] / 0.28, 0.0, 1.0))
	if seat:
		if clips_ok:
			(spr[i] as Sprite2D).visible = false
		_draw_seated(i, tex, foot, H, W, f, col)
		return
	var flip := -1.0 if f < 0.0 else 1.0
	if clips_ok:
		_place_clip(i, foot, H, flip, col)
		return
	# estela
	for e in trail[i]:
		var al := clampf(float(e["life"]) / 0.12, 0.0, 1.0) * 0.22
		draw_set_transform(foot + e["off"], float(e["rot"]), Vector2(flip * float(d["sx"]), float(d["sy"])))
		draw_texture_rect(tex, Rect2(Vector2(-W * 0.5, -H), Vector2(W, H)), false, Color(1, 1, 1, al))
	draw_set_transform(foot + d["off"], float(d["rot"]), Vector2(flip * float(d["sx"]), float(d["sy"])))
	draw_texture_rect(tex, Rect2(Vector2(-W * 0.5, -H), Vector2(W, H)), false, col)
	draw_set_transform(Vector2.ZERO, 0.0, Vector2.ONE)
	if bx.state == Boxer.S.BLOCK:
		var hp := _sprite_head(i)
		draw_arc(hp + Vector2(f * 0.10 * H, 0.04 * H), 0.12 * H, -1.2, 1.2 if f > 0.0 else 1.2, 12, Color(0.4, 0.85, 1.0, 0.35), 0.012 * H)


func _place_clip(i: int, foot: Vector2, H: float, flip: float, col: Color) -> void:
	var pk := _pick_clip(i)
	var clip := String(pk[0])
	var idx := int(pk[1])
	cur_clip[i] = clip
	cur_frame[i] = idx
	var sp: Sprite2D = spr[i]
	var d: Dictionary = an[i]
	var cw: float = float(clip_meta[clip]["cw"])
	var ch: float = float(clip_meta[clip]["ch"])
	sp.texture = clip_tex[clip]
	(sp.material as ShaderMaterial).set_shader_parameter("mask", clip_mask[clip])
	sp.region_rect = Rect2(float(idx) * cw, 0.0, cw, ch)
	sp.offset = Vector2(-cw * 0.36, -(ch - 6.0))
	var sc := H / fig_h
	sp.scale = Vector2(flip * sc * float(d["sx"]), sc * float(d["sy"]))
	sp.position = foot + d["off"]
	sp.rotation = float(d["rot"]) * flip
	sp.modulate = col
	sp.z_index = 3 if boxers[i].position.z > boxers[1 - i].position.z else 2
	sp.visible = true


func _draw_seated(i: int, tex: Texture2D, foot: Vector2, H: float, W: float, f: float, col: Color) -> void:
	var seat_h := 0.46 * k
	var cut := 0.60
	var flip := -1.0 if f < 0.0 else 1.0
	var dst_h := H * cut
	draw_set_transform(Vector2(foot.x, foot.y - seat_h) + Vector2(0, sin(t * 2.0 + float(i)) * 0.004 * H), 0.0, Vector2(flip, 1.0))
	draw_texture_rect_region(tex, Rect2(Vector2(-W * 0.5, -dst_h), Vector2(W, dst_h)), Rect2(Vector2(0, 0), Vector2(tex.get_width(), tex.get_height() * cut)), col)
	draw_set_transform(Vector2.ZERO, 0.0, Vector2.ONE)
	# banqueta de esquina
	var c := Color(0.8, 0.12, 0.14) if i == 0 else Color(0.14, 0.32, 0.85)
	var sw := 0.62 * k
	draw_rect(Rect2(Vector2(foot.x - sw * 0.5, foot.y - seat_h), Vector2(sw, seat_h)), c.darkened(0.45))
	draw_rect(Rect2(Vector2(foot.x - sw * 0.5, foot.y - seat_h - 0.05 * k), Vector2(sw, 0.09 * k)), c)
	draw_rect(Rect2(Vector2(foot.x - sw * 0.5, foot.y - seat_h - 0.05 * k), Vector2(sw, 0.02 * k)), c.lightened(0.4))
	var tw := Art.gear("towel")
	if tw != null:
		var sx := 0.34 * k
		draw_texture_rect(tw, Rect2(Vector2(foot.x + f * 0.55 * k - sx * 0.35, foot.y - seat_h - 0.25 * k), Vector2(sx * 0.7, sx)), false, Color(1, 1, 1, 0.9))


## Golpe: estela de velocidad (sprite aditivo) desde el hombro hasta donde llega la mano del esqueleto (misma lógica del impacto).
func _draw_punch(i: int, foot: Vector2, H: float) -> void:
	var bx = boxers[i]
	if bx.attack_phase() == "startup":
		return
	var f: float = bx.facing
	var J := _joints(bx, rig[bx])
	var chest: Vector2 = J["Chest"]["p"]
	var best := ""
	var ext := 0.0
	for s in ["L", "R"]:
		var hp: Vector2 = J["Hand_" + s]["p"]
		var e := (hp.x - chest.x) * f / k
		if e > ext:
			ext = e
			best = s
	if best == "" or ext < 0.30:
		return
	var hand: Vector2 = _xf(i, J["Hand_" + best]["p"], foot)
	var sh: Vector2 = _xf(i, foot + Vector2(f * 0.03 * H, -0.80 * H), foot)
	var dir := hand - sh
	if dir.length() < 6.0:
		return
	var n := dir.normalized()
	var tex := Art.fx("fx_red_swoosh" if i == 0 else "fx_blue_swoosh")
	var col := Color(1, 1, 1, 0.85)
	if tex != null:
		var len := dir.length() * 1.25
		var wd := len * float(tex.get_height()) / maxf(float(tex.get_width()), 1.0) * 1.2
		draw_set_transform(sh + dir * 0.55, dir.angle(), Vector2.ONE)
		draw_texture_rect(tex, Rect2(Vector2(-len * 0.5, -wd * 0.5), Vector2(len, wd)), false, col)
		draw_set_transform(Vector2.ZERO, 0.0, Vector2.ONE)
	# destello en el puño
	draw_circle(hand, 0.05 * H, Color(1, 1, 1, 0.55))
	draw_circle(hand, 0.09 * H, Color(1, 0.9, 0.6, 0.18))


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
