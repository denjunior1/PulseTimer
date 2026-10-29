
package com.example.pulsetimer

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var display: TextView
    private lateinit var status: TextView
    private lateinit var hours: EditText
    private lateinit var minutes: EditText
    private lateinit var seconds: EditText
    private lateinit var sound: CheckBox
    private lateinit var flash: CheckBox
    private lateinit var volume: SeekBar

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                TimerService.ACTION_UPDATE -> {
                    val remaining = intent.getLongExtra("remaining", 0L)
                    display.text = formatTime(remaining)
                    status.text = intent.getStringExtra("status") ?: "Таймер работает"
                }
                TimerService.ACTION_FLASH -> {
                    val old = display.background
                    display.setBackgroundColor(Color.rgb(65, 100, 150))
                    display.postDelayed({
                        display.background = old
                    }, 180)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(16, 19, 29)
        window.navigationBarColor = Color.rgb(16, 19, 29)
        window.decorView.systemUiVisibility = 0

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(Color.rgb(16, 19, 29))
        }

        fun text(value: String, size: Float, color: Int): TextView {
            return TextView(this).apply {
                text = value
                textSize = size
                setTextColor(color)
                gravity = Gravity.CENTER
            }
        }

        val title = text("PULSE TIMER", 25f, Color.WHITE)
        title.typeface = Typeface.DEFAULT_BOLD
        root.addView(title, match(-2))

        root.addView(
            text("Обратный отсчёт с мягкими сигналами", 14f,
                Color.rgb(160, 174, 198)),
            LinearLayout.LayoutParams(matchWidth(), dp(40))
        )

        display = text("00:01:00", 46f, Color.WHITE).apply {
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setPadding(0, dp(26), 0, dp(26))
            background = rounded(Color.rgb(25, 34, 49), 24)
        }
        root.addView(display, LinearLayout.LayoutParams(
            matchWidth(), dp(140)
        ).apply {
            setMargins(0, dp(18), 0, dp(22))
        })

        val inputs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        hours = numberInput("0")
        minutes = numberInput("1")
        seconds = numberInput("0")

        inputs.addView(inputColumn("Часы", hours), weight())
        inputs.addView(inputColumn("Минуты", minutes), weight())
        inputs.addView(inputColumn("Секунды", seconds), weight())
        root.addView(inputs, match(-2))

        sound = CheckBox(this).apply {
            text = "Звуковой сигнал каждые 5 секунд"
            isChecked = true
            setTextColor(Color.WHITE)
        }
        root.addView(sound, match(-2))

        flash = CheckBox(this).apply {
            text = "Вспышка на экране каждые 5 секунд"
            isChecked = true
            setTextColor(Color.WHITE)
        }
        root.addView(flash, match(-2))

        root.addView(text("Громкость сигнала", 14f,
            Color.LTGRAY), LinearLayout.LayoutParams(
                matchWidth(), dp(32)
            ))

        volume = SeekBar(this).apply {
            max = 100
            progress = 25
        }
        root.addView(volume, match(-2))

        status = text("Готов к запуску", 14f,
            Color.rgb(160, 174, 198))
        root.addView(status, LinearLayout.LayoutParams(
            matchWidth(), dp(46)
        ))

        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        row1.addView(actionButton("Старт") { startTimer() }, weight())
        row1.addView(actionButton("Пауза") {
            command(TimerService.ACTION_PAUSE)
        }, weight())
        root.addView(row1, match(-2))

        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        row2.addView(actionButton("Продолжить") {
            command(TimerService.ACTION_RESUME)
        }, weight())
        row2.addView(actionButton("Сброс") {
            command(TimerService.ACTION_RESET)
        }, weight())
        root.addView(row2, match(-2))

        setContentView(root)
    }

    private fun startTimer() {
        val h = hours.text.toString().toLongOrNull() ?: 0L
        val m = minutes.text.toString().toLongOrNull() ?: 0L
        val s = seconds.text.toString().toLongOrNull() ?: 0L

        val total = (h.coerceIn(0, 99) * 3600 +
            m.coerceIn(0, 59) * 60 +
            s.coerceIn(0, 59))

        if (total <= 0) {
            status.text = "Укажи время больше нуля"
            return
        }

        val intent = Intent(this, TimerService::class.java).apply {
            action = TimerService.ACTION_START
            putExtra("duration", total)
            putExtra("sound", sound.isChecked)
            putExtra("flash", flash.isChecked)
            putExtra("volume", volume.progress)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun command(action: String) {
        val intent = Intent(this, TimerService::class.java).apply {
            this.action = action
            putExtra("sound", sound.isChecked)
            putExtra("flash", flash.isChecked)
            putExtra("volume", volume.progress)
        }
        startService(intent)
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(TimerService.ACTION_UPDATE)
            addAction(TimerService.ACTION_FLASH)
        }

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, filter)
        }
    }

    override fun onStop() {
        try {
            unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
        }
        super.onStop()
    }

    private fun formatTime(value: Long): String {
        val t = value.coerceAtLeast(0)
        val h = t / 3600
        val m = (t % 3600) / 60
        val s = t % 60
        return "%02d:%02d:%02d".format(h, m, s)
    }

    private fun numberInput(initial: String) = EditText(this).apply {
        setText(initial)
        inputType = 2
        textSize = 20f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setSingleLine()
        background = rounded(Color.rgb(25, 34, 49), 12)
    }

    private fun inputColumn(label: String, field: EditText): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), 0, dp(4), dp(12))
        }
        column.addView(TextView(this).apply {
            text = label
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.LTGRAY)
        }, match(-2))
        column.addView(field, LinearLayout.LayoutParams(
            matchWidth(), dp(54)
        ))
        return column
    }

    private fun actionButton(label: String, action: () -> Unit) =
        Button(this).apply {
            text = label
            setOnClickListener { action() }
            setTextColor(Color.WHITE)
            background = rounded(Color.rgb(44, 58, 80), 14)
            isAllCaps = false
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(
                0, dp(54), 1f
            ).apply {
                setMargins(dp(4), dp(4), dp(4), dp(4))
            }
        }

    private fun rounded(color: Int, radius: Int) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }

    private fun dp(value: Int) =
        (value * resources.displayMetrics.density).toInt()

    private fun match(width: Int) =
        LinearLayout.LayoutParams(width, -2)

    private fun matchWidth() = -1

    private fun weight() =
        LinearLayout.LayoutParams(0, -2, 1f)
}
