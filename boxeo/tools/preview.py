"""Dibuja las poses clave de las animaciones (vista lateral y desde arriba) para comprobar que son plausibles."""
import sys
import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import clips
from rig import NAMES, PARENT, fk

def draw(ax, q, ht, view, color, alpha=1.0):
    W, M = fk(q, ht)
    hx = {"side": 2, "top": 0, "front": 0}[view]
    vy = {"side": 1, "top": 2, "front": 1}[view]
    for n in NAMES:
        p = PARENT[n]
        if p:
            ax.plot([W[p][hx], W[n][hx]], [W[p][vy], W[n][vy]], color=color, lw=1.6, alpha=alpha)
    for s in ("L", "R"):
        g = (M["Hand_" + s] @ np.array([0, -0.0, 0.015, 1]))[:3]
        ax.scatter([g[hx]], [g[vy]], s=60, color=color, alpha=alpha, marker="o" if s == "L" else "s")
    head = (M["Head"] @ np.array([0, 0.10, 0, 1]))[:3]
    ax.scatter([head[hx]], [head[vy]], s=220, facecolors="none", edgecolors=color, alpha=alpha)
    # nariz = frente
    nose = (M["Head"] @ np.array([0, 0.085, 0.11, 1]))[:3]
    ax.scatter([nose[hx]], [nose[vy]], s=14, color=color, alpha=alpha)

def run(names, out, views=("side", "top"), cols=None):
    allc = {c["name"]: c for c in clips.build_all()}
    rows = len(names); cols = len(views)
    fig, axs = plt.subplots(rows, cols, figsize=(4.2 * cols, 3.6 * rows), squeeze=False)
    cmap = plt.get_cmap("viridis")
    for r, nm in enumerate(names):
        ev = clips.evaluate(allc[nm])
        for c, v in enumerate(views):
            ax = axs[r][c]
            for i, (t, quats, ht, q) in enumerate(ev):
                draw(ax, q, ht, v, cmap(i / max(1, len(ev) - 1)))
            ax.set_aspect("equal"); ax.grid(alpha=.3)
            if v == "side":
                ax.axhline(0, color="k", lw=.8); ax.set_xlim(-1.3, 1.3); ax.set_ylim(-0.1, 2.0)
            elif v == "top":
                ax.set_xlim(-1.0, 1.0); ax.set_ylim(-1.0, 1.2)
            else:
                ax.axhline(0, color="k", lw=.8); ax.set_xlim(-1, 1); ax.set_ylim(-0.1, 2.0)
            ax.set_title(f"{nm} [{v}] t={ev[0][0]:.2f}->{ev[-1][0]:.2f}s", fontsize=8)
    plt.tight_layout(); plt.savefig(out, dpi=70); print("ok", out)

if __name__ == "__main__":
    out = sys.argv[1]; views = sys.argv[2].split(","); names = sys.argv[3:]
    run(names, out, views)
