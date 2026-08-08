package com.jarvis.launcher

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat

class ListenService : Service(), RecognitionListener {

    companion object {
        const val ACTION_MANUAL_TRIGGER = "com.jarvis.launcher.MANUAL_TRIGGER"
        const val CHANNEL_ID = "jarvis_listen_channel"
        const val NOTIF_ID = 1
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private lateinit var voiceAssistant: VoiceAssistant
    private lateinit var commandProcessor: CommandProcessor
    private val handler = Handler(Looper.getMainLooper())
    private var listeningForWakeWord = true

    private val lang get() = LanguageManager.getSelected(this)

    override fun onCreate() {
        super.onCreate()
        voiceAssistant = VoiceAssistant(this)
        commandProcessor = CommandProcessor(this, voiceAssistant)
        startForeground(NOTIF_ID, buildNotification("Jarwis..."))
        initRecognizer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_MANUAL_TRIGGER) {
            activateAssistant()
        }
        return START_STICKY
    }

    private fun initRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(this@ListenService)
        }
        startWakeWordListening()
    }

    private fun startWakeWordListening() {
        listeningForWakeWord = true
        val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang.speechLocale.toString())
        }
        speechRecognizer?.startListening(recognizerIntent)
    }

    private fun activateAssistant() {
        listeningForWakeWord = false
        val current = lang
        updateNotification("${current.listeningReply}...")
        voiceAssistant.speak(current.listeningReply) {
            val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, current.speechLocale.toString())
            }
            speechRecognizer?.startListening(recognizerIntent)
        }
    }

    override fun onReadyForSpeech(params: android.os.Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}

    override fun onError(error: Int) {
        restartListening()
    }

    override fun onResults(results: android.os.Bundle?) {
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val text = matches?.firstOrNull()?.lowercase() ?: ""

        if (listeningForWakeWord) {
            if (lang.wakeWords.any { text.contains(it) }) {
                activateAssistant()
            } else {
                restartListening()
            }
        } else {
            updateNotification("...")
            commandProcessor.process(text) {
                updateNotification("Jarwis...")
                restartListening()
            }
        }
    }

    override fun onPartialResults(partialResults: android.os.Bundle?) {}
    override fun onEvent(eventType: Int, params: android.os.Bundle?) {}

    private fun restartListening() {
        handler.postDelayed({
            try { startWakeWordListening() } catch (e: Exception) { }
        }, 400)
    }

    private fun buildNotification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Jarwis listen service", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Jarwis")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIF_ID, buildNotification(text))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer?.destroy()
        voiceAssistant.shutdown()
    }
}
