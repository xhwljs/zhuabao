package com.zhuabao;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.Binder;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import androidx.core.app.NotificationCompat;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class VpnProxyService extends VpnService {
    private static final String CHANNEL_ID = "ZhuabaoVpnChannel";
    private static final int VPN_MTU = 1500;
    private static final String VPN_ADDRESS = "10.0.0.2";
    private static final int VPN_PREFIX = 24;
    private static final int NOTIFICATION_ID = 1001;

    private ParcelFileDescriptor vpnInterface;
    private ExecutorService executorService;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private MainActivity mainActivity;

    private final IBinder binder = new LocalBinder();

    public class LocalBinder extends Binder {
        public VpnProxyService getService() {
            return VpnProxyService.this;
        }
    }

    public static boolean isRunning() {
        return false;
    }

    public void setMainActivity(MainActivity activity) {
        this.mainActivity = activity;
    }

    public void stopVpn() {
        isRunning.set(false);
        if (vpnInterface != null) {
            try {
                vpnInterface.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        executorService = Executors.newCachedThreadPool();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (isRunning.get()) {
            return START_STICKY;
        }

        startForeground(NOTIFICATION_ID, createNotification());

        try {
            establishVpn();
            isRunning.set(true);
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
        builder.setSession("抓包工具");
        builder.addAddress(VPN_ADDRESS, VPN_PREFIX);
        builder.addRoute("0.0.0.0", 0);
        builder.addDnsServer("8.8.8.8");
        builder.addDnsServer("8.8.4.4");
        builder.setMtu(VPN_MTU);
        builder.setBlocking(true);

        try {
            builder.addDisallowedApplication(getPackageName());
        } catch (Exception e) {
            e.printStackTrace();
        }

        vpnInterface = builder.establish();
    }

    private void runVpnLoop() {
        if (vpnInterface == null) return;

        FileInputStream in = new FileInputStream(vpnInterface.getFileDescriptor());
        FileOutputStream out = new FileOutputStream(vpnInterface.getFileDescriptor());
        byte[] packet = new byte[VPN_MTU];

        while (isRunning.get()) {
            try {
                int bytesRead = in.read(packet);
                if (bytesRead > 0) {
                    handlePacket(packet, bytesRead, out);
                }
            } catch (IOException e) {
                if (!isRunning.get()) break;
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ie) {
                    break;
                }
            }
        }
    }

    private void handlePacket(byte[] packet, int length, FileOutputStream out) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(packet, 0, length);

            if (length < 20) return;

            int ipHeaderLength = (buffer.get(0) & 0x0F) * 4;
            if (ipHeaderLength < 20 || length < ipHeaderLength) return;

            int protocol = buffer.get(9) & 0xFF;

            byte[] srcIp = new byte[4];
            byte[] dstIp = new byte[4];
            buffer.position(12);
            buffer.get(srcIp);
            buffer.position(16);
            buffer.get(dstIp);

            String sourceIp = (srcIp[0] & 0xFF) + "." + (srcIp[1] & 0xFF) + "." +
                    (srcIp[2] & 0xFF) + "." + (srcIp[3] & 0xFF);
            String destIp = (dstIp[0] & 0xFF) + "." + (dstIp[1] & 0xFF) + "." +
                    (dstIp[2] & 0xFF) + "." + (dstIp[3] & 0xFF);

            String protocolName = "";
            int srcPort = 0;
            int dstPort = 0;

            if (protocol == 6 || protocol == 17) {
                if (length >= ipHeaderLength + 4) {
                    buffer.position(ipHeaderLength);
                    srcPort = buffer.getShort() & 0xFFFF;
                    dstPort = buffer.getShort() & 0xFFFF;
                    protocolName = (protocol == 6) ? "TCP" : "UDP";
                }
            }

            if (protocolName.isEmpty() || (dstPort != 80 && dstPort != 443 && dstPort != 8080 && dstPort != 8443)) {
                out.write(packet, 0, length);
                return;
            }

            String url = protocolName + " " + destIp + ":" + dstPort;
            NetworkLog log = new NetworkLog(url, protocolName);
            log.setRequestBody("From: " + sourceIp + ":" + srcPort + "\nTo: " + destIp + ":" + dstPort);

            if (mainActivity != null) {
                mainActivity.addLog(log);
            }

            out.write(packet, 0, length);

        } catch (Exception e) {
            e.printStackTrace();
            try {
                out.write(packet, 0, length);
            } catch (IOException ignored) {}
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning.set(false);
        executorService.shutdown();
        if (vpnInterface != null) {
            try {
                vpnInterface.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        if (mainActivity != null) {
            mainActivity.onVpnStopped();
        }
    }
}
