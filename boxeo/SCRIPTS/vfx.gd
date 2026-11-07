extends Node3D
## Efectos de partículas ligeros (CPUParticles3D reutilizados). Los parámetros salen de VFX/vfx_presets.json.

var presets := {}
var pool: Array = []
var next_i := 0
var mesh: SphereMesh
var mat: StandardMaterial3D


func setup(vfx_data: Dictionary) -> void:
	presets = vfx_data["presets"]
	mesh = SphereMesh.new()
	mesh.radius = 0.5
	mesh.height = 1.0
	mesh.radial_segments = 6
	mesh.rings = 3
	mat = StandardMaterial3D.new()
	mat.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	mat.vertex_color_use_as_albedo = true
	mesh.material = mat
	for i in range(10):
		var e := CPUParticles3D.new()
		e.mesh = mesh
		e.one_shot = true
		e.emitting = false
		e.explosiveness = 0.95
		e.direction = Vector3.UP
		add_child(e)
		pool.append(e)


func burst(preset_name: String, pos: Vector3) -> void:
	if not presets.has(preset_name):
		return
	var p: Dictionary = presets[preset_name]
	var e: CPUParticles3D = pool[next_i]
	next_i = (next_i + 1) % pool.size()
	e.emitting = false
	e.global_position = pos
	e.amount = int(p["amount"])
	e.lifetime = float(p["lifetime"])
	e.spread = float(p["spread"])
	e.initial_velocity_min = float(p["speed"][0])
	e.initial_velocity_max = float(p["speed"][1])
	e.gravity = Vector3(0, float(p["gravity"]), 0)
	e.scale_amount_min = float(p["size"][0])
	e.scale_amount_max = float(p["size"][1])
	e.color = Color.html(String(p["color"]))
	e.restart()
	e.emitting = true
