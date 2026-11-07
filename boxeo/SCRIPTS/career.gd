extends RefCounted
## Modo carrera: semanas, entrenamiento, dinero, ranking, patrocinadores y próximo rival. Guarda en user://career.json.
## Datos y reglas en CAREER_MODE/career.json (todo ficticio).

const Data = preload("res://SCRIPTS/data.gd")
const SAVE_PATH := "user://career.json"

var cfg: Dictionary = {}
var fighters: Array = []
var s: Dictionary = {}
var rng := RandomNumberGenerator.new()


func _init() -> void:
	cfg = Data.load_json("res://CAREER_MODE/career.json")
	fighters = Data.load_json("res://CHARACTERS/fighters.json")["fighters"]
	rng.randomize()


func has_save() -> bool:
	return FileAccess.file_exists(SAVE_PATH)


func new_career(fighter: Dictionary) -> void:
	var st: Dictionary = cfg["start"]
	s = {
		"fighter": fighter.duplicate(true), "cash": int(st["cash"]), "week": 1, "fame": 0, "fatigue": 0,
		"gym": String(st["gym"]), "rank": int(st["rank"]), "wins": 0, "losses": 0, "kos": 0,
		"slots": int(cfg["week"]["training_slots"]), "equipment": [], "sponsor": "", "belts": [], "trophies": [],
		"next_fight_week": int(cfg["week"]["fight_every_weeks"]), "next": {},
	}
	_roll_opponent()
	save()


func save() -> void:
	var f := FileAccess.open(SAVE_PATH, FileAccess.WRITE)
	f.store_string(JSON.stringify(s))


func load_save() -> bool:
	if not has_save():
		return false
	var parsed = JSON.parse_string(FileAccess.open(SAVE_PATH, FileAccess.READ).get_as_text())
	if parsed == null:
		return false
	s = parsed
	return true


func tier() -> String:
	var best := "Debutante"
	var best_max := 1000
	for t in cfg["rank_tiers"]:
		if int(t["rank_max"]) >= int(s["rank"]) and int(t["rank_max"]) < best_max:
			best = String(t["name"])
			best_max = int(t["rank_max"])
	return best


func gym() -> Dictionary:
	return Data.find_by_id(cfg["gyms"], String(s["gym"]))


## Boxeador del jugador con las mejoras de equipamiento aplicadas.
func effective_fighter() -> Dictionary:
	var f: Dictionary = s["fighter"].duplicate(true)
	for eid in s["equipment"]:
		var e := Data.find_by_id(cfg["equipment"], String(eid))
		for k in e.get("bonus", {}):
			f["stats"][k] = minf(float(cfg["progression"]["stat_cap"]), float(f["stats"][k]) + float(e["bonus"][k]))
	return f


func train(id: String) -> String:
	if int(s["slots"]) <= 0:
		return "No quedan sesiones esta semana."
	var act := Data.find_by_id(cfg["training"], id)
	if act.is_empty():
		return "Entrenamiento desconocido."
	if int(s["cash"]) < int(act["cost"]):
		return "No alcanza el dinero."
	var week_cfg: Dictionary = cfg["week"]
	if act.get("recovers_fatigue", false):
		s["fatigue"] = maxi(0, int(s["fatigue"]) - int(week_cfg["rest_recovery"]))
		s["slots"] = int(s["slots"]) - 1
		save()
		return "Descansaste: fatiga %d." % int(s["fatigue"])
	if int(s["fatigue"]) >= int(week_cfg["fatigue_max"]):
		return "Estás agotado: descansa."
	s["cash"] = int(s["cash"]) - int(act["cost"])
	var mult := float(gym().get("training_mult", 1.0)) * (1.0 - float(s["fatigue"]) / 200.0)
	var txt: Array = []
	for k in act["gains"]:
		var g := float(act["gains"][k]) * mult
		var cap := float(cfg["progression"]["stat_cap"])
		s["fighter"]["stats"][k] = minf(cap, float(s["fighter"]["stats"][k]) + g)
		txt.append("%s +%.1f" % [k, g])
	s["fatigue"] = int(s["fatigue"]) + int(week_cfg["fatigue_per_session"])
	s["slots"] = int(s["slots"]) - 1
	save()
	return "Entrenaste %s: %s" % [act["name"], ", ".join(txt)]


func buy(id: String) -> String:
	var e := Data.find_by_id(cfg["equipment"], id)
	if e.is_empty() or s["equipment"].has(id):
		return "Ya lo tienes."
	if int(s["cash"]) < int(e["price"]):
		return "No alcanza el dinero."
	s["cash"] = int(s["cash"]) - int(e["price"])
	s["equipment"].append(id)
	save()
	return "Compraste %s." % e["name"]


func upgrade_gym() -> String:
	for g in cfg["gyms"]:
		if g["id"] != s["gym"] and int(s["rank"]) <= int(g["unlock_rank"]) and float(g["training_mult"]) > float(gym()["training_mult"]):
			s["gym"] = g["id"]
			save()
			return "Te mudas a: %s." % g["name"]
	return "Aún no desbloqueas un gimnasio mejor (sube en el ranking)."


func sign_sponsor(id: String) -> String:
	var sp := Data.find_by_id(cfg["sponsors"], id)
	if sp.is_empty():
		return ""
	if int(s["fame"]) < int(sp["requires"]["fame"]):
		return "Necesitas más fama (%d)." % int(sp["requires"]["fame"])
	s["sponsor"] = id
	save()
	return "Firmaste con %s." % sp["name"]


func fight_ready() -> bool:
	return int(s["week"]) >= int(s["next_fight_week"])


func end_week() -> String:
	s["week"] = int(s["week"]) + 1
	s["slots"] = int(cfg["week"]["training_slots"])
	s["fatigue"] = maxi(0, int(s["fatigue"]) - 10)
	s["cash"] = int(s["cash"]) - int(gym().get("cost_per_week", 0))
	save()
	return "Semana %d." % int(s["week"])


func _roll_opponent() -> void:
	var pool: Array = []
	for f in fighters:
		if f["id"] != s["fighter"]["id"]:
			pool.append(f)
	var f: Dictionary = (pool[rng.randi() % pool.size()] as Dictionary).duplicate(true)
	var strength := clampf(1.0 - float(s["rank"]) / 110.0, 0.05, 1.0)     # más fuerte cuanto mejor el ranking
	for k in f["stats"]:
		f["stats"][k] = clampf(float(f["stats"][k]) * (0.72 + 0.34 * strength) + rng.randf_range(-3.0, 3.0), 35.0, 99.0)
	var window: Array = cfg["contract"]["opponent_ranks_window"]
	s["next"] = {"fighter": f, "rank": clampi(int(s["rank"]) + rng.randi_range(int(window[0]), int(window[1])), 1, 100),
		"skill": lerpf(0.3, 1.0, strength), "rounds": int(cfg["contract"]["rounds_by_tier"][tier()]),
		"arena": "gym_local" if int(s["rank"]) > 60 else ("arena_city" if int(s["rank"]) > 10 else "arena_international")}


func sponsor_pay() -> int:
	var sp := Data.find_by_id(cfg["sponsors"], String(s["sponsor"]))
	return int(sp.get("pay_per_fight", 0))


## Aplica el resultado de un combate y prepara el siguiente. Devuelve un resumen para mostrar.
func apply_result(res: Dictionary) -> String:
	var prog: Dictionary = cfg["progression"]
	var purse := int(cfg["contract"]["base_purse_by_tier"][tier()])
	var lines: Array = []
	if res["player_won"]:
		s["wins"] = int(s["wins"]) + 1
		var gain := rng.randi_range(int(prog["rank_gain_win"][0]), int(prog["rank_gain_win"][1]))
		s["rank"] = maxi(1, int(s["rank"]) - gain)
		s["fame"] = int(s["fame"]) + int(prog["fame_win"])
		purse = int(purse * (1.0 + float(cfg["contract"]["win_bonus"])))
		if res["ko"]:
			s["kos"] = int(s["kos"]) + 1
			s["fame"] = int(s["fame"]) + int(prog["fame_ko"])
			purse = int(purse * (1.0 + float(cfg["contract"]["ko_bonus"])))
		lines.append("Victoria: subes %d puestos (ranking %d)." % [gain, int(s["rank"])])
		if int(s["wins"]) == 1 and not s["trophies"].has("first_win"):
			s["trophies"].append("first_win")
		if int(s["rank"]) <= 10 and not s["trophies"].has("top10"):
			s["trophies"].append("top10")
		if int(s["rank"]) <= 1 and not s["belts"].has("lmbj"):
			s["belts"].append("lmbj")
			s["trophies"].append("champion")
			lines.append("¡Cinturón LMBJ conquistado!")
	else:
		s["losses"] = int(s["losses"]) + 1
		var loss := rng.randi_range(int(prog["rank_loss_defeat"][0]), int(prog["rank_loss_defeat"][1]))
		s["rank"] = mini(100, int(s["rank"]) + loss)
		s["fame"] = maxi(0, int(s["fame"]) + int(prog["fame_loss"]))
		purse = int(purse * 0.4)
		lines.append("Derrota: bajas %d puestos (ranking %d)." % [loss, int(s["rank"])])
	if int(s["kos"]) >= 10 and not s["trophies"].has("ko_artist"):
		s["trophies"].append("ko_artist")
	var sp := sponsor_pay()
	s["cash"] = int(s["cash"]) + purse + sp
	lines.append("Bolsa: %d + patrocinio %d." % [purse, sp])
	s["fatigue"] = mini(100, int(s["fatigue"]) + 25)
	s["week"] = int(s["week"]) + 1
	s["slots"] = int(cfg["week"]["training_slots"])
	s["next_fight_week"] = int(s["week"]) + int(cfg["week"]["fight_every_weeks"])
	_roll_opponent()
	save()
	return "\n".join(lines)
