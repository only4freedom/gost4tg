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
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.net.InetAddress;
import java.net.Inet4Address;

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
                .setContentText("Auto-Host Rewrite Mode")
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
            sendLog("正在生成配置...");
            createGostJson();

            sendLog("正在优化节点 (重写为 Host 模式)...");
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
            sendLog("服务已启动! 监听日志中...");

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
        // DNS 依然保留，双重保险
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
            String trimmed = line.trim();
            if (trimmed.startsWith("#") || trimmed.isEmpty()) {
                newContent.append(line).append("\n");
                continue;
            }
            try {
                // 如果这行已经包含了 ?host= 或者 ?ip=，说明用户是高手，我们就不乱改了
                if (trimmed.contains("?host=") || trimmed.contains("?ip=")) {
                    newContent.append(line).append("\n");
                } else {
                    String processedLine = rewriteLineToHostMode(line);
                    newContent.append(processedLine).append("\n");
                }
            } catch (Exception e) {
                sendLog("跳过行: " + e.getMessage());
                newContent.append(line).append("\n");
            }
        }

        File file = new File(getFilesDir(), destName);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(newContent.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    // 【核心黑科技】把 "relay://domain:port" 重写为 "relay://ip:port?host=domain"
    private String rewriteLineToHostMode(String line) {
        // 1. 提取域名
        // 假设格式 ...@domain:port... 或 ...://domain:port...
        int atIndex = line.lastIndexOf("@");
        int protocolIndex = line.indexOf("://");
        
        int start = -1;
        if (atIndex != -1) {
            start = atIndex + 1;
        } else if (protocolIndex != -1) {
            start = protocolIndex + 3;
        }
        
        if (start == -1) return line; // 没找到域名开始的地方

        int end = line.lastIndexOf(":"); // 找端口前的冒号
        if (end <= start) return line; // 没找到端口

        String domain = line.substring(start, end);

        // 如果是纯 IP，就不需要改写
        if (!domain.matches(".*[a-zA-Z].*")) return line;

        sendLog("优化域名: " + domain);
        
        // 2. 获取 IPv4 (优先系统DNS，失败则HTTPDNS)
        String ip = resolveToIpv4(domain);

        if (ip != null) {
            sendLog("-> IP: " + ip);
            
            // 3. 拼接新字符串
            // 原: relay+mwss://user:pass@domain:2053
            // 新: relay+mwss://user:pass@1.2.3.4:2053?host=domain
            
            String part1 = line.substring(0, start);
            String part2 = line.substring(end); // 包含 :2053
            
            // 检查原来是否已经有参数了 (即是否包含 ?)
            String separator = line.contains("?") ? "&" : "?";
            
            return part1 + ip + part2 + separator + "host=" + domain;
        } else {
            sendLog("解析失败，保持原样");
            return line;
        }
    }

    private String resolveToIpv4(String host) {
        // 策略1: 系统 DNS
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress addr : addresses) {
                if (addr instanceof Inet4Address) return addr.getHostAddress();
            }
        } catch (Exception ignored) {}

        // 策略2: 腾讯 HTTP DNS
        return getHttpDns(host);
    }

    private String getHttpDns(String domain) {
        try {
            URL url = new URL("http://119.29.29.29/d?dn=" + domain);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(3000);
            conn.setRequestMethod("GET");
            InputStream in = conn.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(in));
            String result = reader.readLine();
            reader.close();
            if (result != null && !result.isEmpty()) return result.split(";")[0];
        } catch (Exception ignored) {}
        return null;
    }

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
