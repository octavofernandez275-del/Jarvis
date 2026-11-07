# Manifiesto de recursos — JARVIS BOXING AI

Generado automáticamente (tools/build_docs.py) a partir de los archivos reales. Tamaños en KB.

**Total: 69 archivos, 2.9 MB.**

## ANIMATIONS/

| Archivo | KB | Formato / especificaciones | Uso | Integración |
|---|---|---|---|---|
| `ANIMATIONS/animation_table.json` | 7.4 | JSON; claves: note, model_height_m, stance_note, attacks, clips, defense | Tabla de golpes (tiempos, daño, alcance medido del modelo), defensa, movimiento, knockdown y lista de clips. | Fuente única para boxer.gd y fight_manager.gd. |

## ARENAS/

| Archivo | KB | Formato / especificaciones | Uso | Integración |
|---|---|---|---|---|
| `ARENAS/arenas.json` | 6.3 | JSON; claves: note, camera_presets, arenas | 7 recintos: gimnasio de barrio, gimnasio pro, arena de ciudad, arena internacional, estadio, vestuario, túnel. Iluminación, materiales, público, cámaras y ambiente propios. | arena_builder.gd.build(cfg, parent). |
| `ARENAS/bleacher_tier.glb` | 9.3 | glTF 2.0 binario; 288 vértices, 144 triángulos; materiales: concrete, seat; sin texturas (colores PBR); validado: sí | Grada escalonada de 10 m (disponible para diseñar arenas propias). | Instanciar y repetir; el público actual se genera por código en crowd.gd. |
| `ARENAS/light_truss.glb` | 14.8 | glTF 2.0 binario; 480 vértices, 320 triángulos; materiales: truss, lamp; sin texturas (colores PBR); validado: sí | Estructura de focos sobre el ring (luces emisivas). | arena_builder.gd (props.truss); las luces reales son SpotLight3D creadas por código. |
| `ARENAS/scoreboard_cube.glb` | 7.6 | glTF 2.0 binario; 192 vértices, 124 triángulos; materiales: frame, screen, cable; sin texturas (colores PBR); validado: sí | Marcador colgante de 4 pantallas (material emisivo). | arena_builder.gd (props.scoreboard). |

## AUDIO/

| Archivo | KB | Formato / especificaciones | Uso | Integración |
|---|---|---|---|---|
| `AUDIO/announcer_lines.json` | 1.6 | JSON; claves: note, ring_announcer, referee, commentators | Textos para locutor, árbitro y comentaristas (SIN voces). | Grabar con voces autorizadas o usar un TTS con licencia. |
| `AUDIO/bell_ding.wav` | 77.6 | WAV PCM 16 bit mono 22050 Hz, 1.8 s | Efecto/ambiente/música sintetizado por código (sin derechos de autor). | SCRIPTS/sfx.gd lo carga por nombre; para ambientes en bucle sfx.ambience(ruta). |
| `AUDIO/bell_round_end.wav` | 94.8 | WAV PCM 16 bit mono 22050 Hz, 2.2 s | Efecto/ambiente/música sintetizado por código (sin derechos de autor). | SCRIPTS/sfx.gd lo carga por nombre; para ambientes en bucle sfx.ambience(ruta). |
| `AUDIO/bell_round_start.wav` | 68.9 | WAV PCM 16 bit mono 22050 Hz, 1.6 s | Efecto/ambiente/música sintetizado por código (sin derechos de autor). | SCRIPTS/sfx.gd lo carga por nombre; para ambientes en bucle sfx.ambience(ruta). |
| `AUDIO/block_hit.wav` | 6.9 | WAV PCM 16 bit mono 22050 Hz, 0.2 s | Efecto/ambiente/música sintetizado por código (sin derechos de autor). | SCRIPTS/sfx.gd lo carga por nombre; para ambientes en bucle sfx.ambience(ruta). |
| `AUDIO/count_tick.wav` | 5.2 | WAV PCM 16 bit mono 22050 Hz, 0.1 s | Efecto/ambiente/música sintetizado por código (sin derechos de autor). | SCRIPTS/sfx.gd lo carga por nombre; para ambientes en bucle sfx.ambience(ruta). |
| `AUDIO/crowd_cheer.wav` | 137.9 | WAV PCM 16 bit mono 22050 Hz, 3.2 s | Efecto/ambiente/música sintetizado por código (sin derechos de autor). | SCRIPTS/sfx.gd lo carga por nombre; para ambientes en bucle sfx.ambience(ruta). |
| `AUDIO/crowd_loop.wav` | 370.4 | WAV PCM 16 bit mono 22050 Hz, 8.6 s | Efecto/ambiente/música sintetizado por código (sin derechos de autor). | SCRIPTS/sfx.gd lo carga por nombre; para ambientes en bucle sfx.ambience(ruta). |
| `AUDIO/gym_ambience_loop.wav` | 327.3 | WAV PCM 16 bit mono 22050 Hz, 7.6 s | Efecto/ambiente/música sintetizado por código (sin derechos de autor). | SCRIPTS/sfx.gd lo carga por nombre; para ambientes en bucle sfx.ambience(ruta). |
| `AUDIO/knockdown_thud.wav` | 38.8 | WAV PCM 16 bit mono 22050 Hz, 0.9 s | Efecto/ambiente/música sintetizado por código (sin derechos de autor). | SCRIPTS/sfx.gd lo carga por nombre; para ambientes en bucle sfx.ambience(ruta). |
| `AUDIO/menu_theme_loop.wav` | 826.9 | WAV PCM 16 bit mono 22050 Hz, 19.2 s | Efecto/ambiente/música sintetizado por código (sin derechos de autor). | SCRIPTS/sfx.gd lo carga por nombre; para ambientes en bucle sfx.ambience(ruta). |
| `AUDIO/punch_body.wav` | 13.0 | WAV PCM 16 bit mono 22050 Hz, 0.3 s | Efecto/ambiente/música sintetizado por código (sin derechos de autor). | SCRIPTS/sfx.gd lo carga por nombre; para ambientes en bucle sfx.ambience(ruta). |
| `AUDIO/punch_heavy.wav` | 17.3 | WAV PCM 16 bit mono 22050 Hz, 0.4 s | Efecto/ambiente/música sintetizado por código (sin derechos de autor). | SCRIPTS/sfx.gd lo carga por nombre; para ambientes en bucle sfx.ambience(ruta). |
| `AUDIO/punch_light.wav` | 9.5 | WAV PCM 16 bit mono 22050 Hz, 0.2 s | Efecto/ambiente/música sintetizado por código (sin derechos de autor). | SCRIPTS/sfx.gd lo carga por nombre; para ambientes en bucle sfx.ambience(ruta). |
| `AUDIO/stinger_lose.wav` | 103.4 | WAV PCM 16 bit mono 22050 Hz, 2.4 s | Efecto/ambiente/música sintetizado por código (sin derechos de autor). | SCRIPTS/sfx.gd lo carga por nombre; para ambientes en bucle sfx.ambience(ruta). |
| `AUDIO/stinger_win.wav` | 81.9 | WAV PCM 16 bit mono 22050 Hz, 1.9 s | Efecto/ambiente/música sintetizado por código (sin derechos de autor). | SCRIPTS/sfx.gd lo carga por nombre; para ambientes en bucle sfx.ambience(ruta). |
| `AUDIO/ui_click.wav` | 2.2 | WAV PCM 16 bit mono 22050 Hz, 0.0 s | Efecto/ambiente/música sintetizado por código (sin derechos de autor). | SCRIPTS/sfx.gd lo carga por nombre; para ambientes en bucle sfx.ambience(ruta). |
| `AUDIO/ui_confirm.wav` | 13.0 | WAV PCM 16 bit mono 22050 Hz, 0.3 s | Efecto/ambiente/música sintetizado por código (sin derechos de autor). | SCRIPTS/sfx.gd lo carga por nombre; para ambientes en bucle sfx.ambience(ruta). |
| `AUDIO/whoosh.wav` | 12.1 | WAV PCM 16 bit mono 22050 Hz, 0.3 s | Efecto/ambiente/música sintetizado por código (sin derechos de autor). | SCRIPTS/sfx.gd lo carga por nombre; para ambientes en bucle sfx.ambience(ruta). |

## CAREER_MODE/

| Archivo | KB | Formato / especificaciones | Uso | Integración |
|---|---|---|---|---|
| `CAREER_MODE/career.json` | 4.4 | JSON; claves: note, start, week, weight_classes, rank_tiers, gyms | Modo carrera: semanas, entrenamientos, equipamiento, gimnasios, patrocinadores ficticios, contratos, ranking, trofeos. | SCRIPTS/career.gd. |

## CHAMPIONSHIPS/

| Archivo | KB | Formato / especificaciones | Uso | Integración |
|---|---|---|---|---|
| `CHAMPIONSHIPS/championships.json` | 2.6 | JSON; claves: note, organizations, belts, tournaments, ceremony_sequence, scoring | Ligas y cinturones ficticios, torneos, secuencia de ceremonia y sistema de puntuación. | Datos para ampliar el modo carrera; la puntuación 10-point-must ya está en fight_manager.gd. |

## CHARACTERS/

| Archivo | KB | Formato / especificaciones | Uso | Integración |
|---|---|---|---|---|
| `CHARACTERS/boxer_base.glb` | 276.5 | glTF 2.0 binario; 1198 vértices, 1512 triángulos; rig: 17 huesos (humanoide), skin rígido; 23 animaciones embebidas; materiales: skin, shorts, gloves, shoes, hair; sin texturas (colores PBR); validado: sí | Boxeador jugable (todos los luchadores comparten este modelo y rig; cambian color, altura y estadísticas). | Instanciar como PackedScene; el importador de Godot crea Skeleton3D + AnimationPlayer. Colores por nombre de material (Data.apply_look). Ver SCRIPTS/boxer.gd. |
| `CHARACTERS/fighters.json` | 8.3 | JSON; claves: note, fighters | 12 boxeadores FICTICIOS: altura, peso, alcance, guardia, estadísticas, estilo, colores, récord. | Data.load_json('res://CHARACTERS/fighters.json')['fighters'] |
| `CHARACTERS/staff_base.glb` | 126.0 | glTF 2.0 binario; 1018 vértices, 1304 triángulos; rig: 17 huesos (humanoide), skin rígido; 7 animaciones embebidas; materiales: skin, shirt, pants, shoes, hair; sin texturas (colores PBR); validado: sí | Árbitros, entrenadores, presentador y comentaristas (mismo esqueleto, ropa en vez de guantes). | Igual que el boxeador; clips de categoría 'staff'. Ver SCRIPTS/staff.gd. |

## COACHES/

| Archivo | KB | Formato / especificaciones | Uso | Integración |
|---|---|---|---|---|
| `COACHES/coaches.json` | 1.1 | JSON; claves: note, model, animations, coaches | 3 entrenadores (modelo staff_base.glb, colores propios, voz pendiente). | staff.gd.setup(model, look, table). |
| `COACHES/commentators.json` | 0.8 | JSON; claves: note, model, people | Presentador y 2 comentaristas (avatar de cabina + voz pendiente). | staff.gd. |

## ENVIRONMENTS/

| Archivo | KB | Formato / especificaciones | Uso | Integración |
|---|---|---|---|---|
| `ENVIRONMENTS/crowd_spectator_lod0.glb` | 4.8 | glTF 2.0 binario; 126 vértices, 152 triángulos; materiales: cloth; sin texturas (colores PBR); validado: sí | Espectador LOD0 con brazos arriba (primeras 2 filas). | crowd.gd lo usa en MultiMesh con color por instancia y shader VFX/crowd_cheer.gdshader. |
| `ENVIRONMENTS/crowd_spectator_lod1.glb` | 2.3 | glTF 2.0 binario; 48 vértices, 24 triángulos; materiales: cloth; sin texturas (colores PBR); validado: sí | Espectador LOD1 (filas 3-5). | Ídem. |
| `ENVIRONMENTS/crowd_spectator_lod2.glb` | 1.6 | glTF 2.0 binario; 24 vértices, 12 triángulos; materiales: cloth; sin texturas (colores PBR); validado: sí | Espectador LOD2 (filas altas), 1 caja. | Ídem. |
| `ENVIRONMENTS/entry_tunnel.glb` | 9.9 | glTF 2.0 binario; 288 vértices, 144 triángulos; materiales: wall, floor, strip; sin texturas (colores PBR); validado: sí | Túnel de entrada con tiras de luz. | arena_builder.gd lo coloca en arenas/estadio; main.gd hace entrar a los boxeadores desde ahí. |
| `ENVIRONMENTS/locker_bank.glb` | 10.5 | glTF 2.0 binario; 312 vértices, 156 triángulos; materiales: locker, door, bench; sin texturas (colores PBR); validado: sí | Fila de 6 casilleros y banco (vestuario). | arena_builder.gd (props.lockers). |

## GYMS/

| Archivo | KB | Formato / especificaciones | Uso | Integración |
|---|---|---|---|---|
| `GYMS/barbell.glb` | 12.8 | glTF 2.0 binario; 358 vértices, 344 triángulos; materiales: steel, plate, plate_red; sin texturas (colores PBR); validado: sí | Barra con discos (pesas). | arena_builder.gd (props.barbells). |
| `GYMS/heavy_bag.glb` | 6.5 | glTF 2.0 binario; 144 vértices, 136 triángulos; materiales: leather, chain, strap; sin texturas (colores PBR); validado: sí | Saco pesado (entrenamiento del modo carrera). | Colocar con arena_builder.gd (props.heavy_bags). |
| `GYMS/speed_bag.glb` | 6.8 | glTF 2.0 binario; 146 vértices, 180 triángulos; materiales: wood, leather, steel; sin texturas (colores PBR); validado: sí | Pera loca con soporte. | arena_builder.gd (props.speed_bags). |
| `GYMS/weight_bench.glb` | 5.9 | glTF 2.0 binario; 152 vértices, 120 triángulos; materiales: pad, steel; sin texturas (colores PBR); validado: sí | Banco de pesas. | arena_builder.gd (props.benches). |

## LOGOS/

| Archivo | KB | Formato / especificaciones | Uso | Integración |
|---|---|---|---|---|
| `LOGOS/app_icon.svg` | 5.1 | SVG vectorial (sin elementos de texto) | Icono de la app (vectorial). | Origen del PNG. |
| `LOGOS/app_icon_512.png` | 3.9 | PNG 512x512 | Icono 512x512 para Android. | project.godot -> config/icon; en exportación usar como icono principal. |
| `LOGOS/jarvis_boxing_ai_logo.png` | 7.1 | PNG 1600x560 | Logo raster 1600x560 con transparencia. | TextureRect. |
| `LOGOS/jarvis_boxing_ai_logo.svg` | 15.8 | SVG vectorial (sin elementos de texto) | Logo vectorial sin texto (letras como rectángulos). | Importar como textura (Godot rasteriza SVG); escala de importación 1.0-2.0. |

## OPPONENTS/

| Archivo | KB | Formato / especificaciones | Uso | Integración |
|---|---|---|---|---|
| `OPPONENTS/ai_profiles.json` | 5.9 | JSON; claves: note, difficulty, profiles | 8 personalidades de IA + 4 dificultades. | boxer_ai.gd.setup(me, foe, profile, skill). |
| `OPPONENTS/boxer_ai.gd` | 10.7 | GDScript (Godot 4), 323 líneas | IA: reconoce ataques, defiende, esquiva, contraataca, combina y aprende patrones del jugador. | boxer.ai = ai; fight_manager avisa con observe()/notify_*(). |

## REFEREES/

| Archivo | KB | Formato / especificaciones | Uso | Integración |
|---|---|---|---|---|
| `REFEREES/referees.json` | 0.6 | JSON; claves: note, model, animations, referees | 2 árbitros con animaciones de cuenta, corte y brazo del ganador. | main.gd elige uno al azar. |

## RINGS/

| Archivo | KB | Formato / especificaciones | Uso | Integración |
|---|---|---|---|---|
| `RINGS/ring_pro.glb` | 28.8 | glTF 2.0 binario; 768 vértices, 612 triángulos; materiales: canvas, apron, post, rope_red, rope_white, rope_blue, pad_red, pad_blue, pad_white, stool; sin texturas (colores PBR); validado: sí | Ring profesional: lona 7.1 m, cuerdas de 4 alturas, postes, esquineros rojo/azul/blanco, escalones y 2 taburetes. Lona en y=0. | Se instancia en el origen; los boxeadores pelean en z=±1 dentro de ±2.8 (ring_half). |

## SCRIPTS/

| Archivo | KB | Formato / especificaciones | Uso | Integración |
|---|---|---|---|---|
| `SCRIPTS/arena_builder.gd` | 4.5 | GDScript (Godot 4), 116 líneas | Código del juego. | Se carga desde SCRIPTS/main.gd. |
| `SCRIPTS/boxer.gd` | 9.0 | GDScript (Godot 4), 315 líneas | Código del juego. | Se carga desde SCRIPTS/main.gd. |
| `SCRIPTS/career.gd` | 7.6 | GDScript (Godot 4), 212 líneas | Código del juego. | Se carga desde SCRIPTS/main.gd. |
| `SCRIPTS/crowd.gd` | 2.4 | GDScript (Godot 4), 62 líneas | Código del juego. | Se carga desde SCRIPTS/main.gd. |
| `SCRIPTS/data.gd` | 2.1 | GDScript (Godot 4), 58 líneas | Código del juego. | Se carga desde SCRIPTS/main.gd. |
| `SCRIPTS/fight_manager.gd` | 10.0 | GDScript (Godot 4), 346 líneas | Código del juego. | Se carga desde SCRIPTS/main.gd. |
| `SCRIPTS/hud.gd` | 6.4 | GDScript (Godot 4), 207 líneas | Código del juego. | Se carga desde SCRIPTS/main.gd. |
| `SCRIPTS/main.gd` | 11.3 | GDScript (Godot 4), 388 líneas | Código del juego. | Se carga desde SCRIPTS/main.gd. |
| `SCRIPTS/menu.gd` | 9.6 | GDScript (Godot 4), 249 líneas | Código del juego. | Se carga desde SCRIPTS/main.gd. |
| `SCRIPTS/replay.gd` | 2.4 | GDScript (Godot 4), 93 líneas | Código del juego. | Se carga desde SCRIPTS/main.gd. |
| `SCRIPTS/sfx.gd` | 1.3 | GDScript (Godot 4), 55 líneas | Código del juego. | Se carga desde SCRIPTS/main.gd. |
| `SCRIPTS/staff.gd` | 0.7 | GDScript (Godot 4), 25 líneas | Código del juego. | Se carga desde SCRIPTS/main.gd. |
| `SCRIPTS/touch_controls.gd` | 2.3 | GDScript (Godot 4), 72 líneas | Código del juego. | Se carga desde SCRIPTS/main.gd. |
| `SCRIPTS/vfx.gd` | 1.4 | GDScript (Godot 4), 51 líneas | Código del juego. | Se carga desde SCRIPTS/main.gd. |

## UI/

| Archivo | KB | Formato / especificaciones | Uso | Integración |
|---|---|---|---|---|
| `UI/mockup_combat_hud.png` | 11.6 | PNG 1280x720 |  |  |
| `UI/mockup_fighter_select.png` | 12.2 | PNG 1280x720 |  |  |
| `UI/mockup_main_menu.png` | 11.0 | PNG 1280x720 |  |  |
| `UI/mockup_results.png` | 7.8 | PNG 1280x720 |  |  |
| `UI/ui_layout.json` | 1.8 | JSON; claves: note, touch_buttons, keyboard, difficulty_options, screens | Posición de los botones táctiles, teclas de PC, opciones de dificultad y lista de pantallas. | touch_controls.gd. |

## VFX/

| Archivo | KB | Formato / especificaciones | Uso | Integración |
|---|---|---|---|---|
| `VFX/crowd_cheer.gdshader` | 0.6 | Shader de Godot 4 (spatial) | Shader del público: salto con fase distinta por instancia. | ShaderMaterial en el MultiMesh (crowd.gd). |
| `VFX/vfx_presets.json` | 1.6 | JSON; claves: note, presets, camera_shake, slow_motion, replay | Partículas de impacto, sudor, bloqueo, polvo, confeti; temblor de cámara, cámara lenta y parámetros de repetición. | SCRIPTS/vfx.gd y main.gd. |
