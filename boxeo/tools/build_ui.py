"""Genera logo (SVG + PNG), icono de la app y maquetas de referencia de las pantallas (PNG).
El SVG no usa texto (las letras son rectángulos de una fuente pixel propia) para que Godot lo importe igual en cualquier teléfono."""
import os
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
G = {}
def _g(ch, rows): G[ch] = rows.split(",")
for ch, rows in {
 "A":"01110,10001,10001,11111,10001,10001,10001","B":"11110,10001,10001,11110,10001,10001,11110","C":"01111,10000,10000,10000,10000,10000,01111",
 "D":"11110,10001,10001,10001,10001,10001,11110","E":"11111,10000,10000,11110,10000,10000,11111","F":"11111,10000,10000,11110,10000,10000,10000",
 "G":"01111,10000,10000,10111,10001,10001,01111","H":"10001,10001,10001,11111,10001,10001,10001","I":"11111,00100,00100,00100,00100,00100,11111",
 "J":"00111,00010,00010,00010,00010,10010,01100","K":"10001,10010,10100,11000,10100,10010,10001","L":"10000,10000,10000,10000,10000,10000,11111",
 "M":"10001,11011,10101,10101,10001,10001,10001","N":"10001,11001,10101,10011,10001,10001,10001","O":"01110,10001,10001,10001,10001,10001,01110",
 "P":"11110,10001,10001,11110,10000,10000,10000","Q":"01110,10001,10001,10001,10101,10010,01101","R":"11110,10001,10001,11110,10100,10010,10001",
 "S":"01111,10000,10000,01110,00001,00001,11110","T":"11111,00100,00100,00100,00100,00100,00100","U":"10001,10001,10001,10001,10001,10001,01110",
 "V":"10001,10001,10001,10001,10001,01010,00100","W":"10001,10001,10001,10101,10101,11011,10001","X":"10001,10001,01010,00100,01010,10001,10001",
 "Y":"10001,10001,01010,00100,00100,00100,00100","Z":"11111,00001,00010,00100,01000,10000,11111",
 "0":"01110,10001,10011,10101,11001,10001,01110","1":"00100,01100,00100,00100,00100,00100,01110","2":"01110,10001,00001,00010,00100,01000,11111",
 "3":"11110,00001,00001,01110,00001,00001,11110","4":"00010,00110,01010,10010,11111,00010,00010","5":"11111,10000,11110,00001,00001,10001,01110",
 "6":"00110,01000,10000,11110,10001,10001,01110","7":"11111,00001,00010,00100,01000,01000,01000","8":"01110,10001,10001,01110,10001,10001,01110",
 "9":"01110,10001,10001,01111,00001,00010,01100"," ":"00000,00000,00000,00000,00000,00000,00000",":":"00000,00100,00100,00000,00100,00100,00000",
 "-":"00000,00000,00000,11111,00000,00000,00000",".":"00000,00000,00000,00000,00000,01100,01100","/":"00001,00010,00010,00100,01000,01000,10000",
 "%":"11001,11010,00010,00100,01000,01011,10011","!":"00100,00100,00100,00100,00100,00000,00100","+":"00000,00100,00100,11111,00100,00100,00000",
 ">":"01000,00100,00010,00001,00010,00100,01000","<":"00010,00100,01000,10000,01000,00100,00010"}.items(): _g(ch, rows)

def hexrgb(h): h = h.lstrip("#"); return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))
def lerp(a, b, f): return tuple(int(a[i] + (b[i] - a[i]) * f) for i in range(3))
def text_width(s, sc): return len(s) * 6 * sc - sc

class Scene:
    def __init__(self, w, h, bg=None): self.w, self.h, self.bg, self.ops = w, h, bg, []
    def rect(self, x, y, w, h, fill, r=0, outline=None): self.ops.append(("rect", x, y, w, h, fill, r, outline))
    def circle(self, cx, cy, r, fill): self.ops.append(("circle", cx, cy, r, fill))
    def text(self, x, y, s, sc, fill, grad=None):
        """grad=(color_arriba, color_abajo) pinta un degradado por filas."""
        for i, ch in enumerate(s.upper()):
            for ry, row in enumerate(G.get(ch, G[" "])):
                col = lerp(hexrgb(grad[0]), hexrgb(grad[1]), ry / 6) if grad else hexrgb(fill)
                for rx, c in enumerate(row):
                    if c == "1": self.rect(x + (i * 6 + rx) * sc, y + ry * sc, sc, sc, "#%02x%02x%02x" % col)
    def glove(self, x, y, k):
        """Guante de boxeo en un cuadrado de 200x200 escalado por k."""
        s = lambda v: v * k
        self.rect(x + s(62), y + s(140), s(76), s(42), "#d9dde3", int(s(7)))
        self.rect(x + s(62), y + s(150), s(76), s(6), "#aab0ba")
        self.rect(x + s(138), y + s(92), s(40), s(56), "#a81d1d", int(s(20)))
        self.rect(x + s(40), y + s(34), s(112), s(116), "#d62828", int(s(44)))
        self.circle(x + s(84), y + s(78), s(15), "#ff7a7a")
        for i in range(3): self.rect(x + s(86 + i * 18), y + s(106), s(5), s(30), "#9f1a1a", int(s(2)))
    def png(self, path):
        im = Image.new("RGBA", (int(self.w), int(self.h)), self.bg or (0, 0, 0, 0)); d = ImageDraw.Draw(im)
        for op in self.ops:
            if op[0] == "rect":
                _, x, y, w, h, fill, r, ol = op
                if r: d.rounded_rectangle([x, y, x + w - 1, y + h - 1], radius=r, fill=fill, outline=ol)
                else: d.rectangle([x, y, x + w - 1, y + h - 1], fill=fill, outline=ol)
            else:
                _, cx, cy, r, fill = op; d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=fill)
        im.save(path)
    def svg(self, path):
        o = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{int(self.w)}" height="{int(self.h)}" viewBox="0 0 {int(self.w)} {int(self.h)}">']
        if self.bg: o.append(f'<rect width="100%" height="100%" fill="{self.bg}"/>')
        for op in self.ops:
            if op[0] == "rect":
                _, x, y, w, h, fill, r, ol = op
                o.append(f'<rect x="{x:g}" y="{y:g}" width="{w:g}" height="{h:g}" rx="{r:g}" fill="{fill}"' + (f' stroke="{ol}"' if ol else "") + "/>")
            else:
                _, cx, cy, r, fill = op; o.append(f'<circle cx="{cx:g}" cy="{cy:g}" r="{r:g}" fill="{fill}"/>')
        o.append("</svg>"); open(path, "w").write("\n".join(o))

def p(*a): 
    path = os.path.join(ROOT, *a); os.makedirs(os.path.dirname(path), exist_ok=True); return path

def logo():
    s = Scene(1600, 560)
    s.glove(20, 30, 2.65)
    s.text(640, 130, "JARVIS", 26, None, grad=("#ffb347", "#e63946"))
    s.rect(640, 345, 910, 10, "#e63946")
    s.text(640, 385, "BOXING", 12, "#ffffff"); s.text(640 + text_width("BOXING ", 12), 385, "AI", 12, "#25d0ff")
    s.svg(p("LOGOS", "jarvis_boxing_ai_logo.svg")); s.png(p("LOGOS", "jarvis_boxing_ai_logo.png"))
    ic = Scene(512, 512, "#0f172a"); ic.rect(0, 0, 512, 512, "#0f172a", 96); ic.glove(60, 40, 1.9)
    ic.text(120, 410, "JBAI", 14, None, grad=("#ffb347", "#e63946"))
    ic.svg(p("LOGOS", "app_icon.svg")); ic.png(p("LOGOS", "app_icon_512.png"))

FIGHTERS_PREVIEW = [("TITO VARGAS", "PRESION"), ("KAI MORROW", "TECNICO"), ("DOMINGO REYES", "CONTRAGOLPE"), ("LEV ZORIN", "ESTRATEGA"),
                    ("ABEL OKONKWO", "RESISTENTE"), ("SORA TANAKA", "VELOZ"), ("MATEO BRANDT", "DEFENSIVO"), ("RAFA CORTEZ", "AGRESIVO")]

def mock_menu():
    s = Scene(1280, 720, "#0b1020")
    for i in range(0, 720, 8): s.rect(0, i, 1280, 8, "#%02x%02x%02x" % lerp((11, 16, 32), (40, 12, 22), i / 720))
    s.glove(60, 40, 1.4); s.text(360, 70, "JARVIS", 14, None, grad=("#ffb347", "#e63946")); s.text(360, 175, "BOXING AI", 6, "#ffffff")
    for i, b in enumerate(["CARRERA", "COMBATE RAPIDO", "ENTRENAMIENTO", "PERSONALIZAR", "AJUSTES"]):
        y = 300 + i * 74; sel = i == 0
        s.rect(80, y, 460, 60, "#e63946" if sel else "#1b2440", 10, "#ffffff" if sel else "#2c3a66"); s.text(110, y + 18, b, 4, "#ffffff")
    s.rect(700, 120, 500, 480, "#121a33", 16, "#2c3a66"); s.text(730, 140, "TEMPORADA 1", 4, "#25d0ff")
    s.text(730, 200, "RANKING 12", 4, "#ffffff"); s.text(730, 250, "PROX COMBATE", 3, "#9fb0d8"); s.text(730, 290, "VS TITO VARGAS", 4, "#ffffff")
    s.text(730, 340, "ARENA CIUDAD", 3, "#9fb0d8"); s.rect(730, 420, 440, 60, "#e63946", 10); s.text(830, 438, "CONTINUAR", 4, "#ffffff")
    s.png(p("UI", "mockup_main_menu.png"))

def mock_select():
    s = Scene(1280, 720, "#0b1020"); s.text(40, 30, "ELIGE BOXEADOR", 6, "#ffffff")
    for i, (n, st) in enumerate(FIGHTERS_PREVIEW):
        x, y = 40 + (i % 4) * 190, 120 + (i // 4) * 250; sel = i == 1
        s.rect(x, y, 175, 235, "#1b2440", 12, "#e63946" if sel else "#2c3a66")
        s.rect(x + 50, y + 25, 75, 110, "#2c3a66", 30); s.text(x + 10, y + 150, n.split()[0], 3, "#ffffff"); s.text(x + 10, y + 180, st, 2, "#25d0ff")
    s.rect(830, 100, 410, 560, "#121a33", 16, "#2c3a66"); s.text(855, 120, "KAI MORROW", 5, "#ffffff"); s.text(855, 165, "TECNICO / ORTODOXO", 2, "#25d0ff")
    for i, (k, v) in enumerate([("PODER", 62), ("VELOCIDAD", 78), ("AGUANTE", 70), ("MANDIBULA", 66), ("DEFENSA", 74), ("PRECISION", 82)]):
        y = 220 + i * 52; s.text(855, y, k, 3, "#9fb0d8"); s.rect(1010, y, 200, 22, "#0b1020", 6); s.rect(1010, y, int(2 * v), 22, "#e63946", 6)
    s.rect(855, 590, 360, 52, "#e63946", 10); s.text(930, 605, "ELEGIR", 5, "#ffffff"); s.png(p("UI", "mockup_fighter_select.png"))

def mock_hud():
    s = Scene(1280, 720, "#1a2238")
    for i in range(0, 720, 6): s.rect(0, i, 1280, 6, "#%02x%02x%02x" % lerp((26, 34, 56), (14, 18, 30), i / 720))
    s.rect(0, 470, 1280, 250, "#0d1220"); s.rect(300, 330, 680, 14, "#c9ccd6"); s.rect(340, 250, 600, 6, "#e63946"); s.rect(340, 290, 600, 6, "#ffffff")
    s.rect(500, 300, 70, 170, "#2c5aa0", 20); s.rect(710, 300, 70, 170, "#a02c2c", 20)
    s.rect(30, 24, 480, 26, "#0b1020", 8); s.rect(30, 24, 400, 26, "#46d17a", 8); s.rect(30, 56, 480, 12, "#0b1020", 5); s.rect(30, 56, 330, 12, "#ffd166", 5)
    s.rect(770, 24, 480, 26, "#0b1020", 8); s.rect(770, 24, 300, 26, "#46d17a", 8); s.rect(770, 56, 480, 12, "#0b1020", 5); s.rect(770, 56, 410, 12, "#ffd166", 5)
    s.text(30, 80, "KAI MORROW", 3, "#ffffff"); s.text(1060, 80, "TITO VARGAS", 3, "#ffffff")
    s.rect(560, 20, 160, 70, "#0b1020", 12, "#2c3a66"); s.text(585, 28, "R2", 4, "#25d0ff"); s.text(585, 62, "1:42", 4, "#ffffff")
    for i, (b, x, y) in enumerate([("JAB", 1040, 540), ("CROSS", 1140, 480), ("HOOK", 940, 480), ("UPPER", 1040, 440), ("BODY", 1140, 590)]):
        s.circle(x + 40, y + 40, 48, "#e63946" if i != 4 else "#d98324"); s.text(x + 40 - text_width(b, 2) // 2, y + 33, b, 2, "#ffffff")
    s.circle(100, 600, 56, "#2c3a66"); s.text(75, 592, "BLOQ", 2, "#ffffff"); s.circle(240, 600, 44, "#2c3a66"); s.text(222, 592, "<<", 3, "#ffffff"); s.circle(340, 600, 44, "#2c3a66"); s.text(322, 592, ">>", 3, "#ffffff")
    s.circle(100, 470, 40, "#3a4a7a"); s.text(84, 462, "ESQ", 2, "#ffffff"); s.circle(210, 470, 40, "#3a4a7a"); s.text(196, 462, "ESQ", 2, "#ffffff")
    s.png(p("UI", "mockup_combat_hud.png"))

def mock_results():
    s = Scene(1280, 720, "#0b1020"); s.text(380, 40, "VICTORIA POR DECISION", 5, "#ffd166")
    s.rect(240, 130, 800, 360, "#121a33", 16, "#2c3a66")
    for i, (j, a, b) in enumerate([("JUEZ 1", 29, 28), ("JUEZ 2", 30, 27), ("JUEZ 3", 29, 28)]):
        y = 170 + i * 80; s.text(280, y, j, 4, "#9fb0d8"); s.text(640, y, f"{a}", 6, "#46d17a"); s.text(800, y, f"{b}", 6, "#ffffff")
    s.text(280, 420, "GOLPES 84/150  KNOCKDOWNS 1", 3, "#ffffff")
    s.rect(240, 540, 380, 70, "#e63946", 12); s.text(300, 562, "SIGUIENTE", 5, "#ffffff"); s.rect(660, 540, 380, 70, "#1b2440", 12, "#2c3a66"); s.text(710, 562, "REPETICION", 5, "#ffffff")
    s.png(p("UI", "mockup_results.png"))

def build_all():
    logo(); mock_menu(); mock_select(); mock_hud(); mock_results()
    return ["LOGOS/jarvis_boxing_ai_logo.svg", "LOGOS/jarvis_boxing_ai_logo.png", "LOGOS/app_icon.svg", "LOGOS/app_icon_512.png",
            "UI/mockup_main_menu.png", "UI/mockup_fighter_select.png", "UI/mockup_combat_hud.png", "UI/mockup_results.png"]

if __name__ == "__main__": print(build_all())
