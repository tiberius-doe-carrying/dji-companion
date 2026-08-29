package com.example.djicompanion;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public final class MainActivity extends Activity {
    private static final int C_BG = Color.parseColor("#F0F4F8");
    private static final int C_SURFACE = Color.parseColor("#FFFFFF");
    private static final int C_SURFACE_MUTED = Color.parseColor("#F6F8FA");
    private static final int C_PRIMARY = Color.parseColor("#1565A8");
    private static final int C_PRIMARY_DARK = Color.parseColor("#0D2538");
    private static final int C_PRIMARY_LIGHT = Color.parseColor("#E3F2FD");
    private static final int C_ACCENT = Color.parseColor("#00ACC1");
    private static final int C_TEXT = Color.parseColor("#102D3E");
    private static final int C_TEXT_SEC = Color.parseColor("#607D8B");
    private static final int C_BORDER = Color.parseColor("#DEE7EB");
    private static final int C_SUCCESS = Color.parseColor("#2E7D32");
    private static final int C_WARNING = Color.parseColor("#ED6C02");

    private TextView statusSummary;
    private Switch agrasSwitch;
    private Switch accessibilitySwitch;
    private Switch commandSwitch;
    private EditText serverUrl;
    private EditText deviceId;
    private EditText accessToken;
    private boolean updatingSwitches;

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
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(C_BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, 0, 0, dp(28));
        scroll.addView(root);

        root.addView(buildHeader());

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(18), dp(20), dp(18), 0);
        root.addView(body, matchWrap());

        statusSummary = text("正在检查…", 13, C_TEXT_SEC);
        statusSummary.setPadding(dp(14), dp(10), dp(14), dp(10));
        statusSummary.setBackground(cardBackground(C_SURFACE_MUTED));
        body.addView(statusSummary, matchWrap());

        LinearLayout statusCard = card("系统状态", "安装与权限开关，绿色表示就绪");
        body.addView(statusCard);
        agrasSwitch = addSwitchRow(statusCard, "DJI Agras", "官方飞控 App 安装状态", false, null, false);
        accessibilitySwitch = addSwitchRow(statusCard, "无障碍服务", "读取页面并执行安全导航", true,
                this::onAccessibilityToggle, true);
        commandSwitch = addSwitchRow(statusCard, "接口命令接收", "连接服务端并执行远程命令", true,
                this::onCommandToggle, true);

        LinearLayout remoteCard = card("接口控制", "功能操作由服务端命令接口下发");
        body.addView(remoteCard);
        serverUrl = input("服务器地址，例如 https://example.com", "server_url");
        deviceId = input("设备编号，例如 rc-t100-001", "device_id");
        accessToken = input("设备访问令牌", "access_token");
        remoteCard.addView(serverUrl);
        remoteCard.addView(deviceId);
        remoteCard.addView(accessToken);
        remoteCard.addView(button("保存配置", v -> saveCommandConfig(true), false));

        LinearLayout testCard = card("本机测试", "接入检查与页面读取");
        body.addView(testCard);
        testCard.addView(button("启动当前 DJI Agras", v -> launchDji(DjiAccessibilityService.AGRAS_PACKAGE), true));
        testCard.addView(button("读取 Agras 当前页面", v -> inspectPage(DjiAccessibilityService.AGRAS_PACKAGE), false));

        LinearLayout outputCard = card("页面检查结果", "无障碍控件的文字、描述和资源 ID");
        body.addView(outputCard);
        TextView output = new TextView(this);
        output.setId(android.R.id.text1);
        output.setTextIsSelectable(true);
        output.setTextSize(12);
        output.setTextColor(C_TEXT_SEC);
        output.setLineSpacing(0, 1.2f);
        output.setText("开启无障碍服务并打开 DJI Agras 后，点击「读取 Agras 当前页面」。");
        output.setPadding(dp(14), dp(14), dp(14), dp(14));
        output.setBackground(cardBackground(C_SURFACE_MUTED));
        outputCard.addView(output, matchWrap());

        return scroll;
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(22), dp(36), dp(22), dp(24));
        header.setBackground(gradientBackground(C_PRIMARY_DARK, C_PRIMARY));

        TextView badge = text("T100", 11, Color.argb(200, 255, 255, 255));
        badge.setPadding(dp(10), dp(4), dp(10), dp(4));
        badge.setBackground(pillBackground(Color.argb(60, 255, 255, 255)));
        badge.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.addView(badge);

        TextView title = text("DJI 遥控器伴随助手", 26, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setPadding(0, dp(10), 0, 0);
        header.addView(title);

        TextView note = text("连接遥控器上的 DJI Agras，提供页面识别与安全导航。飞行和作业操作始终由操作员在官方 App 内确认。", 13,
                Color.argb(210, 255, 255, 255));
        note.setLineSpacing(0, 1.2f);
        note.setPadding(0, dp(8), 0, 0);
        header.addView(note);

        return header;
    }

    private Switch addSwitchRow(LinearLayout parent, String title, String subtitle, boolean interactive,
            CompoundButton.OnCheckedChangeListener listener, boolean showDivider) {
        if (showDivider) {
            View divider = new View(this);
            divider.setBackgroundColor(C_BORDER);
            parent.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        }

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        TextView heading = text(title, 15, C_TEXT);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        labels.addView(heading);
        TextView hint = text(subtitle, 12, C_TEXT_SEC);
        hint.setPadding(0, dp(2), 0, 0);
        labels.addView(hint);
        row.addView(labels);

        Switch toggle = new Switch(this);
        styleSwitch(toggle);
        toggle.setEnabled(interactive);
        toggle.setFocusable(interactive);
        if (listener != null) toggle.setOnCheckedChangeListener(listener);
        row.addView(toggle);

        if (!interactive) {
            row.setAlpha(0.92f);
        }

        parent.addView(row, matchWrap());
        return toggle;
    }

    private void styleSwitch(Switch toggle) {
        ColorStateList track = new ColorStateList(
                new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{Color.argb(120, 0, 172, 193), Color.parseColor("#CFD8DC")});
        ColorStateList thumb = new ColorStateList(
                new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{C_ACCENT, Color.WHITE});
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            toggle.setTrackTintList(track);
            toggle.setThumbTintList(thumb);
        }
    }

    private void onAccessibilityToggle(CompoundButton button, boolean isChecked) {
        if (updatingSwitches) return;
        boolean enabled = isServiceEnabled();
        if (isChecked == enabled) return;
        updatingSwitches = true;
        accessibilitySwitch.setChecked(enabled);
        updatingSwitches = false;
        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        toast(enabled ? "请在系统设置中关闭无障碍服务" : "请在系统设置中开启「T100 伴随助手」");
    }

    private void onCommandToggle(CompoundButton button, boolean isChecked) {
        if (updatingSwitches) return;
        if (isChecked) {
            if (!saveCommandConfig(false)) {
                updatingSwitches = true;
                commandSwitch.setChecked(false);
                updatingSwitches = false;
                return;
            }
            startCommandService();
        } else {
            stopService(new Intent(this, CommandPollService.class));
            toast("命令接收服务已停止");
        }
        refreshStatus();
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

    private void inspectPage(String packageName) {
        DjiAccessibilityService service = DjiAccessibilityService.instance();
        if (service == null) {
            toast("无障碍服务未启用");
            return;
        }
        String report = service.inspectCurrentPage(packageName);
        ((TextView) findViewById(android.R.id.text1)).setText(report);
    }

    private void refreshStatus() {
        boolean agrasInstalled = isInstalled(DjiAccessibilityService.AGRAS_PACKAGE);
        boolean accessibilityEnabled = isServiceEnabled();
        boolean commandRunning = CommandPollService.isRunning();

        updatingSwitches = true;
        agrasSwitch.setChecked(agrasInstalled);
        accessibilitySwitch.setChecked(accessibilityEnabled);
        commandSwitch.setChecked(commandRunning);
        updatingSwitches = false;

        int readyCount = (agrasInstalled ? 1 : 0) + (accessibilityEnabled ? 1 : 0) + (commandRunning ? 1 : 0);
        String readiness = readyCount == 3 ? "全部就绪，可接收远程命令" : readyCount + " / 3 项就绪";
        int summaryBg = readyCount == 3 ? Color.parseColor("#E8F5E9") : Color.parseColor("#FFF3E0");
        int summaryColor = readyCount == 3 ? C_SUCCESS : C_WARNING;
        statusSummary.setText(readiness);
        statusSummary.setTextColor(summaryColor);
        statusSummary.setBackground(cardBackground(summaryBg));
    }

    private boolean isInstalled(String packageName) {
        try {
            getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private EditText input(String hint, String preferenceKey) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setHintTextColor(C_TEXT_SEC);
        input.setTextColor(C_TEXT);
        input.setSingleLine(true);
        input.setTextSize(14);
        input.setPadding(dp(14), dp(12), dp(14), dp(12));
        input.setBackground(inputBackground());
        input.setText(getSharedPreferences("command_config", MODE_PRIVATE).getString(preferenceKey, ""));
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(10);
        input.setLayoutParams(params);
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
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
        else startService(intent);
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
        } catch (Exception ignored) {
            return false;
        }
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

    private LinearLayout card(String title, String subtitle) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(cardBackground(C_SURFACE));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            card.setElevation(dp(2));
        }
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(16);
        card.setLayoutParams(params);

        TextView heading = text(title, 17, C_TEXT);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        card.addView(heading);

        TextView hint = text(subtitle, 12, C_TEXT_SEC);
        hint.setPadding(0, dp(4), 0, dp(10));
        card.addView(hint);
        return card;
    }

    private GradientDrawable cardBackground(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(14));
        drawable.setStroke(dp(1), C_BORDER);
        return drawable;
    }

    private GradientDrawable inputBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(C_SURFACE_MUTED);
        drawable.setCornerRadius(dp(10));
        drawable.setStroke(dp(1), C_BORDER);
        return drawable;
    }

    private GradientDrawable gradientBackground(int start, int end) {
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{start, end});
        return drawable;
    }

    private GradientDrawable pillBackground(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(20));
        return drawable;
    }

    private Button button(String label, View.OnClickListener listener, boolean primary) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTextColor(primary ? Color.WHITE : C_PRIMARY);
        b.setBackgroundTintList(ColorStateList.valueOf(primary ? C_PRIMARY : C_PRIMARY_LIGHT));
        b.setOnClickListener(listener);
        LinearLayout.LayoutParams p = matchWrap();
        p.topMargin = dp(10);
        b.setLayoutParams(p);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            b.setElevation(primary ? dp(2) : 0);
        }
        return b;
    }

    private TextView text(String value, int sp, int color) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(sp);
        v.setTextColor(color);
        return v;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(-1, -2);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_LONG).show();
    }
}
