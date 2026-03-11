package com.groqandroid

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import org.json.JSONArray
import org.json.JSONObject

class SettingsActivity : AppCompatActivity() {

    companion object {
        const val PREFS_NAME = "groq_prefs"
        const val KEY_API_KEY = "api_key"
        const val KEY_LANGUAGE = "language"
        const val KEY_TIP_DISMISSED = "tip_dismissed"
        const val KEY_DICTIONARY = "dictionary"
        const val KEY_REPLACEMENTS = "replacements"
        const val KEY_BUBBLE_ENABLED = "bubble_enabled"
        const val KEY_SOUND_ENABLED = "sound_enabled"
        const val KEY_AUTO_SHOW_BUBBLE = "auto_show_bubble"
        const val KEY_WHISPER_MODEL = "whisper_model"
        const val KEY_API_ENDPOINT = "api_endpoint"
    }

    private lateinit var activationBanner: LinearLayout
    private lateinit var activationText: TextView
    private lateinit var activateButton: Button
    private lateinit var bubbleSwitch: SwitchCompat

    // Export/import launchers
    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@registerForActivityResult
        try {
            val prefs = getEncryptedPrefs()
            val export = JSONObject().apply {
                put("dictionary", prefs.getString(KEY_DICTIONARY, "") ?: "")
                put("replacements", JSONArray(prefs.getString(KEY_REPLACEMENTS, "[]") ?: "[]"))
            }
            contentResolver.openOutputStream(uri)?.use { it.write(export.toString(2).toByteArray()) }
            Toast.makeText(this, "Settings exported", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private var onImportComplete: (() -> Unit)? = null
    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        try {
            val json = contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: return@registerForActivityResult
            val obj = JSONObject(json)
            val prefs = getEncryptedPrefs()
            val editor = prefs.edit()
            if (obj.has("dictionary")) {
                editor.putString(KEY_DICTIONARY, obj.getString("dictionary"))
            }
            if (obj.has("replacements")) {
                editor.putString(KEY_REPLACEMENTS, obj.getJSONArray("replacements").toString())
            }
            editor.apply()
            onImportComplete?.invoke()
            Toast.makeText(this, "Settings imported", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Import failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val apiKeyInput = findViewById<EditText>(R.id.apiKeyInput)
        val saveButton = findViewById<Button>(R.id.saveButton)
        val statusText = findViewById<TextView>(R.id.statusText)
        activationBanner = findViewById(R.id.activationBanner)
        activationText = findViewById(R.id.activationText)
        activateButton = findViewById(R.id.activateButton)

        // Load existing key
        val prefs = getEncryptedPrefs()
        val existingKey = prefs.getString(KEY_API_KEY, "") ?: ""
        if (existingKey.isNotEmpty()) {
            apiKeyInput.setText(existingKey)
            statusText.text = "API key is set"
        }

        saveButton.setOnClickListener {
            val key = apiKeyInput.text.toString().trim()
            if (key.isEmpty()) {
                Toast.makeText(this, R.string.api_key_empty, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.edit().putString(KEY_API_KEY, key).apply()
            Toast.makeText(this, R.string.api_key_saved, Toast.LENGTH_SHORT).show()
            statusText.text = "API key saved"
        }

        val tipBanner = findViewById<LinearLayout>(R.id.tipBanner)
        val tipDismissButton = findViewById<ImageButton>(R.id.tipDismissButton)

        tipDismissButton.setOnClickListener {
            tipBanner.visibility = View.GONE
            prefs.edit().putBoolean(KEY_TIP_DISMISSED, true).apply()
        }

        // Dictionary
        val chipGroup = findViewById<ChipGroup>(R.id.chipGroup)
        val dictionaryInput = findViewById<EditText>(R.id.dictionaryInput)
        val addWordButton = findViewById<Button>(R.id.addWordButton)

        fun loadChips() {
            chipGroup.removeAllViews()
            val words = prefs.getString(KEY_DICTIONARY, "") ?: ""
            if (words.isNotEmpty()) {
                words.split(",").filter { it.isNotBlank() }.forEach { word ->
                    val chip = Chip(this).apply {
                        text = word.trim()
                        isCloseIconVisible = true
                        setOnCloseIconClickListener {
                            val current = (prefs.getString(KEY_DICTIONARY, "") ?: "")
                                .split(",").filter { w -> w.trim() != word.trim() && w.isNotBlank() }
                            prefs.edit().putString(KEY_DICTIONARY, current.joinToString(",")).apply()
                            chipGroup.removeView(this)
                        }
                    }
                    chipGroup.addView(chip)
                }
            }
        }

        fun addWord() {
            val word = dictionaryInput.text.toString().trim()
            if (word.isEmpty()) return
            val current = prefs.getString(KEY_DICTIONARY, "") ?: ""
            val existing = current.split(",").filter { it.isNotBlank() }.map { it.trim() }
            if (existing.contains(word)) {
                Toast.makeText(this, "Word already added", Toast.LENGTH_SHORT).show()
                return
            }
            val updated = if (current.isBlank()) word else "$current,$word"
            prefs.edit().putString(KEY_DICTIONARY, updated).apply()
            dictionaryInput.text.clear()
            loadChips()
        }

        addWordButton.setOnClickListener { addWord() }
        dictionaryInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { addWord(); true } else false
        }
        loadChips()

        // Word Replacements
        val replacementChipGroup = findViewById<ChipGroup>(R.id.replacementChipGroup)
        val replacementFromInput = findViewById<EditText>(R.id.replacementFromInput)
        val replacementToInput = findViewById<EditText>(R.id.replacementToInput)
        val addReplacementButton = findViewById<Button>(R.id.addReplacementButton)

        fun loadReplacementChips() {
            replacementChipGroup.removeAllViews()
            val json = prefs.getString(KEY_REPLACEMENTS, "[]") ?: "[]"
            try {
                val arr = JSONArray(json)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val from = obj.getString("from")
                    val to = obj.getString("to")
                    val chip = Chip(this).apply {
                        text = "$from → $to"
                        isCloseIconVisible = true
                        setOnCloseIconClickListener {
                            val current = JSONArray(prefs.getString(KEY_REPLACEMENTS, "[]") ?: "[]")
                            val updated = JSONArray()
                            for (j in 0 until current.length()) {
                                val o = current.getJSONObject(j)
                                if (!(o.getString("from") == from && o.getString("to") == to)) {
                                    updated.put(o)
                                }
                            }
                            prefs.edit().putString(KEY_REPLACEMENTS, updated.toString()).apply()
                            replacementChipGroup.removeView(this)
                        }
                    }
                    replacementChipGroup.addView(chip)
                }
            } catch (_: Exception) {}
        }

        fun addReplacement() {
            val from = replacementFromInput.text.toString().trim()
            val to = replacementToInput.text.toString().trim()
            if (from.isEmpty() || to.isEmpty()) {
                Toast.makeText(this, "Both fields are required", Toast.LENGTH_SHORT).show()
                return
            }
            val json = prefs.getString(KEY_REPLACEMENTS, "[]") ?: "[]"
            val arr = try { JSONArray(json) } catch (_: Exception) { JSONArray() }
            // Check for duplicate "from"
            for (i in 0 until arr.length()) {
                if (arr.getJSONObject(i).getString("from").equals(from, ignoreCase = true)) {
                    Toast.makeText(this, "Replacement for \"$from\" already exists", Toast.LENGTH_SHORT).show()
                    return
                }
            }
            arr.put(JSONObject().put("from", from).put("to", to))
            prefs.edit().putString(KEY_REPLACEMENTS, arr.toString()).apply()
            replacementFromInput.text.clear()
            replacementToInput.text.clear()
            loadReplacementChips()
        }

        addReplacementButton.setOnClickListener { addReplacement() }
        replacementToInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { addReplacement(); true } else false
        }
        loadReplacementChips()

        // Export / Import
        val exportButton = findViewById<Button>(R.id.exportButton)
        val importButton = findViewById<Button>(R.id.importButton)
        exportButton.setOnClickListener {
            exportLauncher.launch("groqandroid-settings.json")
        }
        importButton.setOnClickListener {
            onImportComplete = { loadChips(); loadReplacementChips() }
            importLauncher.launch(arrayOf("application/json"))
        }

        // Custom API Endpoint
        val endpointInput = findViewById<EditText>(R.id.endpointInput)
        val saveEndpointButton = findViewById<Button>(R.id.saveEndpointButton)
        val savedEndpoint = prefs.getString(KEY_API_ENDPOINT, "") ?: ""
        if (savedEndpoint.isNotEmpty()) {
            endpointInput.setText(savedEndpoint)
        }
        saveEndpointButton.setOnClickListener {
            val endpoint = endpointInput.text.toString().trim()
            if (endpoint.isEmpty()) {
                prefs.edit().remove(KEY_API_ENDPOINT).apply()
                Toast.makeText(this, "Using default Groq endpoint", Toast.LENGTH_SHORT).show()
            } else if (!endpoint.startsWith("https://") && !endpoint.startsWith("http://")) {
                Toast.makeText(this, "Endpoint must start with https://", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            } else {
                prefs.edit().putString(KEY_API_ENDPOINT, endpoint).apply()
                Toast.makeText(this, "Endpoint saved", Toast.LENGTH_SHORT).show()
            }
        }

        // Sound toggle (default: on)
        val soundSwitch = findViewById<SwitchCompat>(R.id.soundSwitch)
        soundSwitch.isChecked = prefs.getBoolean(KEY_SOUND_ENABLED, true)
        soundSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_SOUND_ENABLED, isChecked).apply()
        }

        // Whisper model picker
        val modelGroup = findViewById<com.google.android.material.chip.ChipGroup>(R.id.modelChipGroup)
        val currentModel = prefs.getString(KEY_WHISPER_MODEL, "whisper-large-v3-turbo") ?: "whisper-large-v3-turbo"
        findViewById<com.google.android.material.chip.Chip>(R.id.chipTurbo).isChecked = currentModel == "whisper-large-v3-turbo"
        findViewById<com.google.android.material.chip.Chip>(R.id.chipFull).isChecked = currentModel == "whisper-large-v3"
        modelGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            val model = if (checkedIds.contains(R.id.chipFull)) "whisper-large-v3" else "whisper-large-v3-turbo"
            prefs.edit().putString(KEY_WHISPER_MODEL, model).apply()
        }

        // Floating Voice Input Bubble
        bubbleSwitch = findViewById(R.id.bubbleSwitch)
        bubbleSwitch.isChecked = isAccessibilityServiceEnabled() && prefs.getBoolean(KEY_BUBBLE_ENABLED, false)
        bubbleSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (!isAccessibilityServiceEnabled()) {
                    bubbleSwitch.isChecked = false
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("Enable Accessibility Service")
                        .setMessage(
                            "To use the floating bubble, enable the GroqAndroid Voice Input accessibility service.\n\n" +
                            "If it shows \"Controlled by restricted setting\" or \"App was denied access\":\n\n" +
                            "1. Go to Settings → Apps → GroqAndroid Voice Input\n" +
                            "2. Tap the ⋮ menu (top right)\n" +
                            "3. Select \"Allow restricted settings\"\n" +
                            "4. Confirm with your PIN/fingerprint\n" +
                            "5. Then go back and enable the accessibility service"
                        )
                        .setPositiveButton("Open Accessibility Settings") { _, _ ->
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }
                        .setNeutralButton("Open App Settings") { _, _ ->
                            startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                } else if (!Settings.canDrawOverlays(this)) {
                    bubbleSwitch.isChecked = false
                    Toast.makeText(this, "Overlay permission needed for the floating bubble", Toast.LENGTH_SHORT).show()
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                } else {
                    // Request notification permission on Android 13+
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
                        }
                    }
                    prefs.edit().putBoolean(KEY_BUBBLE_ENABLED, true).apply()
                    sendBroadcast(Intent(TranscriptionOverlayService.ACTION_TOGGLE_BUBBLE).apply {
                        setPackage(packageName)
                    })
                }
            } else {
                prefs.edit().putBoolean(KEY_BUBBLE_ENABLED, false).apply()
                sendBroadcast(Intent(TranscriptionOverlayService.ACTION_TOGGLE_BUBBLE).apply {
                    setPackage(packageName)
                })
            }
        }

        updateActivationBanner()
    }

    override fun onResume() {
        super.onResume()
        updateActivationBanner()
        // Sync toggle with actual service state
        val prefs = getEncryptedPrefs()
        val serviceEnabled = isAccessibilityServiceEnabled()
        if (!serviceEnabled) {
            bubbleSwitch.isChecked = false
            prefs.edit().putBoolean(KEY_BUBBLE_ENABLED, false).apply()
        } else {
            bubbleSwitch.isChecked = prefs.getBoolean(KEY_BUBBLE_ENABLED, false)
            // Request battery optimization exemption when bubble is enabled
            if (prefs.getBoolean(KEY_BUBBLE_ENABLED, false)) {
                requestBatteryOptimizationExemptionIfNeeded()
            }
        }
    }

    private fun requestBatteryOptimizationExemptionIfNeeded() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                @Suppress("BatteryLife")
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (_: Exception) {}
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        return enabledServices.any {
            it.resolveInfo.serviceInfo.packageName == packageName &&
                it.resolveInfo.serviceInfo.name == TranscriptionOverlayService::class.java.name
        }
    }

    private fun isKeyboardEnabled(): Boolean {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        return imm.enabledInputMethodList.any {
            it.packageName == packageName
        }
    }

    private fun isKeyboardSelected(): Boolean {
        val currentIme = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        return currentIme?.startsWith("$packageName/") == true
    }

    private fun updateActivationBanner() {
        val tipBanner = findViewById<LinearLayout>(R.id.tipBanner)
        val prefs = getEncryptedPrefs()

        when {
            !isKeyboardEnabled() -> {
                activationBanner.visibility = View.VISIBLE
                tipBanner.visibility = View.GONE
                activationText.text = "Step 1: Enable the keyboard in your Android settings"
                activateButton.text = "Enable keyboard"
                activateButton.setOnClickListener {
                    startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                }
            }
            !isKeyboardSelected() -> {
                activationBanner.visibility = View.VISIBLE
                tipBanner.visibility = View.GONE
                activationText.text = "Step 2: Select GroqAndroid Voice as your active keyboard"
                activateButton.text = "Select keyboard"
                activateButton.setOnClickListener {
                    (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                        .showInputMethodPicker()
                }
            }
            else -> {
                activationBanner.visibility = View.GONE
                val tipDismissed = prefs.getBoolean(KEY_TIP_DISMISSED, false)
                tipBanner.visibility = if (tipDismissed) View.GONE else View.VISIBLE
            }
        }
    }

    private fun getEncryptedPrefs() = EncryptedSharedPreferences.create(
        this,
        PREFS_NAME,
        MasterKey.Builder(this).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
}
