package com.example.ui.main

import android.Manifest
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.R
import com.example.ai.AudioEngine
import com.example.ai.CommandParser
import com.example.ai.GeminiLiveClient
import com.example.databinding.ActivityMainBinding
import com.example.service.CallMonitorService
import com.example.service.MyraOverlayService
import com.example.ui.settings.SettingsActivity
import com.example.viewmodel.MainViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private lateinit var geminiClient: GeminiLiveClient
    private lateinit var audioEngine: AudioEngine
    private lateinit var chatAdapter: ChatAdapter

    private val handler = Handler(Looper.getMainLooper())
    private var isMuted = false

    private var speechRecognizer: SpeechRecognizer? = null

    // Permissions launcher
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val recordAudioGranted = permissions[Manifest.permission.RECORD_AUDIO] == true
        if (recordAudioGranted) {
            audioEngine.startRecording()
            audioEngine.startPlayback()
            geminiClient.connect()
        } else {
            Toast.makeText(this, "Microphone permission is required for MYRA voice features", Toast.LENGTH_LONG).show()
        }
    }

    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            reconnectSession()
        }
    }

    // Battery receiver
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) {
                val pct = (level * 100) / scale
                binding.batteryText.text = "$pct%"
            }
        }
    }

    // Call ended receiver
    private val callEndedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == CallMonitorService.ACTION_CALL_ENDED) {
                audioEngine.isInCallMode.set(false)
                binding.statusText.text = "Call finished. Tap karke bolo 💬"
                binding.orbAnimationView.setState(OrbState.LISTENING)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)

            initChat()
            initAudioAndAI()
            initUIListeners()
            startPeriodicSystemUpdates()
            checkOverlayPermission()
            checkAndRequestAppPermissions()
            startCallMonitorService()
            handleIncomingCallIntent(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate: ${e.message}", e)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingCallIntent(intent)
    }

    private fun initChat() {
        chatAdapter = ChatAdapter()
        val layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        binding.chatRecycler.layoutManager = layoutManager
        binding.chatRecycler.adapter = chatAdapter

        // Initial welcome message
        chatAdapter.addMessage(
            ChatMessage("Namaste! Main MYRA hoon. Aapki AI Voice Companion ❤️", isUser = false)
        )

        viewModel.commandResult.observe(this) { resultMsg ->
            if (!resultMsg.isNullOrEmpty()) {
                chatAdapter.addMessage(ChatMessage(resultMsg, isUser = false))
                binding.chatRecycler.smoothScrollToPosition(chatAdapter.itemCount - 1)
                geminiClient.sendText("System update: $resultMsg")
            }
        }
    }

    private fun initAudioAndAI() {
        audioEngine = AudioEngine(this)
        geminiClient = GeminiLiveClient(this)

        // Mic chunk from AudioEngine -> send to GeminiLiveClient
        audioEngine.onAudioChunkReady = { base64 ->
            geminiClient.sendAudioChunk(base64)
        }

        // Amplitude callback -> update Orb & Waveform
        audioEngine.onAmplitudeChanged = { rms ->
            runOnUiThread {
                binding.orbAnimationView.setAmplitude(rms)
                binding.waveformView.setAmplitude(rms)
            }
        }

        audioEngine.onSpeakingStarted = {
            runOnUiThread {
                binding.orbAnimationView.setState(OrbState.SPEAKING)
                binding.statusText.text = "MYRA is speaking..."
            }
        }

        audioEngine.onSpeakingStopped = {
            runOnUiThread {
                binding.orbAnimationView.setState(OrbState.LISTENING)
                binding.statusText.text = "Listening... bolo 🎙️"
            }
        }

        // GeminiLiveClient callbacks
        geminiClient.onConnected = {
            runOnUiThread {
                binding.statusText.text = "Connected • Listening... bolo 🎙️"
                binding.orbAnimationView.setState(OrbState.LISTENING)
                setGlowOverlay(true)
            }
        }

        geminiClient.onDisconnected = { reason ->
            runOnUiThread {
                binding.statusText.text = "Disconnected. Tap mic to retry."
                binding.orbAnimationView.setState(OrbState.IDLE)
                setGlowOverlay(false)
            }
        }

        geminiClient.onError = { error ->
            runOnUiThread {
                binding.statusText.text = error
                binding.orbAnimationView.setState(OrbState.IDLE)
            }
        }

        geminiClient.onAudioReceived = { pcmBytes ->
            audioEngine.queueAudio(pcmBytes)
        }

        geminiClient.onInputTranscript = { userText ->
            runOnUiThread {
                chatAdapter.addMessage(ChatMessage(userText, isUser = true))
                binding.chatRecycler.smoothScrollToPosition(chatAdapter.itemCount - 1)
                processVoiceCommand(userText)
            }
        }

        geminiClient.onOutputTranscript = { myraText ->
            runOnUiThread {
                chatAdapter.addMessage(ChatMessage(myraText, isUser = false))
                binding.chatRecycler.smoothScrollToPosition(chatAdapter.itemCount - 1)
            }
        }

        geminiClient.onTurnComplete = {
            runOnUiThread {
                binding.statusText.text = "Listening... bolo 🎙️"
            }
        }
    }

    private fun initUIListeners() {
        // Settings Button
        binding.settingsBtn.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            settingsLauncher.launch(intent)
        }

        // Mic Button Single Tap: Mute / Unmute
        binding.micButton.setOnClickListener {
            isMuted = !isMuted
            audioEngine.setMuted(isMuted)
            if (isMuted) {
                binding.micButton.setImageResource(R.drawable.ic_mic_off)
                binding.statusText.text = "Mic Muted 🔇"
                binding.orbAnimationView.setState(OrbState.IDLE)
                binding.waveformView.stopAnimation()
            } else {
                binding.micButton.setImageResource(R.drawable.ic_mic_on)
                binding.statusText.text = "Listening... bolo 🎙️"
                binding.orbAnimationView.setState(OrbState.LISTENING)
                binding.waveformView.startAnimation()
            }
        }

        // Mic Button Long Press: Interrupt playback
        binding.micButton.setOnLongClickListener {
            audioEngine.interruptPlayback()
            geminiClient.sendInterrupt()
            binding.statusText.text = "Interrupted. Bolo 🎙️"
            binding.orbAnimationView.setState(OrbState.LISTENING)
            Toast.makeText(this, "Stopped speaking", Toast.LENGTH_SHORT).show()
            true
        }

        // Send Text Button
        binding.sendTextBtn.setOnClickListener {
            sendTypedMessage()
        }

        binding.chatInputEditText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendTypedMessage()
                true
            } else false
        }
    }

    private fun sendTypedMessage() {
        val text = binding.chatInputEditText.text.toString().trim()
        if (text.isNotEmpty()) {
            binding.chatInputEditText.text.clear()
            chatAdapter.addMessage(ChatMessage(text, isUser = true))
            binding.chatRecycler.smoothScrollToPosition(chatAdapter.itemCount - 1)
            processVoiceCommand(text)
            geminiClient.sendText(text)
        }
    }

    private fun processVoiceCommand(text: String) {
        val command = CommandParser.parse(text)
        if (command != null) {
            viewModel.executeCommand(command)
        }
    }

    private fun setGlowOverlay(active: Boolean) {
        val targetAlpha = if (active) 0.16f else 0.0f
        binding.redOverlay.animate().alpha(targetAlpha).setDuration(500).start()
    }

    private fun reconnectSession() {
        binding.statusText.text = "Reconnecting to MYRA..."
        binding.orbAnimationView.setState(OrbState.THINKING)
        geminiClient.disconnect()
        handler.postDelayed({
            geminiClient.connect()
        }, 1000)
    }

    private fun startPeriodicSystemUpdates() {
        // Register battery receiver (system broadcast)
        try {
            ContextCompat.registerReceiver(
                this,
                batteryReceiver,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                ContextCompat.RECEIVER_EXPORTED
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error registering battery receiver: ${e.message}")
        }

        // Register call ended receiver (internal app broadcast)
        try {
            ContextCompat.registerReceiver(
                this,
                callEndedReceiver,
                IntentFilter(CallMonitorService.ACTION_CALL_ENDED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error registering call ended receiver: ${e.message}")
        }

        // Clock & RAM runnable
        val updateRunnable = object : Runnable {
            override fun run() {
                try {
                    val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                    binding.timeText.text = timeStr

                    // RAM Free
                    val actManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                    val memInfo = ActivityManager.MemoryInfo()
                    actManager.getMemoryInfo(memInfo)
                    val freeGb = memInfo.availMem / (1024.0 * 1024.0 * 1024.0)
                    binding.ramText.text = String.format(Locale.US, "RAM: %.1fGB FREE", freeGb)
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating RAM/Clock: ${e.message}")
                }

                handler.postDelayed(this, 3000)
            }
        }
        handler.post(updateRunnable)
    }

    private fun checkOverlayPermission() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                // Optional: User can grant in settings when needed
                Log.d(TAG, "Overlay permission not granted yet")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking overlay permission: ${e.message}")
        }
    }

    private fun checkAndRequestAppPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            permissions.add(Manifest.permission.ANSWER_PHONE_CALLS)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            try {
                permissionLauncher.launch(missing.toTypedArray())
            } catch (e: Exception) {
                Log.e(TAG, "Error launching permissions: ${e.message}")
            }
        } else {
            try {
                audioEngine.startRecording()
                audioEngine.startPlayback()
                geminiClient.connect()
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing audio engine: ${e.message}")
            }
        }
    }

    private fun startCallMonitorService() {
        try {
            val serviceIntent = Intent(this, CallMonitorService::class.java)
            startService(serviceIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting CallMonitorService: ${e.message}")
        }
    }

    private fun handleIncomingCallIntent(intent: Intent?) {
        val isCall = intent?.getBooleanExtra(CallMonitorService.EXTRA_INCOMING_CALL, false) ?: false
        val callerName = intent?.getStringExtra(CallMonitorService.EXTRA_CALLER_NAME) ?: ""

        if (isCall && callerName.isNotEmpty()) {
            audioEngine.isInCallMode.set(true)
            binding.statusText.text = "Incoming Call from: $callerName"
            binding.orbAnimationView.setState(OrbState.ACTIVE)

            val announcement = "$callerName ka phone aa raha hai. Uthana hai ya kaat doon?"
            chatAdapter.addMessage(ChatMessage(announcement, isUser = false))

            // Start listening for pick/reject voice command
            startCallDecisionListener()
        }
    }

    private fun startCallDecisionListener() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return

        try {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull()?.lowercase() ?: ""
                        Log.d(TAG, "Call decision recognized: $text")

                        if (text.contains("uthana") || text.contains("pick") || text.contains("answer") || text.contains("yes") || text.contains("haan")) {
                            viewModel.acceptCall()
                        } else if (text.contains("kaat") || text.contains("reject") || text.contains("cut") || text.contains("no") || text.contains("mat")) {
                            viewModel.rejectCall()
                        }
                    }

                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onError(error: Int) {
                        Log.w(TAG, "Call speech recognizer error: $error")
                    }
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }

            val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
            }
            speechRecognizer?.startListening(recognizerIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Error in speech recognizer: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(batteryReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering battery receiver: ${e.message}")
        }
        try {
            unregisterReceiver(callEndedReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering call ended receiver: ${e.message}")
        }
        speechRecognizer?.destroy()
        speechRecognizer = null
        audioEngine.release()
        geminiClient.disconnect()
    }
}
