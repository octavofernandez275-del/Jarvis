package com.octavo.jarvis

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

/** Worker serializado por misión; la información se persiste antes y después de cada paso. */
class MissionWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    companion object { const val KEY_ID = "mission_id" }
    override fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.failure()
        val mission = MissionStore.all(applicationContext).firstOrNull { it.id == id } ?: return Result.failure()
        return try {
            MissionRunner.run(applicationContext, mission) { changed ->
                if (isStopped) MissionControl.cancel(id)
                MissionStore.upsert(applicationContext, changed)
            }
            if (isStopped && mission.status !in setOf("COMPLETADA", "DESCARGA EN COLA")) {
                mission.status = "CANCELADA"
                mission.result = "Misión detenida por el administrador de tareas de Android."
            }
            MissionStore.upsert(applicationContext, mission)
            when (mission.status) {
                "ERROR" -> Result.failure()
                else -> Result.success()
            }
        } catch (e: Exception) {
            if (isStopped) {
                mission.status = "CANCELADA"
                mission.result = "Misión detenida por Android. El progreso anterior quedó guardado."
                mission.updated = System.currentTimeMillis()
                MissionStore.upsert(applicationContext, mission)
                return Result.failure()
            }
            mission.status = "ERROR"
            mission.result = "${e.javaClass.simpleName}: ${e.message ?: "error desconocido"}"
            mission.updated = System.currentTimeMillis()
            MissionStore.upsert(applicationContext, mission)
            ExperienceMemory.add(applicationContext, "ERROR", mission.title, mission.result)
            Result.failure()
        } finally {
            MissionControl.clear(id)
        }
    }
}
