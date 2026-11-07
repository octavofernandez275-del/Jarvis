extends Node
## Reproductor de efectos (pool de 8 voces) y ambiente en bucle. Los WAV están en res://AUDIO/.

const PATH := "res://AUDIO/"
const FILES := ["punch_light", "punch_heavy", "punch_body", "block_hit", "whoosh", "knockdown_thud", "bell_round_start",
	"bell_round_end", "count_tick", "ui_click", "ui_confirm", "crowd_cheer", "stinger_win", "stinger_lose"]

var streams := {}
var voices: Array = []
var amb: AudioStreamPlayer
var next_voice := 0


func _ready() -> void:
	for n in FILES:
		var s = load(PATH + n + ".wav")
		if s != null:
			streams[n] = s
	for i in range(8):
		var p := AudioStreamPlayer.new()
		add_child(p)
		voices.append(p)
	amb = AudioStreamPlayer.new()
	add_child(amb)
	amb.finished.connect(func(): amb.play())


func play(name: String, volume_db: float = 0.0, pitch: float = 1.0) -> void:
	if not streams.has(name):
		return
	var p: AudioStreamPlayer = null
	for v in voices:
		if not v.playing:
			p = v
			break
	if p == null:
		p = voices[next_voice]
		next_voice = (next_voice + 1) % voices.size()
	p.stream = streams[name]
	p.volume_db = volume_db
	p.pitch_scale = pitch
	p.play()


func ambience(file_name: String, volume_db: float = -10.0) -> void:
	var s = load(file_name)
	if s == null:
		return
	amb.stream = s
	amb.volume_db = volume_db
	amb.play()


func stop_ambience() -> void:
	amb.stop()
