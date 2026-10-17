package com.flexiatom.floatingassistant.overlay;

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * 通用**文本面板**（runtime-self-report / m1-region-select 共用）：一屏只读文本 + 底部动作按钮行。
 *
 * <p>它存在的理由是「两处需求形状相同」：自检报告要「长文本 + 复制」，框选结果要「长文本 + 复制/关闭」。
 * 与其各造一张卡，不如把 M3 卡片、滚动、手柄拖曳、返回键关闭这一套共用下去（手势见 {@link PanelDragHandle}，
 * 样式见 {@link PanelStyles}）。本视图不认识 prefs、剪贴板与 OCR，只把动作按钮回调出去。
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

    public TextPanelView(Context context, String title, CharSequence body,
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

        TextView bodyView = new TextView(context);
        bodyView.setText(body);
        bodyView.setTextColor(M3.color(context, M3.ON_SURFACE));
        bodyView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        // 逐字可选：整段复制走动作按钮，想只要其中一行也能选出来
        bodyView.setTextIsSelectable(true);
        bodyView.setPadding(0, dp(6), 0, dp(6));
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
