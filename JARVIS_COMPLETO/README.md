# JARVIS AUTOPILOT 2.0

Aplicación Android Kotlin para `com.octavo.jarvis`, desarrollada para funcionar desde un teléfono Android y GitHub Actions. La base conserva el asistente, voz, manos libres, comandos, motor local llama.cpp/Qwen, memoria conversacional, servicio y reactor gráfico existentes.

## Compilación

La forma reproducible prevista es GitHub Actions: subir el contenido de `JARVIS_COMPLETO/` a la raíz del repositorio y ejecutar el workflow **Compilar APK JARVIS** desde Actions. El workflow prepara Java 17, Android SDK 34, NDK arm64, compila `llama-server` y después ejecuta `gradle assembleDebug`.

No se incluye Gradle Wrapper porque no se generó ni verificó un wrapper binario en este entorno. El workflow configura Gradle 8.10.2.

## Funciones integradas

- Centro AUTOPILOT con misiones, progreso, historial, pausa, reanudación, cancelación y recuperación de trabajos interrumpidos.
- Planificador híbrido: Qwen local si el servidor ya está activo; alternativa determinista de bajo consumo si no lo está. No inicia un segundo modelo.
- WorkManager para ejecución serializada y persistente; las misiones de investigación/descarga esperan conectividad.
- Investigación DuckDuckGo HTML con deduplicación, validación DNS básica de URLs y vistas previas limitadas de hasta tres páginas.
- Descargas HTTPS mediante Android DownloadManager; los archivos no se ejecutan.
- Creative Studio para PDF, DOCX, XLSX, PPTX y TXT, con revisión estructural básica y opción de compartir mediante FileProvider.
- Developer Engine para crear un esqueleto Android Kotlin, guardar código con análisis heurístico y exportar el proyecto a ZIP verificado. No ejecuta ni compila el código generado.
- Memoria de experiencias limitada a 200 eventos y asesor proactivo basado en patrones observados.
- Registro de herramientas y requisitos de confirmación.

## Límites explícitos

- No hay un proveedor de generación de imágenes configurado. Existe un contrato de integración opcional; no se inventa una API ni se simula generación.
- El análisis de código es heurístico y no sustituye un compilador, análisis estático profesional ni sandbox.
- La investigación recupera resultados y metadatos parciales; no certifica la veracidad, reputación ni seguridad de los sitios.
- Las comprobaciones de Office verifican estructura principal, no compatibilidad integral con todas las suites.
- WorkManager está sujeto a las políticas de batería y segundo plano del fabricante Android.
- La ejecución autónoma se limita a las herramientas realmente implementadas; no ejecuta acciones arbitrarias propuestas por el modelo.

## Seguridad

No se ejecutan automáticamente descargas ni proyectos. Las descargas se limitan a HTTPS público; se bloquean destinos locales/privados en la validación previa, aunque esto no constituye una protección completa frente a redirecciones del servidor de descarga o DNS rebinding. Compartir archivos se hace solo cuando el usuario pulsa **Compartir**, mediante URI temporal de solo lectura.

## Estructura

- `app/src/main/java/com/octavo/jarvis/`: aplicación y módulos integrados.
- `app/src/main/res/xml/file_paths.xml`: rutas limitadas para FileProvider.
- `.github/workflows/build.yml`: compilación APK y motor nativo arm64.
- `AUTOPILOT_2_0_ESTADO.md`: detalle de alcance, arquitectura y verificaciones.
