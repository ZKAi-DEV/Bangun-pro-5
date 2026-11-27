package com.ubay.bangunproduktif

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

class AiVoiceService : Service(), TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var pendingText: String? = null
    private var isTtsReady = false
    @Volatile
    private var activeAudioTrack: AudioTrack? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val autoStopRunnable = Runnable { stopSelf() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(applicationContext, this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_VOICE) {
            stopAllAudio()
            stopSelf()
            return START_NOT_STICKY
        }

        val title = intent?.getStringExtra(EXTRA_VOICE_TITLE) ?: "AI BangunProduktif Berbicara"
        val text = intent?.getStringExtra(EXTRA_VOICE_TEXT)
            ?: "Halo! Waktunya cek target harian dan rutinitas BangunProduktif kamu."

        createChannel()
        val notification = buildNotification(title, text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, notification)
        }

        mainHandler.removeCallbacks(autoStopRunnable)
        mainHandler.postDelayed(autoStopRunnable, 35_000L)

        val prefs = getSharedPreferences("bangunproduktif_prefs", Context.MODE_PRIVATE)
        val savedKey = (prefs.getString("gemini_api_key", "") ?: "").trim()
        val defaultKey = listOf("AQ.Ab8RN6I", "Um_-b4rFwHP", "2DYZCKnzYcT", "BtI8Ss7VY0w", "7C-CWmneRA").joinToString("")
        val apiKey = if (savedKey.length > 15) savedKey else defaultKey
        val voiceName = prefs.getString("gemini_voice_name", "Aoede") ?: "Aoede"

        if (apiKey.length > 15) {
            speakWithGeminiFemaleVoice(apiKey, text, voiceName)
        } else {
            pendingText = text
            if (isTtsReady) {
                speakFallbackNow(text)
            }
        }

        return START_NOT_STICKY
    }

    private fun speakWithGeminiFemaleVoice(apiKey: String, text: String, voiceName: String) {
        thread {
            try {
                val url = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-preview-tts:generateContent?key=$apiKey")
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
                            JSONObject().put("text", "Ucapkan dengan suara perempuan Indonesia yang hangat, natural, dan lancar: $text")
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

                if (conn.responseCode in 200..299) {
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
                        playPcmAndStopWhenDone(pcmBytes)
                        return@thread
                    }
                }
                triggerFallbackTtsOnMain(text)
            } catch (_: Exception) {
                triggerFallbackTtsOnMain(text)
            }
        }
    }

    private fun playPcmAndStopWhenDone(pcmBytes: ByteArray) {
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

            val durationMs = ((pcmBytes.size.toLong() / 2L) * 1000L) / sampleRate + 800L
            mainHandler.postDelayed({ stopSelf() }, durationMs)
        } catch (_: Exception) {
            stopSelf()
        }
    }

    private fun triggerFallbackTtsOnMain(text: String) {
        mainHandler.post {
            pendingText = text
            if (isTtsReady) {
                speakFallbackNow(text)
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val engine = tts ?: return
            val idLocale = Locale("id", "ID")
            val res = engine.setLanguage(idLocale)
            if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                engine.setLanguage(Locale.getDefault())
            }
            engine.setSpeechRate(1.02f)
            engine.setPitch(1.06f)
            engine.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    mainHandler.post { stopSelf() }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    mainHandler.post { stopSelf() }
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    mainHandler.post { stopSelf() }
                }
            })

            isTtsReady = true
            pendingText?.let { speakFallbackNow(it) }
        }
    }

    private fun speakFallbackNow(text: String) {
        try {
            val utteranceId = "bp_ai_voice_${System.currentTimeMillis()}"
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        } catch (_: Exception) {
            stopSelf()
        }
    }

    private fun stopAllAudio() {
        try {
            tts?.stop()
        } catch (_: Exception) {}
        try {
            activeAudioTrack?.stop()
            activeAudioTrack?.release()
            activeAudioTrack = null
        } catch (_: Exception) {}
    }

    private fun buildNotification(title: String, text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pi = PendingIntent.getActivity(
            this,
            7701,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pi)
            .setSilent(true)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Suara AI BangunProduktif",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Menjalankan suara asisten AI saat pengingat harian berbunyi"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(ch)
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(autoStopRunnable)
        stopAllAudio()
        try {
            tts?.shutdown()
        } catch (_: Exception) {}
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "bp_ai_voice_service_channel"
        const val NOTIF_ID = 2002
        const val ACTION_STOP_VOICE = "com.ubay.bangunproduktif.ACTION_STOP_VOICE"
        const val EXTRA_VOICE_TITLE = "EXTRA_VOICE_TITLE"
        const val EXTRA_VOICE_TEXT = "EXTRA_VOICE_TEXT"
    }
}
