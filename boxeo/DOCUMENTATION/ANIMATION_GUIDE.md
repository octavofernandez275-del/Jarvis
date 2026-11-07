# Guía de animaciones

Todas las animaciones son esqueléticas (rotaciones por hueso, interpolación lineal), embebidas en los `.glb` y reutilizables por todos los boxeadores. Sin imágenes estáticas.

## Esqueleto (17 huesos, mismos en boxeador y personal)
`Hips → Spine → Chest → Neck → Head`; `Chest → UpperArm_L/R → Forearm_L/R → Hand_L/R`; `Hips → Thigh_L/R → Shin_L/R → Foot_L/R`.
Convención: Y arriba, el personaje mira a +Z, su izquierda es +X, 1 unidad = 1 m, altura 1.82 m. Pose de reposo: de pie con brazos abajo.
Cada clip tiene pista de rotación para los 17 huesos y de traslación para `Hips` (así los clips no se "contaminan" al mezclarlos).

## Golpes (tiempos medidos; coinciden con la duración del clip)
| Golpe | Mano | Blanco | Daño | Aguante | Arranque | Activo | Recuperación | Alcance del guante (m) | Rango de impacto (m) |
|---|---|---|---|---|---|---|---|---|---|
| `jab` | L | head | 6 | 4 | 0.12 | 0.08 | 0.2 | 0.77 | 1.06 |
| `cross` | R | head | 11 | 8 | 0.2 | 0.08 | 0.3 | 0.71 | 1.0 |
| `lead_hook` | L | head | 10 | 7 | 0.22 | 0.08 | 0.32 | 0.43 | 0.72 |
| `rear_uppercut` | R | head | 12 | 9 | 0.24 | 0.08 | 0.36 | 0.32 | 0.61 |
| `body_hook` | L | body | 8 | 6 | 0.22 | 0.08 | 0.3 | 0.42 | 0.71 |

*Alcance del guante* = distancia máxima del centro del guante, medida sobre el modelo. *Rango de impacto* = alcance + guante + cara del rival + tolerancia; es lo que usa el juego.

## Todos los clips
| Clip | Duración (s) | Bucle | Categoría | Descripción |
|---|---|---|---|---|
| `jab` | 0.4 | no | boxer | Golpe straight con mano L; impacto entre 0.12s y 0.20s |
| `cross` | 0.58 | no | boxer | Golpe straight con mano R; impacto entre 0.20s y 0.28s |
| `lead_hook` | 0.62 | no | boxer | Golpe hook con mano L; impacto entre 0.22s y 0.30s |
| `rear_uppercut` | 0.68 | no | boxer | Golpe uppercut con mano R; impacto entre 0.24s y 0.32s |
| `body_hook` | 0.6 | no | boxer | Golpe hook con mano L; impacto entre 0.22s y 0.30s |
| `idle_guard` | 1.2 | sí | boxer | Guardia ortodoxa con respiración y rebote leve |
| `walk_forward` | 0.7 | sí | boxer | Desplazamiento hacia el rival en guardia (sin root motion) |
| `walk_back` | 0.7 | sí | boxer | Retroceso en guardia (sin root motion) |
| `step_left` | 0.8 | sí | boxer | Paso lateral a la izquierda |
| `step_right` | 0.8 | sí | boxer | Paso lateral a la derecha |
| `block_high` | 0.3 | no | boxer | Bloqueo alto; se queda en la última pose mientras se mantiene |
| `dodge_left` | 0.36 | no | boxer | Esquiva lateral; ventana de invulnerabilidad 0.06s-0.26s |
| `dodge_right` | 0.36 | no | boxer | Esquiva lateral; ventana de invulnerabilidad 0.06s-0.26s |
| `hit_head` | 0.36 | no | boxer | Impacto en la cabeza |
| `hit_body` | 0.36 | no | boxer | Impacto al cuerpo |
| `stagger` | 1.1 | no | boxer | Tambaleo tras un golpe fuerte |
| `knockdown` | 1.1 | no | boxer | Caída de espaldas; termina en el suelo |
| `getup` | 1.6 | no | boxer | Levantarse tras la cuenta |
| `victory` | 1.0 | sí | boxer | Brazos en alto celebrando |
| `defeat` | 1.4 | no | boxer | Hombros caídos tras perder |
| `warmup_shadow` | 2.0 | sí | boxer | Sombra: jab-directo con rebote (calentamiento/entrenamiento) |
| `rest_breathe` | 2.4 | sí | boxer | Descanso entre rounds: manos en rodillas respirando |
| `entrance_taunt` | 1.6 | sí | boxer | Entrada al ring con los guantes en alto |
| `idle_staff` | 3.0 | sí | staff | Postura relajada con respiración |
| `walk_staff` | 1.0 | sí | staff | Caminata normal |
| `ref_count` | 1.0 | sí | staff | Árbitro cuenta: un golpe de brazo por segundo |
| `ref_break` | 1.3 | no | staff | Árbitro corta el combate |
| `ref_raise_winner` | 1.5 | no | staff | Árbitro levanta el brazo del ganador |
| `coach_cheer` | 1.0 | sí | staff | Entrenador celebrando |
| `coach_instruct` | 2.0 | sí | staff | Entrenador dando instrucciones con las manos |

## Sustituir por animaciones o personajes propios
1. Mantén los **nombres de clip** de la tabla y la **tabla de golpes** (`animation_table.json`): el juego solo necesita nombre, duración y bucle.
2. Para un personaje nuevo (p. ej. de Mixamo) retargetea a este esqueleto o al revés. Equivalencias de huesos:

| Este proyecto | Mixamo | Unity Humanoid |
|---|---|---|
| Hips | mixamorig:Hips | Hips |
| Spine / Chest | mixamorig:Spine / Spine1 (Spine2) | Spine / Chest |
| Neck / Head | mixamorig:Neck / Head | Neck / Head |
| UpperArm_L/R | mixamorig:LeftArm / RightArm | LeftUpperArm / RightUpperArm |
| Forearm_L/R | mixamorig:LeftForeArm / RightForeArm | LeftLowerArm / RightLowerArm |
| Hand_L/R | mixamorig:LeftHand / RightHand | LeftHand / RightHand |
| Thigh_L/R | mixamorig:LeftUpLeg / RightUpLeg | LeftUpperLeg / RightUpperLeg |
| Shin_L/R | mixamorig:LeftLeg / RightLeg | LeftLowerLeg / RightLowerLeg |
| Foot_L/R | mixamorig:LeftFoot / RightFoot | LeftFoot / RightFoot |

3. En Godot usa un `BoneMap` + `SkeletonProfileHumanoid` en la importación (Retarget) para reutilizar animaciones entre rigs.
4. Para añadir un golpe nuevo: agrégalo en `ATTACKS` y `ATTACK_POSES` de `tools/clips.py` y ejecuta `python3 tools/build_all.py`.
