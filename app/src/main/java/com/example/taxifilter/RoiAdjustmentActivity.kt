package com.example.taxifilter

import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity

class RoiAdjustmentActivity : AppCompatActivity() {

    private lateinit var scanFrame: View
    private var lastX = 0f
    private var lastY = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_roi_adjustment)

        scanFrame = findViewById(R.id.scanFrame)

        // Логика перемещения рамки (как в 1H PROFIT)
        scanFrame.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.rawX
                    lastY = event.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - lastX
                    val deltaY = event.rawY - lastY
                    
                    view.x += deltaX
                    view.y += deltaY
                    
                    lastX = event.rawX
                    lastY = event.rawY
                }
            }
            true
        }

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            saveRoi()
            finish()
        }

        findViewById<Button>(R.id.btnCancel).setOnClickListener {
            finish()
        }
    }

    private fun saveRoi() {
        val prefs = getSharedPreferences("taxi_prefs", Context.MODE_PRIVATE)
        val location = IntArray(2)
        scanFrame.getLocationOnScreen(location)
        
        prefs.edit().apply {
            putBoolean("scan_area_set", true)
            putInt("scan_x", location[0])
            putInt("scan_y", location[1])
            putInt("scan_w", scanFrame.width)
            putInt("scan_h", scanFrame.height)
            apply()
        }
    }
}
