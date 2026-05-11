package com.attendbot;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.net.*;
import android.net.wifi.WifiManager;
import android.os.*;
import android.webkit.*;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import java.text.SimpleDateFormat;
import java.util.*;

public class AttendanceService extends Service {

    static final String TAG = "AttendBot";
    static final String CHANNEL_ID = "attendbot_channel";
    SharedPreferences prefs;
    WebView webView;
    Handler handler;
    boolean attendanceDone = false;
    int retryCount = 0;
    static final int MAX_RETRY = 3;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("AttendBot", MODE_PRIVATE);
        handler = new Handler(Looper.getMainLooper());
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(1, buildNotification("حاضری لگائی جا رہی ہے..."));
        handler.post(this::startProcess);
        return START_NOT_STICKY;
    }

    void startProcess() {
        // Step 1: Ensure internet
        if (!isInternetAvailable()) {
            boolean autoInternet = prefs.getBoolean("autoInternet", true);
            boolean autoFlight = prefs.getBoolean("autoFlight", false);
            if (autoFlight) disableFlightMode();
            if (autoInternet) enableWifi();
            // Wait 5 seconds for internet
            handler.postDelayed(this::openWebsite, 5000);
        } else {
            openWebsite();
        }
    }

    void enableWifi() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm != null && !wm.isWifiEnabled()) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    wm.setWifiEnabled(true);
                } else {
                    // Android 10+ requires user action for WiFi
                    Intent panelIntent = new Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY);
                    panelIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(panelIntent);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "WiFi enable error: " + e.getMessage());
        }
    }

    void disableFlightMode() {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1) {
                Settings.System.putInt(getContentResolver(),
                    Settings.System.AIRPLANE_MODE_ON, 0);
                Intent intent = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
                intent.putExtra("state", false);
                sendBroadcast(intent);
            }
            // Android 4.2+ requires root for flight mode
        } catch (Exception e) {
            Log.e(TAG, "Flight mode error: " + e.getMessage());
        }
    }

    boolean isInternetAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        NetworkInfo info = cm.getActiveNetworkInfo();
        return info != null && info.isConnected();
    }

    void openWebsite() {
        String url = prefs.getString("url", "");
        if (url.isEmpty()) {
            notifyUser("Error", "URL نہیں ملا، ترتیبات چیک کریں");
            stopSelf();
            return;
        }

        updateNotification("ویب سائٹ کھل رہی ہے...");

        handler.post(() -> {
            webView = new WebView(getApplicationContext());
            WebSettings settings = webView.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setDatabaseEnabled(true);
            settings.setGeolocationEnabled(true);
            settings.setMediaPlaybackRequiresUserGesture(false);
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            settings.setUserAgentString(
                "Mozilla/5.0 (Linux; Android 11; Mobile) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");

            webView.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    updateNotification("صفحہ لوڈ ہوا، بٹن ڈھونڈا جا رہا ہے...");
                    handler.postDelayed(() -> findAndClickButton(view), 2000);
                }

                @Override
                public void onReceivedError(WebView view, int code, String desc, String url) {
                    Log.e(TAG, "WebView error: " + desc);
                    if (retryCount < MAX_RETRY) {
                        retryCount++;
                        handler.postDelayed(() -> view.reload(), 3000);
                    } else {
                        notifyUser("خرابی", "ویب سائٹ نہیں کھلی");
                        stopSelf();
                    }
                }
            });

            webView.setWebChromeClient(new WebChromeClient() {
                @Override
                public void onGeolocationPermissionsShowPrompt(String origin,
                    GeolocationPermissions.Callback callback) {
                    callback.invoke(origin, true, false);
                }

                @Override
                public boolean onCreateWindow(WebView view, boolean dialog,
                    boolean userGesture, Message resultMsg) {
                    return false;
                }
            });

            webView.loadUrl(url);
        });
    }

    void findAndClickButton(WebView view) {
        // JavaScript to find "Mark Attendance" button by text content
        String js =
            "(function() {" +
            "  var keywords = ['mark attendance', 'attendance', 'حاضری', 'check in', 'checkin', 'clock in', 'log in', 'sign in'];" +
            "  var elements = document.querySelectorAll('button, input[type=\"button\"], input[type=\"submit\"], a, [role=\"button\"], .btn, [class*=\"btn\"], [class*=\"button\"]');" +
            "  for (var i = 0; i < elements.length; i++) {" +
            "    var el = elements[i];" +
            "    var text = (el.textContent || el.value || el.innerText || '').toLowerCase().trim();" +
            "    for (var k = 0; k < keywords.length; k++) {" +
            "      if (text.indexOf(keywords[k]) !== -1) {" +
            "        el.click();" +
            "        return 'clicked:' + text;" +
            "      }" +
            "    }" +
            "  }" +
            "  return 'notfound';" +
            "})();";

        view.evaluateJavascript(js, result -> {
            Log.d(TAG, "JS Result: " + result);
            if (result != null && result.contains("clicked")) {
                // Button found and clicked
                handler.postDelayed(() -> confirmAttendance(result), 3000);
            } else {
                // Try scrolling down and retry
                if (retryCount < MAX_RETRY) {
                    retryCount++;
                    scrollAndRetry(view);
                } else {
                    notifyUser("نہیں ملا", "Mark Attendance بٹن نہیں ملا");
                    recordFailure();
                    stopSelf();
                }
            }
        });
    }

    void scrollAndRetry(WebView view) {
        String scrollJs = "window.scrollTo(0, document.body.scrollHeight/2);";
        view.evaluateJavascript(scrollJs, null);
        handler.postDelayed(() -> findAndClickButton(view), 2000);
    }

    void confirmAttendance(String buttonText) {
        // Check if page changed (success confirmation)
        String checkJs =
            "(function() {" +
            "  var body = document.body.innerText.toLowerCase();" +
            "  if (body.indexOf('success') !== -1 || body.indexOf('marked') !== -1 || " +
            "      body.indexOf('کامیاب') !== -1 || body.indexOf('لگ گئی') !== -1 || " +
            "      body.indexOf('done') !== -1 || body.indexOf('confirmed') !== -1) {" +
            "    return 'success';" +
            "  }" +
            "  return 'check';" +
            "})();";

        if (webView != null) {
            webView.evaluateJavascript(checkJs, result -> {
                markAttendanceDone();
            });
        } else {
            markAttendanceDone();
        }
    }

    void markAttendanceDone() {
        attendanceDone = true;
        String now = new SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault())
            .format(new Date());

        // Save to prefs
        prefs.edit().putString("lastAttendance", now).apply();

        // Add to history
        String history = prefs.getString("history", "");
        history = history + (history.isEmpty() ? "" : "|") + now;
        prefs.edit().putString("history", history).apply();

        notifyUser("حاضری لگ گئی ✓", "وقت: " + now);

        // Reschedule if daily enabled
        if (prefs.getBoolean("daily", true)) {
            rescheduleForTomorrow();
        }

        // Close service after 2 seconds
        handler.postDelayed(this::stopSelf, 2000);
    }

    void rescheduleForTomorrow() {
        Intent intent = new Intent(this, AlarmReceiver.class);
        intent.setAction("com.attendbot.ATTENDANCE_ALARM");
        intent.putExtra("reschedule", true);
        sendBroadcast(intent);
    }

    void recordFailure() {
        String now = new SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault())
            .format(new Date());
        String history = prefs.getString("history", "");
        history = history + (history.isEmpty() ? "" : "|") + now + " (ناکام)";
        prefs.edit().putString("history", history).apply();
    }

    void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "AttendBot", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("حاضری bot کی اطلاعات");
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(channel);
        }
    }

    Notification buildNotification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AttendBot")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_today)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
    }

    void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(1, buildNotification(text));
    }

    void notifyUser(String title, String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_today)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build();
        nm.notify(2, n);
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
    }
}
