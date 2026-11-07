extends RefCounted
## Público 3D con 3 niveles de detalle (LOD) y una llamada de dibujo por nivel (MultiMesh).
## Cerca del ring: lod0 (126 vértices); gradas medias: lod1 (48); gradas altas: lod2 (24).

const SHADER := "res://VFX/crowd_cheer.gdshader"


## Devuelve el ShaderMaterial (para subir/bajar la euforia con set_shader_parameter("excitement", x)) o null si no hay público.
static func build(parent: Node3D, cfg: Dictionary, floor_y: float) -> ShaderMaterial:
	var crowd: Dictionary = cfg["crowd"]
	var tiers := int(crowd["tiers"])
	if tiers <= 0:
		return null
	var density := float(crowd["density"])
	var palette: Array = crowd["palette"]
	var meshes: Array = []
	for lod in range(3):
		var scn: PackedScene = load("res://ENVIRONMENTS/crowd_spectator_lod%d.glb" % lod)
		var inst := scn.instantiate()
		var found := inst.find_children("*", "MeshInstance3D", true, false)
		meshes.append((found[0] as MeshInstance3D).mesh)
		inst.free()
	var mat := ShaderMaterial.new()
	mat.shader = load(SHADER)
	mat.set_shader_parameter("excitement", float(crowd["excitement"]))
	var xf: Array = [[], [], []]
	var cols: Array = [[], [], []]
	var rng := RandomNumberGenerator.new()
	rng.seed = 4242
	for t in range(tiers):
		var r := 5.6 + 1.25 * float(t)
		var y := floor_y + 0.25 + 0.5 * float(t)
		var count := int(TAU * r / 0.62)
		var lod := 0 if t < 2 else (1 if t < 5 else 2)
		for i in range(count):
			if rng.randf() > density:
				continue
			var ang := TAU * float(i) / float(count) + rng.randf_range(-0.02, 0.02)
			var pos := Vector3(cos(ang) * r, y, sin(ang) * r)
			if absf(pos.x) < 2.4 and absf(pos.z) > 4.5:
				continue      # deja libres los túneles de entrada
			var yaw := atan2(-cos(ang), -sin(ang)) + rng.randf_range(-0.35, 0.35)     # mira al ring, cada uno a su manera
			var s := rng.randf_range(0.86, 1.14)
			var basis := Basis(Vector3.UP, yaw).scaled(Vector3(s, s, s))
			xf[lod].append(Transform3D(basis, pos))
			cols[lod].append(Color.html(String(palette[rng.randi() % palette.size()])))
	for lod in range(3):
		if xf[lod].is_empty():
			continue
		var mm := MultiMesh.new()
		mm.transform_format = MultiMesh.TRANSFORM_3D
		mm.use_colors = true
		mm.mesh = meshes[lod]
		mm.instance_count = xf[lod].size()
		for i in range(xf[lod].size()):
			mm.set_instance_transform(i, xf[lod][i])
			mm.set_instance_color(i, cols[lod][i])
		var mmi := MultiMeshInstance3D.new()
		mmi.multimesh = mm
		mmi.material_override = mat
		parent.add_child(mmi)
	return mat
