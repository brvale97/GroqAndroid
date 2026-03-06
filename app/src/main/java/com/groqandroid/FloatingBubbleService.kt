package com.groqandroid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import kotlin.math.abs

class FloatingBubbleService : Service() {

    companion object {
        const val CHANNEL_ID = "bubble_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.groqandroid.STOP_BUBBLE"
        private const val PREFS_NAME = "bubble_prefs"
        private const val KEY_BUBBLE_X = "bubble_x"
        private const val KEY_BUBBLE_Y = "bubble_y"
        private const val TAP_THRESHOLD = 10
    }

    private lateinit var windowManager: WindowManager
    private lateinit var bubbleView: View
    private lateinit var layoutParams: WindowManager.LayoutParams

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        createBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            windowManager.removeView(bubbleView)
        } catch (_: Exception) {}
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Quick Switch Bubble",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows the floating quick switch bubble"
            setShowBadge(false)
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, FloatingBubbleService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("GroqAndroid Quick Switch")
            .setContentText("Tap the bubble to switch keyboard")
            .setSmallIcon(R.drawable.ic_mic)
            .addAction(
                Notification.Action.Builder(
                    null, "Stop", stopPending
                ).build()
            )
            .setOngoing(true)
            .build()
    }

    private fun createBubble() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val bubbleSize = (48 * resources.displayMetrics.density).toInt()

        // Restore saved position
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedX = prefs.getInt(KEY_BUBBLE_X, 0)
        val savedY = prefs.getInt(KEY_BUBBLE_Y, resources.displayMetrics.heightPixels / 3)

        layoutParams = WindowManager.LayoutParams(
            bubbleSize, bubbleSize,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedX
            y = savedY
        }

        bubbleView = ImageView(this).apply {
            setImageResource(R.drawable.ic_mic)
            setBackgroundResource(R.drawable.bubble_bg)
            val pad = (10 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            elevation = 8 * resources.displayMetrics.density
        }

        setupTouchListener()

        windowManager.addView(bubbleView, layoutParams)
    }

    private fun setupTouchListener() {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        bubbleView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    layoutParams.x = initialX + (event.rawX - initialTouchX).toInt()
                    layoutParams.y = initialY + (event.rawY - initialTouchY).toInt()
                    try {
                        windowManager.updateViewLayout(bubbleView, layoutParams)
                    } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val deltaX = abs(event.rawX - initialTouchX)
                    val deltaY = abs(event.rawY - initialTouchY)

                    if (deltaX < TAP_THRESHOLD && deltaY < TAP_THRESHOLD) {
                        switchToGroqKeyboard()
                    } else {
                        snapToEdge()
                    }
                    savePosition()
                    true
                }
                else -> false
            }
        }
    }

    private fun snapToEdge() {
        val screenWidth = resources.displayMetrics.widthPixels
        val bubbleSize = (48 * resources.displayMetrics.density).toInt()
        val centerX = layoutParams.x + bubbleSize / 2

        layoutParams.x = if (centerX < screenWidth / 2) 0 else screenWidth - bubbleSize

        try {
            windowManager.updateViewLayout(bubbleView, layoutParams)
        } catch (_: Exception) {}
    }

    private fun savePosition() {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_BUBBLE_X, layoutParams.x)
            .putInt(KEY_BUBBLE_Y, layoutParams.y)
            .apply()
    }

    private fun switchToGroqKeyboard() {
        val hasSecureSettings = try {
            val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            Settings.Secure.putString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD, current)
            true
        } catch (_: SecurityException) {
            false
        }

        if (hasSecureSettings) {
            val imeId = "$packageName/.GroqIME"
            Settings.Secure.putString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD, imeId)
        } else {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }
    }
}
