package com.zainli.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.OutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class BarberNotificationService extends Service {
    private static final String PREFS = "zainli_native";
    private static final String SERVICE_CHANNEL = "zainli_background";
    private static final String BOOKING_CHANNEL = "zainli_queue";
    private static final String SUPABASE_URL = "https://lxdqwwdhjgxnhysxlodi.supabase.co";
    private static final String SUPABASE_KEY = "sb_publishable_hfX5zq7_74L1esoHkBqlhg_b7mVr0Hg";

    private ScheduledExecutorService scheduler;
    private SharedPreferences prefs;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        createChannels();
        startForeground(7001, buildForegroundNotification());

        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleWithFixedDelay(this::pollBookings, 1, 6, TimeUnit.SECONDS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm == null) return;

            NotificationChannel serviceChannel = new NotificationChannel(
                SERVICE_CHANNEL, "زينلي - تنبيهات الحلاق", NotificationManager.IMPORTANCE_LOW
            );
            serviceChannel.setDescription("يبقي تنبيهات الحجوزات الجديدة فعالة بالخلفية");
            nm.createNotificationChannel(serviceChannel);

            NotificationChannel bookingChannel = new NotificationChannel(
                BOOKING_CHANNEL, "زينلي - الحجوزات", NotificationManager.IMPORTANCE_HIGH
            );
            bookingChannel.setDescription("تنبيهات الحجوزات الجديدة");
            nm.createNotificationChannel(bookingChannel);
        }
    }

    private Notification buildForegroundNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
            this, 7001, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(this, SERVICE_CHANNEL)
            : new Notification.Builder(this);

        return b.setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("زينلي")
            .setContentText("تنبيهات حجوزات الحلاق فعالة")
            .setOngoing(true)
            .setContentIntent(pi)
            .build();
    }

    private void pollBookings() {
        try {
            String token = prefs.getString("barber_token", "");
            if (token == null || token.isEmpty()) {
                stopSelf();
                return;
            }

            URL url = new URL(SUPABASE_URL + "/rest/v1/rpc/z_barber_dashboard");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setDoOutput(true);
            conn.setRequestProperty("apikey", SUPABASE_KEY);
            conn.setRequestProperty("Authorization", "Bearer " + SUPABASE_KEY);
            conn.setRequestProperty("Content-Type", "application/json");

            String body = "{\"p_token\":\"" + token.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            BufferedReader br = new BufferedReader(new InputStreamReader(
                code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream(),
                StandardCharsets.UTF_8
            ));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            conn.disconnect();

            String response = sb.toString();

            if (code < 200 || code >= 300) {
                if (response.contains("INVALID_SESSION")) {
                    prefs.edit().clear().apply();
                    stopSelf();
                }
                return;
            }

            JSONObject dash = new JSONObject(response);
            JSONArray queue = dash.optJSONArray("queue");
            if (queue == null) return;

            long storedLast = prefs.getLong("last_booking_id", -1L);
            long maxBookingId = storedLast;
            int newestIndex = -1;
            JSONObject newestBooking = null;

            for (int i = 0; i < queue.length(); i++) {
                JSONObject q = queue.optJSONObject(i);
                if (q == null) continue;
                if (!"booking".equals(q.optString("source"))) continue;
                long id = q.optLong("id", 0);
                if (id > maxBookingId) maxBookingId = id;
                if (storedLast >= 0 && id > storedLast) {
                    if (newestBooking == null || id > newestBooking.optLong("id", 0)) {
                        newestBooking = q;
                        newestIndex = i;
                    }
                }
            }

            if (storedLast < 0) {
                prefs.edit().putLong("last_booking_id", maxBookingId).apply();
                return;
            }

            if (newestBooking != null) {
                String customer = newestBooking.optString("customer_name", "زبون جديد");
                String serviceKey = newestBooking.optString("service_key", "");
                String service = serviceName(serviceKey);
                int position = newestIndex + 1;
                showBookingNotification(customer, service, position);
            }

            if (maxBookingId > storedLast) {
                prefs.edit().putLong("last_booking_id", maxBookingId).apply();
            }
        } catch (Exception ignored) {
        }
    }

    private String serviceName(String key) {
        if ("hairBeard".equals(key)) return "شعر + لحية";
        if ("shape".equals(key)) return "تحديد";
        if ("care".equals(key)) return "شعر + لحية + عناية";
        return "خدمة";
    }

    private void showBookingNotification(String customer, String service, int position) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
            this, (int)(System.currentTimeMillis() % 100000), intent,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(this, BOOKING_CHANNEL)
            : new Notification.Builder(this);

        String body = customer + " حجز " + service + " · الدور " + position;
        Notification n = b.setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("🔔 حجز جديد")
            .setContentText(body)
            .setStyle(new Notification.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build();

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify((int)(System.currentTimeMillis() % 100000), n);
    }

    @Override
    public void onDestroy() {
        if (scheduler != null) scheduler.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}