package com.ubay.bangunproduktif

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import org.json.JSONArray
import java.util.Calendar

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            rescheduleSavedAlarms(context)
            AiCoachReceiver.scheduleAllDailyAiCoachSlots(context)
            return
        }

        val label = intent.getStringExtra("EXTRA_ALARM_LABEL") ?: "Waktunya Bangun & Push-Up!"
        val reps = intent.getIntExtra("EXTRA_ALARM_REPS", 10)
        val alarmId = intent.getStringExtra("EXTRA_ALARM_ID") ?: ""

        val serviceIntent = Intent(context, AlarmSoundService::class.java).apply {
            putExtra("EXTRA_ALARM_LABEL", label)
            putExtra("EXTRA_ALARM_REPS", reps)
            putExtra("EXTRA_ALARM_ID", alarmId)
        }
        ContextCompat.startForegroundService(context, serviceIntent)

        // Reschedule for the next repeating day automatically
        rescheduleSavedAlarms(context)
    }

    private fun rescheduleSavedAlarms(context: Context) {
        try {
            val prefs = context.getSharedPreferences("bangunproduktif_prefs", Context.MODE_PRIVATE)
            val raw = prefs.getString("alarms_json", null) ?: return
            val arr = JSONArray(raw)
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val now = Calendar.getInstance()

            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (!o.optBoolean("enabled", true)) continue
                val id = o.optString("id", "alm_$i")
                val time = o.optString("time", "05:00")
                val reps = o.optInt("reps", 10)
                val label = o.optString("label", "Bangun & Push-Up")
                val dArr = o.optJSONArray("days") ?: JSONArray()
                val days = (0 until dArr.length()).map { dArr.getInt(it) }
                val activeDays = if (days.isEmpty()) listOf(0, 1, 2, 3, 4, 5, 6) else days

                val parts = time.split(":")
                val h = parts.getOrNull(0)?.toIntOrNull() ?: 5
                val m = parts.getOrNull(1)?.toIntOrNull() ?: 0

                var bestCal: Calendar? = null
                for (offset in 0..7) {
                    val candidate = Calendar.getInstance().apply {
                        add(Calendar.DAY_OF_YEAR, offset)
                        set(Calendar.HOUR_OF_DAY, h)
                        set(Calendar.MINUTE, m)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    val jsDow = (candidate.get(Calendar.DAY_OF_WEEK) - 1)
                    if (activeDays.contains(jsDow) && candidate.timeInMillis > now.timeInMillis + 1500) {
                        bestCal = candidate
                        break
                    }
                }

                val targetTime = bestCal?.timeInMillis ?: continue

                val alarmIntent = Intent(context, AlarmReceiver::class.java).apply {
                    putExtra("EXTRA_ALARM_ID", id)
                    putExtra("EXTRA_ALARM_LABEL", label)
                    putExtra("EXTRA_ALARM_REPS", reps)
                }
                val pi = PendingIntent.getBroadcast(
                    context,
                    id.hashCode(),
                    alarmIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, targetTime, pi)
                } else {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, targetTime, pi)
                }
            }
        } catch (_: Exception) {}
    }
}
