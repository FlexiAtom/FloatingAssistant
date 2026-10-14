package com.flexiatom.floatingassistant;

import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.flexiatom.floatingassistant.capture.P0Benchmark;
import com.flexiatom.floatingassistant.service.SphereAccessibilityService;

public class MainActivity extends AppCompatActivity {

    public static final String EXTRA_RUN_P0 = "run_p0";
    public static final String EXTRA_P0_ITERATIONS = "p0_iterations";

    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.main_status);
        Button enableBtn = findViewById(R.id.main_enable_accessibility);
        enableBtn.setOnClickListener(v -> startActivity(
                new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        maybeRunP0(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        maybeRunP0(intent);
    }

    private void maybeRunP0(Intent intent) {
        if (intent == null || !intent.getBooleanExtra(EXTRA_RUN_P0, false)) {
            return;
        }
        int iterations = intent.getIntExtra(EXTRA_P0_ITERATIONS, 30);
        new Thread(() -> P0Benchmark.run(iterations), "p0-benchmark").start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean connected = SphereAccessibilityService.isConnected();
        statusText.setText(connected
                ? R.string.main_status_connected
                : R.string.main_status_disconnected);
    }
}
