package com.flexiatom.floatingassistant;

import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.flexiatom.floatingassistant.service.SphereAccessibilityService;

public class MainActivity extends AppCompatActivity {

    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.main_status);
        Button enableBtn = findViewById(R.id.main_enable_accessibility);
        enableBtn.setOnClickListener(v -> startActivity(
                new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
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
