package com.groqandroid

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import kotlin.math.abs

class TranscriptionOverlayService : AccessibilityService() {

    companion object {
        const val CHANNEL_ID = "transcription_overlay_channel"
        const val NOTIFICATION_ID = 2001
        const val ACTION_STOP = "com.groqandroid.STOP_OVERLAY"
        const val ACTION_TOGGLE_BUBBLE = "com.groqandroid.TOGGLE_BUBBLE"
        const val ACTION_SHOW_BUBBLE = "com.groqandroid.SHOW_BUBBLE"
        private const val PREFS_NAME = "bubble_prefs"
        private const val KEY_BUBBLE_X = "bubble_x"
        private const val KEY_BUBBLE_Y = "bubble_y"
        private const val TAP_THRESHOLD_DP = 10
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var windowManager: WindowManager? = null
    private var bubbleView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private lateinit var audioRecorder: AudioRecorder
    private var apiClient: GroqApiClient? = null
    private var recordingJob: Job? = null
    private var transcriptionJob: Job? = null

    private enum class State { IDLE, RECORDING, PROCESSING }
    private var state = State.IDLE
    @Volatile
    private var tapGuard = false
    private var lastInsertedText: String? = null

    private var bubbleVisible = false
    private var closeButtonView: View? = null
    private var closeButtonParams: WindowManager.LayoutParams? = null
    private var bubbleEnabled = false // user toggle from settings
    private var tapThresholdPx = 10f // computed in onServiceConnected
    private var longPressHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_STOP -> {
                    hideBubble()
                    disableSelf()
                }
                ACTION_TOGGLE_BUBBLE -> {
                    val prefs = try { getEncryptedPrefs() } catch (_: Exception) { null }
                    bubbleEnabled = prefs?.getBoolean(SettingsActivity.KEY_BUBBLE_ENABLED, false) == true
                    if (bubbleEnabled) {
                        // In auto-show mode, don't show bubble immediately —
                        // wait for a text field to be focused
                        if (!isAutoShowEnabled()) {
                            showBubble()
                        }
                    } else {
                        hideBubble()
                    }
                }
                ACTION_SHOW_BUBBLE -> {
                    bubbleEnabled = true
                    try { getEncryptedPrefs().edit().putBoolean(SettingsActivity.KEY_BUBBLE_ENABLED, true).apply() } catch (_: Exception) {}
                    showBubble()
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        audioRecorder = AudioRecorder(cacheDir)
        audioRecorder.onMaxDurationReached = {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                Toast.makeText(this, "Max duration reached (2 min)", Toast.LENGTH_SHORT).show()
                stopRecording()
            }
        }
        tapThresholdPx = TAP_THRESHOLD_DP * resources.displayMetrics.density
        // no init needed for audio cues
        createNotificationChannel()

        val filter = IntentFilter().apply {
            addAction(ACTION_STOP)
            addAction(ACTION_TOGGLE_BUBBLE)
            addAction(ACTION_SHOW_BUBBLE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(commandReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(commandReceiver, filter)
        }

        // Show bubble if enabled in settings (but not if auto-show mode is on —
        // in that case, the bubble only appears when a text field is focused)
        val prefs = try { getEncryptedPrefs() } catch (_: Exception) { null }
        bubbleEnabled = prefs?.getBoolean(SettingsActivity.KEY_BUBBLE_ENABLED, false) == true
        if (bubbleEnabled && !isAutoShowEnabled()) {
            showBubble()
        }
    }

    private var autoShown = false // tracks if bubble was auto-shown (so we only auto-hide those)

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!bubbleEnabled || event == null) return
        if (!isAutoShowEnabled()) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                val source = event.source
                if (source != null && source.isEditable) {
                    if (!bubbleVisible) {
                        autoShown = true
                        showBubble()
                    } else {
                        // Bubble already visible — mark as auto-managed so it auto-hides
                        autoShown = true
                    }
                }
                @Suppress("DEPRECATION")
                source?.recycle()
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (!bubbleVisible) return
                if (state != State.IDLE) return
                bubbleView?.postDelayed({
                    if (!bubbleEnabled || !bubbleVisible) return@postDelayed
                    if (state != State.IDLE) return@postDelayed
                    val focused = try {
                        rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    } catch (_: Exception) { null }
                    if (focused == null || !focused.isEditable) {
                        autoShown = false
                        hideBubble()
                    }
                    @Suppress("DEPRECATION")
                    focused?.recycle()
                }, 300)
            }
        }
    }

    private fun isAutoShowEnabled(): Boolean {
        return try {
            getEncryptedPrefs().getBoolean(SettingsActivity.KEY_AUTO_SHOW_BUBBLE, false)
        } catch (_: Exception) {
            false
        }
    }

    override fun onInterrupt() {
        // Required override
    }

    override fun onDestroy() {
        try { unregisterReceiver(commandReceiver) } catch (_: Exception) {}
        hideBubble()
        try {
            if (state == State.RECORDING) {
                audioRecorder.stop()
            }
        } catch (_: Exception) {}
        apiClient?.shutdown()
        scope.cancel()
        super.onDestroy()
    }

    // --- Notification ---

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Voice Input Overlay",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Floating microphone for voice input"
            setShowBadge(false)
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun showNotification(bubbleHidden: Boolean = false) {
        val stopIntent = Intent(ACTION_STOP).apply { setPackage(packageName) }
        val stopPending = PendingIntent.getBroadcast(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        if (bubbleHidden) {
            val showIntent = Intent(ACTION_SHOW_BUBBLE).apply { setPackage(packageName) }
            val showPending = PendingIntent.getBroadcast(
                this, 1, showIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            // Use the tap action on the notification itself to show the bubble
            val notification = Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_mic)
                .setContentTitle("Floating microphone hidden")
                .setContentText("Tap to show the floating microphone")
                .setContentIntent(showPending)
                .addAction(Notification.Action.Builder(null, "Show", showPending).build())
                .addAction(Notification.Action.Builder(null, "Stop", stopPending).build())
                .setOngoing(true)
                .build()

            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, notification)
        } else {
            val notification = Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_mic)
                .setContentTitle("GroqAndroid Voice Input")
                .setContentText("Floating microphone active")
                .addAction(Notification.Action.Builder(null, "Stop", stopPending).build())
                .setOngoing(true)
                .build()

            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun hideNotification() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIFICATION_ID)
    }

    // --- Bubble overlay ---

    fun showBubble() {
        if (bubbleVisible) return

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val bubbleSize = (48 * resources.displayMetrics.density).toInt()

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedX = prefs.getInt(KEY_BUBBLE_X, 0)
        val savedY = prefs.getInt(KEY_BUBBLE_Y, resources.displayMetrics.heightPixels / 3)

        // MIUI/Xiaomi doesn't deliver touch events for TYPE_ACCESSIBILITY_OVERLAY.
        // Use TYPE_APPLICATION_OVERLAY when overlay permission is granted, fall back otherwise.
        val overlayType = if (android.provider.Settings.canDrawOverlays(this)) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        }

        layoutParams = WindowManager.LayoutParams(
            bubbleSize, bubbleSize,
            overlayType,
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
            isClickable = true
        }

        setupTouchListener()

        try {
            windowManager?.addView(bubbleView, layoutParams)
            bubbleVisible = true
            showNotification()
        } catch (e: Exception) {
            Toast.makeText(this, "Could not show bubble: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun hideBubble() {
        if (!bubbleVisible) return

        // Stop recording if active
        if (state == State.RECORDING) {
            try { audioRecorder.stop() } catch (_: Exception) {}
            state = State.IDLE
        }

        hideCloseButton()
        try {
            windowManager?.removeView(bubbleView)
        } catch (_: Exception) {}
        bubbleView = null
        bubbleVisible = false

        // Show notification with "Show" button so user can bring bubble back
        if (bubbleEnabled) {
            hideNotification()
            showNotification(bubbleHidden = true)
        } else {
            hideNotification()
        }
    }

    private fun setupTouchListener() {
        val view = bubbleView ?: return
        val params = layoutParams ?: return

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false
        var isLongPress = false

        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    isLongPress = false
                    // Visual press feedback
                    v.alpha = 0.6f
                    v.scaleX = 0.9f
                    v.scaleY = 0.9f
                    // Start long-press timer (only when idle)
                    if (state == State.IDLE) {
                        longPressRunnable = Runnable {
                            isLongPress = true
                            v.alpha = 1.0f
                            v.scaleX = 1.0f
                            v.scaleY = 1.0f
                            showCloseButton()
                        }
                        longPressHandler.postDelayed(longPressRunnable!!, 600)
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = abs(event.rawX - initialTouchX)
                    val dy = abs(event.rawY - initialTouchY)
                    if (dx > tapThresholdPx || dy > tapThresholdPx) {
                        isDragging = true
                        isLongPress = false
                        longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
                        hideCloseButton()
                        // Restore visual state during drag
                        v.alpha = 1.0f
                        v.scaleX = 1.0f
                        v.scaleY = 1.0f
                    }
                    if (isDragging) {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        try {
                            windowManager?.updateViewLayout(v, params)
                        } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
                    // Restore visual state
                    v.alpha = 1.0f
                    v.scaleX = 1.0f
                    v.scaleY = 1.0f

                    if (isLongPress) {
                        // Long press handled — close button is shown
                    } else if (!isDragging) {
                        onBubbleTapped()
                    } else {
                        snapToEdge()
                    }
                    savePosition()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
                    v.alpha = 1.0f
                    v.scaleX = 1.0f
                    v.scaleY = 1.0f
                    true
                }
                else -> true
            }
        }
    }

    private fun showCloseButton() {
        if (closeButtonView != null) return
        val wm = windowManager ?: return
        val params = layoutParams ?: return
        val bubbleSize = (48 * resources.displayMetrics.density).toInt()
        val closeSize = (24 * resources.displayMetrics.density).toInt()

        val overlayType = if (android.provider.Settings.canDrawOverlays(this)) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        }

        closeButtonParams = WindowManager.LayoutParams(
            closeSize, closeSize,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = params.x + bubbleSize - closeSize / 2
            y = params.y - closeSize / 2
        }

        closeButtonView = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setBackgroundResource(R.drawable.mic_button_recording)
            val pad = (4 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            elevation = 10 * resources.displayMetrics.density
            setOnClickListener {
                hideCloseButton()
                hideBubble()
            }
        }

        try {
            wm.addView(closeButtonView, closeButtonParams)
            // Auto-hide close button after 3 seconds
            longPressHandler.postDelayed({ hideCloseButton() }, 3000)
        } catch (_: Exception) {}
    }

    private fun hideCloseButton() {
        if (closeButtonView == null) return
        try {
            windowManager?.removeView(closeButtonView)
        } catch (_: Exception) {}
        closeButtonView = null
        closeButtonParams = null
    }

    private fun snapToEdge() {
        val params = layoutParams ?: return
        val screenWidth = resources.displayMetrics.widthPixels
        val bubbleSize = (48 * resources.displayMetrics.density).toInt()
        val centerX = params.x + bubbleSize / 2

        params.x = if (centerX < screenWidth / 2) 0 else screenWidth - bubbleSize

        try {
            windowManager?.updateViewLayout(bubbleView, params)
        } catch (_: Exception) {}
    }

    private fun savePosition() {
        val params = layoutParams ?: return
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_BUBBLE_X, params.x)
            .putInt(KEY_BUBBLE_Y, params.y)
            .apply()
    }

    // --- Recording & transcription ---

    private fun vibrate(durationMs: Long = 30) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val v = getSystemService(VIBRATOR_SERVICE) as Vibrator
                v.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } catch (_: Exception) {}
    }

    private fun onBubbleTapped() {
        // Guard against rapid double-taps
        if (tapGuard) return
        tapGuard = true
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ tapGuard = false }, 300)

        when (state) {
            State.IDLE -> startRecording()
            State.RECORDING -> stopRecording()
            State.PROCESSING -> {
                // Cancel ongoing transcription
                transcriptionJob?.cancel()
                state = State.IDLE
                updateBubbleState()
                Toast.makeText(this, "Transcription cancelled", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startRecording() {
        // Check microphone permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "Microphone permission needed — open GroqAndroid app to grant", Toast.LENGTH_LONG).show()
            try {
                val intent = Intent(this, PermissionActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Could not request permission: ${e.message}", Toast.LENGTH_SHORT).show()
            }
            return
        }

        // Check API key
        val apiKey = try { getApiKey() } catch (e: Exception) {
            Toast.makeText(this, "Could not read settings: ${e.message}", Toast.LENGTH_LONG).show()
            null
        }
        if (apiKey.isNullOrEmpty()) {
            Toast.makeText(this, "Set up an API key first in the GroqAndroid app", Toast.LENGTH_LONG).show()
            return
        }
        val endpoint = getCustomEndpoint()
        apiClient = if (endpoint != null) GroqApiClient(apiKey, endpoint) else GroqApiClient(apiKey)

        // Change to recording state immediately for visual feedback
        state = State.RECORDING
        updateBubbleState()
        vibrate(30)
        if (isSoundEnabled()) scope.launch(Dispatchers.IO) { playDictationCue(floatArrayOf(523.25f, 659.25f)) }

        recordingJob = scope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(250) // let the cue finish before recording
            try {
                audioRecorder.record()
            } catch (e: Exception) {
                launch(Dispatchers.Main) {
                    Toast.makeText(this@TranscriptionOverlayService, "Recording error: ${e.message}", Toast.LENGTH_LONG).show()
                    state = State.IDLE
                    updateBubbleState()
                }
            }
        }
    }

    private fun stopRecording() {
        if (state != State.RECORDING) return
        try { audioRecorder.stop() } catch (_: Exception) {}
        state = State.PROCESSING
        updateBubbleState()
        vibrate(50)

        transcriptionJob = scope.launch {
            try {
                kotlinx.coroutines.withTimeout(60_000) {
                    recordingJob?.join()

                    // Skip transcription for very short recordings to avoid Whisper hallucination
                    val fileSize = audioRecorder.outputFile.length()
                    if (fileSize < 32000) { // ~1 second of 16kHz mono 16-bit + WAV header
                        launch(Dispatchers.Main) {
                            Toast.makeText(this@TranscriptionOverlayService, "Recording too short", Toast.LENGTH_SHORT).show()
                        }
                        return@withTimeout
                    }

                    val language = getLanguage()
                    val dictionary = getDictionary()
                    val model = getWhisperModel()
                    val rawText = apiClient?.transcribe(audioRecorder.outputFile, language, dictionary, model) ?: ""
                    val text = applyReplacements(rawText)

                    if (text.isNotEmpty()) {
                        insertTextAtCursor(text + " ")
                        lastInsertedText = text + " "
                        if (isSoundEnabled()) launch(Dispatchers.IO) { playDictationCue(floatArrayOf(587.33f, 440f)) }
                    } else {
                        Toast.makeText(this@TranscriptionOverlayService, "No speech detected", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                Toast.makeText(this@TranscriptionOverlayService, "Timed out — try again", Toast.LENGTH_SHORT).show()
            } catch (e: AuthenticationException) {
                Toast.makeText(this@TranscriptionOverlayService, "Invalid API key — check Settings", Toast.LENGTH_LONG).show()
            } catch (e: TranscriptionException) {
                Toast.makeText(this@TranscriptionOverlayService, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@TranscriptionOverlayService, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                audioRecorder.cleanup()
                state = State.IDLE
                updateBubbleState()
            }
        }
    }

    private fun updateBubbleState() {
        val view = bubbleView ?: return
        view.setBackgroundResource(
            when (state) {
                State.IDLE -> R.drawable.bubble_bg
                State.RECORDING -> R.drawable.mic_button_recording
                State.PROCESSING -> R.drawable.mic_button_processing
            }
        )
    }

    // --- Text insertion via accessibility ---

    private fun insertTextAtCursor(text: String) {
        try {
            val focusedNode = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focusedNode == null) {
                // No focused node found — fallback to clipboard paste
                pasteViaClipboard(text)
                return
            }

            // Try ACTION_SET_TEXT first (works for most native apps)
            val success = trySetText(focusedNode, text)

            if (!success) {
                // Fallback for apps like Claude that use WebView/custom text fields
                pasteViaClipboard(text)
                Toast.makeText(this, "Pasted via clipboard", Toast.LENGTH_SHORT).show()
            }

            @Suppress("DEPRECATION")
            focusedNode.recycle()
        } catch (e: Exception) {
            // Last resort fallback
            try { pasteViaClipboard(text) } catch (_: Exception) {
                Toast.makeText(this, "Could not insert text: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun trySetText(focusedNode: AccessibilityNodeInfo, text: String): Boolean {
        // Many apps (Signal, WhatsApp) return hint/placeholder as node.text when empty.
        val rawText = focusedNode.text?.toString() ?: ""
        val hintText = focusedNode.hintText?.toString()
        val rawSelStart = focusedNode.textSelectionStart
        val rawSelEnd = focusedNode.textSelectionEnd
        val isHintOnly = rawText == hintText || (rawText.isNotEmpty() && rawSelStart == -1 && rawSelEnd == -1)
        val currentText = if (isHintOnly) "" else rawText
        val selStart = if (isHintOnly) 0 else rawSelStart.coerceAtLeast(0)
        val selEnd = if (isHintOnly) 0 else rawSelEnd.coerceAtLeast(selStart)

        val newText = StringBuilder(currentText)
            .replace(selStart, selEnd, text)
            .toString()

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
        }
        val result = focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

        if (result) {
            // Move cursor to after inserted text
            val newCursorPos = selStart + text.length
            val selArgs = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newCursorPos)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newCursorPos)
            }
            focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs)
        }

        return result
    }

    private fun pasteViaClipboard(text: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        // Save current clipboard content to restore later
        val previousClip = clipboard.primaryClip
        // Set our text
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("transcription", text))
        // Small delay to let clipboard update, then paste
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            try {
                // Simulate Ctrl+V paste via accessibility global action
                // Use dispatchGesture or key events - but the most reliable way is performGlobalAction
                // However, there's no global paste action. Instead, find the focused node and paste.
                val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (node != null) {
                    node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                    @Suppress("DEPRECATION")
                    node.recycle()
                }
                // Restore previous clipboard after a short delay
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    try {
                        if (previousClip != null) clipboard.setPrimaryClip(previousClip)
                    } catch (_: Exception) {}
                }, 500)
            } catch (_: Exception) {}
        }, 100)
    }

    // --- Audio cues (matches OpenWhispr dictation cues) ---

    private fun playDictationCue(notes: FloatArray) {
        try {
            val sampleRate = 44100
            val noteDuration = 0.09f
            val noteGap = 0.025f
            val attackTime = 0.015f
            val maxGain = 0.2f

            val noteSamples = (noteDuration * sampleRate).toInt()
            val gapSamples = (noteGap * sampleRate).toInt()
            val totalSamples = notes.size * noteSamples + (notes.size - 1) * gapSamples
            val buffer = ShortArray(totalSamples)

            var offset = 0
            for (freq in notes) {
                val decayDuration = noteDuration - attackTime
                for (i in 0 until noteSamples) {
                    val t = i.toFloat() / sampleRate
                    val sine = Math.sin(2.0 * Math.PI * freq * t).toFloat()
                    // Envelope: linear ramp up, then exponential ramp down to 0.0001 (matches OpenWhispr)
                    val envelope = if (t < attackTime) {
                        (t / attackTime) * maxGain
                    } else {
                        val decayProgress = (t - attackTime) / decayDuration
                        (maxGain * Math.pow(0.0001 / maxGain.toDouble(), decayProgress.toDouble())).toFloat()
                    }
                    buffer[offset + i] = (sine * envelope * Short.MAX_VALUE).toInt().toShort()
                }
                offset += noteSamples + gapSamples
            }

            val audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(buffer.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            audioTrack.write(buffer, 0, buffer.size)
            audioTrack.setNotificationMarkerPosition(buffer.size - 1)
            val lock = java.util.concurrent.CountDownLatch(1)
            audioTrack.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(track: AudioTrack?) { lock.countDown() }
                override fun onPeriodicNotification(track: AudioTrack?) {}
            })
            audioTrack.play()
            lock.await(1000, java.util.concurrent.TimeUnit.MILLISECONDS)
            audioTrack.release()
        } catch (_: Exception) {}
    }

    // --- Settings helpers (same pattern as GroqIME) ---

    private fun getCustomEndpoint(): String? {
        return try {
            val endpoint = getEncryptedPrefs().getString(SettingsActivity.KEY_API_ENDPOINT, null)
            if (endpoint.isNullOrBlank()) null else endpoint
        } catch (_: Exception) {
            null
        }
    }

    private fun getWhisperModel(): String {
        return try {
            getEncryptedPrefs().getString(SettingsActivity.KEY_WHISPER_MODEL, "whisper-large-v3-turbo") ?: "whisper-large-v3-turbo"
        } catch (_: Exception) {
            "whisper-large-v3-turbo"
        }
    }

    private fun isSoundEnabled(): Boolean {
        return try {
            getEncryptedPrefs().getBoolean(SettingsActivity.KEY_SOUND_ENABLED, true)
        } catch (_: Exception) {
            true
        }
    }

    private fun getApiKey(): String? {
        return try {
            getEncryptedPrefs().getString(SettingsActivity.KEY_API_KEY, null)
        } catch (_: Exception) {
            null
        }
    }

    private fun getLanguage(): String? {
        return try {
            getEncryptedPrefs().getString(SettingsActivity.KEY_LANGUAGE, null)
        } catch (_: Exception) {
            null
        }
    }

    private fun getDictionary(): String? {
        return try {
            val dict = getEncryptedPrefs().getString(SettingsActivity.KEY_DICTIONARY, null)
            if (dict.isNullOrBlank()) return null
            val words = dict.split(",").filter { it.isNotBlank() }.take(200).joinToString(", ") { it.trim() }
            if (words.isEmpty()) null else "Vocabulary: $words."
        } catch (_: Exception) {
            null
        }
    }

    private fun getReplacements(): List<Pair<String, String>> {
        return try {
            val json = getEncryptedPrefs().getString(SettingsActivity.KEY_REPLACEMENTS, "[]") ?: "[]"
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                obj.getString("from") to obj.getString("to")
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun applyReplacements(text: String): String {
        var result = text
        for ((from, to) in getReplacements()) {
            val escaped = Regex.escape(from)
            val prefix = if (from.first().isLetterOrDigit() || from.first() == '_') "\\b" else ""
            val suffix = if (from.last().isLetterOrDigit() || from.last() == '_') "\\b" else ""
            val pattern = Regex("$prefix$escaped$suffix", RegexOption.IGNORE_CASE)
            result = pattern.replace(result, Regex.escapeReplacement(to))
        }
        return result
    }

    private fun getEncryptedPrefs() = EncryptedSharedPreferences.create(
        this,
        SettingsActivity.PREFS_NAME,
        MasterKey.Builder(this).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
}
