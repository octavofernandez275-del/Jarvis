"""Escribe las guías de DOCUMENTATION/ (con tablas generadas desde los datos reales)."""
import os, json
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
D = os.path.join(ROOT, "DOCUMENTATION")
T = json.load(open(os.path.join(ROOT, "ANIMATIONS/animation_table.json"), encoding="utf-8"))

def w(name, text): open(os.path.join(D, name), "w", encoding="utf-8").write(text.strip() + "\n")

w("README.md", """
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
- PC: `J` jab, `K` cruzado, `L` gancho, `I` uppercut, `U` golpe al cuerpo, `Espacio` bloquear, `A`/`D` esquivar, `←/→` mover, `C` cambiar cámara.

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
""")

w("LIMITATIONS.md", """
# Limitaciones (lo que NO es este paquete)

Pediste un estudio AAA. Un asistente de texto con código no puede producir ese nivel de contenido artístico. Esto es lo que hay y lo que falta:

## Lo que no se pudo generar
- **Modelos de personajes realistas**: los boxeadores son maniquíes *low-poly* (~1 200 vértices, sin rostro modelado, sin texturas pintadas). Sirven para prototipar y para validar la jugabilidad, no para lanzar al público.
- **Rostros, expresiones faciales y *lip-sync***: el rig no tiene huesos ni *blendshapes* de cara.
- **Animación por captura de movimiento**: las 30 animaciones son poses clave escritas a mano (interpolación entre ellas). Son funcionales y legibles, pero no tienen la calidad de mocap. Hay deslizamiento de pies al cambiar el peso (no hay IK de pies).
- **Voces** de árbitros, entrenadores, presentador y comentaristas: no se incluyen. `AUDIO/announcer_lines.json` trae los textos listos para grabar con actores o TTS con licencia.
- **Música y efectos de estudio**: los sonidos son sintetizados (correctos para prototipo, no de calidad comercial).
- **Boxeadores reales** (Ali, Tyson, Durán, Leonard, Chávez, Monzón, Martínez, Pacquiao, Mayweather, Canelo, Usyk…): sus nombres, rostros, semejanza y voces requieren licencia. No se incluyen. El catálogo usa **12 ficticios originales** con estilos inspirados en arquetipos, no en personas concretas.
- **Texturas comprimidas**: no hay texturas (solo colores de material), por eso no hay nada que comprimir todavía. Cuando pintes texturas, usa ASTC/ETC2 (Godot lo hace al exportar).
- **Prueba en dispositivo**: no se midió rendimiento en un teléfono real; las cifras de memoria son estimaciones.

## Lo que está simplificado a propósito
- Combate en 1D (avanzar/retroceder en la línea entre los dos boxeadores; sin circular alrededor del rival).
- Un solo modelo base para todos los boxeadores (cambian altura, color y estadísticas). Zurdos = modelo espejado.
- Repetición ligera (reproduce posiciones y animaciones grabadas, no re-simula).
- Los túneles, vestuario y accesorios son cajas y cilindros.

## Cómo llegar al nivel profesional (alternativas compatibles)
1. **Personajes**: crea o compra modelos humanoides y retargetea las animaciones (ver `ANIMATION_GUIDE.md`). Mixamo (gratis, licencia de Adobe), Blender + Rigify, o Character Creator.
2. **Animación**: mocap propio o librerías con licencia; luego re-exporta con los mismos nombres de clip y la tabla seguirá funcionando.
3. **Voces**: actores o servicios de voz con licencia comercial.
4. **Boxeadores reales**: acuerdos con los deportistas o sus representantes; luego añádelos a `fighters.json`.
5. **Texturas**: pinta en Substance/Blender; usa 1024×1024 máximo por personaje en móvil.

## Riesgos técnicos conocidos
- Los scripts de Godot no se ejecutaron (ver `README.md`).
- El espejado de zurdos usa escala negativa en X; si ves caras interiores o luces raras, activa "Cull Mode: Disabled" en los materiales o duplica el modelo espejado en Blender.
- El nombre de los materiales importados (`skin`, `shorts`, `gloves`...) debe conservarse para recolorear; si Godot los renombra, ajusta `Data.apply_look`.
""")

rows = ["| Clip | Duración (s) | Bucle | Categoría | Descripción |", "|---|---|---|---|---|"]
for c in T["clips"]:
    rows.append(f"| `{c['name']}` | {c['duration']} | {'sí' if c['loop'] else 'no'} | {c['category']} | {c['note']} |")
arows = ["| Golpe | Mano | Blanco | Daño | Aguante | Arranque | Activo | Recuperación | Alcance del guante (m) | Rango de impacto (m) |", "|---|---|---|---|---|---|---|---|---|---|"]
for n, a in T["attacks"].items():
    arows.append(f"| `{n}` | {a['hand']} | {a['target']} | {a['damage']} | {a['stamina']} | {a['startup']} | {a['active']} | {a['recovery']} | {a['reach_m']} | {a['range']} |")
w("ANIMATION_GUIDE.md", f"""
# Guía de animaciones

Todas las animaciones son esqueléticas (rotaciones por hueso, interpolación lineal), embebidas en los `.glb` y reutilizables por todos los boxeadores. Sin imágenes estáticas.

## Esqueleto (17 huesos, mismos en boxeador y personal)
`Hips → Spine → Chest → Neck → Head`; `Chest → UpperArm_L/R → Forearm_L/R → Hand_L/R`; `Hips → Thigh_L/R → Shin_L/R → Foot_L/R`.
Convención: Y arriba, el personaje mira a +Z, su izquierda es +X, 1 unidad = 1 m, altura 1.82 m. Pose de reposo: de pie con brazos abajo.
Cada clip tiene pista de rotación para los 17 huesos y de traslación para `Hips` (así los clips no se "contaminan" al mezclarlos).

## Golpes (tiempos medidos; coinciden con la duración del clip)
{chr(10).join(arows)}

*Alcance del guante* = distancia máxima del centro del guante, medida sobre el modelo. *Rango de impacto* = alcance + guante + cara del rival + tolerancia; es lo que usa el juego.

## Todos los clips
{chr(10).join(rows)}

## Sustituir por animaciones o personajes propios
1. Mantén los **nombres de clip** de la tabla y la **tabla de golpes** (`animation_table.json`): el juego solo necesita nombre, duración y bucle.
2. Para un personaje nuevo (p. ej. de Mixamo) retargetea a este esqueleto o al revés. Equivalencias de huesos:

| Este proyecto | Mixamo | Unity Humanoid |
|---|---|---|
| Hips | mixamorig:Hips | Hips |
| Spine / Chest | mixamorig:Spine / Spine1 (Spine2) | Spine / Chest |
| Neck / Head | mixamorig:Neck / Head | Neck / Head |
| UpperArm_L/R | mixamorig:LeftArm / RightArm | LeftUpperArm / RightUpperArm |
| Forearm_L/R | mixamorig:LeftForeArm / RightForeArm | LeftLowerArm / RightLowerArm |
| Hand_L/R | mixamorig:LeftHand / RightHand | LeftHand / RightHand |
| Thigh_L/R | mixamorig:LeftUpLeg / RightUpLeg | LeftUpperLeg / RightUpperLeg |
| Shin_L/R | mixamorig:LeftLeg / RightLeg | LeftLowerLeg / RightLowerLeg |
| Foot_L/R | mixamorig:LeftFoot / RightFoot | LeftFoot / RightFoot |

3. En Godot usa un `BoneMap` + `SkeletonProfileHumanoid` en la importación (Retarget) para reutilizar animaciones entre rigs.
4. Para añadir un golpe nuevo: agrégalo en `ATTACKS` y `ATTACK_POSES` de `tools/clips.py` y ejecuta `python3 tools/build_all.py`.
""")

w("INTEGRATION_GODOT.md", """
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
""")

w("INTEGRATION_UNITY.md", """
# Integración en Unity (2022 LTS o superior)

Los `.glb` son glTF 2.0 estándar. Unity no los importa de serie:

1. Instala **glTFast** (`com.unity.cloud.gltfast`, Package Manager → Add by name). Arrastra los `.glb` a `Assets/`.
2. **Humanoid**: en el importador del modelo del boxeador usa *Rig → Animation Type: Humanoid* y revisa el mapa de huesos (tabla en `ANIMATION_GUIDE.md`; faltan dedos y hombros, Unity lo acepta). Si glTFast no genera el Avatar, crea un prefab con *Generic* y reproduce los clips con `Animation`/Playables.
3. **Animaciones**: los clips vienen embebidos con el nombre de `animation_table.json`. Marca *Loop Time* en los de la tabla con `loop: true`.
4. **Datos**: los `.json` se leen con `JsonUtility` o Newtonsoft (`com.unity.nuget.newtonsoft-json`).
5. **IA**: `OPPONENTS/boxer_ai.gd` es lógica pura (sin dependencias de motor salvo posición y estado del boxeador). Se porta casi línea por línea a C#: mismas funciones `observe`, `predict`, `think`, `notify_result`. La tabla de golpes (`animation_table.json`) y los perfiles (`ai_profiles.json`) se reutilizan tal cual.
6. **Público**: usa `GPU instancing`/`Graphics.DrawMeshInstanced` con los 3 LOD de `ENVIRONMENTS/` y un vertex shader de rebote (ver `VFX/crowd_cheer.gdshader` como referencia).
7. **Materiales**: son colores PBR sin texturas; asigna tus Shader Graph/URP Lit y sobreescribe el color por nombre de material (`skin`, `shorts`, `gloves`, `shoes`, `hair`).
""")

w("AI_DESIGN.md", """
# Diseño de la IA de los rivales (`OPPONENTS/boxer_ai.gd`)

## Personalidades (`OPPONENTS/ai_profiles.json`)
Agresivo, Técnico, Defensivo, Contraatacador, Veloz, Resistente, Estratega y Especialista en presión. Cada perfil define: agresividad, ritmo de decisión (`tempo`), reacción y tiempo de reacción, tendencia a mantener la guardia, preferencia bloqueo/esquiva, probabilidad de contraataque, presión (avanzar), distancia preferida, velocidad, capacidad de aprendizaje, umbral de descanso y su lista de combinaciones con pesos.

## Qué hace la IA
| Capacidad | Cómo |
|---|---|
| **Reconocer ataques** | Detecta el golpe del jugador en su fase de arranque (`startup`) si está al alcance. |
| **Defender / esquivar** | Una decisión por golpe: probabilidad según reacción, dificultad y aprendizaje; elige bloqueo o esquiva según su perfil. Solo reacciona si su tiempo de reacción cabe antes del impacto. |
| **Contraatacar** | Durante la recuperación del golpe del jugador (o tras defender), con la probabilidad `counter` del perfil. |
| **Combinar golpes** | Elige combinaciones del perfil ponderadas por pesos aprendidos; encadena en la última parte de la recuperación. |
| **Aprender patrones** | Cadenas de Markov de contexto 1 y 2 acciones del jugador (`jab>cross` → siguiente). Si la confianza ≥ 40 %, **anticipa**: reacciona 2.5× más rápido y con más probabilidad durante 1.2 s. |
| **Adaptarse** | Si el jugador se tapa >40 % → más golpes al cuerpo y uppercuts; si esquiva >30 % → más ganchos y directos. Cada golpe sube o baja su peso según conecte, sea bloqueado o esquivado. Si un golpe del jugador le conecta, lo respeta más. |
| **Estrategia** | Va perdiendo en las tarjetas → arriesga más (más en rounds finales); va ganando → se cuida. Si su aguante baja del umbral, retrocede y descansa. |

`get_insight()` devuelve un texto que el HUD muestra ("IA espera: jab>cross (62%)").

## Dificultad
`skill` 0.3 / 0.55 / 0.8 / 1.0 escala reacción, ritmo y velocidad de aprendizaje.

## Comprobación
`tools/sim_check.py` replica estas reglas y simula combates de 180 s contra un jugador artificial de patrón fijo (jab-cruzado con algo de azar). Resultado: tras ajustar el tiempo de reacción y el costo de bloqueo, en dificultad alta la IA gana al bot con los perfiles técnico, defensivo y presión, queda pareja con el agresivo y pierde con el veloz y el contraatacador (poca pegada o muy pasivos frente a un bot que solo lanza golpes); en dificultad baja pierde con la mayoría. Es una comprobación de equilibrio frente a un bot simple, **no** una medida de cómo jugará contra una persona: ajusta `ai_profiles.json` jugando.
""")

w("ANDROID_OPTIMIZATION.md", """
# Optimización para Android (objetivo: 4 GB de RAM)

## Presupuesto real de los recursos (medido en los archivos)
| Elemento | Dato |
|---|---|
| Boxeador | 1 198 vértices / 1 512 triángulos, 17 huesos, 23 clips en 276 KB |
| Personal (árbitro/entrenador) | 1 018 vértices / 1 304 triángulos, 7 clips en 126 KB |
| Ring | 768 vértices / 612 triángulos |
| Público | 3 LOD: 126 / 48 / 24 vértices por espectador; **1 llamada de dibujo por LOD** (MultiMesh) |
| Escena de estadio completa (estimación) | ~40 000 vértices de público + ~5 000 del resto |
| Peso total del proyecto | ~3.5 MB (sin texturas) |

## Técnicas aplicadas
- **LOD**: 3 niveles de espectadores según la fila. El boxeador es único (solo hay 2-3 personajes visibles).
- **Animaciones reutilizables**: un solo rig y un solo juego de clips para todos los boxeadores; sin duplicar modelos (los colores se cambian con materiales).
- **Texturas**: ninguna (colores PBR). Cuando añadas texturas: ASTC 6×6 o ETC2, máx. 1024² por personaje, atlas para el ring.
- **Efectos ligeros**: `CPUParticles3D` reutilizados (pool de 10), sin sombras de partículas, sin postproceso.
- **Carga progresiva**: la arena se construye por código al elegir el combate; el menú no carga nada de 3D. Si el arranque se nota, mueve `ArenaBuilder.build` a un hilo con `ResourceLoader.load_threaded_request`.
- **Iluminación**: renderizador **Mobile**, MSAA desactivado, ≤ 8 luces, sin sombras salvo que actives una `DirectionalLight3D`.
- **Audio**: WAV mono 22.05 kHz (3.5 MB en total). Convierte a OGG para publicar si necesitas bajar más.

## Exportar
1. Instala las plantillas de exportación y el SDK de Android (Godot → Editor → Administrar plantillas).
2. `Proyecto → Exportar → Android`; habilita *Use Gradle Build* si usas plugins; arquitectura `arm64-v8a`.
3. `project.godot` ya fuerza orientación horizontal, `canvas_items` + `expand` y emulación de toque desde el ratón para probar en PC.
4. Mide en el teléfono (Godot → Debugger → Monitor) y ajusta `spot_count` y `tiers` en `ARENAS/arenas.json` si baja de 30 fps.

## Estimación de memoria (NO medida)
Motor Godot Mobile ≈ 150-250 MB + geometría y audio de este proyecto ≪ 50 MB. Deja margen para texturas y música final.
""")

w("LICENSES.md", """
# Licencias y derechos

- Todos los modelos, animaciones, sonidos, logos, datos y código de esta carpeta fueron **generados por código para este proyecto**; puedes usarlos y modificarlos libremente, incluso comercialmente.
- Los nombres de los 12 boxeadores, entrenadores, árbitros, patrocinadores, ligas y cinturones son **ficticios**. Cualquier parecido con personas o marcas reales es casual.
- **No incluye** nombres, rostros ni voces de boxeadores reales. Para usarlos necesitas licencia o autorización de cada persona/organización.
- Fuentes recomendadas con licencia clara para sustituir contenido: Mixamo (animaciones/personajes, términos de Adobe), Quaternius y Kenney (CC0), Freesound/OpenGameArt (revisar cada licencia), Blender (GPL, los modelos que hagas son tuyos).
""")
