"""Recorta las hojas de recursos (JARVIS BOXING AI V4) en archivos sueltos dentro de boxeo/ART/.
Uso: python3 tools/extract_art.py <carpeta_con_las_imagenes_fuente>
Fuentes esperadas (nombres de los adjuntos): 85bad597 (retratos+escenarios+cinturones), 68d710a9 (extras+equipo+HUD),
52002455 (hoja de recursos con sprites de cuerpo completo)."""
import sys, os, json, glob
import numpy as np
from PIL import Image, ImageFilter
from scipy import ndimage as ndi

SRC = sys.argv[1] if len(sys.argv) > 1 else "."
OUT = os.path.join(os.path.dirname(__file__), "..", "ART")


def src(prefix):
    return Image.open(glob.glob(os.path.join(SRC, prefix + "*"))[0]).convert("RGB")


def save(im, rel, q=88):
    p = os.path.join(OUT, rel)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    if rel.endswith(".png"):
        im.save(p, optimize=True)
    else:
        im.convert("RGB").save(p, quality=q, optimize=True)


def up(im, f=2, blur=0.0):
    im = im.resize((im.width * f, im.height * f), Image.LANCZOS)
    return im.filter(ImageFilter.GaussianBlur(blur)) if blur else im


i1, i7, i6 = src("85bad597"), src("68d710a9"), src("52002455")
meta = {"portraits": [], "sprites": [], "backdrops": {}, "fx": [], "gear": {}}

# ---------------- retratos (33) ----------------
rows = [(12, 136), (142, 264), (272, 396)]
for r, (y0, y1) in enumerate(rows):
    for c in range(11):
        x = int(368 + c * 103.4)
        t = i1.crop((x + 3, y0 + 2, x + 99, y0 + 128)).resize((288, 378), Image.LANCZOS)
        save(t, f"portraits/p{r * 11 + c:02d}.jpg", 90)
        a = np.asarray(t).astype(float)
        face = a[40:130, 90:200].reshape(-1, 3)           # zona de la cara
        # color de piel: media de los píxeles más "piel" (rojizos, no muy oscuros)
        m = (face[:, 0] > face[:, 2] + 10) & (face[:, 0] > 60)
        skin = face[m].mean(0) if m.sum() > 50 else face.mean(0)
        low = a[210:, :].reshape(-1, 3)                    # mitad baja: guantes
        meta["portraits"].append({"i": r * 11 + c, "skin": [int(v) for v in skin], "fem": (r * 11 + c) in (11, 12, 25, 27, 32)})
        # clasificación de color de guante por conteo
        import colorsys
        cnt = {}
        for px in low[::3]:
            h, s, v = colorsys.rgb_to_hsv(*(px / 255.0))
            if v < 0.16: k = "black"
            elif s < 0.16 and v > 0.82: k = "white"
            elif s > 0.6 and v > 0.4:
                d = h * 360
                k = "red" if (d < 12 or d > 340) else ("gold" if 38 < d < 62 else ("green" if 90 < d < 160 else ("blue" if 195 < d < 255 else "other")))
            else: continue
            cnt[k] = cnt.get(k, 0) + 1
        cnt.pop("other", None)
        meta["portraits"][-1]["glove"] = max(cnt, key=cnt.get) if cnt else "red"

# ---------------- sprites de cuerpo completo (hoja 6) ----------------
Y0S, Y1S = 509, 892
reg = np.asarray(i6.crop((0, Y0S, 1385, Y1S))).astype(int)
sat = reg.max(2) - reg.min(2)
cand = (sat < 16) & (reg.max(2) > 150)                     # tablero de ajedrez gris/blanco
lab, n = ndi.label(cand)
border = set(np.unique(np.r_[lab[0], lab[-1], lab[:, 0], lab[:, -1]])) - {0}
bg = np.isin(lab, list(border))
fg = ndi.binary_fill_holes(~bg)
core = ndi.binary_opening(fg, structure=np.ones((3, 3)), iterations=4)      # rompe las uniones finas entre sprites vecinos
lab2, n2 = ndi.label(core, structure=np.ones((3, 3)))
objs = ndi.find_objects(lab2)
sizes = ndi.sum(core, lab2, range(1, n2 + 1))
cent = ndi.center_of_mass(core, lab2, range(1, n2 + 1))
big = [i + 1 for i, sz in enumerate(sizes)
       if sz >= 3500 and 110 <= (objs[i][0].stop - objs[i][0].start) <= 215 and (objs[i][1].stop - objs[i][1].start) <= 140]
# cada píxel de primer plano se reparte al núcleo más cercano (cabezas, guantes, botas que se soltaron)
dist, (iy, ix) = ndi.distance_transform_edt(~np.isin(lab2, big), return_indices=True)
owner_map = lab2[iy, ix]
full = fg & (dist <= 30) & np.isin(owner_map, big)
lab2 = np.where(full, owner_map, 0)
order = sorted(big, key=lambda b_: (0 if cent[b_ - 1][0] < 190 else 1, cent[b_ - 1][1]))
owner = {d: d for d in big}
sp_pal = []
for k, d in enumerate(order[:40]):
    m = (lab2 == d)
    ys, xs = np.where(m)
    y0, y1, x0, x1 = ys.min(), ys.max() + 1, xs.min(), xs.max() + 1
    mask = ndi.binary_erosion(m[y0:y1, x0:x1], iterations=1)
    rgb = i6.crop((x0, Y0S + y0, x1, Y0S + y1)).convert("RGBA")
    rgb.putalpha(Image.fromarray((mask * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(0.9)))
    rgb = rgb.resize((rgb.width * 2, rgb.height * 2), Image.LANCZOS)
    save(rgb, f"sprites/s{k:02d}.png")
    a = np.asarray(i6.crop((x0, Y0S + y0, x1, Y0S + y1))).astype(float)
    hh, ww = a.shape[:2]
    face = a[int(hh * .07):int(hh * .17), int(ww * .38):int(ww * .62)].reshape(-1, 3)
    sp_pal.append({"i": k, "skin": [int(v) for v in face.mean(0)]})
meta["sprites"] = sp_pal
print("sprites:", len(order), "grandes;", n2, "componentes")

# ---------------- logo ----------------
save(up(i7.crop((5, 8, 408, 300)), 1), "ui/logo.png")
logo_reg = np.asarray(i6.crop((0, 0, 396, 244))).astype(int)
c2 = ((logo_reg.max(2) - logo_reg.min(2)) < 16) & (logo_reg.max(2) > 150)
l3, _ = ndi.label(c2)
brd = set(np.unique(np.r_[l3[0], l3[-1], l3[:, 0], l3[:, -1]])) - {0}
fgl = ~np.isin(l3, list(brd))
lg = i6.crop((0, 0, 396, 244)).convert("RGBA")
lg.putalpha(Image.fromarray((ndi.binary_fill_holes(fgl) * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(0.8)))
save(lg.resize((792, 488), Image.LANCZOS), "ui/logo_alpha.png")
save(i7.crop((215, 905, 322, 985)), "ui/vs.png")
save(i7.crop((322, 818, 480, 905)), "ui/glove_red.png")

# ---------------- escenarios ----------------
BD = {}
def bd(name, im, box, blur=0.9):
    t = up(im.crop(box), 3, blur)
    save(t, f"backdrops/{name}.jpg", 86)
    BD[name] = f"backdrops/{name}.jpg"

# hoja 6
bd("crowd_blue", i6, (399, 0, 664, 243)); bd("crowd_screen", i6, (669, 0, 942, 243))
bd("gym_bags", i6, (947, 0, 1204, 243)); bd("locker", i6, (1209, 0, 1426, 243)); bd("tunnel", i6, (1431, 0, 1672, 243))
bd("rooftop_sunset", i6, (1367, 249, 1672, 377)); bd("arena_red_stage", i6, (302, 382, 566, 508))
bd("arena_podium", i6, (862, 382, 1252, 508)); bd("arena_dark_lights", i6, (1256, 382, 1672, 508)); bd("gym_posters", i6, (570, 382, 858, 508))
# hoja 1
xs = [(10, 306), (320, 611), (623, 914), (927, 1219), (1232, 1524)]
nm1 = [["pro", "coliseo", "gimnasio", "callejero", "estadio"], ["lujo", "desierto", "azotea", "neon", "templo"]]
for r, (y0, y1) in enumerate(((480, 603), (650, 774))):
    for c, (x0, x1) in enumerate(xs):
        bd("s1_" + nm1[r][c], i1, (x0, y0, x1, y1))
# hoja 7
xs7 = [(14, 281), (296, 557), (572, 825), (838, 1085)]
nm7 = [["ring_pro", "madison", "vegas", "mundial"], ["gym_train", "club", "urbana", "nocturno"], ["coliseo_lucha", "playa", "mega", "extrema"]]
for r, (y0, y1) in enumerate(((420, 505), (538, 622), (656, 740))):
    for c, (x0, x1) in enumerate(xs7):
        bd("s7_" + nm7[r][c], i7, (x0, y0, x1, y1))
meta["backdrops"] = BD
# color de acento por escenario (tono dominante saturado)
import colorsys
acc = {}
for k, rel in BD.items():
    a = np.asarray(Image.open(os.path.join(OUT, rel)).resize((64, 32))).reshape(-1, 3) / 255.0
    best, bs = (0.1, 0.2, 0.5), 0
    for px in a:
        h, s, v = colorsys.rgb_to_hsv(*px)
        sc = s * v
        if sc > bs: bs, best = sc, tuple(px)
    acc[k] = "#%02x%02x%02x" % tuple(int(v * 255) for v in best)
meta["accent"] = acc

# ---------------- efectos (fondo negro: se usan con mezcla aditiva) ----------------
fx = {"fx_blue_burst": (1108, 428, 1198, 498), "fx_red_burst": (1205, 428, 1297, 498), "fx_white_burst": (1303, 428, 1423, 498), "fx_fire": (1432, 424, 1528, 506),
      "fx_gold_burst": (1108, 518, 1198, 598), "fx_smoke": (1205, 524, 1297, 596), "fx_red_splat": (1303, 520, 1400, 598), "fx_claw": (1435, 524, 1525, 598),
      "fx_blue_swoosh": (1108, 606, 1252, 670), "fx_red_swoosh": (1252, 606, 1335, 670), "fx_sparks": (1338, 604, 1425, 672), "fx_stars": (1432, 608, 1528, 668)}
for k, b in fx.items():
    save(i7.crop(b), f"fx/{k}.png"); meta["fx"].append(k)
fx1 = {"fx_orange_burst": (400, 862, 485, 930), "fx_white_cloud": (545, 866, 610, 930), "fx_blue_lightning": (410, 935, 500, 995), "fx_fire2": (505, 940, 610, 1000)}
for k, b in fx1.items():
    save(i1.crop(b), f"fx/{k}.png"); meta["fx"].append(k)

# ---------------- objetos: cinturones, equipo, trofeo, banqueta ----------------
bel = {"belt_gold": (628, 862, 730, 935), "belt_green": (742, 862, 864, 937), "belt_blue": (628, 932, 730, 992), "belt_gold2": (748, 930, 866, 992)}
for k, b in bel.items(): save(i1.crop(b), f"gear/{k}.png")
gl = {"glove_red": (888, 853, 944, 912), "glove_blue": (944, 853, 1002, 912), "glove_black_red": (1002, 853, 1058, 912), "glove_black": (1058, 853, 1112, 912), "glove_white": (1112, 853, 1166, 912), "glove_white2": (1166, 853, 1214, 912)}
for k, b in gl.items(): save(i1.crop(b), f"gear/{k}.png")
sh = {"shorts_red": (808, 822, 880, 905), "shorts_blue": (884, 822, 960, 905), "shorts_black": (968, 822, 1042, 905), "shorts_gold": (1048, 822, 1125, 905), "shorts_white": (1128, 822, 1210, 905),
      "shorts_blue2": (806, 910, 880, 990), "shorts_goldblack": (886, 910, 960, 990)}
for k, b in sh.items(): save(i7.crop(b), f"gear/{k}.png")
bo = {"boots_red": (962, 915, 1010, 990), "boots_blue": (1012, 915, 1058, 990), "boots_white": (1062, 915, 1108, 990), "boots_gold": (1112, 915, 1160, 990), "boots_black": (1164, 915, 1212, 990)}
for k, b in bo.items(): save(i7.crop(b), f"gear/{k}.png")
for k, b in {"stool": (1238, 686, 1296, 772), "bag": (1308, 676, 1352, 772), "towel": (1370, 680, 1412, 772), "bottle": (1424, 690, 1456, 772), "trophy": (1462, 680, 1520, 772), "belt_wbc": (1108, 688, 1226, 756)}.items():
    save(i7.crop(b), f"gear/{k}.png")
save(i7.crop((1236, 826, 1396, 900)), "ui/podium_map.jpg")
save(i7.crop((1412, 826, 1520, 900)), "ui/trophy_big.jpg")
meta["gear"] = {"gloves": list(gl), "shorts": list(sh), "boots": list(bo), "belts": list(bel)}

json.dump(meta, open(os.path.join(OUT, "art_meta.json"), "w"), indent=1)
print("ok", sum(len(f) for _, _, f in os.walk(OUT)), "archivos")
