"""Genera los archivos de datos JSON (boxeadores ficticios, perfiles de IA, tabla de animaciones, arenas, carrera, campeonatos...)."""
import os, json
import numpy as np
import rig, clips

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

def dump(rel, obj):
    path = os.path.join(ROOT, rel); os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f: json.dump(obj, f, ensure_ascii=False, indent=1)
    return rel

def S(power, speed, stamina, chin, defense, accuracy, footwork, ring_iq):
    return dict(power=power, speed=speed, stamina=stamina, chin=chin, defense=defense, accuracy=accuracy, footwork=footwork, ring_iq=ring_iq)

def L(skin, hair, shorts, gloves, shoes="#111111"): return dict(skin=skin, hair=hair, shorts=shorts, gloves=gloves, shoes=shoes)

def fighters():
    F = [
     dict(id="tito_vargas", name="Tito Vargas", nickname="El Trueno", country="México", weight_class="welter", height_cm=172, weight_kg=66, reach_cm=174, age=27, stance="orthodox", ai_profile="aggressive",
          stats=S(78, 70, 74, 68, 52, 66, 62, 55), look=L("#b07a55", "#14100c", "#1f8a3c", "#c62828"), record=dict(w=24, l=2, ko=19), bio="Peleador de ataque constante que busca el nocaut desde el primer round."),
     dict(id="kai_morrow", name="Kai Morrow", nickname="Metrónomo", country="Estados Unidos", weight_class="medio", height_cm=183, weight_kg=72, reach_cm=188, age=29, stance="orthodox", ai_profile="technical",
          stats=S(62, 78, 72, 66, 74, 82, 76, 80), look=L("#6b4630", "#0d0d0d", "#1d3f8f", "#e0e0e0"), record=dict(w=31, l=1, ko=14), bio="Boxeador de manual: jab, distancia y precisión quirúrgica."),
     dict(id="domingo_reyes", name="Domingo Reyes", nickname="La Sombra", country="Argentina", weight_class="ligero", height_cm=168, weight_kg=61, reach_cm=171, age=31, stance="southpaw", ai_profile="counterpuncher",
          stats=S(64, 80, 70, 64, 80, 78, 82, 84), look=L("#c08c64", "#2a1a10", "#6fb7e8", "#ffffff"), record=dict(w=38, l=4, ko=17), bio="Zurdo escurridizo que castiga cada error del rival."),
     dict(id="lev_zorin", name="Lev Zorin", nickname="El Ajedrecista", country="Ucrania", weight_class="pesado", height_cm=195, weight_kg=102, reach_cm=201, age=33, stance="southpaw", ai_profile="strategic",
          stats=S(76, 62, 80, 78, 72, 74, 60, 92), look=L("#d6a98a", "#4a3a20", "#f2c200", "#1f1f1f"), record=dict(w=22, l=0, ko=15), bio="Lee el combate como una partida y cambia de plan según el marcador."),
     dict(id="abel_okonkwo", name="Abel Okonkwo", nickname="Roca", country="Nigeria", weight_class="supermedio", height_cm=185, weight_kg=76, reach_cm=190, age=28, stance="orthodox", ai_profile="durable",
          stats=S(80, 58, 88, 92, 60, 62, 55, 60), look=L("#3f2a1e", "#000000", "#7a1fa2", "#f5f5f5"), record=dict(w=27, l=3, ko=21), bio="Aguanta lo imposible y responde con golpes de poder."),
     dict(id="sora_tanaka", name="Sora Tanaka", nickname="Destello", country="Japón", weight_class="pluma", height_cm=165, weight_kg=57, reach_cm=168, age=24, stance="southpaw", ai_profile="fast",
          stats=S(48, 95, 78, 52, 70, 76, 90, 70), look=L("#d9ad8a", "#0a0a0a", "#e53935", "#ffffff"), record=dict(w=19, l=2, ko=6), bio="Velocidad de manos y piernas fuera de serie."),
     dict(id="mateo_brandt", name="Mateo Brandt", nickname="El Muro", country="Alemania", weight_class="semipesado", height_cm=188, weight_kg=79, reach_cm=191, age=30, stance="orthodox", ai_profile="defensive",
          stats=S(58, 60, 76, 80, 90, 70, 64, 78), look=L("#e0b89c", "#7a5a2a", "#222222", "#d4af37"), record=dict(w=26, l=2, ko=9), bio="Guardia cerrada y paciencia: gana por puntos sin recibir."),
     dict(id="rafa_cortez", name="Rafa Cortez", nickname="Tornado", country="Colombia", weight_class="superwelter", height_cm=175, weight_kg=69, reach_cm=177, age=26, stance="orthodox", ai_profile="pressure",
          stats=S(72, 72, 90, 70, 50, 64, 66, 52), look=L("#9a6a48", "#1a0f08", "#f57c00", "#212121"), record=dict(w=21, l=3, ko=13), bio="Presiona sin descanso hasta ahogar al rival."),
     dict(id="nico_haddad", name="Nico Haddad", nickname="Pistón", country="Líbano", weight_class="ligero", height_cm=172, weight_kg=61, reach_cm=173, age=25, stance="orthodox", ai_profile="pressure",
          stats=S(66, 74, 92, 74, 56, 60, 68, 58), look=L("#b98c68", "#120c08", "#00897b", "#fafafa"), record=dict(w=17, l=1, ko=9), bio="Motor inagotable: combina golpes al cuerpo y cabeza."),
     dict(id="jonas_lindqvist", name="Jonas Lindqvist", nickname="Hielo", country="Suecia", weight_class="crucero", height_cm=191, weight_kg=89, reach_cm=196, age=32, stance="orthodox", ai_profile="technical",
          stats=S(70, 66, 74, 72, 76, 80, 68, 82), look=L("#e8c4a8", "#c8a860", "#0d47a1", "#ffffff"), record=dict(w=25, l=2, ko=16), bio="Alcance largo y combinaciones limpias."),
     dict(id="diego_salinas", name="Diego Salinas", nickname="Chispa", country="Perú", weight_class="gallo", height_cm=162, weight_kg=53, reach_cm=165, age=23, stance="orthodox", ai_profile="counterpuncher",
          stats=S(52, 86, 72, 56, 76, 72, 84, 74), look=L("#a77455", "#0f0a06", "#c62828", "#ffd600"), record=dict(w=14, l=1, ko=5), bio="Pequeño y ágil, siempre espera el error para contraatacar."),
     dict(id="bruno_kessler", name="Bruno Kessler", nickname="El Yunque", country="Austria", weight_class="pesado", height_cm=193, weight_kg=108, reach_cm=198, age=34, stance="orthodox", ai_profile="durable",
          stats=S(88, 48, 70, 94, 48, 56, 42, 54), look=L("#dcb496", "#6b4a2a", "#37474f", "#b71c1c"), record=dict(w=29, l=5, ko=26), bio="Pegada devastadora y mandíbula de hierro, pero lento."),
    ]
    return dump("CHARACTERS/fighters.json", dict(note="Boxeadores FICTICIOS originales. No representan a ninguna persona real.", fighters=F))

def combo(*a, w=1.0): return dict(seq=list(a), w=w)

def ai_profiles():
    P = {
     "aggressive": dict(label="Agresivo", desc="Ataca primero y seguido; defiende poco.", aggression=0.85, tempo=0.55, reaction=0.45, react_time=0.28, guard_hold=0.25, block_pref=0.4, dodge_pref=0.2, counter=0.35, pressure=0.7, range_pref=0.85, learning=0.35, stamina_rest=22, speed=1.05,
                        combos=[combo("jab", "cross", w=2), combo("jab", "cross", "lead_hook", w=3), combo("lead_hook", "cross", w=2), combo("cross", "lead_hook", "rear_uppercut", w=1.5), combo("body_hook", "cross", w=1.5)]),
     "technical": dict(label="Técnico", desc="Jab, distancia y combinaciones limpias.", aggression=0.5, tempo=0.7, reaction=0.7, react_time=0.2, guard_hold=0.5, block_pref=0.5, dodge_pref=0.35, counter=0.4, pressure=0.3, range_pref=1.0, learning=0.55, stamina_rest=30, speed=1.0,
                       combos=[combo("jab", w=4), combo("jab", "jab", "cross", w=3), combo("jab", "cross", "lead_hook", w=2), combo("jab", "body_hook", "cross", w=1.5)]),
     "defensive": dict(label="Defensivo", desc="Guardia alta, castiga con pocos golpes seguros.", aggression=0.3, tempo=0.95, reaction=0.75, react_time=0.22, guard_hold=0.8, block_pref=0.85, dodge_pref=0.15, counter=0.45, pressure=0.15, range_pref=1.0, learning=0.5, stamina_rest=35, speed=0.95,
                       combos=[combo("jab", w=4), combo("jab", "cross", w=2), combo("body_hook", w=1.5)]),
     "counterpuncher": dict(label="Contraatacador", desc="Espera tu ataque y responde en la recuperación.", aggression=0.35, tempo=0.9, reaction=0.8, react_time=0.18, guard_hold=0.45, block_pref=0.35, dodge_pref=0.65, counter=0.9, pressure=0.1, range_pref=1.05, learning=0.65, stamina_rest=30, speed=1.0,
                            combos=[combo("cross", w=3), combo("lead_hook", "cross", w=3), combo("jab", "cross", w=2), combo("rear_uppercut", w=1.5)]),
     "fast": dict(label="Veloz", desc="Golpes rápidos y mucho movimiento.", aggression=0.65, tempo=0.4, reaction=0.65, react_time=0.2, guard_hold=0.3, block_pref=0.3, dodge_pref=0.55, counter=0.45, pressure=0.45, range_pref=0.9, learning=0.45, stamina_rest=28, speed=1.3,
                 combos=[combo("jab", "jab", w=3), combo("jab", "jab", "cross", w=3), combo("jab", "cross", "lead_hook", "cross", w=2), combo("jab", "body_hook", w=1.5)]),
     "durable": dict(label="Resistente", desc="Acepta intercambios y aguanta el castigo.", aggression=0.6, tempo=0.8, reaction=0.35, react_time=0.3, guard_hold=0.3, block_pref=0.5, dodge_pref=0.1, counter=0.3, pressure=0.55, range_pref=0.72, learning=0.3, stamina_rest=15, speed=0.9,
                     combos=[combo("cross", "lead_hook", w=3), combo("body_hook", "lead_hook", w=2), combo("jab", "cross", w=2), combo("rear_uppercut", "lead_hook", w=2)]),
     "strategic": dict(label="Estratega", desc="Adapta su plan al marcador y a tus costumbres.", aggression=0.5, tempo=0.75, reaction=0.8, react_time=0.2, guard_hold=0.5, block_pref=0.5, dodge_pref=0.4, counter=0.5, pressure=0.35, range_pref=1.0, learning=0.95, stamina_rest=32, speed=1.0,
                       combos=[combo("jab", "cross", w=2), combo("jab", "body_hook", "cross", w=2), combo("lead_hook", "cross", w=2), combo("jab", w=2)]),
     "pressure": dict(label="Presión", desc="Avanza siempre, te arrincona y busca el cuerpo.", aggression=0.8, tempo=0.6, reaction=0.5, react_time=0.26, guard_hold=0.35, block_pref=0.55, dodge_pref=0.15, counter=0.25, pressure=0.95, range_pref=0.68, learning=0.4, stamina_rest=18, speed=1.0,
                  combos=[combo("body_hook", "lead_hook", w=3), combo("jab", "body_hook", "cross", w=3), combo("rear_uppercut", "lead_hook", w=2), combo("cross", "body_hook", w=2)]),
    }
    return dump("OPPONENTS/ai_profiles.json", dict(
        note="Parámetros de personalidad. skill (dificultad) modula reacción, tempo y velocidad de aprendizaje. Ver OPPONENTS/boxer_ai.gd.",
        difficulty=dict(easy=0.3, normal=0.55, hard=0.8, pro=1.0), profiles=P))

def reach_of(name):
    wind, hit = clips.ATTACK_POSES[name]
    q, ht = clips.finalize(hit); W, M = rig.fk(q, ht)
    return max((M["Hand_" + s] @ np.array([0, 0, 0.015, 1]))[2] for s in "LR")

def animation_table():
    clips.build_all()
    A = {}
    for n, a in clips.ATTACKS.items():
        r = reach_of(n)
        A[n] = dict(a, reach_m=round(float(r), 2), range=round(float(r) + 0.29, 2))   # alcance medido del modelo + guante + cara rival + tolerancia
    C = []
    for c in clips.CLIPS:
        ev = clips.evaluate(c)
        C.append(dict(name=c["name"], file="CHARACTERS/boxer_base.glb" if c["cat"] == "boxer" else "CHARACTERS/staff_base.glb",
                      loop=c["loop"], duration=round(ev[-1][0], 3), category=c["cat"], note=c["note"]))
    return dump("ANIMATIONS/animation_table.json", dict(
        note="Fuente única: los tiempos de los golpes salen de tools/clips.py y las duraciones de los clips coinciden con startup+active+recovery. range = distancia centro a centro (m) a la que el golpe conecta.",
        model_height_m=1.82, stance_note="Zurdos: se espeja el nodo visual (scale.x = -1); la lógica no cambia.",
        attacks=A, clips=C,
        defense=dict(dodge_iframes=[0.06, 0.26], dodge_duration=0.36, dodge_stamina=6, block_damage_mult=0.2, block_body_mult=0.6, block_stamina_mult=0.45),
        movement=dict(forward=1.7, back=1.4, block=0.6, min_separation=0.55, ring_half=2.8),
        knockdown=dict(count_seconds=10, count_step=1.0, getup_time=1.6, health_after_getup=28, max_per_round=3, max_per_fight=4)))

def arenas():
    def crowd(tiers, density, pal, exc=0.5): return dict(tiers=tiers, density=density, palette=pal, excitement=exc)
    pal_a = ["#c62828", "#1565c0", "#f9a825", "#2e7d32", "#eeeeee", "#6a1b9a"]
    A = [
     dict(id="gym_local", name="Gimnasio de barrio", kind="gym", size=[16, 14, 5], floor="#5b4636", wall="#8a7f73", ambient="#c9c2b8", ambient_energy=0.9, light_color="#fff1d6", spot_count=2, spot_energy=2.0,
          crowd=crowd(0, 0.0, pal_a), props=dict(heavy_bags=[[-6, 0, -3], [-6, 0, 0], [-6, 0, 3]], speed_bags=[[6, 0, -4]], barbells=[[6, 0, 3]], benches=[[5, 0, 5]]), ambience="AUDIO/gym_ambience_loop.wav", cameras=["broadcast", "behind", "corner"], notes="Combates de debut y sparring."),
     dict(id="gym_pro", name="Gimnasio profesional", kind="gym", size=[24, 20, 7], floor="#2f3a42", wall="#47535c", ambient="#d4e2f2", ambient_energy=1.0, light_color="#ffffff", spot_count=4, spot_energy=2.5,
          crowd=crowd(0, 0.0, pal_a), props=dict(heavy_bags=[[-9, 0, -6], [-9, 0, -3], [-9, 0, 0], [-9, 0, 3]], speed_bags=[[9, 0, -6], [9, 0, -3]], barbells=[[8, 0, 4], [8, 0, 6]], benches=[[6, 0, 6], [10, 0, 6]]), ambience="AUDIO/gym_ambience_loop.wav", cameras=["broadcast", "behind", "corner"], notes="Zona de entrenamiento del modo carrera."),
     dict(id="arena_city", name="Arena de la ciudad", kind="arena", size=[40, 36, 14], floor="#15171c", wall="#1c1f28", ambient="#4c5a80", ambient_energy=0.55, light_color="#ffffff", spot_count=6, spot_energy=5.0,
          crowd=crowd(3, 0.75, pal_a, 0.6), props=dict(truss=True, scoreboard=True), ambience="AUDIO/crowd_loop.wav", cameras=["broadcast", "behind", "corner", "ringside"], notes="Peleas regionales y eliminatorias."),
     dict(id="arena_international", name="Arena internacional", kind="arena", size=[60, 52, 20], floor="#0d0e12", wall="#12141a", ambient="#3b4a78", ambient_energy=0.5, light_color="#fff6e0", spot_count=8, spot_energy=7.0,
          crowd=crowd(5, 0.9, pal_a, 0.8), props=dict(truss=True, scoreboard=True), ambience="AUDIO/crowd_loop.wav", cameras=["broadcast", "behind", "corner", "ringside", "overhead"], notes="Peleas de título mundial."),
     dict(id="stadium", name="Estadio al aire libre", kind="stadium", size=[110, 90, 30], floor="#1b2b1b", wall="#222222", ambient="#6f86b3", ambient_energy=0.7, light_color="#fff3d1", spot_count=8, spot_energy=9.0,
          crowd=crowd(8, 1.0, pal_a, 1.0), props=dict(truss=True, scoreboard=True, night=True), ambience="AUDIO/crowd_loop.wav", cameras=["broadcast", "behind", "corner", "ringside", "overhead"], notes="Evento especial de campeonato (máximo público)."),
     dict(id="locker_room", name="Vestuario", kind="room", size=[10, 6, 3.2], floor="#3a3a3f", wall="#4a5568", ambient="#d0d8e8", ambient_energy=1.0, light_color="#ffffff", spot_count=1, spot_energy=2.0,
          crowd=crowd(0, 0, pal_a), props=dict(lockers=[[0, 0, -2.6]]), ambience="AUDIO/gym_ambience_loop.wav", cameras=["behind"], notes="Previa del combate y vendaje."),
     dict(id="entry_tunnel", name="Túnel de entrada", kind="tunnel", size=[3.3, 12, 3.1], floor="#14141a", wall="#2d2d38", ambient="#8090c0", ambient_energy=0.6, light_color="#e8ecff", spot_count=0, spot_energy=0,
          crowd=crowd(0, 0, pal_a), props=dict(tunnel=True), ambience="AUDIO/crowd_loop.wav", cameras=["behind"], notes="Caminata de entrada al ring."),
    ]
    return dump("ARENAS/arenas.json", dict(note="Cada arena se construye en tiempo de ejecución con SCRIPTS/arena_builder.gd (sin escenas pesadas). Colores en hex.",
                                            camera_presets=dict(
                                                broadcast=dict(mode="side", distance=5.2, height=1.55, fov=48),
                                                behind=dict(mode="behind", distance=2.6, height=1.9, fov=55),
                                                corner=dict(mode="corner", distance=5.5, height=2.4, fov=50),
                                                ringside=dict(mode="side", distance=3.4, height=1.2, fov=60),
                                                overhead=dict(mode="top", distance=0, height=8.0, fov=45)),
                                            arenas=A))

def career():
    return dump("CAREER_MODE/career.json", dict(
        note="Modo carrera: el tiempo avanza por semanas. Datos ficticios (patrocinadores, ligas y cinturones inventados).",
        start=dict(cash=1500, week=1, fame=0, fatigue=0, gym="gym_local", rank=50, weight_class="welter"),
        week=dict(training_slots=3, fight_every_weeks=6, fatigue_max=100, fatigue_per_session=18, rest_recovery=35),
        weight_classes=["gallo", "pluma", "ligero", "superwelter", "welter", "medio", "supermedio", "semipesado", "crucero", "pesado"],
        rank_tiers=[dict(name="Debutante", rank_max=100), dict(name="Prospecto", rank_max=50), dict(name="Contendiente", rank_max=20),
                    dict(name="Retador oficial", rank_max=5), dict(name="Campeón", rank_max=1)],
        gyms=[dict(id="gym_local", name="Gimnasio de barrio", cost_per_week=0, training_mult=1.0, unlock_rank=100),
              dict(id="gym_pro", name="Gimnasio profesional", cost_per_week=250, training_mult=1.35, unlock_rank=35)],
        training=[
            dict(id="heavy_bag", name="Saco pesado", prop="GYMS/heavy_bag.glb", clip="warmup_shadow", gains=dict(power=2.0, stamina=0.5), cost=0),
            dict(id="speed_bag", name="Pera loca", prop="GYMS/speed_bag.glb", clip="warmup_shadow", gains=dict(speed=1.5, accuracy=1.0), cost=0),
            dict(id="weights", name="Pesas", prop="GYMS/barbell.glb", clip="rest_breathe", gains=dict(power=1.0, chin=1.5), cost=0),
            dict(id="roadwork", name="Carrera / cuerda", prop=None, clip="walk_forward", gains=dict(stamina=2.5, footwork=0.5), cost=0),
            dict(id="sparring", name="Sparring", prop="RINGS/ring_pro.glb", clip="idle_guard", gains=dict(defense=1.5, ring_iq=1.5, accuracy=0.5), cost=100),
            dict(id="shadow", name="Sombra", prop=None, clip="warmup_shadow", gains=dict(footwork=1.5, accuracy=1.0), cost=0),
            dict(id="rest", name="Descanso", prop=None, clip="rest_breathe", gains={}, cost=0, recovers_fatigue=True)],
        equipment=[
            dict(id="gloves_pro", name="Guantes Titanio", price=400, bonus=dict(power=2)), dict(id="wraps_gel", name="Vendas de gel", price=150, bonus=dict(chin=2)),
            dict(id="shoes_light", name="Botas ligeras", price=300, bonus=dict(footwork=3, speed=1)), dict(id="rope_pro", name="Cuerda de velocidad", price=80, bonus=dict(stamina=2)),
            dict(id="mouth_guard", name="Protector bucal a medida", price=200, bonus=dict(chin=3))],
        sponsors=[
            dict(id="voltedge", name="VoltEdge Energía", pay_per_fight=300, requires=dict(fame=10), clause="Ganar por KO da +200"),
            dict(id="titanio_gear", name="Titanio Equipamiento", pay_per_fight=500, requires=dict(fame=30), clause="Subir 3 puestos en el ranking"),
            dict(id="andes_motors", name="Andes Motors", pay_per_fight=900, requires=dict(fame=60), clause="Defender el título"),
            dict(id="nova_foods", name="Nova Alimentos", pay_per_fight=1500, requires=dict(fame=85), clause="Pelear en arena internacional")],
        contract=dict(base_purse_by_tier=dict(Debutante=500, Prospecto=1500, Contendiente=6000, **{"Retador oficial": 20000}, Campeón=80000), win_bonus=0.25, ko_bonus=0.15, rounds_by_tier=dict(Debutante=3, Prospecto=4, Contendiente=6, **{"Retador oficial": 10}, Campeón=12), opponent_ranks_window=[-8, 3]),
        progression=dict(rank_gain_win=[2, 6], rank_loss_defeat=[1, 4], fame_win=3, fame_ko=2, fame_loss=-2, stat_cap=99),
        trophies=[dict(id="first_win", name="Primera victoria"), dict(id="ko_artist", name="10 KOs"), dict(id="top10", name="Top 10 mundial"), dict(id="champion", name="Campeón mundial"), dict(id="undefeated", name="Invicto en 10 peleas")]))

def championships():
    return dump("CHAMPIONSHIPS/championships.json", dict(
        note="Ligas y cinturones FICTICIOS.",
        organizations=[dict(id="lmbj", name="Liga Mundial de Boxeo Jarvis", belt_color="#d4af37"), dict(id="cab", name="Confederación Atlántica de Boxeo", belt_color="#c0c0c0")],
        belts=[dict(id=f"{o}_{w}", org=o, weight_class=w, name=f"Campeón {w} {o.upper()}") for o in ("lmbj", "cab") for w in ("pluma", "ligero", "welter", "medio", "pesado")],
        tournaments=[
            dict(id="copa_barrio", name="Copa del Barrio", fighters=4, rounds=3, venue="gym_local", prize=800, belt=None),
            dict(id="torneo_ascenso", name="Torneo de Ascenso", fighters=8, rounds=4, venue="arena_city", prize=5000, belt=None),
            dict(id="eliminatoria", name="Eliminatoria Mundial", fighters=8, rounds=8, venue="arena_city", prize=25000, belt="cab"),
            dict(id="mundial", name="Campeonato Mundial", fighters=2, rounds=12, venue="arena_international", prize=120000, belt="lmbj"),
            dict(id="gala_estadio", name="Gala de Estadio", fighters=2, rounds=12, venue="stadium", prize=400000, belt="lmbj")],
        ceremony_sequence=["entry_tunnel", "ring_walk_challenger", "ring_walk_champion", "ring_announcer_intro", "referee_instructions", "bell_round_start", "fight", "bell_round_end", "decision_announcement", "belt_ceremony", "post_fight_interview"],
        scoring=dict(system="10-point-must", judges=3, knockdown_penalty=1, close_round_noise=0.8)))

def vfx():
    return dump("VFX/vfx_presets.json", dict(
        note="Los efectos usan CPUParticles3D (ligeros, sin shaders propios). Ver SCRIPTS/vfx.gd.",
        presets=dict(
            hit_spark=dict(amount=10, lifetime=0.25, speed=[2.0, 4.0], size=[0.03, 0.06], color="#fff2b0", spread=60, gravity=-6),
            hit_heavy=dict(amount=22, lifetime=0.4, speed=[3.0, 6.5], size=[0.04, 0.09], color="#ffb347", spread=80, gravity=-8),
            sweat=dict(amount=14, lifetime=0.55, speed=[1.0, 3.0], size=[0.015, 0.03], color="#d8f3ff", spread=100, gravity=-9),
            block_puff=dict(amount=8, lifetime=0.3, speed=[0.8, 1.8], size=[0.05, 0.09], color="#cfd8dc", spread=120, gravity=-1),
            dust=dict(amount=18, lifetime=0.9, speed=[0.4, 1.4], size=[0.08, 0.16], color="#8d8a84", spread=180, gravity=-0.5),
            confetti=dict(amount=90, lifetime=3.2, speed=[2.0, 6.0], size=[0.05, 0.09], color="#ffd166", spread=60, gravity=-3)),
        camera_shake=dict(light=dict(strength=0.015, time=0.12), heavy=dict(strength=0.05, time=0.22), knockdown=dict(strength=0.1, time=0.45)),
        slow_motion=dict(knockdown=dict(scale=0.25, time=0.7), final_blow=dict(scale=0.2, time=1.0)),
        replay=dict(buffer_seconds=5.0, sample_hz=30, speed=0.4)))

def audio_manifest():
    return dump("AUDIO/announcer_lines.json", dict(
        note="NO hay voces incluidas. Estas líneas sirven para grabar con voces autorizadas o para un TTS con licencia. Archivo de voz esperado: AUDIO/voices/<id>.ogg",
        ring_announcer=[dict(id="ra_intro", text="Damas y caballeros, bienvenidos a una noche de boxeo profesional."), dict(id="ra_red", text="En la esquina roja, {fighter_a}, con un récord de {record_a}."),
                        dict(id="ra_blue", text="En la esquina azul, {fighter_b}, con un récord de {record_b}."), dict(id="ra_round", text="Round {n}."),
                        dict(id="ra_winner_decision", text="Y el ganador, por decisión, {winner}."), dict(id="ra_winner_ko", text="Ganador por nocaut, {winner}.")],
        referee=[dict(id="rf_box", text="¡Box!"), dict(id="rf_break", text="¡Separados!"), dict(id="rf_count", text="{n}"), dict(id="rf_stop", text="¡Basta! Se acabó la pelea.")],
        commentators=[dict(id="cm_a_knockdown", speaker="A", text="¡Al suelo! ¡Qué golpe tan limpio!"), dict(id="cm_b_adapt", speaker="B", text="Fíjate cómo el rival ya leyó la combinación y se está adaptando."),
                      dict(id="cm_a_round_end", speaker="A", text="Termina el round, un intercambio muy parejo."), dict(id="cm_b_stamina", speaker="B", text="Está llegando al límite de su resistencia."),
                      dict(id="cm_a_counter", speaker="A", text="¡Contraataque perfecto!"), dict(id="cm_b_finish", speaker="B", text="Esto se acaba, no puede defenderse.")]))

def staff():
    dump("COACHES/coaches.json", dict(
        note="Entrenadores y asistentes de esquina. Modelo: CHARACTERS/staff_base.glb (mismo rig, otros colores). No hay rostro animado: ver DOCUMENTATION/LIMITATIONS.md",
        model="CHARACTERS/staff_base.glb", animations=["idle_staff", "walk_staff", "coach_cheer", "coach_instruct"],
        coaches=[dict(id="coach_ramon", name="Ramón Ibarra", role="Entrenador principal", look=dict(skin="#b07a55", hair="#8a8a8a", shirt="#1d3f8f", pants="#222222", shoes="#111111"), voice="AUDIO/voices/coach_ramon (pendiente, licenciar)"),
                 dict(id="coach_ines", name="Inés Valdés", role="Preparadora física", look=dict(skin="#d09a78", hair="#2a1a10", shirt="#e53935", pants="#222222", shoes="#ffffff"), voice="AUDIO/voices/coach_ines (pendiente, licenciar)"),
                 dict(id="coach_walt", name="Walt Brenner", role="Cutman", look=dict(skin="#e0b89c", hair="#c8c8c8", shirt="#2e7d32", pants="#333333", shoes="#111111"), voice="AUDIO/voices/coach_walt (pendiente, licenciar)")]))
    dump("REFEREES/referees.json", dict(
        note="Árbitros. Modelo: CHARACTERS/staff_base.glb.", model="CHARACTERS/staff_base.glb", animations=["idle_staff", "walk_staff", "ref_count", "ref_break", "ref_raise_winner"],
        referees=[dict(id="ref_alvarez", name="Hugo Álvarez", look=dict(skin="#b98c68", hair="#1a1a1a", shirt="#f2f2f2", pants="#0b0b0b", shoes="#0b0b0b")),
                  dict(id="ref_stone", name="Marcus Stone", look=dict(skin="#6b4630", hair="#0d0d0d", shirt="#f2f2f2", pants="#0b0b0b", shoes="#0b0b0b"))]))
    dump("COACHES/commentators.json", dict(
        note="Comentaristas y presentador: solo voz + avatar de cabina (staff_base.glb, animación idle_staff). Voces NO incluidas.",
        model="CHARACTERS/staff_base.glb",
        people=[dict(id="presenter", name="Leo Marín", role="Presentador del ring", look=dict(skin="#c08c64", hair="#1a1008", shirt="#8b0000", pants="#111111", shoes="#111111")),
                dict(id="comm_a", name="Paula Rey", role="Comentarista principal", look=dict(skin="#d9ad8a", hair="#3a2410", shirt="#1d3f8f", pants="#111111", shoes="#111111")),
                dict(id="comm_b", name="Gonzalo Pardo", role="Comentarista técnico", look=dict(skin="#a77455", hair="#0f0a06", shirt="#2e7d32", pants="#111111", shoes="#111111"))]))

def ui_layout():
    return dump("UI/ui_layout.json", dict(
        note="Posiciones normalizadas (0..1) respecto a la pantalla horizontal. Las usa SCRIPTS/touch_controls.gd y SCRIPTS/hud.gd.",
        touch_buttons=[dict(id="jab", label="JAB", x=0.865, y=0.80, r=0.075, action="attack:jab"), dict(id="cross", label="CRUZ", x=0.95, y=0.68, r=0.075, action="attack:cross"),
                       dict(id="hook", label="GANCHO", x=0.78, y=0.66, r=0.075, action="attack:lead_hook"), dict(id="upper", label="UPPER", x=0.865, y=0.55, r=0.07, action="attack:rear_uppercut"),
                       dict(id="body", label="CUERPO", x=0.95, y=0.88, r=0.065, action="attack:body_hook"), dict(id="block", label="BLOQ", x=0.08, y=0.80, r=0.08, action="hold:block"),
                       dict(id="back", label="<<", x=0.21, y=0.84, r=0.065, action="hold:back"), dict(id="fwd", label=">>", x=0.30, y=0.84, r=0.065, action="hold:forward"),
                       dict(id="dodge_l", label="ESQ<", x=0.08, y=0.60, r=0.06, action="tap:dodge_left"), dict(id="dodge_r", label=">ESQ", x=0.19, y=0.64, r=0.06, action="tap:dodge_right")],
        keyboard=dict(jab="J", cross="K", lead_hook="L", rear_uppercut="I", body_hook="U", block="SPACE", dodge_left="A", dodge_right="D", forward="RIGHT", back="LEFT", camera="C"),
        difficulty_options=["easy", "normal", "hard", "pro"],
        screens=["main_menu", "fighter_select", "opponent_select", "arena_select", "difficulty", "combat_hud", "results", "rankings", "career_hub", "training", "contracts", "customize"]))

def build_all():
    out = [fighters(), ai_profiles(), animation_table(), arenas(), career(), championships(), vfx(), audio_manifest(), ui_layout()]
    staff(); out += ["COACHES/coaches.json", "REFEREES/referees.json", "COACHES/commentators.json"]
    return out

if __name__ == "__main__":
    print(build_all())
    t = json.load(open(os.path.join(ROOT, "ANIMATIONS/animation_table.json")))
    for k, v in t["attacks"].items(): print(k, "reach", v["reach_m"], "range", v["range"])
