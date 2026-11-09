# JARVIS BOXING AI V4 — estado y cómo continuar

## Hecho
- Animación por fotogramas reales de la lámina maestra: `boxeo/ART/anim/*.png` (+ `_m.png` máscaras de pantalón/guantes/piel) y `anim.json`. Generado con `boxeo/tools/extract_anim.py`.
- `SCRIPTS/stage2d.gd`: `_pick_clip()` elige clip y fotograma según el estado real del Boxer (IDLE→idle, MOVE→walk/run, ATTACK→jab/cross/hook_l/hook_r/upper con startup→impacto→recuperación, BLOCK, DODGE, HIT, STAGGER, DOWN/GETUP, WIN, LOSE). Shader recolorea pantalón y guantes por boxeador.
- Eliminados: guante/estela superpuesto (`_draw_punch` ya no se llama) y la estela fantasma. Los efectos solo salen en el impacto (`on_fx`, se autoeliminan).
- IA (`OPPONENTS/boxer_ai.gd`): pasos adelante/atrás en guardia (antes se quedaba clavada a distancia). Movimiento lateral (eje x) se ve como desplazamiento en profundidad.
- Pruebas con Godot real: ambos se mueven, todos los golpes usan su clip y vuelven a guardia, pelea contra IA termina sin errores.

## Pendiente (en orden)
1. Prueba de 3 rounds completos sin KO (script en /tmp/gt/t10.gd: jugador pasivo, skill 0.05, round_seconds 8) y revisar capturas.
2. Calidad de recortes: algunos fotogramas tienen manchas/pies cortados; walk (3), stagger (3) tienen pocos cuadros. Ajustar `extract_anim.py` (ROWS/SECT, umbral).
3. Tabla IMPACT en stage2d.gd (fotograma de impacto por clip) está estimada; afinar mirando cada clip.
4. Clips `counter` y `combo` extraídos pero sin usar (el Boxer no distingue contraataque). Conectar si se agrega flag.
5. 30 boxeadores: hoy se recolorean pantalón/guantes sobre los mismos cuerpos de la lámina; no son 30 hojas independientes. Requiere más arte.
6. Probar controles táctiles en dispositivo, otras resoluciones, y actualizar `SIN_LIMITE_Y_BUSQUEDA.md`.
7. Commit + push a `bloques-1-4` y verificar workflow "Compilar Jarvis Boxeo".

## Comando Termux
cd ~/JARVIS-FINAL && git fetch origin && git reset --hard origin/bloques-1-4 && git log --oneline -1
