# Sin límite de tokens + búsqueda mejorada

## Sin límite de tokens
- `Chat.kt`, `Engine.kt`: `max_tokens = -1` (el modelo escribe hasta terminar solo). Antes 3500 / 300; los topes por tarea de `Agente.kt` (120–1500) ya no limitan.
- `Engine.kt`: contexto `-c 2048` -> `Engine.CONTEXTO = 32768` y `--context-shift`, así una respuesta larguísima nunca se corta al llenar el contexto (descarta lo más viejo y sigue). Si tu llama.cpp rechaza `--context-shift`, el reintento automático arranca sin él.
- `Chat.kt`, `Assistant.kt`, `Memory.kt`: presupuestos de historial escalados al nuevo contexto (10 -> 24 mensajes).
- El contexto ahora se elige solo según la RAM (4096 a 32768) y se puede fijar con `contexto 16384` o volver con `contexto automático` (se aplica al reiniciar la app).

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

## Voz estilo mayordomo digital (sin clonar a nadie)
- Voz por defecto más grave y pausada (tono 0.86, velocidad 0.94) y, si hay una voz masculina reconocible entre las instaladas, se usa sola.
- Comando nuevo: `voz jarvis` (o `voz de mayordomo`, `habla como jarvis`) aplica el preset completo. Se afina con `voz más grave`, `habla más lento`, `lista voces`, `voz 2`.
- El modelo responde con tono de mayordomo: cortés, preciso, ingenio seco y sin símbolos raros para que se lean bien en voz alta.

## Mejor entendimiento de la voz ("Jarvis" entendido como "llaves")
- `CorreccionVoz.kt`: corrige variantes mal entendidas (llaves, yarvis, charvis, garvis, travis…) al inicio de la frase o tras "oye/hey/hola". "Las llaves del auto" o "busca las llaves" NO se tocan.
- El reconocedor ahora devuelve hasta 5 hipótesis y se elige la que empieza diciendo Jarvis.
- Pista de vocabulario (Android 13+) con "Jarvis", "oye Jarvis", "hey Jarvis"; y 1,8 s de silencio antes de cortar, para que no te interrumpa si haces una pausa.

## Contexto adaptable a la RAM
- Antes el contexto era fijo (32768) y en un teléfono justo de memoria Android podía cerrar la app. Ahora `Engine.calcularContexto` elige 4096 / 8192 / 16384 / 32768 según la RAM total y todos los presupuestos de texto (historial, prompt, fuentes) se ajustan a ese valor.
- Comandos: `contexto 16384`, `contexto automático`, `qué contexto usas`.

## Memoria que realmente recuerda
- **Aprende sola** lo que cuentas de ti: "me llamo…", "vivo en…", "tengo 25 años", "trabajo en…", "mi perro se llama…", "mi color favorito es…", "me gusta…/no me gusta…". Los datos de un solo valor (nombre, dónde vives, edad) se actualizan en vez de duplicarse; si cambias de opinión sobre un gusto, se corrige. No aprende de preguntas ni de dudas ("me gusta esa idea"). Cada vez que anota algo lo muestra con 🧠. Se apaga con `no aprendas solo` y se enciende con `aprende solo`.
- **Capacidad**: 300 datos (antes 30) de hasta 300 caracteres.
- **Recuerda lo que viene al caso**: al contestar, el modelo recibe los datos más nuevos y además los que tienen que ver con tu pregunta, aunque sean viejos. El tamaño del bloque crece con el contexto (de 900 a 4500 caracteres).
- **Temas de charlas anteriores**: lo que ya no cabe en el historial deja una lista de qué preguntaste (15 temas) que sobrevive al cerrar la app.
- Siguen: `recuerda que…`, `qué recuerdas`, `olvida…`, `borra toda mi memoria`.

## Jarvis Boxeo (juego de boxeo, solo táctil)
- La carpeta `boxeo/` es el proyecto Godot 4.3 de `JARVIS_BOXING_ASSETS`, adaptado a **solo controles táctiles**: se quitó todo el teclado (J/K/L/I/U, espacio, A/D, flechas, C) y la emulación de toque con ratón; se añadió un botón táctil `CAM` para cambiar de cámara. Los botones (golpes, bloqueo, esquivas, avanzar/retroceder) ya eran multitoque.
- Se compila como una **segunda app** (`com.octavo.jarvisboxeo`) con el workflow «Compilar Jarvis Boxeo» (`.github/workflows/boxeo.yml`, se lanza a mano o al cambiar `boxeo/`). Resultado: artefacto `jarvis-boxeo-apk`. Usa el mismo `debug.keystore` que Jarvis.
- En Jarvis: botón **🥊 Boxeo** abajo del chat y el comando `abre boxeo` (o `juega boxeo`). Si el juego no está instalado, lo avisa.
- Aviso honesto: los scripts de Godot nunca se ejecutaron (el paquete original lo dice) y aquí tampoco hay Godot. Es probable que el primer arranque muestre algún error menor; pásame el texto del error y lo corrijo. La exportación de Android en CI tampoco está probada.

## Boxeo: Jarvis rival + narración con voces
- El rival lo controla Jarvis (boxer_ai.gd): aprende tus secuencias de golpes y las anticipa.
- `boxeo/SCRIPTS/narrator.gd`: narración en tiempo real con las voces TTS del dispositivo; voz/tono/velocidad distintos para presentador, comentaristas A/B y entrenadores rojo/azul.
- Entre rounds, Jarvis genera el análisis de ambos entrenadores (estadísticas del round + patrón aprendido del jugador).
- Sin voces en español instaladas, usa cualquiera disponible; si no hay ninguna, no narra.
