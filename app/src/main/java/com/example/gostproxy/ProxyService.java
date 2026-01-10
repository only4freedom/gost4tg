package com.example.gostproxy;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
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
        // 1. 必须先创建通知，否则服务会被系统杀死
        createNotification();

        // 2. 异步启动 Gost
        new Thread(this::runGost).start();

        return START_STICKY;
    }

    private void createNotification() {
        String channelId = "gost_service_id";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(channelId, "Gost Service", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
        Notification notification = new Notification.Builder(this, channelId)
                .setContentTitle("Gost 代理运行中")
                .setContentText("服务已启动 (Port 1080)")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build();
        startForeground(1, notification);
    }

    private void runGost() {
        try {
            // 1. 准备配置文件
            String configPath = copyAsset("gost.json", "gost.json");
            copyAsset("peer.txt", "peer.txt");

            // 2. 准备可执行文件 (关键步骤)
            File binFile = new File(getFilesDir(), "gost_exec");
            
            // 根据 CPU 架构选择对应的核心
            String assetName = "gost_v7a"; // 默认 32位
            for (String abi : Build.SUPPORTED_ABIS) {
                if (abi.contains("arm64")) {
                    assetName = "gost_v8a"; // 如果是 64位机器，用 64位核心
                    break;
                }
            }
            
            // 复制核心到私有目录
            copyAsset(assetName, "gost_exec");

            // 3. 赋予可执行权限 (chmod 777)
            // Java 的 setExecutable 有时不灵，用 Shell 强制给权限
            Runtime.getRuntime().exec("chmod 777 " + binFile.getAbsolutePath()).waitFor();

            // 4. 执行命令
            // ./gost_exec -C gost.json
            ProcessBuilder pb = new ProcessBuilder(binFile.getAbsolutePath(), "-C", configPath);
            pb.directory(getFilesDir()); // 设置工作目录
            pb.redirectErrorStream(true);
            gostProcess = pb.start();

            // 5. 保持读取日志，防止缓冲区堵塞导致进程挂起
            BufferedReader reader = new BufferedReader(new InputStreamReader(gostProcess.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                // System.out.println("GostLog: " + line);
            }

        } catch (Exception e) {
            e.printStackTrace();
            // 如果出错，服务可能会停止，这里可以加日志
        }
    }

    // 辅助：从 Assets 复制到 Files 目录
    private String copyAsset(String assetName, String destName) throws Exception {
        File file = new File(getFilesDir(), destName);
        // 如果文件不存在或需要强制更新，则复制
        // 这里每次启动都复制，确保文件是完整的
        try (InputStream in = getAssets().open(assetName);
             FileOutputStream out = new FileOutputStream(file)) {
            byte[] buffer = new byte[1024];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        }
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
