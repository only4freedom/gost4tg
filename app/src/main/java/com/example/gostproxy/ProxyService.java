package com.example.gostproxy;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
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
    public static final String ACTION_STOP = "com.example.gostproxy.action.STOP";
    public static final String ACTION_RESTART = "com.example.gostproxy.action.RESTART";
    private static final int NOTIFICATION_ID = 1;
    private static final String PREFS_NAME = "gost_settings";
    private static final String PREF_LISTEN_PORT = "listen_port";

    private Process gostProcess;
    private volatile boolean isRunning = false;
    private volatile boolean stopRequested = false;
    private volatile boolean restartRequested = false;
    private final Object workerLock = new Object();
    private final Object processLock = new Object();

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopGostAndService(startId);
            return START_NOT_STICKY;
        }

        createNotification();

        if (intent != null && ACTION_RESTART.equals(intent.getAction())) {
            restartGost();
            return START_NOT_STICKY;
        }

        synchronized (workerLock) {
            if (!isRunning) startGostWorkerLocked();
        }
        return START_NOT_STICKY;
    }

    private void startGostWorkerLocked() {
        stopRequested = false;
        isRunning = true;
        new Thread(this::runGost, "GostProxyWorker").start();
    }

    private void restartGost() {
        synchronized (workerLock) {
            restartRequested = true;
            stopRequested = true;
        }

        synchronized (processLock) {
            if (gostProcess != null) {
                destroyGostProcess(gostProcess);
                gostProcess = null;
            }
        }

        synchronized (workerLock) {
            // If the previous worker already finished, no finally block remains to
            // perform the queued restart, so start it here.
            if (!isRunning && restartRequested) {
                restartRequested = false;
                startGostWorkerLocked();
            }
        }
    }

    private void createNotification() {
        String channelId = "gost_service_fix";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(channelId, "Gost Proxy", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }

        Intent stopIntent = new Intent(this, ProxyService.class);
        stopIntent.setAction(ACTION_STOP);
        int pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pendingIntentFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent stopPendingIntent = PendingIntent.getService(this, 1, stopIntent, pendingIntentFlags);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, channelId)
                : new Notification.Builder(this).setPriority(Notification.PRIORITY_LOW);
        Notification notification = builder
                .setContentTitle("Gost 代理运行中")
                .setContentText("Target IP Param Mode")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "退出", stopPendingIntent)
                .build();
        startForeground(NOTIFICATION_ID, notification);
    }

    private void sendLog(String msg) {
        Intent intent = new Intent("com.example.gostproxy.LOG");
        intent.putExtra("msg", msg);
        sendBroadcast(intent);
    }

    private void runGost() {
        try {
            if (stopRequested) return;
            sendLog("正在生成配置...");
            createGostJson();

            if (stopRequested) return;
            sendLog("正在解析 IP 并重写参数...");
            processPeerFile("peer.txt", "peer.txt");

            if (stopRequested) return;
            File binFile = new File(getFilesDir(), "gost_exec");
            String assetName = "gost_v7a";
            for (String abi : Build.SUPPORTED_ABIS) {
                if (abi.contains("arm64")) { assetName = "gost_v8a"; break; }
            }
            sendLog("检测到架构: " + assetName);
            copyBinaryAsset(assetName, "gost_exec");

            if (stopRequested) return;
            sendLog("正在赋予执行权限...");
            Runtime.getRuntime().exec("chmod 777 " + binFile.getAbsolutePath()).waitFor();

            sendLog("正在启动 Gost...");
            ProcessBuilder pb = new ProcessBuilder(
                binFile.getAbsolutePath(), 
                "-C", new File(getFilesDir(), "gost.json").getAbsolutePath()
            );
            pb.directory(getFilesDir()); 
            pb.redirectErrorStream(true);

            Process process;
            synchronized (processLock) {
                if (stopRequested) return;
                process = pb.start();
                gostProcess = process;
                if (stopRequested) {
                    destroyGostProcess(process);
                    return;
                }
            }
            sendLog("服务已启动! 监听日志中...");

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                sendLog("[Core] " + line);
            }
            
            int exitCode = process.waitFor();
            sendLog("Gost 进程退出，代码: " + exitCode);

        } catch (Exception e) {
            sendLog("错误: " + e.getMessage());
            e.printStackTrace();
        } finally {
            finishGostWorker();
        }
    }

    private void finishGostWorker() {
        synchronized (workerLock) {
            if (restartRequested) {
                restartRequested = false;
                startGostWorkerLocked();
            } else {
                isRunning = false;
            }
        }
    }

    private void createGostJson() throws Exception {
        // DNS 依然保留作为 fallback
        int listenPort = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getInt(PREF_LISTEN_PORT, 1080);
        if (listenPort < 1024 || listenPort > 65535) listenPort = 1080;

        String jsonContent = "{\n" +
                "    \"Debug\": true,\n" +
                "    \"Retries\": 60,\n" +
                "    \"DNS\": \"223.5.5.5:53,8.8.8.8:53\",\n" +
                "    \"ServeNodes\": [ \"auto://:" + listenPort + "\" ],\n" +
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
                // 如果用户已经手动写了 ?ip=，就不再处理
                if (trimmed.contains("?ip=") || trimmed.contains("&ip=")) {
                    newContent.append(line).append("\n");
                } else {
                    String processedLine = appendIpParam(line);
                    newContent.append(processedLine).append("\n");
                }
            } catch (Exception e) {
                sendLog("处理失败: " + e.getMessage());
                newContent.append(line).append("\n");
            }
        }

        File file = new File(getFilesDir(), destName);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(newContent.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    // 【核心修改】保持原域名不变，在末尾追加 ?ip=1.2.3.4
    private String appendIpParam(String line) {
        // 1. 提取域名
        int atIndex = line.lastIndexOf("@");
        int protocolIndex = line.indexOf("://");
        
        int start = -1;
        if (atIndex != -1) {
            start = atIndex + 1;
        } else if (protocolIndex != -1) {
            start = protocolIndex + 3;
        }
        
        if (start == -1) return line; 

        int end = line.lastIndexOf(":"); 
        if (end <= start) return line; 

        String domain = line.substring(start, end);

        // 如果已经是IP，不需要加参数
        if (!domain.matches(".*[a-zA-Z].*")) return line;

        sendLog("解析: " + domain);
        String ip = resolveToIpv4(domain);

        if (ip != null) {
            sendLog("-> " + ip);
            // 拼接逻辑:
            // 检查原行是否已经有参数 (?)
            String separator = line.contains("?") ? "&" : "?";
            // 追加 ip 参数
            return line + separator + "ip=" + ip;
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

    private void stopGostAndService(int startId) {
        synchronized (workerLock) {
            restartRequested = false;
            stopRequested = true;
            isRunning = false;
        }
        synchronized (processLock) {
            if (gostProcess != null) {
                destroyGostProcess(gostProcess);
                gostProcess = null;
            }
        }
        stopForeground(true);
        stopSelf(startId);
    }

    private void destroyGostProcess(Process process) {
        if (process == null) return;
        process.destroy();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                if (process.isAlive()) process.destroyForcibly();
            } catch (Exception ignored) {
                // destroy() 已先行调用；此处仅作为 API 26+ 的强制结束兜底。
            }
        }
    }

    @Override
    public void onDestroy() {
        synchronized (workerLock) {
            restartRequested = false;
            stopRequested = true;
            isRunning = false;
        }
        synchronized (processLock) {
            if (gostProcess != null) {
                destroyGostProcess(gostProcess);
                gostProcess = null;
            }
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
