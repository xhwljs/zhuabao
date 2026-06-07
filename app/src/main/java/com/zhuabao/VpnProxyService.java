package com.zhuabao;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import androidx.core.app.NotificationCompat;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.HashMap;
import java.util.Map;

public class VpnProxyService extends VpnService {
    private static final String CHANNEL_ID = "ZhuabaoVpnChannel";
    private static final int VPN_MTU = 1500;
    private static final String VPN_ADDRESS = "10.0.0.2";
    private static final int VPN_PREFIX = 24;
    private static final int NOTIFICATION_ID = 1001;

    private ParcelFileDescriptor vpnInterface;
    private ExecutorService executorService;
    private volatile boolean isRunning = false;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        executorService = Executors.newCachedThreadPool();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (isRunning) {
            return START_STICKY;
        }

        startForeground(NOTIFICATION_ID, createNotification());
        
        try {
            establishVpn();
            isRunning = true;
            executorService.execute(this::runVpnLoop);
        } catch (Exception e) {
            e.printStackTrace();
            stopSelf();
        }

        return START_STICKY;
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "抓包服务",
                NotificationManager.IMPORTANCE_LOW
        );
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(channel);
    }

    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("抓包服务运行中")
                .setContentText("正在捕获网络请求")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .build();
    }

    private void establishVpn() {
        Builder builder = new Builder();
        builder.addAddress(VPN_ADDRESS, VPN_PREFIX);
        builder.addRoute("0.0.0.0", 0);
        builder.addDnsServer("8.8.8.8");
        builder.addDnsServer("8.8.4.4");
        builder.setMtu(VPN_MTU);
        builder.setSession("Zhuabao VPN");
        vpnInterface = builder.establish();
    }

    private void runVpnLoop() {
        FileInputStream in = new FileInputStream(vpnInterface.getFileDescriptor());
        FileOutputStream out = new FileOutputStream(vpnInterface.getFileDescriptor());
        byte[] packet = new byte[VPN_MTU];

        while (isRunning) {
            try {
                int bytesRead = in.read(packet);
                if (bytesRead > 0) {
                    handlePacket(packet, bytesRead, out);
                }
            } catch (IOException e) {
                if (!isRunning) break;
                e.printStackTrace();
            }
        }
    }

    private void handlePacket(byte[] packet, int length, FileOutputStream out) {
        ByteBuffer buffer = ByteBuffer.wrap(packet, 0, length);
        
        int ipHeaderLength = (buffer.get(0) & 0x0F) * 4;
        int protocol = buffer.get(9) & 0xFF;
        
        byte[] ipBytes = new byte[4];
        buffer.position(12);
        buffer.get(ipBytes);
        String sourceIp = (ipBytes[0] & 0xFF) + "." + (ipBytes[1] & 0xFF) + "." + 
                          (ipBytes[2] & 0xFF) + "." + (ipBytes[3] & 0xFF);
        
        buffer.position(16);
        buffer.get(ipBytes);
        String destIp = (ipBytes[0] & 0xFF) + "." + (ipBytes[1] & 0xFF) + "." + 
                        (ipBytes[2] & 0xFF) + "." + (ipBytes[3] & 0xFF);

        if (protocol == 6) {
            buffer.position(ipHeaderLength);
            int sourcePort = buffer.getShort() & 0xFFFF;
            int destPort = buffer.getShort() & 0xFFFF;
            
            String url = "http://" + destIp + ":" + destPort;
            NetworkLog log = new NetworkLog(url, "TCP");
            MainActivity.addLog(log);
        } else if (protocol == 17) {
            buffer.position(ipHeaderLength);
            int sourcePort = buffer.getShort() & 0xFFFF;
            int destPort = buffer.getShort() & 0xFFFF;
            
            String url = "http://" + destIp + ":" + destPort;
            NetworkLog log = new NetworkLog(url, "UDP");
            MainActivity.addLog(log);
        }

        try {
            out.write(packet, 0, length);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning = false;
        executorService.shutdown();
        try {
            if (vpnInterface != null) {
                vpnInterface.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
