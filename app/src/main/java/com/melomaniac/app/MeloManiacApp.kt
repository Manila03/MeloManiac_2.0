package com.melomaniac.app

import android.app.Application
import com.melomaniac.app.data.AppContainer
import com.melomaniac.app.util.AppLog

class MeloManiacApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            AppLog.e("Crash", "Uncaught on ${thread.name}", error)
            previous?.uncaughtException(thread, error)
        }
        AppLog.i("App", "MeloManiac starting")
        container = AppContainer(this)
        AppLog.i("App", "Container ready")
    }

    companion object {
        lateinit var instance: MeloManiacApp
            private set
    }
}
