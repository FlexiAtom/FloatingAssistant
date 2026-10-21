package com.flexiatom.floatingassistant.overlay;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.TextView;

import com.flexiatom.floatingassistant.R;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 统一悬浮文本编辑器（§4.4，m5-editor-asr-notes E1b 终稿壳）。复用面板系那扇全 app 唯一焦点窗。
 *
 * <p>布局模型（人裁 2026-10-21，草图 {@code ~/Untitled.*}）：**单窗固定宽，编辑器恒满宽；
 * 文档列表＝同窗左栏抽屉，盖住编辑器左半**（b 案·遮挡），展开时非抽屉区加半透明遮罩变暗，
 * 遮罩吃掉编辑器一切点击——含顶栏钮——**只用于收起**（防误触）。
 * 顶栏：{@code ≡  ︶  / ⋮} ＝ 列表、撤销、重做、保存、编辑工具、溢出；
 * 信息带：文档名（左）+ 编码（右）。💾 随 E5、/ 随 E8、⋮ 随 E6 点亮，现为禁用占位。
 *
 * <p>图标：除软盘（人供 SVG 几何件，{@code ic_floppy.xml}）外全部 {@link SketchIcons} 运行时现绘。
 * 落库（E5）、ASR 插入口（§4.4-3）与输出出口（E3）都不在内——本视图只认「打开时给初值、关闭时由调用方决定去留」。
 */
public final class FloatingEditorView extends FrameLayout implements PanelView {

    public interface Listener {
        void onClose();

        void onMove(int dx, int dy);
    }

    /** 编辑区高（dp）：与 E1 同值，编辑器的主战场。 */
    private static final int BODY_HEIGHT_DP = 300;
    /** 连续敲字合并成一笔撤销的静默窗口：600 ms 内的多次输入算一步。 */
    private static final long UNDO_MERGE_MS = 600L;
    private static final int UNDO_LIMIT = 50;
    /** 抽屉宽＝窗宽 1/3（草图比例）。 */
    private static final int DRAWER_WIDTH_DP = 100;
    /** 遮罩黑度：标准抽屉 scrim 档。 */
    private static final float SCRIM_ALPHA = 0.32f;

    private final Listener listener;
    private final LinearLayout editor;
    private final PanelDragHandle handle;
    private final EditText body;
    private final View scrim;
    private final View drawer;
    private final ImageButton undoButton;
    private final ImageButton redoButton;

    private final Deque<String> undoStack = new ArrayDeque<>();
    private final Deque<String> redoStack = new ArrayDeque<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    /** 一段连续编辑的起点文本；静默窗口到点才入栈，避免每敲一个字存一份。 */
    private String pendingUndoBase;
    private final Runnable undoFlush = () -> {
        if (pendingUndoBase != null) {
            pushUndo(pendingUndoBase);
            pendingUndoBase = null;
        }
    };
    private boolean applyingUndo;

    public FloatingEditorView(Context context, Listener listener) {
        super(context);
        this.listener = listener;

        float density = getResources().getDisplayMetrics().density;
        int onSurface = M3.color(context, M3.ON_SURFACE);
        int onVariant = M3.color(context, M3.ON_SURFACE_VARIANT);

        setBackground(PanelStyles.card(context));
        setClipToOutline(true);
        setFocusableInTouchMode(true);

        // ---------- 编辑器层（恒满宽，被抽屉盖住也不改尺寸） ----------
        editor = new LinearLayout(context);
        editor.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(12);
        editor.setPadding(pad, dp(4), pad, pad);
        addView(editor, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

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
        editor.addView(handle, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, dp(18)));

        LinearLayout toolbar = new LinearLayout(context);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton listButton = toolButton(context, SketchIcons.of(SketchIcons.HAMBURGER, onSurface, density),
                R.string.editor_cd_list, v -> openDrawer());
        toolbar.addView(listButton);
        toolbar.addView(new Space(context), new LinearLayout.LayoutParams(0, 1, 1f));
        undoButton = toolButton(context, SketchIcons.of(SketchIcons.UNDO, onSurface, density),
                R.string.editor_cd_undo, v -> undo());
        toolbar.addView(undoButton);
        redoButton = toolButton(context, SketchIcons.of(SketchIcons.REDO, onSurface, density),
                R.string.editor_cd_redo, v -> redo());
        toolbar.addView(redoButton);
        toolbar.addView(disabledToolButton(context, icon(context, R.drawable.ic_floppy, onSurface),
                R.string.editor_cd_save));
        toolbar.addView(disabledToolButton(context, SketchIcons.of(SketchIcons.SLASH, onSurface, density),
                R.string.editor_cd_tools));
        toolbar.addView(disabledToolButton(context, SketchIcons.of(SketchIcons.DOTS, onSurface, density),
                R.string.editor_cd_more));
        syncEditState();
        editor.addView(toolbar, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        editor.addView(divider(context), dividerLp());

        LinearLayout strip = new LinearLayout(context);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setGravity(Gravity.CENTER_VERTICAL);
        TextView docName = new TextView(context);
        docName.setText(R.string.editor_untitled);
        docName.setTextColor(onVariant);
        docName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        strip.addView(docName, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        TextView encoding = new TextView(context);
        encoding.setText("UTF-8");
        encoding.setTextColor(onVariant);
        encoding.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        strip.addView(encoding);
        editor.addView(strip, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        editor.addView(divider(context), dividerLp());

        body = new EditText(context);
        body.setTextColor(onSurface);
        body.setHintTextColor(onVariant);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        body.setBackground(null);
        body.setGravity(Gravity.TOP | Gravity.START);
        body.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        // 焦点型悬浮窗里「点」输入框才拉得起键盘（BlacklistPanelView / TextPanelView 两条同型实测）。
        body.setOnClickListener(v -> {
            InputMethodManager imm = (InputMethodManager)
                    v.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT);
            }
        });
        body.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                if (applyingUndo) {
                    return;
                }
                if (pendingUndoBase == null) {
                    pendingUndoBase = s.toString();
                }
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (applyingUndo) {
                    return;
                }
                mainHandler.removeCallbacks(undoFlush);
                mainHandler.postDelayed(undoFlush, UNDO_MERGE_MS);
            }
        });
        ScrollView scroll = new ScrollView(context);
        scroll.addView(body, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        editor.addView(scroll, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, dp(BODY_HEIGHT_DP)));

        // ---------- 遮罩层：盖整窗，任何点击＝只收起（人裁防误触，含顶栏钮） ----------
        scrim = new View(context);
        scrim.setBackgroundColor(M3.alpha(0xFF000000, SCRIM_ALPHA));
        scrim.setClickable(true);
        scrim.setOnClickListener(v -> closeDrawer());
        scrim.setVisibility(GONE);
        addView(scrim, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        // ---------- 抽屉层：左栏文档列表，压在遮罩之上 ----------
        drawer = new LinearLayout(context);
        ((LinearLayout) drawer).setOrientation(LinearLayout.VERTICAL);
        GradientDrawable drawerBg = new GradientDrawable();
        drawerBg.setColor(M3.color(context, M3.SURFACE_CONTAINER_HIGH) | 0xFF000000);
        float r = dp(12);
        drawerBg.setCornerRadii(new float[]{r, r, 0, 0, 0, 0, r, r});
        drawer.setBackground(drawerBg);
        drawer.setPadding(0, dp(4), dp(6), dp(12));
        drawer.setVisibility(GONE);

        LinearLayout drawerHeader = new LinearLayout(context);
        drawerHeader.setOrientation(LinearLayout.HORIZONTAL);
        drawerHeader.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        TextView drawerTitle = new TextView(context);
        drawerTitle.setText(R.string.editor_drawer_title);
        drawerTitle.setTextColor(onSurface);
        drawerTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        drawerHeader.addView(drawerTitle, new LinearLayout.LayoutParams(
                0, LayoutParams.WRAP_CONTENT, 1f));
        drawerHeader.addView(toolButton(context,
                SketchIcons.of(SketchIcons.SQUARE, onSurface, density),
                R.string.editor_cd_new, v -> { /* E5：新建文档 */ }));
        ((LinearLayout) drawer).addView(drawerHeader, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, dp(40)));
        ((LinearLayout) drawer).addView(divider(context), dividerLp());

        EditText search = new EditText(context);
        search.setHint(R.string.editor_search_hint);
        search.setTextColor(onSurface);
        search.setHintTextColor(onVariant);
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT);
        search.setPadding(dp(6), dp(4), dp(6), dp(4));
        GradientDrawable searchBg = new GradientDrawable();
        searchBg.setColor(0);
        searchBg.setCornerRadius(dp(6));
        searchBg.setStroke(dp(1), M3.color(context, M3.OUTLINE_VARIANT));
        search.setBackground(searchBg);
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        searchLp.setMargins(dp(6), dp(6), 0, dp(6));
        ((LinearLayout) drawer).addView(search, searchLp);

        // 列表区：E5 换 RecyclerView，今天占位直通到底。
        ((LinearLayout) drawer).addView(new Space(context), new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, 0, 1f));
        addView(drawer, new LayoutParams(dp(DRAWER_WIDTH_DP), LayoutParams.MATCH_PARENT));
    }

    private ImageButton toolButton(Context context, android.graphics.drawable.Drawable icon,
                                   int descRes, android.view.View.OnClickListener onClick) {
        ImageButton b = new ImageButton(context);
        b.setImageDrawable(icon);
        b.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        b.setBackgroundColor(0);
        b.setContentDescription(context.getString(descRes));
        b.setOnClickListener(onClick);
        int side = dp(40);
        b.setPadding(dp(8), dp(8), dp(8), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(side, side);
        b.setLayoutParams(lp);
        return b;
    }

    private ImageButton disabledToolButton(Context context, android.graphics.drawable.Drawable icon,
                                           int descRes) {
        ImageButton b = toolButton(context, icon, descRes, null);
        b.setEnabled(false);
        b.setAlpha(0.38f);
        return b;
    }

    private android.graphics.drawable.Drawable icon(Context context, int resId, int tintColor) {
        android.graphics.drawable.Drawable d =
                androidx.core.content.ContextCompat.getDrawable(context, resId);
        if (d != null) {
            d.setTintList(ColorStateList.valueOf(tintColor));
        }
        return d;
    }

    private View divider(Context context) {
        View v = new View(context);
        v.setBackgroundColor(M3.alpha(M3.color(context, M3.OUTLINE_VARIANT), 0.6f));
        return v;
    }

    private LinearLayout.LayoutParams dividerLp() {
        return new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
    }

    private boolean drawerOpen() {
        return drawer.getVisibility() == VISIBLE;
    }

    private void openDrawer() {
        scrim.setVisibility(VISIBLE);
        drawer.setVisibility(VISIBLE);
    }

    private void closeDrawer() {
        scrim.setVisibility(GONE);
        drawer.setVisibility(GONE);
    }

    // ---------- 编辑内核：E1 交付物 + E1b 补重做；全选/复制/粘贴留作 E8「/」菜单的接线件 ----------

    void selectAllText() {
        body.requestFocus();
        body.selectAll();
    }

    /** 有选区复制选区，无选区复制全文——overlay 窗里系统选择工具栏不可依赖（E1 反证卡③）。 */
    void copySelection() {
        String text = body.getText().toString();
        int start = body.getSelectionStart();
        int end = body.getSelectionEnd();
        String payload = (start >= 0 && end > start) ? text.substring(start, end) : text;
        if (payload.isEmpty()) {
            return;
        }
        ClipboardManager cm = (ClipboardManager)
                getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("editor", payload));
        }
    }

    /** 粘贴走 Editable.replace：光标处替换选区插入，且天然被 TextWatcher 记进撤销栈。 */
    void pasteAtCursor() {
        ClipboardManager cm = (ClipboardManager)
                getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip().getItemCount() == 0) {
            return;
        }
        CharSequence pasted = cm.getPrimaryClip().getItemAt(0).getText();
        if (pasted == null) {
            return;
        }
        Editable editable = body.getText();
        int start = Math.max(body.getSelectionStart(), 0);
        int end = Math.max(body.getSelectionEnd(), start);
        editable.replace(start, end, pasted);
    }

    private void undo() {
        flushPending();
        if (undoStack.isEmpty()) {
            return;
        }
        redoStack.addLast(body.getText().toString());
        applySnapshot(undoStack.removeLast());
    }

    private void redo() {
        flushPending();
        if (redoStack.isEmpty()) {
            return;
        }
        undoStack.addLast(body.getText().toString());
        applySnapshot(redoStack.removeLast());
    }

    private void flushPending() {
        mainHandler.removeCallbacks(undoFlush);
        if (pendingUndoBase != null) {
            pushUndo(pendingUndoBase);
            pendingUndoBase = null;
        }
    }

    private void applySnapshot(String snapshot) {
        applyingUndo = true;
        body.setText(snapshot);
        body.setSelection(snapshot.length());
        applyingUndo = false;
        syncEditState();
    }

    private void pushUndo(String snapshot) {
        undoStack.addLast(snapshot);
        if (undoStack.size() > UNDO_LIMIT) {
            undoStack.removeFirst();
        }
        redoStack.clear();
        syncEditState();
    }

    private void syncEditState() {
        undoButton.setAlpha(undoStack.isEmpty() && pendingUndoBase == null ? 0.38f : 1f);
        redoButton.setAlpha(redoStack.isEmpty() ? 0.38f : 1f);
    }

    /** 正文当前文本：机读与 E2 插入口（§4.4-3）的落点，形状同 {@link TextPanelView#bodyText()}。 */
    public String bodyText() {
        return body.getText().toString();
    }

    /** 量测用：模拟人已打字（E2/E4 机读前置），走真实 Editable 通路。 */
    public void setBodyForTest(String text) {
        body.setText(text);
    }

    /** 量测用：走「点正文拉键盘」真实路径，回 IME 状态串（同 {@link TextPanelView#imeTapForTest()}）。 */
    public String imeTapForTest() {
        boolean before = body.isFocused();
        body.requestFocus();
        body.performClick();
        InputMethodManager imm = (InputMethodManager)
                body.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        return "focusBefore=" + before + " focusAfter=" + body.isFocused()
                + " token=" + (body.getWindowToken() != null)
                + " immActive=" + (imm != null && imm.isActive(body))
                + " acceptingText=" + (imm != null && imm.isAcceptingText())
                + " shown=" + body.isShown();
    }

    /** 量测用：抽屉当前是否展开。 */
    public boolean isDrawerOpenForTest() {
        return drawerOpen();
    }

    /**
     * 根视图的高**只由编辑器层决定**。抽屉与遮罩都是 {@code MATCH_PARENT} 子项：放任默认的
     * WRAP_CONTENT 测量，FrameLayout 会把它们量成「当前可用高度」，面板当场被顶满全屏——而
     * {@code FLAG_LAYOUT_NO_LIMITS} 下可用高度又随窗长高，展开抽屉即变成人测卡②报的**无限拉长**。
     * 先用同一规格量出编辑器自然高，再以 EXACTLY(该高) 走标准测量，两个覆盖层正好钉满卡片高。
     */
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }
        editor.measure(widthMeasureSpec, heightMeasureSpec);
        super.onMeasure(widthMeasureSpec,
                MeasureSpec.makeMeasureSpec(editor.getMeasuredHeight(), MeasureSpec.EXACTLY));
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
            if (drawerOpen()) {
                closeDrawer(); // 抽屉态：第一下返回收抽屉，面板不动
            } else {
                listener.onClose();
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }
}
