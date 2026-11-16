package org.photoall.tether

import android.app.Application
import org.photoall.tether.camera.CameraHub
import org.photoall.tether.develop.Library
import org.photoall.tether.engine.LutCatalog

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ErrorLog.init(this)
        ErrorLog.context = { CameraHub.logTail(25) }
        CameraHub.init(this)
        Library.load(this)
        Thread { LutCatalog.load(this) }.start()
    }
}
