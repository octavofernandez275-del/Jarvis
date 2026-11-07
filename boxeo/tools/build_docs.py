"""Genera DOCUMENTATION/ASSET_MANIFEST.md y asset_manifest.json escaneando los archivos reales del proyecto."""
import os, json, wave
from PIL import Image
import validate_glb as V

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

USE = {
 "CHARACTERS/boxer_base.glb": ("Boxeador jugable (todos los luchadores comparten este modelo y rig; cambian color, altura y estadísticas).", "Instanciar como PackedScene; el importador de Godot crea Skeleton3D + AnimationPlayer. Colores por nombre de material (Data.apply_look). Ver SCRIPTS/boxer.gd."),
 "CHARACTERS/staff_base.glb": ("Árbitros, entrenadores, presentador y comentaristas (mismo esqueleto, ropa en vez de guantes).", "Igual que el boxeador; clips de categoría 'staff'. Ver SCRIPTS/staff.gd."),
 "CHARACTERS/fighters.json": ("12 boxeadores FICTICIOS: altura, peso, alcance, guardia, estadísticas, estilo, colores, récord.", "Data.load_json('res://CHARACTERS/fighters.json')['fighters']"),
 "RINGS/ring_pro.glb": ("Ring profesional: lona 7.1 m, cuerdas de 4 alturas, postes, esquineros rojo/azul/blanco, escalones y 2 taburetes. Lona en y=0.", "Se instancia en el origen; los boxeadores pelean en z=±1 dentro de ±2.8 (ring_half)."),
 "GYMS/heavy_bag.glb": ("Saco pesado (entrenamiento del modo carrera).", "Colocar con arena_builder.gd (props.heavy_bags)."),
 "GYMS/speed_bag.glb": ("Pera loca con soporte.", "arena_builder.gd (props.speed_bags)."),
 "GYMS/barbell.glb": ("Barra con discos (pesas).", "arena_builder.gd (props.barbells)."),
 "GYMS/weight_bench.glb": ("Banco de pesas.", "arena_builder.gd (props.benches)."),
 "ENVIRONMENTS/crowd_spectator_lod0.glb": ("Espectador LOD0 con brazos arriba (primeras 2 filas).", "crowd.gd lo usa en MultiMesh con color por instancia y shader VFX/crowd_cheer.gdshader."),
 "ENVIRONMENTS/crowd_spectator_lod1.glb": ("Espectador LOD1 (filas 3-5).", "Ídem."),
 "ENVIRONMENTS/crowd_spectator_lod2.glb": ("Espectador LOD2 (filas altas), 1 caja.", "Ídem."),
 "ENVIRONMENTS/entry_tunnel.glb": ("Túnel de entrada con tiras de luz.", "arena_builder.gd lo coloca en arenas/estadio; main.gd hace entrar a los boxeadores desde ahí."),
 "ENVIRONMENTS/locker_bank.glb": ("Fila de 6 casilleros y banco (vestuario).", "arena_builder.gd (props.lockers)."),
 "ARENAS/bleacher_tier.glb": ("Grada escalonada de 10 m (disponible para diseñar arenas propias).", "Instanciar y repetir; el público actual se genera por código en crowd.gd."),
 "ARENAS/scoreboard_cube.glb": ("Marcador colgante de 4 pantallas (material emisivo).", "arena_builder.gd (props.scoreboard)."),
 "ARENAS/light_truss.glb": ("Estructura de focos sobre el ring (luces emisivas).", "arena_builder.gd (props.truss); las luces reales son SpotLight3D creadas por código."),
 "ARENAS/arenas.json": ("7 recintos: gimnasio de barrio, gimnasio pro, arena de ciudad, arena internacional, estadio, vestuario, túnel. Iluminación, materiales, público, cámaras y ambiente propios.", "arena_builder.gd.build(cfg, parent)."),
 "ANIMATIONS/animation_table.json": ("Tabla de golpes (tiempos, daño, alcance medido del modelo), defensa, movimiento, knockdown y lista de clips.", "Fuente única para boxer.gd y fight_manager.gd."),
 "OPPONENTS/ai_profiles.json": ("8 personalidades de IA + 4 dificultades.", "boxer_ai.gd.setup(me, foe, profile, skill)."),
 "OPPONENTS/boxer_ai.gd": ("IA: reconoce ataques, defiende, esquiva, contraataca, combina y aprende patrones del jugador.", "boxer.ai = ai; fight_manager avisa con observe()/notify_*()."),
 "VFX/vfx_presets.json": ("Partículas de impacto, sudor, bloqueo, polvo, confeti; temblor de cámara, cámara lenta y parámetros de repetición.", "SCRIPTS/vfx.gd y main.gd."),
 "VFX/crowd_cheer.gdshader": ("Shader del público: salto con fase distinta por instancia.", "ShaderMaterial en el MultiMesh (crowd.gd)."),
 "CAREER_MODE/career.json": ("Modo carrera: semanas, entrenamientos, equipamiento, gimnasios, patrocinadores ficticios, contratos, ranking, trofeos.", "SCRIPTS/career.gd."),
 "CHAMPIONSHIPS/championships.json": ("Ligas y cinturones ficticios, torneos, secuencia de ceremonia y sistema de puntuación.", "Datos para ampliar el modo carrera; la puntuación 10-point-must ya está en fight_manager.gd."),
 "COACHES/coaches.json": ("3 entrenadores (modelo staff_base.glb, colores propios, voz pendiente).", "staff.gd.setup(model, look, table)."),
 "COACHES/commentators.json": ("Presentador y 2 comentaristas (avatar de cabina + voz pendiente).", "staff.gd."),
 "REFEREES/referees.json": ("2 árbitros con animaciones de cuenta, corte y brazo del ganador.", "main.gd elige uno al azar."),
 "UI/ui_layout.json": ("Posición de los botones táctiles, teclas de PC, opciones de dificultad y lista de pantallas.", "touch_controls.gd."),
 "AUDIO/announcer_lines.json": ("Textos para locutor, árbitro y comentaristas (SIN voces).", "Grabar con voces autorizadas o usar un TTS con licencia."),
 "LOGOS/jarvis_boxing_ai_logo.svg": ("Logo vectorial sin texto (letras como rectángulos).", "Importar como textura (Godot rasteriza SVG); escala de importación 1.0-2.0."),
 "LOGOS/jarvis_boxing_ai_logo.png": ("Logo raster 1600x560 con transparencia.", "TextureRect."),
 "LOGOS/app_icon.svg": ("Icono de la app (vectorial).", "Origen del PNG."),
 "LOGOS/app_icon_512.png": ("Icono 512x512 para Android.", "project.godot -> config/icon; en exportación usar como icono principal."),
}

def describe(path, full):
    ext = os.path.splitext(path)[1].lower()
    if ext == ".glb":
        j, errs = V.validate(full)
        b = open(full, "rb").read()
        verts = tris = 0
        j2, binary = V.load(full)
        for m in j2["meshes"]:
            for p in m["primitives"]:
                verts += j2["accessors"][p["attributes"]["POSITION"]]["count"]; tris += j2["accessors"][p["indices"]]["count"] // 3
        rig = ""
        if j2.get("skins"):
            rig = f"rig: {len(j2['skins'][0]['joints'])} huesos (humanoide), skin rígido; "
        anims = j2.get("animations", [])
        if anims: rig += f"{len(anims)} animaciones embebidas; "
        mats = ", ".join(m["name"] for m in j2["materials"])
        return f"glTF 2.0 binario; {verts} vértices, {tris} triángulos; {rig}materiales: {mats}; sin texturas (colores PBR); validado: {'sí' if not errs else 'CON ERRORES'}"
    if ext == ".wav":
        with wave.open(full) as w: return f"WAV PCM 16 bit mono {w.getframerate()} Hz, {w.getnframes() / w.getframerate():.1f} s"
    if ext == ".png":
        im = Image.open(full); return f"PNG {im.width}x{im.height}"
    if ext == ".svg": return "SVG vectorial (sin elementos de texto)"
    if ext == ".json":
        d = json.load(open(full, encoding="utf-8")); keys = ", ".join(list(d.keys())[:6]); return f"JSON; claves: {keys}"
    if ext == ".gd": return f"GDScript (Godot 4), {sum(1 for _ in open(full, encoding='utf-8'))} líneas"
    if ext == ".gdshader": return "Shader de Godot 4 (spatial)"
    return ext

def main():
    rows = []
    for dp, dn, fs in os.walk(ROOT):
        dn[:] = [d for d in dn if d not in ("tools", ".godot")]
        for f in sorted(fs):
            full = os.path.join(dp, f); rel = os.path.relpath(full, ROOT).replace(os.sep, "/")
            if rel.startswith("DOCUMENTATION/") or rel in ("project.godot", "main.tscn"): continue
            use, integ = USE.get(rel, (None, None))
            if rel.startswith("AUDIO/") and rel.endswith(".wav"):
                use = "Efecto/ambiente/música sintetizado por código (sin derechos de autor)."; integ = "SCRIPTS/sfx.gd lo carga por nombre; para ambientes en bucle sfx.ambience(ruta)."
            if rel.startswith("SCRIPTS/"):
                use = use or "Código del juego."; integ = integ or "Se carga desde SCRIPTS/main.gd."
            rows.append(dict(path=rel, size_kb=round(os.path.getsize(full) / 1024, 1), format=describe(rel, full), use=use or "", integration=integ or ""))
    json.dump(rows, open(os.path.join(ROOT, "DOCUMENTATION", "asset_manifest.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    by = {}
    for r in rows: by.setdefault(r["path"].split("/")[0], []).append(r)
    out = ["# Manifiesto de recursos — JARVIS BOXING AI", "", "Generado automáticamente (tools/build_docs.py) a partir de los archivos reales. Tamaños en KB.", ""]
    tot = sum(r["size_kb"] for r in rows)
    out.append(f"**Total: {len(rows)} archivos, {tot / 1024:.1f} MB.**\n")
    for folder in sorted(by):
        out.append(f"## {folder}/\n")
        out.append("| Archivo | KB | Formato / especificaciones | Uso | Integración |")
        out.append("|---|---|---|---|---|")
        for r in by[folder]:
            out.append(f"| `{r['path']}` | {r['size_kb']} | {r['format']} | {r['use']} | {r['integration']} |")
        out.append("")
    open(os.path.join(ROOT, "DOCUMENTATION", "ASSET_MANIFEST.md"), "w", encoding="utf-8").write("\n".join(out))
    return len(rows), tot

if __name__ == "__main__": print(main())
