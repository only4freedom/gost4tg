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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

            // 【关键步骤】处理节点列表：去换行符 + 域名自动转IP
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
        // DNS字段主要用于内部解析，虽然对peer连接可能无效，但保留作为fallback
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

    // 【核心黑科技】处理 peer.txt：去换行符 + DNS 预解析
    private void processPeerFile(String assetName, String destName) throws Exception {
        // 1. 读取原文件
        InputStream in = getAssets().open(assetName);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] data = new byte[1024];
        int nRead;
        while ((nRead = in.read(data, 0, data.length)) != -1) buffer.write(data, 0, nRead);
        in.close();
        
        String content = buffer.toString("UTF-8");
        // 去除 Windows 换行符
        content = content.replace("\r", "");
        
        // 2. 逐行分析，替换域名为 IP
        StringBuilder newContent = new StringBuilder();
        String[] lines = content.split("\n");
        
        for (String line : lines) {
            if (line.trim().startsWith("#") || line.trim().isEmpty()) {
                newContent.append(line).append("\n");
                continue;
            }
            
            // 尝试提取域名进行解析
            // 逻辑：找到最后一个 @ 之后，或者 :// 之后，直到最后一个 : 之前的内容
            try {
                String processedLine = resolveLine(line);
                newContent.append(processedLine).append("\n");
            } catch (Exception e) {
                // 如果解析失败，保留原样，防止破坏格式
                sendLog("DNS解析跳过: " + e.getMessage());
                newContent.append(line).append("\n");
            }
        }

        // 3. 写入新文件
        File file = new File(getFilesDir(), destName);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(newContent.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    // 单行解析逻辑
    private String resolveLine(String line) {
        // 简单粗暴的定位：找 host
        // 假设格式是 scheme://[user:pass@]HOST:PORT
        int start = line.lastIndexOf("@");
        if (start == -1) {
            start = line.indexOf("://");
            if (start != -1) start += 3;
        } else {
            start += 1;
        }
        
        int end = line.lastIndexOf(":");
        // 如果没有端口，end 就是行尾? 不，gost 节点通常都有端口
        
        if (start != -1 && end != -1 && end > start) {
            String host = line.substring(start, end);
            // 检查 host 是否包含字母 (如果是纯IP就不解析)
            if (host.matches(".*[a-zA-Z].*")) {
                try {
                    sendLog("正在解析域名: " + host);
                    // 使用 Java 的 DNS 解析能力
                    InetAddress address = InetAddress.getByName(host);
                    String ip = address.getHostAddress();
                    sendLog("域名 " + host + " -> " + ip);
                    
                    // 替换字符串
                    return line.substring(0, start) + ip + line.substring(end);
                } catch (Exception e) {
                    sendLog("解析失败: " + host);
                }
            }
        }
        return line;
    }

    private void copyBinaryAsset(String assetName, String destName) throws Exception {
        File file = new File(getFilesDir(), destName);
        try (InputStream in = getAssets().open(assetName);
             FileOutputStream out = new FileOutputStream(file)) {
            byte[] buffer = new byte[1024];
            int nRead;
            while ((nRead = in.read(data, 0, data.length)) != -1) out.write(buffer, 0, nRead);
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
