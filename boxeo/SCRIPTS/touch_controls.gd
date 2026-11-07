extends Control
## Botones táctiles con multitoque (se dibujan por código, sin texturas). Posiciones en UI/ui_layout.json.
## Emite action_pressed(tipo, nombre, presionado): tipo = attack | hold | tap.
## En PC: el ratón emula el toque (project.godot) y el teclado funciona desde main.gd.

signal action_pressed(kind: String, id: String, pressed: bool)

var buttons: Array = []
var touches := {}            # índice del dedo -> botón
var active := {}             # id del botón -> true mientras está presionado


func setup(layout: Dictionary) -> void:
	buttons = layout["touch_buttons"]
	set_anchors_preset(Control.PRESET_FULL_RECT)
	mouse_filter = Control.MOUSE_FILTER_IGNORE
	queue_redraw()


func _center(b: Dictionary) -> Vector2:
	var sz := get_viewport_rect().size
	return Vector2(float(b["x"]) * sz.x, float(b["y"]) * sz.y)


func _radius(b: Dictionary) -> float:
	return float(b["r"]) * get_viewport_rect().size.y


func _hit(pos: Vector2) -> Dictionary:
	for b in buttons:
		if pos.distance_to(_center(b)) <= _radius(b) * 1.15:
			return b
	return {}


func _split(b: Dictionary) -> Array:
	return String(b["action"]).split(":")


func _input(event: InputEvent) -> void:
	if event is InputEventScreenTouch:
		if event.pressed:
			var b := _hit(event.position)
			if not b.is_empty():
				touches[event.index] = b
				active[b["id"]] = true
				var p := _split(b)
				action_pressed.emit(p[0], p[1], true)
				queue_redraw()
		else:
			if touches.has(event.index):
				var b: Dictionary = touches[event.index]
				touches.erase(event.index)
				active.erase(b["id"])
				var p := _split(b)
				action_pressed.emit(p[0], p[1], false)
				queue_redraw()


func _draw() -> void:
	var font := ThemeDB.fallback_font
	for b in buttons:
		var c := _center(b)
		var r := _radius(b)
		var on: bool = active.has(b["id"])
		var kind: String = _split(b)[0]
		var base := Color(0.9, 0.22, 0.27) if kind == "attack" else Color(0.2, 0.28, 0.48)
		draw_circle(c, r, Color(base.r, base.g, base.b, 0.85 if on else 0.55))
		draw_arc(c, r, 0.0, TAU, 32, Color(1, 1, 1, 0.8), 3.0)
		var fs := int(r * 0.42)
		var tw := font.get_string_size(String(b["label"]), HORIZONTAL_ALIGNMENT_CENTER, -1, fs).x
		draw_string(font, c + Vector2(-tw * 0.5, fs * 0.35), String(b["label"]), HORIZONTAL_ALIGNMENT_LEFT, -1, fs, Color.WHITE)
