"""Sintetiza efectos de sonido y música simple (WAV mono 22.05 kHz, 16 bit). Todo generado por código: sin derechos de autor.
NO incluye voces: los locutores/comentaristas/árbitros requieren voces con licencia o TTS (ver AUDIO/announcer_lines.json)."""
import os, wave
import numpy as np
from scipy.signal import butter, lfilter

SR = 22050
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
rng = np.random.default_rng(7)

def t(d): return np.arange(int(SR * d)) / SR
def lp(x, f): b, a = butter(2, f / (SR / 2), "low"); return lfilter(b, a, x)
def hp(x, f): b, a = butter(2, f / (SR / 2), "high"); return lfilter(b, a, x)
def bp(x, lo, hi): b, a = butter(2, [lo / (SR / 2), hi / (SR / 2)], "band"); return lfilter(b, a, x)
def env(n, a=0.002, d=0.1):
    tt = np.arange(n) / SR
    return np.minimum(tt / a, 1) * np.exp(-tt / d)
def noise(n): return rng.standard_normal(n)

def save(name, x, peak=0.85):
    x = np.asarray(x, float); x = x / (np.abs(x).max() + 1e-9) * peak
    path = os.path.join(ROOT, "AUDIO", name)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with wave.open(path, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR); w.writeframes((x * 32767).astype("<i2").tobytes())
    return dict(file=f"AUDIO/{name}", seconds=round(len(x) / SR, 2), kb=round(os.path.getsize(path) / 1024, 1))

def punch(dur, f0, noise_lo, noise_amt, decay):
    n = int(SR * dur); tt = np.arange(n) / SR
    thump = np.sin(2 * np.pi * (f0 + 60 * np.exp(-tt * 40)) * tt) * env(n, 0.001, decay)
    sl = lp(noise(n), noise_lo) * env(n, 0.0008, decay * 0.45) * noise_amt
    return thump + sl

def bell(dur, f=830):
    n = int(SR * dur); tt = np.arange(n) / SR; x = np.zeros(n)
    for k, (m, a, d) in enumerate([(1, 1, 1.4), (2.76, .6, 0.9), (5.4, .35, .6), (8.93, .2, .4)]):
        x += a * np.sin(2 * np.pi * f * m * tt) * np.exp(-tt / d)
    return x * np.minimum(tt / 0.002, 1)

def loopable(x, fade=0.4):
    n = int(SR * fade); y = x.copy(); w = np.linspace(0, 1, n)
    y[:n] = x[:n] * w + x[-n:] * (1 - w)
    return y[:-n]

def mix(parts, total):
    out = np.zeros(int(SR * total))
    for start, x in parts:
        i = int(start * SR); out[i:i + len(x)] += x[:max(0, len(out) - i)]
    return out

def build_all():
    R = {}
    R["punch_light"] = save("punch_light.wav", punch(0.22, 140, 3500, 0.9, 0.06))
    R["punch_heavy"] = save("punch_heavy.wav", punch(0.40, 80, 2200, 1.2, 0.11))
    R["punch_body"] = save("punch_body.wav", punch(0.30, 65, 900, 0.8, 0.09))
    R["block_hit"] = save("block_hit.wav", punch(0.16, 210, 1500, 0.6, 0.035))
    n = int(SR * 0.28); sw = bp(noise(n), 500, 4500) * np.sin(np.pi * np.arange(n) / n) ** 1.5
    R["whoosh"] = save("whoosh.wav", sw)
    n = int(SR * 0.9); tt = np.arange(n) / SR
    R["knockdown_thud"] = save("knockdown_thud.wav", np.sin(2 * np.pi * 48 * tt) * env(n, .002, .25) + lp(noise(n), 400) * env(n, .001, .12) * 1.2)
    R["bell_ding"] = save("bell_ding.wav", bell(1.8))
    d = bell(1.4)
    R["bell_round_start"] = save("bell_round_start.wav", mix([(0, d)], 1.6))
    R["bell_round_end"] = save("bell_round_end.wav", mix([(0, d), (0.35, d), (0.7, d)], 2.2))
    n = int(SR * 0.12); tt = np.arange(n) / SR
    R["count_tick"] = save("count_tick.wav", np.sin(2 * np.pi * 660 * tt) * env(n, .002, .04))
    R["ui_click"] = save("ui_click.wav", np.sin(2 * np.pi * 900 * tt[:int(SR * .05)]) * env(int(SR * .05), .001, .015))
    n = int(SR * 0.3); tt = np.arange(n) / SR
    R["ui_confirm"] = save("ui_confirm.wav", (np.sin(2 * np.pi * 660 * tt) + np.sin(2 * np.pi * 990 * tt) * (tt > 0.08)) * env(n, .003, .12))
    # público
    n = int(SR * 9); base = bp(noise(n), 250, 2600)
    mod = 0.6 + 0.4 * np.sin(2 * np.pi * 0.21 * np.arange(n) / SR) * np.sin(2 * np.pi * 0.07 * np.arange(n) / SR + 1)
    R["crowd_loop"] = save("crowd_loop.wav", loopable(base * mod), 0.6)
    n = int(SR * 3.2); tt = np.arange(n) / SR
    sw = np.sin(np.pi * np.minimum(tt / 3.2, 1)) ** 2
    R["crowd_cheer"] = save("crowd_cheer.wav", (bp(noise(n), 300, 3500) * (0.5 + 0.5 * np.sin(2 * np.pi * 7 * tt)) + bp(noise(n), 1200, 5000) * 0.4) * sw)
    n = int(SR * 8); R["gym_ambience_loop"] = save("gym_ambience_loop.wav", loopable(lp(noise(n), 300) * 0.5 + np.sin(2 * np.pi * 55 * np.arange(n) / SR) * 0.2 + hp(noise(n), 3000) * 0.03), 0.5)
    # fanfarrias
    def tone(f, d):
        n = int(SR * d); tt = np.arange(n) / SR
        return (np.sin(2 * np.pi * f * tt) + .4 * np.sin(4 * np.pi * f * tt)) * env(n, .01, d * .5)
    R["stinger_win"] = save("stinger_win.wav", mix([(i * 0.16, tone(f, 0.7)) for i, f in enumerate([392, 494, 587, 784, 988])], 1.9))
    R["stinger_lose"] = save("stinger_lose.wav", mix([(i * 0.3, tone(f, 0.9)) for i, f in enumerate([392, 349, 311, 262])], 2.4))
    # música de menú: 100 BPM, 8 compases
    bpm = 100; beat = 60 / bpm; bars = 8; total = bars * 4 * beat
    out = np.zeros(int(SR * total))
    def add(start, x):
        i = int(start * SR); out[i:i + len(x)] += x[:max(0, len(out) - i)]
    kick = punch(0.3, 55, 300, 0.0, 0.12) * 1.4
    snare = lp(noise(int(SR * .2)), 6000) * env(int(SR * .2), .001, .06) * 0.5
    hat = hp(noise(int(SR * .05)), 6000) * env(int(SR * .05), .0005, .015) * 0.25
    roots = [110, 110, 87.3, 98] * 2
    for bar in range(bars):
        for b in range(4):
            tm = (bar * 4 + b) * beat
            if b in (0, 2): add(tm, kick)
            if b in (1, 3): add(tm, snare)
            add(tm, hat); add(tm + beat / 2, hat)
        r = roots[bar]
        for k in range(8):
            f = r * [1, 1, 2, 1, 1.5, 1, 2, 1.5][k]
            nn = int(SR * beat / 2); tt = np.arange(nn) / SR
            add((bar * 4) * beat + k * beat / 2, (np.sign(np.sin(2 * np.pi * f * tt)) * 0.25 + np.sin(2 * np.pi * f * tt) * 0.5) * env(nn, .005, .2))
        pad = (np.sin(2 * np.pi * r * 4 * np.arange(int(SR * 4 * beat)) / SR) + np.sin(2 * np.pi * r * 4 * 1.2 * np.arange(int(SR * 4 * beat)) / SR)) * 0.08
        add(bar * 4 * beat, pad * np.minimum(np.arange(len(pad)) / (SR * 0.3), 1) * np.minimum((len(pad) - np.arange(len(pad))) / (SR * 0.3), 1))
    R["menu_theme_loop"] = save("menu_theme_loop.wav", out, 0.7)
    return R

if __name__ == "__main__":
    for k, v in build_all().items(): print(f"{v['file']:34s} {v['seconds']:5.1f}s {v['kb']:7.1f} KB")
