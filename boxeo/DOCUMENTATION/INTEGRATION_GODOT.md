# Integración en Godot 4.3+

1. **Abrir**: `Importar` → `project.godot`. La primera vez Godot importa los `.glb`, `.wav` y `.svg` (tarda unos segundos).
2. **Ejecutar**: F5. Escena principal `main.tscn` (solo carga `SCRIPTS/main.gd`; todo se construye por código).
3. **Cargar un boxeador en tu propia escena**
```gdscript
const Data = preload("res://SCRIPTS/data.gd")
var table = Data.load_json("res://ANIMATIONS/animation_table.json")
var f = Data.load_json("res://CHARACTERS/fighters.json")["fighters"][0]
var bx = preload("res://SCRIPTS/boxer.gd").new()
add_child(bx)
bx.setup(f, table, load("res://CHARACTERS/boxer_base.glb"), true)
bx.cmd_attack("jab")
```
4. **Animaciones**: el importador crea un `AnimationPlayer` con los clips por nombre. `Data.set_loops` aplica los bucles de la tabla.
5. **Colores por boxeador**: `Data.apply_look(modelo, {"skin": "#aa7755", "shorts": "#cc2222", "gloves": "#222222"})`.
6. **IA**: `var ai = preload("res://OPPONENTS/boxer_ai.gd").new(); ai.setup(rival, jugador, perfil_dict, 0.8); rival.ai = ai`. Después `fight_manager.gd` debe avisar con `observe()` (acciones del jugador) y `notify_result()`.
7. **Arenas**: `ArenaBuilder.build(cfg_de_arenas_json, nodo_padre)`; edita `ARENAS/arenas.json` para crear recintos nuevos sin tocar código.
8. **Exportar a Android**: ver `ANDROID_OPTIMIZATION.md`.
