package com.flexiatom.floatingassistant.overlay;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.flexiatom.floatingassistant.R;
import com.flexiatom.floatingassistant.core.SettingsStore;

import java.util.ArrayList;
import java.util.List;

/**
 * 黑名单规则的**就地编辑面板**（#68-T8b）：一张居中卡片，列出 {@link SettingsStore.Entry} 规则，
 * 可逐条删除、可新增（关键词/正则两态点按切换），每次增删立刻 {@link Listener#onSave} 落盘。
 *
 * <p>取代原先「跳 {@code SettingsActivity}」的占位（用户反馈：子页不该与悬浮层平级地另起 Activity）。
 * 本视图不认识 prefs 与翻译流程，只维护**自己这份工作副本**并回调保存——与 {@code FanMenuView} 不认识
 * {@code Tool} 同一解耦取向。
 *
 * <p>关闭有两条路：卡片上的「关闭」按钮，以及返回键（{@link #dispatchKeyEvent} 拦 {@code KEYCODE_BACK}）。
 * 键盘可见时第一次返回通常被 IME 吞掉用于收起键盘，再按一次才关面板——这是系统惯例，不是缺陷。
 * 面板窗**带焦点**（见 {@link OverlayManager#showPanel}），故下层 App 在面板打开期间收不到触摸（模态），
 * 与扇形菜单同性质。
 */
public final class BlacklistPanelView extends LinearLayout {

    /** 面板固定宽度（dp）：屏宽 360dp 量级下留边成卡片状。 */
    public static final int WIDTH_DP = 300;

    /** 规则列表区高度（dp），超出走滚动，避免长列表把卡片顶出屏幕。 */
    private static final int LIST_HEIGHT_DP = 200;

    public interface Listener {
        /** 规则集合变化（增/删之后），参数为**当前完整列表**（接收方直接整体覆盖持久层）。 */
        void onSave(List<SettingsStore.Entry> entries);

        /** 用户请求关闭面板（关闭按钮 / 返回键）。 */
        void onClose();
    }

    private final Listener listener;
    private final List<SettingsStore.Entry> entries = new ArrayList<>();
    private final LinearLayout listContainer;
    // 由 buildAddRow 装配（Java 不允许在非构造器方法里给 blank final 赋值），故不加 final
    private EditText input;
    private Button typeButton;

    /** 新增条目的类型开关（默认字面关键词）。 */
    private boolean regexMode;

    public BlacklistPanelView(Context context, List<SettingsStore.Entry> initial, Listener listener) {
        super(context);
        this.listener = listener;
        if (initial != null) {
            this.entries.addAll(initial);
        }

        setOrientation(VERTICAL);
        int pad = dp(12);
        setPadding(pad, pad, pad, pad);
        setBackground(cardBackground());
        // 让面板自身可接收焦点（返回键要落到本视图树的 dispatchKeyEvent）
        setFocusableInTouchMode(true);

        addView(buildHeader(context));
        listContainer = new LinearLayout(context);
        listContainer.setOrientation(VERTICAL);
        ScrollView scroll = new ScrollView(context);
        scroll.addView(listContainer,
                new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        addView(scroll, rowLayoutParams(LayoutParams.MATCH_PARENT, dp(LIST_HEIGHT_DP)));
        addView(buildAddRow(context));
        rebuildList();
    }

    /** 标题行：左「黑名单（命中则整页升 Q4）」，右「关闭」。 */
    private View buildHeader(Context context) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(context);
        title.setText(R.string.settings_blacklist_title);
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        row.addView(title, rowLayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        Button close = smallButton(context, R.string.panel_close);
        close.setOnClickListener(v -> listener.onClose());
        row.addView(close, rowLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        return row;
    }

    /** 新增行：输入框 + 类型切换（关键词/正则）+ 「添加」。 */
    private View buildAddRow(Context context) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        input = new EditText(context);
        input.setHint(R.string.settings_new_pattern_hint);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setSingleLine(true);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.parseColor("#99FFFFFF"));
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        // 焦点型悬浮窗里点输入框才拉键盘：显式请求，免得某些 ROM 不自动弹
        input.setOnClickListener(v -> {
            InputMethodManager imm = (InputMethodManager)
                    v.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT);
            }
        });
        row.addView(input, rowLayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        typeButton = smallButton(context, R.string.settings_entry_keyword);
        typeButton.setOnClickListener(v -> {
            regexMode = !regexMode;
            typeButton.setText(regexMode
                    ? R.string.settings_entry_regex : R.string.settings_entry_keyword);
        });
        row.addView(typeButton, rowLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        Button add = smallButton(context, R.string.settings_add_entry);
        add.setOnClickListener(v -> addEntry());
        row.addView(add, rowLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        return row;
    }

    /** 追加一条规则（空输入忽略），随后整体落盘并刷新列表。 */
    private void addEntry() {
        String pattern = input.getText().toString().trim();
        if (pattern.isEmpty()) {
            return;
        }
        entries.add(new SettingsStore.Entry(regexMode, pattern));
        input.setText("");
        rebuildList();
        listener.onSave(new ArrayList<>(entries));
    }

    /** 删除第 index 条（越界忽略），随后整体落盘并刷新列表。 */
    private void removeAt(int index) {
        if (index < 0 || index >= entries.size()) {
            return;
        }
        entries.remove(index);
        rebuildList();
        listener.onSave(new ArrayList<>(entries));
    }

    /** 按当前 {@link #entries} 重建列表行。 */
    private void rebuildList() {
        listContainer.removeAllViews();
        Context context = getContext();
        if (entries.isEmpty()) {
            TextView empty = new TextView(context);
            empty.setText(R.string.settings_test_empty);
            empty.setTextColor(Color.parseColor("#CCFFFFFF"));
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            listContainer.addView(empty, rowLayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
            return;
        }
        for (int i = 0; i < entries.size(); i++) {
            SettingsStore.Entry e = entries.get(i);
            final int index = i;
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            TextView text = new TextView(context);
            text.setText(context.getString(
                    e.regex ? R.string.settings_entry_regex_prefix : R.string.settings_entry_keyword_prefix)
                    + " " + e.pattern);
            text.setTextColor(Color.WHITE);
            text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            text.setSingleLine(true);
            text.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(text, rowLayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

            Button del = smallButton(context, R.string.settings_delete_entry);
            del.setOnClickListener(v -> removeAt(index));
            row.addView(del, rowLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

            listContainer.addView(row, rowLayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        }
    }

    /** 面板内的紧凑按钮：去掉默认最小宽/内边距，免得三个按钮把行挤爆。 */
    private Button smallButton(Context context, int textRes) {
        Button b = new Button(context);
        b.setText(textRes);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        b.setMinimumWidth(0);
        b.setPadding(dp(8), dp(2), dp(8), dp(2));
        return b;
    }

    private GradientDrawable cardBackground() {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#F21C1B1F"));
        bg.setCornerRadius(dp(12));
        return bg;
    }

    private LinearLayout.LayoutParams rowLayoutParams(int width, int height) {
        return new LinearLayout.LayoutParams(width, height);
    }

    private LinearLayout.LayoutParams rowLayoutParams(int width, int height, float weight) {
        return new LinearLayout.LayoutParams(width, height, weight);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK
                && event.getAction() == KeyEvent.ACTION_UP) {
            listener.onClose();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }
}
