package com.example.taxifilter

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity

class ScanAreaActivity : ComponentActivity() {

    private lateinit var drawView: ScanAreaView
    private var startX = 0f
    private var startY = 0f
    private var endX = 0f
    private var endY = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("taxi_prefs", Context.MODE_PRIVATE)

        // Полноэкранный чёрный фон с прозрачностью
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.parseColor("#FF000000")) // полностью чёрный фон позади

        // Показываем выбранный скриншот
        val uri = intent.data
        if (uri != null) {
            val imageView = android.widget.ImageView(this).apply {
                setImageURI(uri)
                scaleType = android.widget.ImageView.ScaleType.FIT_XY
            }
            root.addView(imageView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))

            // Полупрозрачный затемняющий слой поверх скриншота, чтобы выделение было контрастнее
            val darkOverlay = View(this).apply {
                setBackgroundColor(Color.parseColor("#88000000"))
            }
            root.addView(darkOverlay, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }

        drawView = ScanAreaView(this)
        root.addView(drawView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // Инструкция сверху
        val hint = TextView(this).apply {
            text = "Нарисуй область сканирования пальцем"
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(32, 80, 32, 0)
        }
        root.addView(hint)

        // Кнопки снизу
        val btnSave = Button(this).apply {
            text = "СОХРАНИТЬ"
            setBackgroundColor(Color.parseColor("#00FF7F"))
            setTextColor(Color.BLACK)
            setOnClickListener {
                if (drawView.hasSelection()) {
                    val rect = drawView.getSelection()
                    prefs.edit().apply {
                        putInt("scan_x", rect.left)
                        putInt("scan_y", rect.top)
                        putInt("scan_w", rect.width())
                        putInt("scan_h", rect.height())
                        putBoolean("scan_area_set", true)
                        apply()
                    }
                    Toast.makeText(this@ScanAreaActivity, "✅ Область сохранена!", Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    Toast.makeText(this@ScanAreaActivity, "Нарисуй область!", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val btnReset = Button(this).apply {
            text = "СБРОСИТЬ"
            setBackgroundColor(Color.parseColor("#B71C1C"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                prefs.edit().putBoolean("scan_area_set", false).apply()
                drawView.clear()
                Toast.makeText(this@ScanAreaActivity, "Сброшено — сканирует весь экран", Toast.LENGTH_SHORT).show()
            }
        }

        val btnCancel = Button(this).apply {
            text = "ОТМЕНА"
            setBackgroundColor(Color.parseColor("#333333"))
            setTextColor(Color.WHITE)
            setOnClickListener { finish() }
        }

        val btnRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(24, 0, 24, 48)
            weightSum = 3f
            addView(btnSave, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(8, 0, 8, 0) })
            addView(btnReset, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(8, 0, 8, 0) })
            addView(btnCancel, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(8, 0, 8, 0) })
        }

        val btnParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        btnParams.gravity = android.view.Gravity.BOTTOM
        root.addView(btnRow, btnParams)

        setContentView(root)
    }

    inner class ScanAreaView(context: Context) : View(context) {
        private val paint = Paint().apply {
            color = Color.parseColor("#4400FF7F")
            style = Paint.Style.FILL
        }
        private val borderPaint = Paint().apply {
            color = Color.parseColor("#00FF7F")
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        private var rect = RectF()
        private var drawing = false

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.x; startY = event.y
                    drawing = true
                }
                MotionEvent.ACTION_MOVE -> {
                    endX = event.x; endY = event.y
                    rect.set(
                        minOf(startX, endX), minOf(startY, endY),
                        maxOf(startX, endX), maxOf(startY, endY)
                    )
                    invalidate()
                }
                MotionEvent.ACTION_UP -> drawing = false
            }
            return true
        }

        override fun onDraw(canvas: Canvas) {
            if (!rect.isEmpty) {
                canvas.drawRoundRect(rect, 12f, 12f, paint)
                canvas.drawRoundRect(rect, 12f, 12f, borderPaint)
            }
        }

        fun hasSelection() = !rect.isEmpty && rect.width() > 50 && rect.height() > 50
        fun getSelection() = Rect(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt())
        fun clear() { rect = RectF(); invalidate() }
    }

    companion object {
        fun start(context: Context) = context.startActivity(Intent(context, ScanAreaActivity::class.java))
    }
}
