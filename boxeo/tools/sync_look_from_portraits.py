"""Ajusta la ficha visual de cada boxeador a su retrato de selección: piel, pelo (o calvo), barba y color de guantes.
Uso (desde boxeo/): python3 -I tools/sync_look_from_portraits.py"""
import json, colorsys
import numpy as np
from PIL import Image

fj = json.load(open('CHARACTERS/fighters.json'))
F = fj['fighters']

def hexc(c): return '#%02x%02x%02x' % tuple(int(v) for v in c)
def lum(c): return 0.3 * c[0] + 0.59 * c[1] + 0.11 * c[2]

def skin_mask(a):
    r, g, b = a[..., 0] / 255., a[..., 1] / 255., a[..., 2] / 255.
    mx, mn = a.max(axis=2) / 255., a.min(axis=2) / 255.
    s = (mx - mn) / np.maximum(mx, 1e-3)
    hue = np.zeros_like(mx)
    d = np.maximum(mx - mn, 1e-3)
    hue = np.where(mx == r, ((g - b) / d) % 6, np.where(mx == g, (b - r) / d + 2, (r - g) / d + 4)) / 6.0
    return (hue > 0.0) & (hue < 0.12) & (s > 0.22) & (s < 0.8) & (mx > 0.25), s, mx, hue

for f in F:
    im = Image.open(f['portrait'].replace('res://', '')).convert('RGB').resize((200, 260))
    a = np.array(im).astype(np.float32)
    sm, s, v, hue = skin_mask(a)
    chest = a[int(260 * 0.62):int(260 * 0.92), int(200 * 0.30):int(200 * 0.70)]
    csm = sm[int(260 * 0.62):int(260 * 0.92), int(200 * 0.30):int(200 * 0.70)]
    skin = np.median(chest[csm], axis=0) if csm.sum() > 50 else np.array([176, 122, 92.])
    # pelo: franja superior central
    top = a[3:20, 80:120].reshape(-1, 3)
    tsm = sm[3:20, 80:120].reshape(-1)
    hair_c = np.median(top, axis=0)
    bald = bool(tsm.mean() > 0.55 and abs(lum(hair_c) - lum(skin)) < 0.22 * 255)
    # barba: banda de la mandíbula más oscura que la piel
    jaw = a[int(260 * 0.25):int(260 * 0.34), int(200 * 0.40):int(200 * 0.60)].reshape(-1, 3)
    beard = bool(((lum(skin) - np.array([lum(p) for p in jaw])) > 0.28 * lum(skin)).mean() > 0.38)
    # guantes: píxeles no piel, saturados o muy oscuros/blancos en la mitad baja
    low = a[int(260 * 0.50):int(260 * 0.97), int(200 * 0.08):int(200 * 0.92)].reshape(-1, 3)
    lsm = sm[int(260 * 0.50):int(260 * 0.97), int(200 * 0.08):int(200 * 0.92)].reshape(-1)
    cand = low[~lsm]
    gl = None
    if len(cand) > 0.04 * len(low):
        mxv = cand.max(axis=1) / 255.0
        mnv = cand.min(axis=1) / 255.0
        sat = (mxv - mnv) / np.maximum(mxv, 1e-3)
        rr, gg, bb = cand[:, 0], cand[:, 1], cand[:, 2]
        d_ = np.maximum(mxv - mnv, 1e-3) * 255.0
        hu = np.where(cand.max(axis=1) == rr, ((gg - bb) / d_) % 6, np.where(cand.max(axis=1) == gg, (bb - rr) / d_ + 2, (rr - gg) / d_ + 4)) / 6.0
        skinish = (hu > 0.015) & (hu < 0.14)
        score = sat * mxv * (~skinish)
        sel = cand[score > np.percentile(score, 70)]
        if score.max() > 0.28 and len(sel):
            gl = np.median(sel, axis=0)
        else:
            dark = cand[mxv < 0.22]
            gl = np.array([28, 28, 30.]) if len(dark) > 0.5 * len(cand) else np.median(cand, axis=0)
    look = f['look']
    look['skin'] = hexc(skin)
    look['hair'] = hexc(skin * 0.95) if bald else hexc(np.clip(hair_c, 12, 140))
    look['bald'] = bald
    look['beard'] = beard
    if gl is not None:
        look['gloves'] = hexc(gl)
json.dump(fj, open('CHARACTERS/fighters.json', 'w'), ensure_ascii=False, indent=1)
print(sum(1 for f in F if f['look']['bald']), 'calvos;', sum(1 for f in F if f['look']['beard']), 'con barba')
