"""Esqueleto humanoide de 17 huesos + mallas low-poly (boxeador y personal de esquina/árbitro).
Convención: Y arriba, el personaje mira hacia +Z, su izquierda es +X, unidades en metros, altura 1.82 m."""
import numpy as np
from gltf_writer import loft, ellipsoid, box, merge

# nombre, padre, posición de reposo (cabeza del hueso) en espacio del modelo
BONES = [
    ("Hips", None, (0, 1.00, 0)),
    ("Spine", "Hips", (0, 1.10, 0)),
    ("Chest", "Spine", (0, 1.30, 0)),
    ("Neck", "Chest", (0, 1.55, 0)),
    ("Head", "Neck", (0, 1.62, 0)),
    ("UpperArm_L", "Chest", (0.21, 1.48, 0)),
    ("Forearm_L", "UpperArm_L", (0.21, 1.20, 0)),
    ("Hand_L", "Forearm_L", (0.21, 0.95, 0)),
    ("UpperArm_R", "Chest", (-0.21, 1.48, 0)),
    ("Forearm_R", "UpperArm_R", (-0.21, 1.20, 0)),
    ("Hand_R", "Forearm_R", (-0.21, 0.95, 0)),
    ("Thigh_L", "Hips", (0.10, 0.98, 0)),
    ("Shin_L", "Thigh_L", (0.10, 0.52, 0)),
    ("Foot_L", "Shin_L", (0.10, 0.08, 0)),
    ("Thigh_R", "Hips", (-0.10, 0.98, 0)),
    ("Shin_R", "Thigh_R", (-0.10, 0.52, 0)),
    ("Foot_R", "Shin_R", (-0.10, 0.08, 0)),
]
NAMES = [b[0] for b in BONES]
IDX = {n: i for i, n in enumerate(NAMES)}
PARENT = {n: p for n, p, _ in BONES}
HEAD = {n: np.array(h, float) for n, _, h in BONES}


def offset(n):
    p = PARENT[n]
    return HEAD[n] - (HEAD[p] if p else 0)


# ---------------- rotaciones ----------------

def qmul(a, b):
    x1, y1, z1, w1 = a
    x2, y2, z2, w2 = b
    return np.array([w1 * x2 + x1 * w2 + y1 * z2 - z1 * y2, w1 * y2 - x1 * z2 + y1 * w2 + z1 * x2,
                     w1 * z2 + x1 * y2 - y1 * x2 + z1 * w2, w1 * w2 - x1 * x2 - y1 * y2 - z1 * z2])


def axis_q(axis, deg):
    h = np.radians(deg) / 2
    v = np.zeros(4)
    v[axis] = np.sin(h)
    v[3] = np.cos(h)
    return v


def euler_q(e):
    """Euler en grados (rx, ry, rz) -> cuaternión (x,y,z,w). Orden: Y, luego X, luego Z (intrínseco)."""
    return qmul(qmul(axis_q(1, e[1]), axis_q(0, e[0])), axis_q(2, e[2]))


def q_mat(q):
    x, y, z, w = q
    return np.array([[1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
                     [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
                     [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]])


def fk(pose, hips_t=None):
    """Cinemática directa: pose = {hueso: (rx,ry,rz)} -> {hueso: posición mundial de su cabeza}, y matrices."""
    W, M = {}, {}
    for n in NAMES:
        e = pose.get(n, (0, 0, 0))
        R = q_mat(euler_q(e))
        off = np.array(hips_t, float) if (n == "Hips" and hips_t is not None) else offset(n)
        T = np.eye(4)
        T[:3, :3] = R
        T[:3, 3] = off
        M[n] = (M[PARENT[n]] @ T) if PARENT[n] else T
        W[n] = M[n][:3, 3]
    return W, M


def ground_y(pose, hips_t):
    """Altura del punto más bajo de los pies (para verificar contacto con el suelo)."""
    W, M = fk(pose, hips_t)
    ys = []
    for f in ("Foot_L", "Foot_R"):
        for local in (np.array([0, -0.04, 0.0]), np.array([0, -0.04, 0.17])):   # talón y punta
            ys.append((M[f] @ np.append(local, 1))[1])
    return min(ys)


# ---------------- mallas ----------------

def tube(stations, sides=16, caps=True):
    """Sólido de revolución elíptico con perfil variable (músculos). stations = [(y, rx, rz[, cx, cz]), ...] de abajo a arriba."""
    st = [tuple(list(s) + [0.0] * (5 - len(s))) for s in stations]
    pos, nrm, idx = [], [], []
    n = len(st)
    for k, (y, rx, rz, cx, cz) in enumerate(st):
        k0, k1 = max(k - 1, 0), min(k + 1, n - 1)
        for i in range(sides):
            a = 2 * np.pi * i / sides
            c, s_ = np.cos(a), np.sin(a)
            pos.append((cx + c * rx, y, cz + s_ * rz))
            y0, rx0, rz0, cx0, cz0 = st[k0]
            y1, rx1, rz1, cx1, cz1 = st[k1]
            ty = np.array([(cx1 + c * rx1) - (cx0 + c * rx0), y1 - y0, (cz1 + s_ * rz1) - (cz0 + s_ * rz0)])
            tr = np.array([-s_ * rx, 0.0, c * rz])
            nn = np.cross(tr, ty)
            if np.dot(nn, [c, 0.0, s_]) < 0:
                nn = -nn
            nrm.append(nn / max(np.linalg.norm(nn), 1e-9))
    for k in range(n - 1):
        for i in range(sides):
            j = (i + 1) % sides
            a, b, c2, d = k * sides + i, k * sides + j, (k + 1) * sides + i, (k + 1) * sides + j
            idx += [a, b, c2, b, d, c2]
    if caps:
        for k, sign in ((0, -1), (n - 1, 1)):
            y, rx, rz, cx, cz = st[k]
            base = len(pos)
            pos.append((cx, y, cz)); nrm.append((0, sign, 0))
            for i in range(sides):
                a = 2 * np.pi * i / sides
                pos.append((cx + np.cos(a) * rx, y, cz + np.sin(a) * rz)); nrm.append((0, sign, 0))
            for i in range(sides):
                j = (i + 1) % sides
                idx += [base, base + 1 + i, base + 1 + j]
    return np.array(pos, float), np.array(nrm, float), np.array(idx)


def ell(c, r, seg=20, rings=12):
    return ellipsoid(c, r, seg, rings)


def build_parts(kind="boxer"):
    """Devuelve {material: [(pos,nrm,idx,joint_index), ...]}. kind = 'boxer' | 'staff'.
    Versión de alta calidad: perfiles musculares (tube), más segmentos, cara con ojos/cejas/orejas/mandíbula,
    hombros, pectorales, pantorrillas, guantes con pulgar y puño, cintura y suelas."""
    J = IDX
    parts = {"skin": [], "shorts": [], "gloves": [], "shoes": [], "hair": [], "shirt": [], "pants": [], "dark": [], "trim": []}

    def add(mat, geo, joint):
        parts[mat].append((*geo, J[joint]))

    boxer = kind == "boxer"
    top = "skin" if boxer else "shirt"
    leg = "skin" if boxer else "pants"

    # ---- cabeza ----
    add("skin", ell((0, 1.725, 0.0), (0.094, 0.118, 0.102), 24, 14), "Head")                 # cráneo
    add("skin", ell((0, 1.655, 0.030), (0.068, 0.058, 0.076), 20, 10), "Head")               # mandíbula
    add("skin", ell((0, 1.640, 0.082), (0.026, 0.018, 0.02), 12, 6), "Head")                 # mentón
    add("skin", ell((0, 1.700, 0.104), (0.016, 0.026, 0.024), 12, 8), "Head")                # nariz
    add("skin", ell((0, 1.740, 0.088), (0.078, 0.014, 0.03), 14, 6), "Head")                 # arco de las cejas
    add("skin", ell((0, 1.668, 0.088), (0.034, 0.010, 0.016), 12, 6), "Head")                # labios
    for sx in (1, -1):
        add("skin", ell((sx * 0.096, 1.715, -0.006), (0.012, 0.030, 0.022), 10, 8), "Head")  # orejas
        add("dark", ell((sx * 0.036, 1.722, 0.088), (0.012, 0.008, 0.008), 10, 6), "Head")   # ojos
        add("hair", ell((sx * 0.036, 1.739, 0.094), (0.022, 0.0045, 0.008), 10, 4), "Head") # cejas
    if boxer:
        add("hair", ell((0, 1.765, -0.014), (0.099, 0.090, 0.108), 24, 10), "Head")          # pelo
        add("hair", ell((0, 1.78, -0.05), (0.09, 0.07, 0.085), 18, 8), "Head")
    else:
        add("hair", ell((0, 1.76, -0.012), (0.1, 0.092, 0.11), 24, 10), "Head")
    # ---- cuello y trapecios ----
    add("skin", tube([(1.50, 0.062, 0.058), (1.58, 0.048, 0.048), (1.65, 0.044, 0.046)], 16), "Neck")
    # ---- torso (V de hombros a cintura) ----
    add(top, tube([(1.30, 0.168, 0.108), (1.40, 0.182, 0.114), (1.49, 0.205, 0.112), (1.55, 0.150, 0.095), (1.58, 0.075, 0.07)], 20), "Chest")
    add(top, tube([(1.06, 0.150, 0.098), (1.15, 0.148, 0.098), (1.23, 0.158, 0.104), (1.30, 0.168, 0.108)], 20), "Spine")
    add("pants" if not boxer else "shorts", tube([(0.88, 0.190, 0.122), (0.96, 0.192, 0.124), (1.06, 0.162, 0.108), (1.12, 0.152, 0.102)], 20), "Hips")
    if boxer:
        for sx in (1, -1):
            add("skin", ell((sx * 0.075, 1.455, 0.082), (0.078, 0.058, 0.042), 14, 8), "Chest")   # pectorales
        add("trim", tube([(1.085, 0.158, 0.106), (1.125, 0.156, 0.104), (1.145, 0.150, 0.100)], 20), "Hips")  # cintura blanca
    for s, sx in (("L", 1), ("R", -1)):
        # hombro (deltoides) y brazo con bíceps
        add(top, ell((sx * 0.215, 1.48, 0), (0.072, 0.076, 0.072), 16, 10), f"UpperArm_{s}")
        add(top, tube([(1.20, 0.044, 0.044, sx * 0.21), (1.33, 0.054, 0.052, sx * 0.21), (1.42, 0.058, 0.056, sx * 0.21), (1.49, 0.056, 0.056, sx * 0.21)], 16), f"UpperArm_{s}")
        add("skin", ell((sx * 0.21, 1.20, 0), (0.046, 0.046, 0.046), 14, 8), f"Forearm_{s}")
        add("skin", tube([(0.95, 0.032, 0.032, sx * 0.21), (1.05, 0.040, 0.040, sx * 0.21), (1.13, 0.046, 0.044, sx * 0.21), (1.20, 0.044, 0.044, sx * 0.21)], 16), f"Forearm_{s}")
        if boxer:
            # guante: cuerpo, pulgar, puño y vendaje de muñeca
            add("gloves", ell((sx * 0.21, 0.895, 0.018), (0.088, 0.100, 0.094), 22, 14), f"Hand_{s}")
            add("gloves", ell((sx * 0.21 - sx * 0.072, 0.915, 0.045), (0.032, 0.058, 0.042), 12, 8), f"Hand_{s}")
            add("trim", tube([(0.965, 0.058, 0.058, sx * 0.21), (1.02, 0.056, 0.056, sx * 0.21), (1.075, 0.052, 0.052, sx * 0.21)], 16), f"Forearm_{s}")
            add("gloves", tube([(1.07, 0.052, 0.052, sx * 0.21), (1.09, 0.047, 0.047, sx * 0.21)], 14), f"Forearm_{s}")
        else:
            add("skin", ell((sx * 0.21, 0.92, 0.0), (0.042, 0.062, 0.032), 12, 8), f"Hand_{s}")
        # piernas: cuádriceps, rodilla, pantorrilla
        add(leg, tube([(0.52, 0.060, 0.060, sx * 0.10), (0.68, 0.082, 0.082, sx * 0.10), (0.86, 0.092, 0.092, sx * 0.10), (0.99, 0.088, 0.090, sx * 0.10)], 16), f"Thigh_{s}")
        add(leg, ell((sx * 0.10, 0.52, 0.006), (0.062, 0.062, 0.064), 14, 8), f"Shin_{s}")
        add(leg, tube([(0.09, 0.040, 0.044, sx * 0.10), (0.18, 0.046, 0.050, sx * 0.10), (0.36, 0.064, 0.068, sx * 0.10, -0.01), (0.46, 0.058, 0.060, sx * 0.10), (0.52, 0.056, 0.058, sx * 0.10)], 16), f"Shin_{s}")
        if boxer:
            add("shorts", tube([(0.70, 0.104, 0.104, sx * 0.10), (0.86, 0.108, 0.108, sx * 0.10), (1.00, 0.100, 0.100, sx * 0.10)], 16), f"Thigh_{s}")
            add("trim", tube([(0.690, 0.106, 0.106, sx * 0.10), (0.715, 0.107, 0.107, sx * 0.10)], 16), f"Thigh_{s}")   # ribete del pantalón
            add("trim", ell((sx * 0.10, 0.055, 0.115), (0.050, 0.020, 0.135), 14, 6), f"Foot_{s}")                    # suela blanca
        add("shoes", ell((sx * 0.10, 0.075, 0.055), (0.050, 0.052, 0.125), 18, 10), f"Foot_{s}")
        add("shoes", ell((sx * 0.10, 0.085, -0.04), (0.046, 0.06, 0.05), 12, 8), f"Foot_{s}")
    return {k: v for k, v in parts.items() if v}


# Cadenas de huesos entre las que se mezcla el peso cerca de las articulaciones (codos, rodillas, cintura, cuello).
CHAINS = [["Hips", "Spine", "Chest", "Neck", "Head"],
          ["UpperArm_L", "Forearm_L", "Hand_L"], ["UpperArm_R", "Forearm_R", "Hand_R"],
          ["Thigh_L", "Shin_L", "Foot_L"], ["Thigh_R", "Shin_R", "Foot_R"]]
_TAIL = {"Head": (0, 1.86, 0), "Hand_L": (0.21, 0.80, 0), "Hand_R": (-0.21, 0.80, 0),
         "Foot_L": (0.10, 0.05, 0.20), "Foot_R": (-0.10, 0.05, 0.20)}


def _segment(n):
    head = HEAD[n]
    nxt = None
    for c in CHAINS:
        if n in c and c.index(n) + 1 < len(c):
            nxt = c[c.index(n) + 1]
    tail = HEAD[nxt] if nxt else np.array(_TAIL[n], float)
    return head, tail


def _dist_seg(P, a, b):
    ab = b - a
    t = np.clip(((P - a) @ ab) / max(ab @ ab, 1e-9), 0.0, 1.0)
    return np.linalg.norm(P - (a + t[:, None] * ab), axis=1)


def skin_data(parts_for_mat):
    """Skinning suave: cada vértice mezcla su hueso con los vecinos de su cadena según la distancia al segmento de hueso."""
    pos, nrm, idx, J = merge(parts_for_mat)
    n = len(pos)
    joints = np.zeros((n, 4), dtype=np.uint16)
    weights = np.zeros((n, 4), dtype=np.float32)
    seg = {nm: _segment(nm) for c in CHAINS for nm in c}
    for j in np.unique(J):
        name = NAMES[int(j)]
        sel = np.where(J == j)[0]
        cand = [name]
        for c in CHAINS:
            if name in c:
                k = c.index(name)
                cand += [c[m] for m in (k - 1, k + 1) if 0 <= m < len(c)]
        if len(cand) == 1:
            joints[sel, 0] = j
            weights[sel, 0] = 1.0
            continue
        P = pos[sel]
        W = np.zeros((len(sel), len(cand)))
        for ci, cn in enumerate(cand):
            d = _dist_seg(P, *seg[cn])
            W[:, ci] = 1.0 / (d + 0.012) ** 4 * (3.0 if ci == 0 else 1.0)    # el hueso propio pesa más
        W /= W.sum(axis=1, keepdims=True)
        W[W < 0.03] = 0.0
        W /= W.sum(axis=1, keepdims=True)
        order = np.argsort(-W, axis=1)[:, :4]
        for r in range(len(sel)):
            for slot in range(min(4, len(cand))):
                ci = order[r, slot]
                joints[sel[r], slot] = IDX[cand[ci]]
                weights[sel[r], slot] = W[r, ci]
    return pos, nrm, idx, joints, weights


def ibm_for_bones():
    out = []
    for n in NAMES:
        m = np.eye(4)
        m[:3, 3] = -HEAD[n]
        out.append(m.T.reshape(-1))      # glTF es column-major
    return out
