package com.groqandroid

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONArray

class GroqIME : InputMethodService() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var audioRecorder: AudioRecorder
    private var apiClient: GroqApiClient? = null
    private var recordingJob: Job? = null

    private var micButton: ImageButton? = null
    private var statusText: TextView? = null
    private var langButton: TextView? = null

    // Language options: display label -> Whisper code (null = auto)
    private val languages = listOf(
        "AUTO" to null,
        "NL" to "nl",
        "EN" to "en",
        "DE" to "de",
        "FR" to "fr",
        "ES" to "es",
        "IT" to "it",
        "PT" to "pt",
        "JA" to "ja",
        "ZH" to "zh",
        "KO" to "ko",
        "AR" to "ar",
        "TR" to "tr",
        "PL" to "pl",
        "RU" to "ru",
        "SK" to "sk"
    )
    private var currentLangIndex = 0

    private enum class State { IDLE, RECORDING, PROCESSING }
    private var state = State.IDLE
    private var lastInsertedText: String? = null

    override fun onCreate() {
        super.onCreate()
        audioRecorder = AudioRecorder(cacheDir)
        audioRecorder.onMaxDurationReached = {
            Handler(Looper.getMainLooper()).post {
                setStatus("Max duration reached (2 min)")
                stopRecording()
            }
        }
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    private fun getKeyboardHeightPx(): Int =
        (120 * resources.displayMetrics.density).toInt()

    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.keyboard_view, null)

        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val navBarInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, navBarInsets.bottom)
            insets
        }

        micButton = view.findViewById(R.id.micButton)
        statusText = view.findViewById(R.id.statusText)

        val switchButton = view.findViewById<ImageButton>(R.id.switchButton)
        val settingsButton = view.findViewById<ImageButton>(R.id.settingsButton)

        val backspaceButton = view.findViewById<ImageButton>(R.id.backspaceButton)
        langButton = view.findViewById(R.id.langButton)

        // Load saved language
        val savedLang = getLanguage()
        currentLangIndex = languages.indexOfFirst { it.second == savedLang }.coerceAtLeast(0)
        langButton?.text = languages[currentLangIndex].first

        micButton?.setOnClickListener { onMicClicked() }
        setupBackspace(backspaceButton)

        langButton?.setOnClickListener { showLanguageMenu(it) }

        switchButton.setOnClickListener { doSwitchInputMethod() }

        settingsButton.setOnClickListener {
            try {
                val intent = Intent(this, SettingsActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            } catch (_: Exception) {}
        }

        // Punctuation keys (tap = primary, long press = secondary)
        setupViewWithLongPress(view.findViewById(R.id.keyComma), ",", ";")
        setupViewWithLongPress(view.findViewById(R.id.keyDot), ".", ":")
        view.findViewById<TextView>(R.id.keyQuestion).setOnClickListener { typeText("?") }
        view.findViewById<TextView>(R.id.keyExclaim).setOnClickListener { typeText("!") }
        setupSpaceWithSelectAll(view.findViewById(R.id.keySpace))

        // Quotes button - wraps selected text in quotes
        view.findViewById<TextView>(R.id.keyQuotes).setOnClickListener { wrapSelectionInQuotes() }
        view.findViewById<TextView>(R.id.keyEnter).setOnClickListener {
            try {
                currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            } catch (_: Exception) {}
        }

        updateUI()
        return view
    }

    private fun typeText(text: String) {
        try {
            currentInputConnection?.commitText(text, 1)
        } catch (_: Exception) {}
    }

    private fun setupViewWithLongPress(view: View, tapText: String, longPressText: String) {
        var longPressed = false
        view.setOnLongClickListener {
            longPressed = true
            typeText(longPressText)
            true
        }
        view.setOnClickListener {
            if (!longPressed) typeText(tapText)
            longPressed = false
        }
    }

    private fun setupSpaceWithSelectAll(view: View) {
        var longPressed = false
        view.setOnLongClickListener {
            longPressed = true
            try {
                currentInputConnection?.performContextMenuAction(android.R.id.selectAll)
            } catch (_: Exception) {}
            true
        }
        view.setOnClickListener {
            if (!longPressed) typeText(" ")
            longPressed = false
        }
    }

    private fun wrapSelectionInQuotes() {
        try {
            val ic = currentInputConnection ?: return
            val selected = ic.getSelectedText(0)
            if (selected != null && selected.isNotEmpty()) {
                ic.commitText("\"${selected}\"", 1)
            } else {
                ic.commitText("\"\"", 1)
                val extent = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
                if (extent != null) {
                    val pos = extent.selectionStart - 1
                    if (pos >= 0) ic.setSelection(pos, pos)
                }
            }
        } catch (_: Exception) {}
    }

    private fun showLanguageMenu(anchor: View) {
        try {
            val popup = PopupMenu(this, anchor)
            languages.forEachIndexed { index, (label, _) ->
                val fullName = when (label) {
                    "AUTO" -> "Automatic"
                    "NL" -> "Nederlands"
                    "EN" -> "English"
                    "DE" -> "Deutsch"
                    "FR" -> "Français"
                    "ES" -> "Español"
                    "IT" -> "Italiano"
                    "PT" -> "Português"
                    "JA" -> "日本語"
                    "ZH" -> "中文"
                    "KO" -> "한국어"
                    "AR" -> "العربية"
                    "TR" -> "Türkçe"
                    "PL" -> "Polski"
                    "RU" -> "Русский"
                    "SK" -> "Slovenčina"
                    else -> label
                }
                popup.menu.add(0, index, index, fullName)
            }
            popup.setOnMenuItemClickListener { item ->
                currentLangIndex = item.itemId
                val (label, code) = languages[currentLangIndex]
                langButton?.text = label
                saveLanguage(code)
                true
            }
            popup.show()
        } catch (_: Exception) {}
    }

    private val backspaceHandler = Handler(Looper.getMainLooper())
    private val backspaceRepeat = object : Runnable {
        override fun run() {
            sendBackspace()
            backspaceHandler.postDelayed(this, 50)
        }
    }

    private fun setupBackspace(button: ImageButton) {
        button.setOnClickListener { sendBackspace() }
        button.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    backspaceHandler.postDelayed(backspaceRepeat, 400)
                    false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    backspaceHandler.removeCallbacks(backspaceRepeat)
                    false
                }
                else -> false
            }
        }
    }

    private fun sendBackspace() {
        try {
            currentInputConnection?.sendKeyEvent(
                KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)
            )
            currentInputConnection?.sendKeyEvent(
                KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL)
            )
        } catch (_: Exception) {}
    }

    @Volatile
    private var micClickGuard = false

    private fun onMicClicked() {
        // Guard against rapid double-taps
        if (micClickGuard) return
        micClickGuard = true
        Handler(Looper.getMainLooper()).postDelayed({ micClickGuard = false }, 300)

        when (state) {
            State.IDLE -> startRecording()
            State.RECORDING -> stopRecording()
            State.PROCESSING -> { /* ignore taps while processing */ }
        }
    }

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

    private fun startRecording() {
        // Check permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            try {
                val intent = Intent(this, PermissionActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            } catch (_: Exception) {}
            return
        }

        // Check API key
        val apiKey = getApiKey()
        if (apiKey.isNullOrEmpty()) {
            setStatus(getString(R.string.no_api_key))
            return
        }
        apiClient = GroqApiClient(apiKey)

        state = State.RECORDING
        updateUI()
        vibrate(30)

        recordingJob = scope.launch(Dispatchers.IO) {
            try {
                audioRecorder.record()
            } catch (e: Exception) {
                launch(Dispatchers.Main) {
                    setStatus("Error: ${e.message ?: "Recording failed"}")
                    state = State.IDLE
                    updateUI()
                }
            }
        }
    }

    private fun stopRecording() {
        if (state != State.RECORDING) return
        try {
            audioRecorder.stop()
        } catch (_: Exception) {}
        state = State.PROCESSING
        vibrate(50)

        val durationSec = (audioRecorder.durationMs / 1000).toInt()
        setStatus("Transcribing... (${durationSec}s audio)")
        micButton?.setBackgroundResource(R.drawable.mic_button_processing)

        scope.launch {
            try {
                // Wait for recording coroutine to finish writing the WAV file
                recordingJob?.join()

                val language = getLanguage()
                val dictionary = getDictionary()
                val model = getWhisperModel()
                val rawText = withTimeout(60_000L) {
                    apiClient?.transcribe(audioRecorder.outputFile, language, dictionary, model) ?: ""
                }
                val text = applyReplacements(rawText)
                val ic = currentInputConnection
                if (text.isNotEmpty() && ic != null) {
                    ic.commitText(text + " ", 1)
                    lastInsertedText = text + " "
                    setStatus(text)
                } else if (text.isNotEmpty()) {
                    setStatus("No input connection")
                } else {
                    setStatus("No speech detected")
                }
            } catch (e: AuthenticationException) {
                setStatus("Invalid API key — check Settings")
            } catch (e: TranscriptionException) {
                setStatus("Error: ${e.message}")
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                setStatus("Timed out — try again")
            } catch (e: Exception) {
                setStatus("Error: ${e.message}")
            } finally {
                audioRecorder.cleanup()
                state = State.IDLE
                // Only update button color, don't overwrite status text
                micButton?.setBackgroundResource(R.drawable.mic_button_bg)
            }
        }
    }

    private fun updateUI() {
        micButton?.setBackgroundResource(
            when (state) {
                State.IDLE -> R.drawable.mic_button_bg
                State.RECORDING -> R.drawable.mic_button_recording
                State.PROCESSING -> R.drawable.mic_button_processing
            }
        )
        when (state) {
            State.IDLE -> setStatus(getString(R.string.status_ready))
            State.RECORDING -> setStatus(getString(R.string.status_listening))
            State.PROCESSING -> setStatus(getString(R.string.status_processing))
        }
    }

    private fun doSwitchInputMethod() {
        try {
            // Stop any active recording before switching
            if (state == State.RECORDING) {
                audioRecorder.stop()
                state = State.IDLE
            }
            // Use input method picker - most stable across all devices
            val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showInputMethodPicker()
        } catch (_: Exception) {}
    }

    private fun setStatus(text: String) {
        statusText?.text = text
    }

    private fun saveLanguage(code: String?) {
        try {
            val prefs = getPrefs() ?: return
            prefs.edit().apply {
                if (code != null) putString(SettingsActivity.KEY_LANGUAGE, code)
                else remove(SettingsActivity.KEY_LANGUAGE)
            }.apply()
        } catch (_: Exception) {}
    }

    private fun getLanguage(): String? {
        return try {
            getPrefs()?.getString(SettingsActivity.KEY_LANGUAGE, null)
        } catch (_: Exception) {
            null
        }
    }

    private fun getDictionary(): String? {
        return try {
            val dict = getPrefs()?.getString(SettingsActivity.KEY_DICTIONARY, null)
            if (dict.isNullOrBlank()) return null
            val words = dict.split(",").filter { it.isNotBlank() }.take(200).joinToString(", ") { it.trim() }
            if (words.isEmpty()) null else "Vocabulary: $words."
        } catch (_: Exception) {
            null
        }
    }

    private fun getReplacements(): List<Pair<String, String>> {
        return try {
            val json = getPrefs()?.getString(SettingsActivity.KEY_REPLACEMENTS, "[]") ?: "[]"
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
            val pattern = Regex("\\b${Regex.escape(from)}\\b", RegexOption.IGNORE_CASE)
            result = pattern.replace(result, to)
        }
        return result
    }

    private fun getWhisperModel(): String {
        return try {
            getPrefs()?.getString(SettingsActivity.KEY_WHISPER_MODEL, "whisper-large-v3-turbo") ?: "whisper-large-v3-turbo"
        } catch (_: Exception) {
            "whisper-large-v3-turbo"
        }
    }

    private fun getApiKey(): String? {
        return try {
            getPrefs()?.getString(SettingsActivity.KEY_API_KEY, null)
        } catch (_: Exception) {
            null
        }
    }

    private fun getPrefs() = try {
        val masterKey = MasterKey.Builder(this)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            this,
            SettingsActivity.PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        // EncryptedSharedPreferences can fail on work profiles, MDM devices,
        // or after app updates that corrupt the Android Keystore.
        setStatus("Settings unavailable — try restarting app")
        null
    }

    override fun onDestroy() {
        backspaceHandler.removeCallbacksAndMessages(null)
        try {
            if (state == State.RECORDING) {
                audioRecorder.stop()
            }
        } catch (_: Exception) {}
        apiClient?.shutdown()
        scope.cancel()
        super.onDestroy()
    }
}
