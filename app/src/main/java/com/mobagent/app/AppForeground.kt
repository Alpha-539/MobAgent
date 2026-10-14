package com.mobagent.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.mobagent.app.agent.AgentTaskService

/**
 * Ilova hozir foydalanuvchi ko'z oldidami (kamida bitta Activity "started" holatida)?
 *
 * Foydalanuvchi ilovaning ichida bo'lsa, "javob tayyor" va "buyruqni tasdiqlang" bildirishnomalari
 * keraksiz — natija ekranning o'zida ko'rinib turibdi. Shuning uchun AgentTaskService ularni
 * faqat ilova fonda bo'lganda chiqaradi. Ilova fonga o'tganda hali tasdiqlanmagan buyruq
 * bo'lsa, bildirishnoma o'sha payt chiqariladi; old planga qaytganda esa eskirgan
 * bildirishnomalar olib tashlanadi.
 */
object AppForeground {
    @Volatile
    var isForeground: Boolean = false
        private set

    private var started = 0
    private var registered = false

    fun init(app: Application) {
        if (registered) return
        registered = true
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                started++
                if (started == 1) {
                    isForeground = true
                    AgentTaskService.onAppForeground(app)
                }
            }

            override fun onActivityStopped(activity: Activity) {
                started = (started - 1).coerceAtLeast(0)
                if (started == 0) {
                    isForeground = false
                    // Ekran burilishi (configuration change) ilovadan chiqish emas.
                    if (!activity.isChangingConfigurations) AgentTaskService.onAppBackground(app)
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
