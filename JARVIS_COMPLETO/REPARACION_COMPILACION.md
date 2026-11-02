# Reparación de compilación JARVIS AUTOPILOT

Correcciones aplicadas según el log de GitHub Actions:

1. `VoiceInput.kt`: agregado `onNoSpeech` y derivación de los errores de silencio/no coincidencia a ese callback.
2. `Ajustes.kt`: agregadas las preferencias `animacion()` y `arrancarAlEncender()` con sus setters.
3. `JarvisService.kt`: agregado el parámetro `arrancar` y su extra para que `BootReceiver` pueda solicitar el arranque del motor.
4. `AndroidManifest.xml`: registrados `JarvisService`, `BootReceiver` y permisos asociados.

El arranque automático está desactivado por defecto (`arrancar_al_encender=false`) para evitar consumo inesperado de batería. La compilación debe confirmarse ejecutando GitHub Actions.
