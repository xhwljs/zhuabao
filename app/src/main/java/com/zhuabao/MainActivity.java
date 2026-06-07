package com.zhuabao;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.widget.Button;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

public class MainActivity extends AppCompatActivity {
    private static final int VPN_REQUEST_CODE = 1001;
    private static final int NOTIFICATION_PERMISSION_CODE = 1002;
    private Button btnStart, btnStop, btnClear;
    private RecyclerView recyclerView;
    private LogAdapter adapter;
    private boolean isVpnRunning = false;
    private VpnProxyService vpnService;

    public static MainActivity instance;

    private final ServiceConnection vpnConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            VpnProxyService.LocalBinder binder = (VpnProxyService.LocalBinder) service;
            vpnService = binder.getService();
            vpnService.setMainActivity(MainActivity.this);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            vpnService = null;
        }
    };

    private final ActivityResultLauncher<Intent> vpnPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK) {
                    startVpnService();
                } else {
                    Toast.makeText(this, "VPN权限被拒绝", Toast.LENGTH_SHORT).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        instance = this;
        initViews();
        setupRecyclerView();
        setupClickListeners();

        // Request notification permission on Android 13+
        requestNotificationPermission();

        // 恢复 VPN 状态
        if (VpnProxyService.isRunning()) {
            isVpnRunning = true;
            updateButtons();
            bindVpnService();
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        NOTIFICATION_PERMISSION_CODE);
            }
        }
    }

    private void initViews() {
        btnStart = findViewById(R.id.btnStart);
        btnStop = findViewById(R.id.btnStop);
        btnClear = findViewById(R.id.btnClear);
        recyclerView = findViewById(R.id.recyclerView);
    }

    private void setupRecyclerView() {
        adapter = new LogAdapter();
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);

        adapter.setOnItemClickListener(log -> showLogDetail(log));
    }

    private void showLogDetail(NetworkLog log) {
        StringBuilder message = new StringBuilder();
        message.append("URL: ").append(log.getUrl()).append("\n\n");
        message.append("Method: ").append(log.getMethod()).append("\n\n");
        message.append("Time: ").append(log.getFormattedTime()).append("\n\n");

        if (log.getStatusCode() > 0) {
            message.append("Status: ").append(log.getStatusCode()).append("\n\n");
        }

        if (log.getRequestBody() != null && !log.getRequestBody().isEmpty()) {
            message.append("Request:\n").append(log.getRequestBody()).append("\n\n");
        }

        if (log.getResponseBody() != null && !log.getResponseBody().isEmpty()) {
            message.append("Response:\n").append(log.getResponseBody());
        }

        new AlertDialog.Builder(this)
                .setTitle("请求详情")
                .setMessage(message.toString())
                .setPositiveButton("确定", null)
                .show();
    }

    private void setupClickListeners() {
        btnStart.setOnClickListener(v -> startVpn());
        btnStop.setOnClickListener(v -> stopVpn());
        btnClear.setOnClickListener(v -> adapter.clearLogs());
    }

    private void startVpn() {
        Intent intent = VpnService.prepare(this);
        if (intent != null) {
            // 需要显示 VPN 权限对话框
            vpnPermissionLauncher.launch(intent);
        } else {
            // 已经有 VPN 权限，直接启动服务
            startVpnService();
        }
    }

    private void startVpnService() {
        Intent vpnIntent = new Intent(this, VpnProxyService.class);
        startService(vpnIntent);
        isVpnRunning = true;
        updateButtons();
        bindVpnService();
        Toast.makeText(this, "抓包已启动", Toast.LENGTH_SHORT).show();
    }

    private void bindVpnService() {
        Intent intent = new Intent(this, VpnProxyService.class);
        bindService(intent, vpnConnection, Context.BIND_AUTO_CREATE);
    }

    private void stopVpn() {
        if (vpnService != null) {
            vpnService.stopVpn();
        }
        Intent vpnIntent = new Intent(this, VpnProxyService.class);
        stopService(vpnIntent);
        isVpnRunning = false;
        updateButtons();
        Toast.makeText(this, "抓包已停止", Toast.LENGTH_SHORT).show();
    }

    private void updateButtons() {
        runOnUiThread(() -> {
            if (btnStart != null) btnStart.setEnabled(!isVpnRunning);
            if (btnStop != null) btnStop.setEnabled(isVpnRunning);
        });
    }

    public void onVpnStopped() {
        isVpnRunning = false;
        runOnUiThread(this::updateButtons);
    }

    public void addLog(NetworkLog log) {
        runOnUiThread(() -> {
            if (adapter != null) {
                adapter.addLog(log);
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (vpnConnection != null) {
            unbindService(vpnConnection);
        }
        instance = null;
    }
}
