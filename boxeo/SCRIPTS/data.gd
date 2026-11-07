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
		"gloves":
			m.roughness = 0.34
			m.metallic_specular = 0.8
			m.clearcoat_enabled = true
			m.clearcoat = 0.6
			m.clearcoat_roughness = 0.25
		"shorts", "shirt", "pants":
			m.roughness = 0.85
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
