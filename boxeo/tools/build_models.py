"""Genera todos los modelos .glb (boxeador con esqueleto y animaciones, personal, ring, accesorios, público, piezas de arenas)."""
import os, json
import numpy as np
from gltf_writer import GLB, loft, ellipsoid, box, merge, fix_winding
import rig, clips

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def out(*p):
    path = os.path.join(ROOT, *p)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    return path


# ---------------- personajes con esqueleto ----------------
BOXER_MATS = {"skin": (0.78, 0.58, 0.45, 1), "shorts": (0.80, 0.10, 0.10, 1), "gloves": (0.75, 0.05, 0.05, 1),
              "shoes": (0.10, 0.10, 0.10, 1), "hair": (0.10, 0.07, 0.05, 1)}
STAFF_MATS = {"skin": (0.78, 0.58, 0.45, 1), "shirt": (0.92, 0.92, 0.92, 1), "pants": (0.08, 0.08, 0.10, 1),
              "shoes": (0.08, 0.08, 0.08, 1), "hair": (0.10, 0.07, 0.05, 1)}


def build_character(path, kind, cat):
    g = GLB()
    mats = BOXER_MATS if kind == "boxer" else STAFF_MATS
    mid = {n: g.material(n, c, rough=0.75) for n, c in mats.items()}
    bone_nodes = {n: g.node(n, t=rig.offset(n)) for n in rig.NAMES}
    for n in rig.NAMES:
        p = rig.PARENT[n]
        if p:
            g.nodes[bone_nodes[p]].setdefault("children", []).append(bone_nodes[n])
    prims = []
    for mat, plist in rig.build_parts(kind).items():
        pos, nrm, idx, jo, wt = rig.skin_data(plist)
        prims.append(dict(pos=pos, nrm=nrm, idx=idx, joints=jo, weights=wt, mat=mid[mat]))
    mesh = g.mesh("BoxerBody" if kind == "boxer" else "StaffBody", prims)
    skin = g.skin("Armature", [bone_nodes[n] for n in rig.NAMES], rig.ibm_for_bones(), bone_nodes["Hips"])
    mnode = g.node("Body", mesh=mesh, skin=skin)
    root = g.node("BoxerRig" if kind == "boxer" else "StaffRig", children=[bone_nodes["Hips"], mnode])
    g.scene_nodes = [root]
    names = []
    for clip in clips.CLIPS:
        if clip["cat"] != cat:
            continue
        ev = clips.evaluate(clip)
        times = [e[0] for e in ev]
        ch = []
        for i, n in enumerate(rig.NAMES):
            ch.append((bone_nodes[n], "rotation", times, np.array([e[1][i] for e in ev])))
        ch.append((bone_nodes["Hips"], "translation", times, np.array([e[2] for e in ev])))
        g.animation(clip["name"], ch, extras={"loop": clip["loop"], "duration": times[-1]})
        names.append(clip["name"])
    size = g.save(path, "Boxer" if kind == "boxer" else "Staff")
    nverts = sum(len(p["pos"]) for p in prims)
    ntris = sum(len(p["idx"]) // 3 for p in prims)
    return dict(size=size, verts=nverts, tris=ntris, anims=names, bones=len(rig.NAMES))


# ---------------- escenas estáticas ----------------

def rot_to(v):
    y = np.array([0, 1, 0.])
    v = np.array(v, float)
    v = v / np.linalg.norm(v)
    if np.allclose(v, y):
        return np.eye(3)
    if np.allclose(v, -y):
        return np.diag([1, -1, -1.])
    ax = np.cross(y, v)
    s = np.linalg.norm(ax)
    c = float(np.dot(y, v))
    ax /= s
    K = np.array([[0, -ax[2], ax[1]], [ax[2], 0, -ax[0]], [-ax[1], ax[0], 0]])
    return np.eye(3) + s * K + (1 - c) * (K @ K)


def cyl(a, b, r, sides=8, caps=True):
    a, b = np.array(a, float), np.array(b, float)
    L = np.linalg.norm(b - a)
    pos, nrm, idx = loft((0, 0, 0), (0, L, 0), (r, r), (r, r), sides, caps)
    R = rot_to(b - a)
    return pos @ R.T + a, nrm @ R.T, idx


class Static:
    def __init__(self, name):
        self.name = name
        self.mats = {}
        self.geo = {}

    def mat(self, name, rgba, rough=0.8, metal=0.0, emissive=None):
        self.mats[name] = (rgba, rough, metal, emissive)
        self.geo.setdefault(name, [])

    def add(self, mat, geo):
        self.geo[mat].append(geo)

    def save(self, path):
        g = GLB()
        prims = []
        tris = verts = 0
        for name, geos in self.geo.items():
            if not geos:
                continue
            P, N, I = [], [], []
            off = 0
            for pos, nrm, idx in geos:
                I.append(fix_winding(pos, nrm, idx) + off)
                P.append(pos)
                N.append(nrm)
                off += len(pos)
            rgba, rough, metal, em = self.mats[name]
            m = g.material(name, rgba, rough, metal, em)
            pos, nrm, idx = np.vstack(P), np.vstack(N), np.concatenate(I)
            prims.append(dict(pos=pos, nrm=nrm, idx=idx, mat=m))
            verts += len(pos)
            tris += len(idx) // 3
        mesh = g.mesh(self.name, prims)
        g.scene_nodes = [g.node(self.name, mesh=mesh)]
        size = g.save(path, self.name)
        return dict(size=size, verts=verts, tris=tris)


def build_ring(path):
    s = Static("RingPro")
    s.mat("canvas", (0.86, 0.86, 0.82, 1), 0.95)
    s.mat("apron", (0.08, 0.10, 0.22, 1), 0.9)
    s.mat("post", (0.55, 0.57, 0.6, 1), 0.4, 0.8)
    s.mat("rope_red", (0.8, 0.08, 0.08, 1), 0.7)
    s.mat("rope_white", (0.92, 0.92, 0.92, 1), 0.7)
    s.mat("rope_blue", (0.1, 0.2, 0.75, 1), 0.7)
    s.mat("pad_red", (0.75, 0.06, 0.06, 1), 0.8)
    s.mat("pad_blue", (0.08, 0.15, 0.7, 1), 0.8)
    s.mat("pad_white", (0.9, 0.9, 0.9, 1), 0.8)
    s.mat("stool", (0.2, 0.2, 0.22, 1), 0.7)
    s.add("canvas", box((0, -0.03, 0), (3.55, 0.03, 3.55)))
    s.add("apron", box((0, -0.565, 0), (3.55, 0.535, 3.55)))
    # escalones en un lado
    for k in range(3):
        s.add("apron", box((3.55 + 0.25 + 0.25 * (2 - k) - 0.25, -0.9 + 0.3 * k - 0.0, 1.5), (0.25 * (k + 1) * 0.5 + 0.2, 0.15, 0.5)))
    corners = [(-3.2, -3.2, "pad_red"), (3.2, -3.2, "pad_blue"), (3.2, 3.2, "pad_white"), (-3.2, 3.2, "pad_white")]
    for x, z, pad in corners:
        s.add("post", cyl((x, -0.03, z), (x, 1.55, z), 0.06, 8))
        s.add(pad, box((x - np.sign(x) * 0.02, 0.88, z - np.sign(z) * 0.02), (0.10, 0.58, 0.10)))
    rope_mats = ["rope_red", "rope_white", "rope_blue", "rope_white"]
    for h, m in zip((0.42, 0.72, 1.02, 1.32), rope_mats):
        for (x0, z0, _), (x1, z1, _) in zip(corners, corners[1:] + corners[:1]):
            s.add(m, cyl((x0, h, z0), (x1, h, z1), 0.022, 6, caps=False))
    # taburetes de esquina (rojo y azul, fuera de las cuerdas)
    for x, z in ((-3.9, -3.2), (3.9, -3.2)):
        s.add("stool", box((x, -0.62, z), (0.2, 0.02, 0.2)))
        for dx in (-0.16, 0.16):
            for dz in (-0.16, 0.16):
                s.add("stool", cyl((x + dx, -1.1, z + dz), (x + dx, -0.64, z + dz), 0.015, 5))
    return s.save(path)


def build_heavy_bag(path):
    s = Static("HeavyBag")
    s.mat("leather", (0.45, 0.16, 0.10, 1), 0.6)
    s.mat("chain", (0.5, 0.5, 0.52, 1), 0.4, 0.9)
    s.mat("strap", (0.1, 0.1, 0.1, 1), 0.8)
    s.add("leather", cyl((0, 0.40, 0), (0, 1.60, 0), 0.18, 12))
    s.add("strap", cyl((0, 0.95, 0), (0, 1.0, 0), 0.185, 12))
    s.add("chain", cyl((0.05, 1.60, 0), (0.0, 2.55, 0), 0.012, 5))
    s.add("chain", cyl((-0.05, 1.60, 0), (0.0, 2.55, 0), 0.012, 5))
    return s.save(path)


def build_speed_bag(path):
    s = Static("SpeedBag")
    s.mat("wood", (0.55, 0.4, 0.25, 1), 0.9)
    s.mat("leather", (0.6, 0.12, 0.1, 1), 0.6)
    s.mat("steel", (0.55, 0.57, 0.6, 1), 0.4, 0.8)
    s.add("wood", box((0, 2.0, 0), (0.30, 0.02, 0.20)))
    s.add("steel", cyl((0.28, 0, -0.18), (0.28, 2.0, -0.18), 0.02, 6))
    s.add("steel", cyl((-0.28, 0, -0.18), (-0.28, 2.0, -0.18), 0.02, 6))
    s.add("leather", ellipsoid((0, 1.72, 0), (0.09, 0.13, 0.09), 10, 6))
    return s.save(path)


def build_barbell(path):
    s = Static("Barbell")
    s.mat("steel", (0.6, 0.6, 0.62, 1), 0.3, 0.9)
    s.mat("plate", (0.12, 0.12, 0.14, 1), 0.5, 0.4)
    s.mat("plate_red", (0.7, 0.08, 0.08, 1), 0.5, 0.2)
    s.add("steel", cyl((-1.1, 0.3, 0), (1.1, 0.3, 0), 0.014, 6))
    for sx in (-1, 1):
        s.add("plate", cyl((sx * 0.60, 0.3, 0), (sx * 0.65, 0.3, 0), 0.22, 14))
        s.add("plate", cyl((sx * 0.66, 0.3, 0), (sx * 0.71, 0.3, 0), 0.22, 14))
        s.add("plate_red", cyl((sx * 0.72, 0.3, 0), (sx * 0.76, 0.3, 0), 0.15, 12))
    return s.save(path)


def build_bench(path):
    s = Static("WeightBench")
    s.mat("pad", (0.1, 0.1, 0.12, 1), 0.7)
    s.mat("steel", (0.55, 0.57, 0.6, 1), 0.4, 0.8)
    s.add("pad", box((0, 0.45, 0), (0.15, 0.05, 0.6)))
    s.add("pad", box((0, 0.62, -0.7), (0.15, 0.2, 0.05)))
    for dx in (-0.1, 0.1):
        for dz in (-0.45, 0.45):
            s.add("steel", cyl((dx, 0, dz), (dx, 0.42, dz), 0.025, 6))
    return s.save(path)


def build_crowd(path, lod):
    s = Static(f"Spectator_LOD{lod}")
    s.mat("cloth", (1, 1, 1, 1), 0.9)
    if lod == 0:
        s.add("cloth", loft((0, 0.0, 0), (0, 0.62, 0), (0.17, 0.10), (0.22, 0.11), 8))
        s.add("cloth", ellipsoid((0, 0.80, 0), (0.095, 0.115, 0.10), 8, 5))
        for sx in (-1, 1):
            s.add("cloth", cyl((sx * 0.25, 0.58, 0), (sx * 0.34, 1.02, 0.02), 0.04, 5))     # brazos arriba
    elif lod == 1:
        s.add("cloth", box((0, 0.32, 0), (0.2, 0.32, 0.11)))
        s.add("cloth", box((0, 0.80, 0), (0.09, 0.11, 0.09)))
    else:
        s.add("cloth", box((0, 0.45, 0), (0.2, 0.45, 0.1)))
    return s.save(path)


def build_bleacher(path):
    s = Static("BleacherTier")
    s.mat("concrete", (0.35, 0.35, 0.38, 1), 0.95)
    s.mat("seat", (0.15, 0.15, 0.2, 1), 0.8)
    for k in range(6):
        s.add("concrete", box((0, 0.2 * (k + 1) - 0.2, 0.4 * k), (5.0, 0.2 * (k + 1), 0.4)))
        s.add("seat", box((0, 0.2 * (k + 1) + 0.18, 0.4 * k - 0.1), (4.9, 0.02, 0.18)))
    return s.save(path)


def build_scoreboard(path):
    s = Static("ScoreboardCube")
    s.mat("frame", (0.1, 0.1, 0.12, 1), 0.5)
    s.mat("screen", (0.05, 0.1, 0.25, 1), 0.3, 0.0, (0.1, 0.25, 0.9))
    s.mat("cable", (0.05, 0.05, 0.05, 1), 0.9)
    s.add("frame", box((0, 0, 0), (1.6, 0.9, 1.6)))
    for a in range(4):
        ang = a * np.pi / 2
        c, sn = np.cos(ang), np.sin(ang)
        s.add("screen", box((c * 1.61, 0, sn * 1.61), (0.02 if abs(c) > 0.5 else 1.4, 0.75, 0.02 if abs(sn) > 0.5 else 1.4)))
    for dx in (-1.2, 1.2):
        for dz in (-1.2, 1.2):
            s.add("cable", cyl((dx, 0.9, dz), (dx * 0.2, 4.0, dz * 0.2), 0.02, 4))
    return s.save(path)


def build_truss(path):
    s = Static("LightTruss")
    s.mat("truss", (0.2, 0.2, 0.22, 1), 0.4, 0.8)
    s.mat("lamp", (0.95, 0.92, 0.8, 1), 0.3, 0.0, (1.0, 0.95, 0.8))
    h = 7.0
    corners = [(-4, -4), (4, -4), (4, 4), (-4, 4)]
    for (x0, z0), (x1, z1) in zip(corners, corners[1:] + corners[:1]):
        s.add("truss", cyl((x0, h, z0), (x1, h, z1), 0.07, 6))
        s.add("truss", cyl((x0, h - 0.4, z0), (x1, h - 0.4, z1), 0.04, 5))
        for t in (0.15, 0.5, 0.85):
            x, z = x0 + (x1 - x0) * t, z0 + (z1 - z0) * t
            s.add("lamp", box((x, h - 0.35, z), (0.22, 0.12, 0.22)))
    return s.save(path)


def build_tunnel(path):
    s = Static("EntryTunnel")
    s.mat("wall", (0.18, 0.18, 0.22, 1), 0.9)
    s.mat("floor", (0.08, 0.08, 0.1, 1), 0.6)
    s.mat("strip", (0.9, 0.9, 1.0, 1), 0.3, 0.0, (0.8, 0.85, 1.0))
    L = 6.0
    s.add("floor", box((0, -0.05, 0), (1.6, 0.05, L)))
    s.add("wall", box((-1.65, 1.5, 0), (0.05, 1.5, L)))
    s.add("wall", box((1.65, 1.5, 0), (0.05, 1.5, L)))
    s.add("wall", box((0, 3.05, 0), (1.7, 0.05, L)))
    for z in np.linspace(-L + 0.5, L - 0.5, 8):
        s.add("strip", box((0, 2.98, z), (0.5, 0.02, 0.08)))
    return s.save(path)


def build_lockers(path):
    s = Static("LockerBank")
    s.mat("locker", (0.2, 0.3, 0.45, 1), 0.5, 0.5)
    s.mat("door", (0.25, 0.37, 0.55, 1), 0.5, 0.5)
    s.mat("bench", (0.5, 0.35, 0.2, 1), 0.8)
    for k in range(6):
        x = (k - 2.5) * 0.46
        s.add("locker", box((x, 1.0, 0), (0.22, 1.0, 0.25)))
        s.add("door", box((x, 1.0, 0.255), (0.19, 0.95, 0.01)))
    s.add("bench", box((0, 0.42, 0.9), (1.5, 0.03, 0.18)))
    return s.save(path)


def build_all():
    clips.build_all()
    report = {}
    report["CHARACTERS/boxer_base.glb"] = build_character(out("CHARACTERS", "boxer_base.glb"), "boxer", "boxer")
    report["CHARACTERS/staff_base.glb"] = build_character(out("CHARACTERS", "staff_base.glb"), "staff", "staff")
    report["RINGS/ring_pro.glb"] = build_ring(out("RINGS", "ring_pro.glb"))
    report["GYMS/heavy_bag.glb"] = build_heavy_bag(out("GYMS", "heavy_bag.glb"))
    report["GYMS/speed_bag.glb"] = build_speed_bag(out("GYMS", "speed_bag.glb"))
    report["GYMS/barbell.glb"] = build_barbell(out("GYMS", "barbell.glb"))
    report["GYMS/weight_bench.glb"] = build_bench(out("GYMS", "weight_bench.glb"))
    for lod in (0, 1, 2):
        report[f"ENVIRONMENTS/crowd_spectator_lod{lod}.glb"] = build_crowd(out("ENVIRONMENTS", f"crowd_spectator_lod{lod}.glb"), lod)
    report["ARENAS/bleacher_tier.glb"] = build_bleacher(out("ARENAS", "bleacher_tier.glb"))
    report["ARENAS/scoreboard_cube.glb"] = build_scoreboard(out("ARENAS", "scoreboard_cube.glb"))
    report["ARENAS/light_truss.glb"] = build_truss(out("ARENAS", "light_truss.glb"))
    report["ENVIRONMENTS/entry_tunnel.glb"] = build_tunnel(out("ENVIRONMENTS", "entry_tunnel.glb"))
    report["ENVIRONMENTS/locker_bank.glb"] = build_lockers(out("ENVIRONMENTS", "locker_bank.glb"))
    return report


if __name__ == "__main__":
    for k, v in build_all().items():
        print(f"{k:42s} {v['size']/1024:7.1f} KB  verts={v['verts']:5d} tris={v['tris']:5d}" + (f" anims={len(v['anims'])}" if 'anims' in v else ""))
