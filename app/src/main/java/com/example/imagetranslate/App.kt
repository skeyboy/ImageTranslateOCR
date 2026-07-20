package com.example.imagetranslate

import android.app.Application
import org.opencv.android.BaseLoaderCallback
import org.opencv.android.LoaderCallbackInterface
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
            val callback = object : BaseLoaderCallback(this) {
                override fun onManagerConnected(status: Int) {
                    if (status == LoaderCallbackInterface.SUCCESS) {
                        isOpenCVReady = true
                    } else {
                        super.onManagerConnected(status)
                    }
                }
            }
            OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION, this, callback)
        }
    }
}
