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
    public static final String SMARTFARM_PACKAGE = "com.dji.agflow";
    public static final String AGRAS_PACKAGE = "com.dji.agrasx";
    public static final String DOCUMENTS_PACKAGE = "com.android.documentsui";
    public static final String GOOGLE_DOCUMENTS_PACKAGE = "com.google.android.documentsui";
    private static WeakReference<DjiAccessibilityService> current = new WeakReference<>(null);
    private static final Set<String> BLOCKED = new HashSet<>(Arrays.asList(
            "锁定", "解锁", "确认锁定", "确认解锁", "起飞", "开始任务", "执行任务", "返航", "降落",
            "紧急停止", "喷洒", "播撒", "转让", "删除", "lock", "unlock", "takeoff", "take_off",
            "startmission", "start_mission", "returntohome", "return_to_home", "landing", "land",
            "emergencystop", "emergency_stop", "spray", "spread", "transfer", "delete"));

    public static DjiAccessibilityService instance() { return current.get(); }
    public static boolean isSupportedPackage(String value) { return SMARTFARM_PACKAGE.equals(value) || AGRAS_PACKAGE.equals(value)
            || DOCUMENTS_PACKAGE.equals(value) || GOOGLE_DOCUMENTS_PACKAGE.equals(value); }
    public static String displayName(String value) { return AGRAS_PACKAGE.equals(value) ? "DJI Agras" : "DJI SmartFarm"; }
    @Override protected void onServiceConnected() { current = new WeakReference<>(this); }
    @Override public void onDestroy() { current.clear(); super.onDestroy(); }
    @Override public void onInterrupt() { }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }

    public String inspectCurrentPage(String expectedPackage) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "没有可读取的窗口。请先打开官方 DJI App。";
        String error = validateRoot(root, expectedPackage);
        if (error != null) { root.recycle(); return error; }
        StringBuilder out = new StringBuilder("package=").append(root.getPackageName()).append('\n');
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>(); queue.add(root);
        int visited = 0;
        while (!queue.isEmpty() && visited < 300) {
            AccessibilityNodeInfo node = queue.removeFirst(); visited++;
            CharSequence text = node.getText(), desc = node.getContentDescription();
            String id = node.getViewIdResourceName();
            if (hasValue(text) || hasValue(desc) || id != null) {
                out.append(visited).append(". ");
                if (hasValue(text)) out.append("text=").append(text).append(' ');
                if (hasValue(desc)) out.append("desc=").append(desc).append(' ');
                if (id != null) out.append("id=").append(id).append(' ');
                out.append("clickable=").append(node.isClickable()).append('\n');
            }
            for (int i = 0; i < node.getChildCount(); i++) { AccessibilityNodeInfo child = node.getChild(i); if (child != null) queue.addLast(child); }
            if (node != root) node.recycle();
        }
        root.recycle();
        if (visited >= 300) out.append("…控件过多，已在 300 个节点处截断。\n");
        return out.toString();
    }

    public ClickResult clickSafeText(String expectedPackage, String label) {
        if (isSensitive(label)) return blocked(label);
        if (empty(label)) return fail("text 不能为空");
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return fail("没有可操作窗口");
        String error = validateRoot(root, expectedPackage);
        if (error != null) { root.recycle(); return fail(error); }
        List<AccessibilityNodeInfo> matches = root.findAccessibilityNodeInfosByText(label);
        ClickResult result = clickMatches(matches, "文字：\"" + label + "\""); recycle(matches);
        root.recycle(); return result;
    }

    public ClickResult clickSafeResourceId(String expectedPackage, String resourceId) {
        if (isSensitive(resourceId)) return blocked(resourceId);
        if (empty(resourceId)) return fail("resourceId 不能为空");
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return fail("没有可操作窗口");
        String error = validateRoot(root, expectedPackage);
        if (error != null) { root.recycle(); return fail(error); }
        List<AccessibilityNodeInfo> matches = root.findAccessibilityNodeInfosByViewId(resourceId);
        ClickResult result = clickMatches(matches, "资源 ID：\"" + resourceId + "\"");
        recycle(matches); root.recycle(); return result;
    }

    public ClickResult clickSafeRatio(String expectedPackage, float xRatio, float yRatio, String description) {
        if (xRatio < 0 || xRatio > 1 || yRatio < 0 || yRatio > 1) return fail("比例坐标必须在 0 到 1 之间");
        if (isSensitive(description)) return blocked(description);
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return fail("没有可操作窗口");
        String error = validateRoot(root, expectedPackage);
        if (error != null) { root.recycle(); return fail(error); }
        Rect window = new Rect(); root.getBoundsInScreen(window);
        int x = Math.round(window.left + window.width() * xRatio), y = Math.round(window.top + window.height() * yRatio);
        String metadata = nodeMetadata(deepestNodeAt(root, x, y)); root.recycle();
        if (isSensitive(metadata)) return blocked(metadata);
        return dispatchTap(x, y, "已点击比例坐标 (" + xRatio + ", " + yRatio + ")");
    }

    public ClickResult waitForPage(String expectedPackage, String text, String resourceId, long timeoutMillis) {
        if (isSensitive(text) || isSensitive(resourceId)) return blocked(String.valueOf(text) + " " + String.valueOf(resourceId));
        long deadline = System.currentTimeMillis() + Math.max(0, Math.min(timeoutMillis, 60000));
        do {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                boolean found = validateRoot(root, expectedPackage) == null && matchesPage(root, text, resourceId); root.recycle();
                if (found) return new ClickResult(true, "页面已就绪");
            }
            try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return fail("等待页面时被中断"); }
        } while (System.currentTimeMillis() < deadline);
        return fail("等待页面超时");
    }

    public ClickResult performBack(String expectedPackage) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return fail("没有可操作窗口");
        String error = validateRoot(root, expectedPackage); root.recycle();
        if (error != null) return fail(error);
        return performGlobalAction(GLOBAL_ACTION_BACK) ? new ClickResult(true, "已执行返回") : fail("返回操作失败");
    }

    private ClickResult clickMatches(List<AccessibilityNodeInfo> matches, String description) {
        for (AccessibilityNodeInfo match : matches) {
            AccessibilityNodeInfo target = findClickableParent(match);
            if (target != null) {
                String metadata = nodeMetadata(target);
                if (isSensitive(metadata)) return blocked(metadata);
                if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return new ClickResult(true, "已点击" + description);
            }
        }
        return fail("未找到可点击控件（" + description + "）");
    }

    private boolean matchesPage(AccessibilityNodeInfo root, String text, String resourceId) {
        boolean textFound = empty(text), idFound = empty(resourceId);
        if (!textFound) { List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text); textFound = !nodes.isEmpty(); recycle(nodes); }
        if (!idFound) { List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(resourceId); idFound = !nodes.isEmpty(); recycle(nodes); }
        return textFound && idFound;
    }

    private String validateRoot(AccessibilityNodeInfo root, String expectedPackage) {
        String actual = root.getPackageName() == null ? "" : root.getPackageName().toString();
        if (!isSupportedPackage(actual)) return "当前前台不是受支持的 DJI App：" + actual;
        return !empty(expectedPackage) && !expectedPackage.equals(actual) ? "当前前台不是 " + displayName(expectedPackage) + "：" + actual : null;
    }

    private AccessibilityNodeInfo deepestNodeAt(AccessibilityNodeInfo node, int x, int y) {
        AccessibilityNodeInfo best = node; ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>(); queue.add(node);
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo candidate = queue.removeFirst(); Rect bounds = new Rect(); candidate.getBoundsInScreen(bounds);
            if (!bounds.contains(x, y)) continue; best = candidate;
            for (int i = 0; i < candidate.getChildCount(); i++) { AccessibilityNodeInfo child = candidate.getChild(i); if (child != null) queue.add(child); }
        }
        return best;
    }

    private String nodeMetadata(AccessibilityNodeInfo node) {
        StringBuilder value = new StringBuilder();
        for (int depth = 0; depth < 5 && node != null; depth++, node = node.getParent()) {
            if (node.getText() != null) value.append(node.getText()).append(' ');
            if (node.getContentDescription() != null) value.append(node.getContentDescription()).append(' ');
            if (node.getViewIdResourceName() != null) value.append(node.getViewIdResourceName()).append(' ');
        }
        return value.toString();
    }

    private ClickResult dispatchTap(float x, float y, String successMessage) {
        Path path = new Path(); path.moveTo(x, y);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(path, 0, 80)).build();
        CountDownLatch latch = new CountDownLatch(1); boolean[] completed = {false};
        new Handler(Looper.getMainLooper()).post(() -> dispatchGesture(gesture, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription description) { completed[0] = true; latch.countDown(); }
            @Override public void onCancelled(GestureDescription description) { latch.countDown(); }
        }, null));
        try { latch.await(2, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return completed[0] ? new ClickResult(true, successMessage) : fail("点击手势执行失败");
    }

    private boolean isSensitive(String value) {
        String normalized = value == null ? "" : value.toLowerCase().replaceAll("[\\s\\-]", "");
        for (String keyword : BLOCKED) if (normalized.contains(keyword.toLowerCase().replaceAll("[\\s\\-]", ""))) return true;
        return false;
    }
    private ClickResult blocked(String value) { return fail("已拦截高风险动作：\"" + value + "\""); }
    private ClickResult fail(String message) { return new ClickResult(false, message); }
    private boolean empty(String value) { return value == null || value.trim().isEmpty(); }
    private boolean hasValue(CharSequence value) { return value != null && value.length() > 0; }
    private AccessibilityNodeInfo findClickableParent(AccessibilityNodeInfo node) { for (int depth = 0; depth < 5 && node != null; depth++, node = node.getParent()) if (node.isClickable()) return node; return null; }
    private void recycle(List<AccessibilityNodeInfo> nodes) { for (AccessibilityNodeInfo node : nodes) node.recycle(); }
    public static final class ClickResult { public final boolean success; public final String message; ClickResult(boolean success, String message) { this.success = success; this.message = message; } }
}
