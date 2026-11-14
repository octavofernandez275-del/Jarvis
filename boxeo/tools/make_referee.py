"""Dibuja al árbitro (4 fotogramas de 360x330) en estilo ilustrado: camisa blanca, pajarita, pantalón oscuro.
Salida: ART/anim/referee.png. Uso (desde boxeo/): python3 -I tools/make_referee.py"""
import math
from PIL import Image, ImageDraw, ImageFilter

CW, CH, SS = 360, 330, 3
SKIN, SKIN_D = (206, 160, 124), (160, 112, 84)
SHIRT, SHIRT_D = (244, 244, 246), (178, 182, 196)
PANTS, PANTS_D = (30, 30, 38), (14, 14, 20)
OUT = (12, 10, 16)
HAIR = (44, 32, 26)


def limb(d, p0, p1, w, col, col_d):
    ang = math.atan2(p1[1] - p0[1], p1[0] - p0[0])
    nx, ny = -math.sin(ang), math.cos(ang)
    for ww, c in ((w + 5 * SS, OUT), (w, col)):
        poly = [(p0[0] + nx * ww / 2, p0[1] + ny * ww / 2), (p1[0] + nx * ww / 2, p1[1] + ny * ww / 2),
                (p1[0] - nx * ww / 2, p1[1] - ny * ww / 2), (p0[0] - nx * ww / 2, p0[1] - ny * ww / 2)]
        d.polygon(poly, fill=c)
        d.ellipse((p0[0] - ww / 2, p0[1] - ww / 2, p0[0] + ww / 2, p0[1] + ww / 2), fill=c)
        d.ellipse((p1[0] - ww / 2, p1[1] - ww / 2, p1[0] + ww / 2, p1[1] + ww / 2), fill=c)
    sh = w * 0.28   # sombra del lado inferior
    d.line([(p0[0] + nx * w * 0.32, p0[1] + ny * w * 0.32), (p1[0] + nx * w * 0.32, p1[1] + ny * w * 0.32)], fill=col_d, width=int(sh))


def frame(pose):
    im = Image.new('RGBA', (CW * SS, CH * SS), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    s = SS
    cx = int(CW * 0.36) * s + pose.get('dx', 0) * s
    foot = (CH - 6) * s
    lean = pose.get('lean', 0) * s
    # piernas (pantalón)
    hip = (cx - 6 * s, foot - 108 * s)
    kneeA, footA = (cx - 22 * s, foot - 52 * s), (cx - 30 * s, foot - 4 * s)
    kneeB, footB = (cx + 14 * s, foot - 54 * s), (cx + 26 * s, foot - 4 * s)
    limb(d, hip, kneeA, 30 * s, PANTS, PANTS_D); limb(d, kneeA, footA, 24 * s, PANTS, PANTS_D)
    limb(d, hip, kneeB, 30 * s, PANTS, PANTS_D); limb(d, kneeB, footB, 24 * s, PANTS, PANTS_D)
    for fx in (footA, footB):                      # zapatos
        d.ellipse((fx[0] - 16 * s, fx[1] - 8 * s, fx[0] + 28 * s, fx[1] + 6 * s), fill=OUT)
        d.ellipse((fx[0] - 13 * s, fx[1] - 6 * s, fx[0] + 25 * s, fx[1] + 4 * s), fill=(24, 24, 28))
    # tronco (camisa) inclinado
    sh_c = (cx + lean, foot - 190 * s)
    torso = [(cx - 28 * s + lean, foot - 196 * s), (cx + 30 * s + lean, foot - 196 * s), (cx + 24 * s, foot - 104 * s), (cx - 24 * s, foot - 104 * s)]
    d.polygon([(x + (3 * s if i < 2 else 0), y) for i, (x, y) in enumerate(torso)], fill=OUT)
    d.polygon(torso, fill=SHIRT)
    d.polygon([(torso[0][0], torso[0][1]), (torso[0][0] + 14 * s, torso[0][1]), (torso[3][0] + 10 * s, torso[3][1]), (torso[3][0], torso[3][1])], fill=SHIRT_D)
    d.rectangle((cx - 26 * s, foot - 110 * s, cx + 26 * s, foot - 100 * s), fill=PANTS)        # cinturón
    for yy in (150, 135):                                                                    # pliegues
        d.line([(cx - 14 * s + lean, foot - yy * s), (cx + 12 * s + lean, foot - (yy - 4) * s)], fill=SHIRT_D, width=2 * s)
    # cuello y cabeza
    nk = (cx + 4 * s + lean, foot - 204 * s)
    d.rectangle((nk[0] - 9 * s, nk[1] - 10 * s, nk[0] + 9 * s, nk[1] + 8 * s), fill=SKIN_D, outline=OUT)
    hx, hy = nk[0] + 4 * s, nk[1] - 30 * s
    d.ellipse((hx - 25 * s, hy - 30 * s, hx + 25 * s, hy + 30 * s), fill=OUT)
    d.ellipse((hx - 22 * s, hy - 27 * s, hx + 22 * s, hy + 27 * s), fill=SKIN)
    d.pieslice((hx - 22 * s, hy - 27 * s, hx + 22 * s, hy + 12 * s), 180, 360, fill=HAIR)            # pelo corto
    d.ellipse((hx - 25 * s, hy - 8 * s, hx - 15 * s, hy + 8 * s), fill=SKIN_D)                        # oreja
    d.ellipse((hx + 20 * s, hy - 2 * s, hx + 28 * s, hy + 8 * s), fill=SKIN)                          # nariz
    d.line([(hx + 5 * s, hy - 7 * s), (hx + 16 * s, hy - 9 * s)], fill=HAIR, width=3 * s)           # ceja
    d.ellipse((hx + 10 * s, hy - 5 * s, hx + 14 * s, hy - 1 * s), fill=OUT)                          # ojo
    d.line([(hx + 4 * s, hy + 16 * s), (hx + 16 * s, hy + 15 * s)], fill=SKIN_D, width=2 * s)        # boca
    # pajarita
    d.polygon([(nk[0] + 2 * s, nk[1] + 12 * s), (nk[0] - 12 * s, nk[1] + 6 * s), (nk[0] - 12 * s, nk[1] + 20 * s)], fill=PANTS)
    d.polygon([(nk[0] + 2 * s, nk[1] + 12 * s), (nk[0] + 16 * s, nk[1] + 6 * s), (nk[0] + 16 * s, nk[1] + 20 * s)], fill=PANTS)
    # brazos: (hombro) -> codo -> mano, ángulos por pose
    for (shx, a1, a2, front) in ((cx - 24 * s + lean, pose['bA'][0], pose['bA'][1], False), (cx + 26 * s + lean, pose['bB'][0], pose['bB'][1], True)):
        sp = (shx, foot - 186 * s)
        el = (sp[0] + 46 * s * math.cos(math.radians(a1)), sp[1] + 46 * s * math.sin(math.radians(a1)))
        wr = (el[0] + 44 * s * math.cos(math.radians(a2)), el[1] + 44 * s * math.sin(math.radians(a2)))
        limb(d, sp, el, 22 * s, SHIRT, SHIRT_D); limb(d, el, wr, 19 * s, SHIRT, SHIRT_D)
        d.ellipse((wr[0] - 10 * s, wr[1] - 10 * s, wr[0] + 10 * s, wr[1] + 10 * s), fill=OUT)
        d.ellipse((wr[0] - 8 * s, wr[1] - 8 * s, wr[0] + 8 * s, wr[1] + 8 * s), fill=SKIN)
    im = im.resize((CW, CH), Image.LANCZOS)
    return im


POSES = [
    {'bA': (95, 70), 'bB': (80, 60)},                                  # de pie, brazos algo flexionados
    {'bA': (88, 60), 'bB': (92, 72), 'dx': 2, 'lean': 2},              # leve balanceo
    {'bA': (95, 70), 'bB': (-60, -85), 'lean': 1},                     # cuenta: brazo arriba
    {'bA': (95, 70), 'bB': (-35, -80), 'lean': 1},                     # cuenta: brazo arriba (alterno)
]
atlas = Image.new('RGBA', (CW * len(POSES), CH), (0, 0, 0, 0))
for i, p in enumerate(POSES):
    atlas.paste(frame(p), (i * CW, 0))
atlas.save('ART/anim/referee.png')
print('árbitro listo', atlas.size)
