package com.example.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

private const val TAG = "VoiceManager"

enum class VoiceState {
    IDLE,
    LISTENING,
    PROCESSING,
    SPEAKING
}

class VoiceManager(private val context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    private val _voiceState = MutableStateFlow(VoiceState.IDLE)
    val voiceState: StateFlow<VoiceState> = _voiceState.asStateFlow()

    private val _recognizedText = MutableStateFlow<String?>(null)
    val recognizedText: StateFlow<String?> = _recognizedText.asStateFlow()

    private var speechRecognizer: SpeechRecognizer? = null

    init {
        try {
            tts = TextToSpeech(context.applicationContext, this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize TTS", e)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "TTS Language not supported or missing data")
            } else {
                isTtsReady = true
                setupUtteranceListener()
            }
        } else {
            Log.e(TAG, "TTS Initialization failed")
        }
    }

    private fun setupUtteranceListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                _voiceState.value = VoiceState.SPEAKING
            }

            override fun onDone(utteranceId: String?) {
                _voiceState.value = VoiceState.IDLE
            }

            override fun onError(utteranceId: String?) {
                _voiceState.value = VoiceState.IDLE
            }
        })
    }

    fun speak(text: String, pitch: Float = 1.0f, speed: Float = 1.0f) {
        if (!isTtsReady) return
        stopListening()
        tts?.setPitch(pitch)
        tts?.setSpeechRate(speed)
        val utteranceId = "aura_tts_${System.currentTimeMillis()}"
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    fun stopSpeaking() {
        if (tts?.isSpeaking == true) {
            tts?.stop()
        }
        if (_voiceState.value == VoiceState.SPEAKING) {
            _voiceState.value = VoiceState.IDLE
        }
    }

    fun startListening(onResult: (String) -> Unit) {
        stopSpeaking()
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w(TAG, "Speech recognition not available on this device")
            return
        }

        try {
            if (speechRecognizer == null) {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }

            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    _voiceState.value = VoiceState.LISTENING
                }

                override fun onBeginningOfSpeech() {
                    _voiceState.value = VoiceState.LISTENING
                }

                override fun onRmsChanged(rmsdB: Float) {}

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    _voiceState.value = VoiceState.PROCESSING
                }

                override fun onError(error: Int) {
                    Log.e(TAG, "SpeechRecognizer error: $error")
                    _voiceState.value = VoiceState.IDLE
                }

                override fun onResults(results: Bundle?) {
                    _voiceState.value = VoiceState.IDLE
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val spoken = matches?.firstOrNull()
                    if (!spoken.isNullOrBlank()) {
                        _recognizedText.value = spoken
                        onResult(spoken)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val partialMatches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = partialMatches?.firstOrNull()
                    if (!text.isNullOrBlank()) {
                        _recognizedText.value = text
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start SpeechRecognizer", e)
            _voiceState.value = VoiceState.IDLE
        }
    }

    fun stopListening() {
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping recognizer", e)
        }
        if (_voiceState.value == VoiceState.LISTENING || _voiceState.value == VoiceState.PROCESSING) {
            _voiceState.value = VoiceState.IDLE
        }
    }

    fun cleanup() {
        tts?.stop()
        tts?.shutdown()
        speechRecognizer?.destroy()
    }
}
