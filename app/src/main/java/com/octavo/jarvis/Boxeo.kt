package com.octavo.jarvis

import android.content.Context
import android.content.Intent

/** Acceso al juego Jarvis Boxeo (app aparte, hecha con Godot en la carpeta boxeo/). Solo lo abre; si no está instalado, avisa. */
object Boxeo {
    const val PAQUETE = "com.octavo.jarvisboxeo"

    fun abrir(ctx: Context): Reply {
        val i = ctx.packageManager.getLaunchIntentForPackage(PAQUETE)
            ?: return Reply("Todavía no tienes instalado Jarvis Boxeo. Compílalo en GitHub Actions («Compilar Jarvis Boxeo»), descarga jarvis-boxeo-apk e instálalo.")
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return Reply("¡A los guantes! Abriendo Jarvis Boxeo.", despues = { ctx.startActivity(i) })
    }
}
