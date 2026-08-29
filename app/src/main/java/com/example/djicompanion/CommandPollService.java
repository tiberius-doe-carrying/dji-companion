package com.example.djicompanion;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.ContentValues;
import android.content.ContentResolver;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.provider.MediaStore;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
                try { reportStatus(base, token, deviceId); } catch (Exception ignored) { }
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
            } else if ("DOWNLOAD_PRESCRIPTION".equals(type)) {
                message = downloadPrescription(base, token, payload);
                success = true;
                launchDji(DjiAccessibilityService.AGRAS_PACKAGE);
            } else if ("IMPORT_PRESCRIPTION".equals(type)) {
                DjiAccessibilityService.ClickResult result = importPrescription(payload);
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

    private String downloadPrescription(String base, String token, JSONObject payload) throws Exception {
        String id = payload.optString("prescriptionId", "").trim();
        String fileName = payload.optString("fileName", "").trim();
        String expectedHash = payload.optString("sha256", "").trim().toLowerCase();
        if (id.isEmpty() || fileName.isEmpty() || expectedHash.length() != 64 || fileName.contains("/") || fileName.contains("\\")) {
            throw new SecurityException("处方图下载参数无效");
        }
        HttpURLConnection connection = open(base + "/api/device/prescriptions/" + URLEncoder.encode(id, "UTF-8"), token, "GET");
        if (connection.getResponseCode() != 200) throw new IllegalStateException("处方图下载 HTTP " + connection.getResponseCode());
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        Uri destination = null;
        File legacyFile = null;
        OutputStream output;
        ContentResolver resolver = getContentResolver();
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
            values.put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream");
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/DJI-Prescriptions");
            values.put(MediaStore.Downloads.IS_PENDING, 1);
            destination = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (destination == null) throw new IllegalStateException("无法创建公共下载文件");
            output = resolver.openOutputStream(destination, "w");
        } else {
            File directory = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "DJI-Prescriptions");
            if (!directory.exists() && !directory.mkdirs()) throw new IllegalStateException("无法创建下载目录");
            legacyFile = new File(directory, fileName);
            output = new FileOutputStream(legacyFile);
        }
        if (output == null) throw new IllegalStateException("无法打开处方图目标文件");
        try (InputStream input = connection.getInputStream(); OutputStream target = output) {
            byte[] buffer = new byte[32 * 1024]; int count;
            while ((count = input.read(buffer)) != -1) { target.write(buffer, 0, count); digest.update(buffer, 0, count); }
        } catch (Exception error) {
            if (destination != null) resolver.delete(destination, null, null);
            if (legacyFile != null) legacyFile.delete();
            throw error;
        } finally { connection.disconnect(); }
        String actualHash = toHex(digest.digest());
        if (!expectedHash.equals(actualHash)) {
            if (destination != null) resolver.delete(destination, null, null);
            if (legacyFile != null) legacyFile.delete();
            throw new SecurityException("处方图 SHA-256 校验失败");
        }
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues ready = new ContentValues(); ready.put(MediaStore.Downloads.IS_PENDING, 0);
            resolver.update(destination, ready, null, null);
            return "处方图已保存到 Download/DJI-Prescriptions/" + fileName + "；已启动 DJI Agras，请通过接口读取导入页面";
        }
        return "处方图已保存到伴随 App 下载目录：" + legacyFile.getAbsolutePath() + "；Android 10 以下需手动复制到 Agras 可见目录";
    }

    private DjiAccessibilityService.ClickResult importPrescription(JSONObject payload) {
        DjiAccessibilityService service = requireAccessibility();
        String fileName = payload.optString("fileName", "").trim();
        String entryText = payload.optString("entryText", "导入").trim();
        long timeout = Math.max(1000, Math.min(payload.optLong("timeoutMs", 15000), 60000));
        if (fileName.isEmpty() || fileName.contains("/") || fileName.contains("\\")) {
            return new DjiAccessibilityService.ClickResult(false, "处方图文件名无效");
        }
        DjiAccessibilityService.ClickResult step = service.clickSafeText(DjiAccessibilityService.AGRAS_PACKAGE, entryText);
        if (!step.success) return new DjiAccessibilityService.ClickResult(false, "请先进入 Agras 处方图导入页面：" + step.message);
        step = service.waitForPage(null, fileName, "", timeout);
        if (!step.success) return new DjiAccessibilityService.ClickResult(false, "文件选择器未找到：" + fileName);
        step = service.clickSafeText(null, fileName);
        if (!step.success) return step;

        DjiAccessibilityService.ClickResult settings = service.waitForPage(DjiAccessibilityService.AGRAS_PACKAGE, "处方图导入设置", "", 8000);
        if (!settings.success) return new DjiAccessibilityService.ClickResult(true, "已选择处方图文件，官方 App 未显示导入设置，请读取当前页面确认结果");
        String sourceId = "other".equalsIgnoreCase(payload.optString("source", "dji")) ? "unitOther" : "unitDJI";
        String unitId = "ha".equalsIgnoreCase(payload.optString("unit", "mu")) ? "unitHa" : "unitMu";
        String sampleId = "average".equalsIgnoreCase(payload.optString("resample", "max")) ? "averageResampleTypeItem" : "maxResampleTypeItem";
        step = service.clickSafeResourceId(DjiAccessibilityService.AGRAS_PACKAGE, "com.dji.agrasx:id/" + sourceId);
        if (!step.success) return step;
        step = service.clickSafeResourceId(DjiAccessibilityService.AGRAS_PACKAGE, "com.dji.agrasx:id/" + unitId);
        if (!step.success) return step;
        step = service.clickSafeResourceId(DjiAccessibilityService.AGRAS_PACKAGE, "com.dji.agrasx:id/" + sampleId);
        if (!step.success) return step;
        step = service.clickSafeResourceId(DjiAccessibilityService.AGRAS_PACKAGE, "com.dji.agrasx:id/btnConfirm");
        return step.success ? new DjiAccessibilityService.ClickResult(true, "处方图导入已确认；未执行航线上传或任务操作") : step;
    }

    private String toHex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) out.append(String.format(java.util.Locale.US, "%02x", value & 0xff));
        return out.toString();
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

    private void reportStatus(String base, String token, String deviceId) throws Exception {
        HttpURLConnection connection = open(base + "/api/device/status", token, "POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        JSONObject body = new JSONObject()
                .put("deviceId", deviceId)
                .put("foregroundService", true)
                .put("accessibilityEnabled", DjiAccessibilityService.instance() != null)
                .put("agrasInstalled", getPackageManager().getLaunchIntentForPackage(DjiAccessibilityService.AGRAS_PACKAGE) != null)
                .put("companionVersion", "0.1.0")
                .put("androidVersion", Build.VERSION.RELEASE);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        int code = connection.getResponseCode();
        connection.disconnect();
        if (code != 200) throw new IllegalStateException("状态上报 HTTP " + code);
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
