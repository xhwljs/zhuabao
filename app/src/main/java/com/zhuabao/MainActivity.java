package com.zhuabao;

import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

public class MainActivity extends AppCompatActivity {
    private static final int VPN_REQUEST_CODE = 1001;
    private Button btnStart, btnStop, btnClear;
    private RecyclerView recyclerView;
    private LogAdapter adapter;
    private boolean isVpnRunning = false;

    public static LogAdapter staticAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        setupRecyclerView();
        setupClickListeners();
        
        staticAdapter = adapter;
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

        adapter.setOnItemClickListener(log -> {
            showLogDetail(log);
        });
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
            startActivityForResult(intent, VPN_REQUEST_CODE);
        } else {
            onActivityResult(VPN_REQUEST_CODE, RESULT_OK, null);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VPN_REQUEST_CODE && resultCode == RESULT_OK) {
            Intent vpnIntent = new Intent(this, VpnProxyService.class);
            startService(vpnIntent);
            isVpnRunning = true;
            updateButtons();
            Toast.makeText(this, "抓包已启动", Toast.LENGTH_SHORT).show();
        }
    }

    private void stopVpn() {
        Intent vpnIntent = new Intent(this, VpnProxyService.class);
        stopService(vpnIntent);
        isVpnRunning = false;
        updateButtons();
        Toast.makeText(this, "抓包已停止", Toast.LENGTH_SHORT).show();
    }

    private void updateButtons() {
        btnStart.setEnabled(!isVpnRunning);
        btnStop.setEnabled(isVpnRunning);
    }

    public static void addLog(NetworkLog log) {
        if (staticAdapter != null) {
            staticAdapter.addLog(log);
        }
    }
}
