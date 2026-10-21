package com.flexiatom.floatingassistant.overlay;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.flexiatom.floatingassistant.R;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 统一悬浮文本编辑器（§4.4，m5-editor-asr-notes E1）。复用面板系那扇全 app 唯一焦点窗，
 * 交付编辑内核：键盘输入、光标、全选/复制/粘贴/撤销。ASR 插入口（§4.4-3）、输出出口
 * （§4.4-4/§4.5）与落库（E5）都不在内——本视图只认「打开时给初值、关闭时由调用方决定去留」。
 */
public final class FloatingEditorView extends LinearLayout implements PanelView {

    public interface Listener {
        void onClose();

        void onMove(int dx, int dy);
    }

    /** 编辑区高（dp）：比 {@link TextPanelView} 的展示正文高，编辑器的主战场就是这块。 */
    private static final int BODY_HEIGHT_DP = 300;
    /** 连续敲字合并成一笔撤销的静默窗口：600 ms 内的多次输入算一步。 */
    private static final long UNDO_MERGE_MS = 600L;
    private static final int UNDO_LIMIT = 50;

    private final Listener listener;
    private final PanelDragHandle handle;
    private final EditText body;

    private final Deque<String> undoStack = new ArrayDeque<>();
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
        titleView.setText(R.string.editor_title);
        titleView.setTextColor(M3.color(context, M3.ON_SURFACE));
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        addView(titleView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        body = new EditText(context);
        body.setTextColor(M3.color(context, M3.ON_SURFACE));
        body.setHintTextColor(M3.color(context, M3.ON_SURFACE_VARIANT));
        body.setHint(R.string.editor_hint);
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
        addView(scroll, new LayoutParams(LayoutParams.MATCH_PARENT, dp(BODY_HEIGHT_DP)));

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        addButtonRow(context, row, R.string.editor_select_all, this::selectAll);
        addButtonRow(context, row, R.string.editor_copy, this::copySelection);
        addButtonRow(context, row, R.string.editor_paste, this::pasteAtCursor);
        addButtonRow(context, row, R.string.editor_undo, this::undo);
        addView(row, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    }

    private void addButtonRow(Context context, LinearLayout row, int labelRes, Runnable action) {
        Button button = PanelStyles.tonalButton(context, context.getString(labelRes));
        button.setOnClickListener(v -> action.run());
        LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(6);
        row.addView(button, lp);
    }

    private void selectAll() {
        body.requestFocus();
        body.selectAll();
    }

    /** 有选区复制选区，无选区复制全文——overlay 窗里系统选择工具栏不可依赖（E1 反证卡③）。 */
    private void copySelection() {
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
    private void pasteAtCursor() {
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
        mainHandler.removeCallbacks(undoFlush);
        if (pendingUndoBase != null) {
            pushUndo(pendingUndoBase);
            pendingUndoBase = null;
        }
        if (undoStack.isEmpty()) {
            return;
        }
        String previous = undoStack.removeLast();
        applyingUndo = true;
        body.setText(previous);
        body.setSelection(previous.length());
        applyingUndo = false;
    }

    private void pushUndo(String snapshot) {
        undoStack.addLast(snapshot);
        if (undoStack.size() > UNDO_LIMIT) {
            undoStack.removeFirst();
        }
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
