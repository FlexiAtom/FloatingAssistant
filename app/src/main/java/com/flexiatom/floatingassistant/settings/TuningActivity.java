/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This file is part of FloatingAssistant.
 *
 * Copyright (C) 2026 FlexiAtom
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.flexiatom.floatingassistant.settings;

import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.flexiatom.floatingassistant.R;
import com.flexiatom.floatingassistant.core.SelfTestRunner;
import com.flexiatom.floatingassistant.core.TuningStore;
import com.flexiatom.floatingassistant.service.SphereAccessibilityService;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;

/**
 * 设备自调页（提案 §7.2 L2）——当前只放截图组（{@code device-tuning-page} A 切片）。
 *
 * <p>每个控件改动即刻落 {@link TuningStore}，截图器下一次取参数时现读，故热生效、无需重启服务。
 * 其余组（覆盖层/推理/注入/保活）与设备档案按切片台账在后续里程碑补，不在本页先摆空控件。
 */
public class TuningActivity extends AppCompatActivity {

    private TuningStore tuning;

    private Slider timeout;
    private Slider retry;
    private Slider interval;
    private Slider black;
    private MaterialSwitch detectBlank;
    private MaterialSwitch warmup;
    private RadioGroup fallback;
    private TextView timeoutLabel;
    private TextView retryLabel;
    private TextView intervalLabel;
    private TextView blackLabel;
    private TextView result;
    private Button runSelfTestButton;
    /** 程序化刷新控件时不回写 prefs，避免一次打开页面就把所有默认值「写一遍」。 */
    private boolean binding;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tuning);
        setTitle(R.string.tuning_title);

        tuning = new TuningStore(this);
        timeout = findViewById(R.id.tuning_timeout);
        retry = findViewById(R.id.tuning_retry);
        interval = findViewById(R.id.tuning_interval);
        black = findViewById(R.id.tuning_black);
        detectBlank = findViewById(R.id.tuning_detect_blank);
        warmup = findViewById(R.id.tuning_warmup);
        fallback = findViewById(R.id.tuning_fallback);
        timeoutLabel = findViewById(R.id.tuning_timeout_label);
        retryLabel = findViewById(R.id.tuning_retry_label);
        intervalLabel = findViewById(R.id.tuning_interval_label);
        blackLabel = findViewById(R.id.tuning_black_label);
        result = findViewById(R.id.tuning_result);
        runSelfTestButton = findViewById(R.id.tuning_run_selftest);

        TextView fingerprint = findViewById(R.id.tuning_fingerprint);
        String device = (Build.MANUFACTURER + " " + Build.MODEL).trim();
        String patch = Build.VERSION.SECURITY_PATCH;
        if (patch == null || patch.isEmpty()) {
            patch = "未知";
        }
        fingerprint.setText(getString(R.string.tuning_fingerprint,
                device, Build.VERSION.RELEASE, patch));

        bindControls();
        refreshControls();
    }

    private void bindControls() {
        timeout.addOnChangeListener((slider, value, fromUser) -> {
            updateTimeoutLabel((long) value);
            if (!binding && fromUser) {
                tuning.setTimeoutMs((long) value);
            }
        });
        retry.addOnChangeListener((slider, value, fromUser) -> {
            updateRetryLabel((int) value);
            if (!binding && fromUser) {
                tuning.setRetryCount((int) value);
            }
        });
        interval.addOnChangeListener((slider, value, fromUser) -> {
            updateIntervalLabel((long) value);
            if (!binding && fromUser) {
                tuning.setMinIntervalMs((long) value);
            }
        });
        black.addOnChangeListener((slider, value, fromUser) -> {
            updateBlackLabel((int) value);
            if (!binding && fromUser) {
                tuning.setBlackThreshold((int) value);
            }
        });
        detectBlank.setOnCheckedChangeListener((btn, checked) -> {
            if (!binding) {
                tuning.setDetectBlank(checked);
            }
        });
        warmup.setOnCheckedChangeListener((btn, checked) -> {
            if (!binding) {
                tuning.setWarmup(checked);
            }
        });
        fallback.setOnCheckedChangeListener((group, checkedId) -> {
            if (!binding) {
                tuning.setFallbackStrict(checkedId == R.id.tuning_fallback_strict);
            }
        });

        runSelfTestButton.setOnClickListener(v -> runSelfTest());
        findViewById(R.id.tuning_reset).setOnClickListener(v -> {
            tuning.resetDefaults();
            refreshControls();
            result.setText(R.string.tuning_reset_done);
        });
    }

    private void refreshControls() {
        binding = true;
        timeout.setValue(tuning.timeoutMs());
        retry.setValue(tuning.retryCount());
        interval.setValue(tuning.minIntervalMs());
        black.setValue(tuning.blackThreshold());
        detectBlank.setChecked(tuning.detectBlank());
        warmup.setChecked(tuning.warmup());
        fallback.check(tuning.fallbackStrict()
                ? R.id.tuning_fallback_strict : R.id.tuning_fallback_lenient);
        updateTimeoutLabel(tuning.timeoutMs());
        updateRetryLabel(tuning.retryCount());
        updateIntervalLabel(tuning.minIntervalMs());
        updateBlackLabel(tuning.blackThreshold());
        binding = false;
    }

    private void runSelfTest() {
        SphereAccessibilityService svc = SphereAccessibilityService.getInstance();
        if (svc == null) {
            result.setText(R.string.tuning_selftest_no_service);
            return;
        }
        runSelfTestButton.setEnabled(false);
        result.setText(R.string.tuning_selftest_running);
        svc.runSelfTest(SelfTestRunner.DEFAULT_ITERATIONS, r -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            runSelfTestButton.setEnabled(true);
            result.setText(r.text);
        });
    }

    private void updateTimeoutLabel(long ms) {
        timeoutLabel.setText(getString(R.string.tuning_timeout_label, ms));
    }

    private void updateRetryLabel(int count) {
        retryLabel.setText(getString(R.string.tuning_retry_label, count));
    }

    private void updateIntervalLabel(long ms) {
        intervalLabel.setText(getString(R.string.tuning_interval_label, ms));
    }

    private void updateBlackLabel(int threshold) {
        blackLabel.setText(getString(R.string.tuning_black_label, threshold));
    }
}
