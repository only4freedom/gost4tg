package com.example.gostproxy;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 启动后台服务
        startService(new Intent(this, ProxyService.class));
        // 模拟最小化
        moveTaskToBack(true);
    }
}
