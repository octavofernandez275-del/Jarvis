# JARVIS AUTOPILOT — primera integración

## Incluido
- Acceso a AUTOPILOT desde la pantalla principal.
- Interfaz Android nativa con estética oscura/cian inspirada en JARVIS.
- Misiones persistentes localmente mediante SharedPreferences/JSON.
- Investigación web automática mediante DuckDuckGo HTML y guardado de informes en `Android/data/<paquete>/files/Autopilot/Informes`.
- Descargas directas cuando la misión incluye una URL HTTP(S) y pide descargarla; se usa Android DownloadManager.
- Confirmación del estado de descarga mediante BroadcastReceiver.
- No se modifica Engine.kt, EngineManager.kt, Assistant.kt, VoiceInput.kt ni VoiceOutput.kt.

## Límites de esta primera versión
- La investigación obtiene títulos/enlaces públicos; todavía no realiza lectura profunda de cada página ni análisis semántico con Qwen.
- Para descargar, la misión debe incluir un enlace directo al archivo. Todavía no descubre por sí sola enlaces de descarga en resultados de búsqueda.
- Las misiones se ejecutan en un hilo de la pantalla AUTOPILOT. No es todavía un agente persistente de segundo plano tras cerrar o forzar la detención de la app.
- La planificación avanzada con el modelo local, ejecución de múltiples subtareas y recuperación tras reinicio quedan para la siguiente fase.
- No se ha podido compilar aquí: el ZIP no incluye Gradle Wrapper y este entorno no dispone de Android SDK/Gradle instalados. Debe validarse con GitHub Actions antes de instalar el APK.

## Prueba sugerida
1. Abrir JARVIS y tocar `JARVIS AUTOPILOT`.
2. Crear una misión: `Investiga herramientas gratuitas para crear una tienda de libros digitales`.
3. Esperar a que se guarde el informe y abrir `VER INFORME`.
4. Probar una descarga con una URL HTTPS directa a un archivo permitido.
