JARVIS BOXING AI V4 — MEJORAS
================================

Archivos incluidos:
- boxeo/OPPONENTS/ai_profiles.json: 56 combinaciones distribuidas en 8 perfiles,
  usando únicamente los golpes que ya existen en animation_table.json.
- boxeo/SCRIPTS/main.gd: secuencia de cámara cinematográfica durante la entrada.
- boxeo/project.godot: conserva la activación de Text-to-Speech de Android.

Integración:
Extraer el ZIP desde la raíz del repositorio JARVIS-FINAL, conservando las rutas.
No reemplaza los demás archivos del juego.

Nota de verificación:
Se validó el JSON y que cada golpe de las combinaciones exista en la tabla de
animaciones. No se pudo ejecutar una compilación Godot/Android en este entorno;
conviene probar la escena en el dispositivo antes de publicar.
