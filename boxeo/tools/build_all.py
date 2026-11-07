"""Regenera TODOS los recursos: python3 tools/build_all.py   (requiere python3, numpy, scipy, Pillow)"""
import build_models, build_audio, build_ui, build_data, build_docs, validate_glb
print("modelos:", len(build_models.build_all()))
print("audio:", len(build_audio.build_all()))
print("ui:", len(build_ui.build_all()))
print("datos:", len(build_data.build_all()))
import os; root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
bad = 0
for dp, _, fs in os.walk(root):
    for f in fs:
        if f.endswith(".glb"):
            _, e = validate_glb.validate(os.path.join(dp, f)); bad += bool(e)
print("glb con errores:", bad)
print("manifiesto:", build_docs.main())
import build_guides
