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

/** Plain-language reasons for speech-recognition errors. */
object VoiceErrors {
    /** Null for errors that need no message (the user stopped it, or simply said nothing). */
    fun message(code: Int): String? = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that. Tap the mic and try again."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Voice input needs a connection right now."
        SpeechRecognizer.ERROR_AUDIO -> "The microphone couldn't record."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Aura needs microphone permission for voice input."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Voice input is busy. Try again in a moment."
        SpeechRecognizer.ERROR_CLIENT -> null
        else -> "Voice input stopped (error $code)."
    }
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

    /** Returns false if the phone's voice isn't ready (so the caller can say so). */
    fun speak(text: String, pitch: Float = 1.0f, speed: Float = 1.0f): Boolean {
        if (!isTtsReady) {
            if (_voiceState.value == VoiceState.PROCESSING) _voiceState.value = VoiceState.IDLE
            return false
        }
        stopListening()
        tts?.setPitch(pitch)
        tts?.setSpeechRate(speed)
        val utteranceId = "aura_tts_${System.currentTimeMillis()}"
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        return true
    }

    private var player: android.media.MediaPlayer? = null
    /** The downloaded reply being played; deleted as soon as playback ends (audit 02, F-05). */
    private var playingFile: java.io.File? = null

    private fun releasePlayer() {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        playingFile?.delete()
        playingFile = null
    }

    /** Plays a downloaded reply (ElevenLabs voice). Returns false if the file can't be played. */
    fun playAudio(file: java.io.File): Boolean {
        stopSpeaking()
        stopListening()
        playingFile = file
        return try {
            player = android.media.MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener { if (player === it) releasePlayer() else it.release(); _voiceState.value = VoiceState.IDLE }
                setOnErrorListener { mp, _, _ -> if (player === mp) releasePlayer() else mp.release(); _voiceState.value = VoiceState.IDLE; true }
                prepare()
                start()
            }
            _voiceState.value = VoiceState.SPEAKING
            true
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't play reply audio", e)
            releasePlayer()
            _voiceState.value = VoiceState.IDLE
            false
        }
    }

    fun stopSpeaking() {
        releasePlayer()
        if (tts?.isSpeaking == true) {
            tts?.stop()
        }
        if (_voiceState.value == VoiceState.SPEAKING) {
            _voiceState.value = VoiceState.IDLE
        }
    }

    /** Shown while an ElevenLabs reply is being fetched; tapping stop cancels it. */
    fun markPreparing() {
        _voiceState.value = VoiceState.PROCESSING
    }

    /** Stops listening and speaking, whatever is happening. */
    fun stopAll() {
        stopListening()
        stopSpeaking()
        _voiceState.value = VoiceState.IDLE
    }

    fun startListening(
        onResult: (String) -> Unit,
        onPartial: (String) -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        stopSpeaking()
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w(TAG, "Speech recognition not available on this device")
            onError("Voice input isn't available on this phone.")
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
                    VoiceErrors.message(error)?.let(onError)
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
                        onPartial(text)
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start SpeechRecognizer", e)
            _voiceState.value = VoiceState.IDLE
            onError("Voice input couldn't start.")
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
        releasePlayer()
        tts?.stop()
        tts?.shutdown()
        speechRecognizer?.destroy()
    }
}
