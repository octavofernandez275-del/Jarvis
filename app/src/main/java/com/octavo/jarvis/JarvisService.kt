package com.octavo.jarvis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder

/**
 * Servicio en primer plano: mantiene vivo el proceso (y con él el motor de IA y la voz)
 * cuando sales de la app o apagas la pantalla. Muestra una notificación con botones.
 * - Tipo base (specialUse): motor activo.
 * - Tipo micrófono: solo mientras el modo manos libres está encendido.
 * Lo arranca la pantalla (visible); lo detiene EngineManager al apagar el motor, o el botón Detener.
 */
class JarvisService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instancia = this
        Assistant.iniciar(this)          // por si el sistema creó el proceso solo para el servicio
        crearCanales(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Obligatorio llamar a startForeground enseguida
        foreground(intent?.getBooleanExtra(EXTRA_MIC, HandsFree.activo) ?: HandsFree.activo)
        when (intent?.action) {
            ACCION_DETENER -> apagarTodo()
            ACCION_INTERRUMPIR -> Assistant.interrupt()
        }
        return START_NOT_STICKY
    }

    /** Pone/actualiza la notificación y el tipo de servicio. Devuelve false si no pudo con el micrófono. */
    fun foreground(mic: Boolean): Boolean {
        try {
            val n = notificacion(mic)
            if (Build.VERSION.SDK_INT >= 34) {
                var tipo = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                if (mic) tipo = tipo or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                startForeground(ID, n, tipo)
            } else if (Build.VERSION.SDK_INT >= 30) {
                startForeground(ID, n, if (mic) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
            } else {
                startForeground(ID, n)
            }
            return true
        } catch (e: Exception) {
            if (mic) {
                // Sin permiso de micrófono o sin poder usarlo en segundo plano: seguir sin micrófono
                foreground(false)
                HandsFree.desactivar("No pude activar el micrófono en segundo plano.")
            } else {
                stopSelf()
            }
            return false
        }
    }

    private fun notificacion(mic: Boolean): Notification {
        val abrir = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CANAL_SERVICIO)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("J.A.R.V.I.S.")
            .setContentText(
                if (mic) "Manos libres activo"
                else "Motor activo. Se apaga solo si no lo usas."
            )
            .setContentIntent(abrir)
            .setOngoing(true)
            .addAction(accion("Interrumpir", ACCION_INTERRUMPIR, 1))
            .addAction(accion("Detener", ACCION_DETENER, 2))
            .build()
    }

    private fun accion(titulo: String, codigo: String, req: Int): Notification.Action {
        val pi = PendingIntent.getService(
            this, req,
            Intent(this, JarvisService::class.java).setAction(codigo),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val icono = Icon.createWithResource(this, android.R.drawable.ic_media_pause)
        return Notification.Action.Builder(icono, titulo, pi).build()
    }

    /** Botón "Detener": apaga manos libres, la voz, el motor y el servicio. */
    private fun apagarTodo() {
        HandsFree.desactivar(null)
        Assistant.interrupt()
        EngineManager.apagarYa()
        Assistant.estado("Detenido. El motor se enciende solo cuando le hables.")
        stopSelf()
    }

    override fun onDestroy() {
        instancia = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        const val ACCION_DETENER = "com.octavo.jarvis.DETENER"
        const val ACCION_INTERRUMPIR = "com.octavo.jarvis.INTERRUMPIR"
        const val EXTRA_MIC = "mic"
        const val CANAL_SERVICIO = "jarvis_servicio"
        const val CANAL_ACCIONES = "jarvis_acciones"
        private const val ID = 1

        @Volatile var instancia: JarvisService? = null

        /** Arranca el servicio (llamar con la app visible). */
        fun iniciar(ctx: Context, microfono: Boolean) {
            try {
                ctx.startForegroundService(
                    Intent(ctx, JarvisService::class.java).putExtra(EXTRA_MIC, microfono)
                )
            } catch (e: Exception) {
                // Si Android no deja arrancarlo (app en segundo plano), la app sigue sin servicio
            }
        }

        fun detener(ctx: Context) {
            ctx.stopService(Intent(ctx, JarvisService::class.java))
        }

        /** Sube o baja el tipo "micrófono". Devuelve false si no se pudo. */
        fun fijarMicrofono(ctx: Context, mic: Boolean): Boolean {
            val s = instancia
            if (s != null) return s.foreground(mic)
            if (mic) iniciar(ctx, true)
            return true
        }

        fun crearCanales(ctx: Context) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CANAL_SERVICIO, "Jarvis activo", NotificationManager.IMPORTANCE_LOW)
            )
            nm.createNotificationChannel(
                NotificationChannel(CANAL_ACCIONES, "Acciones de Jarvis", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }
}
