"""V4b: extrae fotogramas de la lámina de 16 animaciones (4x4 celdas, un solo boxeador) -> ART/anim/<clip>.png + <clip>_m.png + anim.json.
Máscaras: R=pantalón, G=guantes, B=piel (para teñir por boxeador desde el shader). Uso (desde boxeo/): python3 -I tools/extract_anim2.py lamina.png"""
import sys, json, os
import numpy as np, cv2
from PIL import Image, ImageFilter
from scipy import ndimage as ndi

src = sys.argv[1]
im = Image.open(src).convert('RGB')
A = np.array(im)
H0, W0 = A.shape[:2]
LUM = A.max(axis=2).astype(np.float32)
NAMES = ["jab", "cross", "hook_l", "hook_r", "upper", "hit", "down", "getup", "win", "lose", "walk", "run", "block", "dodge", "stagger", "idle"]
CW, CH, AX = 360, 330, 0.36
TITLE = 0.15
os.makedirs('ART/anim', exist_ok=True)
for f in os.listdir('ART/anim'):
    os.remove('ART/anim/' + f)
cw0, ch0 = W0 / 4.0, H0 / 4.0

def segment(cell):
    h, w = cell.shape
    body = cell[int(h * TITLE):] > 45
    body = ndi.binary_opening(body, structure=np.ones((3, 1)))
    prof = body.sum(axis=0) > 2
    prof = ndi.binary_closing(prof, structure=np.ones(7))
    lab, k = ndi.label(prof)
    runs = [(sl[0].start, sl[0].stop) for sl in ndi.find_objects(lab) if sl[0].stop - sl[0].start > 14]
    return runs

frames_by = {}
for ci, name in enumerate(NAMES):
    r, c = divmod(ci, 4)
    x0, x1 = int(c * cw0), int((c + 1) * cw0)
    y0, y1 = int(r * ch0), int((r + 1) * ch0)
    cell = LUM[y0:y1, x0:x1]
    runs = segment(cell)
    prof_ = (cell[int((y1 - y0) * TITLE):] > 45).sum(axis=0).astype(float)
    N = 5 if name == "jab" else 4
    if len(runs) == 1:                        # todos pegados: N cortes iguales ajustados al valle más cercano
        a, b = runs[0]
        wN = (b - a) / float(N)
        cuts_ = [a]
        for k in range(1, N):
            ideal = a + k * wN
            lo, hi = int(ideal - 0.3 * wN), int(ideal + 0.3 * wN)
            cuts_.append(lo + int(np.argmin(prof_[lo:hi])))
        cuts_.append(b)
        runs = [(cuts_[q], cuts_[q + 1]) for q in range(N)]
    while len(runs) < N:                      # fotogramas pegados: partir el tramo más ancho por el valle central
        runs.sort(key=lambda t: t[0])
        j = max(range(len(runs)), key=lambda q: runs[q][1] - runs[q][0])
        a, b = runs.pop(j)
        parts = 2
        mid0 = (a + b) / 2.0
        lo, hi = int(a + 0.35 * (b - a)), int(a + 0.65 * (b - a))
        v = lo + int(np.argmin(prof_[lo:hi])) if hi > lo else int(mid0)
        runs += [(a, v), (v, b)]
    runs.sort(key=lambda t: t[0])
    frs = []
    for a, b in runs:
        crop = A[y0 + int((y1 - y0) * TITLE):y1, x0 + max(a - 3, 0):x0 + b + 3]
        frs.append(crop)
    frames_by[name] = frs
    print(name, len(frs), [b - a for a, b in runs])

# escala global: altura de un boxeador de pie (idle) -> 250 px
def fig_bbox(crop):
    l = crop.max(axis=2) > 40
    l = ndi.binary_opening(l, structure=np.ones((3, 1)))
    l = ndi.binary_closing(l, iterations=2)
    lab, k = ndi.label(l)
    if k == 0:
        return None, None
    sizes = ndi.sum(l, lab, range(1, k + 1))
    fg = lab == (int(np.argmax(sizes)) + 1)
    ys, xs = np.where(fg)
    return fg, (ys.min(), ys.max() + 1, xs.min(), xs.max() + 1)

hs = []
for cr in frames_by["idle"]:
    fg, bb = fig_bbox(cr)
    if fg is not None: hs.append(bb[1] - bb[0])
S = 250.0 / float(np.median(hs))
print("escala", S)
meta = {}
skin_acc = np.zeros(3); skin_n = 0
for name in NAMES:
    items = []
    for crop in frames_by[name]:
        fg, bb = fig_bbox(crop)
        if fg is None: continue
        top, bot, lx, rx = bb
        hh = max(bot - top, 1)
        # alfa suave
        alpha = np.array(Image.fromarray((fg * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(0.6)))
        hsv = cv2.cvtColor(crop, cv2.COLOR_RGB2HSV).astype(np.float32)
        Hh, Ss, Vv = hsv[..., 0] / 180.0, hsv[..., 1] / 255.0, hsv[..., 2] / 255.0
        skin = fg & (Hh > 0.01) & (Hh < 0.11) & (Ss > 0.25) & (Ss < 0.85) & (Vv > 0.28)
        yy = (np.arange(crop.shape[0])[:, None] - top) / hh
        sh = fg & (yy > 0.50) & (yy < 0.72) & ~skin
        gl = fg & (yy > 0.12) & (yy < 0.56) & ~skin & ~sh & ((Ss > 0.45) | (Vv < 0.22) | ((Ss < 0.18) & (Vv > 0.75)))
        hair = fg & (yy < 0.16) & ~skin & (Vv < 0.35)
        if name == "idle" or name == "walk":
            skin_acc += crop[skin].sum(axis=0); skin_n += int(skin.sum())
        mk = np.dstack([sh, gl, skin]).astype(np.uint8) * 255
        zone = skin & (yy > 0.17) & (yy < 0.56)
        legs = skin & (yy >= 0.56)
        hk = np.dstack([hair, zone, legs]).astype(np.uint8) * 255
        rgba = Image.fromarray(np.dstack([crop, alpha]).astype(np.uint8))
        w2, h2 = int(crop.shape[1] * S), int(crop.shape[0] * S)
        fr = rgba.resize((w2, h2), Image.LANCZOS).filter(ImageFilter.UnsharpMask(1.2, 35, 3))
        mi = Image.fromarray(mk).resize((w2, h2), Image.BILINEAR)
        hi_ = Image.fromarray(hk).resize((w2, h2), Image.BILINEAR)
        # ancla: torso (mediana x de la franja 25-60%) y pies abajo
        ys, xs = np.where(fg)
        torso = (ys > top + 0.25 * hh) & (ys < top + 0.6 * hh)
        cx = int(np.median(xs[torso])) if torso.any() else int(xs.mean())
        lying = (rx - lx) > 1.1 * hh
        ox = int(CW * AX - cx * S)
        ox = min(max(ox, 4 - int(lx * S)), CW - 4 - int(rx * S))
        oy = int(CH - 6 - bot * S)
        items.append((fr, mi, ox, oy, hi_))
    n = len(items)
    atlas = Image.new('RGBA', (CW * n, CH), (0, 0, 0, 0))
    mask = Image.new('RGB', (CW * n, CH), (0, 0, 0))
    hmask = Image.new('RGB', (CW * n, CH), (0, 0, 0))
    for i, (fr, mi, ox, oy, hi_) in enumerate(items):
        atlas.alpha_composite(fr, (max(i * CW + ox, 0), max(oy, 0))) if False else atlas.paste(fr, (i * CW + ox, oy), fr)
        mask.paste(mi, (i * CW + ox, oy))
        hmask.paste(hi_, (i * CW + ox, oy))
    atlas.save(f'ART/anim/{name}.png')
    mask.save(f'ART/anim/{name}_m.png')
    hmask.save(f'ART/anim/{name}_h.png')
    meta[name] = {"n": n, "cw": CW, "ch": CH}
sk = (skin_acc / max(skin_n, 1) / 255.0).tolist()
json.dump({"cell": [CW, CH], "anchor_x": AX, "fig_h": 250.0, "skin_ref": sk, "clips": meta}, open('ART/anim/anim.json', 'w'))
sheet = Image.new('RGBA', (CW * 6 // 2, CH * len(meta) // 2), (70, 80, 100, 255))
for r, name in enumerate(meta):
    at = Image.open(f'ART/anim/{name}.png')
    at = at.resize((at.width // 2, at.height // 2))
    sheet.paste(at, (0, r * CH // 2), at)
sheet.convert('RGB').save('/tmp/claude-0/-home-claude/878bb875-8c92-53dc-911f-ae6043e8c55f/scratchpad/art/anim_sheet2.png')
