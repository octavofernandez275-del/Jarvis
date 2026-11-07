# JARVIS BOXING AI — Next Level V6

## Cambios
- El boxeador del jugador activa `step_left` / `step_right` al desplazarse lateralmente sin avanzar.
- Durante el bloqueo, el paso lateral también usa la animación lateral con velocidad reducida.
- La IA aplica footwork orbital con cambios de dirección irregulares, corrección hacia el centro del ring y reducción de desplazamiento cuando está cansada o demasiado cerca.
- Los ocho perfiles conservan su personalidad y reciben un parámetro `orbit` distinto.

## Conservación
No se agregaron boxeadores ni se reemplazaron escenas, modelos, voces, golpes, tablas de daño o sistemas de puntuación.

## Verificación
- JSON de perfiles validado.
- Se verificó que los clips `step_left` y `step_right` estén declarados en `ANIMATIONS/animation_table.json`.
- No se ejecutó el proyecto en Godot en este entorno; es necesaria una prueba en el teléfono antes de considerar la compilación confirmada.
