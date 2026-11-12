package com.overlord.omnistream.core

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.util.Log
import com.overlord.omnistream.ui.CrashActivity
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class CrashHandler private constructor(private val context: Context) : Thread.UncaughtExceptionHandler {

    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        val stackTrace = sw.toString()
        Log.e("OmniStreamCrash", "FATAL APP CRASH:\n$stackTrace")

        val deviceInfo = "設備: ${Build.MANUFACTURER} ${Build.MODEL}\n系統: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

        // 保存崩潰日誌至本機檔案
        try {
            val crashFile = File(context.filesDir, "last_crash.txt")
            crashFile.writeText("$deviceInfo\n\n$stackTrace")
        } catch (e: Exception) {
            Log.e("OmniStreamCrash", "Failed to write crash log", e)
        }

        // 開啟 CrashActivity 顯示詳細資訊，避免直接閃退
        try {
            val intent = Intent(context, CrashActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                putExtra("CRASH_INFO", stackTrace)
                putExtra("DEVICE_INFO", deviceInfo)
            }
            context.startActivity(intent)
            Process.killProcess(Process.myPid())
            System.exit(10)
        } catch (e: Exception) {
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        fun init(context: Context) {
            Thread.setDefaultUncaughtExceptionHandler(CrashHandler(context.applicationContext))
        }

        fun showCrash(context: Context, throwable: Throwable) {
            val sw = StringWriter()
            val pw = PrintWriter(sw)
            throwable.printStackTrace(pw)
            val stackTrace = sw.toString()
            Log.e("OmniStreamCrash", "FATAL EXCEPTION:\n$stackTrace")

            val deviceInfo = "設備: ${Build.MANUFACTURER} ${Build.MODEL}\n系統: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

            try {
                val crashFile = File(context.filesDir, "last_crash.txt")
                crashFile.writeText("$deviceInfo\n\n$stackTrace")
            } catch (e: Exception) {}

            try {
                val intent = Intent(context, CrashActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    putExtra("CRASH_INFO", stackTrace)
                    putExtra("DEVICE_INFO", deviceInfo)
                }
                context.startActivity(intent)
                Process.killProcess(Process.myPid())
                System.exit(10)
            } catch (e: Exception) {}
        }
    }
}

