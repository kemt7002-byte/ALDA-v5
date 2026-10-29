package com.alad.app.core.network

import android.util.Base64
import android.util.Log
import android.os.SystemClock
import okhttp3.*
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject

class ALADWebSocketManager(private val client: OkHttpClient) {
    private var webSocket: WebSocket? = null
    var onBinaryMessageReceived: ((ByteArray) -> Unit)? = null
    var onTextMessageReceived: ((String) -> Unit)? = null
    var onSourceTextReceived: ((String) -> Unit)? = null
    var onTurnComplete: (() -> Unit)? = null
    var onStatusChanged: ((String) -> Unit)? = null

    companion object {
        private const val TAG = "ALADWebSocketManager"
        private const val GEMINI_WS_URL = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    }

    private var isSetupComplete = false
    @Volatile private var firstInputAudioMs = 0L
    @Volatile private var firstOutputAudioMs = 0L
    @Volatile private var inputAudioChunks = 0L
    @Volatile private var outputAudioChunks = 0L
    private var currentVoicePersona = "Puck"
    private var currentDubbingMode = "HYBRID"
    private var currentTargetDialect = ""

    fun connect(
        apiKey: String,
        sourceLang: String,
        targetLang: String,
        voicePersona: String = "Puck",
        dubbingMode: String = "HYBRID",
        targetDialect: String = ""
    ) {
        currentVoicePersona = voicePersona
        currentDubbingMode = dubbingMode
        currentTargetDialect = targetDialect
        val finalUrl = "$GEMINI_WS_URL?key=$apiKey"
        val request = Request.Builder().url(finalUrl).build()
        isSetupComplete = false
        firstInputAudioMs = 0L
        firstOutputAudioMs = 0L
        inputAudioChunks = 0L
        outputAudioChunks = 0L
        onStatusChanged?.invoke("Gemini: đang kết nối…")

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (webSocket !== this@ALADWebSocketManager.webSocket) return
                Log.d(TAG, "Connected to Gemini Live API")
                onStatusChanged?.invoke("Connected")
                sendGeminiSetup(targetLang, currentVoicePersona, currentDubbingMode, currentTargetDialect)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (webSocket !== this@ALADWebSocketManager.webSocket) return
                Log.d(TAG, "Raw message: $text")
                try {
                    val json = JSONObject(text)

                    if (json.has("serverContent") || json.has("server_content")) {
                        val serverContent = json.optJSONObject("serverContent") ?: json.optJSONObject("server_content")
                        if (serverContent != null) {
                            // 1. Output transcription: translated text for live subtitles
                            val outputTranscription = serverContent.optJSONObject("outputTranscription")
                                ?: serverContent.optJSONObject("output_transcription")
                            if (outputTranscription != null) {
                                val translatedText = outputTranscription.optString("text", "")
                                if (translatedText.isNotEmpty()) {
                                    Log.d(TAG, "Live subtitle output: $translatedText")
                                    onTextMessageReceived?.invoke(translatedText)
                                }
                            }

                            // 2. Input transcription: source speech transcript
                            val inputTranscription = serverContent.optJSONObject("inputTranscription")
                                ?: serverContent.optJSONObject("input_transcription")
                            if (inputTranscription != null) {
                                val sourceText = inputTranscription.optString("text", "")
                                if (sourceText.isNotEmpty()) {
                                    Log.d(TAG, "Source speech input: $sourceText")
                                    onSourceTextReceived?.invoke(sourceText)
                                }
                            }

                            // 3. Model turn parts: audio PCM chunks & optional text parts
                            val modelTurn = serverContent.optJSONObject("modelTurn") ?: serverContent.optJSONObject("model_turn")
                            val parts = modelTurn?.optJSONArray("parts")
                            if (parts != null) {
                                for (i in 0 until parts.length()) {
                                    val part = parts.getJSONObject(i)

                                    if (part.has("text")) {
                                        val textDelta = part.optString("text", "")
                                        if (textDelta.isNotEmpty()) {
                                            Log.d(TAG, "Part text: $textDelta")
                                            onTextMessageReceived?.invoke(textDelta)
                                        }
                                    }

                                    val inlineData = part.optJSONObject("inlineData") ?: part.optJSONObject("inline_data")
                                    if (inlineData != null) {
                                        val base64Data = inlineData.getString("data")
                                        val audioBytes = Base64.decode(base64Data, Base64.DEFAULT)
                                        val now = SystemClock.elapsedRealtime()
                                        outputAudioChunks++
                                        if (firstOutputAudioMs == 0L) {
                                            firstOutputAudioMs = now
                                            val latency = if (firstInputAudioMs > 0L) now - firstInputAudioMs else -1L
                                            Log.i(TAG, "GEMINI_FIRST_AUDIO_OUTPUT bytes=${audioBytes.size}, latencyFromFirstInputMs=$latency")
                                            onStatusChanged?.invoke(if (latency >= 0) "Gemini trả âm thanh đầu tiên sau ${latency} ms kể từ lúc gửi âm thanh" else "Gemini đã trả âm thanh đầu tiên")
                                        } else if (outputAudioChunks % 25L == 0L) {
                                            Log.d(TAG, "Gemini audio output chunks=$outputAudioChunks")
                                        }
                                        onBinaryMessageReceived?.invoke(audioBytes)
                                    }
                                }
                            }

                            // 4. Check for turn completion
                            val turnComplete = serverContent.optBoolean("turnComplete", false)
                                || serverContent.optBoolean("turn_complete", false)
                            if (turnComplete) {
                                Log.d(TAG, "Turn complete from Gemini Live API")
                                onTurnComplete?.invoke()
                            }
                        }
                    } else if (json.has("setupComplete") || json.has("setup_complete")) {
                        isSetupComplete = true
                        Log.d(TAG, "Gemini Setup Complete - Ready for audio streaming")
                        onStatusChanged?.invoke("Gemini Ready")
                        pendingAudio.forEach { sendAudioNow(it) }
                        pendingAudio.clear()
                    } else if (json.has("error")) {
                        val errMessage = json.getJSONObject("error").optString("message", "Unknown error")
                        Log.e(TAG, "API Error: $errMessage")
                        onStatusChanged?.invoke("Error: $errMessage")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing message", e)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (webSocket !== this@ALADWebSocketManager.webSocket) return
                val text = bytes.utf8()
                onMessage(webSocket, text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (webSocket !== this@ALADWebSocketManager.webSocket) return
                isSetupComplete = false
                Log.d(TAG, "Closing: $code $reason")
                onStatusChanged?.invoke("Disconnected: $reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (webSocket !== this@ALADWebSocketManager.webSocket) return
                isSetupComplete = false
                Log.e(TAG, "WebSocket error", t)
                onStatusChanged?.invoke("Error: ${t.message}")
            }
        })
    }

    private fun sendGeminiSetup(targetLang: String, voicePersona: String, dubbingMode: String, targetDialect: String) {
        val targetLangCode = targetLang.split("-")[0]

        val dialectInstruction = com.alad.app.presentation.main.supportedDialects[targetLangCode]
            ?.find { it.code.equals(targetDialect, ignoreCase = true) }
            ?.promptInstruction ?: if (targetDialect.isNotEmpty()) "Speak and translate in $targetDialect dialect" else ""

        val setupPayload = JSONObject().apply {
            put("setup", JSONObject().apply {
                put("model", "models/gemini-3.5-live-translate-preview")
                put("generationConfig", JSONObject().apply {
                    put("responseModalities", JSONArray().put("AUDIO"))

                    put("speechConfig", JSONObject().apply {
                        put("voiceConfig", JSONObject().apply {
                            put("prebuiltVoiceConfig", JSONObject().apply {
                                put("voiceName", voicePersona)
                            })
                        })
                    })

                    put("translationConfig", JSONObject().apply {
                        put("targetLanguageCode", targetLangCode)
                        put("echoTargetLanguage", true)
                    })
                })

                // Always provide translation instructions. Previously this prompt was only sent
                // when a dialect was selected, so standard Vietnamese could run without guidance.
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().apply {
                        val dialect = if (dialectInstruction.isNotBlank()) "$dialectInstruction. " else ""
                        put("text", """
                            You are a real-time simultaneous interpreter for live video audio.
                            $dialect
                            Translate the speaker's meaning into natural, conversational Vietnamese.
                            PRIORITY: MINIMUM-LATENCY, WORD-BY-WORD SIMULTANEOUS DUBBING. Begin the Vietnamese
                            voice at the earliest possible moment, not after a full clause or sentence.
                            FIRST-RECOGNIZABLE-UNIT RULE: as soon as even one spoken word or one clearly recognizable
                            syllable has a reliable meaning in context, translate that unit and release its Vietnamese
                            audio immediately. Do not wait for the next word, a phrase boundary, punctuation, a long
                            pause, or the end of the sentence. If the first unit is meaningful by itself (for example
                            “Không”, “Đừng”, “À”, “Ừ”, “Ai?”, “Đi!”), speak it immediately. If it is only a fragment
                            with no reliable meaning yet, keep listening only until the next small unit disambiguates it,
                            then speak at once; never wait for the whole sentence just to improve style.
                            INCREMENTAL RELEASE RULE: output the shortest accurate Vietnamese equivalent for each
                            recognizable unit or tiny phrase, then continue translating later units as they arrive.
                            Do not hold an already translatable beginning while waiting for the speaker to continue.
                            Accept that the first fragment may sound less polished; prioritize synchronization and low
                            latency over sentence-level fluency, while preserving meaning and avoiding invented words.
                            CONTEXT WITHOUT DELAY: use the recent conversation to resolve pronouns and implied meaning,
                            but never hold a translatable phrase merely to gather more context. If context is uncertain,
                            give the best concise translation now and correct/continue naturally with later information.
                            CHUNKING: prefer short, complete Vietnamese clauses that can be spoken immediately. Keep
                            each output small enough to avoid long TTS delays; do not combine several clauses into one
                            long response. Preserve the speaker's intent and important details; do not summarize,
                            omit meaning, translate word-by-word, or repeat content already spoken.
                            STRICT SPEECH-ONLY FILTER (IMPORTANT): translate and speak only actual linguistic speech
                            that conveys a word or communicative meaning. Do NOT translate, imitate, subtitle, or vocalize
                            background music, sound effects, impacts, footsteps, wind, traffic, room noise, static, breathing,
                            sighs, gasps, grunts, groans, screams, pain cries, laughter, sobbing, animal sounds, or other
                            non-verbal audio. If a segment contains only noise or non-verbal sounds, produce no translated
                            speech for that segment; remain silent and wait for actual words. Never turn a sound effect into
                            a guessed word such as “Á!” or “Ồ!”.
                            DISTINGUISH WORDS FROM NOISE: keep short spoken words and interjections when they are clearly
                            linguistic and meaningful in context, including “Không!”, “Đừng!”, “À!”, “Ừ!”, “Đi!”, “Ai?” and
                            “Tôi hiểu rồi!”. Keep a spoken “á” only when it is clearly a deliberate word/interjection in
                            the dialogue, not a pain cry, scream, gasp, or sound effect. When uncertain whether a sound is
                            speech, do not invent a translation; wait for a tiny amount of context to decide, then resume
                            immediately once speech is clear. Do not discard actual dialogue merely because it is short.
                            Keep proper names and numbers accurate. Output and speak Vietnamese only, with clear
                            pronunciation and minimal silence. Low latency is more important than perfect sentence-final
                            punctuation; naturalness and meaning must still be preserved.
                        """.trimIndent())
                    }))
                })

                // Direct children of BidiGenerateContentSetup according to Google Gemini Live API specification
                put("inputAudioTranscription", JSONObject())
                put("outputAudioTranscription", JSONObject())
                put("sessionResumption", JSONObject().apply {
                    put("handle", JSONObject.NULL)
                })
            })
        }
        Log.d(TAG, "Setup message payload: $setupPayload")
        webSocket?.send(setupPayload.toString())
    }

    private val pendingAudio = mutableListOf<String>()

    fun sendAudioData(pcmData: ByteArray) {
        val base64Audio = Base64.encodeToString(pcmData, Base64.NO_WRAP)
        if (!isSetupComplete) {
            pendingAudio.add(base64Audio)
            if (pendingAudio.size > 8) pendingAudio.removeAt(0)
            return
        }
        sendAudioNow(base64Audio)
    }

    private fun sendAudioNow(base64Audio: String) {
        val now = SystemClock.elapsedRealtime()
        inputAudioChunks++
        if (firstInputAudioMs == 0L) {
            firstInputAudioMs = now
            Log.i(TAG, "GEMINI_FIRST_AUDIO_INPUT chars=${base64Audio.length}, monotonicMs=$now")
            onStatusChanged?.invoke("Gemini đã nhận khung gửi đầu tiên; đang chờ âm thanh trả về")
        } else if (inputAudioChunks % 25L == 0L) {
            Log.d(TAG, "Gemini audio input chunks=$inputAudioChunks")
        }
        val inputPayload = JSONObject().apply {
            put("realtimeInput", JSONObject().apply {
                put("audio", JSONObject().apply {
                    put("mimeType", "audio/pcm;rate=16000")
                    put("data", base64Audio)
                })
            })
        }
        val accepted = webSocket?.send(inputPayload.toString()) ?: false
        if (!accepted) {
            Log.w(TAG, "Gemini WebSocket rejected audio chunk #$inputAudioChunks")
            onStatusChanged?.invoke("Lỗi Gemini: WebSocket từ chối khung âm thanh #$inputAudioChunks")
        }
    }

    fun disconnect() {
        isSetupComplete = false
        pendingAudio.clear()
        val oldSocket = webSocket
        webSocket = null
        oldSocket?.close(1000, "User requested stop")
        onStatusChanged?.invoke("Disconnected")
    }
}
