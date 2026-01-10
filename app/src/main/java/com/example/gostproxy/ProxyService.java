package com.example.gostproxy;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.os.IBinder;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;

public class ProxyService extends Service {
    private Process gostProcess;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 1. 通知栏保活
        String channelId = "gost_core_service";
        NotificationChannel channel = new NotificationChannel(channelId, "Gost Core", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
        Notification notification = new Notification.Builder(this, channelId)
                .setContentTitle("Gost 正在运行")
                .setContentText("端口: 1080 (V2官方核心)")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build();
        startForeground(1, notification);

        // 2. 启动进程
        new Thread(this::runGost).start();

        return START_STICKY;
    }

    private void runGost() {
        try {
            // 准备配置文件
            String configPath = copyAsset("gost.json");
            copyAsset("peer.txt");

            // 找到核心文件的路径 (它被伪装成了 so 库)
            ApplicationInfo appInfo = getApplicationContext().getApplicationInfo();
            String corePath = appInfo.nativeLibraryDir + "/libgost_core.so";

            // 赋予执行权限 (保险起见)
            new File(corePath).setExecutable(true);

            // 执行命令: ./libgost_core.so -C config.json
            ProcessBuilder pb = new ProcessBuilder(corePath, "-C", configPath);
            pb.redirectErrorStream(true); // 合并错误输出
            gostProcess = pb.start();

            // 读取输出日志，防止进程因为缓冲区满而卡死
            BufferedReader reader = new BufferedReader(new InputStreamReader(gostProcess.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                // System.out.println("GOST: " + line); // 可以在 Logcat 看到日志
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private String copyAsset(String filename) throws Exception {
        File file = new File(getFilesDir(), filename);
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
    public void onDestroy() {
        if (gostProcess != null) gostProcess.destroy();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
