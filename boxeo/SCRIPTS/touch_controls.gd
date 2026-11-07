extends Control
## Botones táctiles con multitoque (se dibujan por código, sin texturas). Posiciones en UI/ui_layout.json.
## Emite action_pressed(tipo, nombre, presionado): tipo = attack | hold | tap.
## En PC: el ratón emula el toque (project.godot) y el teclado funciona desde main.gd.

signal action_pressed(kind: String, id: String, pressed: bool)

var player = null             # boxeador del jugador (para atenuar golpes sin aguante y marcar la parada)
var buttons: Array = []
var touches := {}            # índice del dedo -> botón
var active := {}             # id del botón -> true mientras está presionado


func setup(layout: Dictionary) -> void:
	buttons = layout["touch_buttons"]
	set_anchors_preset(Control.PRESET_FULL_RECT)
	mouse_filter = Control.MOUSE_FILTER_IGNORE
	queue_redraw()


func _process(_delta: float) -> void:
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
				Input.vibrate_handheld(22 if p[0] == "attack" else 10)
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


const COLORES := {
	"jab": Color(0.25, 0.6, 1.0), "cross": Color(0.95, 0.25, 0.3), "hook": Color(1.0, 0.6, 0.15),
	"upper": Color(0.7, 0.4, 1.0), "body": Color(0.3, 0.85, 0.5), "block": Color(0.2, 0.75, 0.9),
}


func _draw() -> void:
	var font := ThemeDB.fallback_font
	for b in buttons:
		var c := _center(b)
		var on: bool = active.has(b["id"])
		var r := _radius(b) * (0.92 if on else 1.0)
		var kind: String = _split(b)[0]
		var tired: bool = kind == "attack" and player != null and is_instance_valid(player) and float(player.stamina) < 12.0
		var col: Color = COLORES.get(String(b["id"]), Color(0.75, 0.8, 0.95) if kind != "attack" else Color(0.95, 0.3, 0.3))
		if tired:
			col = col.lerp(Color(0.45, 0.45, 0.5), 0.8)
		draw_circle(c + Vector2(0, r * 0.08), r, Color(0, 0, 0, 0.35))                       # sombra
		draw_circle(c, r, Color(0.04, 0.05, 0.1, 0.55))                                      # base oscura
		draw_circle(c, r * 0.86, Color(col.r, col.g, col.b, 0.8 if on else 0.35))            # relleno de color
		draw_arc(c, r * 0.97, 0.0, TAU, 40, Color(col.r, col.g, col.b, 0.95), 4.0, true)     # aro
		draw_arc(c, r * 0.72, PI * 1.1, PI * 1.9, 14, Color(1, 1, 1, 0.35), 3.0, true)       # brillo
		# ventana de parada perfecta: aro dorado mientras la guardia está "fresca"
		if String(b["id"]) == "block" and player != null and is_instance_valid(player) and player.is_parrying():
			draw_arc(c, r * 1.12, 0.0, TAU, 40, Color(1.0, 0.85, 0.2, 0.95), 6.0, true)
		var fs := int(r * 0.34)
		var txt := String(b["label"])
		var tw := font.get_string_size(txt, HORIZONTAL_ALIGNMENT_CENTER, -1, fs).x
		var pos := c + Vector2(-tw * 0.5, fs * 0.35)
		draw_string_outline(font, pos, txt, HORIZONTAL_ALIGNMENT_LEFT, -1, fs, 5, Color(0, 0, 0, 0.9))
		draw_string(font, pos, txt, HORIZONTAL_ALIGNMENT_LEFT, -1, fs, Color.WHITE)
