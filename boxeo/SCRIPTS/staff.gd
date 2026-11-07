extends Node3D
## Árbitro, entrenador o comentarista: mismo esqueleto (staff_base.glb), otro color de ropa.

const Data = preload("res://SCRIPTS/data.gd")

var anim: AnimationPlayer
var visual: Node3D


func setup(model: PackedScene, look: Dictionary, table: Dictionary) -> void:
	visual = Node3D.new()
	add_child(visual)
	var inst := model.instantiate()
	visual.add_child(inst)
	Data.apply_look(inst, look)
	anim = Data.find_anim(inst)
	if anim:
		anim.playback_default_blend_time = 0.15
		Data.set_loops(anim, table, "staff")
	play("idle_staff")


func play(clip: String, speed: float = 1.0) -> void:
	if anim and anim.has_animation(clip):
		anim.play(clip, -1.0, speed)
