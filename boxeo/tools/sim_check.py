"""Réplica en Python de la lógica de combate e IA (boxer.gd + fight_manager.gd + boxer_ai.gd) para comprobar equilibrio y comportamiento
sin necesitar Godot. NO es el código del juego: es una verificación de las reglas y los números."""
import json, os, random, sys
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
T = json.load(open(f"{ROOT}/ANIMATIONS/animation_table.json", encoding="utf-8"))
P = json.load(open(f"{ROOT}/OPPONENTS/ai_profiles.json", encoding="utf-8"))
F = {f["id"]: f for f in json.load(open(f"{ROOT}/CHARACTERS/fighters.json", encoding="utf-8"))["fighters"]}
ATK = T["attacks"]; DEF = T["defense"]; MOV = T["movement"]
IDLE, MOVE, ATTACK, BLOCK, DODGE, HIT, STAGGER, DOWN, GETUP = range(9)

class Boxer:
    def __init__(s, f, z, face, ai=None):
        s.f = f; s.st = f["stats"]; s.z = z; s.face = face; s.state = IDLE; s.health = 100.0; s.stamina = 100.0
        s.stun = 0; s.cur = ""; s.t = 0; s.hit_done = False; s.aid = 0; s.ts = 0.85 + s.st["speed"] / 100 * 0.35
        s.dt_ = 0; s.want_block = False; s.move = 0.0; s.kd = 0; s.ai = ai; s.opp = None
    def rng(s, n): return ATK[n]["range"] * s.f["reach_cm"] / 180
    def phase(s):
        if s.state != ATTACK: return ""
        a = ATK[s.cur]
        return "startup" if s.t < a["startup"] else ("active" if s.t < a["startup"] + a["active"] else "recovery")
    def can_act(s):
        if s.state in (IDLE, MOVE, BLOCK): return True
        if s.state == ATTACK:
            a = ATK[s.cur]; tot = a["startup"] + a["active"] + a["recovery"]
            return a["startup"] + a["active"] + a["recovery"] * 0.55 <= s.t < tot
        return False
    def stunned(s): return s.state in (HIT, STAGGER, DOWN, GETUP)
    def dist(s): return abs(s.opp.z - s.z)
    def attack(s, n):
        if not s.can_act() or s.stamina < 3: return False
        a = ATK[n]; s.cur = n; s.t = 0; s.hit_done = False; s.aid += 1; s.state = ATTACK; s.stamina = max(0, s.stamina - a["stamina"]); s.on_action(n); return True
    def dodge(s, side):
        if not s.can_act() or s.stamina < 6: return False
        s.state = DODGE; s.dt_ = 0; s.cur = ""; s.stamina = max(0, s.stamina - DEF["dodge_stamina"]); s.on_action(side); return True
    def dodge_active(s): return s.state == DODGE and DEF["dodge_iframes"][0] <= s.dt_ <= DEF["dodge_iframes"][1]
    def move_z(s, dz):
        nz = max(-MOV["ring_half"], min(MOV["ring_half"], s.z + dz))
        side = 1 if s.z > s.opp.z else -1
        if abs(nz - s.opp.z) < MOV["min_separation"]: nz = s.opp.z + side * MOV["min_separation"]
        s.z = max(-MOV["ring_half"], min(MOV["ring_half"], nz))
    def update(s, dt, M):
        if s.ai: s.ai.think(dt)
        regen = {IDLE: 14, MOVE: 8, BLOCK: 6}.get(s.state, 2) if s.state != DOWN else 0
        s.stamina = min(100, s.stamina + regen * dt * (0.6 + s.st["stamina"] / 150))
        if s.state in (IDLE, MOVE):
            if s.want_block: s.state = BLOCK; s.on_action("block"); return
            foot = (0.85 + s.st["footwork"] / 100 * 0.4) * (s.ai.p.get("speed", 1) if s.ai else 1)
            if abs(s.move) > .1:
                s.state = MOVE; v = MOV["forward"] if s.move > 0 else -MOV["back"]; s.move_z(s.face * v * foot * dt)
            else: s.state = IDLE
        elif s.state == BLOCK:
            if not s.want_block: s.state = IDLE
        elif s.state == ATTACK:
            a = ATK[s.cur]; s.t += dt * s.ts
            if not s.hit_done and s.t >= a["startup"]: s.hit_done = True; M.resolve(s, s.cur)
            if s.t >= a["startup"] + a["active"] + a["recovery"]: s.state = IDLE; s.cur = ""
        elif s.state == DODGE:
            s.dt_ += dt
            if s.dt_ >= DEF["dodge_duration"]: s.state = IDLE
        elif s.state in (HIT, STAGGER, GETUP):
            s.stun -= dt
            if s.stun <= 0: s.state = IDLE
    def on_action(s, a): s.M.action(s, a)

class AI:
    ATT = ["jab", "cross", "lead_hook", "rear_uppercut", "body_hook"]
    def __init__(s, me, foe, prof, skill):
        s.me, s.foe, s.p, s.skill = me, foe, prof, skill; s.hist = []; s.trans = {}; s.w = {a: 1.0 for a in s.ATT}; s.danger = {}
        s.fs = dict(attacks=0, blocks=0, dodges=0); s.clock = 0; s.think_t = .5; s.combo = []; s.gap = 0; s.rid = -1; s.cid = -1
        s.rc = ""; s.rd = 0; s.cp = False; s.gt = 0; s.go = False; s.ant = ""; s.antu = 0; s.bb = 0
        s.pred_ok = s.pred_n = 0
    def count(s, c, n): s.trans.setdefault(c, {}); s.trans[c][n] = s.trans[c].get(n, 0) + 1
    def predict(s):
        best = ("", 0.0); cx = []
        if len(s.hist) >= 2: cx.append(s.hist[-2] + ">" + s.hist[-1])
        if len(s.hist) >= 1: cx.append(s.hist[-1])
        for c in cx:
            if c not in s.trans: continue
            tot = sum(s.trans[c].values())
            if tot < 3: continue
            for k, v in s.trans[c].items():
                if v / tot > best[1]: best = (k, v / tot)
            if best[1] > 0: return best
        return best
    def observe(s, a):
        pr = s.predict()
        if pr[0]: s.pred_n += 1; s.pred_ok += (pr[0] == a)
        if len(s.hist) >= 2: s.count(s.hist[-2] + ">" + s.hist[-1], a)
        if len(s.hist) >= 1: s.count(s.hist[-1], a)
        s.hist.append(a); s.hist = s.hist[-40:]
        if a in s.ATT: s.fs["attacks"] += 1
        elif a == "block": s.fs["blocks"] += 1
        elif a.startswith("dodge"): s.fs["dodges"] += 1
        p = s.predict()
        if p[1] >= .4 and random.random() < s.p["learning"] * (.4 + s.skill * .6): s.ant = p[0]; s.antu = s.clock + 1.2
    def result(s, a, o):
        r = .12 * s.p["learning"] * (.5 + s.skill)
        s.w[a] += {"hit": 2 * r, "blocked": -r, "dodged": -1.5 * r}.get(o, 0); s.w[a] = max(.3, min(3, s.w[a]))
    def got_hit(s, a): s.danger[a] = s.danger.get(a, 0) + 1
    def think(s, dt):
        s.clock += dt; me, foe = s.me, s.foe
        if me.stunned(): me.move = 0; return
        d = me.dist()
        # reacción
        thr = foe.cur if (foe.state == ATTACK and foe.phase() == "startup" and d <= foe.rng(foe.cur) * 1.15) else ""
        if thr and foe.aid != s.rid:
            s.rid = foe.aid; tl = ATK[thr]["startup"] - foe.t
            rt = s.p["react_time"] * (1.3 - s.skill) * .55; pred = s.ant == thr and s.clock < s.antu
            if pred: rt *= .4
            p = s.p["reaction"] * (.4 + .6 * s.skill) + (.25 * s.p["learning"] if pred else 0) + min(.2, s.danger.get(thr, 0) * .04)
            if rt < tl * .9 and random.random() < min(.95, max(0, p)):
                dp, bp = s.p["dodge_pref"], s.p["block_pref"]
                s.rc = "dodge" if random.random() * (dp + bp) < dp else "block"; s.rd = rt
                s.cp = random.random() < s.p["counter"] * (.5 + s.skill)
            else: s.rc = ""
        if s.rc:
            s.rd -= dt
            if s.rd <= 0:
                if s.rc == "dodge": me.dodge("dodge_left" if random.random() < .5 else "dodge_right")
                else: s.go = True; s.gt = .5; me.want_block = True
                s.rc = ""
            return
        # guardia
        if me.state == ATTACK or s.combo: me.want_block = False
        else:
            s.gt -= dt
            if s.gt <= 0:
                ex = .25 if me.stamina < s.p["stamina_rest"] else 0
                s.go = random.random() < s.p["guard_hold"] + ex and d < 1.9; s.gt = random.uniform(.5, 1.2)
            me.want_block = s.go
        if me.state == ATTACK and not s.combo: me.move = 0; return
        press = s.p["pressure"]; rest = me.stamina < s.p["stamina_rest"]
        if foe.state == ATTACK and foe.phase() == "recovery" and foe.aid != s.cid:
            s.cid = foe.aid; pc = s.p["counter"] * (.4 + .6 * s.skill) + (.25 if s.cp else 0)
            if not rest and random.random() < pc and s.start(d): s.cp = False; return
        if s.combo:
            s.gap -= dt
            if s.gap <= 0 and me.can_act():
                if d <= me.rng(s.combo[0]) * 1.08: s.fire()
                else: s.combo = []
            me.move = 0; return
        s.think_t -= dt
        if s.think_t <= 0:
            s.think_t = s.p["tempo"] * (1.35 - .6 * s.skill) * random.uniform(.7, 1.3)
            ag = max(.05, min(.98, s.p["aggression"] * (.6 + .6 * s.skill) + s.bb))
            if not rest and random.random() < ag: s.start(d)
        des = 1.9 if rest else s.p["range_pref"]; sl = .5 + (.08 - .5) * press; mv = 0.0
        if d > des + sl: mv = 1.0
        elif d < des - .2 and (press < .7 or rest): mv = -1.0
        me.move = mv
    def start(s, d):
        opts = []
        br = s.fs["blocks"] / max(1, sum(s.fs.values()))
        for c in s.p["combos"]:
            if d > s.me.rng(c["seq"][0]) * 1.02: continue
            w = c["w"] * sum(s.w[a] for a in c["seq"]) / len(c["seq"])
            if br > .4 and ("body_hook" in c["seq"] or "rear_uppercut" in c["seq"]): w *= 1.5
            opts.append((c["seq"], w))
        if not opts: return False
        r = random.random() * sum(w for _, w in opts)
        for seq, w in opts:
            r -= w
            if r <= 0: s.combo = list(seq); break
        else: s.combo = list(opts[0][0])
        s.fire(); return True
    def fire(s):
        n = s.combo.pop(0); s.me.want_block = False
        if s.me.attack(n): s.gap = random.uniform(0, .1) * (1.4 - s.skill)
        else: s.combo = []

class Bot:
    """Jugador simulado: patrón fijo jab-cross con algo de azar, se tapa a veces."""
    def __init__(s, me, patt): s.me = me; s.patt = patt; s.i = 0; s.t = 0; s.p = {"speed": 1}
    def think(s, dt):
        me = s.me; s.t -= dt
        d = me.dist(); me.move = 1.0 if d > 1.0 else (-1.0 if d < .8 else 0.0)
        me.want_block = random.random() < .002 or (me.want_block and random.random() < .9)
        if s.t <= 0 and me.can_act() and d < 1.1:
            if random.random() < .1: me.want_block = True; s.t = .5; return
            me.attack(s.patt[s.i % len(s.patt)]); s.i += 1; s.t = random.uniform(.15, .5)

class Match:
    def __init__(s, fa, fb, skill, patt=None, secs=180, ai_a=False):
        s.a = Boxer(fa, -1.0, 1); s.b = Boxer(fb, 1.0, -1); s.a.opp, s.b.opp = s.b, s.a
        for x in (s.a, s.b): x.M = s
        s.b.ai = AI(s.b, s.a, P["profiles"][fb["ai_profile"]], skill)
        s.a.ai = Bot(s.a, patt or ["jab", "cross"]) if not ai_a else AI(s.a, s.b, P["profiles"][fa["ai_profile"]], skill)
        s.secs = secs; s.land = [0, 0]; s.thr = [0, 0]; s.out = {"hit": [0, 0], "blocked": [0, 0], "dodged": [0, 0], "whiff": [0, 0]}; s.kds = [0, 0]; s.over = None
    def action(s, bx, a):
        if a in ATK: s.thr[0 if bx is s.a else 1] += 1
        if bx is s.a and isinstance(s.b.ai, AI): s.b.ai.observe(a)
        if bx is s.b and isinstance(s.a.ai, AI): s.a.ai.observe(a)
    def resolve(s, att, n):
        df = s.b if att is s.a else s.a; i = 0 if att is s.a else 1; atk = ATK[n]; head = atk["target"] == "head"
        def note(o):
            s.out[o][i] += 1
            if isinstance(att.ai, AI): att.ai.result(n, o)
        if att.dist() > att.rng(n) or df.state in (DOWN, GETUP): return note("whiff")
        if df.dodge_active(): df.stamina = max(0, df.stamina - 2); return note("dodged")
        dmg = atk["damage"] * (.6 + att.st["power"] / 100 * .8) * (.75 if att.stamina < 15 else 1)
        if df.state == BLOCK:
            bm = DEF["block_damage_mult"] if head else DEF["block_body_mult"]
            df.stamina = max(0, df.stamina - dmg * DEF["block_stamina_mult"]); df.health = max(0, df.health - dmg * bm * .3); return note("blocked")
        clean = random.random() < max(.3, min(.95, .55 + att.st["accuracy"] / 200 - df.st["defense"] / 400))
        counter = df.state == ATTACK and df.phase() != "recovery"
        dmg *= (1 if clean else .5) * (1.3 if counter else 1)
        hd = dmg * (1.25 - df.st["chin"] / 200) * (1 if head else .45); sd = 0 if head else dmg * 1.6
        newh = df.health - hd; knock = newh <= 0; stag = (not knock) and dmg >= 9 and newh < 30 and random.random() < .35
        note("hit"); s.land[i] += 1
        if isinstance(df.ai, AI): df.ai.got_hit(n)
        df.health = max(0, df.health - hd); df.stamina = max(0, df.stamina - sd); df.cur = ""
        if knock:
            df.state = DOWN; df.kd += 1; s.kds[0 if df is s.a else 1] += 1; df.down_t = 0
            return
        df.state = STAGGER if stag else HIT; df.stun = 1.1 if stag else atk["stun"]
        df.move_z(-df.face * (.1 + dmg * .01))
    def run(s):
        dt = 1 / 60; t = 0
        while t < s.secs and not s.over:
            for x in (s.a, s.b):
                if x.state == DOWN:
                    x.down_t += dt
                    if x.down_t > 4.0 + 0.5 * x.kd:
                        if s.kds[0 if x is s.a else 1] >= 3: s.over = "TKO " + ("B" if x is s.a else "A")
                        else: x.state = IDLE; x.health = 28 + x.st["chin"] * .1
                else: x.update(dt, s)
            t += dt
        return t

def run(profile_fighter, skill, patt, n=40, secs=180):
    R = dict(hit=[0, 0], blocked=[0, 0], dodged=[0, 0], whiff=[0, 0], kd=[0, 0], hp=[0, 0], pred=0, predn=0, ko=0, thr=[0, 0])
    for _ in range(n):
        m = Match(F["kai_morrow"], F[profile_fighter], skill, patt, secs); m.run()
        for k in ("hit", "blocked", "dodged", "whiff"):
            for j in (0, 1): R[k][j] += m.out[k][j]
        for j in (0, 1): R["kd"][j] += m.kds[j]; R["hp"][j] += m.a.health if j == 0 else m.b.health; R["thr"][j] += m.thr[j]
        R["pred"] += m.b.ai.pred_ok; R["predn"] += m.b.ai.pred_n; R["ko"] += 1 if m.over else 0
    return {k: ([round(x / n, 1) for x in v] if isinstance(v, list) else round(v / n, 2)) for k, v in R.items()}

if __name__ == "__main__":
    random.seed(1)
    print("Jugador simulado (patrón jab-cross) vs IA, 180 s, media de 40 combates. Listas = [jugador, IA]")
    for pf in ("tito_vargas", "kai_morrow", "domingo_reyes", "mateo_brandt", "sora_tanaka", "rafa_cortez"):
        for sk in (0.3, 0.8):
            r = run(pf, sk, ["jab", "cross"])
            acc = round(100 * r["pred"] / r["predn"]) if r["predn"] else 0
            print(f"{pf:14s} {F[pf]['ai_profile']:15s} skill={sk}: lanzados={r['thr']} conectados={r['hit']} bloq={r['blocked']} esq={r['dodged']} fallos={r['whiff']} caidas={r['kd']} vidaFinal={r['hp']} KOs={r['ko']} predicciones_acertadas={acc}%")
