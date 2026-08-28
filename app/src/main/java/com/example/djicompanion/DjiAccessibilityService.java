package com.example.djicompanion;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class DjiAccessibilityService extends AccessibilityService {
    private static final String DJI_PACKAGE = "com.dji.agflow";
    private static WeakReference<DjiAccessibilityService> current = new WeakReference<>(null);

    // These actions may affect aircraft availability, ownership, flight, or physical operation.
    private static final Set<String> BLOCKED = new HashSet<>(Arrays.asList(
            "锁定", "解锁", "确认锁定", "确认解锁", "起飞", "开始任务", "执行任务",
            "返航", "降落", "紧急停止", "喷洒", "播撒", "转让", "删除"
    ));

    public static DjiAccessibilityService instance() { return current.get(); }

    @Override protected void onServiceConnected() { current = new WeakReference<>(this); }
    @Override public void onDestroy() { current.clear(); super.onDestroy(); }
    @Override public void onInterrupt() { }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }

    public String inspectCurrentPage() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "没有可读取的窗口。请先打开 DJI SmartFarm。";
        CharSequence pkg = root.getPackageName();
        if (pkg == null || !DJI_PACKAGE.contentEquals(pkg)) {
            root.recycle();
            return "当前前台不是 DJI SmartFarm：" + pkg;
        }

        StringBuilder out = new StringBuilder("package=").append(pkg).append('\n');
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;
        while (!queue.isEmpty() && visited < 300) {
            AccessibilityNodeInfo node = queue.removeFirst();
            visited++;
            CharSequence text = node.getText();
            CharSequence desc = node.getContentDescription();
            String id = node.getViewIdResourceName();
            if ((text != null && text.length() > 0) || (desc != null && desc.length() > 0) || id != null) {
                out.append(visited).append(". ");
                if (text != null && text.length() > 0) out.append("text=").append(text).append(' ');
                if (desc != null && desc.length() > 0) out.append("desc=").append(desc).append(' ');
                if (id != null) out.append("id=").append(id).append(' ');
                out.append("clickable=").append(node.isClickable()).append('\n');
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.addLast(child);
            }
            if (node != root) node.recycle();
        }
        root.recycle();
        if (visited >= 300) out.append("…控件过多，已在 300 个节点处截断。\n");
        return out.toString();
    }

    public ClickResult clickSafeLabel(String label) {
        if (isSensitive(label)) return new ClickResult(false, "已拦截高风险动作：\"" + label + "\"");
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return new ClickResult(false, "没有可操作窗口");
        CharSequence pkg = root.getPackageName();
        if (pkg == null || !DJI_PACKAGE.contentEquals(pkg)) {
            root.recycle();
            return new ClickResult(false, "当前前台不是 DJI SmartFarm");
        }
        List<AccessibilityNodeInfo> matches = root.findAccessibilityNodeInfosByText(label);
        for (AccessibilityNodeInfo match : matches) {
            AccessibilityNodeInfo target = findClickableParent(match);
            if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                recycle(matches); root.recycle();
                return new ClickResult(true, "已点击：\"" + label + "\"");
            }
        }
        recycle(matches);
        if (isBottomNavigation(label)) {
            Rect bounds = new Rect();
            root.getBoundsInScreen(bounds);
            root.recycle();
            return clickBottomNavigation(label, bounds);
        }
        root.recycle();
        return new ClickResult(false, "未找到可点击控件：\"" + label + "\"");
    }

    public ClickResult waitAndClickSafeLabel(String label, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        ClickResult last = new ClickResult(false, "DJI SmartFarm 页面尚未就绪");
        do {
            last = clickSafeLabel(label);
            if (last.success || !last.message.contains("窗口")) return last;
            try { Thread.sleep(400); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new ClickResult(false, "等待页面时被中断");
            }
        } while (System.currentTimeMillis() < deadline);
        return last;
    }

    private boolean isBottomNavigation(String label) {
        return "数据".equals(label) || "作业".equals(label)
                || "设备".equals(label) || "我的".equals(label);
    }

    private ClickResult clickBottomNavigation(String label, Rect window) {
        if (window.width() <= 0 || window.height() <= 0) {
            return new ClickResult(false, "无法取得 DJI SmartFarm 窗口尺寸");
        }
        float fraction;
        if ("数据".equals(label)) fraction = 0.125f;
        else if ("作业".equals(label)) fraction = 0.375f;
        else if ("设备".equals(label)) fraction = 0.625f;
        else fraction = 0.875f;
        float x = window.left + window.width() * fraction;
        float y = window.top + window.height() * 0.94f;
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 80)).build();
        CountDownLatch latch = new CountDownLatch(1);
        boolean[] completed = {false};
        new Handler(Looper.getMainLooper()).post(() -> dispatchGesture(gesture,
                new GestureResultCallback() {
                    @Override public void onCompleted(GestureDescription description) {
                        completed[0] = true; latch.countDown();
                    }
                    @Override public void onCancelled(GestureDescription description) {
                        latch.countDown();
                    }
                }, null));
        try { latch.await(2, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return completed[0]
                ? new ClickResult(true, "已通过底部导航位置点击：\"" + label + "\"")
                : new ClickResult(false, "底部导航手势执行失败：\"" + label + "\"");
    }

    private boolean isSensitive(String label) {
        String normalized = label == null ? "" : label.trim().replace(" ", "");
        for (String keyword : BLOCKED) {
            if (normalized.contains(keyword)) return true;
        }
        return false;
    }

    private AccessibilityNodeInfo findClickableParent(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo cursor = node;
        for (int depth = 0; depth < 5 && cursor != null; depth++) {
            if (cursor.isClickable()) return cursor;
            cursor = cursor.getParent();
        }
        return null;
    }

    private void recycle(List<AccessibilityNodeInfo> nodes) {
        for (AccessibilityNodeInfo node : nodes) node.recycle();
    }

    public static final class ClickResult {
        public final boolean success;
        public final String message;
        ClickResult(boolean success, String message) { this.success = success; this.message = message; }
    }
}
