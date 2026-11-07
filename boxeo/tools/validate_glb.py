"""Validador independiente de .glb: lee el archivo tal como lo leerá el motor, comprueba la estructura
y puede dibujar la malla con skinning por software en cualquier instante de una animación."""
import json, struct, sys, os
import numpy as np

CT = {5126: np.float32, 5123: np.uint16, 5125: np.uint32}
NC = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4, "MAT4": 16}

def load(path):
    b = open(path, "rb").read()
    magic, ver, total = struct.unpack_from("<III", b, 0)
    assert magic == 0x46546C67 and ver == 2 and total == len(b), "cabecera GLB inválida"
    jl, jt = struct.unpack_from("<II", b, 12); assert jt == 0x4E4F534A
    j = json.loads(b[20:20 + jl])
    off = 20 + jl; bl, bt = struct.unpack_from("<II", b, off); assert bt == 0x004E4942
    binary = b[off + 8: off + 8 + bl]
    return j, binary

def acc(j, binary, i):
    a = j["accessors"][i]; v = j["bufferViews"][a["bufferView"]]
    dt = CT[a["componentType"]]; n = NC[a["type"]]
    start = v.get("byteOffset", 0) + a.get("byteOffset", 0)
    arr = np.frombuffer(binary, dtype=dt, count=a["count"] * n, offset=start)
    return arr.reshape(a["count"], n) if n > 1 else arr

def validate(path):
    j, binary = load(path); errs = []
    nn = len(j["nodes"])
    for v in j["bufferViews"]:
        if v["byteOffset"] + v["byteLength"] > len(binary): errs.append("bufferView fuera del buffer")
    for mi, m in enumerate(j["meshes"]):
        for p in m["primitives"]:
            pos = acc(j, binary, p["attributes"]["POSITION"]); nrm = acc(j, binary, p["attributes"]["NORMAL"])
            idx = acc(j, binary, p["indices"])
            if len(pos) != len(nrm): errs.append("POSITION/NORMAL distinto tamaño")
            if idx.max() >= len(pos): errs.append("índice fuera de rango")
            if len(idx) % 3: errs.append("índices no múltiplo de 3")
            if not np.isfinite(pos).all(): errs.append("posiciones no finitas")
            if "JOINTS_0" in p["attributes"]:
                jo = acc(j, binary, p["attributes"]["JOINTS_0"]); wt = acc(j, binary, p["attributes"]["WEIGHTS_0"])
                nj = len(j["skins"][0]["joints"])
                if jo.max() >= nj: errs.append("joint fuera de rango")
                if not np.allclose(wt.sum(axis=1), 1, atol=1e-4): errs.append("pesos no suman 1")
    for s in j.get("skins", []):
        ibm = acc(j, binary, s["inverseBindMatrices"])
        if len(ibm) != len(s["joints"]): errs.append("IBM != joints")
        if max(s["joints"]) >= nn: errs.append("joint node inválido")
    for a in j.get("animations", []):
        for c in a["channels"]:
            if c["target"]["node"] >= nn: errs.append(f"{a['name']}: nodo destino inválido")
            smp = a["samplers"][c["sampler"]]
            t = acc(j, binary, smp["input"]); o = acc(j, binary, smp["output"])
            if len(t) != len(o): errs.append(f"{a['name']}: tiempos/valores distinto largo")
            if len(t) > 1 and not (np.diff(t) > 0).all(): errs.append(f"{a['name']}: tiempos no crecientes")
            if c["target"]["path"] == "rotation" and not np.allclose(np.linalg.norm(o, axis=1), 1, atol=1e-3):
                errs.append(f"{a['name']}: cuaternión no unitario")
    return j, errs

def qmat(q):
    x, y, z, w = q
    return np.array([[1-2*(y*y+z*z), 2*(x*y-z*w), 2*(x*z+y*w)], [2*(x*y+z*w), 1-2*(x*x+z*z), 2*(y*z-x*w)], [2*(x*z-y*w), 2*(y*z+x*w), 1-2*(x*x+y*y)]])

def pose_world(j, binary, anim_name=None, t=0.0):
    """Matrices mundiales de todos los nodos en el instante t de la animación (interpolación lineal/nlerp)."""
    nodes = j["nodes"]; n = len(nodes)
    T = [np.array(nd.get("translation", [0, 0, 0]), float) for nd in nodes]
    R = [np.array(nd.get("rotation", [0, 0, 0, 1]), float) for nd in nodes]
    if anim_name:
        an = next(a for a in j["animations"] if a["name"] == anim_name)
        for c in an["channels"]:
            s = an["samplers"][c["sampler"]]; tt = acc(j, binary, s["input"]); vv = acc(j, binary, s["output"]).astype(float)
            k = int(np.clip(np.searchsorted(tt, t, side="right") - 1, 0, len(tt) - 1)); k2 = min(k + 1, len(tt) - 1)
            f = 0 if k2 == k else np.clip((t - tt[k]) / (tt[k2] - tt[k]), 0, 1)
            val = vv[k] + (vv[k2] - vv[k]) * f
            if c["target"]["path"] == "rotation": R[c["target"]["node"]] = val / np.linalg.norm(val)
            else: T[c["target"]["node"]] = val
    parent = {}
    for i, nd in enumerate(nodes):
        for ch in nd.get("children", []): parent[ch] = i
    W = [None] * n
    def world(i):
        if W[i] is not None: return W[i]
        M = np.eye(4); M[:3, :3] = qmat(R[i]); M[:3, 3] = T[i]
        W[i] = world(parent[i]) @ M if i in parent else M
        return W[i]
    for i in range(n): world(i)
    return W

def skinned_vertices(j, binary, anim_name=None, t=0.0):
    W = pose_world(j, binary, anim_name, t)
    skin = j["skins"][0]; ibm = acc(j, binary, skin["inverseBindMatrices"]).reshape(-1, 4, 4).transpose(0, 2, 1)
    JM = [W[jn] @ ibm[k] for k, jn in enumerate(skin["joints"])]
    out = []
    for p in j["meshes"][0]["primitives"]:
        pos = acc(j, binary, p["attributes"]["POSITION"]).astype(float); jo = acc(j, binary, p["attributes"]["JOINTS_0"])[:, 0]
        idx = acc(j, binary, p["indices"]).reshape(-1, 3)
        ph = np.hstack([pos, np.ones((len(pos), 1))])
        sp = np.array([(JM[jo[v]] @ ph[v])[:3] for v in range(len(pos))])
        col = j["materials"][p["material"]]["pbrMetallicRoughness"]["baseColorFactor"][:3]
        out.append((sp, idx, col))
    return out, W, skin

def render(path, anim, times, outpng):
    import matplotlib; matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from matplotlib.collections import PolyCollection
    j, binary = load(path)
    fig, axs = plt.subplots(len(times), 2, figsize=(7, 3.6 * len(times)), squeeze=False)
    for r, t in enumerate(times):
        meshes, W, skin = skinned_vertices(j, binary, anim, t)
        for c, view in enumerate(("front", "side")):
            ax = axs[r][c]; polys, cols, depth = [], [], []
            for sp, idx, col in meshes:
                tri = sp[idx]
                n = np.cross(tri[:, 1] - tri[:, 0], tri[:, 2] - tri[:, 0]); n /= (np.linalg.norm(n, axis=1, keepdims=True) + 1e-9)
                if view == "front": h, v, d, vdir = 0, 1, 2, np.array([0, 0, 1.])
                else: h, v, d, vdir = 2, 1, 0, np.array([1., 0, 0])
                light = np.clip(n @ (vdir * 0.7 + np.array([0.3, 0.5, 0.4])), 0.15, 1)
                vis = (n @ vdir) > -0.05 if True else None
                for k in range(len(tri)):
                    if not vis[k]: continue
                    polys.append(tri[k][:, [h, v]] * (1 if view == "front" else 1)); cols.append(np.clip(np.array(col) * (0.35 + 0.65 * light[k]), 0, 1)); depth.append(tri[k][:, d].mean())
            order = np.argsort(depth)
            ax.add_collection(PolyCollection([polys[i] for i in order], facecolors=[cols[i] for i in order], edgecolors="none"))
            ax.set_xlim(-1.2, 1.2) if view == "front" else ax.set_xlim(-1.2, 1.2); ax.set_ylim(-0.05, 2.0); ax.set_aspect("equal"); ax.axhline(0, color="k", lw=.6)
            ax.set_title(f"{anim or 'reposo'} t={t:.2f}s [{view}]", fontsize=8)
    plt.tight_layout(); plt.savefig(outpng, dpi=75)

if __name__ == "__main__":
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    bad = 0
    for dp, _, fs in os.walk(root):
        for f in fs:
            if f.endswith(".glb"):
                j, errs = validate(os.path.join(dp, f))
                print(("OK  " if not errs else "FALLA"), os.path.relpath(os.path.join(dp, f), root), errs[:3])
                bad += bool(errs)
    print("archivos con errores:", bad)
