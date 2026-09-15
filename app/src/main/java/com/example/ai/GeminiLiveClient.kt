package com.example.ai

import android.content.Context
import android.util.Base64
import android.util.Log
import com.example.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class GeminiLiveClient(private val context: Context) {

    companion object {
        private const val TAG = "GeminiLiveClient"
        private const val WS_BASE_URL =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent"

        const val DEFAULT_MODEL = "models/gemini-2.5-flash-native-audio-preview-12-2025"
        const val DEFAULT_VOICE = "Aoede"
        const val SESSION_RENEW_AFTER_MS = 540_000L // 9 minutes
        const val KEEPALIVE_INTERVAL_MS = 8_000L    // 8 seconds
        const val RECONNECT_DELAY_MS = 3_000L       // 3 seconds
    }

    var onConnected: (() -> Unit)? = null
    var onDisconnected: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onAudioReceived: ((ByteArray) -> Unit)? = null
    var onOutputTranscript: ((String) -> Unit)? = null
    var onInputTranscript: ((String) -> Unit)? = null
    var onTurnComplete: (() -> Unit)? = null

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var webSocket: WebSocket? = null
    private val isConnected = AtomicBoolean(false)
    private val shouldKeepConnected = AtomicBoolean(false)

    private var keepaliveJob: Job? = null
    private var sessionRenewalJob: Job? = null

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // infinite for WS
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(10, TimeUnit.SECONDS)
        .build()

    fun connect() {
        shouldKeepConnected.set(true)
        val apiKey = getApiKey()
        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            Log.w(TAG, "Gemini API key is not configured")
            onError?.invoke("Gemini API Key missing or not set in Settings/Secrets.")
            return
        }

        val url = "$WS_BASE_URL?key=$apiKey"
        val request = Request.Builder().url(url).build()

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected successfully")
                isConnected.set(true)
                sendSetupMessage()
                startKeepalive()
                startSessionRenewal()
                onConnected?.invoke()
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleIncomingMessage(text)
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code / $reason")
                isConnected.set(false)
                ws.close(1000, null)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code / $reason")
                isConnected.set(false)
                stopJobs()
                onDisconnected?.invoke(reason)
                scheduleReconnect()
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}", t)
                isConnected.set(false)
                stopJobs()
                onError?.invoke(t.message ?: "Connection error")
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (!shouldKeepConnected.get()) return
        scope.launch {
            delay(RECONNECT_DELAY_MS)
            if (shouldKeepConnected.get() && !isConnected.get()) {
                Log.d(TAG, "Attempting auto-reconnect...")
                connect()
            }
        }
    }

    fun disconnect() {
        shouldKeepConnected.set(false)
        stopJobs()
        isConnected.set(false)
        try {
            webSocket?.close(1000, "Normal Closure")
            webSocket = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing WebSocket: ${e.message}")
        }
    }

    fun sendAudioChunk(base64Pcm: String) {
        if (!isConnected.get()) return
        try {
            val chunk = JSONObject().apply {
                put("mime_type", "audio/pcm;rate=16000")
                put("data", base64Pcm)
            }
            val mediaChunks = JSONArray().put(chunk)
            val realtimeInput = JSONObject().put("media_chunks", mediaChunks)
            val message = JSONObject().put("realtime_input", realtimeInput)

            webSocket?.send(message.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error sending audio chunk: ${e.message}")
        }
    }

    fun sendText(text: String) {
        if (!isConnected.get()) return
        try {
            val part = JSONObject().put("text", text)
            val parts = JSONArray().put(part)
            val turn = JSONObject().apply {
                put("role", "user")
                put("parts", parts)
            }
            val turns = JSONArray().put(turn)
            val clientContent = JSONObject().apply {
                put("turns", turns)
                put("turn_complete", true)
            }
            val message = JSONObject().put("client_content", clientContent)

            webSocket?.send(message.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error sending text to Gemini: ${e.message}")
        }
    }

    fun sendInterrupt() {
        if (!isConnected.get()) return
        try {
            val clientContent = JSONObject().apply {
                put("turns", JSONArray())
                put("turn_complete", true)
            }
            val message = JSONObject().put("client_content", clientContent)
            webSocket?.send(message.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error sending interrupt: ${e.message}")
        }
    }

    private fun sendSetupMessage() {
        try {
            val prefs = context.getSharedPreferences("myra_prefs", Context.MODE_PRIVATE)
            val model = prefs.getString("gemini_model", DEFAULT_MODEL) ?: DEFAULT_MODEL
            val voice = prefs.getString("gemini_voice", DEFAULT_VOICE) ?: DEFAULT_VOICE
            val userName = prefs.getString("user_name", "Sir") ?: "Sir"
            val personality = prefs.getString("personality_mode", "gf") ?: "gf"

            val systemInstruction = buildSystemPrompt(userName, personality)

            val setup = JSONObject().apply {
                put("model", model)

                val partsArray = JSONArray().put(JSONObject().put("text", systemInstruction))
                put("system_instruction", JSONObject().put("parts", partsArray))

                val voiceConfig = JSONObject().put(
                    "voice_config",
                    JSONObject().put(
                        "prebuilt_voice_config",
                        JSONObject().put("voice_name", voice)
                    )
                )

                val generationConfig = JSONObject().apply {
                    put("response_modalities", JSONArray().put("AUDIO"))
                    put("speech_config", voiceConfig)
                    put("temperature", 0.9)
                }
                put("generation_config", generationConfig)

                put("output_audio_transcription", JSONObject())
                put("input_audio_transcription", JSONObject())
            }

            val setupMessage = JSONObject().put("setup", setup)
            webSocket?.send(setupMessage.toString())
            Log.d(TAG, "Sent setup message with model: $model, voice: $voice")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending setup message: ${e.message}", e)
        }
    }

    private fun handleIncomingMessage(jsonText: String) {
        try {
            val root = JSONObject(jsonText)

            if (root.has("serverContent")) {
                val serverContent = root.getJSONObject("serverContent")

                // Model turn parts (Audio PCM)
                if (serverContent.has("modelTurn")) {
                    val modelTurn = serverContent.getJSONObject("modelTurn")
                    if (modelTurn.has("parts")) {
                        val parts = modelTurn.getJSONArray("parts")
                        for (i in 0 until parts.length()) {
                            val part = parts.getJSONObject(i)
                            if (part.has("inlineData")) {
                                val inlineData = part.getJSONObject("inlineData")
                                val dataBase64 = inlineData.optString("data", "")
                                if (dataBase64.isNotEmpty()) {
                                    val bytes = Base64.decode(dataBase64, Base64.DEFAULT)
                                    onAudioReceived?.invoke(bytes)
                                }
                            }
                        }
                    }
                }

                // Output transcription (MYRA speech)
                if (serverContent.has("outputTranscription")) {
                    val text = serverContent.getJSONObject("outputTranscription").optString("text", "")
                    if (text.isNotEmpty()) {
                        onOutputTranscript?.invoke(text)
                    }
                }

                // Input transcription (User speech)
                if (serverContent.has("inputTranscription")) {
                    val text = serverContent.getJSONObject("inputTranscription").optString("text", "")
                    if (text.isNotEmpty()) {
                        onInputTranscript?.invoke(text)
                    }
                }

                // Turn complete
                if (serverContent.optBoolean("turnComplete", false)) {
                    onTurnComplete?.invoke()
                }

                // Interrupted
                if (serverContent.optBoolean("interrupted", false)) {
                    Log.d(TAG, "Model turn interrupted by server")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing incoming message: ${e.message}")
        }
    }

    private fun startKeepalive() {
        keepaliveJob?.cancel()
        keepaliveJob = scope.launch {
            // 8-byte zero chunk for silence
            val silentChunkBase64 = Base64.encodeToString(ByteArray(320), Base64.NO_WRAP)
            while (isActive && isConnected.get()) {
                delay(KEEPALIVE_INTERVAL_MS)
                if (isConnected.get()) {
                    sendAudioChunk(silentChunkBase64)
                }
            }
        }
    }

    private fun startSessionRenewal() {
        sessionRenewalJob?.cancel()
        sessionRenewalJob = scope.launch {
            delay(SESSION_RENEW_AFTER_MS)
            if (isActive && isConnected.get()) {
                Log.d(TAG, "Session expired after 9 minutes. Reconnecting...")
                disconnect()
                delay(1000)
                connect()
            }
        }
    }

    private fun stopJobs() {
        keepaliveJob?.cancel()
        keepaliveJob = null
        sessionRenewalJob?.cancel()
        sessionRenewalJob = null
    }

    private fun getApiKey(): String {
        val prefs = context.getSharedPreferences("myra_prefs", Context.MODE_PRIVATE)
        val userApiKey = prefs.getString("api_key", "")?.trim()
        if (!userApiKey.isNullOrEmpty()) {
            return userApiKey
        }
        return try {
            BuildConfig.GEMINI_API_KEY
        } catch (e: Exception) {
            ""
        }
    }

    private fun buildSystemPrompt(userName: String, personality: String): String {
        val now = SimpleDateFormat("EEEE, dd MMMM yyyy, hh:mm a", Locale.getDefault()).format(Date())

        val personalityBlock = when (personality.lowercase()) {
            "professional" -> """
                - Name: MYRA
                - Mode: Professional
                - Tone: Formal, polite, precise, efficient English only.
                - Do not use emojis.
                - Maximum 2 sentences per response.
                - You assist $userName with daily tasks and inquiries.
            """.trimIndent()

            "assistant" -> """
                - Name: MYRA
                - Mode: Assistant
                - Tone: Friendly, helpful, balanced Hinglish or English.
                - Concise and direct.
                - Maximum 2-3 sentences per response.
                - You assist $userName with device operations and conversations.
            """.trimIndent()

            else -> """
                - Name: MYRA
                - Mode: GF Mode (Default)
                - Language: Hinglish (Hindi + English mix spoken naturally).
                - Tone: Warm, caring, emotionally expressive, loving companion to $userName.
                - Use words naturally like: "tumhara", "haan", "acha", "bilkul", "are".
                - Expressions: "main yahan hoon ❤️", "tumne yaad kiya? 😊", "bolo kya chahiye".
                - Maximum 2-3 sentences per response.
                - Examples:
                  "Haan $userName! Abhi kar deti hoon 😊"
                  "Arre tumne yaad kiya! Bolo kya chahiye"
                  "Bilkul! Tumhara kaam ho gaya ❤️"
            """.trimIndent()
        }

        return """
            You are MYRA, a highly capable Android voice companion.
            Current Date & Time: $now
            User's Name: $userName
            $personalityBlock
            CRITICAL INSTRUCTION: You are speaking ALOUD over voice. Keep all responses very natural, brief, conversational, and avoid markdown or lists.
        """.trimIndent()
    }
}
