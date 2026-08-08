package com.jarvis.launcher

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.app.*
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

// ================= LANGUAGE MANAGER =================
object LanguageManager {

    data class LangProfile(
        val code: String,
        val displayName: String,
        val speechLocale: Locale,
        val wakeWords: List<String>,
        val greeting: String,
        val listeningReply: String,
        val timeQuestion: List<String>,
        val dateQuestion: List<String>,
        val openWord: String,
        val callWord: String,
        val timeAnswerPrefix: String,
        val dateAnswerPrefix: String,
        val appOpenedSuffix: String,
        val appNotFound: String,
        val noInternetNoMatch: String
    )

    val LANGUAGES = listOf(
        LangProfile("tk", "Turkmenche", Locale("tk", "TM"), listOf("jarwis", "jarvis"),
            "Salam! Men Jarwis, size name komek gerek?", "Dinleyarin",
            listOf("sagat naçe", "wagt naçe"), listOf("naçe sene", "bu gun naçe"),
            "aç", "jan et", "Hazir sagat", "Bu gun", "açylyar",
            "Bagyshlan, bu atly programmany tapmadym",
            "Bagyshlan, internet yok we bu soragy ozbashdak bilemok."),
        LangProfile("ru", "Russkiy", Locale("ru", "RU"), listOf("dzharvis", "jarvis"),
            "Privet! Ya Dzharvis, chem mogu pomoch?", "Slushayu",
            listOf("skolko vremeni", "kotoriy chas"), listOf("kakoe segodnya chislo", "kakaya segodnya data"),
            "otkroy", "pozvoni", "Seychas", "Segodnya", "otkryvaetsya",
            "Izvinite, ne nashel takoe prilozhenie",
            "Izvinite, net interneta, i ya ne znayu otvet sam."),
        LangProfile("en", "English", Locale("en", "US"), listOf("jarvis"),
            "Hello! I'm Jarvis, how can I help?", "Listening",
            listOf("what time is it", "what's the time"), listOf("what's the date", "what day is it"),
            "open", "call", "It's", "Today is", "opening",
            "Sorry, I couldn't find that app",
            "Sorry, there's no internet and I don't know that on my own."),
        LangProfile("uz", "O'zbekcha", Locale("uz", "UZ"), listOf("jarvis"),
            "Salom! Men Jarvis, sizga qanday yordam bera olaman?", "Tinglayapman",
            listOf("soat nechi", "vaqt nechi"), listOf("bugun nechi sana", "sana nechi"),
            "och", "qongiroq qil", "Hozir soat", "Bugun", "ochilyapti",
            "Kechirasiz, bunday ilovani topolmadim",
            "Kechirasiz, internet yo'q va men bu savolni bilmayman."),
        LangProfile("tr", "Turkce", Locale("tr", "TR"), listOf("jarvis"),
            "Merhaba! Ben Jarvis, size nasil yardimci olabilirim?", "Dinliyorum",
            listOf("saat kac", "saat ne"), listOf("bugun ayin kaci", "tarih ne"),
            "ac", "ara", "Su an saat", "Bugun", "aciliyor",
            "Uzgunum, bu uygulamayi bulamadim",
            "Uzgunum, internet yok ve bunu kendim bilmiyorum.")
    )

    private const val PREFS = "jarvis_prefs"
    private const val KEY_LANG = "selected_lang"

    fun getSelected(context: Context): LangProfile {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val code = prefs.getString(KEY_LANG, "tk")
        return LANGUAGES.firstOrNull { it.code == code } ?: LANGUAGES[0]
    }

    fun setSelected(context: Context, code: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_LANG, code).apply()
    }
}

// ================= VOICE ASSISTANT (TTS) =================
class VoiceAssistant(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var ready = false
    private var pendingText: String? = null
    private var pendingCallback: (() -> Unit)? = null

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ready = true
                applyLanguage()
                pendingText?.let { speak(it, pendingCallback) }
            }
        }
    }

    fun applyLanguage() {
        val lang = LanguageManager.getSelected(context)
        val result = tts?.setLanguage(lang.speechLocale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts?.setLanguage(Locale.US)
        }
    }

    fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (!ready) {
            pendingText = text
            pendingCallback = onDone
            return
        }
        val utteranceId = UUID.randomUUID().toString()
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { onDone?.invoke() }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { onDone?.invoke() }
        })
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
    }
}

// ================= ONLINE AI =================
class OnlineAI {

    private val API_KEY = "SIZIN_API_ACARYNYZY_BU_YERE_GOYUN"
    private val ENDPOINT = "https://api.anthropic.com/v1/messages"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())

    private val languageInstruction = mapOf(
        "tk" to "turkmen dilinde",
        "ru" to "na russkom yazyke",
        "en" to "in English",
        "uz" to "o'zbek tilida",
        "tr" to "Turkce olarak"
    )

    fun ask(question: String, langCode: String, onReply: (String) -> Unit) {
        if (API_KEY.startsWith("SIZI")) {
            onReply("API acary entek goyulmandyr.")
            return
        }

        val instruction = languageInstruction[langCode] ?: "turkmen dilinde"
        val body = JSONObject().apply {
            put("model", "claude-sonnet-4-6")
            put("max_tokens", 300)
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", "Gysgaca we dushnukli $instruction jogap ber: $question")
            }))
        }

        val request = Request.Builder()
            .url(ENDPOINT)
            .addHeader("x-api-key", API_KEY)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("content-type", "application/json")
            .post(RequestBody.create(MediaType.parse("application/json"), body.toString()))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post { onReply("Internete birikmekde sawlik yuze cykdy.") }
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val json = JSONObject(response.body?.string() ?: "{}")
                    val text = json.getJSONArray("content").getJSONObject(0).getString("text")
                    mainHandler.post { onReply(text) }
                } catch (e: Exception) {
                    mainHandler.post { onReply("Jogaby okap bolmady.") }
                }
            }
        })
    }
}

// ================= COMMAND PROCESSOR =================
class CommandProcessor(private val context: Context, private val voice: VoiceAssistant) {

    private val onlineAI = OnlineAI()

    fun process(rawText: String, onFinished: () -> Unit) {
        val lang = LanguageManager.getSelected(context)
        val text = rawText.lowercase(lang.speechLocale).trim()

        if (tryOfflineCommand(text, lang)) {
            onFinished()
            return
        }

        if (isOnline()) {
            onlineAI.ask(text, lang.code) { reply ->
                voice.speak(reply) { onFinished() }
            }
        } else {
            voice.speak(lang.noInternetNoMatch) { onFinished() }
        }
    }

    private fun tryOfflineCommand(text: String, lang: LanguageManager.LangProfile): Boolean {
        return when {
            lang.timeQuestion.any { text.contains(it) } -> {
                val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                voice.speak("${lang.timeAnswerPrefix} $time")
                true
            }
            lang.dateQuestion.any { text.contains(it) } -> {
                val date = SimpleDateFormat("d MMMM, EEEE", lang.speechLocale).format(Date())
                voice.speak("${lang.dateAnswerPrefix} $date")
                true
            }
            text.contains(lang.callWord) -> {
                val name = text.substringAfter(lang.callWord).trim()
                voice.speak("$name - ${lang.appOpenedSuffix}")
                true
            }
            lang.wakeWords.none { text == it } && text.contains(lang.openWord) -> {
                val appName = text.replace(lang.openWord, "").trim()
                if (appName.isNotEmpty()) openApp(appName, lang) else false
                true
            }
            lang.wakeWords.any { text.contains(it) } && text.split(" ").size <= 2 -> {
                voice.speak(lang.greeting)
                true
            }
            else -> false
        }
    }

    private fun openApp(appName: String, lang: LanguageManager.LangProfile) {
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(0)
        val match = apps.firstOrNull {
            pm.getApplicationLabel(it).toString().lowercase(lang.speechLocale).contains(appName)
        }
        if (match != null) {
            val launchIntent = pm.getLaunchIntentForPackage(match.packageName)
            launchIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            launchIntent?.let { context.startActivity(it) }
            voice.speak("$appName ${lang.appOpenedSuffix}")
        } else {
            voice.speak(lang.appNotFound)
        }
    }

    private fun isOnline(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}

// ================= LISTEN SERVICE =================
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
                putExtra(Recogniz
