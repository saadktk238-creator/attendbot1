package com.attendbot;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import java.util.*;
import java.text.SimpleDateFormat;

public class MainActivity extends AppCompatActivity {

    SharedPreferences prefs;
    TextView tvStatus, tvLastAttendance, tvNextRun;
    EditText etUrl, etWindowStart, etWindowEnd;
    Switch swAutoInternet, swAutoFlight, swDaily;
    Button btnStartStop, btnTestNow;
    LinearLayout llHistory;
    boolean botRunning = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("AttendBot", MODE_PRIVATE);
        initViews();
        loadSettings();
        updateUI();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(this,
                new String[]{
                    android.Manifest.permission.POST_NOTIFICATIONS,
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION
                }, 1);
        } else {
            ActivityCompat.requestPermissions(this,
                new String[]{
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION
                }, 1);
        }
        checkBatteryOptimization();
    }

    void checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (!pm.isIgnoringBatteryOptimizations(getPackageName())) {
                new AlertDialog.Builder(this)
                    .setTitle("بیٹری سیور الرٹ")
                    .setMessage("App کو صحیح وقت پر چلانے کے لیے اسے Battery Optimization سے نکالنا ضروری ہے۔")
                    .setPositiveButton("سیٹنگز کھولیں", (d, w) -> {
                        Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                        intent.setData(android.net.Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                    })
                    .setNegativeButton("بعد میں", null)
                    .show();
            }
        }
    }

    void initViews() {
        tvStatus = findViewById(R.id.tvStatus);
        tvLastAttendance = findViewById(R.id.tvLastAttendance);
        tvNextRun = findViewById(R.id.tvNextRun);
        etUrl = findViewById(R.id.etUrl);
        etWindowStart = findViewById(R.id.etWindowStart);
        etWindowEnd = findViewById(R.id.etWindowEnd);
        swAutoInternet = findViewById(R.id.swAutoInternet);
        swAutoFlight = findViewById(R.id.swAutoFlight);
        swDaily = findViewById(R.id.swDaily);
        btnStartStop = findViewById(R.id.btnStartStop);
        btnTestNow = findViewById(R.id.btnTestNow);
        llHistory = findViewById(R.id.llHistory);

        etWindowStart.setOnClickListener(v -> showTimePicker(true));
        etWindowEnd.setOnClickListener(v -> showTimePicker(false));

        btnStartStop.setOnClickListener(v -> {
            if (botRunning) stopBot();
            else startBot();
        });

        btnTestNow.setOnClickListener(v -> {
            saveSettings();
            Intent serviceIntent = new Intent(this, AttendanceService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
            Toast.makeText(this, "ٹیسٹ شروع ہو رہا ہے، نوٹیفکیشن چیک کریں", Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.btnSaveSettings).setOnClickListener(v -> saveSettings());
    }

    void showTimePicker(boolean isStart) {
        String current = isStart ? etWindowStart.getText().toString() : etWindowEnd.getText().toString();
        int hour = 8, minute = isStart ? 30 : 0;
        try {
            String[] parts = current.split(":");
            hour = Integer.parseInt(parts[0]);
            minute = Integer.parseInt(parts[1]);
        } catch (Exception ignored) {}

        new TimePickerDialog(this, (view, h, m) -> {
            String time = String.format("%02d:%02d", h, m);
            if (isStart) etWindowStart.setText(time);
            else etWindowEnd.setText(time);
        }, hour, minute, true).show();
    }

    void startBot() {
        String url = etUrl.getText().toString().trim();
        if (url.isEmpty() || !url.startsWith("http")) {
            Toast.makeText(this, "براہ کرم درست URL داخل کریں", Toast.LENGTH_SHORT).show();
            return;
        }
        saveSettings();
        botRunning = true;
        prefs.edit().putBoolean("running", true).apply();
        scheduleAlarm();
        updateUI();
        Toast.makeText(this, "Bot شروع ہو گیا ✓", Toast.LENGTH_SHORT).show();
    }

    void stopBot() {
        botRunning = false;
        prefs.edit().putBoolean("running", false).apply();
        cancelAlarm();
        updateUI();
        Toast.makeText(this, "Bot بند ہو گیا", Toast.LENGTH_SHORT).show();
    }

    void scheduleAlarm() {
        String windowStart = etWindowStart.getText().toString();
        String[] parts = windowStart.split(":");
        int startHour = Integer.parseInt(parts[0]);
        int startMin = Integer.parseInt(parts[1]);

        String windowEnd = etWindowEnd.getText().toString();
        String[] eParts = windowEnd.split(":");
        int endHour = Integer.parseInt(eParts[0]);
        int endMin = Integer.parseInt(eParts[1]);

        // Random time between window start and end
        int startTotal = startHour * 60 + startMin;
        int endTotal = endHour * 60 + endMin;
        int randomMin = startTotal + new Random().nextInt(Math.max(1, endTotal - startTotal));
        int triggerHour = randomMin / 60;
        int triggerMin = randomMin % 60;

        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, triggerHour);
        calendar.set(Calendar.MINUTE, triggerMin);
        calendar.set(Calendar.SECOND, 0);

        // If time already passed today, schedule for tomorrow
        if (calendar.getTimeInMillis() < System.currentTimeMillis()) {
            calendar.add(Calendar.DAY_OF_YEAR, 1);
        }

        // Save scheduled time for display
        String scheduledTime = String.format("%02d:%02d", triggerHour, triggerMin);
        prefs.edit().putString("scheduledTime", scheduledTime).apply();

        AlarmManager alarmManager = (AlarmManager) getSystemService(ALARM_SERVICE);
        Intent intent = new Intent(this, AlarmReceiver.class);
        intent.setAction("com.attendbot.ATTENDANCE_ALARM");
        PendingIntent pi = PendingIntent.getBroadcast(this, 0, intent,
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

        tvNextRun.setText("اگلی حاضری: " + scheduledTime);
    }

    void cancelAlarm() {
        AlarmManager alarmManager = (AlarmManager) getSystemService(ALARM_SERVICE);
        Intent intent = new Intent(this, AlarmReceiver.class);
        PendingIntent pi = PendingIntent.getBroadcast(this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        alarmManager.cancel(pi);
        tvNextRun.setText("Bot بند ہے");
    }

    void saveSettings() {
        prefs.edit()
            .putString("url", etUrl.getText().toString().trim())
            .putString("windowStart", etWindowStart.getText().toString())
            .putString("windowEnd", etWindowEnd.getText().toString())
            .putBoolean("autoInternet", swAutoInternet.isChecked())
            .putBoolean("autoFlight", swAutoFlight.isChecked())
            .putBoolean("daily", swDaily.isChecked())
            .apply();
        Toast.makeText(this, "ترتیبات محفوظ ہو گئیں ✓", Toast.LENGTH_SHORT).show();
    }

    void loadSettings() {
        etUrl.setText(prefs.getString("url", ""));
        etWindowStart.setText(prefs.getString("windowStart", "08:30"));
        etWindowEnd.setText(prefs.getString("windowEnd", "09:00"));
        swAutoInternet.setChecked(prefs.getBoolean("autoInternet", true));
        swAutoFlight.setChecked(prefs.getBoolean("autoFlight", false));
        swDaily.setChecked(prefs.getBoolean("daily", true));
        botRunning = prefs.getBoolean("running", false);
    }

    void updateUI() {
        if (botRunning) {
            btnStartStop.setText("■  Bot بند کریں");
            btnStartStop.setBackgroundColor(0xFFE53935);
            tvStatus.setText("● Bot چل رہا ہے");
            tvStatus.setTextColor(0xFF1a7a4a);
            String scheduled = prefs.getString("scheduledTime", "--:--");
            tvNextRun.setText("اگلی حاضری: " + scheduled);
        } else {
            btnStartStop.setText("▶  Bot شروع کریں");
            btnStartStop.setBackgroundColor(0xFF1a7a4a);
            tvStatus.setText("○ Bot بند ہے");
            tvStatus.setTextColor(0xFF888888);
            tvNextRun.setText("Bot بند ہے");
        }

        String last = prefs.getString("lastAttendance", "ابھی تک نہیں");
        tvLastAttendance.setText("آخری حاضری: " + last);
        loadHistory();
    }

    void loadHistory() {
        llHistory.removeAllViews();
        String histJson = prefs.getString("history", "");
        if (histJson.isEmpty()) return;

        String[] entries = histJson.split("\\|");
        for (int i = entries.length - 1; i >= Math.max(0, entries.length - 5); i--) {
            TextView tv = new TextView(this);
            tv.setText("✓  " + entries[i]);
            tv.setTextSize(13);
            tv.setPadding(0, 8, 0, 8);
            tv.setTextColor(0xFF333333);
            llHistory.addView(tv);

            View divider = new View(this);
            divider.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1));
            divider.setBackgroundColor(0xFFd0eadb);
            llHistory.addView(divider);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadSettings();
        updateUI();
    }
}
