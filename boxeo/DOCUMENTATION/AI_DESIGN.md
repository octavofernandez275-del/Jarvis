# Diseño de la IA de los rivales (`OPPONENTS/boxer_ai.gd`)

## Personalidades (`OPPONENTS/ai_profiles.json`)
Agresivo, Técnico, Defensivo, Contraatacador, Veloz, Resistente, Estratega y Especialista en presión. Cada perfil define: agresividad, ritmo de decisión (`tempo`), reacción y tiempo de reacción, tendencia a mantener la guardia, preferencia bloqueo/esquiva, probabilidad de contraataque, presión (avanzar), distancia preferida, velocidad, capacidad de aprendizaje, umbral de descanso y su lista de combinaciones con pesos.

## Qué hace la IA
| Capacidad | Cómo |
|---|---|
| **Reconocer ataques** | Detecta el golpe del jugador en su fase de arranque (`startup`) si está al alcance. |
| **Defender / esquivar** | Una decisión por golpe: probabilidad según reacción, dificultad y aprendizaje; elige bloqueo o esquiva según su perfil. Solo reacciona si su tiempo de reacción cabe antes del impacto. |
| **Contraatacar** | Durante la recuperación del golpe del jugador (o tras defender), con la probabilidad `counter` del perfil. |
| **Combinar golpes** | Elige combinaciones del perfil ponderadas por pesos aprendidos; encadena en la última parte de la recuperación. |
| **Aprender patrones** | Cadenas de Markov de contexto 1 y 2 acciones del jugador (`jab>cross` → siguiente). Si la confianza ≥ 40 %, **anticipa**: reacciona 2.5× más rápido y con más probabilidad durante 1.2 s. |
| **Adaptarse** | Si el jugador se tapa >40 % → más golpes al cuerpo y uppercuts; si esquiva >30 % → más ganchos y directos. Cada golpe sube o baja su peso según conecte, sea bloqueado o esquivado. Si un golpe del jugador le conecta, lo respeta más. |
| **Estrategia** | Va perdiendo en las tarjetas → arriesga más (más en rounds finales); va ganando → se cuida. Si su aguante baja del umbral, retrocede y descansa. |

`get_insight()` devuelve un texto que el HUD muestra ("IA espera: jab>cross (62%)").

## Dificultad
`skill` 0.3 / 0.55 / 0.8 / 1.0 escala reacción, ritmo y velocidad de aprendizaje.

## Comprobación
`tools/sim_check.py` replica estas reglas y simula combates de 180 s contra un jugador artificial de patrón fijo (jab-cruzado con algo de azar). Resultado: tras ajustar el tiempo de reacción y el costo de bloqueo, en dificultad alta la IA gana al bot con los perfiles técnico, defensivo y presión, queda pareja con el agresivo y pierde con el veloz y el contraatacador (poca pegada o muy pasivos frente a un bot que solo lanza golpes); en dificultad baja pierde con la mayoría. Es una comprobación de equilibrio frente a un bot simple, **no** una medida de cómo jugará contra una persona: ajusta `ai_profiles.json` jugando.
