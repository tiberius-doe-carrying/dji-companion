package com.example.djicompanion;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class CommandPollService extends Service {
    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "command_receiver";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean running;

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIFICATION_ID, notification("等待成熟系统命令"));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!running) {
            running = true;
            worker.submit(this::pollLoop);
        }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        running = false;
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void pollLoop() {
        while (running) {
            SharedPreferences config = getSharedPreferences("command_config", MODE_PRIVATE);
            String base = config.getString("server_url", "");
            String deviceId = config.getString("device_id", "");
            String token = config.getString("access_token", "");
            if (base.isEmpty() || deviceId.isEmpty() || token.isEmpty()) {
                updateNotification("等待服务器配置");
                sleep(5000); continue;
            }
            try {
                String encoded = URLEncoder.encode(deviceId, "UTF-8");
                HttpURLConnection connection = open(base + "/api/device/commands/next?deviceId=" + encoded, token, "GET");
                int code = connection.getResponseCode();
                if (code == 204) {
                    connection.disconnect(); sleep(3000); continue;
                }
                if (code != 200) throw new IllegalStateException("HTTP " + code);
                JSONObject command = new JSONObject(read(connection.getInputStream()));
                connection.disconnect();
                executeAndAck(base, token, command);
            } catch (Exception error) {
                updateNotification("连接失败：" + error.getClass().getSimpleName());
                sleep(5000);
            }
        }
    }

    private void executeAndAck(String base, String token, JSONObject command) {
        String commandId = command.optString("id", "");
        String type = command.optString("type", "");
        JSONObject payload = command.optJSONObject("payload");
        if (payload == null) payload = new JSONObject();
        boolean success = false;
        String message;
        try {
            String targetPackage = resolvePackage(payload, type);
            if ("OPEN_DJI".equals(type) || "OPEN_AGRAS".equals(type) || "OPEN_APP".equals(type)) {
                success = launchDji(targetPackage);
                message = success ? "已请求启动 " + DjiAccessibilityService.displayName(targetPackage)
                        : DjiAccessibilityService.displayName(targetPackage) + " 未安装或系统阻止后台启动";
            } else if ("OPEN_DEEPLINK".equals(type)) {
                String uri = payload.optString("uri", "");
                if (!uri.startsWith("agworkflow://")) throw new SecurityException("只允许 agworkflow://");
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
                intent.setPackage(targetPackage);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                success = true; message = "已请求打开 Deep Link";
            } else if ("INSPECT_PAGE".equals(type)) {
                DjiAccessibilityService service = DjiAccessibilityService.instance();
                if (service == null) throw new IllegalStateException("无障碍服务未启用");
                if (service.inspectCurrentPage(targetPackage).contains("没有可读取的窗口")) {
                    launchDji(targetPackage);
                    sleep(1500);
                }
                message = service.inspectCurrentPage(targetPackage);
                success = message.startsWith("package=");
            } else if ("CLICK_TEXT".equals(type)) {
                DjiAccessibilityService service = DjiAccessibilityService.instance();
                if (service == null) throw new IllegalStateException("无障碍服务未启用");
                String label = payload.optString("text", "");
                DjiAccessibilityService.ClickResult result = service.clickSafeText(targetPackage, label);
                success = result.success; message = result.message;
            } else if ("CLICK_ID".equals(type)) {
                DjiAccessibilityService service = requireAccessibility();
                DjiAccessibilityService.ClickResult result = service.clickSafeResourceId(targetPackage, payload.optString("resourceId", ""));
                success = result.success; message = result.message;
            } else if ("CLICK_RATIO".equals(type)) {
                DjiAccessibilityService service = requireAccessibility();
                DjiAccessibilityService.ClickResult result = service.clickSafeRatio(targetPackage,
                        (float) payload.optDouble("x", -1), (float) payload.optDouble("y", -1), payload.optString("description", ""));
                success = result.success; message = result.message;
            } else if ("WAIT_PAGE".equals(type)) {
                DjiAccessibilityService service = requireAccessibility();
                DjiAccessibilityService.ClickResult result = service.waitForPage(targetPackage, payload.optString("text", ""),
                        payload.optString("resourceId", ""), payload.optLong("timeoutMs", 10000));
                success = result.success; message = result.message;
            } else if ("BACK".equals(type)) {
                DjiAccessibilityService.ClickResult result = requireAccessibility().performBack(targetPackage);
                success = result.success; message = result.message;
            } else {
                message = "不支持的命令类型：" + type;
            }
        } catch (Exception error) {
            message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        }
        updateNotification((success ? "成功：" : "失败：") + abbreviate(message));
        if (!commandId.isEmpty()) ack(base, token, commandId, success, message);
    }

    private DjiAccessibilityService requireAccessibility() {
        DjiAccessibilityService service = DjiAccessibilityService.instance();
        if (service == null) throw new IllegalStateException("无障碍服务未启用");
        return service;
    }

    private String resolvePackage(JSONObject payload, String type) {
        String app = payload.optString("app", "").trim().toLowerCase();
        if ("OPEN_AGRAS".equals(type) || "agras".equals(app) || DjiAccessibilityService.AGRAS_PACKAGE.equals(app)) {
            return DjiAccessibilityService.AGRAS_PACKAGE;
        }
        if ("smartfarm".equals(app) || DjiAccessibilityService.SMARTFARM_PACKAGE.equals(app)) {
            return DjiAccessibilityService.SMARTFARM_PACKAGE;
        }
        if (app.isEmpty()) return DjiAccessibilityService.AGRAS_PACKAGE;
        throw new IllegalArgumentException("不支持的 app：" + app);
    }

    private boolean launchDji(String packageName) {
        Intent launch = getPackageManager().getLaunchIntentForPackage(packageName);
        if (launch == null) return false;
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(launch);
        return true;
    }

    private void ack(String base, String token, String id, boolean success, String message) {
        HttpURLConnection connection = null;
        try {
            String encoded = URLEncoder.encode(id, "UTF-8");
            connection = open(base + "/api/device/commands/" + encoded + "/ack", token, "POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            JSONObject body = new JSONObject().put("success", success).put("message", message);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            connection.getResponseCode();
        } catch (Exception ignored) {
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private HttpURLConnection open(String target, String token, String method) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(target).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("Authorization", "Bearer " + token);
        connection.setRequestProperty("Accept", "application/json");
        return connection;
    }

    private String read(InputStream stream) throws Exception {
        StringBuilder value = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line; while ((line = reader.readLine()) != null) value.append(line);
        }
        return value.toString();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "成熟系统命令接收", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private Notification notification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        return builder.setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("T100 伴随助手").setContentText(text)
                .setContentIntent(pending).setOngoing(true).build();
    }

    private void updateNotification(String text) {
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(text));
    }
    private String abbreviate(String value) { return value.length() > 80 ? value.substring(0, 80) + "…" : value; }
    private void sleep(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
