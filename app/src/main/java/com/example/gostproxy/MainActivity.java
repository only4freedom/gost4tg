package com.example.gostproxy;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {
    private TextView logView;
    private ScrollView scrollView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // 简单的界面：一个黑色背景的日志窗口
        scrollView = new ScrollView(this);
        logView = new TextView(this);
        logView.setText("准备启动服务...\n");
        logView.setTextColor(0xFF00FF00); // 绿色文字
        logView.setBackgroundColor(0xFF000000); // 黑色背景
        logView.setPadding(20, 20, 20, 20);
        scrollView.addView(logView);
        setContentView(scrollView);

        // 注册日志接收器
        IntentFilter filter = new IntentFilter("com.example.gostproxy.LOG");
        registerReceiver(logReceiver, filter); // 注意：targetSdk 28 允许隐式广播

        // 启动服务
        startService(new Intent(this, ProxyService.class));
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
