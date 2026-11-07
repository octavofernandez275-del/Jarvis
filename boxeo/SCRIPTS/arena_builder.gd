extends RefCounted
## Construye una arena en tiempo de ejecución a partir de ARENAS/arenas.json: suelo, paredes, luces, público y accesorios.
## Sin escenas pesadas: todo son cajas, luces y los .glb pequeños de ARENAS/, GYMS/ y ENVIRONMENTS/.

const Crowd = preload("res://SCRIPTS/crowd.gd")
const FLOOR_Y := -1.1          # el suelo está 1.1 m bajo la lona del ring (que está en y = 0)


static func _box(parent: Node3D, size: Vector3, pos: Vector3, color: Color, emissive: bool = false) -> void:
	var mi := MeshInstance3D.new()
	var bm := BoxMesh.new()
	bm.size = size
	mi.mesh = bm
	var m := StandardMaterial3D.new()
	m.albedo_color = color
	m.roughness = 0.9
	if emissive:
		m.emission_enabled = true
		m.emission = color
	mi.material_override = m
	mi.position = pos
	parent.add_child(mi)


static func _prop(parent: Node3D, path: String, pos: Vector3, yaw: float = 0.0) -> Node3D:
	var scn := load(path) as PackedScene
	if scn == null:
		return null
	var n := scn.instantiate() as Node3D
	parent.add_child(n)
	n.position = pos
	n.rotation.y = yaw
	return n


## Devuelve {"crowd_mat": ShaderMaterial|null, "has_ring": bool}
static func build(cfg: Dictionary, parent: Node3D) -> Dictionary:
	var size: Array = cfg["size"]
	var w := float(size[0])
	var d := float(size[1])
	var h := float(size[2])
	var kind := String(cfg["kind"])
	var out := {"crowd_mat": null, "has_ring": kind != "tunnel" and kind != "room"}

	# entorno y luz ambiente
	var env := Environment.new()
	env.background_mode = Environment.BG_COLOR
	env.background_color = Color.html(String(cfg["wall"])).darkened(0.6)
	env.ambient_light_source = Environment.AMBIENT_SOURCE_COLOR
	env.ambient_light_color = Color.html(String(cfg["ambient"]))
	env.ambient_light_energy = float(cfg["ambient_energy"])
	env.tonemap_mode = Environment.TONE_MAPPER_FILMIC
	env.tonemap_exposure = 1.1
	env.glow_enabled = true
	env.glow_intensity = 0.7
	env.glow_bloom = 0.08
	env.glow_hdr_threshold = 0.9
	env.adjustment_enabled = true
	env.adjustment_contrast = 1.12
	env.adjustment_saturation = 1.15
	env.fog_enabled = true
	env.fog_light_color = Color.html(String(cfg["wall"])).darkened(0.7)
	env.fog_density = 0.012
	var key := DirectionalLight3D.new()
	parent.add_child(key)
	key.rotation_degrees = Vector3(-62, 35, 0)
	key.light_energy = 0.55
	key.light_color = Color(1.0, 0.96, 0.9)
	key.shadow_enabled = true
	key.directional_shadow_max_distance = 22.0
	var we := WorldEnvironment.new()
	we.environment = env
	parent.add_child(we)

	# suelo y paredes (el estadio no tiene paredes)
	var floor_c := Color.html(String(cfg["floor"]))
	var wall_c := Color.html(String(cfg["wall"]))
	_box(parent, Vector3(w, 0.2, d), Vector3(0, FLOOR_Y - 0.1, 0), floor_c)
	if kind != "stadium":
		_box(parent, Vector3(w, h, 0.3), Vector3(0, FLOOR_Y + h * 0.5, -d * 0.5), wall_c)
		_box(parent, Vector3(w, h, 0.3), Vector3(0, FLOOR_Y + h * 0.5, d * 0.5), wall_c)
		_box(parent, Vector3(0.3, h, d), Vector3(-w * 0.5, FLOOR_Y + h * 0.5, 0), wall_c)
		_box(parent, Vector3(0.3, h, d), Vector3(w * 0.5, FLOOR_Y + h * 0.5, 0), wall_c)
		_box(parent, Vector3(w, 0.3, d), Vector3(0, FLOOR_Y + h, 0), wall_c.darkened(0.3))

	# luces: focos sobre el ring + relleno
	var spots := int(cfg["spot_count"])
	var light_c := Color.html(String(cfg["light_color"]))
	var top := minf(h - 1.0, 8.0) + FLOOR_Y
	for i in range(spots):
		var ang := TAU * float(i) / float(maxi(spots, 1))
		var sl := SpotLight3D.new()
		parent.add_child(sl)
		sl.light_color = light_c
		sl.light_energy = float(cfg["spot_energy"]) * 0.35
		sl.spot_range = h * 2.2
		sl.spot_angle = 38.0
		sl.shadow_enabled = (i < 2)   # sombras en 2 focos: buen aspecto sin matar el rendimiento
		sl.shadow_bias = 0.05
		sl.look_at_from_position(Vector3(cos(ang) * 3.0, top, sin(ang) * 3.0), Vector3(0, 0, 0), Vector3.UP)
	var fill := OmniLight3D.new()
	parent.add_child(fill)
	fill.light_color = light_c
	fill.light_energy = 0.7
	fill.omni_range = maxf(w, d)
	fill.position = Vector3(0, top, 0)

	# público
	out["crowd_mat"] = Crowd.build(parent, cfg, FLOOR_Y)

	# accesorios
	var props: Dictionary = cfg.get("props", {})
	for p in props.get("heavy_bags", []):
		_prop(parent, "res://GYMS/heavy_bag.glb", Vector3(p[0], FLOOR_Y, p[2]))
	for p in props.get("speed_bags", []):
		_prop(parent, "res://GYMS/speed_bag.glb", Vector3(p[0], FLOOR_Y, p[2]))
	for p in props.get("barbells", []):
		_prop(parent, "res://GYMS/barbell.glb", Vector3(p[0], FLOOR_Y, p[2]))
	for p in props.get("benches", []):
		_prop(parent, "res://GYMS/weight_bench.glb", Vector3(p[0], FLOOR_Y, p[2]))
	for p in props.get("lockers", []):
		_prop(parent, "res://ENVIRONMENTS/locker_bank.glb", Vector3(p[0], FLOOR_Y, p[2]))
	if props.get("truss", false):
		_prop(parent, "res://ARENAS/light_truss.glb", Vector3(0, FLOOR_Y, 0))
	if props.get("scoreboard", false):
		_prop(parent, "res://ARENAS/scoreboard_cube.glb", Vector3(0, FLOOR_Y + minf(h - 3.0, 9.0), 0))
	if props.get("tunnel", false):
		_prop(parent, "res://ENVIRONMENTS/entry_tunnel.glb", Vector3(0, FLOOR_Y, 0))

	# túneles de entrada de los dos boxeadores (solo en arenas con público)
	if kind == "arena" or kind == "stadium":
		_prop(parent, "res://ENVIRONMENTS/entry_tunnel.glb", Vector3(0, FLOOR_Y, -11.2))
		_prop(parent, "res://ENVIRONMENTS/entry_tunnel.glb", Vector3(0, FLOOR_Y, 11.2))
	# ring
	if out["has_ring"]:
		_prop(parent, "res://RINGS/ring_pro.glb", Vector3.ZERO)
	return out
