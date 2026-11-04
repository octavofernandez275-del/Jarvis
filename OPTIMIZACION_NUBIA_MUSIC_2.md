# Optimización de rendimiento, estabilidad y energía (Nubia Music 2, 8 núcleos)

Parches aplicados sobre la versión más reciente (raíz del repositorio). No se reconstruyó nada; no se quitó ninguna función.
La carpeta `JARVIS_COMPLETO/` (copia antigua del 2-nov) no se tocó.

## Qué cambia

| Área | Cambio | Efecto esperado |
|---|---|---|
| Reparto de núcleos (`Cpu.kt`, `Ajustes.kt`, `Engine.kt`) | Perfil automático: núcleos rápidos detectados por `cpu_capacity`. En 2 rápidos + 6 eficientes: **4 hilos para escribir** (`-t`) y **6 para leer el prompt** (`-tb`). Antes: 6 y 6. | Escribir la respuesta está limitado por memoria, no por cálculo: menos hilos = menos calor casi sin perder velocidad. Leer el prompt usa más núcleos (cálculo puro, dura poco). |
| Máscaras de núcleos (`Engine.kt`) | Con "núcleos rápidos" activado, la lectura del prompt ahora tiene su propia máscara (`-Cb`). Antes se usaba una máscara de 6 bits con 4 hilos, y los hilos caían solo en núcleos eficientes. | Corrige un fallo latente al fijar núcleos. |
| Motor (`Engine.kt`) | `--poll 0` (los hilos no giran en vacío entre tokens) y `--no-webui` (menos RAM). | Menos CPU en espera y algo menos de RAM. |
| Arranque a prueba de fallos (`Engine.kt`, `EngineManager.kt`) | Si esta versión de llama.cpp rechaza alguna de las opciones nuevas, el reintento automático arranca con las opciones de siempre. | El motor nunca deja de arrancar por un parche. |
| Calor (`Termico.kt`) | Lee el estado térmico de Android (10+) y la temperatura de la batería (8+) y el modo ahorro. Con calor: menos hilos (solo en modo automático), respuestas más cortas si está muy caliente, el piloto automático espera, el motor no se levanta solo y las tareas de fondo hacen pausas. | No fuerza el procesador de forma constante. |
| Trabajo de fondo (`Hilos.kt`, `Agente.kt`, `Web.kt`, `Autopilot.kt`) | Tareas del agente, misiones y red con prioridad BACKGROUND y un grupo de red reutilizable (antes se creaba y destruía un grupo en cada búsqueda). | Android las agrupa en núcleos eficientes; la pantalla y el modelo no compiten con ellas. |
| Reactor (`ReactorView.kt`) | 15 fps en reposo, 30 cuando hace algo, menos si hay calor o ahorro. Marcas del anillo precalculadas (1 llamada en vez de 82 rotaciones por cuadro). Preferencias en caché (antes 2 lecturas por cuadro). | Menos CPU/GPU con la pantalla encendida. Visualmente igual. |

## Comandos nuevos (por voz o texto)
- `modo ahorro`: solo los núcleos rápidos escriben (2 hilos) y 4 leen.
- `modo equilibrado`: vuelve al reparto automático (por defecto).
- `modo rendimiento`: 6 y 6 como antes.
- `estado térmico` / `temperatura del teléfono`: temperatura de batería, nivel de calor e hilos en uso.

Todos los comandos anteriores (`hilos N`, `hilos de lectura N`, `núcleos rápidos`, `prioridad baja`, etc.) siguen funcionando; si fijas `hilos N` a mano, ese valor se respeta sin reducciones por calor.

## Cómo comprobar la mejora
La línea de estado ya muestra `primera palabra X s · Y tok/s · hilos A/B`. Compara `modo rendimiento` contra `modo equilibrado` con la misma pregunta. Si los tok/s del equilibrado caen mucho, prueba `hilos 5` o `hilos 6`.

## Pendiente de validar
- **Compilación:** no se pudo compilar en el entorno donde se aplicaron los parches (sin Android SDK, Kotlin ni red). Ejecuta el workflow *Compilar APK* de GitHub Actions antes de instalar.
- El arranque al encender el teléfono sigue en `true` por defecto en el código (`Ajustes.arrancarAlEncender`), aunque `REPARACION_COMPILACION.md` dice `false`. Si prefieres ahorrar batería: di `no arranques al encender`.
