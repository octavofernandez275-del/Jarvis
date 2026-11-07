# JARVIS BOXING AI — paquete de recursos y prototipo jugable (Godot 4)

Esta carpeta ES un proyecto de Godot 4.3+ (renderizador Mobile). Ábrela con Godot (`Importar` → `project.godot`) y ejecuta.

## Qué contiene (todo generado por código, sin derechos de autor)
- **2 modelos con esqueleto y animaciones** (`CHARACTERS/boxer_base.glb`: 23 clips, `staff_base.glb`: 7 clips). 17 huesos, ~1 200 vértices.
- **Ring profesional, accesorios de gimnasio, público 3 LOD, túnel, vestuario, marcador y focos** (`.glb`).
- **12 boxeadores ficticios** con estadísticas, estilo, guardia y colores (`fighters.json`).
- **IA con 8 personalidades** que aprende patrones del jugador (`OPPONENTS/boxer_ai.gd`).
- **Combate completo**: golpes, bloqueo, esquiva, contraataque, knockdowns con cuenta, rounds, puntuación de 3 jueces, TKO/KO, repetición.
- **7 recintos** configurables por datos, **modo carrera** (entrenamiento, dinero, ranking, patrocinadores ficticios, guardado), **18 sonidos** sintetizados, **logo e icono**, HUD y controles táctiles.

## Controles
- Táctil: botones en pantalla (multitoque). Durante la cuenta de un knockdown, machaca cualquier botón para levantarte.
- Solo táctil: no hay controles de teclado ni ratón. Botón `CAM` para cambiar de cámara.

## Estado de verificación (sé honesto con tu equipo)
| Qué | Cómo se comprobó |
|---|---|
| Los 15 `.glb` | Validados con un lector independiente (estructura, índices, pesos, cuaterniones) y dibujados con *skinning* por software en varias poses. |
| Poses y alcance de golpes | Cinemática directa del esqueleto: ningún pie bajo el suelo; el alcance de cada golpe se mide del modelo y se escribe en `animation_table.json`. |
| Lógica de combate e IA | Réplica en Python (`tools/sim_check.py`) con cientos de combates simulados; se ajustaron reacción de la IA y costo de bloqueo. |
| **Scripts de Godot** | **NO se ejecutaron**: no había Godot en el entorno de creación. Revisados por lectura y balance de sintaxis. Espera corregir algún error menor al abrirlos. |

Lee `LIMITATIONS.md` antes de planear tu producción.

## Estructura
`CHARACTERS/ OPPONENTS/ ANIMATIONS/ ARENAS/ GYMS/ ENVIRONMENTS/ RINGS/ COACHES/ REFEREES/ UI/ LOGOS/ VFX/ AUDIO/ CAREER_MODE/ CHAMPIONSHIPS/ DOCUMENTATION/` más `SCRIPTS/` (código), `tools/` (generadores en Python; Godot los ignora) y `project.godot`, `main.tscn`.
Cada archivo está descrito en `ASSET_MANIFEST.md`.

## Regenerar los recursos
`pip install numpy scipy pillow matplotlib` y luego `python3 tools/build_all.py`. Para cambiar un golpe o una animación edita `tools/clips.py` (una sola fuente de verdad: los tiempos de la tabla y de los clips salen del mismo sitio).
