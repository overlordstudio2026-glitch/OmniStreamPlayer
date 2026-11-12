package com.overlord.omnistream.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*

class CrashActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val crashInfo = intent.getStringExtra("CRASH_INFO") ?: "無錯誤堆疊追蹤資訊"
        val deviceInfo = intent.getStringExtra("DEVICE_INFO") ?: "未知設備"

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B0E14"))
            setPadding(40, 60, 40, 40)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val titleView = TextView(this).apply {
            text = "⚠️ OmniStream Player 遇到錯誤"
            setTextColor(Color.parseColor("#EF4444"))
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 20)
        }
        rootLayout.addView(titleView)

        val descView = TextView(this).apply {
            text = "$deviceInfo\n\nApp 啟動時發生未捕獲異常，以下為錯誤詳情："
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 13f
            setPadding(0, 0, 0, 20)
        }
        rootLayout.addView(descView)

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            setBackgroundColor(Color.parseColor("#151B28"))
            setPadding(24, 24, 24, 24)
        }

        val stackTraceView = TextView(this).apply {
            text = crashInfo
            setTextColor(Color.parseColor("#E6EDF3"))
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        scrollView.addView(stackTraceView)
        rootLayout.addView(scrollView)

        val buttonLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 30, 0, 0)
        }

        val copyButton = Button(this).apply {
            text = "複製錯誤日誌"
            setBackgroundColor(Color.parseColor("#00F0FF"))
            setTextColor(Color.parseColor("#0B0E14"))
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("OmniStreamCrash", "$deviceInfo\n\n$crashInfo")
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this@CrashActivity, "已複製崩潰日誌至剪貼簿", Toast.LENGTH_SHORT).show()
            }
        }
        buttonLayout.addView(copyButton)

        val restartButton = Button(this).apply {
            text = "重新開啟 App"
            setBackgroundColor(Color.parseColor("#334155"))
            setTextColor(Color.WHITE)
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = 30 }
            layoutParams = params
            setOnClickListener {
                val restartIntent = Intent(this@CrashActivity, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                startActivity(restartIntent)
                finish()
            }
        }
        buttonLayout.addView(restartButton)

        rootLayout.addView(buttonLayout)
        setContentView(rootLayout)
    }
}
