"""Escritor mínimo de glTF 2.0 binario (.glb) con mallas, materiales, esqueleto (skin) y animaciones.
Sin dependencias salvo numpy. Pensado para Godot 4 y Unity (glTFast)."""
import json, struct
import numpy as np

FLOAT, USHORT, UINT = 5126, 5123, 5125
ARRAY_BUFFER, ELEMENT_BUFFER = 34962, 34963


class GLB:
    def __init__(self):
        self.bin = bytearray()
        self.views, self.accessors = [], []
        self.nodes, self.meshes, self.materials, self.skins, self.animations = [], [], [], [], []
        self.scene_nodes = []

    # ---- datos binarios ----
    def _view(self, data, target=None):
        while len(self.bin) % 4:
            self.bin.append(0)
        v = {"buffer": 0, "byteOffset": len(self.bin), "byteLength": len(data)}
        if target:
            v["target"] = target
        self.bin += data
        self.views.append(v)
        return len(self.views) - 1

    def accessor(self, arr, gl_type, comp, target=None, minmax=False):
        dt = {FLOAT: np.float32, USHORT: np.uint16, UINT: np.uint32}[comp]
        arr = np.ascontiguousarray(np.asarray(arr).astype(dt))
        a = {"bufferView": self._view(arr.tobytes(), target), "componentType": comp,
             "count": int(arr.shape[0]), "type": gl_type}
        if minmax:
            a["min"] = [float(x) for x in np.atleast_1d(arr.min(axis=0))]
            a["max"] = [float(x) for x in np.atleast_1d(arr.max(axis=0))]
        self.accessors.append(a)
        return len(self.accessors) - 1

    # ---- materiales ----
    def material(self, name, rgba, rough=0.8, metal=0.0, emissive=None):
        m = {"name": name, "pbrMetallicRoughness": {"baseColorFactor": list(rgba),
                                                     "metallicFactor": metal, "roughnessFactor": rough}}
        if rgba[3] < 1:
            m["alphaMode"] = "BLEND"
        if emissive:
            m["emissiveFactor"] = list(emissive)
        self.materials.append(m)
        return len(self.materials) - 1

    # ---- mallas ----
    def mesh(self, name, prims):
        """prims: lista de dict(pos, nrm, idx, mat, joints=None, weights=None)"""
        out = []
        for p in prims:
            attrs = {
                "POSITION": self.accessor(p["pos"], "VEC3", FLOAT, ARRAY_BUFFER, minmax=True),
                "NORMAL": self.accessor(p["nrm"], "VEC3", FLOAT, ARRAY_BUFFER),
            }
            if p.get("joints") is not None:
                attrs["JOINTS_0"] = self.accessor(p["joints"], "VEC4", USHORT, ARRAY_BUFFER)
                attrs["WEIGHTS_0"] = self.accessor(p["weights"], "VEC4", FLOAT, ARRAY_BUFFER)
            comp = UINT if len(p["pos"]) > 65535 else USHORT
            idx = self.accessor(np.asarray(p["idx"]).reshape(-1), "SCALAR", comp, ELEMENT_BUFFER)
            out.append({"attributes": attrs, "indices": idx, "material": p["mat"], "mode": 4})
        self.meshes.append({"name": name, "primitives": out})
        return len(self.meshes) - 1

    def node(self, name, t=None, r=None, s=None, mesh=None, skin=None, children=None):
        n = {"name": name}
        if t is not None and any(abs(x) > 1e-9 for x in t):
            n["translation"] = [float(x) for x in t]
        if r is not None:
            n["rotation"] = [float(x) for x in r]
        if s is not None:
            n["scale"] = [float(x) for x in s]
        if mesh is not None:
            n["mesh"] = mesh
        if skin is not None:
            n["skin"] = skin
        if children:
            n["children"] = children
        self.nodes.append(n)
        return len(self.nodes) - 1

    def skin(self, name, joints, ibm, skeleton):
        a = self.accessor(np.array(ibm, dtype=np.float32).reshape(-1, 16), "MAT4", FLOAT)
        self.skins.append({"name": name, "joints": joints, "inverseBindMatrices": a, "skeleton": skeleton})
        return len(self.skins) - 1

    def animation(self, name, channels, extras=None):
        """channels: lista de (node, 'translation'|'rotation', tiempos[N], valores[N,3|4])"""
        samplers, chans = [], []
        for node, path, times, vals in channels:
            tin = self.accessor(np.array(times, dtype=np.float32), "SCALAR", FLOAT, minmax=True)
            tout = self.accessor(np.array(vals, dtype=np.float32), "VEC3" if path == "translation" else "VEC4", FLOAT)
            samplers.append({"input": tin, "output": tout, "interpolation": "LINEAR"})
            chans.append({"sampler": len(samplers) - 1, "target": {"node": node, "path": path}})
        a = {"name": name, "samplers": samplers, "channels": chans}
        if extras:
            a["extras"] = extras
        self.animations.append(a)

    def save(self, path, scene_name="Scene"):
        j = {"asset": {"version": "2.0", "generator": "JARVIS BOXING AI build_assets.py"},
             "scene": 0, "scenes": [{"name": scene_name, "nodes": self.scene_nodes}],
             "nodes": self.nodes, "meshes": self.meshes, "materials": self.materials,
             "accessors": self.accessors, "bufferViews": self.views,
             "buffers": [{"byteLength": len(self.bin) + (-len(self.bin) % 4)}]}
        if self.skins:
            j["skins"] = self.skins
        if self.animations:
            j["animations"] = self.animations
        js = json.dumps(j, separators=(",", ":")).encode()
        js += b" " * (-len(js) % 4)
        b = bytes(self.bin) + b"\0" * (-len(self.bin) % 4)
        total = 12 + 8 + len(js) + 8 + len(b)
        with open(path, "wb") as f:
            f.write(struct.pack("<III", 0x46546C67, 2, total))
            f.write(struct.pack("<II", len(js), 0x4E4F534A)); f.write(js)
            f.write(struct.pack("<II", len(b), 0x004E4942)); f.write(b)
        return total


# ---------------- geometría ----------------

def loft(p0, p1, r0, r1, sides=8, caps=True):
    """Prisma/cono elíptico entre dos puntos sobre el eje Y. r = (rx, rz). Devuelve pos, nrm, idx."""
    p0, p1 = np.array(p0, float), np.array(p1, float)
    pos, nrm, idx = [], [], []
    for p, r in ((p0, r0), (p1, r1)):
        for i in range(sides):
            a = 2 * np.pi * i / sides
            c, s = np.cos(a), np.sin(a)
            pos.append(p + np.array([c * r[0], 0, s * r[1]]))
            n = np.array([c / max(r[0], 1e-6), 0, s / max(r[1], 1e-6)])
            nrm.append(n / np.linalg.norm(n))
    for i in range(sides):
        j = (i + 1) % sides
        idx += [i, j, i + sides, j, j + sides, i + sides]
    if caps:
        for k, (p, sign) in enumerate(((p0, -1), (p1, 1))):
            base = len(pos)
            r = (r0, r1)[k]
            pos.append(p); nrm.append([0, sign, 0])
            for i in range(sides):
                a = 2 * np.pi * i / sides
                pos.append(p + np.array([np.cos(a) * r[0], 0, np.sin(a) * r[1]])); nrm.append([0, sign, 0])
            for i in range(sides):
                j = (i + 1) % sides
                idx += [base, base + 1 + i, base + 1 + j] if sign > 0 else [base, base + 1 + j, base + 1 + i]
    return np.array(pos), np.array(nrm), np.array(idx)


def ellipsoid(c, r, seg=10, rings=6):
    c, r = np.array(c, float), np.array(r, float)
    pos, nrm, idx = [], [], []
    for i in range(rings + 1):
        th = np.pi * i / rings
        for k in range(seg):
            ph = 2 * np.pi * k / seg
            d = np.array([np.sin(th) * np.cos(ph), np.cos(th), np.sin(th) * np.sin(ph)])
            pos.append(c + d * r)
            n = d / np.maximum(r, 1e-6)
            nrm.append(n / np.linalg.norm(n))
    for i in range(rings):
        for k in range(seg):
            a, b = i * seg + k, i * seg + (k + 1) % seg
            idx += [a, b, a + seg, b, b + seg, a + seg]
    return np.array(pos), np.array(nrm), np.array(idx)


def box(c, h):
    c, h = np.array(c, float), np.array(h, float)
    faces = [((1, 0, 0), (0, 1, 0), (0, 0, 1)), ((-1, 0, 0), (0, 0, 1), (0, 1, 0)),
             ((0, 1, 0), (0, 0, 1), (1, 0, 0)), ((0, -1, 0), (1, 0, 0), (0, 0, 1)),
             ((0, 0, 1), (1, 0, 0), (0, 1, 0)), ((0, 0, -1), (0, 1, 0), (1, 0, 0))]
    pos, nrm, idx = [], [], []
    for n, u, v in faces:
        n, u, v = np.array(n, float), np.array(u, float), np.array(v, float)
        b = len(pos)
        for su, sv in ((-1, -1), (1, -1), (1, 1), (-1, 1)):
            pos.append(c + (n + su * u + sv * v) * h)
            nrm.append(n)
        idx += [b, b + 1, b + 2, b, b + 2, b + 3]
    return np.array(pos), np.array(nrm), np.array(idx)


def fix_winding(pos, nrm, idx):
    """Garantiza que cada triángulo mire hacia su normal (orden antihorario visto desde fuera)."""
    idx = np.asarray(idx).reshape(-1, 3).copy()
    for k, (a, b, c) in enumerate(idx):
        n = np.cross(pos[b] - pos[a], pos[c] - pos[a])
        if np.dot(n, nrm[a] + nrm[b] + nrm[c]) < 0:
            idx[k] = (a, c, b)
    return idx.reshape(-1)


def merge(parts):
    """parts: lista de (pos, nrm, idx, joint_index). Une en una sola primitiva con skinning rígido."""
    P, N, I, J = [], [], [], []
    off = 0
    for pos, nrm, idx, j in parts:
        idx = fix_winding(pos, nrm, idx)
        P.append(pos); N.append(nrm); I.append(np.asarray(idx) + off); J += [j] * len(pos)
        off += len(pos)
    return np.vstack(P), np.vstack(N), np.concatenate(I), np.array(J)
