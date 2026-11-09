extends CanvasLayer
## Menús por código: principal, selección de boxeador/rival/arena/dificultad y centro del modo carrera.
## Emite fight_requested(config) con: player, opponent (diccionarios de fighters.json), arena (id), skill (0..1), rounds, round_seconds, career (bool).

signal fight_requested(config: Dictionary)

const Data = preload("res://SCRIPTS/data.gd")
const Career = preload("res://SCRIPTS/career.gd")
const Art = preload("res://SCRIPTS/art.gd")

const VEST_PATH := "user://vestuario.json"

const STAT_NAMES := {"power": "Poder", "speed": "Velocidad", "stamina": "Aguante", "chin": "Mandíbula", "defense": "Defensa",
	"accuracy": "Precisión", "footwork": "Piernas", "ring_iq": "Inteligencia"}

var fighters: Array = []
var arenas: Array = []
var profiles: Dictionary = {}
var difficulty: Dictionary = {}
var root: Control
var career = null
var voz = null                 # voz.gd (lo pone main.gd)
var vest: Dictionary = {}      # vestuario elegido: {"gloves": "#hex", "shorts": ..., "shoes": ...}
var bg_tex: Texture2D = null


func _ready() -> void:
	layer = 5
	fighters = Data.load_json("res://CHARACTERS/fighters.json")["fighters"]
	arenas = Data.load_json("res://ARENAS/arenas.json")["arenas"]
	var p: Dictionary = Data.load_json("res://OPPONENTS/ai_profiles.json")
	profiles = p["profiles"]
	difficulty = p["difficulty"]
	bg_tex = Art.random_backdrop()
	if FileAccess.file_exists(VEST_PATH):
		var fa := FileAccess.open(VEST_PATH, FileAccess.READ)
		var j = JSON.parse_string(fa.get_as_text())
		if j is Dictionary:
			vest = j


## Aplica el vestuario elegido al boxeador del jugador (copia).
func with_vest(f: Dictionary) -> Dictionary:
	var c: Dictionary = f.duplicate(true)
	for k in vest:
		c["look"][k] = vest[k]
	return c


func _save_vest() -> void:
	var fa := FileAccess.open(VEST_PATH, FileAccess.WRITE)
	if fa:
		fa.store_string(JSON.stringify(vest))


func open() -> void:
	show_main()


func close() -> void:
	if root:
		root.queue_free()
		root = null


# ---------------- utilidades de interfaz ----------------

func _reset() -> VBoxContainer:
	close()
	root = Control.new()
	root.set_anchors_preset(Control.PRESET_FULL_RECT)
	add_child(root)
	var bg := ColorRect.new()
	bg.color = Color(0.05, 0.07, 0.14)
	bg.set_anchors_preset(Control.PRESET_FULL_RECT)
	root.add_child(bg)
	if bg_tex != null:
		var pic := TextureRect.new()
		pic.texture = bg_tex
		pic.expand_mode = TextureRect.EXPAND_IGNORE_SIZE
		pic.stretch_mode = TextureRect.STRETCH_KEEP_ASPECT_COVERED
		pic.set_anchors_preset(Control.PRESET_FULL_RECT)
		pic.modulate = Color(0.45, 0.45, 0.55)
		root.add_child(pic)
		var shade := ColorRect.new()
		shade.color = Color(0.02, 0.03, 0.08, 0.55)
		shade.set_anchors_preset(Control.PRESET_FULL_RECT)
		root.add_child(shade)
	var v := VBoxContainer.new()
	v.set_anchors_preset(Control.PRESET_FULL_RECT)
	v.offset_left = 40
	v.offset_right = -40
	v.offset_top = 24
	v.offset_bottom = -24
	v.add_theme_constant_override("separation", 12)
	root.add_child(v)
	return v


func _label(text: String, size: int = 22, color: Color = Color.WHITE) -> Label:
	var l := Label.new()
	l.text = text
	l.add_theme_font_size_override("font_size", size)
	l.add_theme_color_override("font_color", color)
	return l


func _btn(text: String, cb: Callable, w: float = 320.0, h: float = 54.0) -> Button:
	var b := Button.new()
	b.text = text
	b.custom_minimum_size = Vector2(w, h)
	b.pressed.connect(cb)
	return b


func _stat_bars(parent: Control, stats: Dictionary) -> void:
	for k in STAT_NAMES:
		var row := HBoxContainer.new()
		row.add_child(_label(STAT_NAMES[k], 18, Color(0.7, 0.78, 1.0)))
		(row.get_child(0) as Label).custom_minimum_size = Vector2(130, 0)
		var pb := ProgressBar.new()
		pb.max_value = 100.0
		pb.value = float(stats[k])
		pb.show_percentage = false
		pb.custom_minimum_size = Vector2(200, 18)
		pb.size_flags_vertical = Control.SIZE_SHRINK_CENTER
		row.add_child(pb)
		parent.add_child(row)


# ---------------- pantallas ----------------

func show_main() -> void:
	var v := _reset()
	# sprites de dos boxeadores flanqueando el menú
	var pool := fighters.duplicate()
	pool.shuffle()
	for i in range(2):
		var sp := TextureRect.new()
		sp.texture = Art.tex(String(pool[i].get("sprite", "")))
		sp.expand_mode = TextureRect.EXPAND_IGNORE_SIZE
		sp.stretch_mode = TextureRect.STRETCH_KEEP_ASPECT_CENTERED
		sp.flip_h = i == 1
		sp.anchor_top = 0.1
		sp.anchor_bottom = 1.0
		sp.anchor_left = 0.0 if i == 0 else 0.74
		sp.anchor_right = 0.26 if i == 0 else 1.0
		sp.mouse_filter = Control.MOUSE_FILTER_IGNORE
		root.add_child(sp)
	var logo := TextureRect.new()
	logo.texture = Art.ui("logo_alpha")
	logo.expand_mode = TextureRect.EXPAND_IGNORE_SIZE
	logo.stretch_mode = TextureRect.STRETCH_KEEP_ASPECT_CENTERED
	logo.custom_minimum_size = Vector2(0, 230)
	v.add_child(logo)
	if logo.texture == null:
		var t := _label("JARVIS BOXING AI", 64, Color(1.0, 0.7, 0.28))
		t.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
		v.add_child(t)
	var quick := func():
		var choose_opp := func(f: Dictionary):
			var opp_cb := func(o: Dictionary): _pick_options(f, o)
			_pick_fighter("Elige a tu rival", opp_cb, String(f["id"]))
		_pick_fighter("Elige tu boxeador", choose_opp)
	var career_cb := func(): _career_entry()
	var voz_cb := func(): show_voz()
	var vest_cb := func(): show_vestuario()
	for item in [["Combate rápido", quick], ["Modo carrera", career_cb], ["Vestuario", vest_cb], ["Probar voces", voz_cb]]:
		var b := _btn(item[0], item[1], 420, 58)
		b.size_flags_horizontal = Control.SIZE_SHRINK_CENTER
		v.add_child(b)


## Vestuario: elige guantes, pantalón y botas con los iconos de equipo; se aplica a tu boxeador en todos los combates.
func show_vestuario() -> void:
	var v := _reset()
	v.add_child(_label("Vestuario", 44, Color(1.0, 0.82, 0.4)))
	v.add_child(_label("Se aplica a tu boxeador en combate rápido y en la carrera.", 18, Color(0.85, 0.9, 1.0)))
	for sec in [["Guantes", "gloves", Art.GLOVES], ["Pantalón", "shorts", Art.SHORTS], ["Botas", "shoes", Art.BOOTS]]:
		v.add_child(_label(sec[0], 24, Color(0.15, 0.82, 1.0)))
		var row := HBoxContainer.new()
		row.add_theme_constant_override("separation", 10)
		v.add_child(row)
		for icon in sec[2]:
			var hexc: String = sec[2][icon]
			var bt := Button.new()
			bt.icon = Art.gear(icon)
			bt.expand_icon = true
			bt.custom_minimum_size = Vector2(96, 96)
			bt.toggle_mode = true
			bt.button_pressed = String(vest.get(sec[1], "")) == hexc
			var key: String = sec[1]
			bt.pressed.connect(func():
				vest[key] = hexc
				_save_vest()
				show_vestuario())
			row.add_child(bt)
	var row2 := HBoxContainer.new()
	row2.add_theme_constant_override("separation", 14)
	v.add_child(row2)
	row2.add_child(_btn("Quitar vestuario", func():
		vest = {}
		_save_vest()
		show_vestuario(), 260, 52))
	row2.add_child(_btn("Volver", func(): show_main(), 200, 52))


## Pantalla de diagnóstico y prueba de las voces (narradores, entrenadores, árbitro y Jarvis).
func show_voz() -> void:
	var v := _reset()
	v.add_child(_label("Prueba de voces", 44, Color(1.0, 0.82, 0.4)))
	var info := _label("", 20, Color(0.85, 0.9, 1.0))
	info.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	info.custom_minimum_size = Vector2(900, 0)
	v.add_child(info)
	var refresh := func():
		info.text = voz.info() if voz != null else "Voz no disponible."
	refresh.call()
	var tm := Timer.new()
	tm.wait_time = 1.0
	tm.autostart = true
	tm.timeout.connect(refresh)
	root.add_child(tm)
	var row := HBoxContainer.new()
	row.add_theme_constant_override("separation", 14)
	v.add_child(row)
	var probar := func():
		if voz != null:
			voz.probar()
	row.add_child(_btn("Probar voces", probar, 260, 60))
	var defecto := func():
		if voz != null:
			voz.usar_defecto = true
			voz.parar()
			voz.hablar("pres", "Probando la voz por defecto del sistema.", true)
	row.add_child(_btn("Voz del sistema", defecto, 260, 60))
	var volver := func(): show_main()
	row.add_child(_btn("Volver", volver, 200, 60))


func _pick_fighter(title: String, on_done: Callable, exclude_id: String = "") -> void:
	var v := _reset()
	v.add_child(_label(title, 36, Color(1.0, 0.82, 0.4)))
	var h := HBoxContainer.new()
	h.size_flags_vertical = Control.SIZE_EXPAND_FILL
	h.add_theme_constant_override("separation", 24)
	v.add_child(h)
	var scroll := ScrollContainer.new()
	scroll.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	scroll.horizontal_scroll_mode = ScrollContainer.SCROLL_MODE_DISABLED
	h.add_child(scroll)
	var grid := GridContainer.new()
	grid.columns = 5
	grid.add_theme_constant_override("h_separation", 8)
	grid.add_theme_constant_override("v_separation", 8)
	scroll.add_child(grid)
	var detail := VBoxContainer.new()
	detail.custom_minimum_size = Vector2(440, 0)
	h.add_child(detail)
	var show_detail := func(f: Dictionary):
		for c in detail.get_children():
			c.queue_free()
		var top := HBoxContainer.new()
		top.add_theme_constant_override("separation", 12)
		detail.add_child(top)
		var spr := TextureRect.new()
		spr.texture = Art.tex(String(f.get("sprite", "")))
		spr.expand_mode = TextureRect.EXPAND_IGNORE_SIZE
		spr.stretch_mode = TextureRect.STRETCH_KEEP_ASPECT_CENTERED
		spr.custom_minimum_size = Vector2(120, 230)
		top.add_child(spr)
		var info := VBoxContainer.new()
		top.add_child(info)
		info.add_child(_label(String(f["name"]), 28))
		info.add_child(_label("\"%s\"" % f["nickname"], 20, Color(1.0, 0.8, 0.4)))
		var prof: Dictionary = profiles.get(f["ai_profile"], {})
		info.add_child(_label("%s · %s" % [f["country"], String(f["weight_class"]).capitalize()], 17, Color(0.15, 0.82, 1.0)))
		info.add_child(_label("%s · %d años" % ["Zurdo" if f["stance"] == "southpaw" else "Ortodoxo", int(f["age"])], 16, Color(0.8, 0.85, 1.0)))
		info.add_child(_label("Estilo: %s" % prof.get("label", "?"), 17))
		info.add_child(_label("Récord %d-%d (%d KO)" % [f["record"]["w"], f["record"]["l"], f["record"]["ko"]], 17))
		info.add_child(_label("%d cm · %d kg\nalcance %d cm" % [f["height_cm"], f["weight_kg"], f["reach_cm"]], 15, Color(0.8, 0.85, 1.0)))
		_stat_bars(detail, f["stats"])
		var bio := _label(String(f["bio"]), 15, Color(0.85, 0.88, 1.0))
		bio.autowrap_mode = TextServer.AUTOWRAP_WORD
		detail.add_child(bio)
		detail.add_child(_btn("Elegir", func(): on_done.call(f), 260, 52))
	var first := true
	for f in fighters:
		if f["id"] == exclude_id:
			continue
		var fb := Button.new()
		fb.icon = Art.tex(String(f.get("portrait", "")))
		fb.expand_icon = true
		fb.text = String(f["name"]).split(" ")[0]
		fb.icon_alignment = HORIZONTAL_ALIGNMENT_CENTER
		fb.vertical_icon_alignment = VERTICAL_ALIGNMENT_TOP
		fb.add_theme_font_size_override("font_size", 14)
		fb.custom_minimum_size = Vector2(112, 150)
		fb.pressed.connect(func(): show_detail.call(f))
		grid.add_child(fb)
		if first:
			first = false
			show_detail.call(f)
	v.add_child(_btn("Volver", func(): show_main(), 200, 44))


func _pick_options(player: Dictionary, opp: Dictionary) -> void:
	var v := _reset()
	v.add_child(_label("%s  vs  %s" % [player["name"], opp["name"]], 40, Color(1.0, 0.82, 0.4)))
	var ids: Array = []
	for ar in arenas:
		if ar["kind"] in ["gym", "arena", "stadium"]:
			ids.append(ar["id"])
	var arena_sel := [ids.find("arena_city") if ids.has("arena_city") else 0]
	var arena_name := _label("", 22, Color(1.0, 0.82, 0.4))
	var thumbs := HBoxContainer.new()
	thumbs.add_theme_constant_override("separation", 8)
	var sc := ScrollContainer.new()
	sc.custom_minimum_size = Vector2(0, 118)
	sc.vertical_scroll_mode = ScrollContainer.SCROLL_MODE_DISABLED
	sc.add_child(thumbs)
	var thumb_btns: Array = []
	var refresh_arena := func():
		var cur: Dictionary = Data.find_by_id(arenas, String(ids[arena_sel[0]]))
		arena_name.text = "%s  ·  %s" % [cur["name"], cur.get("notes", "")]
		for i in range(thumb_btns.size()):
			thumb_btns[i].modulate = Color.WHITE if i == arena_sel[0] else Color(0.55, 0.55, 0.6)
	for i in range(ids.size()):
		var ar: Dictionary = Data.find_by_id(arenas, String(ids[i]))
		var tb := Button.new()
		tb.icon = Art.tex(String(ar.get("backdrop", "")))
		tb.expand_icon = true
		tb.custom_minimum_size = Vector2(170, 100)
		var idx := i
		tb.pressed.connect(func():
			arena_sel[0] = idx
			refresh_arena.call())
		thumbs.add_child(tb)
		thumb_btns.append(tb)
	v.add_child(_label("Arena", 22))
	v.add_child(sc)
	v.add_child(arena_name)
	refresh_arena.call()
	var diff_opt := OptionButton.new()
	var diff_keys := ["easy", "normal", "hard", "pro"]
	for k in ["Fácil", "Normal", "Difícil", "Profesional"]:
		diff_opt.add_item(k)
	diff_opt.select(1)
	var rounds_opt := OptionButton.new()
	var round_vals := [1, 3, 6, 12]
	for r in round_vals:
		rounds_opt.add_item("%d rounds" % r)
	rounds_opt.select(1)
	var time_opt := OptionButton.new()
	var time_vals := [45.0, 60.0, 90.0, 180.0]
	for t in time_vals:
		time_opt.add_item("%d s por round" % int(t))
	time_opt.select(1)
	for pair in [["Dificultad de la IA", diff_opt], ["Rounds", rounds_opt], ["Duración", time_opt]]:
		var row := HBoxContainer.new()
		row.add_child(_label(pair[0], 22))
		(row.get_child(0) as Label).custom_minimum_size = Vector2(260, 0)
		pair[1].custom_minimum_size = Vector2(320, 48)
		row.add_child(pair[1])
		v.add_child(row)
	var go := func():
		var cfg := {
			"player": with_vest(player), "opponent": opp, "arena": ids[arena_sel[0]], "skill": float(difficulty[diff_keys[diff_opt.selected]]),
			"rounds": round_vals[rounds_opt.selected], "round_seconds": time_vals[time_opt.selected], "career": false,
		}
		fight_requested.emit(cfg)
	v.add_child(_btn("¡A PELEAR!", go, 320, 64))
	v.add_child(_btn("Volver", func(): show_main(), 200, 46))


# ---------------- modo carrera ----------------

func _career_entry() -> void:
	career = Career.new()
	if career.has_save() and career.load_save():
		show_hub("Carrera cargada.")
		return
	var created := func(f: Dictionary):
		career.new_career(f)
		show_hub("¡Empieza tu carrera!")
	_pick_fighter("Elige tu boxeador para la carrera", created)


func show_hub(msg: String = "") -> void:
	var v := _reset()
	var s: Dictionary = career.s
	var fi: Dictionary = s["fighter"]
	var hd := HBoxContainer.new()
	hd.add_theme_constant_override("separation", 14)
	v.add_child(hd)
	var pt := TextureRect.new()
	pt.texture = Art.tex(String(fi.get("portrait", "")))
	pt.expand_mode = TextureRect.EXPAND_IGNORE_SIZE
	pt.stretch_mode = TextureRect.STRETCH_KEEP_ASPECT_CENTERED
	pt.custom_minimum_size = Vector2(54, 70)
	hd.add_child(pt)
	hd.add_child(_label("%s · %s · Semana %d" % [fi["name"], career.tier(), int(s["week"])], 34, Color(1.0, 0.82, 0.4)))
	v.add_child(_label("Dinero $%d   Ranking %d   Fama %d   Fatiga %d/100   Sesiones %d   Récord %d-%d (%d KO)" % [int(s["cash"]), int(s["rank"]), int(s["fame"]), int(s["fatigue"]), int(s["slots"]), int(s["wins"]), int(s["losses"]), int(s["kos"])], 18, Color(0.85, 0.9, 1.0)))
	var logros: Array = []
	for bl in s["belts"]:
		logros.append("Cinturón " + String(bl).to_upper())
	for tr in s["trophies"]:
		logros.append(String(tr).replace("_", " "))
	if not logros.is_empty():
		var lr := HBoxContainer.new()
		lr.add_theme_constant_override("separation", 10)
		v.add_child(lr)
		var bi := 0
		var belt_keys := ["belt_gold", "belt_wbc", "belt_blue", "belt_green", "belt_gold2"]
		for bl in s["belts"]:
			var ic := TextureRect.new()
			ic.texture = Art.gear(belt_keys[bi % belt_keys.size()])
			ic.expand_mode = TextureRect.EXPAND_IGNORE_SIZE
			ic.stretch_mode = TextureRect.STRETCH_KEEP_ASPECT_CENTERED
			ic.custom_minimum_size = Vector2(110, 62)
			ic.tooltip_text = String(bl)
			lr.add_child(ic)
			bi += 1
		for tr in s["trophies"]:
			var tc := TextureRect.new()
			tc.texture = Art.gear("trophy")
			tc.expand_mode = TextureRect.EXPAND_IGNORE_SIZE
			tc.stretch_mode = TextureRect.STRETCH_KEEP_ASPECT_CENTERED
			tc.custom_minimum_size = Vector2(50, 62)
			lr.add_child(tc)
		lr.add_child(_label(" · ".join(logros), 16, Color(1.0, 0.85, 0.4)))
	if msg != "":
		v.add_child(_label(msg, 20, Color(0.4, 1.0, 0.6)))
	var h := HBoxContainer.new()
	h.add_theme_constant_override("separation", 30)
	h.size_flags_vertical = Control.SIZE_EXPAND_FILL
	v.add_child(h)
	var left := VBoxContainer.new()
	h.add_child(left)
	_stat_bars(left, fi["stats"])
	var nxt: Dictionary = s["next"]
	left.add_child(_label("Próximo rival: %s (ranking %d), %d rounds" % [nxt["fighter"]["name"], int(nxt["rank"]), int(nxt["rounds"])], 18, Color(1.0, 0.8, 0.5)))
	var mid := VBoxContainer.new()
	h.add_child(mid)
	mid.add_child(_label("Entrenar", 24, Color(0.15, 0.82, 1.0)))
	for t in career.cfg["training"]:
		mid.add_child(_btn("%s%s" % [t["name"], "  ($%d)" % t["cost"] if int(t["cost"]) > 0 else ""], func(): show_hub(career.train(t["id"])), 260, 40))
	var right := VBoxContainer.new()
	h.add_child(right)
	right.add_child(_label("Gestión", 24, Color(0.15, 0.82, 1.0)))
	right.add_child(_btn("Terminar semana", func(): show_hub(career.end_week()), 300, 40))
	right.add_child(_btn("Mejorar gimnasio", func(): show_hub(career.upgrade_gym()), 300, 40))
	for e in career.cfg["equipment"]:
		var owned: bool = s["equipment"].has(e["id"])
		var eb := _btn("%s ($%d)%s" % [e["name"], e["price"], " ✓" if owned else ""], func(): show_hub(career.buy(e["id"])), 300, 36)
		eb.disabled = owned
		right.add_child(eb)
	for sp in career.cfg["sponsors"]:
		right.add_child(_btn("Patrocinio: %s" % sp["name"], func(): show_hub(career.sign_sponsor(sp["id"])), 300, 36))
	var go := func():
		var cfg := {
			"player": with_vest(career.effective_fighter()), "opponent": nxt["fighter"], "arena": nxt["arena"], "skill": float(nxt["skill"]),
			"rounds": int(nxt["rounds"]), "round_seconds": 60.0, "career": true,
		}
		fight_requested.emit(cfg)
	var fb := _btn("PRÓXIMO COMBATE", go, 320, 60)
	fb.disabled = not career.fight_ready()
	v.add_child(fb)
	if not career.fight_ready():
		v.add_child(_label("Faltan %d semanas para tu próxima pelea. Entrena y termina la semana." % (int(s["next_fight_week"]) - int(s["week"])), 18, Color(0.8, 0.85, 1.0)))
	v.add_child(_btn("Menú principal", func(): show_main(), 220, 44))
