package com.example.gostproxy;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // 提示用户服务正在启动
        Toast.makeText(this, "正在启动代理服务...", Toast.LENGTH_LONG).show();
        
        // 启动服务
        startService(new Intent(this, ProxyService.class));
        
        // 延迟 1 秒再后台，让用户看到 Toast，确信不是闪退
        new android.os.Handler().postDelayed(() -> {
            moveTaskToBack(true);
        }, 1000);
    }
}
