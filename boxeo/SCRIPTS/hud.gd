extends CanvasLayer
## HUD de combate construido por código: salud, aguante, round, cronómetro, tarjetas, anuncios y pantalla de resultado.

signal rematch_pressed
signal menu_pressed
signal replay_pressed
signal jarvis_report_pressed

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
var voz = null                 # voz.gd (para el indicador VOZ)
var voz_label: Label
var _voz_estado := ""


func _style(color: Color) -> StyleBoxFlat:
	var s := StyleBoxFlat.new()
	s.bg_color = color
	s.set_corner_radius_all(10)
	return s


func _bar_style(color: Color, border: bool) -> StyleBoxFlat:
	var s := StyleBoxFlat.new()
	s.bg_color = color
	s.set_corner_radius_all(9)
	if border:
		s.set_border_width_all(2)
		s.border_color = Color(1, 1, 1, 0.35)
	return s


## Color de la barra de vida según lo que queda: verde → rojo.
func _tint(p: ProgressBar) -> void:
	var sb := p.get_theme_stylebox("fill") as StyleBoxFlat
	if sb:
		var t := clampf((float(p.value) / 100.0 - 0.2) / 0.5, 0.0, 1.0)
		sb.bg_color = Color(0.92, 0.25, 0.25).lerp(Color(0.27, 0.85, 0.5), t)


func _bar(fill: Color, h: float) -> ProgressBar:
	var p := ProgressBar.new()
	p.min_value = 0.0
	p.max_value = 100.0
	p.value = 100.0
	p.show_percentage = false
	p.custom_minimum_size = Vector2(0, h)
	p.add_theme_stylebox_override("background", _bar_style(Color(0.03, 0.04, 0.09, 0.85), true))
	p.add_theme_stylebox_override("fill", _bar_style(fill, false))
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
	# viñeta + franja superior para que todo se lea sobre la escena
	var vig := TextureRect.new()
	var gt := GradientTexture2D.new()
	var g := Gradient.new()
	g.colors = PackedColorArray([Color(0, 0, 0, 0), Color(0, 0, 0, 0), Color(0, 0, 0, 0.6)])
	g.offsets = PackedFloat32Array([0.0, 0.55, 1.0])
	gt.gradient = g
	gt.fill = GradientTexture2D.FILL_RADIAL
	gt.fill_from = Vector2(0.5, 0.5)
	gt.fill_to = Vector2(1.0, 0.5)
	gt.width = 256
	gt.height = 128
	vig.texture = gt
	vig.expand_mode = TextureRect.EXPAND_IGNORE_SIZE
	vig.stretch_mode = TextureRect.STRETCH_SCALE
	vig.set_anchors_preset(Control.PRESET_FULL_RECT)
	vig.mouse_filter = Control.MOUSE_FILTER_IGNORE
	ui.add_child(vig)
	var band := ColorRect.new()
	band.color = Color(0, 0, 0, 0.5)
	band.anchor_right = 1.0
	band.offset_bottom = 104
	band.mouse_filter = Control.MOUSE_FILTER_IGNORE
	ui.add_child(band)
	var left := _corner(true)
	var right := _corner(false)
	left.add_child(_label(String(a.data["name"]).to_upper(), 24, Color(1.0, 0.45, 0.45)))
	hp_a = _bar(Color(0.27, 0.82, 0.48), 24)
	st_a = _bar(Color(1.0, 0.82, 0.4), 10)
	left.add_child(hp_a)
	left.add_child(st_a)
	var rn := _label(String(b.data["name"]).to_upper() + "  ·  JARVIS", 24, Color(0.45, 0.7, 1.0))
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
	var mid_bg := Panel.new()
	mid_bg.add_theme_stylebox_override("panel", _style(Color(0.02, 0.03, 0.08, 0.75)))
	mid_bg.anchor_left = 0.5
	mid_bg.anchor_right = 0.5
	mid_bg.offset_left = -120
	mid_bg.offset_right = 120
	mid_bg.offset_top = 4
	mid_bg.offset_bottom = 118
	mid_bg.mouse_filter = Control.MOUSE_FILTER_IGNORE
	ui.add_child(mid_bg)
	ui.move_child(mid_bg, band.get_index() + 1)
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
	announce_label.offset_top = -95
	announce_label.modulate.a = 0.0
	ui.add_child(announce_label)
	voz_label = _label("VOZ ...", 15, Color(0.7, 0.75, 0.85))
	voz_label.position = Vector2(26, 106)
	ui.add_child(voz_label)
	mgr.announce.connect(announce)
	mgr.fx.connect(_on_fx_flash)
	mgr.round_changed.connect(func(n, t): round_label.text = "R%d/%d" % [n, t])
	mgr.score_updated.connect(func(t): score_label.text = t)
	mgr.fight_finished.connect(show_result)


func _process(_delta: float) -> void:
	if mgr == null:
		return
	hp_a.value = a.health
	_tint(hp_a)
	_tint(hp_b)
	hp_b.value = b.health
	st_a.value = a.stamina
	st_b.value = b.stamina
	var t := maxf(0.0, float(mgr.time_left))
	time_label.text = "%d:%02d" % [floori(t / 60.0), int(t) % 60]
	insight_label.text = b.ai.get_insight() if (show_insight and b.ai != null) else ""
	_update_voz()


## Indicador pequeño: VOZ OK (verde), VOZ ... (gris, aún no habló) o SIN VOZ (rojo, con pista).
func _update_voz() -> void:
	if voz == null or voz_label == null:
		return
	var e: String = voz.estado()
	if e == _voz_estado:
		return
	_voz_estado = e
	if e == "ok":
		voz_label.text = "VOZ OK"
		voz_label.add_theme_color_override("font_color", Color(0.45, 0.95, 0.6))
	elif e == "error":
		voz_label.text = "SIN VOZ  (Menú > Probar voces)"
		voz_label.add_theme_color_override("font_color", Color(1.0, 0.45, 0.45))
	else:
		voz_label.text = "VOZ ..."
		voz_label.add_theme_color_override("font_color", Color(0.7, 0.75, 0.85))


## Subtítulos de narradores/entrenadores (arriba, bajo el marcador, para no tapar los botones táctiles).
var caption_panel: PanelContainer
var caption_label: Label
var caption_tween: Tween


func caption(who: String, text: String, seconds: float = 5.0) -> void:
	if caption_panel == null:
		caption_panel = PanelContainer.new()
		caption_panel.add_theme_stylebox_override("panel", _style(Color(0, 0, 0, 0.62)))
		caption_panel.anchor_left = 0.2
		caption_panel.anchor_right = 0.8
		caption_panel.anchor_top = 0.0
		caption_panel.anchor_bottom = 0.0
		caption_panel.offset_left = 0
		caption_panel.offset_right = 0
		caption_panel.offset_top = 164
		caption_panel.offset_bottom = 164
		caption_panel.grow_vertical = Control.GROW_DIRECTION_END
		caption_panel.mouse_filter = Control.MOUSE_FILTER_IGNORE
		caption_label = _label("", 20)
		caption_label.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
		caption_label.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
		caption_panel.add_child(caption_label)
		add_child(caption_panel)
	caption_label.text = "%s: %s" % [who, text]
	caption_panel.modulate.a = 1.0
	if caption_tween:
		caption_tween.kill()
	caption_tween = create_tween()
	caption_tween.tween_interval(maxf(0.5, seconds))
	caption_tween.tween_property(caption_panel, "modulate:a", 0.0, 0.4)


## Aviso corto de combate (parada, esquiva, contragolpe).
var flash_label: Label
var flash_tween: Tween


func flash(text: String, color: Color) -> void:
	if flash_label == null:
		flash_label = _label("", 44)
		flash_label.set_anchors_preset(Control.PRESET_CENTER_TOP)
		flash_label.anchor_left = 0.0
		flash_label.anchor_right = 1.0
		flash_label.offset_left = 0
		flash_label.offset_right = 0
		flash_label.offset_top = 205
		flash_label.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
		flash_label.mouse_filter = Control.MOUSE_FILTER_IGNORE
		ui.add_child(flash_label)
	flash_label.text = text
	flash_label.add_theme_color_override("font_color", color)
	flash_label.modulate.a = 1.0
	if flash_tween:
		flash_tween.kill()
	flash_tween = create_tween()
	flash_tween.tween_interval(0.5)
	flash_tween.tween_property(flash_label, "modulate:a", 0.0, 0.3)


func _on_fx_flash(kind: String, _pos: Vector3, extra: Dictionary) -> void:
	var df = extra.get("defender", null)
	var mine: bool = df == a
	if kind == "parry":
		flash("¡PARADA PERFECTA!" if mine else "JARVIS PARA", Color(1.0, 0.85, 0.2) if mine else Color(0.5, 0.75, 1.0))
	elif kind == "slip":
		flash("¡ESQUIVA!" if mine else "JARVIS ESQUIVA", Color(0.4, 1.0, 0.6) if mine else Color(0.5, 0.75, 1.0))
	elif kind == "combo":
		var cnt: int = int(extra.get("count", 0))
		if extra.get("owner", null) == a:
			flash("COMBO x%d" % cnt, Color(1.0, 0.85, 0.2))
		elif cnt >= 3:
			flash("JARVIS COMBO x%d" % cnt, Color(0.5, 0.75, 1.0))
	elif (kind == "hit_heavy" or kind == "hit_light") and extra.get("counter", false):
		flash("¡CONTRAGOLPE!" if not mine else "TE CONTRAGOLPEAN", Color(0.4, 1.0, 0.6) if not mine else Color(1.0, 0.4, 0.4))


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
	var bj := Button.new()
	bj.text = "Contarle a Jarvis"
	bj.custom_minimum_size = Vector2(230, 56)
	bj.pressed.connect(func(): jarvis_report_pressed.emit())
	row.add_child(bj)
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
