"""Extrae los fotogramas de animación de la lámina maestra (filas GUARDIA, CAMINATA, JAB, ...): fondo negro -> alfa,
alinea cada fotograma (pies y torso), amplía x3 y genera un atlas por animación + máscara de colores (R=pantalón, G=guantes, B=piel)
para teñir a cada boxeador desde un shader. Uso (desde boxeo/): python3 -I tools/extract_anim.py <lamina.png>"""
import sys, json, os, colorsys
import numpy as np
from PIL import Image, ImageFilter
from scipy import ndimage as ndi

src = sys.argv[1]
im = Image.open(src).convert('RGB')
A = np.array(im).astype(np.float32)
LUM = A.max(axis=2)
UP = 3
CW, CH = 100 * UP, 104 * UP           # celda (antes de ampliar: 100x104)
AX = 0.36                             # el torso queda en 36% del ancho (los golpes se extienden a la derecha)
ROWS = [(397, 486), (503, 596), (611, 704), (722, 868)]
# (fila, nombre, x0, x1, nº de fotogramas)
SECT = [
    (0, "idle", 8, 345, 7), (0, "walk", 345, 500, 4), (0, "run", 500, 850, 8), (0, "jab", 855, 1145, 6), (0, "cross", 1160, 1525, 7),
    (1, "hook_l", 8, 298, 6), (1, "hook_r", 300, 595, 6), (1, "upper", 596, 872, 6), (1, "block", 880, 1150, 5), (1, "dodge", 1160, 1520, 6),
    (2, "combo", 8, 335, 7), (2, "counter", 335, 685, 7), (2, "hit", 685, 985, 7), (2, "stagger", 985, 1150, 4), (2, "down", 1150, 1528, 8),
    (3, "win", 12, 337, 7), (3, "lose", 337, 730, 9),
]
EXPECT = {'idle': 7, 'walk': 4, 'run': 7, 'jab': 5, 'cross': 6, 'hook_l': 5, 'hook_r': 5, 'upper': 5, 'block': 5, 'dodge': 6, 'combo': 7,
          'counter': 7, 'hit': 6, 'stagger': 4, 'down': 7, 'win': 6, 'lose': 9}
os.makedirs('ART/anim', exist_ok=True)
meta = {}

def cuts(prof, x0, x1, n):
    w = (x1 - x0) / n
    out = [x0]
    for k in range(1, n):
        ideal = x0 + k * w
        lo, hi = int(ideal - 0.32 * w), int(ideal + 0.32 * w)
        seg = prof[lo:hi]
        out.append(lo + int(np.argmin(seg)) if len(seg) else int(ideal))
    out.append(x1)
    return out

def hsv(px):
    return np.array([colorsys.rgb_to_hsv(*(p / 255.0)) for p in px])

# --- detección de fotogramas: grupos de cabeza/torso (dilatados en horizontal), sin la línea de suelo
GROUPS = {}
for ri, (y0, y1) in enumerate(ROWS):
    H_ = y1 - y0
    r0, r1 = (0, int(H_ * 0.62)) if ri != 2 else (0, int(H_ * 0.62))
    band = LUM[y0:y1].copy()
    band[int(H_ * 0.86):] = 0
    m = band > 60
    if ri == 2:
        pass
    mm = ndi.binary_dilation(m[r0:r1], structure=np.ones((1, 9)))
    lab, k = ndi.label(mm)
    cl = []
    for i, sl in enumerate(ndi.find_objects(lab)):
        area = int((lab[sl] == i + 1).sum())
        if area > 220 and (sl[1].stop - sl[1].start) >= 14:
            cl.append([sl[1].start, sl[1].stop])
    cl.sort()
    GROUPS[ri] = cl

for ri, name, x0, x1, n in SECT:
    y0, y1 = ROWS[ri]
    band = LUM[y0:y1]
    cl = [c for c in GROUPS[ri] if x0 <= (c[0] + c[1]) / 2 < x1]
    if ri == 2 and name == "down":
        m = band[int((y1 - y0) * 0.30):int((y1 - y0) * 0.9)] > 55
        mm = ndi.binary_dilation(m, structure=np.ones((1, 9)))
        lab, k = ndi.label(mm)
        cl = sorted([[sl[1].start, sl[1].stop] for i, sl in enumerate(ndi.find_objects(lab)) if (lab[sl] == i + 1).sum() > 250 and x0 <= (sl[1].start + sl[1].stop) / 2 < x1])
    if len(cl) == EXPECT[name]:
        xs = [x0] + [int((cl[i][1] + cl[i + 1][0]) / 2) for i in range(len(cl) - 1)] + [x1]
        n = len(cl)
    else:                                   # agrupación dudosa: cortes por valles con el nº esperado de fotogramas
        n = EXPECT[name]
        prof = (band[:int((y1 - y0) * 0.85)] > 55).sum(axis=0).astype(float)
        prof = np.convolve(prof, np.ones(3) / 3, mode='same')
        xs = cuts(prof, x0, x1, n)
    items = []
    for i in range(n):
        a, b = xs[i], xs[i + 1]
        crop = A[y0:y1, a:b]
        l = crop.max(axis=2)
        fg = l > 28
        fg = ndi.binary_opening(fg, structure=np.ones((3, 1)))      # quita la línea de suelo
        fg = ndi.binary_closing(fg, iterations=2)
        holes = ndi.binary_fill_holes(fg) & ~fg
        hl, hk = ndi.label(holes)
        if hk:
            hs_ = ndi.sum(holes, hl, range(1, hk + 1))
            for hi, hv in enumerate(hs_):
                if hv < 90: fg |= hl == hi + 1
        lab, k = ndi.label(fg)
        if k == 0: continue
        sizes = ndi.sum(fg, lab, range(1, k + 1))
        fg = lab == (int(np.argmax(sizes)) + 1)
        ys, xs_ = np.where(fg)
        top, bot = ys.min(), ys.max() + 1
        # ancla: pies (borde inferior) y centro de masa del torso
        torso = (ys > top + 0.25 * (bot - top)) & (ys < top + 0.6 * (bot - top))
        cx = int(np.median(xs_[torso])) if torso.any() else int(xs_.mean())
        alpha = np.array(Image.fromarray((fg * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(0.7)))
        rgba = np.dstack([crop, alpha]).astype(np.uint8)
        fr = Image.fromarray(rgba)
        h0, w0 = rgba.shape[:2]
        # máscaras de color (en resolución original)
        hh = max(bot - top, 1)
        mk = np.zeros((h0, w0, 3), np.uint8)
        px = crop.reshape(-1, 3)
        hs = hsv(px[::1]).reshape(h0, w0, 3)
        H, S, V = hs[..., 0], hs[..., 1], hs[..., 2]
        skin = fg & (H > 0.01) & (H < 0.11) & (S > 0.25) & (S < 0.8) & (V > 0.28)
        yy = (np.arange(h0)[:, None] - top) / hh
        shorts_band = (yy > 0.50) & (yy < 0.72)
        upper_band = (yy > 0.10) & (yy < 0.56)
        sh = fg & shorts_band & ~skin
        gl = fg & upper_band & ~skin & ~sh & ((S > 0.45) | (V < 0.22) | ((S < 0.18) & (V > 0.75)))
        gl &= (yy > 0.14)
        mk[..., 0] = sh * 255; mk[..., 1] = gl * 255; mk[..., 2] = skin * 255
        mi = Image.fromarray(mk)
        # ampliar y colocar en la celda
        sc_ = 0.8 if ri == 3 else 1.0
        fr = fr.resize((int(w0 * UP * sc_), int(h0 * UP * sc_)), Image.LANCZOS).filter(ImageFilter.UnsharpMask(1.2, 35, 3))
        mi = mi.resize((int(w0 * UP * sc_), int(h0 * UP * sc_)), Image.BILINEAR)
        ox = int(CW * AX - cx * UP * sc_)
        oy = int(CH - 6 - bot * UP * sc_)
        items.append((fr, mi, ox, oy, int(fg.sum())))
    ar = sorted(it[4] for it in items)
    med = ar[len(ar) // 2] if ar else 0
    items = [it for it in items if it[4] >= 0.4 * med]      # descarta recortes vacíos / basura
    n = len(items)
    atlas = Image.new('RGBA', (CW * n, CH), (0, 0, 0, 0))
    mask = Image.new('RGB', (CW * n, CH), (0, 0, 0))
    for i, (fr, mi, ox, oy, _) in enumerate(items):
        atlas.paste(fr, (i * CW + ox, oy), fr)
        mask.paste(mi, (i * CW + ox, oy))
    atlas.save(f'ART/anim/{name}.png')
    mask.save(f'ART/anim/{name}_m.png')
    meta[name] = {"n": n, "cw": CW, "ch": CH}
    print(name, n, xs)
json.dump({"cell": [CW, CH], "anchor_x": AX, "up": UP, "clips": meta}, open('ART/anim/anim.json', 'w'))
# hoja de contactos para revisar
sheet = Image.new('RGBA', (CW * 8 // 2, CH * len(meta) // 2), (70, 80, 100, 255))
for r, name in enumerate(meta):
    at = Image.open(f'ART/anim/{name}.png').resize((CW * meta[name]['n'] // 2, CH // 2))
    sheet.paste(at, (0, r * CH // 2), at)
sheet.convert('RGB').save('/tmp/claude-0/-home-claude/878bb875-8c92-53dc-911f-ae6043e8c55f/scratchpad/art/anim_sheet.png')
