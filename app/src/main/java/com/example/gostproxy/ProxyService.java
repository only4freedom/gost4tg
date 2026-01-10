package com.example.gostproxy;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;

public class ProxyService extends Service {
    private Process gostProcess;
    private boolean isRunning = false;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createNotification();
        if (!isRunning) {
            isRunning = true;
            new Thread(this::runGost).start();
        }
        return START_NOT_STICKY;
    }

    private void createNotification() {
        String channelId = "gost_service_fix";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(channelId, "Gost Proxy", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
        Notification notification = new Notification.Builder(this, channelId)
                .setContentTitle("Gost 代理运行中")
                .setContentText("TargetSDK 28 Compatibility Mode")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build();
        startForeground(1, notification);
    }

    private void sendLog(String msg) {
        // 发送广播给主界面显示
        Intent intent = new Intent("com.example.gostproxy.LOG");
        intent.putExtra("msg", msg);
        sendBroadcast(intent);
    }

    private void runGost() {
        try {
            sendLog("正在检查环境...");
            String configPath = copyAsset("gost.json", "gost.json");
            copyAsset("peer.txt", "peer.txt");

            // 确定架构
            File binFile = new File(getFilesDir(), "gost_exec");
            String assetName = "gost_v7a";
            for (String abi : Build.SUPPORTED_ABIS) {
                if (abi.contains("arm64")) { assetName = "gost_v8a"; break; }
            }
            sendLog("检测到架构: " + assetName);
            copyAsset(assetName, "gost_exec");

            // 授权 (关键)
            sendLog("正在赋予执行权限...");
            Process chmod = Runtime.getRuntime().exec("chmod 777 " + binFile.getAbsolutePath());
            chmod.waitFor();

            // 启动
            sendLog("正在启动 Gost 核心...");
            ProcessBuilder pb = new ProcessBuilder(binFile.getAbsolutePath(), "-C", configPath);
            pb.directory(getFilesDir()); // 设置工作目录，确保能读到 peer.txt
            pb.redirectErrorStream(true);
            
            gostProcess = pb.start();
            sendLog("Gost 进程已创建 PID: " + (Build.VERSION.SDK_INT >= 26 ? gostProcess.pid() : "未知"));

            // 读取日志
            BufferedReader reader = new BufferedReader(new InputStreamReader(gostProcess.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                sendLog("[Core] " + line);
            }
            
            int exitCode = gostProcess.waitFor();
            sendLog("Gost 进程异常退出，代码: " + exitCode);
            isRunning = false;

        } catch (Exception e) {
            sendLog("严重错误: " + e.getMessage());
            e.printStackTrace();
            isRunning = false;
        }
    }

    private String copyAsset(String assetName, String destName) throws Exception {
        File file = new File(getFilesDir(), destName);
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
