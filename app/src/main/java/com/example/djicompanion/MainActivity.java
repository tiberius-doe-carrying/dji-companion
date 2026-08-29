package com.example.djicompanion;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public final class MainActivity extends Activity {
    private TextView status;
    private EditText serverUrl;
    private EditText deviceId;
    private EditText accessToken;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(buildUi());
    }

    @Override protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(24));
        scroll.addView(root);

        TextView title = text("T100 伴随助手", 26, Color.rgb(13, 37, 56));
        root.addView(title);
        TextView note = text("原型仅做页面识别和安全导航。锁机、解锁、起飞、任务执行、返航等动作必须在官方 App 内由操作员确认。", 15, Color.DKGRAY);
        note.setPadding(0, dp(10), 0, dp(16));
        root.addView(note);

        status = text("正在检查…", 14, Color.rgb(30, 80, 110));
        status.setPadding(dp(12), dp(12), dp(12), dp(12));
        status.setBackgroundColor(Color.rgb(232, 244, 250));
        root.addView(status, matchWrap());

        TextView remoteTitle = text("成熟系统接入", 18, Color.rgb(13, 37, 56));
        remoteTitle.setPadding(0, dp(20), 0, dp(6));
        root.addView(remoteTitle);
        serverUrl = input("服务器地址，例如 https://example.com", "server_url");
        deviceId = input("设备编号，例如 rc-t100-001", "device_id");
        accessToken = input("设备访问令牌", "access_token");
        root.addView(serverUrl); root.addView(deviceId); root.addView(accessToken);
        root.addView(button("保存系统服务配置", v -> saveCommandConfig(true)));
        root.addView(button("启动命令接收", v -> startCommandService()));
        root.addView(button("停止命令接收", v -> stopService(new Intent(this, CommandPollService.class))));

        root.addView(button("1. 打开无障碍设置", v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));
        root.addView(button("2. 启动 DJI SmartFarm", v -> launchDji(DjiAccessibilityService.SMARTFARM_PACKAGE)));
        root.addView(button("启动 DJI Agras", v -> launchDji(DjiAccessibilityService.AGRAS_PACKAGE)));
        root.addView(button("测试 agworkflow:// Deep Link", v -> launchDeepLink()));
        root.addView(button("读取 SmartFarm 当前页面", v -> inspectPage(DjiAccessibilityService.SMARTFARM_PACKAGE)));
        root.addView(button("读取 Agras 当前页面", v -> inspectPage(DjiAccessibilityService.AGRAS_PACKAGE)));
        TextView navTitle = text("DJI SmartFarm 底部导航", 18, Color.rgb(13, 37, 56));
        navTitle.setPadding(0, dp(20), 0, dp(4));
        root.addView(navTitle);
        root.addView(button("打开数据页", v -> safeClick("数据")));
        root.addView(button("打开作业页", v -> safeClick("作业")));
        root.addView(button("打开设备页", v -> safeClick("设备")));
        root.addView(button("打开我的页", v -> safeClick("我的")));

        TextView outputTitle = text("页面检查结果", 18, Color.rgb(13, 37, 56));
        outputTitle.setPadding(0, dp(20), 0, dp(8));
        root.addView(outputTitle);
        TextView output = new TextView(this);
        output.setId(android.R.id.text1);
        output.setTextIsSelectable(true);
        output.setTextSize(12);
        output.setText("开启无障碍服务并打开官方 App 后，点击读取官方 App 当前页面。");
        output.setPadding(dp(12), dp(12), dp(12), dp(12));
        output.setBackgroundColor(Color.rgb(245, 245, 245));
        root.addView(output, matchWrap());
        return scroll;
    }

    private void launchDji(String packageName) {
        Intent launch = getPackageManager().getLaunchIntentForPackage(packageName);
        if (launch == null) {
            toast("未找到 " + packageName + "，请确认官方 App 已安装");
            return;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(launch);
    }

    private void launchDeepLink() {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("agworkflow://"));
            intent.setPackage(DjiAccessibilityService.SMARTFARM_PACKAGE);
            startActivity(intent);
        } catch (Exception e) {
            toast("Deep Link 启动失败：" + e.getClass().getSimpleName());
        }
    }

    private void inspectPage(String packageName) {
        DjiAccessibilityService service = DjiAccessibilityService.instance();
        if (service == null) {
            toast("无障碍服务未启用");
            return;
        }
        String report = service.inspectCurrentPage(packageName);
        ((TextView) findViewById(android.R.id.text1)).setText(report);
    }

    private void safeClick(String label) {
        DjiAccessibilityService service = DjiAccessibilityService.instance();
        if (service == null) {
            toast("请先开启无障碍服务");
            return;
        }
        DjiAccessibilityService.ClickResult result = service.clickSafeText(DjiAccessibilityService.SMARTFARM_PACKAGE, label);
        toast(result.message);
    }

    private void refreshStatus() {
        status.setText("DJI SmartFarm：" + (isInstalled(DjiAccessibilityService.SMARTFARM_PACKAGE) ? "已安装" : "未安装")
                + "\nDJI Agras：" + (isInstalled(DjiAccessibilityService.AGRAS_PACKAGE) ? "已安装" : "未安装")
                + "\n无障碍服务：" + (isServiceEnabled() ? "已启用" : "未启用"));
    }

    private boolean isInstalled(String packageName) {
        try { getPackageManager().getPackageInfo(packageName, 0); return true; }
        catch (Exception ignored) { return false; }
    }

    private EditText input(String hint, String preferenceKey) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setSingleLine(true);
        input.setText(getSharedPreferences("command_config", MODE_PRIVATE).getString(preferenceKey, ""));
        return input;
    }

    private void startCommandService() {
        SharedPreferences config = getSharedPreferences("command_config", MODE_PRIVATE);
        String url = config.getString("server_url", "");
        String id = config.getString("device_id", "");
        String token = config.getString("access_token", "");
        if (!isAllowedServerUrl(url) || id.isEmpty() || token.isEmpty()) {
            toast("请先填写并保存系统服务配置");
            return;
        }
        Intent intent = new Intent(this, CommandPollService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent); else startService(intent);
        toast("命令接收服务已启动");
    }

    private boolean saveCommandConfig(boolean showConfirmation) {
        String url = serverUrl.getText().toString().trim().replaceAll("/+$", "");
        String id = deviceId.getText().toString().trim();
        String token = accessToken.getText().toString().trim();
        if (!isAllowedServerUrl(url)) {
            toast("服务地址无效：请输入 HTTPS 或局域网 HTTP 地址");
            serverUrl.requestFocus();
            return false;
        }
        if (id.isEmpty()) {
            toast("设备编号不能为空");
            deviceId.requestFocus();
            return false;
        }
        if (token.isEmpty()) {
            toast("设备访问令牌不能为空");
            accessToken.requestFocus();
            return false;
        }
        getSharedPreferences("command_config", MODE_PRIVATE).edit()
                .putString("server_url", url)
                .putString("device_id", id)
                .putString("access_token", token)
                .apply();
        serverUrl.setText(url);
        if (showConfirmation) {
            new AlertDialog.Builder(this)
                    .setTitle("保存成功")
                    .setMessage("系统服务配置已保存到本机。\n\n服务地址：" + url + "\n设备编号：" + id)
                    .setPositiveButton("确定", null)
                    .show();
        }
        return true;
    }

    private boolean isAllowedServerUrl(String value) {
        try {
            Uri uri = Uri.parse(value);
            if ("https".equalsIgnoreCase(uri.getScheme())) return uri.getHost() != null;
            if (!"http".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) return false;
            String host = uri.getHost();
            return "localhost".equals(host) || "127.0.0.1".equals(host)
                    || host.startsWith("192.168.") || host.startsWith("10.")
                    || host.matches("172\\.(1[6-9]|2[0-9]|3[01])\\..*");
        } catch (Exception ignored) { return false; }
    }

    private boolean isServiceEnabled() {
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

    private Button button(String label, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(listener);
        LinearLayout.LayoutParams p = matchWrap();
        p.topMargin = dp(10);
        b.setLayoutParams(p);
        return b;
    }

    private TextView text(String value, int sp, int color) {
        TextView v = new TextView(this);
        v.setText(value); v.setTextSize(sp); v.setTextColor(color);
        return v;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(-1, -2);
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }
}
