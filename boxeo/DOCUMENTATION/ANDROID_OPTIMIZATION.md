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
