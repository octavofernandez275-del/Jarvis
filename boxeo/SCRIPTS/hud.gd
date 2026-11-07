extends CanvasLayer
## HUD de combate construido por código: salud, aguante, round, cronómetro, tarjetas, anuncios y pantalla de resultado.

signal rematch_pressed
signal menu_pressed
signal replay_pressed

var mgr
var a
var b
var ui: Control
var hp_a: ProgressBar
var hp_b: ProgressBar
var st_a: ProgressBar
var st_b: ProgressBar
var round_label: Label
var time_label: Label
var score_label: Label
var insight_label: Label
var announce_label: Label
var announce_tween: Tween
var result_panel: Control
var show_insight := true


func _style(color: Color) -> StyleBoxFlat:
	var s := StyleBoxFlat.new()
	s.bg_color = color
	s.set_corner_radius_all(6)
	return s


func _bar(fill: Color, h: float) -> ProgressBar:
	var p := ProgressBar.new()
	p.min_value = 0.0
	p.max_value = 100.0
	p.value = 100.0
	p.show_percentage = false
	p.custom_minimum_size = Vector2(0, h)
	p.add_theme_stylebox_override("background", _style(Color(0.04, 0.06, 0.12, 0.85)))
	p.add_theme_stylebox_override("fill", _style(fill))
	return p


func _label(text: String, size: int, color: Color = Color.WHITE) -> Label:
	var l := Label.new()
	l.text = text
	l.add_theme_font_size_override("font_size", size)
	l.add_theme_color_override("font_color", color)
	l.add_theme_constant_override("outline_size", 5)
	l.add_theme_color_override("font_outline_color", Color.BLACK)
	return l


func _corner(left: bool) -> VBoxContainer:
	var v := VBoxContainer.new()
	if left:
		v.anchor_left = 0.0
		v.anchor_right = 0.0
		v.offset_left = 24
		v.offset_right = 444
	else:
		v.anchor_left = 1.0
		v.anchor_right = 1.0
		v.offset_left = -444
		v.offset_right = -24
	v.offset_top = 14
	ui.add_child(v)
	return v


func setup(mgr_, a_, b_) -> void:
	layer = 10
	mgr = mgr_
	a = a_
	b = b_
	ui = Control.new()
	ui.set_anchors_preset(Control.PRESET_FULL_RECT)
	ui.mouse_filter = Control.MOUSE_FILTER_IGNORE
	add_child(ui)
	var left := _corner(true)
	var right := _corner(false)
	left.add_child(_label(String(a.data["name"]).to_upper(), 24))
	hp_a = _bar(Color(0.27, 0.82, 0.48), 24)
	st_a = _bar(Color(1.0, 0.82, 0.4), 10)
	left.add_child(hp_a)
	left.add_child(st_a)
	var rn := _label(String(b.data["name"]).to_upper(), 24)
	rn.horizontal_alignment = HORIZONTAL_ALIGNMENT_RIGHT
	right.add_child(rn)
	hp_b = _bar(Color(0.27, 0.82, 0.48), 24)
	st_b = _bar(Color(1.0, 0.82, 0.4), 10)
	hp_b.fill_mode = ProgressBar.FILL_END_TO_BEGIN
	st_b.fill_mode = ProgressBar.FILL_END_TO_BEGIN
	right.add_child(hp_b)
	right.add_child(st_b)
	# centro: round, cronómetro, tarjetas
	var mid := VBoxContainer.new()
	mid.anchor_left = 0.5
	mid.anchor_right = 0.5
	mid.offset_left = -110
	mid.offset_right = 110
	mid.offset_top = 8
	ui.add_child(mid)
	round_label = _label("R1", 26, Color(0.15, 0.82, 1.0))
	time_label = _label("1:00", 38)
	score_label = _label("", 18, Color(0.85, 0.88, 1.0))
	insight_label = _label("", 16, Color(1.0, 0.8, 0.5))
	for l in [round_label, time_label, score_label, insight_label]:
		l.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
		mid.add_child(l)
	# anuncios
	announce_label = _label("", 76, Color(1, 0.95, 0.75))
	announce_label.set_anchors_preset(Control.PRESET_CENTER)
	announce_label.grow_horizontal = Control.GROW_DIRECTION_BOTH
	announce_label.grow_vertical = Control.GROW_DIRECTION_BOTH
	announce_label.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	announce_label.offset_top = -140
	announce_label.modulate.a = 0.0
	ui.add_child(announce_label)
	mgr.announce.connect(announce)
	mgr.round_changed.connect(func(n, t): round_label.text = "R%d/%d" % [n, t])
	mgr.score_updated.connect(func(t): score_label.text = t)
	mgr.fight_finished.connect(show_result)


func _process(_delta: float) -> void:
	if mgr == null:
		return
	hp_a.value = a.health
	hp_b.value = b.health
	st_a.value = a.stamina
	st_b.value = b.stamina
	var t := maxf(0.0, float(mgr.time_left))
	time_label.text = "%d:%02d" % [floori(t / 60.0), int(t) % 60]
	insight_label.text = b.ai.get_insight() if (show_insight and b.ai != null) else ""


func announce(text: String, seconds: float) -> void:
	announce_label.text = text
	announce_label.modulate.a = 1.0
	if announce_tween:
		announce_tween.kill()
	announce_tween = create_tween()
	announce_tween.tween_interval(maxf(0.1, seconds - 0.4))
	announce_tween.tween_property(announce_label, "modulate:a", 0.0, 0.4)


func show_result(res: Dictionary) -> void:
	await get_tree().create_timer(2.5, true, false, true).timeout
	var panel := PanelContainer.new()
	panel.set_anchors_preset(Control.PRESET_CENTER)
	panel.grow_horizontal = Control.GROW_DIRECTION_BOTH
	panel.grow_vertical = Control.GROW_DIRECTION_BOTH
	panel.add_theme_stylebox_override("panel", _style(Color(0.07, 0.1, 0.2, 0.94)))
	var v := VBoxContainer.new()
	v.add_theme_constant_override("separation", 10)
	panel.add_child(v)
	var title := "EMPATE"
	if res["winner"] == "a":
		title = "¡VICTORIA!"
	elif res["winner"] == "b":
		title = "DERROTA"
	var t := _label(title, 56, Color(1, 0.82, 0.4))
	t.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	v.add_child(t)
	var m := _label("%s  (round %d)" % [res["method"], res["round"]], 26)
	m.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	v.add_child(m)
	if res["method"].begins_with("Decisi") or res["method"] == "Empate":
		var parts: Array = []
		for c in res["cards"]:
			parts.append("%d-%d" % [c[0], c[1]])
		var c := _label("Jueces: " + "   ".join(parts), 22, Color(0.85, 0.88, 1.0))
		c.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
		v.add_child(c)
	var s := _label("Golpes: %d de %d   |   Caídas: %d - %d" % [res["landed"][0], res["thrown"][0], res["knockdowns"][1], res["knockdowns"][0]], 20, Color(0.8, 0.85, 1.0))
	s.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	v.add_child(s)
	var row := HBoxContainer.new()
	row.alignment = BoxContainer.ALIGNMENT_CENTER
	row.add_theme_constant_override("separation", 14)
	v.add_child(row)
	if res["ko"]:
		var br := Button.new()
		br.text = "Repetición"
		br.custom_minimum_size = Vector2(190, 56)
		br.pressed.connect(func(): replay_pressed.emit())
		row.add_child(br)
	var bm := Button.new()
	bm.text = "Continuar" if res.get("career", false) else "Revancha"
	bm.custom_minimum_size = Vector2(190, 56)
	bm.pressed.connect(func(): rematch_pressed.emit())
	row.add_child(bm)
	var bx := Button.new()
	bx.text = "Menú"
	bx.custom_minimum_size = Vector2(190, 56)
	bx.pressed.connect(func(): menu_pressed.emit())
	row.add_child(bx)
	ui.add_child(panel)
	result_panel = panel


func hide_result() -> void:
	if result_panel:
		result_panel.queue_free()
		result_panel = null
