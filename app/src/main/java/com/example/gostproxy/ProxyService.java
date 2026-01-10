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
import java.net.InetAddress;
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
                .setContentText("DNS Resolved Mode")
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
            createGostJson();

            sendLog("正在解析节点域名...");
            processPeerFile("peer.txt", "peer.txt");

            File binFile = new File(getFilesDir(), "gost_exec");
            String assetName = "gost_v7a";
            for (String abi : Build.SUPPORTED_ABIS) {
                if (abi.contains("arm64")) { assetName = "gost_v8a"; break; }
            }
            sendLog("检测到架构: " + assetName);
            copyBinaryAsset(assetName, "gost_exec");

            sendLog("正在赋予执行权限...");
            Runtime.getRuntime().exec("chmod 777 " + binFile.getAbsolutePath()).waitFor();

            sendLog("正在启动 Gost...");
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

    private void createGostJson() throws Exception {
        String jsonContent = "{\n" +
                "    \"Debug\": true,\n" +
                "    \"Retries\": 60,\n" +
                "    \"DNS\": \"223.5.5.5:53,8.8.8.8:53\",\n" +
                "    \"ServeNodes\": [ \"auto://:1080\" ],\n" +
                "    \"ChainNodes\": [ \"?peer=peer.txt\" ]\n" +
                "}";
        File file = new File(getFilesDir(), "gost.json");
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(jsonContent.getBytes(StandardCharsets.UTF_8));
        }
    }

    private void processPeerFile(String assetName, String destName) throws Exception {
        InputStream in = getAssets().open(assetName);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] data = new byte[1024];
        int nRead;
        while ((nRead = in.read(data, 0, data.length)) != -1) buffer.write(data, 0, nRead);
        in.close();
        
        String content = buffer.toString("UTF-8");
        content = content.replace("\r", "");
        
        StringBuilder newContent = new StringBuilder();
        String[] lines = content.split("\n");
        
        for (String line : lines) {
            if (line.trim().startsWith("#") || line.trim().isEmpty()) {
                newContent.append(line).append("\n");
                continue;
            }
            try {
                String processedLine = resolveLine(line);
                newContent.append(processedLine).append("\n");
            } catch (Exception e) {
                sendLog("DNS解析跳过: " + e.getMessage());
                newContent.append(line).append("\n");
            }
        }

        File file = new File(getFilesDir(), destName);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(newContent.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    private String resolveLine(String line) {
        int start = line.lastIndexOf("@");
        if (start == -1) {
            start = line.indexOf("://");
            if (start != -1) start += 3;
        } else {
            start += 1;
        }
        int end = line.lastIndexOf(":");
        
        if (start != -1 && end != -1 && end > start) {
            String host = line.substring(start, end);
            if (host.matches(".*[a-zA-Z].*")) {
                try {
                    sendLog("正在解析域名: " + host);
                    InetAddress address = InetAddress.getByName(host);
                    String ip = address.getHostAddress();
                    sendLog("域名 " + host + " -> " + ip);
                    return line.substring(0, start) + ip + line.substring(end);
                } catch (Exception e) {
                    sendLog("解析失败: " + host);
                }
            }
        }
        return line;
    }

    // 【已修复】这里之前变量名写错了，现在统一为 buffer
    private void copyBinaryAsset(String assetName, String destName) throws Exception {
        File file = new File(getFilesDir(), destName);
        try (InputStream in = getAssets().open(assetName);
             FileOutputStream out = new FileOutputStream(file)) {
            byte[] buffer = new byte[1024];
            int nRead;
            while ((nRead = in.read(buffer, 0, buffer.length)) != -1) out.write(buffer, 0, nRead);
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
