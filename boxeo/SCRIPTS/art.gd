extends RefCounted
## Acceso a las imágenes de ART/ (retratos, sprites, fondos, efectos, equipo, interfaz). Todo con caché y sin fallar si falta un archivo.

static var _cache: Dictionary = {}

const FX := ["fx_blue_burst", "fx_red_burst", "fx_white_burst", "fx_fire", "fx_fire2", "fx_gold_burst", "fx_orange_burst", "fx_smoke",
	"fx_red_splat", "fx_claw", "fx_blue_swoosh", "fx_red_swoosh", "fx_sparks", "fx_stars", "fx_white_cloud", "fx_blue_lightning"]
const GLOVES := {"glove_red": "#c62828", "glove_blue": "#1565c0", "glove_black": "#1c1c1c", "glove_black_red": "#3a1418", "glove_white": "#f2f2f2", "glove_white2": "#dfe6f5"}
const SHORTS := {"shorts_red": "#c62828", "shorts_blue": "#1b4fc4", "shorts_blue2": "#1e63d6", "shorts_black": "#1a1a1a", "shorts_gold": "#d4a017", "shorts_goldblack": "#8a6a10", "shorts_white": "#f0f0f0"}
const BOOTS := {"boots_red": "#c62828", "boots_blue": "#1b4fc4", "boots_white": "#f0f0f0", "boots_gold": "#d4a017", "boots_black": "#111111"}
const BELTS := {"wbc": "belt_wbc", "gold": "belt_gold", "gold2": "belt_gold2", "blue": "belt_blue", "green": "belt_green"}


static func tex(path: String) -> Texture2D:
	if path == "":
		return null
	if _cache.has(path):
		return _cache[path]
	var t: Texture2D = null
	if ResourceLoader.exists(path):
		t = load(path) as Texture2D
	if t == null:
		var im := Image.load_from_file(path)
		if im != null and not im.is_empty():
			t = ImageTexture.create_from_image(im)
	_cache[path] = t
	return t


static func fx(name: String) -> Texture2D:
	return tex("res://ART/fx/%s.png" % name)


static func gear(name: String) -> Texture2D:
	return tex("res://ART/gear/%s.png" % name)


static func ui(name: String) -> Texture2D:
	var t := tex("res://ART/ui/%s.png" % name)
	return t if t != null else tex("res://ART/ui/%s.jpg" % name)


static func backdrop(path: String) -> Texture2D:
	return tex(path)


static func additive() -> CanvasItemMaterial:
	var m := CanvasItemMaterial.new()
	m.blend_mode = CanvasItemMaterial.BLEND_MODE_ADD
	return m


## Fondo aleatorio para menús.
static func random_backdrop(rng_seed: int = -1) -> Texture2D:
	var d := DirAccess.open("res://ART/backdrops")
	var names: Array = []
	if d != null:
		for f in d.get_files():
			var n := String(f).replace(".import", "")
			if n.ends_with(".jpg") and not names.has(n):
				names.append(n)
	if names.is_empty():
		return null
	names.sort()
	var i := (randi() if rng_seed < 0 else rng_seed) % names.size()
	return tex("res://ART/backdrops/" + String(names[i]))
