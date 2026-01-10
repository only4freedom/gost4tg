package com.example.gostproxy;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

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
                .setContentText("DNS Fix Mode") // 提示这是 DNS 修复版
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build();
        startForeground(1, notification);
    }

    private void sendLog(String msg) {
        Intent intent = new Intent("com.example.gostproxy.LOG");
        intent.putExtra("msg", msg);
        sendBroadcast(intent);
    }

    private void runGost() {
        try {
            sendLog("正在准备配置文件...");
            
            // 依然保留自动清洗功能，防止 \r 换行符问题
            String configPath = smartCopyAsset("gost.json", "gost.json");
            smartCopyAsset("peer.txt", "peer.txt");

            File binFile = new File(getFilesDir(), "gost_exec");
            String assetName = "gost_v7a";
            for (String abi : Build.SUPPORTED_ABIS) {
                if (abi.contains("arm64")) { assetName = "gost_v8a"; break; }
            }
            sendLog("检测到架构: " + assetName);
            
            copyBinaryAsset(assetName, "gost_exec");

            sendLog("正在赋予执行权限...");
            Runtime.getRuntime().exec("chmod 777 " + binFile.getAbsolutePath()).waitFor();

            sendLog("正在启动 Gost (强制 DNS)...");
            
            // 【关键修改】在这里直接加入 -dns 参数
            // 223.5.5.5 是阿里DNS，1.1.1.1 是Cloudflare DNS (备用)
            // 格式: -C config.json -dns ip:port,ip:port
            ProcessBuilder pb = new ProcessBuilder(
                binFile.getAbsolutePath(), 
                "-C", configPath,
                "-dns", "223.5.5.5:53,119.29.29.29:53,8.8.8.8:53"
            );
            
            pb.directory(getFilesDir()); 
            pb.redirectErrorStream(true);
            
            gostProcess = pb.start();
            sendLog("服务已启动! 请尝试连接...");

            BufferedReader reader = new BufferedReader(new InputStreamReader(gostProcess.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                sendLog("[Core] " + line);
            }
            
            int exitCode = gostProcess.waitFor();
            sendLog("Gost 进程退出，代码: " + exitCode);
            isRunning = false;

        } catch (Exception e) {
            sendLog("错误: " + e.getMessage());
            e.printStackTrace();
            isRunning = false;
        }
    }

    // 智能复制：去换行符
    private String smartCopyAsset(String assetName, String destName) throws Exception {
        File file = new File(getFilesDir(), destName);
        try (InputStream in = getAssets().open(assetName);
             FileOutputStream out = new FileOutputStream(file)) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            int nRead;
            byte[] data = new byte[1024];
            while ((nRead = in.read(data, 0, data.length)) != -1) {
                buffer.write(data, 0, nRead);
            }
            buffer.flush();
            String content = buffer.toString("UTF-8");
            String fixedContent = content.replace("\r", "");
            out.write(fixedContent.getBytes(StandardCharsets.UTF_8));
        }
        return file.getAbsolutePath();
    }

    // 二进制复制
    private void copyBinaryAsset(String assetName, String destName) throws Exception {
        File file = new File(getFilesDir(), destName);
        try (InputStream in = getAssets().open(assetName);
             FileOutputStream out = new FileOutputStream(file)) {
            byte[] buffer = new byte[1024];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        }
    }

    @Override
    public void onDestroy() {
        if (gostProcess != null) gostProcess.destroy();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
