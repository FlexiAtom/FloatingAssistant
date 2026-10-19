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
 * 语向选择面板（m2-translation-settings 第一步）：左列选**原语言**、右列选**目标语言**，点一行即落盘。
 *
 * <p>与 {@link BlacklistPanelView} 同一取向——本视图不认识 prefs 与翻译流程，只回调
 * {@link Listener#onPickLang}；语言表来自 {@link LangCatalog}（官方模型卡抄下来的 38 项），
 * 本类不硬编码任何语言名。
 *
 * <p><b>底部那句话现在说什么</b>：原生层的硬编方向已在第二步撤掉（提示词改由 Java 侧
 * {@code MtPrompt} 按官方句式组装，{@code nativeTranslate} 只收完整 user 消息），所以那句
 * "当前仅中→英生效"已随之下线。留下的这句只讲两件事：语向会生效，以及**左列不参与提示词**
 * ——官方所有句式都只有 {@code {target_lang}}，源语言由模型自行判定。不写后半句就是让一列摆设冒充设置。
 */
public final class TranslatePanelView extends LinearLayout implements PanelView {

    public interface Listener {
        /** 用户点了一侧的语向。{@code isSource=true} 改原语言，否则改目标语言。 */
        void onPickLang(boolean isSource, String code);

        /** 用户请求关闭面板（手柄双击 / 返回键）。 */
        void onClose();

        /** 手柄拖曳：请求把面板窗移动 ({@code dx},{@code dy}) 屏幕像素增量（正=右下）。 */
        void onMove(int dx, int dy);
    }

    /** 单侧列表的固定高度（dp）；38 项走滚动，不撑爆卡片。 */
    private static final int LIST_HEIGHT_DP = 300;

    private final Listener listener;
    private final String[] selected = new String[2]; // [0]=源, [1]=目标
    private final LinearLayout[] columns = new LinearLayout[2];
    private TextView pair;
    private PanelDragHandle handle;

    public TranslatePanelView(Context context, String srcCode, String tgtCode, Listener listener) {
        super(context);
        this.listener = listener;
        this.selected[0] = srcCode;
        this.selected[1] = tgtCode;

        setOrientation(VERTICAL);
        int pad = dp(12);
        setPadding(pad, pad, pad, pad);
        setBackground(PanelStyles.card(context));
        setFocusableInTouchMode(true); // 返回键要落到本视图树的 dispatchKeyEvent

        addView(buildHandle(context));
        addView(buildHeader(context));
        addView(buildColumns(context));
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

    /** 标题行：「翻译设置」+ 当前语向摘要（{@code 中文 → 英语}）。 */
    private View buildHeader(Context context) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(context);
        title.setText(R.string.translate_panel_title);
        title.setTextColor(M3.color(context, M3.ON_SURFACE));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        row.addView(title, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        pair = new TextView(context);
        pair.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        pair.setTextColor(M3.color(context, M3.ON_SURFACE_VARIANT));
        pair.setEllipsize(TextUtils.TruncateAt.END);
        pair.setMaxLines(1);
        pair.setGravity(Gravity.END);
        row.addView(pair, weighted(1f));
        refreshPair(context);
        return row;
    }

    private View buildColumns(Context context) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(HORIZONTAL);

        for (int side = 0; side < 2; side++) {
            LinearLayout column = new LinearLayout(context);
            column.setOrientation(VERTICAL);
            columns[side] = column;

            ScrollView scroll = new ScrollView(context);
            scroll.addView(column, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

            LinearLayout cell = new LinearLayout(context);
            cell.setOrientation(VERTICAL);
            cell.setPadding(dp(2), 0, dp(2), 0);
            TextView label = new TextView(context);
            label.setText(side == 0
                    ? R.string.translate_label_source : R.string.translate_label_target);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            label.setTextColor(M3.color(context, M3.ON_SURFACE_VARIANT));
            cell.addView(label, new LayoutParams(LayoutParams.MATCH_PARENT,
                    LayoutParams.WRAP_CONTENT));
            cell.addView(scroll, new LayoutParams(LayoutParams.MATCH_PARENT, dp(LIST_HEIGHT_DP)));
            row.addView(cell, weighted(1f));
        }
        rebuildColumns(context);
        return row;
    }

    /** 底部那句诚实边界（见类注释）。 */
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

    /** 按 {@link #selected} 重建两列的行（选中行填 primaryContainer 底色）。 */
    private void rebuildColumns(Context context) {
        for (int side = 0; side < 2; side++) {
            LinearLayout column = columns[side];
            column.removeAllViews();
            for (LangCatalog.Lang lang : LangCatalog.ALL) {
                column.addView(buildRow(context, side, lang));
            }
        }
    }

    private View buildRow(Context context, final int side, LangCatalog.Lang lang) {
        TextView row = new TextView(context);
        row.setText(lang.name);
        row.setTag(lang.code);
        row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        row.setPadding(dp(8), dp(6), dp(8), dp(6));
        boolean picked = selected[side].equals(lang.code);
        row.setTextColor(M3.color(context, picked ? M3.ON_PRIMARY_CONTAINER : M3.ON_SURFACE));
        row.setBackgroundColor(picked ? M3.color(context, M3.PRIMARY_CONTAINER) : 0);
        row.setOnClickListener(v -> {
            if (selected[side].equals(lang.code)) {
                return; // 重复点同一项不写 prefs、不 bump version
            }
            selected[side] = lang.code;
            rebuildColumns(context);
            refreshPair(context);
            listener.onPickLang(side == 0, lang.code);
        });
        return row;
    }

    private void refreshPair(Context context) {
        pair.setText(context.getString(R.string.translate_pair_format,
                LangCatalog.nameOf(selected[0]), LangCatalog.nameOf(selected[1])));
    }

    /**
     * 机读入口（照 {@code EXTRA_ANIM_TOGGLE} 的先例）：对某列里 {@code code} 那行执行
     * {@link View#performClick()}——走的是手指点击<b>同一条</b> OnClickListener，唯独不经过触摸命中测试。
     * 这样能把 T2 的两件事拆开证：「写了没有」由这里验，「点得中点不中」仍归人。
     *
     * @return 该列里找到 {@code code} 这一行（{@code performClick} 的返回值只表示挂了监听器，
     *         点中同一项时监听器按设计直接 return，不写 prefs）
     */
    public boolean clickRowForTest(boolean isSource, String code) {
        if (code == null) {
            return false;
        }
        LinearLayout column = columns[isSource ? 0 : 1];
        for (int i = 0; i < column.getChildCount(); i++) {
            if (code.equals(column.getChildAt(i).getTag())) {
                column.getChildAt(i).performClick();
                return true;
            }
        }
        return false;
    }

    private LayoutParams weighted(float weight) {
        LayoutParams lp = new LayoutParams(0, LayoutParams.WRAP_CONTENT);
        lp.weight = weight;
        return lp;
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

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }
}
