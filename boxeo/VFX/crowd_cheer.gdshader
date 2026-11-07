// Público 3D animado con una sola llamada de dibujo (MultiMesh): cada espectador salta con fase distinta.
// El color de cada instancia (ropa) llega por COLOR (MultiMesh.use_colors = true).
shader_type spatial;
render_mode cull_back;

uniform float speed = 6.0;
uniform float amp = 0.10;
uniform float excitement : hint_range(0.0, 1.0) = 0.5;   // 0 = sentados, 1 = eufóricos

void vertex() {
	float ph = float(INSTANCE_ID) * 1.713;
	float lift = max(VERTEX.y, 0.0);
	VERTEX.y += abs(sin(TIME * speed * (0.6 + excitement) + ph)) * amp * excitement * lift;
}

void fragment() {
	ALBEDO = COLOR.rgb;
	ROUGHNESS = 0.9;
}
