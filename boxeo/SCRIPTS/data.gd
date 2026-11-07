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


## Cambia los colores del modelo por nombre de material: look = {"skin": "#aabbcc", "shorts": ..., "gloves": ...}
## Los materiales del .glb se llaman: skin, shorts, gloves, shoes, hair (boxeador) y skin, shirt, pants, shoes, hair (personal).
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
			if look.has(key):
				var m := StandardMaterial3D.new()
				m.albedo_color = Color.html(String(look[key]))
				m.roughness = 0.75
				mi.set_surface_override_material(i, m)


## Marca como bucle las animaciones que la tabla (animation_table.json) declara en bucle.
static func set_loops(anim: AnimationPlayer, table: Dictionary, category: String) -> void:
	if anim == null:
		return
	for c in table.get("clips", []):
		if c["category"] == category and anim.has_animation(c["name"]):
			var a := anim.get_animation(c["name"])
			a.loop_mode = Animation.LOOP_LINEAR if c["loop"] else Animation.LOOP_NONE
