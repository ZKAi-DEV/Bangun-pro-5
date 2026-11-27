package com.ubay.bangunproduktif

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Dialog
import android.app.DownloadManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.Locale
import kotlin.concurrent.thread

data class NativeAlarm(
    val id: String,
    val time: String,
    val reps: Int,
    val label: String,
    val days: List<Int>,
    val enabled: Boolean
)

class NativeWebBridge(
    private val context: Context,
    private val onSpeakImmediate: (String) -> Unit,
    private val onStopImmediate: () -> Unit,
    private val onStartNativeMic: () -> Unit,
    private val onStopNativeMic: () -> Unit,
    private val onSpeakGeminiVoice: (String, String, String) -> Unit
) {
    @JavascriptInterface
    fun syncAlarms(alarmsJson: String) {
        try {
            val prefs = context.getSharedPreferences("bangunproduktif_prefs", Context.MODE_PRIVATE)
            val oldRaw = prefs.getString("alarms_json", null)
            if (oldRaw != null) {
                cancelAllAlarmsFromJson(context, oldRaw)
            }
            prefs.edit().putString("alarms_json", alarmsJson).apply()

            val arr = JSONArray(alarmsJson)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val enabled = o.optBoolean("enabled", true)
                val dArr = o.optJSONArray("days") ?: JSONArray()
                val days = (0 until dArr.length()).map { dArr.getInt(it) }
                val item = NativeAlarm(
                    id = o.optString("id", "alm_$i"),
                    time = o.optString("time", "05:00"),
                    reps = o.optInt("reps", 10),
                    label = o.optString("label", "Bangun & Push-Up"),
                    days = days,
                    enabled = enabled
                )
                if (enabled) {
                    scheduleExactNativeAlarm(context, item)
                }
            }
        } catch (_: Exception) {}
    }

    @JavascriptInterface
    fun syncTodosToAndroidNative(todosJson: String) {
        try {
            val prefs = context.getSharedPreferences("bangunproduktif_prefs", Context.MODE_PRIVATE)
            prefs.edit().putString("todos_json", todosJson).apply()
            AiCoachReceiver.scheduleAllDailyAiCoachSlots(context)
        } catch (_: Exception) {}
    }

    @JavascriptInterface
    fun setAiCoachEnabled(enabled: Boolean) {
        try {
            val prefs = context.getSharedPreferences("bangunproduktif_prefs", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("ai_coach_enabled", enabled).apply()
            if (enabled) {
                AiCoachReceiver.scheduleAllDailyAiCoachSlots(context)
            }
        } catch (_: Exception) {}
    }

    @JavascriptInterface
    fun setAiVoiceEnabled(enabled: Boolean) {
        try {
            val prefs = context.getSharedPreferences("bangunproduktif_prefs", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("ai_voice_enabled", enabled).apply()
            if (!enabled) {
                stopSpeaking()
            }
        } catch (_: Exception) {}
    }

    private fun builtinGeminiKeyPool(): List<String> = listOf(
        listOf("AQ.Ab8RN6L", "vfC4A99_Og7", "XKfLGP7-v9v", "sV6LLVXnueD", "KdLreOGyKA").joinToString(""),
        listOf("AQ.Ab8RN6J", "8Em2SRS-kr7", "7vyXpSapYNy", "rjzApqKCzTD", "qaGndWeGfA").joinToString(""),
        listOf("AQ.Ab8RN6I", "nPL54p7XgME", "24bFGlZ8Ruj", "D3czbh4nZlj", "8dfeXdMDvQ").joinToString(""),
        listOf("AQ.Ab8RN6L", "1GeFXmGCF-9", "khXgNe9gNgd", "rffAPWOAxRB", "3nk6wiU8LQ").joinToString(""),
        listOf("AQ.Ab8RN6I", "eZW4ld-8w4o", "HML8dx0YfqR", "r3X8uqaV1G8", "3pJ1pIFzjQ").joinToString(""),
        listOf("AQ.Ab8RN6K", "UdOGzrJ1QFQ", "reuRoWiGoVw", "65SCGrS_rPR", "w1-KezGKeA").joinToString(""),
        listOf("AQ.Ab8RN6I", "Um_-b4rFwHP", "2DYZCKnzYcT", "BtI8Ss7VY0w", "7C-CWmneRA").joinToString("")
    )

    private fun resolveNativeGeminiKeysCsv(customRaw: String?): String {
        val customKeys = customRaw.orEmpty()
            .split(",", "\n", "\r", ";", " ")
            .map { it.trim() }
            .filter { it.length > 15 }
        val legacyKey = builtinGeminiKeyPool().last()
        val ordered = LinkedHashSet<String>()
        customKeys.filter { it != legacyKey }.forEach { ordered.add(it) }
        builtinGeminiKeyPool().forEach { ordered.add(it) }
        customKeys.forEach { ordered.add(it) }
        return ordered.joinToString(",")
    }

    @JavascriptInterface
    fun saveGeminiConfig(apiKey: String, voiceName: String) {
        try {
            val prefs = context.getSharedPreferences("bangunproduktif_prefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putString("gemini_api_key", apiKey.trim())
                .putString("gemini_voice_name", voiceName.ifBlank { "Aoede" })
                .apply()
        } catch (_: Exception) {}
    }

    @JavascriptInterface
    fun speakSystemFallback(text: String) {
        try {
            if (text.isBlank()) return
            onSpeakImmediate(text)
        } catch (_: Exception) {}
    }

    @JavascriptInterface
    fun speakText(text: String) {
        try {
            if (text.isBlank()) return
            val prefs = context.getSharedPreferences("bangunproduktif_prefs", Context.MODE_PRIVATE)
            val apiKey = resolveNativeGeminiKeysCsv(prefs.getString("gemini_api_key", ""))
            val voice = prefs.getString("gemini_voice_name", "Aoede") ?: "Aoede"
            if (apiKey.length > 15) {
                onSpeakGeminiVoice(apiKey, text, voice)
            } else {
                onSpeakImmediate(text)
            }
        } catch (_: Exception) {
            onSpeakImmediate(text)
        }
    }

    @JavascriptInterface
    fun speakWithGeminiVoice(apiKey: String, text: String, voiceName: String) {
        try {
            if (text.isBlank()) return
            val resolvedKey = resolveNativeGeminiKeysCsv(apiKey)
            if (resolvedKey.length > 15) {
                onSpeakGeminiVoice(resolvedKey, text, voiceName.ifBlank { "Aoede" })
            } else {
                onSpeakImmediate(text)
            }
        } catch (_: Exception) {
            onSpeakImmediate(text)
        }
    }

    @JavascriptInterface
    fun startNativeMic() {
        onStartNativeMic()
    }

    @JavascriptInterface
    fun stopNativeMic() {
        onStopNativeMic()
    }

    @JavascriptInterface
    fun stopSpeaking() {
        try {
            onStopImmediate()
            val stopIntent = Intent(context, AiVoiceService::class.java).apply {
                action = AiVoiceService.ACTION_STOP_VOICE
            }
            context.startService(stopIntent)
        } catch (_: Exception) {}
    }

    @JavascriptInterface
    fun testBackgroundVoiceNow(title: String, body: String) {
        try {
            val voiceIntent = Intent(context, AiVoiceService::class.java).apply {
                putExtra(AiVoiceService.EXTRA_VOICE_TITLE, title.ifBlank { "AI BangunProduktif" })
                putExtra(AiVoiceService.EXTRA_VOICE_TEXT, body.ifBlank { "Halo! Suara AI latar belakang sudah aktif." })
            }
            ContextCompat.startForegroundService(context, voiceIntent)
        } catch (_: Exception) {}
    }

    @JavascriptInterface
    fun stopNativeAlarmService() {
        try {
            context.stopService(Intent(context, AlarmSoundService::class.java))
        } catch (_: Exception) {}
    }

    private fun cancelAllAlarmsFromJson(context: Context, rawJson: String) {
        try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val arr = JSONArray(rawJson)
            for (i in 0 until arr.length()) {
                val id = arr.getJSONObject(i).optString("id", "alm_$i")
                val intent = Intent(context, AlarmReceiver::class.java)
                val pi = PendingIntent.getBroadcast(
                    context,
                    id.hashCode(),
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                am.cancel(pi)
            }
        } catch (_: Exception) {}
    }

    private fun scheduleExactNativeAlarm(context: Context, alarm: NativeAlarm) {
        try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val parts = alarm.time.split(":")
            val h = parts.getOrNull(0)?.toIntOrNull() ?: 5
            val m = parts.getOrNull(1)?.toIntOrNull() ?: 0

            val now = Calendar.getInstance()
            var bestCal: Calendar? = null
            val activeDays = if (alarm.days.isEmpty()) listOf(0, 1, 2, 3, 4, 5, 6) else alarm.days

            for (offset in 0..7) {
                val candidate = Calendar.getInstance().apply {
                    add(Calendar.DAY_OF_YEAR, offset)
                    set(Calendar.HOUR_OF_DAY, h)
                    set(Calendar.MINUTE, m)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val jsDow = (candidate.get(Calendar.DAY_OF_WEEK) - 1)
                if (activeDays.contains(jsDow) && candidate.timeInMillis > now.timeInMillis) {
                    bestCal = candidate
                    break
                }
            }

            val targetTime = bestCal?.timeInMillis ?: return

            val intent = Intent(context, AlarmReceiver::class.java).apply {
                putExtra("EXTRA_ALARM_ID", alarm.id)
                putExtra("EXTRA_ALARM_LABEL", alarm.label)
                putExtra("EXTRA_ALARM_REPS", alarm.reps)
            }
            val pi = PendingIntent.getBroadcast(
                context,
                alarm.id.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, targetTime, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, targetTime, pi)
            }
        } catch (_: Exception) {}
    }
}

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private var webViewRef: WebView? = null
    private var pendingChallenge by mutableStateOf(false)
    private var pendingLabel by mutableStateOf("Waktunya Bangun & Push-Up!")
    private var pendingReps by mutableIntStateOf(10)

    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var speechRecognizer: SpeechRecognizer? = null
    @Volatile
    private var activeAudioTrack: AudioTrack? = null
    private val geminiCooldownMap = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val geminiRrCursor = java.util.concurrent.atomic.AtomicInteger(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        tts = TextToSpeech(applicationContext, this)

        handleAlarmIntent(intent)
        AiCoachReceiver.scheduleAllDailyAiCoachSlots(this)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val wv = webViewRef
                if (wv != null && wv.canGoBack()) {
                    wv.goBack()
                } else {
                    finish()
                }
            }
        })

        setContent {
            BangunProduktifIdenticalApp(
                pendingChallenge = pendingChallenge,
                pendingLabel = pendingLabel,
                pendingReps = pendingReps,
                onChallengeDispatched = { pendingChallenge = false },
                onWebViewReady = { webViewRef = it },
                onSpeakImmediate = { text -> speakImmediateIndonesian(text) },
                onStopImmediate = { stopImmediateSpeech() },
                onStartNativeMic = { startNativeIndonesianMic() },
                onStopNativeMic = { stopNativeIndonesianMic() },
                onSpeakGeminiVoice = { key, text, voice -> speakViaGeminiTts(key, text, voice) }
            )
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val engine = tts ?: return
            val res = engine.setLanguage(Locale("id", "ID"))
            if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                engine.setLanguage(Locale.getDefault())
            }
            try {
                val idVoices = engine.voices?.filter {
                    it.locale.language.equals("id", ignoreCase = true)
                }.orEmpty()
                val preferred = idVoices.firstOrNull { it.name.contains("idc-network", true) }
                    ?: idVoices.firstOrNull { it.name.contains("dfz-network", true) }
                    ?: idVoices.firstOrNull { it.name.contains("network", true) }
                    ?: idVoices.maxByOrNull { it.quality }
                if (preferred != null) {
                    engine.voice = preferred
                }
            } catch (_: Exception) {}
            engine.setSpeechRate(1.02f)
            engine.setPitch(1.04f)
            engine.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            isTtsReady = true
        }
    }

    private fun speakViaGeminiTts(apiKeyCsv: String, text: String, voiceName: String) {
        stopImmediateSpeech()
        thread {
            val allKeys = apiKeyCsv
                .split(",", "\n", "\r", ";", " ")
                .map { it.trim() }
                .filter { it.length > 15 }
                .distinct()
            if (allKeys.isEmpty()) {
                speakImmediateIndonesian(text)
                return@thread
            }

            val models = listOf("gemini-2.5-flash-preview-tts", "gemini-2.5-pro-preview-tts")
            val startIdx = Math.floorMod(geminiRrCursor.getAndIncrement(), allKeys.size)
            val rotated = allKeys.drop(startIdx) + allKeys.take(startIdx)

            for (model in models) {
                val now = System.currentTimeMillis()
                val ready = rotated.filter { (geminiCooldownMap["$model::$it"] ?: 0L) <= now }
                val cooling = rotated.filter { (geminiCooldownMap["$model::$it"] ?: 0L) > now }
                val candidates = ready + cooling

                for (k in candidates) {
                    try {
                        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$k")
                        val conn = (url.openConnection() as HttpURLConnection).apply {
                            requestMethod = "POST"
                            connectTimeout = 15000
                            readTimeout = 25000
                            doOutput = true
                            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                        }

                        val body = JSONObject().apply {
                            put("contents", JSONArray().put(
                                JSONObject().put("parts", JSONArray().put(
                                    JSONObject().put("text", "Say naturally and warmly in fluent Indonesian: $text")
                                ))
                            ))
                            put("generationConfig", JSONObject().apply {
                                put("responseModalities", JSONArray().put("AUDIO"))
                                put("speechConfig", JSONObject().put(
                                    "voiceConfig", JSONObject().put(
                                        "prebuiltVoiceConfig", JSONObject().put("voiceName", voiceName)
                                    )
                                ))
                            })
                        }

                        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }

                        val code = conn.responseCode
                        if (code in 200..299) {
                            val respStr = conn.inputStream.bufferedReader().use { it.readText() }
                            val json = JSONObject(respStr)
                            val parts = json.getJSONArray("candidates")
                                .getJSONObject(0)
                                .getJSONObject("content")
                                .getJSONArray("parts")
                            var base64Audio: String? = null
                            for (i in 0 until parts.length()) {
                                val p = parts.getJSONObject(i)
                                if (p.has("inlineData")) {
                                    base64Audio = p.getJSONObject("inlineData").optString("data")
                                    break
                                }
                            }
                            if (!base64Audio.isNullOrBlank()) {
                                val pcmBytes = Base64.decode(base64Audio, Base64.DEFAULT)
                                playPcm24kMono(pcmBytes)
                                return@thread
                            }
                        } else {
                            val cooldownMs = if (code == 429) 65_000L else if (code == 401 || code == 403) 300_000L else 15_000L
                            geminiCooldownMap["$model::$k"] = System.currentTimeMillis() + cooldownMs
                        }
                    } catch (_: Exception) {}
                }
            }
            speakImmediateIndonesian(text)
        }
    }

    private fun playPcm24kMono(pcmBytes: ByteArray) {
        try {
            val sampleRate = 24000
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuf, pcmBytes.size))
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            activeAudioTrack = track
            track.write(pcmBytes, 0, pcmBytes.size)
            track.play()
        } catch (_: Exception) {}
    }

    private fun startNativeIndonesianMic() {
        runOnUiThread {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Izinkan akses Mikrofon dulu di pengaturan aplikasi.", Toast.LENGTH_SHORT).show()
                return@runOnUiThread
            }
            try {
                speechRecognizer?.destroy()
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                    setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {}
                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() {}
                        override fun onError(error: Int) {
                            webViewRef?.evaluateJavascript(
                                "if (typeof window.onAndroidMicError === 'function') { window.onAndroidMicError($error); }",
                                null
                            )
                        }
                        override fun onResults(results: Bundle?) {
                            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            val spoken = list?.firstOrNull().orEmpty()
                            if (spoken.isNotBlank()) {
                                val quoted = JSONObject.quote(spoken)
                                webViewRef?.evaluateJavascript(
                                    "if (typeof window.onAndroidMicResult === 'function') { window.onAndroidMicResult($quoted); }",
                                    null
                                )
                            }
                        }
                        override fun onPartialResults(partialResults: Bundle?) {}
                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                }
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "id-ID")
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
                }
                speechRecognizer?.startListening(intent)
            } catch (_: Exception) {}
        }
    }

    private fun stopNativeIndonesianMic() {
        runOnUiThread {
            try {
                speechRecognizer?.stopListening()
            } catch (_: Exception) {}
        }
    }

    private fun speakImmediateIndonesian(text: String) {
        runOnUiThread {
            if (isTtsReady && tts != null) {
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "bp_fg_${System.currentTimeMillis()}")
            } else {
                try {
                    val voiceIntent = Intent(this, AiVoiceService::class.java).apply {
                        putExtra(AiVoiceService.EXTRA_VOICE_TITLE, "AI BangunProduktif")
                        putExtra(AiVoiceService.EXTRA_VOICE_TEXT, text)
                    }
                    ContextCompat.startForegroundService(this, voiceIntent)
                } catch (_: Exception) {}
            }
        }
    }

    private fun stopImmediateSpeech() {
        runOnUiThread {
            try {
                tts?.stop()
            } catch (_: Exception) {}
            try {
                activeAudioTrack?.stop()
                activeAudioTrack?.release()
                activeAudioTrack = null
            } catch (_: Exception) {}
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAlarmIntent(intent)
        AiCoachReceiver.scheduleAllDailyAiCoachSlots(this)
    }

    override fun onDestroy() {
        stopImmediateSpeech()
        try {
            speechRecognizer?.destroy()
            tts?.shutdown()
        } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun handleAlarmIntent(intent: Intent?) {
        val trigger = intent?.getBooleanExtra("TRIGGER_CHALLENGE", false) ?: false
        if (trigger) {
            pendingLabel = intent?.getStringExtra("EXTRA_ALARM_LABEL") ?: "Waktunya Bangun & Push-Up!"
            pendingReps = intent?.getIntExtra("EXTRA_ALARM_REPS", 10) ?: 10
            pendingChallenge = true
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BangunProduktifIdenticalApp(
    pendingChallenge: Boolean,
    pendingLabel: String,
    pendingReps: Int,
    onChallengeDispatched: () -> Unit,
    onWebViewReady: (WebView) -> Unit,
    onSpeakImmediate: (String) -> Unit,
    onStopImmediate: () -> Unit,
    onStartNativeMic: () -> Unit,
    onStopNativeMic: () -> Unit,
    onSpeakGeminiVoice: (String, String, String) -> Unit
) {
    val context = LocalContext.current
    var filePathCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var pageFinished by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {}

    LaunchedEffect(Unit) {
        val needed = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    val fileChooserLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        val uris = mutableListOf<Uri>()
        if (result.resultCode == android.app.Activity.RESULT_OK && data != null) {
            val clipData = data.clipData
            if (clipData != null) {
                for (i in 0 until clipData.itemCount) {
                    clipData.getItemAt(i).uri?.let { uris.add(it) }
                }
            } else {
                data.data?.let { uris.add(it) }
            }
        }
        filePathCallback?.onReceiveValue(if (uris.isNotEmpty()) uris.toTypedArray() else null)
        filePathCallback = null
    }

    LaunchedEffect(pendingChallenge, pageFinished, webViewInstance) {
        if (pendingChallenge && pageFinished && webViewInstance != null) {
            val safeLabel = JSONObject.quote(pendingLabel)
            webViewInstance?.evaluateJavascript(
                "if (typeof window.triggerAlarmFromAndroidNative === 'function') { window.triggerAlarmFromAndroidNative($safeLabel, $pendingReps); }",
                null
            )
            onChallengeDispatched()
        }
    }

    val assetLoader = remember {
        WebViewAssetLoader.Builder()
            .setDomain("zkai-dev.github.io")
            .addPathHandler("/Bangun-produktif/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()
    }

    val cleanChromeUserAgent =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.6478.122 Mobile Safari/537.36"

    AndroidView(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF08090C)),
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(android.graphics.Color.parseColor("#08090C"))
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    allowFileAccess = true
                    allowContentAccess = true
                    mediaPlaybackRequiresUserGesture = false
                    javaScriptCanOpenWindowsAutomatically = true
                    setSupportMultipleWindows(true)
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    cacheMode = WebSettings.LOAD_DEFAULT
                    userAgentString = cleanChromeUserAgent
                }

                addJavascriptInterface(
                    NativeWebBridge(
                        ctx,
                        onSpeakImmediate,
                        onStopImmediate,
                        onStartNativeMic,
                        onStopNativeMic,
                        onSpeakGeminiVoice
                    ),
                    "AndroidNative"
                )

                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest
                    ): WebResourceResponse? {
                        val url = request.url
                        if (url.host == "zkai-dev.github.io" &&
                            (url.path == "/Bangun-produktif" || url.path == "/Bangun-produktif/")
                        ) {
                            return assetLoader.shouldInterceptRequest(
                                Uri.parse("https://zkai-dev.github.io/Bangun-produktif/index.html")
                            )
                        }
                        return assetLoader.shouldInterceptRequest(url)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        pageFinished = true
                    }
                }

                webChromeClient = object : WebChromeClient() {
                    override fun onPermissionRequest(request: PermissionRequest?) {
                        request?.grant(request.resources)
                    }

                    override fun onCreateWindow(
                        view: WebView?,
                        isDialog: Boolean,
                        isUserGesture: Boolean,
                        resultMsg: Message?
                    ): Boolean {
                        val popupDialog = Dialog(ctx, android.R.style.Theme_DeviceDefault_NoActionBar)
                        val popupWebView = WebView(ctx).apply {
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                databaseEnabled = true
                                javaScriptCanOpenWindowsAutomatically = true
                                setSupportMultipleWindows(true)
                                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                userAgentString = cleanChromeUserAgent
                            }
                            webViewClient = object : WebViewClient() {
                                override fun shouldInterceptRequest(
                                    v: WebView?,
                                    req: WebResourceRequest
                                ): WebResourceResponse? {
                                    return assetLoader.shouldInterceptRequest(req.url)
                                }
                            }
                            webChromeClient = object : WebChromeClient() {
                                override fun onCloseWindow(window: WebView?) {
                                    popupDialog.dismiss()
                                }
                            }
                        }

                        popupDialog.setContentView(
                            popupWebView,
                            ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        )
                        popupDialog.setOnDismissListener {
                            try {
                                popupWebView.stopLoading()
                                popupWebView.destroy()
                            } catch (_: Exception) {}
                        }
                        popupDialog.show()

                        val transport = resultMsg?.obj as? WebView.WebViewTransport
                        transport?.webView = popupWebView
                        resultMsg?.sendToTarget()
                        return true
                    }

                    override fun onShowFileChooser(
                        webView: WebView?,
                        callback: ValueCallback<Array<Uri>>?,
                        fileChooserParams: FileChooserParams?
                    ): Boolean {
                        filePathCallback?.onReceiveValue(null)
                        filePathCallback = callback
                        return try {
                            val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                type = "*/*"
                            }
                            fileChooserLauncher.launch(intent)
                            true
                        } catch (_: Exception) {
                            filePathCallback = null
                            false
                        }
                    }
                }

                setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                    try {
                        if (url.startsWith("http://") || url.startsWith("https://")) {
                            val req = DownloadManager.Request(Uri.parse(url)).apply {
                                setMimeType(mimeType)
                                addRequestHeader("User-Agent", userAgent)
                                setTitle(URLUtil.guessFileName(url, contentDisposition, mimeType))
                                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                                setDestinationInExternalPublicDir(
                                    Environment.DIRECTORY_DOWNLOADS,
                                    URLUtil.guessFileName(url, contentDisposition, mimeType)
                                )
                            }
                            val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                            dm.enqueue(req)
                            Toast.makeText(ctx, "Mengunduh file...", Toast.LENGTH_SHORT).show()
                        }
                    } catch (_: Exception) {}
                }

                loadUrl("https://zkai-dev.github.io/Bangun-produktif/index.html")
                webViewInstance = this
                onWebViewReady(this)
            }
        }
    )
}
