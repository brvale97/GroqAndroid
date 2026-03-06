package com.groqandroid

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
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
        private const val OVERLAY_PERMISSION_REQUEST = 1001
    }

    private lateinit var activationBanner: LinearLayout
    private lateinit var activationText: TextView
    private lateinit var activateButton: Button

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

        // Quick Switch Bubble
        val bubbleSwitch = findViewById<SwitchCompat>(R.id.bubbleSwitch)
        bubbleSwitch.isChecked = prefs.getBoolean(KEY_BUBBLE_ENABLED, false)
        bubbleSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (!Settings.canDrawOverlays(this)) {
                    bubbleSwitch.isChecked = false
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST)
                } else {
                    prefs.edit().putBoolean(KEY_BUBBLE_ENABLED, true).apply()
                    startService(Intent(this, FloatingBubbleService::class.java))
                }
            } else {
                prefs.edit().putBoolean(KEY_BUBBLE_ENABLED, false).apply()
                stopService(Intent(this, FloatingBubbleService::class.java))
            }
        }

        updateActivationBanner()
    }

    override fun onResume() {
        super.onResume()
        updateActivationBanner()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_PERMISSION_REQUEST) {
            val bubbleSwitch = findViewById<SwitchCompat>(R.id.bubbleSwitch)
            if (Settings.canDrawOverlays(this)) {
                val prefs = getEncryptedPrefs()
                prefs.edit().putBoolean(KEY_BUBBLE_ENABLED, true).apply()
                bubbleSwitch.isChecked = true
                startService(Intent(this, FloatingBubbleService::class.java))
            } else {
                bubbleSwitch.isChecked = false
                Toast.makeText(this, "Overlay permission is required for the bubble", Toast.LENGTH_SHORT).show()
            }
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
