package com.flexiatom.floatingassistant.overlay;

import android.content.Context;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * 通用**文本面板**（runtime-self-report / m1-region-select 共用）：一屏文本 + 底部动作按钮行。
 *
 * <p>它存在的理由是「两处需求形状相同」：自检报告要「长文本 + 复制」，框选结果要「长文本 + 复制/关闭」。
 * 与其各造一张卡，不如把 M3 卡片、滚动、手柄拖曳、返回键关闭这一套共用下去（手势见 {@link PanelDragHandle}，
 * 样式见 {@link PanelStyles}）。本视图不认识 prefs、剪贴板与 OCR，只把动作按钮回调出去。
 *
 * <p>正文是 {@link EditText} 而非只读 TextView：调用方给的文本是 OCR 猜的，人一眼就能看出哪里错，
 * 故要能就地改（{@link #bodyText()} 取**改后**的值）。这要求面板窗**可聚焦**（{@link OverlayManager#showPanel}），
 * 且点击时要显式 {@code showSoftInput} —— 两条都抄 {@link BlacklistPanelView} 的实测结论。
 *
 * <p>关闭有三条：动作按钮里自带的「关闭」（由调用方放进 actions）、手柄双击、返回键
 * （{@link #dispatchKeyEvent} 拦 {@code KEYCODE_BACK}）。面板窗带焦点（{@link OverlayManager#showPanel}），
 * 故打开期间下层 App 收不到触摸，与黑名单面板同性质。
 */
public final class TextPanelView extends LinearLayout implements PanelView {

    /** 正文滚动区高度（dp）：报告与整段 OCR 结果都可能长，超出走滚动，别把卡片顶出屏幕。 */
    private static final int BODY_HEIGHT_DP = 240;

    public interface Listener {
        void onClose();

        void onMove(int dx, int dy);
    }

    /** 底部动作：标签 + 点击后做什么（是否顺带收起面板由调用方在自己的 Runnable 里决定，视图不猜）。 */
    public static final class Action {
        public final String label;
        public final Runnable run;

        public Action(String label, Runnable run) {
            this.label = label;
            this.run = run;
        }
    }

    private final Listener listener;
    private final PanelDragHandle handle;
    private final EditText body;

    public TextPanelView(Context context, String title, CharSequence bodyText,
                         List<Action> actions, Listener listener) {
        super(context);
        this.listener = listener;

        setOrientation(VERTICAL);
        int pad = dp(12);
        setPadding(pad, pad, pad, pad);
        setBackground(PanelStyles.card(context));
        setFocusableInTouchMode(true);

        handle = new PanelDragHandle(context, dp(WIDTH_DP / 2), new PanelDragHandle.Callback() {
            @Override
            public void onClose() {
                listener.onClose();
            }

            @Override
            public void onMove(int dx, int dy) {
                listener.onMove(dx, dy);
            }
        });
        LayoutParams handleLp = new LayoutParams(LayoutParams.MATCH_PARENT, dp(20));
        handleLp.bottomMargin = dp(4);
        addView(handle, handleLp);

        TextView titleView = new TextView(context);
        titleView.setText(title);
        titleView.setTextColor(M3.color(context, M3.ON_SURFACE));
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        addView(titleView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        // 正文**可编辑**（用户 2026-10-18：结果要能唤起软键盘改识别结果）：OCR 对拉丁/符号仍会错读，
        // 改五个字比重新框一次快。用 EditText 而非 textIsSelectable 的 TextView——后者只能选不能改。
        // 背景置空是必须的：EditText 默认底会把 M3 卡片再套一层输入框边。
        EditText bodyView = new EditText(context);
        bodyView.setText(bodyText);
        bodyView.setTextColor(M3.color(context, M3.ON_SURFACE));
        bodyView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        bodyView.setBackground(null);
        bodyView.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        // 与 {@link BlacklistPanelView} 同一条实测经验：焦点型悬浮窗里**点**输入框才拉得起键盘，
        // 得显式 showSoftInput，免得某些 ROM 不自动弹（黑名单页「输不进去」的同一格）。
        bodyView.setOnClickListener(v -> {
            InputMethodManager imm = (InputMethodManager)
                    v.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT);
            }
        });
        bodyView.setPadding(0, dp(6), 0, dp(6));
        this.body = bodyView;
        ScrollView scroll = new ScrollView(context);
        scroll.addView(bodyView, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        addView(scroll, new LayoutParams(LayoutParams.MATCH_PARENT, dp(BODY_HEIGHT_DP)));

        if (actions != null && !actions.isEmpty()) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(HORIZONTAL);
            row.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            for (Action action : actions) {
                Button button = PanelStyles.tonalButton(context, action.label);
                button.setOnClickListener(v -> action.run.run());
                LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
                lp.leftMargin = dp(6);
                row.addView(button, lp);
            }
            addView(row, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        }
    }

    @Override
    public void handleRectInRoot(android.graphics.Rect out) {
        handle.barRectInRoot(out);
        offsetDescendantRectToMyCoords(handle, out);
    }

    /**
     * 正文**当前**文本：用户改过就是改后的，复制必须以它为准（面板初值会过期）。
     * 也是机读入口（runtime-self-report 行 71）：正文区高 240dp 可滚动，截图只看得到首屏，
     * 拿这条读出的整串与 {@code RuntimeReport} 那几行比，就不必靠手指滚到底。
     */
    public String bodyText() {
        return body.getText().toString();
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
