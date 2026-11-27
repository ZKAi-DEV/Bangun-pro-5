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
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class AiVoiceService : Service(), TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var pendingSpeakText: String? = null
    private var isTtsReady = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val autoStopRunnable = Runnable { stopSelf() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(applicationContext, this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_VOICE) {
            tts?.stop()
            stopSelf()
            return START_NOT_STICKY
        }

        createServiceChannel()
        val initialTitle = intent?.getStringExtra(EXTRA_VOICE_TITLE) ?: "AI BangunProduktif"
        val directText = intent?.getStringExtra(EXTRA_VOICE_TEXT)
        val slotName = intent?.getStringExtra(EXTRA_AI_SLOT)

        val fgNotif = buildForegroundServiceNotification(
            initialTitle,
            directText ?: "AI sedang mengecek progres harian & keuangan kamu..."
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, fgNotif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, fgNotif)
        }

        mainHandler.removeCallbacks(autoStopRunnable)
        mainHandler.postDelayed(autoStopRunnable, 40_000L)

        if (!slotName.isNullOrBlank()) {
            // Run live background AI analysis (Gemini API if key available, else smart local persona)
            worker.execute {
                val (title, body) = generateLivingBackgroundMessage(applicationContext, slotName)
                showExpandableAiAlert(applicationContext, slotName.hashCode(), title, body)

                val prefs = getSharedPreferences("bangunproduktif_prefs", Context.MODE_PRIVATE)
                val voiceEnabled = prefs.getBoolean("ai_voice_enabled", true)
                mainHandler.post {
                    if (voiceEnabled) {
                        queueOrSpeak(body)
                    } else {
                        stopSelf()
                    }
                }
            }
        } else if (!directText.isNullOrBlank()) {
            queueOrSpeak(directText)
        } else {
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun queueOrSpeak(text: String) {
        pendingSpeakText = text
        if (isTtsReady) {
            speakNow(text)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val engine = tts ?: return
            val res = engine.setLanguage(Locale("id", "ID"))
            if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                engine.setLanguage(Locale.getDefault())
            }
            engine.setSpeechRate(1.02f)
            engine.setPitch(1.0f)
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
            pendingSpeakText?.let { speakNow(it) }
        } else {
            stopSelf()
        }
    }

    private fun speakNow(text: String) {
        try {
            val cleanText = text.replace("*", "").replace("#", "").replace("`", "")
            tts?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, "bp_ai_voice_${System.currentTimeMillis()}")
        } catch (_: Exception) {
            stopSelf()
        }
    }

    private fun generateLivingBackgroundMessage(context: Context, slotName: String): Pair<String, String> {
        val prefs = context.getSharedPreferences("bangunproduktif_prefs", Context.MODE_PRIVATE)
        val todayIso = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

        val pendingTasks = mutableListOf<String>()
        var doneCount = 0
        var totalCount = 0
        try {
            val todosRaw = prefs.getString("todos_json", "{}") ?: "{}"
            val root = JSONObject(todosRaw)
            if (root.has(todayIso)) {
                val arr = root.getJSONArray(todayIso)
                totalCount = arr.length()
                for (i in 0 until arr.length()) {
                    val item = arr.getJSONObject(i)
                    if (item.optBoolean("completed", false)) {
                        doneCount++
                    } else {
                        pendingTasks.add(item.optString("title", "Tugas"))
                    }
                }
            }
        } catch (_: Exception) {}

        var activeAlarms = 0
        try {
            val alarmsRaw = prefs.getString("alarms_json", "[]") ?: "[]"
            val arr = JSONArray(alarmsRaw)
            for (i in 0 until arr.length()) {
                if (arr.getJSONObject(i).optBoolean("enabled", true)) activeAlarms++
            }
        } catch (_: Exception) {}

        var income = 0L
        var expense = 0L
        var balance = 0L
        try {
            val finRaw = prefs.getString("finance_summary_json", "{}") ?: "{}"
            val finObj = JSONObject(finRaw)
            income = finObj.optLong("monthIncome", 0L)
            expense = finObj.optLong("monthExpense", 0L)
            balance = finObj.optLong("monthBalance", income - expense)
        } catch (_: Exception) {}

        var apiKey = ""
        var critiqueMode = "jujur"
        var userContext = "Ubay, 20 tahun, mahasiswa Relasi Industri Polteknaker, aktif GSA & NoberAI, narik ShopeeFood/Lalamove malam, freelance desain & video, bercita-cita jadi Engineer & berkarier kuat."
        try {
            val cfgRaw = prefs.getString("ai_config_json", "{}") ?: "{}"
            val cfgObj = JSONObject(cfgRaw)
            apiKey = cfgObj.optString("geminiApiKey", "").trim()
            critiqueMode = cfgObj.optString("critiqueMode", "jujur")
            val customCtx = cfgObj.optString("userContext", "").trim()
            if (customCtx.isNotEmpty()) userContext = customCtx
        } catch (_: Exception) {}

        val title = when (slotName) {
            "Pagi" -> "AI Pendamping • Check-in Pagi"
            "Siang" -> "AI Pendamping • Evaluasi Siang"
            "Sore" -> "AI Pendamping • Pengingat Sore"
            "Malam" -> "AI Pendamping • Refleksi Malam"
            else -> "AI Pendamping • Live Check-in"
        }

        // Try live Gemini 2.5 Flash API first if user configured API key
        if (apiKey.isNotEmpty()) {
            val geminiReply = callGeminiBackground(
                apiKey = apiKey,
                slotName = slotName,
                critiqueMode = critiqueMode,
                userContext = userContext,
                doneCount = doneCount,
                totalCount = totalCount,
                pendingTasks = pendingTasks,
                activeAlarms = activeAlarms,
                income = income,
                expense = expense,
                balance = balance
            )
            if (!geminiReply.isNullOrBlank()) {
                return title to geminiReply
            }
        }

        // Smart, natural, non-stiff local Indonesian fallback
        val idFmt = NumberFormat.getNumberInstance(Locale("id", "ID"))
        val focusList = pendingTasks.take(2).joinToString(" sama ")
        val financeHint = if (income > 0 || expense > 0) {
            if (expense > income) {
                " Pengeluaran bulan ini udah Rp ${idFmt.format(expense)}, lebih gede dari pemasukan nih, rem dulu jajan!"
            } else {
                " Saldo bulan ini aman di Rp ${idFmt.format(balance)}."
            }
        } else ""

        val body = when (critiqueMode) {
            "roasting" -> when (slotName) {
                "Pagi" -> if (focusList.isNotEmpty()) {
                    "Woy bangun! Jangan cuma mimpi jadi engineer tapi rebahan terus. Pagi ini $focusList masih kosong tuh, buruan gerak!$financeHint"
                } else {
                    "Pagi! Gaspol hari ini, jangan kendor. Target gede nggak bakal kejadian kalau masih nunda-nunda.$financeHint"
                }
                "Siang" -> if (totalCount > 0 && doneCount < totalCount / 2) {
                    "Udah siang baru kelar $doneCount dari $totalCount tugas? Serius nih mau naik level? Sikat $focusList sekarang juga!$financeHint"
                } else {
                    "Lumayan lah siang ini udah $doneCount dari $totalCount kelar. Jangan puas dulu, sisanya masih nunggu tuh.$financeHint"
                }
                "Sore" -> if (focusList.isNotEmpty()) {
                    "Sore bro! $focusList masih nongkrong belum diceklis. Ntar malem capek narik malah lupa, kelarin sekarang!$financeHint"
                } else {
                    "Gokil, sore ini tugas lu udah bersih. Pertahanin mental juara kayak gini!"
                }
                else -> if (focusList.isNotEmpty()) {
                    "Malem! Jujur ya, hari ini masih bolong di $focusList. Jangan diulangin besok, cek $activeAlarms alarm push up lu sebelum tidur.$financeHint"
                } else {
                    "Respect! Hari ini lu tuntas semua. Istirahat yang bener, besok gas lagi.$financeHint"
                }
            }
            "santai" -> when (slotName) {
                "Pagi" -> "Pagi Ubay! Semangat mulai hari ini ya. ${if (focusList.isNotEmpty()) "Pelan-pelan kita cicil $focusList dulu yuk." else "Cek jadwal & rutinitas pagi kamu dulu ya."}$financeHint"
                "Siang" -> "Halo, gimana siangnya? Progres kamu udah $doneCount dari $totalCount nih. ${if (focusList.isNotEmpty()) "Kalau ada jeda, yuk sikat $focusList." else "Mantap banget!"}$financeHint"
                "Sore" -> "Sore! Jangan lupa tarik napas sebentar ya. ${if (focusList.isNotEmpty()) "Tinggal $focusList lagi nih sebelum malam." else "Rutinitas hari ini udah rapi banget!"}$financeHint"
                else -> "Malam Ubay! Makasih udah berjuang hari ini. ${if (focusList.isNotEmpty()) "Yang belum sempat kayak $focusList bisa kita evaluasi buat besok." else "Hari ini keren banget, semua beres!"}$financeHint"
            }
            else -> when (slotName) {
                "Pagi" -> "Pagi! Ini check-in harian kamu. ${if (focusList.isNotEmpty()) "Prioritas sekarang: $focusList. Jangan sampai ketunda." else "Pastikan semua target hari ini jelas dari pagi."}$financeHint"
                "Siang" -> "Update tengah hari: baru $doneCount dari $totalCount selesai. ${if (focusList.isNotEmpty()) "Segera kerjakan $focusList biar nggak numpuk pas malam." else "Ritme kamu hari ini bagus."}$financeHint"
                "Sore" -> "Evaluasi sore: ${if (focusList.isNotEmpty()) "masih ada $focusList yang kebuka. Tuntaskan sebelum shift malam." else "target harian aman terkendali."}$financeHint"
                else -> "Refleksi malam: progres hari ini $doneCount dari $totalCount. ${if (focusList.isNotEmpty()) "Catat kenapa $focusList tertinggal, lalu pasang alarm push up buat besok." else "Solid! Siapkan tidur cukup buat besok."}$financeHint"
            }
        }
        return title to body
    }

    private fun callGeminiBackground(
        apiKey: String,
        slotName: String,
        critiqueMode: String,
        userContext: String,
        doneCount: Int,
        totalCount: Int,
        pendingTasks: List<String>,
        activeAlarms: Int,
        income: Long,
        expense: Long,
        balance: Long
    ): String? {
        var conn: HttpURLConnection? = null
        return try {
            val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey"
            conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 15_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            }

            val styleGuide = when (critiqueMode) {
                "roasting" -> "Gaya bicara: teman dekat yang blak-blakan, boleh roasting pedas tapi tetap peduli dan memotivasi, santai pakai bahasa Indonesia sehari-hari, SAMA SEKALI TIDAK KAKU."
                "santai" -> "Gaya bicara: teman dekat yang hangat, santai, suportif, natural pakai bahasa Indonesia sehari-hari, tidak seperti robot."
                else -> "Gaya bicara: mentor & partner produktivitas yang jujur, tajam, langsung to the point, santai natural berbahasa Indonesia, berani mengkritik kalau progres kurang."
            }

            val promptText = """
Kamu adalah asisten AI pribadi yang hidup di dalam aplikasi BangunProduktif.
Profil & tujuan user: $userContext
$styleGuide

Data real-time sekarang (Slot: $slotName):
- Progres To-Do hari ini: $doneCount dari $totalCount selesai.
- Tugas yang belum selesai: ${if (pendingTasks.isEmpty()) "Semua sudah beres" else pendingTasks.take(4).joinToString(", ")}
- Alarm Push-Up aktif: $activeAlarms
- Keuangan bulan ini: Pemasukan Rp $income, Pengeluaran Rp $expense, Saldo Rp $balance.

Tugasmu: Buat 1 pesan singkat (maksimal 2-3 kalimat, enak dibacakan dengan suara/TTS, tanpa simbol markdown atau emoji berlebihan) untuk mengingatkan sekaligus mengkritik/menyemangati user sesuai data nyata di atas.
            """.trimIndent()

            val payload = JSONObject().apply {
                put("contents", JSONArray().put(
                    JSONObject().put("parts", JSONArray().put(
                        JSONObject().put("text", promptText)
                    ))
                ))
            }

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload.toString()) }

            if (conn.responseCode in 200..299) {
                val responseStr = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
                val root = JSONObject(responseStr)
                val candidates = root.optJSONArray("candidates")
                val text = candidates
                    ?.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")
                    ?.optJSONObject(0)
                    ?.optString("text", "")
                    ?.trim()
                if (!text.isNullOrBlank()) text else null
            } else null
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun showExpandableAiAlert(context: Context, notifId: Int, title: String, message: String) {
        AiCoachReceiver.createAiCoachChannel(context)
        val openIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("OPEN_AI_TAB", true)
        }
        val pi = PendingIntent.getActivity(
            context,
            notifId + 600,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(context, AiCoachReceiver.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(notifId, notif)
    }

    private fun buildForegroundServiceNotification(title: String, text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("OPEN_AI_TAB", true)
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
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pi)
            .setSilent(true)
            .build()
    }

    private fun createServiceChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Suara & Otak AI BangunProduktif",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Menjalankan analisis & suara asisten AI di latar belakang"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(ch)
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(autoStopRunnable)
        try {
            tts?.stop()
            tts?.shutdown()
            worker.shutdownNow()
        } catch (_: Exception) {}
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "bp_ai_voice_service_channel"
        const val NOTIF_ID = 2002
        const val ACTION_STOP_VOICE = "com.ubay.bangunproduktif.ACTION_STOP_VOICE"
        const val EXTRA_VOICE_TITLE = "EXTRA_VOICE_TITLE"
        const val EXTRA_VOICE_TEXT = "EXTRA_VOICE_TEXT"
        const val EXTRA_AI_SLOT = "EXTRA_AI_SLOT"
    }
}
