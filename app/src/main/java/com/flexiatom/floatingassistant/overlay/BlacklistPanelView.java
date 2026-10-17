package com.flexiatom.floatingassistant.overlay;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
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

    /** 拖曳起始阈值（px²，TouchTool 同款 9px 的平方）：位移不足它不算拖，留给双击判定。 */
    private static final float DRAG_SLOP_SQ_PX = 81f;

    /** 手柄两次轻点间隔不超过它算「双击关闭」。 */
    private static final long DOUBLE_TAP_TIMEOUT_MS = 300L;

    public interface Listener {
        /** 规则集合变化（增/删之后），参数为**当前完整列表**（接收方直接整体覆盖持久层）。 */
        void onSave(List<SettingsStore.Entry> entries);

        /** 用户请求关闭面板（关闭按钮 / 返回键 / 手柄双击）。 */
        void onClose();

        /** 手柄按住拖曳：请求把面板窗移动 ({@code dx},{@code dy}) 屏幕像素增量（正=右下）。 */
        void onMove(int dx, int dy);
    }

    private final Listener listener;
    private final List<SettingsStore.Entry> entries = new ArrayList<>();
    private final LinearLayout listContainer;
    /** 顶部**可见浅灰条**（不是整条触摸区）：{@link OverlayManager#movePanel} 以它是否越出屏边判「拖出即关闭」。 */
    private View handle;
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

        addView(buildHandle(context));
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

    /**
     * 顶部手柄（#68-T8b 复验补充）：居中一条浅灰短条，长度＝面板窗宽的一半。
     * **双击=关闭面板**，**按住拖曳=移动面板窗**（默认贴顶，键盘遮不住；仍可按需拖开）。
     * 触摸目标是整条 {@link android.widget.FrameLayout}（高 20dp，远大于 4dp 的可见条），免得细条点不中。
     */
    private View buildHandle(Context context) {
        android.widget.FrameLayout touchArea = new android.widget.FrameLayout(context);
        View bar = new View(context);
        this.handle = bar; // 量的是**可见条**的矩形：判「拖出即关闭」以它为准，整条触摸区太宽会一拖就关
        GradientDrawable barBg = new GradientDrawable();
        barBg.setColor(M3.alpha(M3.color(context, M3.ON_SURFACE_VARIANT), 0.5f)); // 半透明 onSurfaceVariant：叠在容器面上是中性灰条
        barBg.setCornerRadius(dp(2));
        bar.setBackground(barBg);
        android.widget.FrameLayout.LayoutParams barLp = new android.widget.FrameLayout.LayoutParams(
                dp(WIDTH_DP / 2), dp(4));
        barLp.gravity = Gravity.CENTER_HORIZONTAL;
        touchArea.addView(bar, barLp);

        // 拖曳按 TouchTool（top.bogey.touch_tool/utils/float_window_manager/FloatViewParent）的写法自算：
        // 每事件取「本点 − 上一点」的 raw 增量、并把基准推进到本点。**不用 GestureDetector.onScroll**——
        // 它的 distanceX/Y 在新实现里是按整段手势累计的，把它当逐帧增量累加进 lp.x 会让窗跑得比手指快，
        // 撞上屏边即回弹＝用户报的抽搐。双击也自己判（两次 UP 间隔内、且期间没拖动）。
        touchArea.setOnTouchListener(new View.OnTouchListener() {
            private float lastRawX;
            private float lastRawY;
            private float carryX;
            private float carryY;
            private boolean dragging;
            private long lastTapUpAt;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        lastRawX = e.getRawX();
                        lastRawY = e.getRawY();
                        carryX = 0f;
                        carryY = 0f;
                        dragging = false;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float dx = e.getRawX() - lastRawX;
                        float dy = e.getRawY() - lastRawY;
                        if (!dragging && dx * dx + dy * dy < DRAG_SLOP_SQ_PX) {
                            return true;
                        }
                        dragging = true;
                        // 亚像素余量进 carry 累加，免得连续小步位移被 round 吃成 0（拖不动）
                        float fx = dx + carryX;
                        float fy = dy + carryY;
                        int ix = Math.round(fx);
                        int iy = Math.round(fy);
                        carryX = fx - ix;
                        carryY = fy - iy;
                        lastRawX = e.getRawX();
                        lastRawY = e.getRawY();
                        if (ix != 0 || iy != 0) {
                            listener.onMove(ix, iy);
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                        if (dragging) {
                            dragging = false;
                            return true;
                        }
                        long now = android.os.SystemClock.uptimeMillis();
                        if (lastTapUpAt > 0 && now - lastTapUpAt <= DOUBLE_TAP_TIMEOUT_MS) {
                            lastTapUpAt = 0;
                            listener.onClose();
                        } else {
                            lastTapUpAt = now;
                        }
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        dragging = false;
                        return true;
                    default:
                        return false;
                }
            }
        });
        LinearLayout.LayoutParams lp = rowLayoutParams(LayoutParams.MATCH_PARENT, dp(20));
        lp.bottomMargin = dp(4);
        touchArea.setLayoutParams(lp);
        return touchArea;
    }

    /** 标题行：「黑名单（命中则整页升 Q4）」。关闭不走按钮，由顶部手柄双击/返回键承担。 */
    private View buildHeader(Context context) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(context);
        title.setText(R.string.settings_blacklist_title);
        title.setTextColor(M3.color(context, M3.ON_SURFACE));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        row.addView(title, rowLayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
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
        input.setTextColor(M3.color(context, M3.ON_SURFACE));
        input.setHintTextColor(M3.alpha(M3.color(context, M3.ON_SURFACE_VARIANT), 0.6f));
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
            empty.setTextColor(M3.alpha(M3.color(context, M3.ON_SURFACE_VARIANT), 0.8f));
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
            text.setTextColor(M3.color(context, M3.ON_SURFACE));
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

    /**
     * 顶部浅灰条在**本视图（= 窗内容）坐标**内的矩形，供 {@link OverlayManager#movePanel} 结合窗位置判
     * 「浅灰条拖出屏幕即关闭」。用布局坐标而非 {@code getLocationOnScreen}：后者要等下一帧遍历才反映
     * {@code updateViewLayout} 的新位置，逐帧拖曳会读到一个事件滞后的旧值。
     */
    public void handleRectInRoot(android.graphics.Rect out) {
        if (handle == null) {
            out.setEmpty();
            return;
        }
        handle.getDrawingRect(out);
        offsetDescendantRectToMyCoords(handle, out);
    }

    /** 面板内的紧凑按钮：自带圆角背景（不带框架按钮的 4/6dp 内建 inset）+ 零最小宽高，尺寸只由文字与内边距决定。 */
    private Button smallButton(Context context, int textRes) {
        Button b = new Button(context);
        b.setText(textRes);
        b.setAllCaps(false);
        b.setTextColor(M3.color(context, M3.ON_SECONDARY_CONTAINER));
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setStateListAnimator(null);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(M3.color(context, M3.SECONDARY_CONTAINER)); // M3 tonal 按钮底（配 onSecondaryContainer 字）
        bg.setCornerRadius(dp(6));
        b.setBackground(bg);
        b.setPadding(dp(6), dp(4), dp(6), dp(4));
        return b;
    }

    private GradientDrawable cardBackground() {
        GradientDrawable bg = new GradientDrawable();
        // 容器面保留 95% 不透明：悬浮窗贴在任何 App 上，留一点透明度表层级、留一点实心保可读
        bg.setColor(M3.alpha(M3.color(getContext(), M3.SURFACE_CONTAINER_HIGH), 0.95f));
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
