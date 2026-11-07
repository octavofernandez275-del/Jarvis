extends RefCounted
## Utilidades estáticas: leer JSON y preparar modelos importados (colores, animaciones).
## Uso: const Data = preload("res://SCRIPTS/data.gd")  ->  Data.load_json("res://...")


static func load_json(path: String) -> Variant:
	var f := FileAccess.open(path, FileAccess.READ)
	if f == null:
		push_error("No se pudo abrir " + path)
		return null
	var parsed = JSON.parse_string(f.get_as_text())
	if parsed == null:
		push_error("JSON inválido: " + path)
	return parsed


static func find_by_id(list: Array, id: String) -> Dictionary:
	for item in list:
		if item.get("id", "") == id:
			return item
	return {}


## Busca el AnimationPlayer que crea el importador de glTF dentro del modelo.
static func find_anim(root: Node) -> AnimationPlayer:
	var found := root.find_children("*", "AnimationPlayer", true, false)
	if found.is_empty():
		return null
	return found[0] as AnimationPlayer


## Material con aspecto cuidado según la parte: piel con brillo de sudor y luz de contorno, guantes de cuero con barniz,
## tela mate. El nombre queda en resource_name para poder animarlo después (golpe, sudor, moratones; ver polish.gd).
static var _tex_cache := {}


## Texturas generadas por código (periódicas, sin archivos): piel con poros, cuero del guante y tela con trama.
## Los modelos no tienen UV, por eso se proyectan en triplanar.
static func _tex(kind: String) -> ImageTexture:
	if _tex_cache.has(kind):
		return _tex_cache[kind]
	var n := 128
	var img := Image.create(n, n, false, Image.FORMAT_RGB8)
	var rng := RandomNumberGenerator.new()
	rng.seed = hash(kind)
	for y in range(n):
		for x in range(n):
			var u := float(x) / n
			var w := float(y) / n
			var v := 1.0
			match kind:
				"skin":
					v = 0.93 + 0.035 * sin(TAU * (3.0 * u + 2.0 * w)) + 0.03 * sin(TAU * (5.0 * u - 7.0 * w + 0.3)) + 0.02 * sin(TAU * (11.0 * u + 13.0 * w)) + rng.randf_range(-0.03, 0.03)
				"leather":
					v = 0.90 + 0.05 * sin(TAU * (9.0 * u)) * sin(TAU * (9.0 * w)) + 0.04 * sin(TAU * (4.0 * u + 3.0 * w)) + rng.randf_range(-0.05, 0.05)
				"cloth":
					v = 0.90 + 0.07 * sin(TAU * 20.0 * u) * sin(TAU * 20.0 * w) + rng.randf_range(-0.02, 0.02)
			v = clampf(v, 0.0, 1.0)
			img.set_pixel(x, y, Color(v, v, v))
	img.generate_mipmaps()
	var t := ImageTexture.create_from_image(img)
	_tex_cache[kind] = t
	return t


static func _triplanar(m: StandardMaterial3D, kind: String, scale: float) -> void:
	m.albedo_texture = _tex(kind)
	m.uv1_triplanar = true
	m.uv1_scale = Vector3(scale, scale, scale)
	m.texture_filter = BaseMaterial3D.TEXTURE_FILTER_LINEAR_WITH_MIPMAPS


static func make_mat(key: String, color: Color) -> StandardMaterial3D:
	var m := StandardMaterial3D.new()
	m.resource_name = key
	m.albedo_color = color
	m.roughness = 0.75
	match key:
		"skin":
			m.roughness = 0.6
			m.metallic_specular = 0.45
			m.rim_enabled = true
			m.rim = 0.4
			m.rim_tint = 0.5
			m.emission_enabled = true          # negro = sin efecto; se usa para el destello al recibir un golpe
			m.emission = Color.BLACK
			m.set_meta("base", color)
			_triplanar(m, "skin", 6.0)
		"gloves":
			m.roughness = 0.34
			m.metallic_specular = 0.8
			m.clearcoat_enabled = true
			m.clearcoat = 0.6
			m.clearcoat_roughness = 0.25
			_triplanar(m, "leather", 4.0)
		"shorts", "shirt", "pants":
			m.roughness = 0.85
			_triplanar(m, "cloth", 5.0)
			m.rim_enabled = true
			m.rim = 0.2
			m.rim_tint = 0.3
		"shoes":
			m.roughness = 0.5
			m.metallic_specular = 0.6
		"hair":
			m.roughness = 0.5
			m.metallic_specular = 0.6
	return m


## Cambia los colores del modelo por nombre de material: look = {"skin": "#aabbcc", "shorts": ..., "gloves": ...}
## Los materiales del .glb se llaman: skin, shorts, gloves, shoes, hair (boxeador) y skin, shirt, pants, shoes, hair (personal).
## Todos los materiales con nombre conocido se sustituyen por la versión mejorada (con el color del look o el original).
static func apply_look(root: Node, look: Dictionary) -> void:
	for node in root.find_children("*", "MeshInstance3D", true, false):
		var mi := node as MeshInstance3D
		if mi.mesh == null:
			continue
		for i in range(mi.mesh.get_surface_count()):
			var base: Material = mi.mesh.surface_get_material(i)
			if base == null:
				continue
			var key := String(base.resource_name)
			if key == "":
				continue
			var col := Color(0.8, 0.8, 0.8)
			if base is BaseMaterial3D:
				col = (base as BaseMaterial3D).albedo_color
			if look.has(key):
				col = Color.html(String(look[key]))
			mi.set_surface_override_material(i, make_mat(key, col))


## Marca como bucle las animaciones que la tabla (animation_table.json) declara en bucle.
static func set_loops(anim: AnimationPlayer, table: Dictionary, category: String) -> void:
	if anim == null:
		return
	for c in table.get("clips", []):
		if c["category"] == category and anim.has_animation(c["name"]):
			var a := anim.get_animation(c["name"])
			a.loop_mode = Animation.LOOP_LINEAR if c["loop"] else Animation.LOOP_NONE
