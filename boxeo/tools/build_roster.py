"""Genera CHARACTERS/fighters.json (33 boxeadores con retrato y sprite) y amplía ARENAS/arenas.json con los fondos de ART/.
Uso (desde boxeo/): python3 -I tools/build_roster.py   Los nombres son ficticios."""
import json, random, copy, colorsys
import numpy as np
from PIL import Image
from scipy.optimize import linear_sum_assignment

M = json.load(open('ART/art_meta.json'))
F = json.load(open('CHARACTERS/fighters.json'))
old = F['fighters']
rng = random.Random(7)

def hexc(rgb): return '#%02x%02x%02x' % tuple(int(x) for x in rgb)
def h2rgb(h): h = h.lstrip('#'); return [int(h[i:i+2], 16) for i in (0, 2, 4)]

P = M['portraits']; S = M['sprites']
# --- retrato <-> boxeador existente por parecido de piel
cost = np.array([[np.linalg.norm(np.array(h2rgb(o['look']['skin'])) - np.array(p['skin'])) + (0 if not p['fem'] else 400) for p in P] for o in old])
r, c = linear_sum_assignment(cost)
port_of = {old[i]['id']: int(j) for i, j in zip(r, c)}
used = set(port_of.values())
free = [i for i in range(len(P)) if i not in used]

male = ["Marco Valdés", "Idris Kamara", "Tomás Aguirre", "Viktor Hale", "Andre Dubois", "Haruto Mori", "Emil Sandoval", "Kofi Mensah",
        "Luca Ferraro", "Dante Ibarra", "Ismael Cruz", "Rurik Volkov", "Matías Quiroga", "Jamal Whitaker", "Hugo Barrientos", "Nikolai Petrov",
        "Samir Haddad", "Ezequiel Rojas", "Callum Reid", "Tariq Nasser", "Joaquín Mena", "Bastian Roth", "Omar Delgado", "Elías Navarro"]
female = ["Valeria Cruz", "Anika Sørensen", "Camila Ortega", "Mei Lin Zhao", "Rosa Ibáñez"]
nick_m = ["El Martillo", "La Sombra", "Piedra", "El Zorro", "Rayo", "El Tigre", "Hielo", "El Profeta", "Cobra", "El Toro", "Fénix", "Yunque", "El Relámpago", "Lobo", "Acero", "El Halcón", "Tormenta", "El Cirujano", "Furia", "Roca", "Dinamita", "El Fantasma", "Garra", "Titán"]
nick_f = ["La Pantera", "Estrella", "La Leona", "Dragona", "Tempestad"]
countries = ["México", "Argentina", "España", "Colombia", "Cuba", "Puerto Rico", "EE. UU.", "Reino Unido", "Rusia", "Ucrania", "Japón", "Nigeria", "Ghana", "Brasil", "Chile", "Italia", "Francia", "Alemania", "Filipinas", "Corea del Sur", "Kazajistán", "Tailandia", "Australia", "Irlanda"]
classes = ["pluma", "ligero", "welter", "superwelter", "medio", "supermedio", "semipesado", "crucero", "pesado", "gallo"]
heights = {"gallo": 165, "pluma": 168, "ligero": 172, "welter": 176, "superwelter": 179, "medio": 182, "supermedio": 185, "semipesado": 188, "crucero": 191, "pesado": 195}
weights = {"gallo": 54, "pluma": 57, "ligero": 61, "welter": 67, "superwelter": 70, "medio": 73, "supermedio": 76, "semipesado": 80, "crucero": 90, "pesado": 105}
profiles = {
    "aggressive": dict(power=80, speed=68, stamina=72, chin=68, defense=50, accuracy=64, footwork=60, ring_iq=54),
    "technical": dict(power=60, speed=74, stamina=70, chin=62, defense=70, accuracy=80, footwork=76, ring_iq=76),
    "pressure": dict(power=72, speed=64, stamina=86, chin=74, defense=56, accuracy=62, footwork=58, ring_iq=62),
    "durable": dict(power=66, speed=54, stamina=80, chin=88, defense=58, accuracy=58, footwork=50, ring_iq=60),
    "counterpuncher": dict(power=66, speed=76, stamina=66, chin=60, defense=78, accuracy=76, footwork=74, ring_iq=80),
    "defensive": dict(power=52, speed=66, stamina=74, chin=70, defense=86, accuracy=66, footwork=72, ring_iq=72),
    "fast": dict(power=54, speed=90, stamina=68, chin=56, defense=68, accuracy=74, footwork=86, ring_iq=64),
    "strategic": dict(power=64, speed=68, stamina=72, chin=66, defense=72, accuracy=72, footwork=70, ring_iq=88),
}
bios = {
    "aggressive": "Sale a buscar el nocaut desde el primer campanazo y no da respiro.",
    "technical": "Boxeo limpio y preciso: gana los rounds con el jab y los ángulos.",
    "pressure": "Camina hacia delante sin parar y desgasta al rival round tras round.",
    "durable": "Aguanta lo que le echen y responde con golpes pesados al cuerpo.",
    "counterpuncher": "Espera el error y lo castiga con un contragolpe seco.",
    "defensive": "Cierra la guardia, esquiva y contesta solo cuando está seguro.",
    "fast": "Manos y piernas veloces: entra, golpea y desaparece.",
    "strategic": "Estudia al rival y cambia el plan según cómo vaya el combate.",
}
gloves_pal = ["#c62828", "#1565c0", "#1c1c1c", "#f2f2f2", "#d4af37", "#7b1fa2"]
shorts_pal = {"red": "#c62828", "blue": "#1b4fc4", "black": "#1a1a1a", "gold": "#d4a017", "white": "#f0f0f0"}

def sprite_shorts(idx):
    im = np.array(Image.open('ART/sprites/s%02d.png' % idx).convert('RGBA')).astype(float)
    h = im.shape[0]
    reg = im[int(h * 0.40):int(h * 0.62)]
    reg = reg[reg[..., 3] > 200][:, :3]
    if len(reg) < 30: return "#1a1a1a"
    sat = np.array([colorsys.rgb_to_hsv(*(p / 255))[1] for p in reg[::3]])
    sel = reg[::3][sat > 0.5] if (sat > 0.5).sum() > 20 else reg
    return hexc(np.median(sel, axis=0))

def hair_color(pi):
    im = np.array(Image.open('ART/portraits/p%02d.jpg' % pi).convert('RGB').resize((72, 94))).astype(float)
    reg = im[2:16, 22:50].reshape(-1, 3)
    v = reg.mean(axis=1)
    dark = reg[v <= np.percentile(v, 40)]
    return hexc(dark.mean(axis=0) * 0.9)

# --- asignar los 33 retratos a boxeadores
slots = []   # (id, portrait, base dict)
for o in old: slots.append((o['id'], port_of[o['id']], o))
mi = fi = 0
for pi in free:
    if P[pi]['fem']:
        n = female[fi]; nk = nick_f[fi]; fi += 1
    else:
        n = male[mi]; nk = nick_m[mi]; mi += 1
    slots.append((None, pi, {"name": n, "nickname": nk, "country": countries[(mi + fi) % len(countries)], "female": P[pi]['fem']}))
skin_cost = np.array([[np.linalg.norm(np.array(P[pi]['skin']) - np.array(s['skin'])) for s in S] for _, pi, _ in slots])
used_sp = {}
r2, c2 = linear_sum_assignment(skin_cost[:, :])
for i, j in zip(r2, c2): used_sp[i] = int(j)
for i in range(len(slots)):
    if i not in used_sp: used_sp[i] = int(np.argmin(skin_cost[i]))

profs = list(profiles)
out = []
for i, (fid, pi, base) in enumerate(slots):
    if fid:
        f = copy.deepcopy(base)
    else:
        name = base['name']
        prof = profs[(i * 3 + 1) % len(profs)]
        wc = classes[(i * 7 + 3) % len(classes)]
        st = {k: int(max(35, min(95, v + rng.randint(-7, 7)))) for k, v in profiles[prof].items()}
        w = rng.randint(14, 34); l = rng.randint(0, 6)
        f = {"id": name.lower().replace(' ', '_').translate(str.maketrans('áéíóúñøü', 'aeiounou')), "name": name, "nickname": base['nickname'],
             "country": base['country'], "weight_class": wc, "height_cm": heights[wc] + rng.randint(-3, 4), "weight_kg": weights[wc] + rng.randint(-1, 2),
             "reach_cm": heights[wc] + rng.randint(0, 9), "age": rng.randint(21, 36), "stance": "southpaw" if rng.random() < 0.2 else "orthodox",
             "ai_profile": prof, "stats": st, "look": {}, "record": {"w": w, "l": l, "ko": int(w * rng.uniform(0.35, 0.8))}, "bio": bios[prof]}
    f['portrait'] = "res://ART/portraits/p%02d.jpg" % pi
    f['sprite'] = "res://ART/sprites/s%02d.png" % used_sp[i]
    f['female'] = bool(P[pi]['fem'])
    look = f.get('look', {})
    look['skin'] = hexc(P[pi]['skin']) if not fid else look['skin']
    if not fid:
        look['hair'] = hair_color(pi)
        look['shoes'] = "#111111"
    look['shorts'] = M['sprites'][used_sp[i]].get('shorts_hex') or sprite_shorts(used_sp[i])
    look['gloves'] = M['sprites'][used_sp[i]].get('glove_hex') or look.get('gloves', '#c62828')
    f['look'] = look
    out.append(f)
# retratos de los 12 originales: el color de piel real sale del retrato
for f in out:
    pi = int(f['portrait'][-6:-4]); f['look']['skin'] = hexc(np.array(P[pi]['skin']) * 0.85 + np.array([20, 14, 10]))
F['fighters'] = out
F['note'] = "33 boxeadores ficticios. portrait/sprite apuntan a ART/. Generado con tools/build_roster.py."
json.dump(F, open('CHARACTERS/fighters.json', 'w'), ensure_ascii=False, indent=1)
print(len(out), 'boxeadores', len(set(f['id'] for f in out)), 'ids únicos')

# ---------------- arenas ----------------
A = json.load(open('ARENAS/arenas.json'))
arenas = A['arenas']
byid = {a['id']: a for a in arenas}
mapping = {"gym_local": "s7_gym_train", "gym_pro": "s7_club", "arena_city": "s1_pro", "arena_international": "s1_coliseo",
           "stadium": "s1_estadio", "locker_room": "locker", "entry_tunnel": "tunnel"}
for k, v in mapping.items():
    byid[k]['backdrop'] = "res://ART/backdrops/%s.jpg" % v
    byid[k]['accent'] = M['accent'][v]
new = [
    ("s1_callejero", "Callejón clandestino", "gym", "Combate de calle, sin focos."), ("s1_gimnasio", "Gimnasio clásico", "gym", "Cuerdas viejas y sudor."),
    ("s1_lujo", "Salón de lujo", "arena", "Velada de gala."), ("s1_desierto", "Arena del desierto", "stadium", "Calor, polvo y viento."),
    ("s1_azotea", "Azotea al atardecer", "gym", "Ring sobre la ciudad."), ("s1_neon", "Club de neón", "arena", "Luces violetas y música alta."),
    ("s1_templo", "Templo dorado", "arena", "Silencio antes del combate."), ("s7_madison", "Gran garden", "stadium", "El escenario más famoso."),
    ("s7_vegas", "Noche de Las Vegas", "arena", "Casino, luces y apuestas."), ("s7_mundial", "Final mundial", "stadium", "Título del mundo en juego."),
    ("s7_urbana", "Pista urbana", "gym", "Cemento y grafiti."), ("s7_nocturno", "Estadio nocturno", "stadium", "Reflectores bajo la lluvia."),
    ("s7_coliseo_lucha", "Coliseo de lucha", "stadium", "Gladiadores modernos."), ("s7_playa", "Ring en la playa", "gym", "Arena, sol y olas."),
    ("s7_mega", "Mega arena", "stadium", "Cincuenta mil gargantas."), ("s7_extrema", "Arena extrema", "arena", "Fuego y pirotecnia."),
    ("s7_ring_pro", "Ring profesional", "arena", "Luz cenital y silencio."), ("crowd_blue", "Pabellón azul", "arena", "Gradas azules."),
    ("crowd_screen", "Pabellón con pantalla", "arena", "Videomarcador gigante."), ("gym_bags", "Gimnasio de sacos", "gym", "Sacos de entrenamiento."),
    ("gym_posters", "Gimnasio de carteles", "gym", "Paredes llenas de leyendas."), ("rooftop_sunset", "Terraza del puerto", "gym", "Brisa y luces del puerto."),
    ("arena_red_stage", "Escenario rojo", "arena", "Foco rojo sobre el cuadrilátero."), ("arena_podium", "Gran podio", "arena", "Podio de campeones."),
    ("arena_dark_lights", "Arena en penumbra", "arena", "Luces tenues y humo."),
]
tmpl = {"gym": byid["gym_pro"], "arena": byid["arena_city"], "stadium": byid["stadium"]}
for bid, name, kind, note in new:
    a = copy.deepcopy(tmpl[kind])
    a.update({"id": "bg_" + bid, "name": name, "kind": kind, "notes": note, "backdrop": "res://ART/backdrops/%s.jpg" % bid, "accent": M['accent'][bid]})
    arenas.append(a)
json.dump(A, open('ARENAS/arenas.json', 'w'), ensure_ascii=False, indent=1)
print(len(arenas), 'arenas')
