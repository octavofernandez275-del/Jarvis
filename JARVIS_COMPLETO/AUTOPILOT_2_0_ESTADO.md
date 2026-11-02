# JARVIS AUTOPILOT 2.0 — estado técnico de esta entrega

## Base preservada

Se parte de `JARVIS_AUTOPILOT_CORREGIDO.zip`. Se conserva el paquete `com.octavo.jarvis`, el asistente, motor local llama-server/Qwen, voz, manos libres, comandos, memoria general, servicio, receptor de arranque y reactor gráfico. No se incorpora el sistema de infiltración, que el usuario indicó que podrá agregar por separado.

## Integración funcional

1. **Cerebro y planificación:** el planificador reutiliza Qwen local solo cuando el modelo ya existe y el endpoint local responde. El llamado se realiza dentro del Worker, nunca en el hilo de interfaz. Si el motor no está disponible, usa un plan determinista liviano.
2. **Agentes/herramientas:** registro de capacidades con indicación local/opcional y requisito de confirmación.
3. **Memoria:** historial local limitado a 200 experiencias, separado de la memoria del chat.
4. **Investigación:** DuckDuckGo HTML, máximo ocho resultados, deduplicación por URL, filtro de destinos locales/privados y vistas previas limitadas a tres páginas, sin seguir redirecciones durante la vista previa.
5. **Descargas:** solo por petición explícita y enlace HTTPS validado; Android DownloadManager gestiona la transferencia. El archivo nunca se ejecuta.
6. **Creative Studio:** exportación PDF, DOCX, XLSX, PPTX y TXT. Se sanitizan caracteres XML de control, se revisa la estructura principal de paquetes Office y se permite compartir manualmente con FileProvider.
7. **Developer Engine:** crea un esqueleto Android Kotlin con workflow de GitHub Actions, guarda archivos con revisión heurística y permite empaquetar el proyecto en ZIP con control de rutas y tamaño. No ejecuta ni compila el código.
8. **Asesor proactivo:** recomienda mejoras a partir de patrones de errores/exportaciones; no cambia permisos ni procedimientos por sí solo.
9. **Calidad/seguridad:** validaciones de archivo, paquetes Office, rutas ZIP, detección heurística de llamadas peligrosas y posibles secretos, límites de memoria/tamaño y confirmación al compartir.

## Robustez añadida en esta revisión

- Se quitó la planificación Qwen del hilo principal para evitar congelar la interfaz.
- La misión se persiste sincrónicamente antes de encolar el Worker, evitando una carrera entre guardado y lectura del Worker.
- Se limita el historial de misiones a 100 y el de experiencias a 200.
- Reanudar una misión reemplaza el Worker anterior por el nuevo trabajo único; si la aplicación vuelve tras una interrupción y WorkManager ya no ejecuta el trabajo, el usuario puede recuperarlo desde la tarjeta de misión.
- El Worker detecta detenciones de WorkManager y limpia el estado cooperativo al finalizar.
- Se añadió FileProvider con acceso temporal de solo lectura para compartir artefactos.
- Las misiones de descarga en cola ahora pueden cancelarse desde el panel; el receptor evita convertir una cancelación explícita en error de descarga.
- El exportador de proyectos ZIP limita cantidad de archivos y tamaño total, comprueba rutas canónicas y vuelve a leer el ZIP para verificar entradas.
- Los directorios de salida usan almacenamiento externo específico de la app con fallback al almacenamiento interno.

## Dependencias

- Android Gradle Plugin 8.7.3.
- Kotlin Android 2.0.21.
- Gradle 8.10.2 en GitHub Actions.
- compileSdk 34, minSdk 26, targetSdk 34.
- WorkManager KTX 2.9.1.
- AndroidX Core KTX 1.13.1 (FileProvider).
- Motor nativo llama.cpp compilado para `arm64-v8a` durante GitHub Actions.

## Límites conocidos

- No hay proveedor/modelo de generación de imágenes configurado. El proyecto expone un contrato opcional, pero no finge generar imágenes ni presupone endpoints o claves.
- La planificación semántica usa Qwen solo si el servidor local ya está saludable; si no, se aplica el plan de respaldo.
- La misión ejecutable se limita a las rutas integradas de investigación o descarga. No existe un runtime general que ejecute arbitrariamente herramientas inventadas por el modelo.
- La investigación no verifica reputación o veracidad de cada página; las vistas previas son metadatos parciales.
- La validación DNS no evita por completo DNS rebinding ni redirecciones posteriores de DownloadManager.
- Office se valida estructuralmente, no mediante Microsoft Office/LibreOffice en dispositivo.
- Developer Engine es un editor/creador básico y análisis heurístico; no es compilador ni sandbox.
- No hay antivirus integrado.
- WorkManager puede demorarse o detenerse por políticas de batería del fabricante.
- El workflow clona la rama principal de llama.cpp; para reproducibilidad estricta conviene fijar un commit cuando se valide una compilación estable.

## Verificación ejecutada en este entorno de edición

- Inspección del árbol del proyecto y revisión de llamadas entre `Autopilot`, `MissionWorker`, `MissionStore`, `Engine`, actividades y manifiesto.
- Validación XML del manifiesto y `file_paths.xml`.
- Validación XML de los 18 fragmentos del exportador mediante parser XML; para DOCX se sustituyeron las interpolaciones Kotlin por contenido representativo. No se generó un documento real en este entorno Android.
- Kotlin compiler 1.9.0 ejecutado sobre las fuentes para detectar errores de sintaxis; no se reportaron errores de parser (`expecting`/tokens inesperados). La compilación semántica produjo referencias no resueltas porque este entorno no contiene Android SDK ni dependencias AndroidX.
- No se ejecutó `assembleDebug`, no se generó ni inspeccionó un APK y no se ejecutó GitHub Actions.

La compilación real y la instalación en el Nubia siguen pendientes de ejecutar el workflow del repositorio.
