package com.example.gostproxy;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import gostlib.Gostlib; // 引用生成的库

public class ProxyService extends Service {
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 1. 通知栏保活
        String channelId = "gost_v2_service";
        NotificationChannel channel = new NotificationChannel(channelId, "Gost V2 Proxy", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);

        Notification notification = new Notification.Builder(this, channelId)
                .setContentTitle("Gost V2 运行中")
                .setContentText("正在后台运行...")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build();
        startForeground(1, notification);

        // 2. 启动 Gost V2
        new Thread(() -> {
            try {
                // 复制 assets 里的文件到手机私有目录
                String configPath = copyAsset("gost.json");
                copyAsset("peer.txt"); 
                
                // 启动！
                Gostlib.start(configPath);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();

        return START_STICKY;
    }

    private String copyAsset(String filename) throws Exception {
        File file = new File(getFilesDir(), filename);
        // 每次启动都覆盖，方便你更新配置，如果不想覆盖可以加 if(!file.exists())
        InputStream in = getAssets().open(filename);
        FileOutputStream out = new FileOutputStream(file);
        byte[] buffer = new byte[1024];
        int read;
        while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        in.close();
        out.close();
        return file.getAbsolutePath();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
