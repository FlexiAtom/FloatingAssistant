package com.flexiatom.floatingassistant.settings;

import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.flexiatom.floatingassistant.R;
import com.flexiatom.floatingassistant.core.BlacklistMatcher;
import com.flexiatom.floatingassistant.core.SettingsStore;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 翻译设置页：模型选择模式（人工优先）+ 黑名单（关键词/正则，自动模式下命中升 Q4）+ 试匹配。
 *
 * <p>全部即时落 {@link SettingsStore}；采集端（{@code MtModelHub}）每页重读 mode + version，
 * 故改完下一次采集即生效，无需重启服务。
 */
public class SettingsActivity extends AppCompatActivity {

    private SettingsStore settings;
    private BlacklistMatcher matcher;

    private RadioGroup modeGroup;
    private LinearLayout listContainer;
    private EditText newPattern;
    private RadioGroup entryType;
    private EditText testInput;
    private TextView testResult;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        setTitle(R.string.settings_title);

        settings = new SettingsStore(this);
        matcher = new BlacklistMatcher(settings);

        modeGroup = findViewById(R.id.settings_mode_group);
        listContainer = findViewById(R.id.settings_blacklist_container);
        newPattern = findViewById(R.id.settings_new_pattern);
        entryType = findViewById(R.id.settings_entry_type);
        testInput = findViewById(R.id.settings_test_input);
        testResult = findViewById(R.id.settings_test_result);

        initMode();
        modeGroup.setOnCheckedChangeListener((g, id) -> settings.setMode(modeFromCheckedId(id)));

        findViewById(R.id.settings_add_entry).setOnClickListener(v -> addEntry());
        findViewById(R.id.settings_test_btn).setOnClickListener(v -> runTest());

        rebuildList();
    }

    private void initMode() {
        int id;
        switch (settings.mode()) {
            case SettingsStore.MODE_FORCE_LIGHT:
                id = R.id.settings_mode_light;
                break;
            case SettingsStore.MODE_FORCE_HEAVY:
                id = R.id.settings_mode_heavy;
                break;
            default:
                id = R.id.settings_mode_auto;
        }
        modeGroup.check(id);
    }

    private int modeFromCheckedId(int id) {
        if (id == R.id.settings_mode_light) {
            return SettingsStore.MODE_FORCE_LIGHT;
        } else if (id == R.id.settings_mode_heavy) {
            return SettingsStore.MODE_FORCE_HEAVY;
        }
        return SettingsStore.MODE_AUTO;
    }

    private void addEntry() {
        String p = newPattern.getText().toString().trim();
        if (p.isEmpty()) {
            return;
        }
        boolean regex = entryType.getCheckedRadioButtonId() == R.id.settings_entry_regex;
        if (regex) {
            try {
                Pattern.compile(p);
            } catch (PatternSyntaxException e) {
                // 允许加入（匹配时会跳过并记日志），但先提示用户这条当前无效
                Toast.makeText(this, R.string.settings_bad_regex, Toast.LENGTH_SHORT).show();
            }
        }
        List<SettingsStore.Entry> list = settings.blacklist();
        list.add(new SettingsStore.Entry(regex, p));
        settings.setBlacklist(list);
        newPattern.setText("");
        rebuildList();
    }

    private void removeAt(int index) {
        List<SettingsStore.Entry> list = settings.blacklist();
        if (index >= 0 && index < list.size()) {
            list.remove(index);
            settings.setBlacklist(list);
        }
        rebuildList();
    }

    private void rebuildList() {
        listContainer.removeAllViews();
        List<SettingsStore.Entry> list = settings.blacklist();
        for (int i = 0; i < list.size(); i++) {
            listContainer.addView(buildRow(list.get(i), i));
        }
    }

    private View buildRow(SettingsStore.Entry entry, int index) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView label = new TextView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.topMargin = dp(6);
        label.setLayoutParams(lp);
        label.setInputType(InputType.TYPE_CLASS_TEXT);
        int prefix = entry.regex
                ? R.string.settings_entry_regex_prefix : R.string.settings_entry_keyword_prefix;
        label.setText(getString(prefix) + " " + entry.pattern);

        Button del = new Button(this);
        del.setText(R.string.settings_delete_entry);
        del.setOnClickListener(v -> removeAt(index));

        row.addView(label);
        row.addView(del);
        return row;
    }

    private void runTest() {
        List<SettingsStore.Entry> list = settings.blacklist();
        if (list.isEmpty()) {
            testResult.setText(R.string.settings_test_empty);
            return;
        }
        String text = testInput.getText().toString();
        boolean hit = matcher.matches(text);
        testResult.setText(hit ? R.string.settings_test_hit : R.string.settings_test_miss);
    }

    private int dp(int v) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(v * density);
    }
}
