package com.example.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.R
import com.example.databinding.ActivitySettingsBinding
import com.example.service.AccessibilityHelperService
import com.example.viewmodel.MainViewModel

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val viewModel: MainViewModel by viewModels()

    private val modelsList = listOf(
        Pair("Native Audio (Human Voice) — DEFAULT", "models/gemini-2.5-flash-native-audio-preview-12-2025"),
        Pair("Flash Live (Fast)", "models/gemini-2.0-flash-live-001"),
        Pair("Pro Audio Dialog", "models/gemini-2.5-flash-preview-native-audio-dialog")
    )

    private val voicesList = listOf(
        Pair("Aoede (Female) — Default", "Aoede"),
        Pair("Charon (Male)", "Charon"),
        Pair("Kore (Female)", "Kore"),
        Pair("Fenrir (Male)", "Fenrir"),
        Pair("Puck (Male)", "Puck"),
        Pair("Leda (Female)", "Leda"),
        Pair("Orus (Male)", "Orus"),
        Pair("Zephyr (Female)", "Zephyr")
    )

    private lateinit var primeAdapter: PrimeContactAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initViews()
        loadSavedSettings()
        setupPrimeContactsList()
        checkAccessibilityStatus()
    }

    override fun onResume() {
        super.onResume()
        checkAccessibilityStatus()
    }

    private fun initViews() {
        binding.settingsBackBtn.setOnClickListener {
            finish()
        }

        // Model Spinner
        val modelLabels = modelsList.map { it.first }
        val modelAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, modelLabels)
        binding.settingsModelSpinner.adapter = modelAdapter

        // Voice Spinner
        val voiceLabels = voicesList.map { it.first }
        val voiceAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, voiceLabels)
        binding.settingsVoiceSpinner.adapter = voiceAdapter

        // Open Accessibility Settings
        binding.openAccessibilityBtn.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }

        // Add Prime Contact
        binding.addPrimeContactBtn.setOnClickListener {
            showAddPrimeContactDialog()
        }

        // Save
        binding.saveSettingsBtn.setOnClickListener {
            saveSettings()
        }
    }

    private fun loadSavedSettings() {
        val prefs = getSharedPreferences("myra_prefs", Context.MODE_PRIVATE)

        val apiKey = prefs.getString("api_key", "") ?: ""
        binding.settingsApiKeyInput.setText(apiKey)

        val userName = prefs.getString("user_name", "Sir") ?: "Sir"
        binding.settingsNameInput.setText(userName)

        val savedModel = prefs.getString("gemini_model", modelsList[0].second)
        val modelIndex = modelsList.indexOfFirst { it.second == savedModel }.coerceAtLeast(0)
        binding.settingsModelSpinner.setSelection(modelIndex)

        val savedVoice = prefs.getString("gemini_voice", voicesList[0].second)
        val voiceIndex = voicesList.indexOfFirst { it.second == savedVoice }.coerceAtLeast(0)
        binding.settingsVoiceSpinner.setSelection(voiceIndex)

        when (prefs.getString("personality_mode", "gf")) {
            "professional" -> binding.radioProfessional.isChecked = true
            "assistant" -> binding.radioAssistant.isChecked = true
            else -> binding.radioGf.isChecked = true
        }
    }

    private fun setupPrimeContactsList() {
        binding.primeContactsRecycler.layoutManager = LinearLayoutManager(this)

        viewModel.primeContacts.observe(this) { contacts ->
            primeAdapter = PrimeContactAdapter(contacts) { position ->
                viewModel.deletePrimeContact(position)
            }
            binding.primeContactsRecycler.adapter = primeAdapter
        }
    }

    private fun showAddPrimeContactDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_add_prime_contact, null)
        val nameInput = view.findViewById<EditText>(R.id.dialogNameInput)
        val numInput = view.findViewById<EditText>(R.id.dialogNumberInput)

        AlertDialog.Builder(this)
            .setView(view)
            .setPositiveButton("Add") { _, _ ->
                val name = nameInput.text.toString().trim()
                val number = numInput.text.toString().trim()
                if (name.isNotEmpty() && number.isNotEmpty()) {
                    viewModel.addPrimeContact(name, number)
                } else {
                    Toast.makeText(this, "Please enter both name and number", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun checkAccessibilityStatus() {
        val enabled = AccessibilityHelperService.isEnabled(this)
        if (enabled) {
            binding.accessibilityStatusText.text = "✅ Active (Automations Enabled)"
            binding.accessibilityStatusText.setTextColor(getColor(R.color.success))
        } else {
            binding.accessibilityStatusText.text = "❌ Inactive (Tap to grant)"
            binding.accessibilityStatusText.setTextColor(getColor(R.color.primary_accent))
        }
    }

    private fun saveSettings() {
        val prefs = getSharedPreferences("myra_prefs", Context.MODE_PRIVATE)

        val apiKey = binding.settingsApiKeyInput.text.toString().trim()
        val userName = binding.settingsNameInput.text.toString().trim().ifEmpty { "Sir" }

        val selectedModelIndex = binding.settingsModelSpinner.selectedItemPosition.coerceIn(0, modelsList.size - 1)
        val selectedModel = modelsList[selectedModelIndex].second

        val selectedVoiceIndex = binding.settingsVoiceSpinner.selectedItemPosition.coerceIn(0, voicesList.size - 1)
        val selectedVoice = voicesList[selectedVoiceIndex].second

        val personalityMode = when (binding.settingsPersonalityRadioGroup.checkedRadioButtonId) {
            R.id.radioProfessional -> "professional"
            R.id.radioAssistant -> "assistant"
            else -> "gf"
        }

        prefs.edit().apply {
            putString("api_key", apiKey)
            putString("user_name", userName)
            putString("gemini_model", selectedModel)
            putString("gemini_voice", selectedVoice)
            putString("personality_mode", personalityMode)
            apply()
        }

        Toast.makeText(this, "Settings saved! Reconnecting to MYRA...", Toast.LENGTH_LONG).show()
        setResult(RESULT_OK)
        finish()
    }
}
