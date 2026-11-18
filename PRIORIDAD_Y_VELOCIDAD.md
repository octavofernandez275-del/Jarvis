# Prioridad máxima permanente y tiempos de respuesta

Sin compilar (no hay Android SDK aquí). Lo siguiente es probar el workflow "Compilar APK" y hacer el diagnóstico por bloques.

| Archivo | Cambio |
|---|---|
| `Hilos.kt` | Prioridad -8 (URGENT_DISPLAY) para red y respuesta; -4 para agente/misiones. `Hilos.maxima()` sube el hilo actual. |
| `Ajustes.kt` | `prioridadMaxima` = **true por defecto**. El calor "caliente" ya no resta hilos (solo crítico y ahorro de energía). |
| `Engine.kt` | Todos los hilos de llama-server a nice -8, reaplicado cada 0,5 s el primer minuto y cada 4 s después (antes: nice 9 durante 3 s). Usa `/proc/<pid>/task` en vez de recorrer todo `/proc`. `--poll 50` (antes 0). |
| `Chat.kt` | Cada respuesta sube su hilo y reaplica la prioridad al motor antes de empezar. Sin caché HTTP. |
| `Assistant.kt` | Hilos de la respuesta y de la búsqueda web a prioridad máxima; espera máxima de la búsqueda 15 s (antes 30). La línea de estado muestra "prioridad máx". |
| `Commands.kt` | Nuevo `prioridad máxima`. `prioridad baja` y `prioridad normal` siguen existiendo y la desactivan. |

Límites: la prioridad solo da turno de CPU, no sube la frecuencia. Si Android rechaza algún hilo, se ignora sin error.
