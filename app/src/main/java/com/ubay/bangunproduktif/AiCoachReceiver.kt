package com.ubay.bangunproduktif

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import java.util.Calendar

class AiCoachReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("bangunproduktif_prefs", Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean("ai_coach_enabled", true)
        if (!enabled) return

        val slotName = intent.getStringExtra("EXTRA_AI_SLOT") ?: "Pagi"

        try {
            val serviceIntent = Intent(context, AiVoiceService::class.java).apply {
                putExtra(AiVoiceService.EXTRA_AI_SLOT, slotName)
            }
            ContextCompat.startForegroundService(context, serviceIntent)
        } catch (_: Exception) {}

        scheduleNextAiCoachSlot(context, slotName)
    }

    companion object {
        const val CHANNEL_ID = "bp_ai_coach_channel"

        fun createAiCoachChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Asisten AI Hidup BangunProduktif",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Kritik, evaluasi & pengingat suara otomatis AI setiap hari walau aplikasi ditutup"
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
                    putExtra("EXTRA_AI_SLOT", slotName)
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
