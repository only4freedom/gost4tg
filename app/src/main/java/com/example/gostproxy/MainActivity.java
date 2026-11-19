package com.example.gostproxy;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.text.InputType;

public class MainActivity extends Activity {
    private static final String PREFS_NAME = "gost_settings";
    private static final String PREF_AUTO_START = "auto_start_enabled";
    private static final String PREF_BATTERY_PROMPT_SHOWN = "battery_prompt_shown";
    private static final String PREF_LISTEN_PORT = "listen_port";

    private TextView logView;
    private ScrollView scrollView;
    private EditText portInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // 设置项放在日志区上方。
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(16, 12, 16, 12);
        root.setBackgroundColor(0xFF000000);

        CheckBox autoStart = new CheckBox(this);
        autoStart.setText("手机开机后自动启动 Gost");
        autoStart.setTextColor(0xFFFFFFFF);
        autoStart.setChecked(getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(PREF_AUTO_START, false));
        autoStart.setOnCheckedChangeListener((button, checked) ->
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                        .putBoolean(PREF_AUTO_START, checked)
                        .apply());
        root.addView(autoStart, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView portLabel = new TextView(this);
        portLabel.setText("本地监听端口（1024–65535）");
        portLabel.setTextColor(0xFFFFFFFF);
        root.addView(portLabel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        portInput = new EditText(this);
        portInput.setSingleLine(true);
        portInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        portInput.setHint("默认 1080");
        portInput.setTextColor(0xFFFFFFFF);
        portInput.setHintTextColor(0xFFAAAAAA);
        int savedPort = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getInt(PREF_LISTEN_PORT, 1080);
        portInput.setText(String.valueOf(savedPort));
        root.addView(portInput, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        Button savePortButton = new Button(this);
        savePortButton.setText("保存并重启代理");
        savePortButton.setOnClickListener(v -> savePortAndRestart());
        root.addView(savePortButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        Button batterySettings = new Button(this);
        batterySettings.setText("电池优化设置");
        batterySettings.setOnClickListener(v -> requestBatteryOptimizationExemption());
        root.addView(batterySettings, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // 简单的黑底日志窗口。
        scrollView = new ScrollView(this);
        logView = new TextView(this);
        logView.setText("准备启动服务...\n");
        logView.setTextColor(0xFF00FF00); // 绿色文字
        logView.setBackgroundColor(0xFF000000); // 黑色背景
        logView.setPadding(20, 20, 20, 20);
        scrollView.addView(logView);
        root.addView(scrollView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);

        // 注册日志接收器
        IntentFilter filter = new IntentFilter("com.example.gostproxy.LOG");
        registerReceiver(logReceiver, filter); // 注意：targetSdk 28 允许隐式广播

        // 前台服务负责维持 Gost 进程。
        startGostService(this, new Intent(this, ProxyService.class));

        // 只在尚未处理过且仍受电池优化限制时提示；用户也可随时点设置按钮。
        maybePromptBatteryOptimization();
    }

    private void savePortAndRestart() {
        String value = portInput.getText().toString().trim();
        final int port;
        try {
            port = Integer.parseInt(value);
        } catch (NumberFormatException invalidPort) {
            portInput.setError("请输入有效端口");
            return;
        }

        if (port < 1024 || port > 65535) {
            portInput.setError("端口范围应为 1024–65535");
            return;
        }

        boolean saved = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putInt(PREF_LISTEN_PORT, port)
                .commit();
        if (!saved) {
            Toast.makeText(this, "保存端口失败", Toast.LENGTH_LONG).show();
            return;
        }

        Toast.makeText(this, "端口已保存，正在重启代理", Toast.LENGTH_SHORT).show();
        Intent restartIntent = new Intent(this, ProxyService.class);
        restartIntent.setAction(ProxyService.ACTION_RESTART);
        startGostService(this, restartIntent);
    }

    private void maybePromptBatteryOptimization() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;

        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager == null || powerManager.isIgnoringBatteryOptimizations(getPackageName())) return;

        android.content.SharedPreferences preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (preferences.getBoolean(PREF_BATTERY_PROMPT_SHOWN, false)) return;
        preferences.edit().putBoolean(PREF_BATTERY_PROMPT_SHOWN, true).apply();

        new AlertDialog.Builder(this)
                .setTitle("允许后台持续运行")
                .setMessage("为避免系统在后台停止 Gost 代理，建议允许本应用不受电池优化限制。此设置可能增加耗电。")
                .setPositiveButton("去设置", (dialog, which) -> requestBatteryOptimizationExemption())
                .setNegativeButton("暂不", null)
                .show();
    }

    private void requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;

        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager == null || powerManager.isIgnoringBatteryOptimizations(getPackageName())) return;

        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception requestUnavailable) {
            Intent intent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            startActivity(intent);
        }
    }

    static void startGostService(Context context, Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    private final BroadcastReceiver logReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String log = intent.getStringExtra("msg");
            if (log != null) {
                logView.append(log + "\n");
                scrollView.fullScroll(ScrollView.FOCUS_DOWN);
            }
        }
    };

    @Override
    protected void onDestroy() {
        unregisterReceiver(logReceiver);
        super.onDestroy();
    }
}
