extends CanvasLayer
## Pantalla de presentación VS: sprites de cuerpo entero de ambos boxeadores, nombres, récords, el logo VS y el fondo de la arena.

signal finished

const Art = preload("res://SCRIPTS/art.gd")


func setup(fa: Dictionary, fb: Dictionary, arena_cfg: Dictionary, seconds: float = 3.0) -> void:
	layer = 20
	var root := Control.new()
	root.set_anchors_preset(Control.PRESET_FULL_RECT)
	root.mouse_filter = Control.MOUSE_FILTER_STOP
	add_child(root)
	var bg := ColorRect.new()
	bg.color = Color(0.03, 0.04, 0.09)
	bg.set_anchors_preset(Control.PRESET_FULL_RECT)
	root.add_child(bg)
	var back := TextureRect.new()
	back.texture = Art.tex(String(arena_cfg.get("backdrop", "")))
	back.expand_mode = TextureRect.EXPAND_IGNORE_SIZE
	back.stretch_mode = TextureRect.STRETCH_KEEP_ASPECT_COVERED
	back.set_anchors_preset(Control.PRESET_FULL_RECT)
	back.modulate = Color(0.55, 0.55, 0.65)
	root.add_child(back)
	var acc := Color.html(String(arena_cfg.get("accent", "#1b5ae0")))
	# dos mitades diagonales rojo / azul
	for side in [0, 1]:
		var poly := Polygon2D.new()
		var col := Color(0.8, 0.1, 0.15, 0.55) if side == 0 else Color(0.12, 0.3, 0.85, 0.55)
		poly.color = col
		root.add_child(poly)
		poly.set_meta("side", side)
	root.resized.connect(func(): _layout(root))
	var names: Array = []
	var sprites: Array = []
	for i in range(2):
		var f: Dictionary = fa if i == 0 else fb
		var spr := TextureRect.new()
		spr.texture = Art.tex(String(f.get("sprite", "")))
		spr.expand_mode = TextureRect.EXPAND_IGNORE_SIZE
		spr.stretch_mode = TextureRect.STRETCH_KEEP_ASPECT_CENTERED
		spr.texture_filter = CanvasItem.TEXTURE_FILTER_LINEAR
		spr.flip_h = i == 1
		root.add_child(spr)
		sprites.append(spr)
		var l := Label.new()
		l.text = "%s\n\"%s\"\n%s · %d-%d (%d KO)" % [String(f["name"]).to_upper(), f["nickname"], f["country"], f["record"]["w"], f["record"]["l"], f["record"]["ko"]]
		l.add_theme_font_size_override("font_size", 28)
		l.add_theme_constant_override("outline_size", 8)
		l.add_theme_color_override("font_outline_color", Color.BLACK)
		l.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
		root.add_child(l)
		names.append(l)
	var vs := TextureRect.new()
	vs.texture = Art.ui("vs")
	vs.expand_mode = TextureRect.EXPAND_IGNORE_SIZE
	vs.stretch_mode = TextureRect.STRETCH_KEEP_ASPECT_CENTERED
	vs.material = Art.additive()
	root.add_child(vs)
	var arena_l := Label.new()
	arena_l.text = String(arena_cfg.get("name", ""))
	arena_l.add_theme_font_size_override("font_size", 26)
	arena_l.add_theme_color_override("font_color", acc.lightened(0.4))
	arena_l.add_theme_constant_override("outline_size", 6)
	arena_l.add_theme_color_override("font_outline_color", Color.BLACK)
	arena_l.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	root.add_child(arena_l)
	root.set_meta("parts", {"sprites": sprites, "names": names, "vs": vs, "arena": arena_l})
	await get_tree().process_frame
	_layout(root)
	var parts: Dictionary = root.get_meta("parts")
	var sz := root.size
	for i in range(2):
		var spr: TextureRect = sprites[i]
		var end_x := spr.position.x
		spr.position.x = end_x + (-sz.x * 0.5 if i == 0 else sz.x * 0.5)
		create_tween().tween_property(spr, "position:x", end_x, 0.5).set_trans(Tween.TRANS_CUBIC).set_ease(Tween.EASE_OUT)
	vs.pivot_offset = vs.size * 0.5
	vs.scale = Vector2(3.0, 3.0)
	vs.modulate.a = 0.0
	var tv := create_tween().set_parallel(true)
	tv.tween_property(vs, "scale", Vector2.ONE, 0.35).set_delay(0.45).set_trans(Tween.TRANS_BACK).set_ease(Tween.EASE_OUT)
	tv.tween_property(vs, "modulate:a", 1.0, 0.2).set_delay(0.45)
	await get_tree().create_timer(seconds).timeout
	var out := create_tween()
	out.tween_property(root, "modulate:a", 0.0, 0.4)
	await out.finished
	finished.emit()
	queue_free()


func _layout(root: Control) -> void:
	var sz := root.size
	if sz.x < 10.0 or not root.has_meta("parts"):
		return
	var parts: Dictionary = root.get_meta("parts")
	for c in root.get_children():
		if c is Polygon2D:
			var side: int = c.get_meta("side")
			c.polygon = PackedVector2Array([Vector2(0, 0), Vector2(sz.x * 0.56, 0), Vector2(sz.x * 0.44, sz.y), Vector2(0, sz.y)]) if side == 0 \
				else PackedVector2Array([Vector2(sz.x * 0.56, 0), Vector2(sz.x, 0), Vector2(sz.x, sz.y), Vector2(sz.x * 0.44, sz.y)])
	for i in range(2):
		var spr: TextureRect = parts["sprites"][i]
		spr.size = Vector2(sz.x * 0.34, sz.y * 0.68)
		spr.position = Vector2(sz.x * (0.08 if i == 0 else 0.58), sz.y * 0.06)
		var l: Label = parts["names"][i]
		l.size = Vector2(sz.x * 0.4, 130)
		l.position = Vector2(sz.x * (0.05 if i == 0 else 0.55), sz.y * 0.76)
		l.add_theme_color_override("font_color", Color(1.0, 0.75, 0.75) if i == 0 else Color(0.75, 0.85, 1.0))
	var vs: TextureRect = parts["vs"]
	vs.size = Vector2(sz.y * 0.4, sz.y * 0.3)
	vs.position = Vector2(sz.x * 0.5 - vs.size.x * 0.5, sz.y * 0.32)
	var al: Label = parts["arena"]
	al.size = Vector2(sz.x, 40)
	al.position = Vector2(0, 10)
