package com.denizetkar.walkietalkieapp

import android.app.Application
import android.util.Log

class MainApplication : Application() {
    companion object {
        init {
            try {
                System.loadLibrary("c++_shared")
                System.loadLibrary("walkie_talkie_engine")
            } catch (e: UnsatisfiedLinkError) {
                Log.e("MainApplication", "Failed to load native libraries", e)
            }
        }
    }
}