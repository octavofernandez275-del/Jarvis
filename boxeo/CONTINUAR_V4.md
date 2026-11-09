# JARVIS BOXING AI V4 — estado y cómo continuar

## Hecho
- Animación por fotogramas reales de la lámina maestra: `boxeo/ART/anim/*.png` (+ `_m.png` máscaras de pantalón/guantes/piel) y `anim.json`. Generado con `boxeo/tools/extract_anim.py`.
- `SCRIPTS/stage2d.gd`: `_pick_clip()` elige clip y fotograma según el estado real del Boxer (IDLE→idle, MOVE→walk/run, ATTACK→jab/cross/hook_l/hook_r/upper con startup→impacto→recuperación, BLOCK, DODGE, HIT, STAGGER, DOWN/GETUP, WIN, LOSE). Shader recolorea pantalón y guantes por boxeador.
- Eliminados: guante/estela superpuesto (`_draw_punch` ya no se llama) y la estela fantasma. Los efectos solo salen en el impacto (`on_fx`, se autoeliminan).
- IA (`OPPONENTS/boxer_ai.gd`): pasos adelante/atrás en guardia (antes se quedaba clavada a distancia). Movimiento lateral (eje x) se ve como desplazamiento en profundidad.
- Pruebas con Godot real: ambos se mueven, todos los golpes usan su clip y vuelven a guardia, pelea contra IA termina sin errores.

## V4b (esta sesión, sin Godot disponible: NADA de esto está probado en motor)
- Descubierto: en la lámina maestra (`1002118888.png`) cada fotograma de las filas de animación es un boxeador DISTINTO. Un clip mezcla cuerpos, no es una animación coherente.
- Los 32 boxeadores completos de las 2 filas superiores se extrajeron a `ART/fighters/fNN.png` y ahora SON los sprites del roster: `ART/sprites/s00..s31.png` (antes 26 sprites de otra lámina, más borrosos; s24 estaba repetido 8 veces).
- `ART/sprites.json` y `CHARACTERS/fighters.json` (look.shorts / look.gloves) recalculados desde los sprites nuevos; sprites repetidos reasignados (queda s24 x2).
- `stage2d.gd`: `_static_body()` -> en IDLE, MOVE y BLOCK se dibuja el sprite propio del boxeador con la animación procedural (balanceo, rebote, inclinación). Los clips de la lámina solo se usan en ATTACK, HIT, STAGGER, DOWN/GETUP, DODGE, WIN y LOSE.
- Clips: cross reordenado a [4,3,2,0,2,3] (antes empezaba con el brazo extendido y terminaba en un recorte roto); IMPACT cross = 3. Se probó filtrar restos del suelo, sin cambio visible (están pegados a los pies).
- Se borraron los `.import` de `ART/sprites/` (Godot los regenera al abrir/exportar).

## Pendiente (en orden)
1. ABRIR en Godot y verificar que carga (no se ejecutó nada). Revisar: idle/move/block con sprite propio, salto de cuerpo al pasar a golpe, tamaño relativo sprite vs clip (ambos H/fig_h).
2. Prueba de 3 rounds sin KO (script en /tmp/gt/t10.gd, no incluido en el zip): jugador pasivo, skill 0.05, round_seconds 8.
3. Calidad de golpes: siguen mezclando cuerpos y con restos en los pies (jab2, run4-5, hook_l2-3, hook_r3). Solución real: hoja de animación de UN cuerpo por clip (nueva arte); luego recolorear con el shader.
4. IMPACT de hook_l/hook_r/upper (2) sigue estimado; confirmar mirando en motor.
5. Clips `counter` y `combo` sin usar (combo tiene dos cuerpos superpuestos).
6. 30 boxeadores: ya hay 32 cuerpos distintos en guardia; los golpes siguen compartidos y recoloreados.
7. Probar controles táctiles, otras resoluciones y actualizar `SIN_LIMITE_Y_BUSQUEDA.md`.
8. Commit + push a `bloques-1-4` y verificar workflow "Compilar Jarvis Boxeo".

## Comando Termux
cd ~/JARVIS-FINAL && git fetch origin && git reset --hard origin/bloques-1-4 && git log --oneline -1
