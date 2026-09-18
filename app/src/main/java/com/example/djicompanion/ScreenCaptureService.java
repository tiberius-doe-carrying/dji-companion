package com.example.djicompanion;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Captures a user-authorized remote-controller screen frame and uploads only the latest JPEG. */
public final class ScreenCaptureService extends Service {
    public static final String ACTION_START = "com.example.djicompanion.capture.START";
    public static final String ACTION_STOP = "com.example.djicompanion.capture.STOP";
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";
    public static final String EXTRA_WIDTH = "width";
    public static final String EXTRA_HEIGHT = "height";
    public static final String EXTRA_DENSITY = "density";
    private static final String CHANNEL_ID = "screen_capture";
    private static final int NOTIFICATION_ID = 1002;
    private static volatile ScreenCaptureService current;

    private final ExecutorService uploader = Executors.newSingleThreadExecutor();
    private HandlerThread captureThread;
    private Handler captureHandler;
    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private volatile boolean captureRequested;
    private volatile boolean uploadRunning;

    public static boolean isReady() { return current != null && current.projection != null; }

    public static boolean requestScreenshot() {
        ScreenCaptureService service = current;
        if (service == null || service.projection == null) return false;
        service.captureRequested = true;
        return true;
    }

    public static void stopCapture(Context context) {
        ScreenCaptureService service = current;
        if (service != null) service.stopSelf();
    }

    @Override public void onCreate() {
        super.onCreate();
        current = this;
        createChannel();
        startForeground(NOTIFICATION_ID, notification("等待屏幕采集授权"));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        if (ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(intent.getAction()) && projection == null) startProjection(intent);
        return START_NOT_STICKY;
    }

    private void startProjection(Intent intent) {
        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
        Intent resultData = Build.VERSION.SDK_INT >= 33
                ? intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class)
                : intent.getParcelableExtra(EXTRA_RESULT_DATA);
        int width = Math.max(1, intent.getIntExtra(EXTRA_WIDTH, 1280));
        int height = Math.max(1, intent.getIntExtra(EXTRA_HEIGHT, 720));
        int density = Math.max(1, intent.getIntExtra(EXTRA_DENSITY, 160));
        if (resultCode != Activity.RESULT_OK || resultData == null) { stopSelf(); return; }
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        projection = manager.getMediaProjection(resultCode, resultData);
        if (projection == null) { stopSelf(); return; }
        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() { stopSelf(); }
        }, new Handler(getMainLooper()));
        captureThread = new HandlerThread("screen-capture");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        imageReader.setOnImageAvailableListener(this::onImageAvailable, captureHandler);
        virtualDisplay = projection.createVirtualDisplay("AgrasScreenCapture", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader.getSurface(), null, captureHandler);
        // MainActivity 授权完成后会立即切回 Agras，稍等页面稳定再采集第一张。
        captureHandler.postDelayed(() -> captureRequested = true, 2000);
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification("屏幕采集已授权"));
    }

    private void onImageAvailable(ImageReader reader) {
        Image image = reader.acquireLatestImage();
        if (image == null) return;
        try {
            if (!captureRequested || uploadRunning) return;
            captureRequested = false;
            uploadRunning = true;
            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            int rowPadding = rowStride - pixelStride * image.getWidth();
            int bitmapWidth = image.getWidth() + rowPadding / pixelStride;
            Bitmap padded = Bitmap.createBitmap(bitmapWidth, image.getHeight(), Bitmap.Config.ARGB_8888);
            padded.copyPixelsFromBuffer(buffer);
            Bitmap cropped = Bitmap.createBitmap(padded, 0, 0, image.getWidth(), image.getHeight());
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            cropped.compress(Bitmap.CompressFormat.JPEG, 82, output);
            padded.recycle();
            cropped.recycle();
            byte[] jpeg = output.toByteArray();
            uploader.submit(() -> {
                try { upload(jpeg); }
                finally { uploadRunning = false; }
            });
        } finally {
            image.close();
        }
    }

    private void upload(byte[] jpeg) {
        HttpURLConnection connection = null;
        try {
            SharedPreferences config = getSharedPreferences("command_config", MODE_PRIVATE);
            String base = config.getString("server_url", "").replaceAll("/+$", "");
            String deviceId = config.getString("device_id", "").trim();
            String token = config.getString("access_token", "").trim();
            if (base.isEmpty() || deviceId.isEmpty() || token.isEmpty()) return;
            connection = (HttpURLConnection) new URL(base + "/api/device/screenshot?deviceId="
                    + URLEncoder.encode(deviceId, "UTF-8")).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(15000);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(jpeg.length);
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("Content-Type", "image/jpeg");
            try (OutputStream output = connection.getOutputStream()) { output.write(jpeg); }
            connection.getResponseCode();
        } catch (Exception ignored) {
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    @Override public void onDestroy() {
        current = null;
        if (imageReader != null) { imageReader.setOnImageAvailableListener(null, null); imageReader.close(); }
        if (virtualDisplay != null) virtualDisplay.release();
        if (projection != null) projection.stop();
        if (captureThread != null) captureThread.quitSafely();
        uploader.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL_ID, "屏幕画面采集", NotificationManager.IMPORTANCE_LOW));
    }

    private Notification notification(String text) {
        Intent launch = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, launch,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        return builder.setSmallIcon(android.R.drawable.ic_menu_camera).setContentTitle("T100 屏幕采集")
                .setContentText(text).setOngoing(true).setContentIntent(pending).build();
    }
}
