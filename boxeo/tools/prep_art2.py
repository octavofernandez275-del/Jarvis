"""Postproceso de ART/: nitidez de fondos, sprites recortados al contorno (con colores de guantes/pantalón en art_meta.json)
y guantes del equipo con fondo transparente. Idempotente. Uso (desde boxeo/): python3 -I tools/prep_art2.py"""
import json, glob, colorsys
import numpy as np
from PIL import Image, ImageFilter
from scipy import ndimage as ndi

M = json.load(open('ART/art_meta.json'))

if not M.get('sharpened'):
    for f in glob.glob('ART/backdrops/*.jpg'):
        im = Image.open(f).convert('RGB')
        im = im.filter(ImageFilter.UnsharpMask(radius=2.4, percent=150, threshold=2))
        im.save(f, quality=90)
    M['sharpened'] = True

def hexc(rgb): return '#%02x%02x%02x' % tuple(int(x) for x in rgb)

def classify_glove(px):
    """px: Nx3 (0..255) de la franja de los guantes. Devuelve (clase, hex)."""
    hsv = np.array([colorsys.rgb_to_hsv(*(p / 255.0)) for p in px[::2]])
    rgb = px[::2]
    skin = (hsv[:, 0] > 0.02) & (hsv[:, 0] < 0.12) & (hsv[:, 1] > 0.25) & (hsv[:, 1] < 0.75) & (hsv[:, 2] > 0.25)
    red = ((hsv[:, 0] < 0.02) | (hsv[:, 0] > 0.93)) & (hsv[:, 1] > 0.55) & (hsv[:, 2] > 0.3)
    blue = (hsv[:, 0] > 0.55) & (hsv[:, 0] < 0.70) & (hsv[:, 1] > 0.5) & (hsv[:, 2] > 0.3)
    white = (hsv[:, 1] < 0.15) & (hsv[:, 2] > 0.8)
    black = (hsv[:, 2] < 0.16)
    cnt = {"red": red.sum(), "blue": blue.sum(), "white": white.sum() * 0.8, "black": black.sum() * 0.9}
    k = max(cnt, key=cnt.get)
    return k, {"red": "#c62828", "blue": "#1565c0", "white": "#f2f2f2", "black": "#1c1c1c"}[k]

for i, f in enumerate(sorted(glob.glob('ART/sprites/s*.png'))):
    im = Image.open(f).convert('RGBA')
    bb = im.getchannel('A').point(lambda v: 255 if v > 40 else 0).getbbox()
    if bb:
        pad = 2
        bb = (max(0, bb[0] - pad), max(0, bb[1] - pad), min(im.width, bb[2] + pad), min(im.height, bb[3] + pad))
        im = im.crop(bb)
        im.save(f)
    a = np.array(im)
    h, w = a.shape[:2]
    band = a[int(h * 0.16):int(h * 0.36)]
    px = band[band[..., 3] > 200][:, :3].astype(float)
    gk, gh = classify_glove(px) if len(px) > 40 else ("black", "#1c1c1c")
    low = a[int(h * 0.40):int(h * 0.62)]
    lp = low[low[..., 3] > 200][:, :3].astype(float)
    sat = np.array([colorsys.rgb_to_hsv(*(p / 255))[1] for p in lp[::3]]) if len(lp) > 30 else np.array([])
    sel = lp[::3][sat > 0.5] if len(sat) and (sat > 0.5).sum() > 20 else lp
    sh = hexc(np.median(sel, axis=0)) if len(sel) else "#1a1a1a"
    idx = int(f[-6:-4])
    M['sprites'][idx].update({"glove": gk, "glove_hex": gh, "shorts_hex": sh, "w": w, "h": h})

# guantes del equipo: quitar el fondo negro (relleno desde el borde)
for f in glob.glob('ART/gear/glove_*.png') + glob.glob('ART/gear/boots_*.png') + glob.glob('ART/gear/shorts_*.png'):
    im = Image.open(f).convert('RGBA')
    a = np.array(im)
    dark = (a[..., :3].max(axis=2) < 34)
    lab, n = ndi.label(dark)
    border = set(np.unique(np.concatenate([lab[0], lab[-1], lab[:, 0], lab[:, -1]]))) - {0}
    bg = np.isin(lab, list(border))
    alpha = np.where(bg, 0, 255).astype(np.uint8)
    alpha = np.array(Image.fromarray(alpha).filter(ImageFilter.GaussianBlur(0.8)))
    a[..., 3] = alpha
    Image.fromarray(a).save(f)

json.dump(M, open('ART/art_meta.json', 'w'))
json.dump({'s%02d' % i: {'shorts': s['shorts_hex'], 'gloves': s['glove_hex']} for i, s in enumerate(M['sprites'])}, open('ART/sprites.json', 'w'))
print('ok', len(M['sprites']), [s.get('glove') for s in M['sprites']])
