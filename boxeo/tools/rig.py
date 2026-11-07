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

def build_parts(kind="boxer"):
    """Devuelve {material: [(pos,nrm,idx,joint_index), ...]}. kind = 'boxer' | 'staff'."""
    J = IDX
    parts = {"skin": [], "shorts": [], "gloves": [], "shoes": [], "hair": [], "shirt": [], "pants": []}

    def add(mat, geo, joint):
        parts[mat].append((*geo, J[joint]))

    boxer = kind == "boxer"
    top = "skin" if boxer else "shirt"          # material del torso y los brazos superiores
    leg = "skin" if boxer else "pants"

    # cabeza, cuello, nariz (marca el frente) y pelo
    add("skin", ellipsoid((0, 1.72, 0), (0.095, 0.12, 0.105), 10, 6), "Head")
    add("skin", box((0, 1.705, 0.108), (0.014, 0.022, 0.02)), "Head")
    add("hair", ellipsoid((0, 1.755, -0.012), (0.1, 0.095, 0.112), 10, 5), "Head")
    add("skin", loft((0, 1.53, 0), (0, 1.64, 0), (0.05, 0.05), (0.045, 0.045), 8), "Neck")
    # torso
    add(top, loft((0, 1.30, 0), (0, 1.56, 0), (0.165, 0.10), (0.20, 0.115), 10), "Chest")
    add(top, loft((0, 1.06, 0), (0, 1.30, 0), (0.15, 0.10), (0.165, 0.10), 10), "Spine")
    if boxer:
        add("shorts", loft((0, 0.90, 0), (0, 1.12, 0), (0.185, 0.12), (0.15, 0.10), 10), "Hips")
    else:
        add("pants", loft((0, 0.90, 0), (0, 1.12, 0), (0.185, 0.12), (0.15, 0.10), 10), "Hips")
    for s, sx in (("L", 1), ("R", -1)):
        # brazos
        add(top, ellipsoid((sx * 0.21, 1.48, 0), (0.065, 0.065, 0.065), 8, 5), f"UpperArm_{s}")
        add(top, loft((sx * 0.21, 1.20, 0), (sx * 0.21, 1.48, 0), (0.042, 0.042), (0.058, 0.058), 8), f"UpperArm_{s}")
        add("skin", ellipsoid((sx * 0.21, 1.20, 0), (0.047, 0.047, 0.047), 8, 5), f"Forearm_{s}")
        add("skin", loft((sx * 0.21, 0.95, 0), (sx * 0.21, 1.20, 0), (0.034, 0.034), (0.045, 0.045), 8), f"Forearm_{s}")
        if boxer:
            add("gloves", ellipsoid((sx * 0.21, 0.90, 0.015), (0.078, 0.092, 0.082), 10, 6), f"Hand_{s}")
            add("gloves", loft((sx * 0.21, 0.97, 0), (sx * 0.21, 1.07, 0), (0.06, 0.06), (0.052, 0.052), 8), f"Forearm_{s}")
        else:
            add("skin", ellipsoid((sx * 0.21, 0.92, 0.0), (0.04, 0.06, 0.03), 8, 5), f"Hand_{s}")
        # piernas
        add(leg, loft((sx * 0.10, 0.52, 0), (sx * 0.10, 0.98, 0), (0.06, 0.06), (0.085, 0.085), 8), f"Thigh_{s}")
        add(leg, ellipsoid((sx * 0.10, 0.52, 0), (0.063, 0.063, 0.063), 8, 5), f"Shin_{s}")
        add(leg, loft((sx * 0.10, 0.10, 0), (sx * 0.10, 0.52, 0), (0.042, 0.042), (0.058, 0.058), 8), f"Shin_{s}")
        if boxer:
            add("shorts", loft((sx * 0.10, 0.74, 0), (sx * 0.10, 0.99, 0), (0.092, 0.092), (0.092, 0.092), 8), f"Thigh_{s}")
        add("shoes", box((sx * 0.10, 0.045, 0.06), (0.048, 0.045, 0.125)), f"Foot_{s}")
    return {k: v for k, v in parts.items() if v}


def skin_data(parts_for_mat):
    pos, nrm, idx, J = merge(parts_for_mat)
    joints = np.zeros((len(pos), 4), dtype=np.uint16)
    joints[:, 0] = J
    weights = np.zeros((len(pos), 4), dtype=np.float32)
    weights[:, 0] = 1.0
    return pos, nrm, idx, joints, weights


def ibm_for_bones():
    out = []
    for n in NAMES:
        m = np.eye(4)
        m[:3, 3] = -HEAD[n]
        out.append(m.T.reshape(-1))      # glTF es column-major
    return out
