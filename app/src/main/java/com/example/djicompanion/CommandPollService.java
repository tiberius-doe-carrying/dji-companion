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
import android.content.ComponentName;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.TextUtils;

import org.json.JSONArray;
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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public final class  CommandPollService extends Service {
    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "command_receiver";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean running;
    private static volatile boolean active;

    public static boolean isRunning() {
        return active;
    }

    @Override public void onCreate() {
        super.onCreate();
        active = true;
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
        active = false;
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
            if (!config.getBoolean("configured", false)
                    || base.isEmpty() || deviceId.isEmpty() || token.isEmpty()) {
                updateNotification("接口控制未配置，命令接收已停止");
                running = false;
                stopSelf();
                break;
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
            } else if ("READ_AGRAS_INVENTORY".equals(type)) {
                DjiAccessibilityService.ClickResult result = readAndReportAgrasInventory(base, token);
                success = result.success;
                message = result.message;
            } else if ("PREPARE_AGRAS_JOB".equals(type)) {
                DjiAccessibilityService.ClickResult result = prepareAgrasJob(payload);
                success = result.success;
                message = result.message;
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

    private DjiAccessibilityService.ClickResult readAndReportAgrasInventory(String base, String token) throws Exception {
        DjiAccessibilityService service = requireAccessibility();
        DjiAccessibilityService.ClickResult step = enterAgrasFileCenter(service);
        if (!step.success) return new DjiAccessibilityService.ClickResult(false, "无法进入 Agras 文件中心，请返回 Agras 首页后重试");

        LinkedHashSet<String> jobs = new LinkedHashSet<>();
        step = service.clickSafeExactText(DjiAccessibilityService.AGRAS_PACKAGE, "作业");
        if (step.success) {
            sleep(800);
            jobs.addAll(service.collectListTexts(DjiAccessibilityService.AGRAS_PACKAGE,
                    "com.dji.agrasx:id/itemNameTv", "com.dji.agrasx:id/contentListView"));
        }
        step = service.clickSafeExactText(DjiAccessibilityService.AGRAS_PACKAGE, "进行中作业");
        if (step.success) {
            sleep(800);
            jobs.addAll(service.collectListTexts(DjiAccessibilityService.AGRAS_PACKAGE,
                    "com.dji.agrasx:id/itemNameTv", "com.dji.agrasx:id/contentListView"));
        }
        step = service.clickSafeExactText(DjiAccessibilityService.AGRAS_PACKAGE, "处方图");
        if (!step.success) return new DjiAccessibilityService.ClickResult(false, "无法打开已导入处方图列表：" + step.message);
        sleep(800);
        step = service.clickSafeExactText(DjiAccessibilityService.AGRAS_PACKAGE, "本地");
        if (!step.success) return new DjiAccessibilityService.ClickResult(false, "无法切换到处方图本地列表：" + step.message);
        sleep(800);
        List<String> prescriptions = service.collectListTexts(DjiAccessibilityService.AGRAS_PACKAGE,
                "com.dji.agrasx:id/itemNameTv", "com.dji.agrasx:id/contentListView");
        postAgrasInventory(base, token, new ArrayList<>(jobs), prescriptions);
        return new DjiAccessibilityService.ClickResult(true,
                "Agras 清单已上报：作业/地块 " + jobs.size() + " 个，已导入处方图 " + prescriptions.size() + " 个");
    }

    private DjiAccessibilityService.ClickResult enterAgrasFileCenter(DjiAccessibilityService service) {
        launchDji(DjiAccessibilityService.AGRAS_PACKAGE);
        sleep(1500);
        DjiAccessibilityService.ClickResult step = service.waitForPage(DjiAccessibilityService.AGRAS_PACKAGE, "",
                "com.dji.agrasx:id/fileTypeSelector", 1500);
        for (int attempt = 0; attempt < 4 && !step.success; attempt++) {
            if (service.waitForPage(DjiAccessibilityService.AGRAS_PACKAGE, "",
                    "com.dji.agrasx:id/iv_icon_home_task", 800).success) {
                step = clickResourceWithRetry(service, "com.dji.agrasx:id/iv_icon_home_task", 3000);
                if (!step.success) return new DjiAccessibilityService.ClickResult(false, "无法打开 Agras 文件中心：" + step.message);
            } else {
                service.performBack(DjiAccessibilityService.AGRAS_PACKAGE);
            }
            step = service.waitForPage(DjiAccessibilityService.AGRAS_PACKAGE, "",
                    "com.dji.agrasx:id/fileTypeSelector", 2500);
        }
        return step;
    }

    private DjiAccessibilityService.ClickResult prepareAgrasJob(JSONObject payload) {
        String jobName = payload.optString("jobName", "").trim();
        String prescriptionName = payload.optString("prescriptionName", "").trim();
        if (jobName.isEmpty() || prescriptionName.isEmpty()) {
            return new DjiAccessibilityService.ClickResult(false, "作业名称和处方图名称不能为空");
        }
        DjiAccessibilityService service = requireAccessibility();
        DjiAccessibilityService.ClickResult step = enterAgrasFileCenter(service);
        if (!step.success) return new DjiAccessibilityService.ClickResult(false, "无法进入 Agras 文件中心");
        step = service.clickSafeExactText(DjiAccessibilityService.AGRAS_PACKAGE, "作业");
        if (!step.success) return new DjiAccessibilityService.ClickResult(false, "无法打开作业列表：" + step.message);
        sleep(600);
        service.clickSafeExactText(DjiAccessibilityService.AGRAS_PACKAGE, "本地");
        sleep(600);
        step = service.clickDescendantForExactText(DjiAccessibilityService.AGRAS_PACKAGE,
                "com.dji.agrasx:id/itemNameTv", jobName, "com.dji.agrasx:id/gotoIv");
        if (!step.success) return new DjiAccessibilityService.ClickResult(false, "未找到作业：" + jobName);
        step = service.waitForPage(DjiAccessibilityService.AGRAS_PACKAGE, "",
                "com.dji.agrasx:id/selectPrecisionRes", 8000);
        if (!step.success) return new DjiAccessibilityService.ClickResult(false,
                "已点击作业，但未进入地图作业页面；请在遥控器确认该作业可打开");
        step = service.clickSafeResourceId(DjiAccessibilityService.AGRAS_PACKAGE,
                "com.dji.agrasx:id/selectPrecisionRes");
        if (!step.success) return new DjiAccessibilityService.ClickResult(false, "无法打开处方图选择面板：" + step.message);
        step = service.waitForPage(DjiAccessibilityService.AGRAS_PACKAGE, "",
                "com.dji.agrasx:id/precisionName", 5000);
        if (!step.success) return new DjiAccessibilityService.ClickResult(false, "处方图选择面板未加载完成");
        step = service.clickListItemByExactText(DjiAccessibilityService.AGRAS_PACKAGE,
                "com.dji.agrasx:id/precisionName", prescriptionName);
        if (!step.success) return new DjiAccessibilityService.ClickResult(false, "未找到已导入处方图：" + prescriptionName);
        step = service.waitForPage(DjiAccessibilityService.AGRAS_PACKAGE, "确定(R3)",
                "com.dji.agrasx:id/textConfirm", 5000);
        if (!step.success) return new DjiAccessibilityService.ClickResult(false, "处方图确认弹窗未加载完成：" + step.message);
        step = service.clickSafeExactText(DjiAccessibilityService.AGRAS_PACKAGE, "确定(R3)");
        if (!step.success) {
            step = service.clickSafeResourceId(DjiAccessibilityService.AGRAS_PACKAGE,
                    "com.dji.agrasx:id/textConfirm");
        }
        if (!step.success) return new DjiAccessibilityService.ClickResult(false, "无法确认处方图选择：" + step.message);
        sleep(800);
        DjiAccessibilityService.ClickResult invoke = service.waitForPage(DjiAccessibilityService.AGRAS_PACKAGE,
                "调用", "", 3000);
        if (invoke.success) {
            invoke = service.clickSafeExactText(DjiAccessibilityService.AGRAS_PACKAGE, "调用");
            if (!invoke.success) return new DjiAccessibilityService.ClickResult(false, "无法点击右下角调用：" + invoke.message);
            sleep(1000);
        }
        step = service.waitForPage(DjiAccessibilityService.AGRAS_PACKAGE, "执行",
                "com.dji.agrasx:id/btnAction", 5000);
        if (!step.success) return new DjiAccessibilityService.ClickResult(false,
                "未找到右下角执行按钮：请确认作业已调用且飞行器已连接");
        step = service.clickSafeExactText(DjiAccessibilityService.AGRAS_PACKAGE, "执行");
        if (!step.success) step = service.clickSafeResourceId(DjiAccessibilityService.AGRAS_PACKAGE,
                "com.dji.agrasx:id/btnAction");
        if (!step.success) return new DjiAccessibilityService.ClickResult(false, "无法点击右下角执行：" + step.message);
        sleep(800);
        if (service.currentPageContains(DjiAccessibilityService.AGRAS_PACKAGE, "无重合区域")
                || service.currentPageContains(DjiAccessibilityService.AGRAS_PACKAGE, "未重合")) {
            return new DjiAccessibilityService.ClickResult(false,
                    "作业与处方图没有重合区域，已停止在警告页面，请勿继续执行");
        }
        return new DjiAccessibilityService.ClickResult(true,
                "作业已调用并点击执行：" + jobName + " → " + prescriptionName + "；请在遥控器继续完成官方安全确认");
    }

    private void postAgrasInventory(String base, String token, List<String> jobs, List<String> prescriptions) throws Exception {
        String deviceId = getSharedPreferences("command_config", MODE_PRIVATE).getString("device_id", "");
        HttpURLConnection connection = open(base + "/api/device/agras-inventory", token, "POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        JSONObject body = new JSONObject().put("deviceId", deviceId)
                .put("jobs", new JSONArray(jobs)).put("prescriptions", new JSONArray(prescriptions));
        try (OutputStream output = connection.getOutputStream()) {
            output.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        int code = connection.getResponseCode();
        connection.disconnect();
        if (code != 200) throw new IllegalStateException("清单上报 HTTP " + code);
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
        JSONArray files = payload.optJSONArray("files");
        if (id.isEmpty() || files == null || files.length() != 2) {
            throw new SecurityException("处方图下载参数无效");
        }
        JSONObject tif = null;
        JSONObject tfw = null;
        for (int index = 0; index < files.length(); index++) {
            JSONObject file = files.optJSONObject(index);
            String name = file == null ? "" : file.optString("fileName", "").trim();
            if (name.toLowerCase(java.util.Locale.US).endsWith(".tif")) tif = file;
            else if (name.toLowerCase(java.util.Locale.US).endsWith(".tfw")) tfw = file;
        }
        if (tif == null || tfw == null || !baseName(tif.optString("fileName")).equals(baseName(tfw.optString("fileName")))) {
            throw new SecurityException("处方图必须包含同名的 .tif 和 .tfw 文件");
        }
        String pairBaseName = baseName(tif.optString("fileName"));
        File sdDirectory = findRemovableSdPrescriptionDirectory();
        SharedPreferences state = getSharedPreferences("prescription_state", MODE_PRIVATE);
        state.edit().putBoolean("sd_ready", false).remove("base_name").remove("tif_name").remove("tfw_name").apply();
        downloadPrescriptionFile(base, token, id, tif, sdDirectory);
        downloadPrescriptionFile(base, token, id, tfw, sdDirectory);
        if (sdDirectory != null) {
            state.edit().putBoolean("sd_ready", true).putString("base_name", pairBaseName)
                    .putString("tif_name", tif.optString("fileName")).putString("tfw_name", tfw.optString("fileName")).apply();
            launchDji(DjiAccessibilityService.AGRAS_PACKAGE);
            return "处方图文件已保存到 SD 卡根目录 DJI/RX/：" + tif.optString("fileName") + "、" + tfw.optString("fileName")
                    + "；已启动 DJI Agras，可执行导入";
        }
        return "未检测到可写的可移除 SD 卡；处方图已保存到遥控器内部 Download/DJI-Prescriptions/，但禁止执行导入";
    }

    private void downloadPrescriptionFile(String base, String token, String id, JSONObject file, File sdDirectory) throws Exception {
        String fileName = file.optString("fileName", "").trim();
        String expectedHash = file.optString("sha256", "").trim().toLowerCase(java.util.Locale.US);
        if (fileName.isEmpty() || expectedHash.length() != 64 || fileName.contains("/") || fileName.contains("\\")) {
            throw new SecurityException("处方图文件下载参数无效");
        }
        HttpURLConnection connection = open(base + "/api/device/prescriptions/" + URLEncoder.encode(id, "UTF-8") + "/"
                + URLEncoder.encode(fileName, "UTF-8"), token, "GET");
        if (connection.getResponseCode() != 200) throw new IllegalStateException("处方图下载 HTTP " + connection.getResponseCode());
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        Uri destination = null;
        File legacyFile = null;
        OutputStream output;
        ContentResolver resolver = getContentResolver();
        if (sdDirectory != null) {
            legacyFile = new File(sdDirectory, fileName);
            output = new FileOutputStream(legacyFile, false);
        } else if (Build.VERSION.SDK_INT >= 29) {
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
        if (destination != null) {
            ContentValues ready = new ContentValues(); ready.put(MediaStore.Downloads.IS_PENDING, 0);
            resolver.update(destination, ready, null, null);
            return;
        }
    }

    private File findRemovableSdRoot() {
        File[] candidates = getExternalFilesDirs(null);
        if (candidates == null) return null;
        for (File candidate : candidates) {
            if (candidate == null || !Environment.isExternalStorageRemovable(candidate)
                    || !Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState(candidate))) continue;
            String path = candidate.getAbsolutePath();
            int marker = path.indexOf(File.separator + "Android" + File.separator + "data" + File.separator);
            if (marker > 0) return new File(path.substring(0, marker));
        }
        return null;
    }

    private File findRemovableSdPrescriptionDirectory() {
        File root = findRemovableSdRoot();
        if (root == null) return null;
        File directory = new File(root, "DJI" + File.separator + "RX");
        if ((!directory.exists() && !directory.mkdirs()) || !directory.isDirectory() || !directory.canWrite()) return null;
        return directory;
    }

    private boolean isPrescriptionImportReady() {
        File directory = findRemovableSdPrescriptionDirectory();
        SharedPreferences state = getSharedPreferences("prescription_state", MODE_PRIVATE);
        String tifName = state.getString("tif_name", "");
        String tfwName = state.getString("tfw_name", "");
        return directory != null && state.getBoolean("sd_ready", false)
                && !tifName.isEmpty() && !tfwName.isEmpty()
                && new File(directory, tifName).isFile() && new File(directory, tfwName).isFile();
    }

    private String baseName(String fileName) {
        String value = fileName == null ? "" : fileName.trim();
        int dot = value.lastIndexOf('.');
        return dot > 0 ? value.substring(0, dot) : "";
    }

    private DjiAccessibilityService.ClickResult importPrescription(JSONObject payload) {
        String fileName = payload.optString("fileName", "").trim();
        String entryText = payload.optString("entryText", "导入").trim();
        long timeout = Math.max(1000, Math.min(payload.optLong("timeoutMs", 15000), 60000));
        if (fileName.isEmpty() || !fileName.toLowerCase(java.util.Locale.US).endsWith(".tif")
                || fileName.contains("/") || fileName.contains("\\")) {
            return new DjiAccessibilityService.ClickResult(false, "处方图文件名无效");
        }
        File sdDirectory = findRemovableSdPrescriptionDirectory();
        SharedPreferences state = getSharedPreferences("prescription_state", MODE_PRIVATE);
        String expectedBaseName = state.getString("base_name", "");
        if (sdDirectory == null || !isPrescriptionImportReady()
                || !baseName(fileName).equals(expectedBaseName)
                || !fileName.equals(state.getString("tif_name", ""))) {
            return new DjiAccessibilityService.ClickResult(false,
                    "未检测到 SD 卡中的完整同名处方图文件对，禁止执行导入；请确认文件位于 SD 卡根目录 DJI/RX/");
        }
        DjiAccessibilityService service = requireAccessibility();
        launchDji(DjiAccessibilityService.AGRAS_PACKAGE);
        String visibleFileName = baseName(fileName);
        DjiAccessibilityService.ClickResult step = ensurePrescriptionSdPage(service, visibleFileName, timeout);
        if (!step.success) return step;
        step = service.clickSafeText(DjiAccessibilityService.AGRAS_PACKAGE, visibleFileName);
        if (!step.success) return new DjiAccessibilityService.ClickResult(false, "无法勾选处方图文件：" + step.message);
        step = service.waitForPage(DjiAccessibilityService.AGRAS_PACKAGE, entryText,
                "com.dji.agrasx:id/positiveBtn", 3000);
        if (!step.success) return new DjiAccessibilityService.ClickResult(false, "勾选文件后未找到导入按钮");
        long importButtonDeadline = System.currentTimeMillis() + 3000;
        do {
            step = service.clickSafeResourceId(DjiAccessibilityService.AGRAS_PACKAGE, "com.dji.agrasx:id/positiveBtn");
            if (step.success) break;
            try { Thread.sleep(250); } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return new DjiAccessibilityService.ClickResult(false, "等待导入按钮时被中断");
            }
        } while (System.currentTimeMillis() < importButtonDeadline);
        if (!step.success) return new DjiAccessibilityService.ClickResult(false, "导入按钮不可点击：" + step.message);

        DjiAccessibilityService.ClickResult settings = service.waitForPage(DjiAccessibilityService.AGRAS_PACKAGE, "处方图导入设置", "", 8000);
        if (!settings.success) return new DjiAccessibilityService.ClickResult(true, "已选择处方图文件，官方 App 未显示导入设置，请读取当前页面确认结果");
        step = clickResourceWithRetry(service, "com.dji.agrasx:id/averageResampleTypeItem", 3000);
        if (!step.success) return new DjiAccessibilityService.ClickResult(false, "无法选择平均值：" + step.message);
        step = clickResourceWithRetry(service, "com.dji.agrasx:id/btnConfirm", 3000);
        return step.success ? new DjiAccessibilityService.ClickResult(true, "已选择平均值并确认处方图导入；未执行航线上传或任务操作") : step;
    }

    private DjiAccessibilityService.ClickResult ensurePrescriptionSdPage(DjiAccessibilityService service,
            String visibleFileName, long timeoutMs) {
        final String packageName = DjiAccessibilityService.AGRAS_PACKAGE;
        DjiAccessibilityService.ClickResult step = service.waitForPage(packageName, visibleFileName,
                "com.dji.agrasx:id/contentListView", 1000);
        if (step.success) return step;

        step = service.waitForPage(packageName, "", "com.dji.agrasx:id/fileTypeSelector", 800);
        if (!step.success) {
            DjiAccessibilityService.ClickResult home = service.waitForPage(packageName, "",
                    "com.dji.agrasx:id/iv_icon_home_task", 800);
            if (!home.success) {
                DjiAccessibilityService.ClickResult close = service.clickSafeResourceId(packageName,
                        "com.dji.agrasx:id/closeBtn");
                if (!close.success) service.performBack(packageName);
                home = service.waitForPage(packageName, "", "com.dji.agrasx:id/iv_icon_home_task", 5000);
                if (!home.success) return new DjiAccessibilityService.ClickResult(false,
                        "无法返回 Agras 首页，请关闭当前弹窗或作业页面后重试");
            }
            step = clickResourceWithRetry(service, "com.dji.agrasx:id/iv_icon_home_task", 3000);
            if (!step.success) return new DjiAccessibilityService.ClickResult(false, "无法打开 Agras 任务管理：" + step.message);
            step = service.waitForPage(packageName, "", "com.dji.agrasx:id/fileTypeSelector", 5000);
            if (!step.success) return new DjiAccessibilityService.ClickResult(false, "Agras 任务管理页面加载超时");
        }

        step = service.clickSafeText(packageName, "处方图");
        if (!step.success) return new DjiAccessibilityService.ClickResult(false, "无法切换到处方图：" + step.message);
        step = service.waitForPage(packageName, "内存卡", "com.dji.agrasx:id/resourceTypeSelector", 3000);
        if (!step.success) return new DjiAccessibilityService.ClickResult(false, "处方图页面未显示内存卡选项");
        step = service.clickSafeText(packageName, "内存卡");
        if (!step.success) return new DjiAccessibilityService.ClickResult(false, "无法切换到内存卡：" + step.message);
        step = service.waitForPage(packageName, visibleFileName, "com.dji.agrasx:id/contentListView", timeoutMs);
        return step.success ? step : new DjiAccessibilityService.ClickResult(false,
                "内存卡处方图列表未找到文件：" + visibleFileName);
    }

    private DjiAccessibilityService.ClickResult clickResourceWithRetry(DjiAccessibilityService service,
            String resourceId, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        DjiAccessibilityService.ClickResult result;
        do {
            result = service.clickSafeResourceId(DjiAccessibilityService.AGRAS_PACKAGE, resourceId);
            if (result.success) return result;
            try { Thread.sleep(250); } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return new DjiAccessibilityService.ClickResult(false, "等待控件时被中断：" + resourceId);
            }
        } while (System.currentTimeMillis() < deadline);
        return result;
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
                .put("accessibilityEnabled", isAccessibilityServiceEnabled())
                .put("accessibilityConnected", DjiAccessibilityService.instance() != null)
                .put("sdCardInserted", findRemovableSdRoot() != null)
                .put("sdCardWritable", findRemovableSdPrescriptionDirectory() != null)
                .put("prescriptionImportReady", isPrescriptionImportReady())
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

    private boolean isAccessibilityServiceEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabled)) return false;
        ComponentName mine = new ComponentName(this, DjiAccessibilityService.class);
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(enabled);
        while (splitter.hasNext()) {
            ComponentName item = ComponentName.unflattenFromString(splitter.next());
            if (mine.equals(item)) return true;
        }
        return false;
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
