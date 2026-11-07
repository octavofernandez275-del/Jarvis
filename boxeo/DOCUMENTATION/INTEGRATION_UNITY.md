# Integración en Unity (2022 LTS o superior)

Los `.glb` son glTF 2.0 estándar. Unity no los importa de serie:

1. Instala **glTFast** (`com.unity.cloud.gltfast`, Package Manager → Add by name). Arrastra los `.glb` a `Assets/`.
2. **Humanoid**: en el importador del modelo del boxeador usa *Rig → Animation Type: Humanoid* y revisa el mapa de huesos (tabla en `ANIMATION_GUIDE.md`; faltan dedos y hombros, Unity lo acepta). Si glTFast no genera el Avatar, crea un prefab con *Generic* y reproduce los clips con `Animation`/Playables.
3. **Animaciones**: los clips vienen embebidos con el nombre de `animation_table.json`. Marca *Loop Time* en los de la tabla con `loop: true`.
4. **Datos**: los `.json` se leen con `JsonUtility` o Newtonsoft (`com.unity.nuget.newtonsoft-json`).
5. **IA**: `OPPONENTS/boxer_ai.gd` es lógica pura (sin dependencias de motor salvo posición y estado del boxeador). Se porta casi línea por línea a C#: mismas funciones `observe`, `predict`, `think`, `notify_result`. La tabla de golpes (`animation_table.json`) y los perfiles (`ai_profiles.json`) se reutilizan tal cual.
6. **Público**: usa `GPU instancing`/`Graphics.DrawMeshInstanced` con los 3 LOD de `ENVIRONMENTS/` y un vertex shader de rebote (ver `VFX/crowd_cheer.gdshader` como referencia).
7. **Materiales**: son colores PBR sin texturas; asigna tus Shader Graph/URP Lit y sobreescribe el color por nombre de material (`skin`, `shorts`, `gloves`, `shoes`, `hair`).
