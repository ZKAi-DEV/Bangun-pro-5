package com.ubay.bangunproduktif

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class AiCoachReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("bangunproduktif_prefs", Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean("ai_coach_enabled", true)
        if (!enabled) return

        val slotName = intent.getStringExtra(EXTRA_AI_SLOT) ?: "Pagi"
        val customTitle = intent.getStringExtra(EXTRA_AI_TITLE)
        val customBody = intent.getStringExtra(EXTRA_AI_BODY)

        val (title, message) = if (!customTitle.isNullOrBlank() && !customBody.isNullOrBlank()) {
            customTitle to customBody
        } else {
            buildSmartDailyCoachingMessage(context, slotName)
        }

        showAiCoachNotification(context, slotName.hashCode(), title, message)

        val voiceEnabled = prefs.getBoolean("ai_voice_enabled", true)
        if (voiceEnabled) {
            try {
                val voiceIntent = Intent(context, AiVoiceService::class.java).apply {
                    putExtra(AiVoiceService.EXTRA_VOICE_TITLE, title)
                    putExtra(AiVoiceService.EXTRA_VOICE_TEXT, message)
                }
                ContextCompat.startForegroundService(context, voiceIntent)
            } catch (_: Exception) {}
        }

        scheduleNextAiCoachSlot(context, slotName)
    }

    private fun buildSmartDailyCoachingMessage(context: Context, slotName: String): Pair<String, String> {
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
                    val completed = item.optBoolean("completed", false)
                    val tTitle = item.optString("title", "Tugas")
                    if (completed) {
                        doneCount++
                    } else {
                        pendingTasks.add(tTitle)
                    }
                }
            }
        } catch (_: Exception) {}

        var alarmSummary = ""
        try {
            val alarmsRaw = prefs.getString("alarms_json", "[]") ?: "[]"
            val arr = JSONArray(alarmsRaw)
            var activeAlarms = 0
            for (i in 0 until arr.length()) {
                if (arr.getJSONObject(i).optBoolean("enabled", true)) {
                    activeAlarms++
                }
            }
            if (activeAlarms > 0) {
                alarmSummary = ". Kamu punya $activeAlarms alarm push up aktif"
            }
        } catch (_: Exception) {}

        val focusList = pendingTasks.take(2).joinToString(" dan ")

        return when (slotName) {
            "Pagi" -> {
                val body = if (focusList.isNotEmpty()) {
                    "Selamat pagi! Fokus kamu pagi ini adalah $focusList. Jangan tunda push up dan rutinitas harianmu$alarmSummary!"
                } else {
                    "Selamat pagi! Awali hari dengan energi penuh. Cek jadwal sholat, rutinitas, dan alarm push up kamu hari ini$alarmSummary."
                }
                "AI BangunProduktif • Sapaan Pagi" to body
            }
            "Siang" -> {
                val body = if (totalCount > 0) {
                    "Halo, selamat siang! Progres hari ini sudah $doneCount dari $totalCount tugas selesai${if (focusList.isNotEmpty()) ". Yang belum diceklis antara lain $focusList" else ", mantap banget, pertahankan!"}."
                } else {
                    "Selamat siang! Waktunya evaluasi tengah hari. Sudah cek tugas dan jadwal kuliah atau kerja hari ini?"
                }
                "AI BangunProduktif • Cek Progres Siang" to body
            }
            "Sore" -> {
                val body = if (focusList.isNotEmpty()) {
                    "Selamat sore! Sisa tugas sore ini adalah $focusList. Ayo tuntaskan sebelum malam tiba!"
                } else {
                    "Selamat sore! Keren, rutinitas harianmu sudah tuntas. Siapkan target untuk besok ya."
                }
                "AI BangunProduktif • Pengingat Sore" to body
            }
            else -> {
                val body = if (focusList.isNotEmpty()) {
                    "Selamat malam! Evaluasi malam ini masih ada $focusList yang belum selesai. Jangan lupa cek alarm push up sebelum tidur!"
                } else {
                    "Selamat malam! Hari ini kamu luar biasa produktif. Pastikan alarm bangun dan push up untuk besok sudah aktif."
                }
                "AI BangunProduktif • Refleksi Malam" to body
            }
        }
    }

    private fun showAiCoachNotification(context: Context, notifId: Int, title: String, message: String) {
        createAiCoachChannel(context)

        val openIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("OPEN_AI_COACH", true)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notifId + 500,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(notifId, notification)
    }

    companion object {
        const val CHANNEL_ID = "bp_ai_coach_channel"
        const val EXTRA_AI_SLOT = "EXTRA_AI_SLOT"
        const val EXTRA_AI_TITLE = "EXTRA_AI_TITLE"
        const val EXTRA_AI_BODY = "EXTRA_AI_BODY"

        fun createAiCoachChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Asisten AI Harian BangunProduktif",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Pengingat & suara otomatis AI setiap hari meskipun aplikasi ditutup"
                }
                val nm = context.getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(channel)
            }
        }

        fun scheduleAllDailyAiCoachSlots(context: Context) {
            scheduleNextAiCoachSlot(context, "Pagi")
            scheduleNextAiCoachSlot(context, "Siang")
            scheduleNextAiCoachSlot(context, "Sore")
            scheduleNextAiCoachSlot(context, "Malam")
        }

        fun scheduleNextAiCoachSlot(context: Context, slotName: String) {
            try {
                val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val (hour, minute) = when (slotName) {
                    "Pagi" -> 6 to 0
                    "Siang" -> 12 to 15
                    "Sore" -> 16 to 30
                    else -> 21 to 0
                }

                val cal = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, hour)
                    set(Calendar.MINUTE, minute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                    if (timeInMillis <= System.currentTimeMillis()) {
                        add(Calendar.DAY_OF_YEAR, 1)
                    }
                }

                val intent = Intent(context, AiCoachReceiver::class.java).apply {
                    putExtra(EXTRA_AI_SLOT, slotName)
                }
                val pi = PendingIntent.getBroadcast(
                    context,
                    ("ai_coach_" + slotName).hashCode(),
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
                } else {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
                }
            } catch (_: Exception) {}
        }
    }
}
