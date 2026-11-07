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
