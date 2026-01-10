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
                .setContentText("Hardcoded Config Mode")
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
            sendLog("正在生成标准配置文件...");
            
            // 【核弹级修复】直接用 Java 代码写入 gost.json
            // 这样 100% 保证 DNS 配置生效，且没有格式错误
            createGostJson();

            // 依然需要清洗 peer.txt (去除 \r)
            sendLog("正在处理节点列表...");
            smartCopyAsset("peer.txt", "peer.txt");

            // 准备二进制文件
            File binFile = new File(getFilesDir(), "gost_exec");
            String assetName = "gost_v7a";
            for (String abi : Build.SUPPORTED_ABIS) {
                if (abi.contains("arm64")) { assetName = "gost_v8a"; break; }
            }
            sendLog("检测到架构: " + assetName);
            copyBinaryAsset(assetName, "gost_exec");

            sendLog("正在赋予执行权限...");
            Runtime.getRuntime().exec("chmod 777 " + binFile.getAbsolutePath()).waitFor();

            sendLog("正在启动 Gost (读取本地配置)...");
            
            // 【关键修复】移除了 -dns 参数，回归 -C gost.json
            ProcessBuilder pb = new ProcessBuilder(
                binFile.getAbsolutePath(), 
                "-C", new File(getFilesDir(), "gost.json").getAbsolutePath()
            );
            
            pb.directory(getFilesDir()); 
            pb.redirectErrorStream(true);
            
            gostProcess = pb.start();
            sendLog("服务进程已启动! 监听日志中...");

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

    // 【新增】手动生成 gost.json 文件
    private void createGostJson() throws Exception {
        // 这里写入你最完美的配置，包含 DNS 和所有参数
        // 223.5.5.5 是阿里DNS，1.1.1.1 是 CF DNS
        String jsonContent = "{\n" +
                "    \"Debug\": true,\n" +
                "    \"Retries\": 60,\n" +
                "    \"DNS\": \"223.5.5.5:53,8.8.8.8:53,1.1.1.1:53\",\n" +
                "    \"ServeNodes\": [\n" +
                "        \"auto://:1080\"\n" +
                "    ],\n" +
                "    \"ChainNodes\": [\n" +
                "        \"?peer=peer.txt\"\n" +
                "    ]\n" +
                "}";

        File file = new File(getFilesDir(), "gost.json");
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(jsonContent.getBytes(StandardCharsets.UTF_8));
        }
    }

    // 智能复制 peer.txt (去换行符)
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
        // 只有当文件不存在时才复制? 不，为了保险，每次都覆盖
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
