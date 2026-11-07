"""Animaciones esqueléticas (keyframes de rotación por hueso). Una sola fuente de verdad:
la tabla ATTACKS se escribe tal cual en ANIMATIONS/animation_table.json y la usa el código del juego."""
import numpy as np
from rig import NAMES, ground_y, euler_q

# ---------------- tabla de golpes (tiempos en segundos) ----------------
# range = distancia máxima centro-a-centro (m) a la que conecta; target = head|body; kind = tipo de golpe (para esquivas)
ATTACKS = {
    "jab":           dict(hand="L", target="head", damage=6,  stamina=4,  startup=0.12, active=0.08, recovery=0.20, range=1.40, stun=0.22, kind="straight"),
    "cross":         dict(hand="R", target="head", damage=11, stamina=8,  startup=0.20, active=0.08, recovery=0.30, range=1.50, stun=0.35, kind="straight"),
    "lead_hook":     dict(hand="L", target="head", damage=10, stamina=7,  startup=0.22, active=0.08, recovery=0.32, range=1.20, stun=0.34, kind="hook"),
    "rear_uppercut": dict(hand="R", target="head", damage=12, stamina=9,  startup=0.24, active=0.08, recovery=0.36, range=1.05, stun=0.40, kind="uppercut"),
    "overhand":      dict(hand="R", target="head", damage=13, stamina=9,  startup=0.28, active=0.08, recovery=0.38, range=0.80, stun=0.42, kind="hook"),
    "lead_uppercut": dict(hand="L", target="head", damage=8,  stamina=6,  startup=0.18, active=0.08, recovery=0.28, range=0.66, stun=0.30, kind="uppercut"),
    "body_hook":     dict(hand="L", target="body", damage=8,  stamina=6,  startup=0.22, active=0.08, recovery=0.30, range=1.20, stun=0.30, kind="hook"),
}

# ---------------- utilidades de pose ----------------

def mir(e):
    """Espeja una rotación del lado izquierdo al derecho."""
    return (e[0], -e[1], -e[2])


def pose(base=None, _t=(0, 0, 0), **kw):
    p = dict(base or {})
    p.update({k: tuple(v) for k, v in kw.items()})
    p["_t"] = _t
    return p


def blend(a, b, f):
    out = {}
    for k in set(a) | set(b):
        if k == "_t":
            ta, tb = a.get("_t", (0, 0, 0)), b.get("_t", (0, 0, 0))
            out[k] = tuple(x + (y - x) * f for x, y in zip(ta, tb))
        else:
            ea, eb = a.get(k, (0, 0, 0)), b.get(k, (0, 0, 0))
            out[k] = tuple(x + (y - x) * f for x, y in zip(ea, eb))
    return out


def finalize(p, ground=True, aim=1.0):
    """Compensa el giro del torso para que los brazos apunten al rival y apoya los pies en el suelo."""
    q = {k: tuple(v) for k, v in p.items() if k != "_t"}
    yaw = sum(q.get(b, (0, 0, 0))[1] for b in ("Hips", "Spine", "Chest"))
    for b in ("UpperArm_L", "UpperArm_R"):
        e = q.get(b, (0, 0, 0))
        q[b] = (e[0], e[1] - yaw * aim, e[2])
    dx, dy, dz = p.get("_t", (0, 0, 0))
    if ground:
        # La altura de la cadera sale de apoyar el pie más bajo en el suelo; dy solo sirve para saltos (positivo)
        return q, (dx, 1.0 - ground_y(q, (0, 1.0, 0)) + max(0.0, dy), dz)
    return q, (dx, dy, dz)   # sin apoyo en suelo (caídas): _t es posición absoluta de la cadera


# ---------------- poses base ----------------
STAND = pose()
GUARD = pose(
    Hips=(0, -30, 0), Spine=(6, -8, 0), Chest=(4, -4, 0), Neck=(-5, 20, 0), Head=(7, 22, 0),
    UpperArm_L=(-42, 0, -8), Forearm_L=(-118, 0, 0),
    UpperArm_R=(-34, 0, 10), Forearm_R=(-125, 0, 0),
    Thigh_L=(-16, 0, 0), Shin_L=(24, 0, 0), Foot_L=(-8, 0, 0),
    Thigh_R=(14, 0, 0), Shin_R=(10, 0, 0), Foot_R=(-24, 0, 0),
)


def with_(base, **kw):
    return pose(base, _t=kw.pop("_t", base.get("_t", (0, 0, 0))), **kw)


# ---------------- golpes ----------------
JAB_WIND = with_(GUARD, Hips=(0, -34, 0), UpperArm_L=(-30, 0, -8), Forearm_L=(-135, 0, 0))
JAB_HIT = with_(GUARD, _t=(0, 0, 0.07), Hips=(0, -22, 0), Spine=(8, -10, 0), Chest=(4, -2, 0),
                UpperArm_L=(-88, 0, -2), Forearm_L=(-5, 0, 0), Head=(6, 14, 0), Neck=(-4, 14, 0),
                Thigh_L=(-24, 0, 0), Shin_L=(30, 0, 0), Foot_L=(-6, 0, 0))

CROSS_WIND = with_(GUARD, Hips=(0, -42, 0), Spine=(6, -12, 0), UpperArm_R=(-20, 0, 14), Forearm_R=(-140, 0, 0))
CROSS_HIT = with_(GUARD, _t=(0, 0, 0.10), Hips=(0, -4, 0), Spine=(10, 6, 0), Chest=(4, 2, 0),
                  UpperArm_R=(-88, 0, 2), Forearm_R=(-5, 0, 0), UpperArm_L=(-38, 0, -8), Forearm_L=(-125, 0, 0),
                  Thigh_R=(24, 0, 0), Shin_R=(30, 0, 0), Foot_R=(-50, 0, 0), Neck=(-4, 4, 0), Head=(6, 8, 0))

HOOK_HIT = with_(GUARD, _t=(0, 0, 0.05), Hips=(0, -2, 0), Spine=(8, 8, 0), Chest=(4, 4, 0),
                 UpperArm_L=(0, -60, 120), Forearm_L=(-88, 0, 0), Neck=(-4, -4, 0), Head=(6, 2, 0),
                 Thigh_L=(-20, 0, 0), Shin_L=(28, 0, 0))
HOOK_WIND = with_(blend(GUARD, HOOK_HIT, 0.7), Hips=(0, -44, 0), Spine=(6, -14, 0), Chest=(4, -6, 0), Head=(6, 24, 0), Neck=(-4, 22, 0))

UPPER_WIND = with_(GUARD, _t=(0, -0.10, 0), Hips=(0, -38, 0), Spine=(14, -10, 0),
                   UpperArm_R=(8, 0, 8), Forearm_R=(-70, 0, 0), Thigh_R=(20, 0, 0), Shin_R=(34, 0, 0), Foot_R=(-54, 0, 0))
UPPER_HIT = with_(GUARD, _t=(0, 0.02, 0.08), Hips=(0, -6, 0), Spine=(-4, 4, 0), Chest=(0, 0, 0),
                  UpperArm_R=(-62, 0, 4), Forearm_R=(-130, 0, 0), Thigh_R=(16, 0, 0), Shin_R=(12, 0, 0), Foot_R=(-28, 0, 0))

BODY_HIT = with_(GUARD, _t=(0, -0.14, 0.04), Hips=(0, -6, 0), Spine=(20, 8, 0), Chest=(6, 4, 0),
                 UpperArm_L=(3, -60, 78), Forearm_L=(-101, 0, 0), Head=(10, 6, 0), Neck=(-6, -2, 0),
                 Thigh_L=(-26, 0, 0), Shin_L=(44, 0, 0), Foot_L=(-18, 0, 0))
BODY_WIND = with_(blend(GUARD, BODY_HIT, 0.6), _t=(0, -0.08, 0), Hips=(0, -44, 0), Spine=(16, -12, 0), Chest=(6, -6, 0))

# gancho por encima (mano R, arco alto, el cuerpo se inclina) y uppercut de mano adelantada
OVER_HIT = with_(GUARD, _t=(0, -0.04, 0.09), Hips=(0, -2, 0), Spine=(16, 8, 0), Chest=(6, 4, 0),
                 UpperArm_R=(-6, 60, -125), Forearm_R=(-80, 0, 0), UpperArm_L=(-38, 0, -8), Forearm_L=(-125, 0, 0),
                 Neck=(-6, 2, 0), Head=(8, 4, 0), Thigh_R=(22, 0, 0), Shin_R=(28, 0, 0), Foot_R=(-46, 0, 0))
OVER_WIND = with_(blend(GUARD, OVER_HIT, 0.6), _t=(0, -0.06, 0), Hips=(0, -46, 0), Spine=(10, -14, 0), UpperArm_R=(-30, 50, -60), Forearm_R=(-120, 0, 0))
LUP_WIND = with_(GUARD, _t=(0, -0.10, 0), Hips=(0, -32, 0), Spine=(12, -8, 0), UpperArm_L=(8, 0, -8), Forearm_L=(-70, 0, 0),
                 Thigh_L=(-26, 0, 0), Shin_L=(40, 0, 0), Foot_L=(-14, 0, 0))
LUP_HIT = with_(GUARD, _t=(0, 0.02, 0.07), Hips=(0, -20, 0), Spine=(-2, -2, 0), UpperArm_L=(-62, 0, -4), Forearm_L=(-130, 0, 0),
                Thigh_L=(-20, 0, 0), Shin_L=(24, 0, 0), Foot_L=(-10, 0, 0))

ATTACK_POSES = {"overhand": (OVER_WIND, OVER_HIT), "lead_uppercut": (LUP_WIND, LUP_HIT), "jab": (JAB_WIND, JAB_HIT), "cross": (CROSS_WIND, CROSS_HIT), "lead_hook": (HOOK_WIND, HOOK_HIT),
                "rear_uppercut": (UPPER_WIND, UPPER_HIT), "body_hook": (BODY_WIND, BODY_HIT)}

CLIPS = []   # cada clip: dict(name, loop, cat, ground, keys=[(t, pose)], note)


def add(name, keys, loop=False, cat="boxer", ground=True, note=""):
    CLIPS.append(dict(name=name, loop=loop, cat=cat, ground=ground, keys=keys, note=note))


def build_attack_clips():
    for n, a in ATTACKS.items():
        wind, hit = ATTACK_POSES[n]
        s, ac, r = a["startup"], a["active"], a["recovery"]
        total = s + ac + r
        add(n, [(0, GUARD), (s * 0.5, wind), (s + ac * 0.5, hit), (s + ac + r * 0.35, blend(hit, GUARD, 0.7)), (total, GUARD)],
            note=f"Golpe {a['kind']} con mano {a['hand']}; impacto entre {s:.2f}s y {s + ac:.2f}s")


def cyc(fn, n, period):
    """Muestrea una función de fase (0..1) en n pasos para un ciclo."""
    return [(period * i / n, fn(i / n)) for i in range(n + 1)]


def build_all():
    CLIPS.clear()
    build_attack_clips()
    S = np.sin
    TAU = 2 * np.pi

    # --- guardia y desplazamientos ---
    add("idle_guard", cyc(lambda f: with_(GUARD, _t=(0, 0.012 * S(TAU * f), 0), Chest=(4 + 1.5 * S(TAU * f), -4, 0),
                                          UpperArm_L=(-42 + 2 * S(TAU * f), 0, -8), UpperArm_R=(-34 - 2 * S(TAU * f), 0, 10)), 8, 1.2),
        loop=True, note="Guardia ortodoxa con respiración y rebote leve")

    def walk(sign):
        def f(ph):
            a = S(TAU * ph)
            return with_(GUARD, _t=(0, 0.015 * abs(a) - 0.01, 0.0),
                         Thigh_L=(-16 + sign * 10 * a, 0, 0), Shin_L=(24 + 8 * max(0, -a * sign), 0, 0), Foot_L=(-8, 0, 0),
                         Thigh_R=(14 - sign * 10 * a, 0, 0), Shin_R=(10 + 8 * max(0, a * sign), 0, 0), Foot_R=(-24, 0, 0))
        return f
    add("walk_forward", cyc(walk(1), 8, 0.7), loop=True, note="Desplazamiento hacia el rival en guardia (sin root motion)")
    add("walk_back", cyc(walk(-1), 8, 0.7), loop=True, note="Retroceso en guardia (sin root motion)")

    def step(side):
        def f(ph):
            a = S(TAU * ph)
            return with_(GUARD, _t=(side * 0.02 * a, 0.012 * abs(a), 0), Hips=(0, -30, 4 * side * a),
                         Thigh_L=(-16, 0, 6 * side * a), Thigh_R=(14, 0, -6 * side * a))
        return f
    add("step_left", cyc(step(1), 8, 0.8), loop=True, note="Paso lateral a la izquierda")
    add("step_right", cyc(step(-1), 8, 0.8), loop=True, note="Paso lateral a la derecha")

    # --- defensa ---
    block = with_(GUARD, Spine=(14, -8, 0), Head=(14, 22, 0), UpperArm_L=(-70, 0, -14), Forearm_L=(-142, 0, 0),
                  UpperArm_R=(-66, 0, 16), Forearm_R=(-144, 0, 0))
    add("block_high", [(0, GUARD), (0.12, block), (0.30, block)], note="Bloqueo alto; se queda en la última pose mientras se mantiene")
    for nm, side in (("dodge_left", 1), ("dodge_right", -1)):
        d = with_(GUARD, _t=(0.20 * side, -0.06, 0), Spine=(10, -8, -16 * side), Chest=(4, -4, -4 * side), Head=(8, 22, 10 * side))
        add(nm, [(0, GUARD), (0.12, d), (0.22, d), (0.36, GUARD)], note="Esquiva lateral; ventana de invulnerabilidad 0.06s-0.26s")

    # --- reacciones ---
    hh = with_(GUARD, _t=(0, 0, -0.06), Head=(-24, 10, 4), Neck=(-14, 20, 0), Spine=(-4, -8, 0),
               UpperArm_L=(-20, 0, -4), Forearm_L=(-90, 0, 0), UpperArm_R=(-20, 0, 6), Forearm_R=(-90, 0, 0))
    add("hit_head", [(0, GUARD), (0.08, hh), (0.36, GUARD)], note="Impacto en la cabeza")
    hb = with_(GUARD, _t=(0, -0.06, -0.04), Spine=(30, -8, 0), Chest=(10, -4, 0), Head=(18, 22, 0),
               UpperArm_L=(-30, 0, -4), Forearm_L=(-100, 0, 0), UpperArm_R=(-30, 0, 6), Forearm_R=(-100, 0, 0))
    add("hit_body", [(0, GUARD), (0.08, hb), (0.36, GUARD)], note="Impacto al cuerpo")

    def stag(f):
        a = S(TAU * f)
        return with_(GUARD, _t=(0.05 * a, -0.05, -0.04 * abs(a)), Spine=(16, -8, 14 * a), Head=(10, 22, -10 * a),
                     UpperArm_L=(-12, 0, -4), Forearm_L=(-60, 0, 0), UpperArm_R=(-12, 0, 6), Forearm_R=(-60, 0, 0),
                     Thigh_L=(-10, 0, 0), Shin_L=(34, 0, 0), Thigh_R=(8, 0, 0), Shin_R=(30, 0, 0), Foot_R=(-38, 0, 0), Foot_L=(-24, 0, 0))
    add("stagger", cyc(stag, 8, 1.1), note="Tambaleo tras un golpe fuerte")

    lying = with_(STAND, _t=(0, 0.27, -0.40), Hips=(-90, 0, 0), Spine=(4, 0, 0), Head=(-8, 0, 0),
                  UpperArm_L=(-4, 0, 22), UpperArm_R=(-4, 0, -22), Forearm_L=(-12, 0, 0), Forearm_R=(-12, 0, 0),
                  Thigh_L=(10, 0, 4), Thigh_R=(6, 0, -6), Shin_L=(4, 0, 0))
    mid = with_(GUARD, _t=(0, 0.55, -0.22), Hips=(-30, 0, 0), Spine=(-12, 0, 0), Head=(-12, 0, 0),
                UpperArm_L=(-10, 0, 20), UpperArm_R=(-10, 0, -20), Forearm_L=(-20, 0, 0), Forearm_R=(-20, 0, 0),
                Thigh_L=(30, 0, 0), Shin_L=(30, 0, 0), Thigh_R=(30, 0, 0), Shin_R=(30, 0, 0))
    add("knockdown", [(0, with_(GUARD, _t=(0, 0.94, 0))), (0.25, mid), (0.5, with_(mid, Hips=(-60, 0, 0), _t=(0, 0.38, -0.3))), (1.1, lying)],
        ground=False, note="Caída de espaldas; termina en el suelo")
    sit = with_(STAND, _t=(0, 0.30, -0.20), Hips=(-12, 0, 0), Spine=(-24, 0, 0), Head=(20, 0, 0), Thigh_L=(-85, 0, 0), Thigh_R=(-85, 0, 0),
                Shin_L=(85, 0, 0), Shin_R=(85, 0, 0), UpperArm_L=(-30, 0, 10), UpperArm_R=(-30, 0, -10), Forearm_L=(-40, 0, 0), Forearm_R=(-40, 0, 0))
    kneel = with_(GUARD, _t=(0, 0.52, 0.0), Hips=(0, -20, 0), Spine=(30, 0, 0), Head=(10, 0, 0), Thigh_L=(-70, 0, 0), Shin_L=(100, 0, 0),
                  Thigh_R=(-20, 0, 0), Shin_R=(100, 0, 0), UpperArm_L=(-30, 0, 0), Forearm_L=(-60, 0, 0), UpperArm_R=(-30, 0, 0), Forearm_R=(-60, 0, 0))
    add("getup", [(0, lying), (0.5, sit), (1.0, kneel), (1.6, with_(GUARD, _t=(0, 0.94, 0)))], ground=False, note="Levantarse tras la cuenta")

    # --- celebración, derrota, entrenamiento ---
    def vic(f):
        a = S(TAU * f)
        return with_(STAND, _t=(0, 0.04 * abs(a), 0), UpperArm_L=(-168, 0, 12), UpperArm_R=(-168, 0, -12),
                     Forearm_L=(-8, 0, 0), Forearm_R=(-8, 0, 0), Head=(-6, 0, 6 * a), Spine=(-4, 0, 3 * a),
                     Thigh_L=(-4, 0, 3), Thigh_R=(-4, 0, -3))
    add("victory", cyc(vic, 8, 1.0), loop=True, note="Brazos en alto celebrando")
    slump = with_(STAND, _t=(0, -0.03, 0), Spine=(26, 0, 0), Chest=(10, 0, 0), Head=(24, 0, 0), Neck=(10, 0, 0),
                  UpperArm_L=(6, 0, 6), UpperArm_R=(6, 0, -6), Forearm_L=(-10, 0, 0), Forearm_R=(-10, 0, 0),
                  Thigh_L=(-8, 0, 0), Thigh_R=(-8, 0, 0), Shin_L=(14, 0, 0), Shin_R=(14, 0, 0), Foot_L=(-6, 0, 0), Foot_R=(-6, 0, 0))
    add("defeat", [(0, STAND), (0.8, slump), (1.4, slump)], note="Hombros caídos tras perder")

    def shadow(f):
        if f < 0.25: return blend(GUARD, JAB_HIT, S(np.pi * f / 0.25))
        if f < 0.5: return GUARD
        if f < 0.75: return blend(GUARD, CROSS_HIT, S(np.pi * (f - 0.5) / 0.25))
        return GUARD
    add("warmup_shadow", cyc(shadow, 16, 2.0), loop=True, note="Sombra: jab-directo con rebote (calentamiento/entrenamiento)")

    def breathe(f):
        a = S(TAU * f)
        return with_(STAND, _t=(0, -0.10, 0.02), Spine=(32 + 2 * a, 0, 0), Chest=(6 + 3 * a, 0, 0), Head=(10, 0, 0),
                     UpperArm_L=(-30, 0, 6), UpperArm_R=(-30, 0, -6), Forearm_L=(-30, 0, 0), Forearm_R=(-30, 0, 0),
                     Thigh_L=(-34, 0, 0), Thigh_R=(-34, 0, 0), Shin_L=(34, 0, 0), Shin_R=(34, 0, 0), Foot_L=(0, 0, 0), Foot_R=(0, 0, 0))
    add("rest_breathe", cyc(breathe, 8, 2.4), loop=True, note="Descanso entre rounds: manos en rodillas respirando")

    def entrance(f):
        a = S(TAU * f)
        return with_(GUARD, _t=(0, 0.03 * abs(a), 0), Hips=(0, -10, 0), Spine=(2, 0, 0), Chest=(0, 0, 0), Neck=(0, 0, 0), Head=(0, 0, 0),
                     UpperArm_L=(-150, 0, 20), Forearm_L=(-30, 0, 0), UpperArm_R=(-150, 0, -20), Forearm_R=(-30, 0, 0),
                     Thigh_L=(-14 * a, 0, 0), Thigh_R=(14 * a, 0, 0), Shin_L=(10 * max(0, -a), 0, 0), Shin_R=(10 * max(0, a), 0, 0))
    add("entrance_taunt", cyc(entrance, 8, 1.6), loop=True, note="Entrada al ring con los guantes en alto")

    # --- personal: árbitro y entrenadores ---
    def staff_idle(f):
        a = S(TAU * f)
        return with_(STAND, _t=(0, 0, 0), Chest=(1.5 * a, 0, 0), Head=(0, 6 * S(TAU * f * 0.5), 0), UpperArm_L=(-12, 0, 6), UpperArm_R=(-12, 0, -6),
                     Forearm_L=(-30, 0, 0), Forearm_R=(-30, 0, 0))
    add("idle_staff", cyc(staff_idle, 8, 3.0), loop=True, cat="staff", note="Postura relajada con respiración")

    def staff_walk(f):
        a = S(TAU * f)
        return with_(STAND, _t=(0, 0.015 * abs(a), 0), Thigh_L=(22 * a, 0, 0), Thigh_R=(-22 * a, 0, 0), Shin_L=(18 * max(0, -a), 0, 0), Shin_R=(18 * max(0, a), 0, 0),
                     UpperArm_L=(-22 * a, 0, 5), UpperArm_R=(22 * a, 0, -5), Forearm_L=(-20, 0, 0), Forearm_R=(-20, 0, 0))
    add("walk_staff", cyc(staff_walk, 8, 1.0), loop=True, cat="staff", note="Caminata normal")

    def ref_count(f):
        a = max(0, S(TAU * f))
        return with_(STAND, UpperArm_R=(-100 + 35 * a, 0, -10), Forearm_R=(-30, 0, 0), UpperArm_L=(-12, 0, 6), Forearm_L=(-30, 0, 0),
                     Spine=(6, -10, 0), Head=(10, 10, 0))
    add("ref_count", cyc(ref_count, 8, 1.0), loop=True, cat="staff", note="Árbitro cuenta: un golpe de brazo por segundo")

    out = with_(STAND, UpperArm_L=(-12, 0, 75), UpperArm_R=(-12, 0, -75), Forearm_L=(-8, 0, 0), Forearm_R=(-8, 0, 0))
    cross = with_(STAND, UpperArm_L=(-70, 0, -30), UpperArm_R=(-70, 0, 30), Forearm_L=(-20, 0, 0), Forearm_R=(-20, 0, 0))
    add("ref_break", [(0, STAND), (0.3, cross), (0.6, out), (1.0, out), (1.3, STAND)], cat="staff", note="Árbitro corta el combate")
    up = with_(STAND, UpperArm_R=(-8, 0, -150), Forearm_R=(-5, 0, 0), UpperArm_L=(-12, 0, 6), Forearm_L=(-30, 0, 0), Spine=(0, 0, 6))
    add("ref_raise_winner", [(0, STAND), (0.5, up), (1.5, up)], cat="staff", note="Árbitro levanta el brazo del ganador")

    def cheer(f):
        a = S(TAU * f)
        return with_(STAND, _t=(0, 0.05 * abs(a), 0), UpperArm_L=(-150 + 20 * a, 0, 15), UpperArm_R=(-150 - 20 * a, 0, -15),
                     Forearm_L=(-20, 0, 0), Forearm_R=(-20, 0, 0), Head=(-6, 0, 5 * a))
    add("coach_cheer", cyc(cheer, 8, 1.0), loop=True, cat="staff", note="Entrenador celebrando")

    def instruct(f):
        a = S(TAU * f)
        return with_(STAND, Spine=(6, 0, 0), Head=(4, 8 * a, 0), UpperArm_L=(-55 + 10 * a, 0, 15), Forearm_L=(-70, 0, 0),
                     UpperArm_R=(-55 - 10 * a, 0, -15), Forearm_R=(-70, 0, 0))
    add("coach_instruct", cyc(instruct, 8, 2.0), loop=True, cat="staff", note="Entrenador dando instrucciones con las manos")
    return CLIPS


def evaluate(clip):
    """Devuelve [(t, quats[17,4], hips_t)] listos para exportar (con continuidad de signo)."""
    out = []
    prev = None
    for t, p in clip["keys"]:
        q, ht = finalize(p, ground=clip["ground"])
        quats = np.array([euler_q(q.get(n, (0, 0, 0))) for n in NAMES])
        if prev is not None:
            for i in range(len(NAMES)):
                if np.dot(prev[i], quats[i]) < 0:
                    quats[i] = -quats[i]
        prev = quats
        out.append((t, quats, ht, q))
    return out
