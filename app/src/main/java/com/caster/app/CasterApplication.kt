package com.caster.app

import android.app.Application
import android.util.Log

class CasterApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.i("CasterApp", "Android Caster v1.0 started")
    }
}
