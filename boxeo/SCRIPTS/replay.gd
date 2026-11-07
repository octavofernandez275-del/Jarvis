extends Node
## Repetición ligera: guarda la posición y el clip/tiempo de animación de cada personaje ~5 s y los reproduce en cámara lenta.
## No re-simula el combate: solo vuelve a mostrar lo grabado.

signal finished

var chars: Array = []          # boxeadores (y árbitro si tiene .anim/.last_clip)
var frames: Array = []
var highlight: Array = []      # copia congelada del último momento clave (knockdown)
var src: Array = []
var interval := 1.0 / 30.0
var max_frames := 150
var timer := 0.0
var playing := false
var idx := 0.0
var speed := 0.4
var backup: Array = []


func setup(list: Array, hz: float, seconds: float, play_speed: float) -> void:
	chars = list
	interval = 1.0 / hz
	max_frames = int(hz * seconds)
	speed = play_speed


func _physics_process(delta: float) -> void:
	if playing:
		_step(delta)
		return
	timer += delta
	if timer >= interval:
		timer = 0.0
		var f: Array = []
		for c in chars:
			var pos_t := 0.0
			if c.anim != null:
				pos_t = c.anim.current_animation_position
			f.append([c.position.z, c.rotation.y, String(c.last_clip), pos_t])
		frames.append(f)
		if frames.size() > max_frames:
			frames.pop_front()


## Guarda lo grabado hasta ahora como momento clave (main.gd lo llama ~2.5 s después de un knockdown).
func capture_highlight() -> void:
	highlight = frames.duplicate(true)


func start() -> bool:
	src = highlight if highlight.size() >= 10 else frames
	if src.size() < 10 or playing:
		return false
	backup = []
	for c in chars:
		backup.append([c.position.z, c.rotation.y])
		c.replay_mode = true
	playing = true
	idx = 0.0
	return true


func _step(delta: float) -> void:
	idx += delta * speed / interval
	var i := int(idx)
	if i >= src.size():
		_stop()
		return
	var f: Array = src[i]
	for k in range(chars.size()):
		var c = chars[k]
		c.position.z = float(f[k][0])
		c.rotation.y = float(f[k][1])
		var clip: String = f[k][2]
		if c.anim != null and clip != "" and c.anim.has_animation(clip):
			if c.anim.current_animation != clip:
				c.anim.play(clip, 0.0)
			c.anim.speed_scale = 0.0
			c.anim.seek(float(f[k][3]), true)


func _stop() -> void:
	playing = false
	for k in range(chars.size()):
		var c = chars[k]
		c.position.z = float(backup[k][0])
		c.rotation.y = float(backup[k][1])
		c.replay_mode = false
		if c.anim != null:
			c.anim.speed_scale = 1.0
			if String(c.last_clip) != "" and c.anim.has_animation(c.last_clip):
				c.anim.play(c.last_clip, 0.2)
	finished.emit()
