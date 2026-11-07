extends RefCounted
## Mejoras gráficas sin texturas externas (todo se genera por código y es ligero para móvil):
##  - iluminación con más contraste: foco cálido principal + luz de contorno fría, ACES, brillo y sombras
##  - ring: lona con trama y logo central, cuerdas y postes con brillo, esquinas acolchadas
##  - conos de luz (haces de los focos) con mezcla aditiva
##  - flashes de cámaras del público en golpes fuertes y caídas
##  - destello de color al recibir un golpe, y sudor/enrojecimiento de la piel a lo largo de la pelea

const CONE_SHADER := """
shader_type spatial;
render_mode unshaded, blend_add, cull_disabled, depth_draw_never, fog_disabled;
uniform vec4 tint : source_color = vec4(1.0, 0.95, 0.82, 1.0);
uniform float strength = 0.16;
uniform float half_h = 4.0;
varying float t;
void vertex() {
	t = clamp((VERTEX.y + half_h) / (2.0 * half_h), 0.0, 1.0);   // 0 abajo (ring), 1 arriba (foco)
}
void fragment() {
	float edge = pow(abs(dot(normalize(NORMAL), normalize(VIEW))), 1.6);
	ALBEDO = tint.rgb;
	ALPHA = clamp(strength * edge * (0.25 + 0.75 * t), 0.0, 1.0);
}
"""

static var _logo_tex: ImageTexture = null
static var _canvas_tex: ImageTexture = null


# ---------------- escena / arena ----------------

## Se llama justo después de ArenaBuilder.build(). Devuelve {"flashes": Node3D|null} para usar en golpes fuertes.
static func apply_arena(world: Node3D, cfg: Dictionary, floor_y: float, has_crowd: bool, has_ring: bool) -> Dictionary:
	var out := {"flashes": null}
	_tune_environment(world, cfg)
	_tune_lights(world, cfg)
	if has_ring:
		_tune_ring(world)
		_add_logo(world)
	_add_cones(world, cfg)
	if has_crowd:
		out["flashes"] = _make_flashes(world, floor_y)
	return out


static func _tune_environment(world: Node3D, cfg: Dictionary) -> void:
	for n in world.find_children("*", "WorldEnvironment", false, false):
		var env: Environment = (n as WorldEnvironment).environment
		if env == null:
			continue
		env.tonemap_mode = Environment.TONE_MAPPER_ACES
		env.tonemap_exposure = 1.0
		env.tonemap_white = 5.0
		env.ambient_light_energy = float(env.ambient_light_energy) * 0.7     # más contraste: el ring destaca
		env.glow_enabled = true
		env.glow_intensity = 0.8
		env.glow_bloom = 0.1
		env.glow_hdr_threshold = 0.85
		env.glow_hdr_scale = 2.0
		env.adjustment_enabled = true
		env.adjustment_contrast = 1.14
		env.adjustment_saturation = 1.12
		env.fog_density = 0.01


static func _tune_lights(world: Node3D, cfg: Dictionary) -> void:
	var has_rim := false
	for l in world.find_children("*", "DirectionalLight3D", false, false):
		var d := l as DirectionalLight3D
		if d.light_energy > 0.0 and not has_rim:
			d.light_energy = 0.42
			d.light_color = Color(1.0, 0.93, 0.82)       # foco principal cálido
			has_rim = true
	for sl in world.find_children("*", "SpotLight3D", false, false):
		var sp := sl as SpotLight3D
		sp.light_energy = sp.light_energy * 1.5           # los focos pintan el ring con luz más fuerte
	# luz de contorno fría desde atrás: separa a los boxeadores del fondo (sin sombras = barata)
	var rim := DirectionalLight3D.new()
	world.add_child(rim)
	rim.rotation_degrees = Vector3(-28, -145, 0)
	rim.light_color = Color(0.55, 0.7, 1.0)
	rim.light_energy = 0.38
	rim.shadow_enabled = false


# ---------------- ring ----------------

static func _make_canvas_tex() -> ImageTexture:
	if _canvas_tex != null:
		return _canvas_tex
	var n := 128
	var img := Image.create(n, n, true, Image.FORMAT_RGB8)
	var rng := RandomNumberGenerator.new()
	rng.seed = 77
	for y in range(n):
		for x in range(n):
			# trama de lona: hilos finos cruzados + ruido suave
			var weave := 0.04 * (1.0 if (x % 4 < 2) != (y % 4 < 2) else -1.0)
			var noise := rng.randf_range(-0.025, 0.025)
			var v := 0.88 + weave + noise
			img.set_pixel(x, y, Color(v, v, v * 0.97))
	img.generate_mipmaps()
	_canvas_tex = ImageTexture.create_from_image(img)
	return _canvas_tex


static func _tune_ring(world: Node3D) -> void:
	for node in world.find_children("*", "MeshInstance3D", true, false):
		var mi := node as MeshInstance3D
		if mi.mesh == null:
			continue
		for i in range(mi.mesh.get_surface_count()):
			var base: Material = mi.mesh.surface_get_material(i)
			if base == null or not (base is BaseMaterial3D):
				continue
			var key := String(base.resource_name)
			var m := (base as BaseMaterial3D).duplicate() as StandardMaterial3D
			if m == null:
				continue
			match key:
				"canvas":
					m.albedo_texture = _make_canvas_tex()
					m.uv1_triplanar = true
					m.uv1_world_triplanar = true
					m.uv1_scale = Vector3(1.4, 1.4, 1.4)
					m.texture_filter = BaseMaterial3D.TEXTURE_FILTER_LINEAR_WITH_MIPMAPS
					m.roughness = 0.9
				"post":
					m.metallic = 0.85
					m.roughness = 0.28
				"rope_red", "rope_white", "rope_blue":
					m.roughness = 0.38
					m.metallic_specular = 0.7
					m.rim_enabled = true
					m.rim = 0.3
				"pad_red", "pad_blue", "pad_white":
					m.roughness = 0.55
					m.metallic_specular = 0.6
				"apron":
					m.roughness = 0.8
				_:
					continue
			mi.set_surface_override_material(i, m)


## Logo grande en el centro de la lona: aros concentricos, franja y esquinas de color (rojo / azul).
static func _make_logo_tex() -> ImageTexture:
	if _logo_tex != null:
		return _logo_tex
	var n := 256
	var img := Image.create(n, n, true, Image.FORMAT_RGBA8)
	img.fill(Color(0, 0, 0, 0))
	var c := Vector2(n * 0.5, n * 0.5)
	for y in range(n):
		for x in range(n):
			var d := Vector2(x, y).distance_to(c) / (n * 0.5)
			var a := 0.0
			var col := Color(0.08, 0.1, 0.3)
			if d > 0.88 and d < 0.96:
				a = 0.9
			elif d > 0.72 and d < 0.76:
				a = 0.75
				col = Color(0.75, 0.1, 0.1)
			elif d < 0.2:
				a = 0.85
				col = Color(0.75, 0.1, 0.1)
			elif d > 0.22 and d < 0.27:
				a = 0.8
			# estrella simple en el centro del aro rojo: cuatro rayos
			if d < 0.62 and (absf(float(x) - c.x) < 4.0 or absf(float(y) - c.y) < 4.0) and d > 0.28:
				a = 0.55
			if a > 0.0:
				img.set_pixel(x, y, Color(col.r, col.g, col.b, a))
	img.generate_mipmaps()
	_logo_tex = ImageTexture.create_from_image(img)
	return _logo_tex


static func _add_logo(world: Node3D) -> void:
	var quad := MeshInstance3D.new()
	var pm := PlaneMesh.new()
	pm.size = Vector2(5.2, 5.2)
	quad.mesh = pm
	var m := StandardMaterial3D.new()
	m.albedo_texture = _make_logo_tex()
	m.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	m.roughness = 0.9
	m.cull_mode = BaseMaterial3D.CULL_DISABLED
	quad.material_override = m
	quad.position = Vector3(0, 0.006, 0)
	quad.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	world.add_child(quad)


# ---------------- haces de luz ----------------

static func _add_cones(world: Node3D, cfg: Dictionary) -> void:
	var spots := world.find_children("*", "SpotLight3D", false, false)
	if spots.is_empty():
		return
	var sh := Shader.new()
	sh.code = CONE_SHADER
	var kind := String(cfg.get("kind", "gym"))
	var strength := 0.10 if kind == "gym" else 0.14
	for s in spots:
		var sl := s as SpotLight3D
		var from: Vector3 = sl.position
		var to := Vector3(0, 0.0, 0)
		var dist := from.distance_to(to)
		if dist < 1.0:
			continue
		var cone := MeshInstance3D.new()
		var cm := CylinderMesh.new()
		cm.height = dist
		cm.top_radius = 0.06
		cm.bottom_radius = dist * tan(deg_to_rad(20.0))
		cm.radial_segments = 20
		cm.rings = 1
		cm.cap_top = false
		cm.cap_bottom = false
		cone.mesh = cm
		var mat := ShaderMaterial.new()
		mat.shader = sh
		mat.set_shader_parameter("strength", strength)
		mat.set_shader_parameter("half_h", dist * 0.5)
		mat.set_shader_parameter("tint", sl.light_color)
		cone.material_override = mat
		cone.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
		world.add_child(cone)
		# el cilindro mide su altura en Y con la punta fina arriba: el eje Y apunta del ring hacia el foco
		var y_axis := (from - to).normalized()
		var up_ref := Vector3.UP if absf(y_axis.dot(Vector3.UP)) < 0.99 else Vector3.RIGHT
		var x_axis := y_axis.cross(up_ref).normalized()
		var z_axis := x_axis.cross(y_axis).normalized()
		cone.transform = Transform3D(Basis(x_axis, y_axis, z_axis), (from + to) * 0.5)


# ---------------- flashes del público ----------------

static func _make_flashes(world: Node3D, floor_y: float) -> Node3D:
	var root := Node3D.new()
	root.name = "Flashes"
	world.add_child(root)
	var mesh := QuadMesh.new()
	mesh.size = Vector2(0.55, 0.55)
	var mat := StandardMaterial3D.new()
	mat.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	mat.billboard_mode = BaseMaterial3D.BILLBOARD_ENABLED
	mat.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	mat.albedo_color = Color(2.5, 2.4, 2.2, 1.0)
	mat.blend_mode = BaseMaterial3D.BLEND_MODE_ADD
	root.set_meta("fy", floor_y)
	for i in range(8):
		var q := MeshInstance3D.new()
		q.mesh = mesh
		q.material_override = mat
		q.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
		q.visible = false
		root.add_child(q)
	return root


## Parpadean varias cámaras del público (llamar tras un golpe fuerte o una caída).
static func flash_burst(flashes: Node3D, count: int) -> void:
	if flashes == null or not is_instance_valid(flashes):
		return
	var kids := flashes.get_children()
	var fy: float = float(flashes.get_meta("fy", -1.1))
	for i in range(mini(count, kids.size())):
		var q := kids[i] as MeshInstance3D
		# posición nueva en las gradas (la altura sigue la inclinación de los niveles del público)
		var ang := randf() * TAU
		var r := randf_range(6.5, 11.0)
		var tier := (r - 5.6) / 1.25
		q.position = Vector3(cos(ang) * r, fy + 0.25 + 0.5 * tier + randf_range(1.2, 1.7), sin(ang) * r)
		var tw := flashes.create_tween()
		tw.tween_interval(randf_range(0.0, 0.5))
		tw.tween_callback(q.set_visible.bind(true))
		tw.tween_interval(0.07)
		tw.tween_callback(q.set_visible.bind(false))
		tw.tween_interval(0.12)
		tw.tween_callback(q.set_visible.bind(true))
		tw.tween_interval(0.05)
		tw.tween_callback(q.set_visible.bind(false))


# ---------------- boxeadores ----------------

static func _skin_mats(bx: Node) -> Array:
	var out: Array = []
	if bx == null or not ("visual" in bx) or bx.visual == null:
		return out
	for node in bx.visual.find_children("*", "MeshInstance3D", true, false):
		var mi := node as MeshInstance3D
		if mi.mesh == null:
			continue
		for i in range(mi.mesh.get_surface_count()):
			var m := mi.get_surface_override_material(i) as StandardMaterial3D
			if m != null and String(m.resource_name) == "skin":
				out.append(m)
	return out


## Destello de color en la piel del que recibe el golpe (blanco = ligero, naranja/rojo = fuerte).
static func hit_flash(bx: Node, heavy: bool) -> void:
	if bx == null or not is_instance_valid(bx):
		return
	var col := Color(0.55, 0.12, 0.05) if heavy else Color(0.25, 0.1, 0.06)
	for m in _skin_mats(bx):
		var mat := m as StandardMaterial3D
		if mat.has_meta("tw"):
			var old = mat.get_meta("tw")
			if old is Tween and (old as Tween).is_valid():
				(old as Tween).kill()
		mat.emission = col
		var tw: Tween = bx.create_tween()
		tw.tween_property(mat, "emission", Color.BLACK, 0.28 if heavy else 0.18)
		mat.set_meta("tw", tw)


## Sudor (piel más brillante) y enrojecimiento según el cansancio y los golpes recibidos. level: 0..1
static func wear(bx: Node, level: float) -> void:
	var lv := clampf(level, 0.0, 1.0)
	for m in _skin_mats(bx):
		var mat := m as StandardMaterial3D
		mat.roughness = lerpf(0.62, 0.3, lv)
		if mat.has_meta("base"):
			var base: Color = mat.get_meta("base")
			var red := Color(minf(1.0, base.r * 1.08), base.g * 0.78, base.b * 0.74)
			mat.albedo_color = base.lerp(red, lv * 0.7)


## Sudor/enrojecimiento recomendado para un boxeador en un momento de la pelea.
static func wear_level(bx: Node, round_n: int, total_rounds: int) -> float:
	var progress := float(maxi(0, round_n - 1)) / float(maxi(1, total_rounds))
	var damage := 1.0 - clampf(float(bx.health) / 100.0, 0.0, 1.0)
	return clampf(progress * 0.6 + damage * 0.6, 0.0, 1.0)
