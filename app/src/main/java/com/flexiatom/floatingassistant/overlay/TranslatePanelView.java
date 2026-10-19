package com.flexiatom.floatingassistant.overlay;

import android.content.Context;
import android.graphics.Rect;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.flexiatom.floatingassistant.R;
import com.flexiatom.floatingassistant.core.LangCatalog;

/**
 * 目标语言选择面板（m2-translation-settings）：一列 38 项，点一行即落盘。
 *
 * <p>与 {@link BlacklistPanelView} 同一取向——本视图不认识 prefs 与翻译流程，只回调
 * {@link Listener#onPick}；语言表来自 {@link LangCatalog}（官方模型卡抄下来的 38 项），
 * 本类不硬编码任何语言名。
 *
 * <p><b>为什么只有一列、没有「原语言」</b>：人原话要的是"选原语言和目标语言"，左列一度做过，
 * 装机实测后按人的裁决摘掉——Hy-MT2 官方 6 类句式的正文模板里**只有** {@code {target_lang}}，
 * 没有任何源语言槽（{@code {source_lang}} 只在注释句出现一次），源语言由模型自行判定。
 * 实测坐实：喂英文原文而 prefs 里 {@code src_lang=zh} 不动，输出仍是日语。
 * 留一列改不动输出的选择器＝摆设冒充设置，故整列连同 prefs 的 {@code src_lang} 一起下线。
 */
public final class TranslatePanelView extends LinearLayout implements PanelView {

    public interface Listener {
        /** 用户选了目标语言。 */
        void onPick(String code);

        /** 用户请求关闭面板（手柄双击 / 返回键）。 */
        void onClose();

        /** 手柄拖曳：请求把面板窗移动 ({@code dx},{@code dy}) 屏幕像素增量（正=右下）。 */
        void onMove(int dx, int dy);
    }

    /** 列表固定高度（dp）；38 项走滚动，不撑爆卡片。 */
    private static final int LIST_HEIGHT_DP = 300;

    private final Listener listener;
    private String selected;
    private LinearLayout column;
    private TextView current;
    private PanelDragHandle handle;

    public TranslatePanelView(Context context, String tgtCode, Listener listener) {
        super(context);
        this.listener = listener;
        this.selected = tgtCode;

        setOrientation(VERTICAL);
        int pad = dp(12);
        setPadding(pad, pad, pad, pad);
        setBackground(PanelStyles.card(context));
        setFocusableInTouchMode(true); // 返回键要落到本视图树的 dispatchKeyEvent

        addView(buildHandle(context));
        addView(buildHeader(context));
        addView(buildList(context));
        addView(buildNote(context));
    }

    private View buildHandle(Context context) {
        PanelDragHandle touchArea = new PanelDragHandle(context, dp(PanelView.WIDTH_DP / 2),
                new PanelDragHandle.Callback() {
                    @Override
                    public void onClose() {
                        listener.onClose();
                    }

                    @Override
                    public void onMove(int dx, int dy) {
                        listener.onMove(dx, dy);
                    }
                });
        this.handle = touchArea;
        LinearLayout.LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, dp(20));
        lp.bottomMargin = dp(4);
        touchArea.setLayoutParams(lp);
        return touchArea;
    }

    /** 标题行：「翻译设置」+ 当前目标语（选完立刻在这里回显，不用滚回列表找高亮）。 */
    private View buildHeader(Context context) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(context);
        title.setText(R.string.translate_panel_title);
        title.setTextColor(M3.color(context, M3.ON_SURFACE));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        row.addView(title, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        current = new TextView(context);
        current.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        current.setTextColor(M3.color(context, M3.ON_SURFACE_VARIANT));
        current.setEllipsize(TextUtils.TruncateAt.END);
        current.setMaxLines(1);
        current.setGravity(Gravity.END);
        row.addView(current, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        refreshCurrent(context);
        return row;
    }

    private View buildList(Context context) {
        column = new LinearLayout(context);
        column.setOrientation(VERTICAL);
        ScrollView scroll = new ScrollView(context);
        scroll.addView(column, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        LinearLayout cell = new LinearLayout(context);
        cell.setOrientation(VERTICAL);
        cell.setPadding(dp(2), 0, dp(2), 0);
        TextView label = new TextView(context);
        label.setText(R.string.translate_label_target);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        label.setTextColor(M3.color(context, M3.ON_SURFACE_VARIANT));
        cell.addView(label, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        cell.addView(scroll, new LayoutParams(LayoutParams.MATCH_PARENT, dp(LIST_HEIGHT_DP)));
        rebuildList(context);
        return cell;
    }

    /** 底部那句：语向已生效（原生硬编已在第二步撤掉，见 {@code MtPrompt}）。 */
    private View buildNote(Context context) {
        TextView note = new TextView(context);
        note.setText(R.string.translate_note_applied);
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        note.setTextColor(M3.color(context, M3.ON_SURFACE_VARIANT));
        LinearLayout.LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        note.setLayoutParams(lp);
        return note;
    }

    /** 按 {@link #selected} 重建列表行（选中行填 primaryContainer 底色）。 */
    private void rebuildList(Context context) {
        column.removeAllViews();
        for (LangCatalog.Lang lang : LangCatalog.ALL) {
            column.addView(buildRow(context, lang));
        }
    }

    private View buildRow(Context context, LangCatalog.Lang lang) {
        TextView row = new TextView(context);
        row.setText(lang.name);
        row.setTag(lang.code);
        row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        row.setPadding(dp(8), dp(6), dp(8), dp(6));
        boolean picked = selected.equals(lang.code);
        row.setTextColor(M3.color(context, picked ? M3.ON_PRIMARY_CONTAINER : M3.ON_SURFACE));
        row.setBackgroundColor(picked ? M3.color(context, M3.PRIMARY_CONTAINER) : 0);
        row.setOnClickListener(v -> {
            if (selected.equals(lang.code)) {
                return; // 重复点同一项不写 prefs、不 bump version
            }
            selected = lang.code;
            rebuildList(context);
            refreshCurrent(context);
            listener.onPick(lang.code);
        });
        return row;
    }

    private void refreshCurrent(Context context) {
        current.setText(context.getString(R.string.translate_current_tgt,
                LangCatalog.nameOf(selected)));
    }

    @Override
    public void handleRectInRoot(Rect out) {
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

    /**
     * 机读入口（照 {@code EXTRA_ANIM_TOGGLE} 的先例）：对 {@code code} 那行执行
     * {@link View#performClick()}——走的是手指点击<b>同一条</b> OnClickListener，唯独不经过触摸命中测试。
     * 这样能把"点了→落盘"与"点得中点不中"拆开证，后者仍归人。
     *
     * @return 列表里有没有这一行（点中同一项时监听器按设计直接 return，不写 prefs）
     */
    public boolean clickRowForTest(String code) {
        if (code == null) {
            return false;
        }
        for (int i = 0; i < column.getChildCount(); i++) {
            if (code.equals(column.getChildAt(i).getTag())) {
                column.getChildAt(i).performClick();
                return true;
            }
        }
        return false;
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }
}
