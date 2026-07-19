package com.example.imagetranslate

import android.app.Application
import org.opencv.android.OpenCVLoader

class App : Application() {
    companion object {
        var isOpenCVReady = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        initOpenCV()
    }

    private fun initOpenCV() {
        if (OpenCVLoader.initDebug()) {
            isOpenCVReady = true
        } else {
            OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION, this) { _, _ ->
                isOpenCVReady = true
            }
        }
    }
}

