package com.jarvis.chat

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Jarvis en segundo plano: escucha en bucle, y cuando oye "Jarvis" / "Oye Jarvis"
 * toma la orden que sigue y se la pasa a la app para que la ejecute.
 */
class JarvisService : Service() {
    companion object {
        @Volatile var busy = false
        @Volatile var busyUntil = 0L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var rec: SpeechRecognizer? = null
    private var running = false
    private var awaiting = false
    private var awaitingUntil = 0L
    private var ticking = false
    @Volatile private var briefing = false
    private val tick = object : Runnable {
        override fun run() {
            checkBrief()
            if (ticking) handler.postDelayed(this, 60000)
        }
    }
    private val wake = Regex(
        "(?i)\\b(?:oye|ey|hey|ok|okay)?\\s*(?:jarvis|yarvis|harvis|jarbis|yárvis|jarvi)\\b[\\s,.:;!¡¿?]*"
    )

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            getSharedPreferences("jarvis", MODE_PRIVATE).edit()
                .putBoolean("bg", false).putBoolean("brief_on", false).apply()
            stopListening()
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }
        val wakeOn = getSharedPreferences("jarvis", MODE_PRIVATE).getBoolean("bg", false)
        val micOk = wakeOn && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel("jarvis", "Jarvis activo", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val flag = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java)
                .putExtra("listen", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            flag
        )
        val stop = PendingIntent.getService(
            this, 2, Intent(this, JarvisService::class.java).setAction("STOP"), flag
        )
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "jarvis")
        else Notification.Builder(this)
        val n = b.setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Jarvis activo")
            .setContentText(if (micOk) "Di «Jarvis» y tu orden, o toca para hablar" else "Toca para hablar")
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Detener", stop)
            .setOngoing(true)
            .build()
        when {
            Build.VERSION.SDK_INT >= 34 -> startForeground(
                1, n,
                if (micOk) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                else ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
            Build.VERSION.SDK_INT >= 29 -> startForeground(
                1, n, if (micOk) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            )
            else -> startForeground(1, n)
        }
        if (micOk && !running && SpeechRecognizer.isRecognitionAvailable(this)) {
            running = true
            listenLoop()
        }
        if (!ticking) {
            ticking = true
            handler.post(tick)
        }
        return START_NOT_STICKY
    }

    private fun stopListening() {
        running = false
        ticking = false
        handler.removeCallbacksAndMessages(null)
        rec?.destroy()
        rec = null
    }

    override fun onDestroy() {
        stopListening()
        super.onDestroy()
    }

    private fun next(ms: Long) {
        if (running) handler.postDelayed({ listenLoop() }, ms)
    }

    private fun listenLoop() {
        if (!running) return
        if (busy || System.currentTimeMillis() < busyUntil) {
            next(600)
            return
        }
        rec?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        rec = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull() ?: ""
                handle(t)
            }

            override fun onError(error: Int) {
                if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    running = false
                    return
                }
                next(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 2000 else 400)
            }

            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(p: Bundle?) {}
            override fun onEvent(t: Int, p: Bundle?) {}
        })
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-MX")
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        )
    }

    private fun handle(text: String) {
        val now = System.currentTimeMillis()
        val m = wake.find(text)
        if (m != null) {
            val after = text.substring(m.range.last + 1).trim()
            val before = text.substring(0, m.range.first).trim()
            val cmd = if (after.length > 2) after else before
            if (cmd.length > 2) {
                dispatch(cmd)
                return
            }
            beep()
            awaiting = true
            awaitingUntil = now + 8000
            next(300)
            return
        }
        if (awaiting && now < awaitingUntil && text.isNotBlank()) {
            awaiting = false
            dispatch(text.trim())
            return
        }
        next(300)
    }

    private fun dispatch(cmd: String) {
        awaiting = false
        busyUntil = System.currentTimeMillis() + 6000
        try {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra("command", cmd)
            )
        } catch (e: Exception) {
        }
        next(1500)
    }

    /** Una vez al día, a la hora elegida, prepara el resumen y lo manda como notificación. */
    private fun checkBrief() {
        val p = getSharedPreferences("jarvis", MODE_PRIVATE)
        if (!p.getBoolean("brief_on", false) || briefing) return
        val hour = (p.getString("brief_hour", "8") ?: "8").toIntOrNull()?.coerceIn(0, 23) ?: 8
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        if (Calendar.getInstance().get(Calendar.HOUR_OF_DAY) < hour) return
        if (p.getString("last_brief", "") == today) return
        briefing = true
        p.edit().putString("last_brief", today).apply()
        Thread {
            try {
                notifyBrief(Brief.summarize(this, Brief.gather(this)))
            } catch (e: Exception) {
                notifyBrief("No pude preparar tu resumen: ${e.message}")
            } finally {
                briefing = false
            }
        }.start()
    }

    private fun notifyBrief(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel("jarvis_brief", "Resumen diario", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val open = PendingIntent.getActivity(
            this, 3,
            Intent(this, MainActivity::class.java)
                .putExtra("briefing", text)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "jarvis_brief")
        else Notification.Builder(this)
        val n = b.setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Tu resumen de hoy")
            .setContentText(text.lines().firstOrNull() ?: "")
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(2, n)
    }

    private fun beep() {
        try {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70).startTone(ToneGenerator.TONE_PROP_ACK, 150)
        } catch (e: Exception) {
        }
    }
}
