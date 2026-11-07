import numpy as np
from scipy.optimize import least_squares
import clips
from rig import fk
def glove(q, ht, s="L"):
    W, M = fk(q, ht)
    return (M["Hand_"+s] @ np.array([0,0,0.015,1]))[:3], W["Forearm_"+s]
def tune(base, tgt_g, tgt_elbow_y, x0, bounds, side="L"):
    ua, fo = "UpperArm_"+side, "Forearm_"+side
    def res(v):
        p = clips.with_(base, **{ua:(v[0],v[3],v[1]), fo:(v[2],0,0)})
        q, ht = clips.finalize(p)
        g, el = glove(q, ht, side)
        return np.concatenate([(g-tgt_g)*3, [(el[1]-tgt_elbow_y)*2]])
    r = least_squares(res, x0, bounds=bounds)
    return r.x, np.linalg.norm(r.fun)
for name, base, tg, ey, x0 in [
  ("HOOK", clips.HOOK_HIT, np.array([0.02,1.52,0.46]), 1.46, [-35,82,-85,0]),
  ("BODY", clips.BODY_HIT, np.array([0.04,1.02,0.42]), 1.10, [-30,62,-80,0]),
]:
    x, e = tune(base, tg, ey, x0, ([-100,-30,-170,-60],[20,120,0,60]))
    print(name, "UpperArm rx=%.0f rz=%.0f ry_extra=%.0f  Forearm rx=%.0f  err=%.3f" % (x[0],x[1],x[3],x[2],e))
    p = clips.with_(base, UpperArm_L=(x[0],x[3],x[1]), Forearm_L=(x[2],0,0))
    q, ht = clips.finalize(p); g,el = glove(q,ht); print("  glove", g.round(2), "elbow", el.round(2))
