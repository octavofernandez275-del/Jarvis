package com.octavo.jarvis

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Al encender el teléfono (o actualizar la app) arranca el servicio, que carga el motor en segundo plano:
 * así Jarvis ya está listo cuando lo necesitas. Se desactiva con el comando "no arranques al encender".
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val accion = intent?.action ?: return
        if (accion != Intent.ACTION_BOOT_COMPLETED && accion != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!Ajustes.arrancarAlEncender(context)) return
        if (!Engine.modelFile(context).exists()) return      // sin modelo no hay nada que cargar (y no se descarga solo)
        JarvisService.iniciar(context, false, arrancar = true)
    }
}
