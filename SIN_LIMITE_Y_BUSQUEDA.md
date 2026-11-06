# Sin límite de tokens + búsqueda mejorada

## Sin límite de tokens
- `Chat.kt`, `Engine.kt`: `max_tokens = -1` (el modelo escribe hasta terminar solo). Antes 3500 / 300; los topes por tarea de `Agente.kt` (120–1500) ya no limitan.
- `Engine.kt`: contexto `-c 2048` -> `Engine.CONTEXTO = 32768` y `--context-shift`, así una respuesta larguísima nunca se corta al llenar el contexto (descarta lo más viejo y sigue). Si tu llama.cpp rechaza `--context-shift`, el reintento automático arranca sin él.
- `Chat.kt`, `Assistant.kt`, `Memory.kt`: presupuestos de historial escalados al nuevo contexto (10 -> 24 mensajes).
- Si el teléfono va justo de RAM, baja `CONTEXTO` a 8192 o 4096 en `Engine.kt`.

## Búsqueda
- +15 fuentes en paralelo: DuckDuckGo (normal y Lite), Bing, Mojeek, Yahoo, Brave, Google Noticias, Stack Overflow, arXiv, Reddit, Hacker News, Open Library, Internet Archive y Wikipedia en 6 idiomas. Si una falla, las demás siguen.
- Ranking: posición + coincidencia entre buscadores + palabras de la consulta en título/URL.
- 3 consultas generadas por el modelo + la original (antes 2).
- Hasta 6 fuentes (antes 3), leídas en paralelo; Wikipedia completa del mejor artículo.
- Extracción de páginas mejorada (article/main, listas, subtítulos, meta descripción, entidades HTML) y recuperación de pasajes (`mejoresPasajes`): se envía al modelo lo que contesta la consulta, no los primeros 900 caracteres.
- Descarga de páginas hasta 1,5 MB (antes 400 KB).

## Pendiente
No se pudo compilar aquí (sin Kotlin/Android SDK). Ejecuta el workflow *Compilar APK* de GitHub Actions antes de instalar.

## Búsqueda, ronda 2
- Memoria de páginas (10 min) y reintento automático ante fallos de red.
- Reputación de sitios (Wikipedia, .gov, .edu, agencias, ciencia suben; redes sociales y tiendas se descartan).
- Se descartan captchas, avisos de cookies y "activa JavaScript"; se descartan fuentes que copian a otra (similitud > 70 %).
- Extracción del artículo desde datos estructurados JSON-LD.
- Corrección ortográfica (Wikipedia) si casi no hay resultados.
- Segunda ronda automática con términos nuevos sacados de los títulos hallados si quedan menos de 4 fuentes.
- Pedidos de algo reciente ("hoy", "precio", "últimas"…) agregan titulares de noticias.

## Búsqueda en vivo en el chat
- Antes solo las tareas buscaban en internet; el chat contestaba de memoria. Ahora, si preguntas datos o actualidad ("quién ganó…", "cuánto cuesta…", "qué es…"), Jarvis busca mientras arranca el motor, lee las mejores páginas, se las pasa al modelo y muestra las fuentes [1], [2]… debajo de la respuesta (la voz no lee los enlaces).
- No busca en saludos, cálculos, creatividad ni charla. Las preguntas cortas de seguimiento usan tu pregunta anterior como contexto.
- Comandos: `desactiva la búsqueda en internet` / `activa la búsqueda en internet` (por defecto activada).
- Verificación: los fuentes se analizaron con el compilador de Kotlin (sin errores de sintaxis); la compilación completa necesita el SDK de Android (workflow de GitHub Actions).

## Interfaz renovada
**Pantalla principal (`MainActivity.kt`)**
- Conversación en burbujas: tú en rojo a la derecha, Jarvis en azul a la izquierda con su etiqueta; avisos del sistema en gris pequeño. Los enlaces de las fuentes se pueden tocar; mantener pulsada una burbuja copia su texto.
- El scroll ya no te arrastra al final si estás leyendo mensajes anteriores.
- Estado en píldora con punto de color: ámbar (trabajando/buscando), verde (listo), rojo (escuchando).
- Barra de entrada tipo cápsula con botones redondos (micrófono que late en rojo al escuchar, enviar ➤).
- Pantalla de bienvenida con atajos (noticias, investigar, presentación, imagen) cuando no hay mensajes.
- Fondo con degradado, animación de entrada de mensajes, respuesta táctil (se encoge y vibra suave) en botones.

**Tareas (`TareasActivity.kt`)**: botón ← para volver, tarjetas con franja de color y píldora de estado (verde lista, ámbar trabajando, rojo falló/espera tu OK), barra de progreso y última línea de la bitácora mientras Jarvis trabaja, pantalla vacía más clara.

**Compartido (`Tema.kt`)**: paleta ampliada, fondo degradado, botón circular, píldora, respuesta táctil y animación de entrada.

## Tareas: reorganización (a partir de una captura)
- El campo "Nueva tarea" pasó a una cápsula ABAJO, con botones redondos ＋ y ▶, y un texto más corto (el ejemplo largo ocupaba 5 líneas).
- Atajos en una fila desplazable de 38 dp de alto, con 💡 Sugerir primero; Piloto automático y Curiosidad en dos botones anchos de una línea, sin cortarse.
- Botones de tamaño fijo en dp (no se agrandan con la fuente grande del sistema) en Tareas y en la fila inferior del chat.
