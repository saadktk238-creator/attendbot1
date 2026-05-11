package com.attendbot;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.*;
import android.os.Build;
import java.util.*;

public class AlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        SharedPreferences prefs = context.getSharedPreferences("AttendBot", Context.MODE_PRIVATE);

        String action = intent.getAction();

        // On boot, reschedule if bot was running
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            if (prefs.getBoolean("running", false)) {
                scheduleNext(context, prefs);
            }
            return;
        }

        // Attendance alarm triggered
        if (!prefs.getBoolean("running", false)) return;

        // Start attendance service
        Intent serviceIntent = new Intent(context, AttendanceService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent);
        } else {
            context.startService(serviceIntent);
        }

        // Schedule next day if daily enabled
        if (prefs.getBoolean("daily", true) && !intent.getBooleanExtra("reschedule", false)) {
            scheduleNext(context, prefs);
        }
    }

    static void scheduleNext(Context context, SharedPreferences prefs) {
        String windowStart = prefs.getString("windowStart", "08:30");
        String windowEnd = prefs.getString("windowEnd", "09:00");

        String[] sParts = windowStart.split(":");
        String[] eParts = windowEnd.split(":");

        int startTotal = Integer.parseInt(sParts[0]) * 60 + Integer.parseInt(sParts[1]);
        int endTotal = Integer.parseInt(eParts[0]) * 60 + Integer.parseInt(eParts[1]);
        int randomMin = startTotal + new Random().nextInt(Math.max(1, endTotal - startTotal));

        int triggerHour = randomMin / 60;
        int triggerMin = randomMin % 60;

        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.DAY_OF_YEAR, 1);
        calendar.set(Calendar.HOUR_OF_DAY, triggerHour);
        calendar.set(Calendar.MINUTE, triggerMin);
        calendar.set(Calendar.SECOND, 0);

        // Save scheduled time
        prefs.edit().putString("scheduledTime",
            String.format("%02d:%02d", triggerHour, triggerMin)).apply();

        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(context, AlarmReceiver.class);
        intent.setAction("com.attendbot.ATTENDANCE_ALARM");
        PendingIntent pi = PendingIntent.getBroadcast(context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                    calendar.getTimeInMillis(), pi);
            } catch (SecurityException e) {
                alarmManager.set(AlarmManager.RTC_WAKEUP, calendar.getTimeInMillis(), pi);
            }
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, calendar.getTimeInMillis(), pi);
        }
    }
}
