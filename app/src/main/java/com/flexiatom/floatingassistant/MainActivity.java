package com.flexiatom.floatingassistant;

import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.flexiatom.floatingassistant.capture.P0Benchmark;
import com.flexiatom.floatingassistant.ocr.OcrOrtProbe;
import com.flexiatom.floatingassistant.ocr.P1Runner;
import com.flexiatom.floatingassistant.service.SphereAccessibilityService;

public class MainActivity extends AppCompatActivity {

    public static final String EXTRA_RUN_P0 = "run_p0";
    public static final String EXTRA_P0_ITERATIONS = "p0_iterations";
    public static final String EXTRA_P0_DELAY_MS = "p0_delay_ms";
    public static final String EXTRA_P0_LABEL = "p0_label";

    public static final String EXTRA_RUN_ORT = "run_ort";
    public static final String EXTRA_ORT_MODEL = "ort_model";
    public static final String EXTRA_ORT_H = "ort_h";
    public static final String EXTRA_ORT_W = "ort_w";
    public static final String EXTRA_ORT_THREADS = "ort_threads";
    public static final String EXTRA_ORT_ITERATIONS = "ort_iterations";

    public static final String EXTRA_RUN_P1 = "run_p1";
    public static final String EXTRA_P1_MODE = "p1_mode";
    public static final String EXTRA_P1_DELAY_MS = "p1_delay_ms";
    public static final String EXTRA_P1_THREADS = "p1_threads";
    public static final String EXTRA_P1_REC_MAX_W = "p1_rec_max_w";
    public static final String EXTRA_P1_DET_LIMIT = "p1_det_limit";

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
        maybeRunOrtProbe(getIntent());
        maybeRunP1(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        maybeRunP0(intent);
        maybeRunOrtProbe(intent);
        maybeRunP1(intent);
    }

    private void maybeRunP0(Intent intent) {
        if (intent == null || !intent.getBooleanExtra(EXTRA_RUN_P0, false)) {
            return;
        }
        int iterations = intent.getIntExtra(EXTRA_P0_ITERATIONS, 30);
        long delayMs = (long) intent.getIntExtra(EXTRA_P0_DELAY_MS, 0);
        String label = intent.getStringExtra(EXTRA_P0_LABEL);
        if (label == null) {
            label = "-";
        }
        final String lbl = label;
        new Thread(() -> P0Benchmark.run(iterations, delayMs, lbl), "p0-benchmark").start();
    }

    private void maybeRunOrtProbe(Intent intent) {
        if (intent == null || !intent.getBooleanExtra(EXTRA_RUN_ORT, false)) {
            return;
        }
        String model = intent.getStringExtra(EXTRA_ORT_MODEL);
        if (model == null) {
            return;
        }
        int h = intent.getIntExtra(EXTRA_ORT_H, 448);
        int w = intent.getIntExtra(EXTRA_ORT_W, 960);
        int threads = intent.getIntExtra(EXTRA_ORT_THREADS, 4);
        int iterations = intent.getIntExtra(EXTRA_ORT_ITERATIONS, 5);
        new Thread(() -> OcrOrtProbe.run(this, model, h, w, threads, iterations), "ort-probe").start();
    }

    private void maybeRunP1(Intent intent) {
        if (intent == null || !intent.getBooleanExtra(EXTRA_RUN_P1, false)) {
            return;
        }
        String mode = intent.getStringExtra(EXTRA_P1_MODE);
        if (mode == null) {
            mode = "both";
        }
        long delayMs = (long) intent.getIntExtra(EXTRA_P1_DELAY_MS, 6000);
        int threads = intent.getIntExtra(EXTRA_P1_THREADS, 2);
        int recMaxW = intent.getIntExtra(EXTRA_P1_REC_MAX_W, 0);
        int detLimit = intent.getIntExtra(EXTRA_P1_DET_LIMIT, 0);
        final String m = mode;
        new Thread(() -> P1Runner.run(this, m, delayMs, threads, recMaxW, detLimit,
                "ch_PP-OCRv4_det_infer.onnx", "ch_PP-OCRv4_rec_infer.onnx", "ppocr_keys_v1.txt"),
                "p1-runner").start();
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
