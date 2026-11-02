# JARVIS AUTOPILOT 2.0

Proyecto Android Kotlin `com.octavo.jarvis`, evolucionado sobre `JARVIS_AUTOPILOT_CORREGIDO.zip`. Conserva asistente, motor local llama-server/Qwen, voz, manos libres, comandos, memoria general, servicio y reactor. No incluye el sistema de infiltración.

La versión agrega WorkManager para misiones, planificación híbrida que reutiliza el modelo local solo cuando ya está activo, investigación pública con límites, validación y descargas controladas, Creative Studio (PDF/DOCX/XLSX/PPTX/TXT), Developer Engine, memoria de experiencias, catálogo de herramientas, asesor proactivo y controles básicos de calidad/seguridad. Ver `AUTOPILOT_2_0_ESTADO.md` para alcance y limitaciones.

## Compilación

El workflow `.github/workflows/build.yml` instala Java 17, Android SDK/NDK y Gradle, compila `llama-server` para `arm64-v8a`, lo empaqueta en `app/src/main/jniLibs/arm64-v8a` y genera el APK debug.

Este ZIP no incluye Gradle Wrapper. El entorno de edición no dispone de Android SDK/NDK ni de dependencias Android para ejecutar `assembleDebug`; por eso no se afirma que el APK compile hasta que GitHub Actions finalice correctamente.
