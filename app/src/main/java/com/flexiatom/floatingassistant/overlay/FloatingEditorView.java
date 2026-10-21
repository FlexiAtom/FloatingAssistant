package com.flexiatom.floatingassistant.overlay;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.TextView;

import com.flexiatom.floatingassistant.R;
import com.flexiatom.floatingassistant.core.NotesCodec;
import com.flexiatom.floatingassistant.core.NotesStore;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * 统一悬浮文本编辑器（§4.4，m5-editor-asr-notes E1b 终稿壳）。复用面板系那扇全 app 唯一焦点窗。
 *
 * <p>布局模型（人裁 2026-10-21，草图 {@code ~/Untitled.*}）：**单窗固定宽，编辑器恒满宽；
 * 文档列表＝同窗左栏抽屉，盖住编辑器左半**（b 案·遮挡），展开时非抽屉区加半透明遮罩变暗，
 * 遮罩吃掉编辑器一切点击——含顶栏钮——**只用于收起**（防误触）。
 * 顶栏：{@code ≡  ︶  / ⋮} ＝ 列表、撤销、重做、保存、编辑工具、溢出；
 * 信息带：文档名（左）+ 编码（右）。{@code /}＝**行级与全文级**工具（走 {@link DrawerContent}），
 * 选区级动作一律走**选择气泡**（复制/剪切/粘贴/全选/朗读/翻译）；无选区时长按正文唤起同一只气泡的
 * 行级五项（复制行/剪切行/粘贴/朗读/翻译），作用光标所在行——两套内容的分法皆人裁 2026-10-22；
 * 💾 已随 E5 点亮（落库走 {@link NotesStore}），⋮ 随 E6 点亮，仍为禁用占位。
 *
 * <p>图标：除软盘（人供 SVG 几何件，{@code ic_floppy.xml}）外全部 {@link SketchIcons} 运行时现绘。
 * 朗读与翻译不在本视图里造引擎，经 {@link Listener} 交回服务层用现成 TTS/MT 跑。
 * <b>但记事本的落库在本视图里</b>：E5 之后「打开＝接着上次那篇读、停手/换篇/关面板＝写回文件」全是视图的
 * 责任，服务层不插手——{@link NotesStore} 是存储层不是引擎（无模型、无并发预算），沿用仓内
 * {@code SettingsStore} 那套"谁要用谁自己 {@code of(context)}"的多实例口径，不另开注入通路。
 * ASR 插入口（§4.4-3，E2）仍未接。
 */
public final class FloatingEditorView extends FrameLayout implements PanelView {

    public interface Listener {
        void onClose();

        void onMove(int dx, int dy);

        /**
         * 送翻译（E3 出口）：源文已在视图侧按口径取好，模型与线程都归服务侧；
         * 结果**回主线程**交给 {@code onResult}，失败或空回 {@code null}（视图不动，提示由服务侧浮层给）。
         */
        void onTranslate(String source, java.util.function.Consumer<String> onResult);

        /** 送 TTS（E3 出口）：开关语义在引擎侧——正在读时再按＝停。 */
        void onSpeak(String text);

        /** 当下是否在朗读：抽屉条目标签「朗读」/「取消」由它定（与服务侧面板钮同一条规则）。 */
        boolean isSpeaking();
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
    private final BodyEditText body;
    private final ScrollView bodyScroll;
    private final View scrim;
    private final LinearLayout drawer;
    private final TextView drawerTitle;
    private final ImageButton drawerHeaderAction;
    private final EditText drawerSearch;
    private final LinearLayout drawerList;
    private final LinearLayout bubble;
    private Button bubbleSpeak;
    /** 气泡当前挂的是哪一套项：0＝还没装，1＝选区级六项，2＝行级五项。 */
    private int bubbleItems;
    /** 长按唤起的行气泡是否有效：选区一形成、或光标另落一次就顶掉。 */
    private boolean lineBubblePinned;
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

    // ---------- E5 记事本：一篇一个正文文件，身份与脏判都记在这里 ----------

    /** 自动落库的静默窗口：停手这么久才写盘，不每个字盘一次（同撤销合并的思路，只是更长）。 */
    private static final long AUTOSAVE_MERGE_MS = 1200L;
    /** 搜索框输入合并窗口：比落库短，边打边看结果要跟得上手。 */
    private static final long DOC_SEARCH_DEBOUNCE_MS = 220L;
    private final NotesStore notes;
    private final TextView docName;
    private final Runnable autosaveTick = this::persistCurrent;
    /** 写成方法引用而不是就地 lambda：字段初始化期 {@code drawerSearch} 还没赋值，javac 按 definite assignment 会拒。 */
    private final Runnable docSearchTick = this::runDocSearch;
    /** 当下这篇的身份；null＝新建、还没落过库。 */
    private String docId;
    /** 上一次落库的内容——与正文相等就不写盘。 */
    private String savedText = "";
    /** 一次写只在途一份：回调之后再做脏判补写，免得同一篇被并发写出两个身份。 */
    private boolean saveInFlight;
    /** 构造时那次「取最近一篇」是否仍有效：人已经动过正文就作废，不把输入盖掉。 */
    private boolean initialLoadPending = true;
    /** 当前抽屉装的是否文档列表——搜索框只对它有反应，{@code /} 菜单那格没有搜索框。 */
    private boolean docListShowing;
    /** 列表刷新与搜索共用一个代次：只认最后一次请求的结果，早到的旧结果丢掉。 */
    private int listSeq;

    public FloatingEditorView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        this.notes = NotesStore.of(context);

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
                R.string.editor_cd_list, v -> openDocDrawer());
        toolbar.addView(listButton);
        toolbar.addView(new Space(context), new LinearLayout.LayoutParams(0, 1, 1f));
        undoButton = toolButton(context, SketchIcons.of(SketchIcons.UNDO, onSurface, density),
                R.string.editor_cd_undo, v -> undo());
        toolbar.addView(undoButton);
        redoButton = toolButton(context, SketchIcons.of(SketchIcons.REDO, onSurface, density),
                R.string.editor_cd_redo, v -> redo());
        toolbar.addView(redoButton);
        // 💾 随 E5 点亮（人裁 2026-10-21：□＝软盘保存钮）
        toolbar.addView(toolButton(context, icon(context, R.drawable.ic_floppy, onSurface),
                R.string.editor_cd_save, v -> persistCurrent()));
        toolbar.addView(toolButton(context, SketchIcons.of(SketchIcons.SLASH, onSurface, density),
                R.string.editor_cd_tools, v -> openDrawer(editToolsContent(context))));
        toolbar.addView(disabledToolButton(context, SketchIcons.of(SketchIcons.DOTS, onSurface, density),
                R.string.editor_cd_more));
        syncEditState();
        editor.addView(toolbar, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        editor.addView(divider(context), dividerLp());

        LinearLayout strip = new LinearLayout(context);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(context);
        title.setText(R.string.editor_untitled);
        title.setTextColor(onVariant);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        docName = title;
        strip.addView(title, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        TextView encoding = new TextView(context);
        encoding.setText("UTF-8");
        encoding.setTextColor(onVariant);
        encoding.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        strip.addView(encoding);
        editor.addView(strip, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        editor.addView(divider(context), dividerLp());

        body = new BodyEditText(context);
        body.setTextColor(onSurface);
        body.setHintTextColor(onVariant);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        body.setBackground(null);
        body.setGravity(Gravity.TOP | Gravity.START);
        body.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        // 手柄球垂在行下方，最后那一行的球会被 ScrollView 的下边缘切掉——给正文垫出这一颗的位子。
        // 只动 bottom：气泡定位那套算法用的是 layout 行顶，paddingTop 一变它整个跟着上浮，别碰。
        body.setPadding(body.getPaddingLeft(), body.getPaddingTop(),
                body.getPaddingRight(), dp(16));
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
                initialLoadPending = false; // 人已动笔：构造时那次「取最近一篇」不许再盖掉输入
                mainHandler.removeCallbacks(autosaveTick);
                mainHandler.postDelayed(autosaveTick, AUTOSAVE_MERGE_MS);
            }
        });
        bodyScroll = new ScrollView(context);
        bodyScroll.addView(body, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        editor.addView(bodyScroll, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, dp(BODY_HEIGHT_DP)));

        // ---------- 遮罩层：盖整窗，任何点击＝只收起（人裁防误触，含顶栏钮） ----------
        scrim = new View(context);
        scrim.setBackgroundColor(M3.alpha(0xFF000000, SCRIM_ALPHA));
        scrim.setClickable(true);
        scrim.setOnClickListener(v -> closeDrawer());
        scrim.setVisibility(GONE);
        addView(scrim, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        // ---------- 抽屉层：一个抽屉位、N 种内容——外壳固定，栏头与条目由按键传入（bindDrawer） ----------
        drawer = new LinearLayout(context);
        drawer.setOrientation(LinearLayout.VERTICAL);
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
        drawerTitle = new TextView(context);
        drawerTitle.setTextColor(onSurface);
        drawerTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        drawerHeader.addView(drawerTitle, new LinearLayout.LayoutParams(
                0, LayoutParams.WRAP_CONTENT, 1f));
        drawerHeaderAction = toolButton(context,
                SketchIcons.of(SketchIcons.SQUARE, onSurface, density), R.string.editor_cd_new, null);
        drawerHeader.addView(drawerHeaderAction);
        drawer.addView(drawerHeader, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, dp(40)));
        drawer.addView(divider(context), dividerLp());

        drawerSearch = new EditText(context);
        drawerSearch.setHint(R.string.editor_search_hint);
        drawerSearch.setTextColor(onSurface);
        drawerSearch.setHintTextColor(onVariant);
        drawerSearch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        drawerSearch.setSingleLine(true);
        drawerSearch.setInputType(InputType.TYPE_CLASS_TEXT);
        drawerSearch.setPadding(dp(6), dp(4), dp(6), dp(4));
        GradientDrawable searchBg = new GradientDrawable();
        searchBg.setColor(0);
        searchBg.setCornerRadius(dp(6));
        searchBg.setStroke(dp(1), M3.color(context, M3.OUTLINE_VARIANT));
        drawerSearch.setBackground(searchBg);
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        searchLp.setMargins(dp(6), dp(6), 0, dp(6));
        drawer.addView(drawerSearch, searchLp);

        drawerList = new LinearLayout(context);
        drawerList.setOrientation(LinearLayout.VERTICAL);
        ScrollView listScroll = new ScrollView(context);
        listScroll.addView(drawerList, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        drawer.addView(listScroll, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));
        addView(drawer, new LayoutParams(dp(DRAWER_WIDTH_DP), LayoutParams.MATCH_PARENT));

        // ---------- 选择气泡（压在一切之上）：选区一形成就弹，贴选区首行上方，顶边放不下就翻到下方 ----------
        bubble = new LinearLayout(context);
        bubble.setOrientation(LinearLayout.HORIZONTAL);
        bubble.setGravity(Gravity.CENTER_VERTICAL);
        bubble.setClickable(true); // 自身点击不外泄给正文，否则点钮会被当成挪光标而把气泡收掉
        bubble.setPadding(dp(2), 0, dp(2), 0);
        GradientDrawable bubbleBg = new GradientDrawable();
        bubbleBg.setColor(M3.color(context, M3.INVERSE_SURFACE));
        bubbleBg.setCornerRadius(dp(8));
        bubble.setBackground(bubbleBg);
        setBubbleItems(false);
        bubble.setVisibility(GONE);
        addView(bubble, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        body.onSelectionChanged = this::updateBubbleForSelection;
        // 长按＝唤起「行气泡」（人裁 2026-10-22：无选区也得能召出气泡来读/译一行）。
        // 接管点在 BodyEditText.performLongClick，不是 setOnLongClickListener：后者在这里永远跑不到
        // （人测卡⑤-b「目前无法长按」）——TextView 到点把长按整个交给它自己的 TouchHelper 去选词。
        // 按住不动＝行气泡；按住再拖＝拖曳扩选（这面板压根不画系统选择手柄，那条得自己接，见 BodyEditText）；
        // 单击仍是挪光标，选词靠双击。
        body.onLongPressForLine = () -> {
            body.setSelection(body.getOffsetForPosition(body.lastDownX, body.lastDownY));
            lineBubblePinned = true;
            updateBubbleForSelection();
        };

        // ---------- E5：搜索框接列表过滤（只在文档列表那格生效），并接着上次那篇开 ----------
        drawerSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (!docListShowing) {
                    return; // / 那格没有搜索框，别让它显示时也在背后发查询
                }
                mainHandler.removeCallbacks(docSearchTick);
                mainHandler.postDelayed(docSearchTick, DOC_SEARCH_DEBOUNCE_MS);
            }
        });
        loadLastDoc();
    }

    /**
     * 气泡的两套内容（人裁 2026-10-22 的作用域分法）：有选区＝选区级，长按唤起的无选区＝光标所在行。
     * 换的是同一只气泡的子项，不建第二只——两套都要压在抽屉之上、都要跟着窗口走。
     */
    private void setBubbleItems(boolean lineItems) {
        int want = lineItems ? 2 : 1;
        if (bubbleItems == want) {
            return;
        }
        bubbleItems = want;
        bubble.removeAllViews();
        if (lineItems) {
            bubble.addView(bubbleAction(R.string.editor_line_copy, this::copyLine));
            bubble.addView(bubbleAction(R.string.editor_line_cut, this::cutLine));
            // 粘贴留给光标本身：长按后想贴东西，没有选区也得能贴（长按从此不唤系统那个粘贴菜单）
            bubble.addView(bubbleAction(R.string.editor_sel_paste, this::pasteAtCursor));
            bubbleSpeak = bubbleAction(R.string.action_speak, this::speakOutlet);
            bubble.addView(bubbleSpeak);
            bubble.addView(bubbleAction(R.string.editor_translate, this::translateOutlet));
        } else {
            bubble.addView(bubbleAction(R.string.action_copy, this::copySelection));
            bubble.addView(bubbleAction(R.string.editor_sel_cut, this::cutSelection));
            bubble.addView(bubbleAction(R.string.editor_sel_paste, this::pasteAtCursor));
            bubble.addView(bubbleAction(R.string.editor_sel_all, this::selectAllText));
            bubbleSpeak = bubbleAction(R.string.action_speak, this::speakOutlet);
            bubble.addView(bubbleSpeak);
            bubble.addView(bubbleAction(R.string.editor_translate, this::translateOutlet));
        }
    }

    /** 气泡里的一条：反色底上的扁平短钮，尺寸只由文字与内边距定（六条要挤进 300dp 宽）。 */
    private Button bubbleAction(int labelRes, Runnable action) {
        Context context = getContext();
        Button b = new Button(context);
        b.setText(labelRes);
        b.setAllCaps(false);
        b.setFocusable(false);
        b.setTextColor(M3.color(context, M3.ON_INVERSE_SURFACE));
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        b.setBackgroundColor(0);
        b.setStateListAnimator(null);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(7), dp(4), dp(7), dp(4));
        b.setOnClickListener(v -> action.run());
        return b;
    }

    /**
     * 选区一形成就弹、一收起就藏（人裁 2026-10-22）；无选区时只有长按钉住的那次才弹（行气泡）。
     * 气泡是**同窗**绘制的最上层子视图：不另开窗、不抢焦点、拖窗时跟着走，代价是它出不了卡片边界——
     * 所以上边放不下时翻到选区下方，而正文滚动也不重算位置（人裁：跟了反而可能滚出窗口）。
     */
    private void updateBubbleForSelection() {
        int start = body.getSelectionStart();
        int end = body.getSelectionEnd();
        android.text.Layout layout = body.getLayout();
        boolean hasSelection = end > start;
        if (hasSelection) {
            lineBubblePinned = false;
        } else if (!lineBubblePinned) {
            bubble.setVisibility(GONE);
            return;
        }
        if (layout == null || getWidth() == 0 || drawerOpen()) {
            bubble.setVisibility(GONE);
            return;
        }
        setBubbleItems(!hasSelection);
        bubbleSpeak.setText(listener.isSpeaking() ? R.string.action_cancel : R.string.action_speak);

        int anchor = hasSelection ? start
                : Math.max(0, Math.min(start, body.getText().length()));
        int firstLine = layout.getLineForOffset(anchor);
        int lastLine = layout.getLineForOffset(Math.max(anchor, end - 1));
        // 锚在选区**首行**那一段：跨行选区若取首末行的横向极值，气泡会被算到莫名其妙的地方
        int anchorEnd = firstLine == lastLine ? end : layout.getLineEnd(firstLine);
        float hx0 = layout.getPrimaryHorizontal(anchor);
        float hx1 = layout.getPrimaryHorizontal(anchorEnd);
        Rect sel = new Rect(Math.round(Math.min(hx0, hx1)), layout.getLineTop(firstLine),
                Math.round(Math.max(hx0, hx1)), layout.getLineBottom(firstLine));
        offsetDescendantRectToMyCoords(body, sel);
        // ScrollView 的滚动是画布平移，不体现在祖先的 left/top 里，得自己补回来
        sel.offset(0, -bodyScroll.getScrollY());
        placeBubble(sel);
        bubble.setVisibility(VISIBLE);
    }

    /** 量气泡自身尺寸，把中心对到锚段中心，再按卡片边界左右各收 4dp；顶边不够就翻到下方。 */
    private void placeBubble(Rect sel) {
        int margin = dp(4);
        bubble.measure(MeasureSpec.makeMeasureSpec(getWidth(), MeasureSpec.AT_MOST),
                MeasureSpec.UNSPECIFIED);
        int bw = bubble.getMeasuredWidth();
        int bh = bubble.getMeasuredHeight();
        int left = Math.max(margin, Math.min((sel.left + sel.right) / 2 - bw / 2, getWidth() - bw - margin));
        int top = sel.top - bh - margin;
        if (top < margin) {
            top = Math.min(sel.bottom + margin, getHeight() - bh - margin);
        }
        LayoutParams lp = (LayoutParams) bubble.getLayoutParams();
        lp.leftMargin = left;
        lp.topMargin = top;
        bubble.setLayoutParams(lp);
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

    /** 展开抽屉位并装入这份内容；已展开时＝原地换内容（N 种内容共用一格，人裁 2026-10-22）。 */
    private void openDrawer(DrawerContent content) {
        bubble.setVisibility(GONE); // 抽屉与气泡抢的是同一块屏，展开这一格就先收气泡
        lineBubblePinned = false;   // 收掉就不该被下一次选区变化莫名顶回来，要唤再长按
        bindDrawer(content);
        scrim.setVisibility(VISIBLE);
        drawer.setVisibility(VISIBLE);
    }

    private void bindDrawer(DrawerContent content) {
        drawerTitle.setText(content.title);
        if (content.headerIcon == null) {
            drawerHeaderAction.setVisibility(GONE);
        } else {
            drawerHeaderAction.setVisibility(VISIBLE);
            drawerHeaderAction.setImageDrawable(content.headerIcon);
            drawerHeaderAction.setOnClickListener(v -> content.headerAction.run());
        }
        drawerSearch.setVisibility(content.searchable ? VISIBLE : GONE);
        drawerList.removeAllViews();
        for (DrawerContent.Item item : content.items) {
            drawerList.addView(drawerRow(item));
        }
    }

    private void closeDrawer() {
        scrim.setVisibility(GONE);
        drawer.setVisibility(GONE);
    }

    /** 条目＝整行宽的扁平菜单项。**不吃焦点**，否则点一次就把正文光标与键盘让出去（收起后没法连打）。 */
    private View drawerRow(DrawerContent.Item item) {
        Button b = new Button(getContext());
        b.setText(item.label);
        b.setAllCaps(false);
        b.setFocusable(false);
        b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        b.setTextColor(M3.color(getContext(), M3.ON_SURFACE));
        b.setBackgroundColor(0);
        b.setStateListAnimator(null);
        int rowHeight = dp(30);
        b.setMinHeight(rowHeight);
        b.setMinimumHeight(rowHeight);
        b.setPadding(dp(6), 0, dp(6), 0);
        if (item.action == null) {
            b.setEnabled(false);
            b.setAlpha(0.38f);
        } else {
            b.setOnClickListener(v -> item.action.run());
            if (item.longAction != null) {
                b.setOnLongClickListener(v -> {
                    item.longAction.run();
                    return true; // 吃掉这次长按，否则紧接着的抬起会被当成一次普通点击
                });
            }
        }
        return b;
    }

    /** ≡＝文档列表（E5）：栏头右上＝新建，搜索框＝默认正则，条目＝一篇文档，**长按＝置顶/取消置顶**。 */
    private DrawerContent docListContent() {
        Context context = getContext();
        return new DrawerContent(context.getString(R.string.editor_drawer_title),
                SketchIcons.of(SketchIcons.SQUARE, M3.color(context, M3.ON_SURFACE),
                        context.getResources().getDisplayMetrics().density),
                this::newDocument,
                true,
                Collections.emptyList());
    }

    private void openDocDrawer() {
        docListShowing = false;    // 先关监听，免得清词这一下在背后发一次查询
        drawerSearch.setText("");
        openDrawer(docListContent());
        docListShowing = true;
        refreshDocList("");
    }

    /**
     * 列表与搜索结果都走 {@code notes-io} 线程读盘，回主线程换条目。
     * 代次 {@code listSeq} 是唯一的时间判据：抽屉里连打几个字会发出好几个查询，只有最后一个的结果能落屏
     * ——否则旧查询的条目会把新查询的盖回去。
     */
    private void refreshDocList(String query) {
        final int seq = ++listSeq;
        java.util.function.Consumer<List<NotesCodec.Record>> onMain = records -> {
            if (seq != listSeq || !docListShowing || !drawerOpen()) {
                return;
            }
            bindDocRows(records == null ? Collections.<NotesCodec.Record>emptyList() : records);
        };
        if (query == null || query.isEmpty()) {
            notes.load(onMain);
        } else {
            notes.search(query, onMain);
        }
    }

    /** 搜索框停手到点：拿当下这行字去查（默认正则，非法正则由存储层退回字面包含）。 */
    private void runDocSearch() {
        refreshDocList(drawerSearch.getText().toString());
    }

    /** 置顶在前（{@link NotesCodec#sorted} 的口径），当下这篇行尾点个 {@code ▸}——列表窄，得知道自己在哪。 */
    private void bindDocRows(List<NotesCodec.Record> records) {
        drawerList.removeAllViews();
        if (records.isEmpty()) {
            drawerList.addView(drawerRow(new DrawerContent.Item(
                    getContext().getString(R.string.editor_notes_empty), null, null)));
            return;
        }
        for (NotesCodec.Record record : records) {
            String label = (record.pinned ? "★ " : "") + titleOf(record.title)
                    + (record.id.equals(docId) ? " ▸" : "");
            String id = record.id;
            boolean pinned = record.pinned;
            drawerList.addView(drawerRow(new DrawerContent.Item(label,
                    () -> openNote(id), () -> togglePin(id, !pinned))));
        }
    }

    /** 换文档前先落库当下这篇（人裁 §4c：宁多存不误丢）；两次写都排在同一条 {@code notes-io} 上，先后有保证。 */
    private void openNote(String id) {
        persistCurrent();
        notes.body(id, text -> {
            if (text == null) {
                return; // 读挂了就保持现状，不要把正文换成空、也不收起抽屉——让人的编辑现场原样留着
            }
            installDoc(id, text, false);
            closeDrawer();
        });
    }

    private void newDocument() {
        persistCurrent();
        installDoc(null, "", true);
        closeDrawer();
    }

    private void togglePin(String id, boolean pinned) {
        notes.setPinned(id, pinned, ignored -> {
            if (docListShowing && drawerOpen()) {
                refreshDocList(drawerSearch.getText().toString());
            }
        });
    }

    /** 把正文换成另一篇：清撤销栈、认作已落库（载入不是用户的编辑，不该进撤销、也不该被自动落库写回）。 */
    private void installDoc(String id, String text, boolean caretToEnd) {
        docId = id;
        savedText = text;
        initialLoadPending = false;
        undoStack.clear();
        redoStack.clear();
        pendingUndoBase = null;
        mainHandler.removeCallbacks(undoFlush);
        mainHandler.removeCallbacks(autosaveTick);
        applyingUndo = true;
        body.setText(text);
        applyingUndo = false;
        body.setSelection(caretToEnd ? text.length() : 0);
        updateDocName();
        syncEditState();
    }

    /** 打开编辑器＝接着上次那篇读（取更新时间最新的一篇——置顶是给列表排序用的，不代表"这篇在写"）。 */
    private void loadLastDoc() {
        notes.load(records -> {
            if (!initialLoadPending || records == null || records.isEmpty()) {
                return;
            }
            NotesCodec.Record latest = records.get(0);
            for (NotesCodec.Record r : records) {
                if (r.updatedMs > latest.updatedMs) {
                    latest = r;
                }
            }
            final String id = latest.id;
            notes.body(id, text -> {
                if (text == null || !initialLoadPending) {
                    return; // 等结果的这段时间里人已经打了字，就别把输入盖掉
                }
                installDoc(id, text, true);
            });
        });
    }

    /**
     * 落库当下这篇（💾 与自动落库与关面板同一条通路）。三种情况不写盘：在途一次没回、正文与上次落库相同、
     * 空正文的新文档（那还不是一篇"文档"，生成一条空记录只是脏列表）。
     */
    private void persistCurrent() {
        mainHandler.removeCallbacks(autosaveTick);
        String text = body.getText().toString();
        if (saveInFlight || text.equals(savedText) || (text.isEmpty() && docId == null)) {
            return;
        }
        saveInFlight = true;
        final String pendingId = docId;
        notes.save(pendingId, text, savedId -> {
            saveInFlight = false;
            if (savedId == null) {
                return; // 写挂了：正文一个字都不动，停手后下一次自动落库自会再试
            }
            if (docId == null) {
                docId = savedId;
            }
            savedText = text;
            updateDocName(); // 信息带从「未命名」换成标题＝落库成功的人眼凭据
            if (!body.getText().toString().equals(savedText)) {
                mainHandler.postDelayed(autosaveTick, AUTOSAVE_MERGE_MS); // 期间又打了字，补一次
            }
            if (docListShowing && drawerOpen()) {
                refreshDocList(drawerSearch.getText().toString());
            }
        });
    }

    /** 标题＝正文首行（存储层同一口径），空白篇显示「未命名」。 */
    private void updateDocName() {
        docName.setText(titleOf(NotesCodec.deriveTitle(body.getText().toString())));
    }

    private String titleOf(String title) {
        return title == null || title.isEmpty()
                ? getContext().getString(R.string.editor_untitled) : title;
    }

    /**
     * /＝编辑工具，**只管行级与全文级**（人裁 2026-10-22：选区级的复制/剪切/粘贴/翻译/TTS 归**选择气泡**，
     * 不进这一格）。当前实装「xx行」六项（作用于**光标所在行**，显示沿用 MT 短名），
     * 其余七项按人给的原始次序传 null 置灰占位——整眼菜单先看全，缺的档期一眼可见。
     */
    private DrawerContent editToolsContent(Context context) {
        List<DrawerContent.Item> items = new ArrayList<>(13);
        items.add(toolItem(R.string.editor_line_copy, this::copyLine));
        items.add(toolItem(R.string.editor_line_cut, this::cutLine));
        items.add(toolItem(R.string.editor_line_delete, this::deleteLine));
        items.add(toolItem(R.string.editor_line_clear, this::clearLine));
        items.add(toolItem(R.string.editor_line_replace, this::replaceLineWithClipboard));
        items.add(toolItem(R.string.editor_line_duplicate, this::duplicateLine));
        items.add(toolItem(R.string.editor_case_upper, null));
        items.add(toolItem(R.string.editor_case_lower, null));
        items.add(toolItem(R.string.editor_indent_add, null));
        items.add(toolItem(R.string.editor_indent_remove, null));
        items.add(toolItem(R.string.editor_format_code, null));
        items.add(toolItem(R.string.editor_comment_toggle, null));
        items.add(toolItem(R.string.editor_compress_code, null));
        return new DrawerContent(context.getString(R.string.editor_cd_tools),
                null, null, false, items);
    }

    private DrawerContent.Item toolItem(int labelRes, Runnable action) {
        return new DrawerContent.Item(getContext().getString(labelRes), action);
    }

    // ---------- 编辑内核：E1 交付物 + E1b 补重做；全选/复制/粘贴留作 E8「/」菜单的接线件 ----------

    void selectAllText() {
        body.requestFocus();
        body.selectAll();
    }

    /** 复制选区。这一套项只在有选区时挂得上，无选区的分支够不着，不留兜底。 */
    void copySelection() {
        int start = body.getSelectionStart();
        int end = body.getSelectionEnd();
        if (end > start) {
            writeClipboard(body.getText().subSequence(start, end).toString());
        }
    }

    /** 剪切选区，同上：无选区不做事，行级的活归「剪切行」。 */
    void cutSelection() {
        int start = body.getSelectionStart();
        int end = body.getSelectionEnd();
        if (end <= start) {
            return;
        }
        writeClipboard(body.getText().subSequence(start, end).toString());
        Editable editable = body.getText();
        editable.replace(start, end, "");
        body.setSelection(Math.min(start, editable.length()));
    }

    private void writeClipboard(String payload) {
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

    // ---------- 「xx行」一族：一律作用于光标所在行（人裁 2026-10-22），显示沿用 MT 的短名 ----------

    private static final class Line {
        final String text;
        final int start;
        final int end;

        Line(String text, int start, int end) {
            this.text = text;
            this.start = start;
            this.end = end;
        }
    }

    /** 光标所在行（＝选区起点所在行）。 */
    private Line cursorLine() {
        return lineAt(body.getSelectionStart());
    }

    /** 某偏移所在行，行首/行尾偏移不含行尾换行符。 */
    private Line lineAt(int offset) {
        String text = body.getText().toString();
        int caret = Math.max(0, Math.min(offset, text.length()));
        int lineStart = text.lastIndexOf('\n', caret - 1) + 1;
        int nextBreak = text.indexOf('\n', lineStart);
        int lineEnd = nextBreak < 0 ? text.length() : nextBreak;
        return new Line(text.substring(lineStart, lineEnd), lineStart, lineEnd);
    }

    private void copyLine() {
        writeClipboard(cursorLine().text);
    }

    private void cutLine() {
        copyLine();
        deleteLine();
    }

    /** 删整行连同其换行；删的若是没有行尾换行的末行，改吃**前一个**换行，免得留下一条空行。 */
    private void deleteLine() {
        Editable text = body.getText();
        Line line = cursorLine();
        if (line.end < text.length()) {
            text.replace(line.start, line.end + 1, "");
            body.setSelection(Math.min(line.start, text.length()));
        } else if (line.start > 0) {
            text.replace(line.start - 1, line.end, "");
            body.setSelection(Math.min(line.start - 1, text.length()));
        } else {
            text.replace(0, line.end, "");
            body.setSelection(0);
        }
    }

    /** 清空只去掉行内容，换行留着——行还在，只是变成空行（与删除行的区别就在这）。 */
    private void clearLine() {
        Editable text = body.getText();
        Line line = cursorLine();
        text.replace(line.start, line.end, "");
        body.setSelection(Math.min(line.start, text.length()));
    }

    /** 替换行＝用剪贴板**首行**整体换掉光标所在行（人裁 2026-10-22 的读法）。 */
    private void replaceLineWithClipboard() {
        ClipboardManager cm = (ClipboardManager)
                getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip().getItemCount() == 0) {
            return;
        }
        CharSequence clip = cm.getPrimaryClip().getItemAt(0).getText();
        if (clip == null) {
            return;
        }
        String first = clip.toString();
        int breakAt = first.indexOf('\n');
        if (breakAt >= 0) {
            first = first.substring(0, breakAt);
        }
        Editable text = body.getText();
        Line line = cursorLine();
        text.replace(line.start, line.end, first);
        body.setSelection(Math.min(line.start + first.length(), text.length()));
    }

    /** 重复行：在本行正下方插一条同样的，光标落在新行的同一列。 */
    private void duplicateLine() {
        Editable text = body.getText();
        Line line = cursorLine();
        int caret = Math.max(0, Math.min(body.getSelectionStart(), text.length()));
        int column = Math.max(0, Math.min(caret - line.start, line.text.length()));
        text.insert(line.end, "\n" + line.text);
        body.setSelection(Math.min(line.end + 1 + column, text.length()));
    }

    // ---------- 输出出口（E3）：翻译与朗读同一条口径——有选区取选区，无选区取光标所在行 ----------

    private void translateOutlet() {
        String source = outletSource();
        if (!source.isEmpty()) {
            listener.onTranslate(source, this::insertTranslationBelowCaret);
        }
    }

    private void speakOutlet() {
        String source = outletSource();
        if (source.isEmpty()) {
            return;
        }
        listener.onSpeak(source);
        // 点钮不产生选区变化，标签不自己翻，就当场按引擎状态翻一次
        bubbleSpeak.setText(listener.isSpeaking()
                ? R.string.action_cancel : R.string.action_speak);
    }

    /**
     * 朗读状态回推（人裁 2026-10-22）：合成什么时候自己结束，视图侧看不出来，由服务层的
     * {@code onSpeakingChanged} 推过来，标签才不会停在「取消」。
     */
    public void setSpeaking(boolean speaking) {
        bubbleSpeak.setText(speaking ? R.string.action_cancel : R.string.action_speak);
    }

    private String outletSource() {
        String text = body.getText().toString();
        int start = Math.max(0, Math.min(body.getSelectionStart(), text.length()));
        int end = Math.max(start, Math.min(body.getSelectionEnd(), text.length()));
        return end > start ? text.substring(start, end) : lineAt(start).text;
    }

    /**
     * 模型要跑几秒才回来，所以插入锚点**到了再算**：这期间人挪了光标、又打了字，译文就落在当下那一行的
     * 正下方，而不是插进一个早就漂走的旧偏移里。译文自带的换行原样保留。
     */
    private void insertTranslationBelowCaret(String translated) {
        if (translated == null || translated.isEmpty()) {
            return;
        }
        Editable text = body.getText();
        Line anchor = lineAt(Math.max(body.getSelectionStart(), body.getSelectionEnd()));
        text.insert(anchor.end, "\n" + translated);
        body.setSelection(Math.min(anchor.end + 1 + translated.length(), text.length()));
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

    /**
     * 视图从窗上摘下来＝最后一次落库机会。关面板的通路不止一条（返回键、拖曳条、换别的面板、服务销毁），
     * 全都要经过这一格，所以挂在这里而不是逐条去补调用方。写是异步的：视图已摘不影响
     * {@code notes-io} 把那一次写完。
     */
    @Override
    protected void onDetachedFromWindow() {
        mainHandler.removeCallbacks(autosaveTick);
        mainHandler.removeCallbacks(docSearchTick);
        persistCurrent();
        super.onDetachedFromWindow();
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }

    /**
     * 正文输入框：存在的唯一理由是 {@code onSelectionChanged} 这个 protected 回调——
     * 「选区一形成就弹」没有别的通路（焦点监听看不见选区，文本监听看不见光标折叠）。
     * 回调字段在视图建好后才挂：构造期 {@code EditText} 自己会调一次，那时气泡还不存在。
     */
    static final class BodyEditText extends EditText {
        Runnable onSelectionChanged;
        Runnable onLongPressForLine;
        /** 按下落点：带坐标的长按自带，无参那次（无障碍）用它兜底，行气泡按它算光标该落哪。 */
        float lastDownX;
        float lastDownY;
        /** 长按已到点：此后手指再动就改走「拖曳扩选」，不再当挪光标（人裁 2026-10-22，见下）。 */
        private boolean longPressFired;
        /** 拖曳扩选的固定端；-1＝没在拖。 */
        private int dragAnchor = -1;
        private final int touchSlop;
        private final float density;
        /** 正在拖哪一颗手柄球：-1＝没拖，0＝首端，1＝尾端；固定端记在 dragAnchor 里。 */
        private int handleDrag = -1;
        private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        /** 手柄几何临时槽：杆 x、杆顶、杆底、球心 x、球心 y、球半径。 */
        private final float[] geom = new float[6];

        BodyEditText(Context context) {
            super(context);
            touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
            density = context.getResources().getDisplayMetrics().density;
            handlePaint.setColor(M3.color(context, M3.PRIMARY));
        }

        private float dpf(float value) {
            return value * density;
        }

        /**
         * 「按住不动＝行气泡，按住再拖＝扩选」这条分叉只能自己接（人测卡⑤-d 前提纠正
         * 「选区手柄不存在」）：这块面板里系统根本不画选择手柄，所以拖曳扩选唯一的入口本就是
         * 长按之后的那串 MOVE，而长按已经被我们接管——交回系统也换不回手柄，只会丢掉头一次弹的气泡。
         */
        @Override
        public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (handleDrag >= 0) {
                // 抓住球＝调那一端。整串事件都不交给 Editor，否则它把按下当成挪光标、选区当场塌掉。
                if (action == MotionEvent.ACTION_MOVE) {
                    moveHandleTo(event.getX(), event.getY());
                } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    handleDrag = -1;
                    dragAnchor = -1;
                    longPressFired = false;
                    disallowAncestorIntercept(false);
                }
                return true;
            }
            if (action == MotionEvent.ACTION_DOWN) {
                lastDownX = event.getX();
                lastDownY = event.getY();
                longPressFired = false;
                dragAnchor = -1;
                int which = handleAt(event.getX(), event.getY());
                if (which >= 0) {
                    handleDrag = which;
                    dragAnchor = which == 0 ? getSelectionEnd() : getSelectionStart();
                    disallowAncestorIntercept(true);
                    moveHandleTo(event.getX(), event.getY());
                    return true;
                }
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                if (dragAnchor >= 0) {
                    disallowAncestorIntercept(false);
                }
                dragAnchor = -1;
                longPressFired = false;
            } else if (longPressFired && action == MotionEvent.ACTION_MOVE) {
                if (dragAnchor < 0
                        && Math.hypot(event.getX() - lastDownX, event.getY() - lastDownY) > touchSlop) {
                    dragAnchor = getOffsetForPosition(lastDownX, lastDownY);
                    // 不夺回则外层 ScrollView 一有竖向位移就 intercept，拖选当场断掉
                    disallowAncestorIntercept(true);
                }
                if (dragAnchor >= 0) {
                    int focal = getOffsetForPosition(event.getX(), event.getY());
                    setSelection(Math.min(dragAnchor, focal), Math.max(dragAnchor, focal));
                    return true; // 这条 MOVE 不再交给 Editor 当「挪光标」
                }
            }
            return super.onTouchEvent(event);
        }

        /** 这道闸在 ViewGroup 手上：子视图要拖选，就得替父级把 intercept 关掉（拖完必须还原，否则整页滚不动）。 */
        private void disallowAncestorIntercept(boolean disallow) {
            android.view.ViewParent parent = getParent();
            if (parent != null) {
                parent.requestDisallowInterceptTouchEvent(disallow);
            }
        }

        /**
         * 「偏置水滴」手柄（人裁 2026-10-22，主流样式）：杆贯穿选区首/末行那一列，球垂在**行下方**，
         * 首端球偏左、尾端球偏右。球朝下正是为了把选区**上方**整块留给气泡——两者不抢同一格触摸。
         * 坐标换算照 {@code Editor.getTopOffset()} 的路子：padding + layout 行顶/行底。
         */
        private boolean handleGeom(int which) {
            android.text.Layout layout = getLayout();
            int start = getSelectionStart();
            int end = getSelectionEnd();
            if (layout == null || end <= start) {
                return false;
            }
            int line = layout.getLineForOffset(which == 0 ? start : Math.max(start, end - 1));
            int edge = which == 0 ? start : Math.min(end, layout.getLineEnd(line));
            float stem = layout.getPrimaryHorizontal(edge) + getTotalPaddingLeft();
            float top = getTotalPaddingTop() + layout.getLineTop(line);
            float bottom = getTotalPaddingTop() + layout.getLineBottom(line);
            float r = dpf(5.5f);
            geom[0] = stem;
            geom[1] = top;
            geom[2] = bottom;
            // 球夹在视图内：选区正好落在行首时，往左偏的那一颗会被左边缘切掉半颗，看着就像没画。
            geom[3] = Math.max(r, Math.min(getWidth() - r, stem + (which == 0 ? -r : r)));
            geom[4] = bottom + r;
            geom[5] = r;
            return true;
        }

        /** 命中球优先（到球心 14dp 内），其次杆那一列（±9dp）。没选区或没命中返回 -1。 */
        private int handleAt(float x, float y) {
            for (int which = 0; which < 2; which++) {
                if (!handleGeom(which)) {
                    return -1;
                }
                if (Math.hypot(x - geom[3], y - geom[4]) <= dpf(14)) {
                    return which;
                }
                if (Math.abs(x - geom[0]) <= dpf(9) && y >= geom[1] - dpf(6) && y <= geom[2] + dpf(6)) {
                    return which;
                }
            }
            return -1;
        }

        private void moveHandleTo(float x, float y) {
            int focal = getOffsetForPosition(x, y);
            setSelection(Math.min(dragAnchor, focal), Math.max(dragAnchor, focal));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            for (int which = 0; which < 2; which++) {
                if (!handleGeom(which)) {
                    continue;
                }
                handlePaint.setStyle(Paint.Style.STROKE);
                handlePaint.setStrokeWidth(dpf(2f));
                canvas.drawLine(geom[0], geom[1], geom[0], geom[4], handlePaint);
                handlePaint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(geom[3], geom[4], geom[5], handlePaint);
            }
        }

        /**
         * TextView 会在长按到点时把它交给自家的 TouchHelper 去选词，`OnLongClickListener` 因此
         * 形同虚设（人测卡⑤-b）。在这里接管＝既不唤系统那套选词与工具条，也不另建计时器抢时序。
         * 两个重载都要盖：手指长按走带坐标那版，无障碍路径走无参那版（后者退回 ACTION_DOWN 的落点）。
         */
        @Override
        public boolean performLongClick() {
            if (onLongPressForLine == null) {
                return super.performLongClick();
            }
            longPressFired = true;
            onLongPressForLine.run();
            return true;
        }

        @Override
        public boolean performLongClick(float x, float y) {
            if (onLongPressForLine == null) {
                return super.performLongClick(x, y);
            }
            lastDownX = x;
            lastDownY = y;
            longPressFired = true;
            onLongPressForLine.run();
            return true;
        }

        @Override
        protected void onSelectionChanged(int start, int end) {
            super.onSelectionChanged(start, end);
            invalidate(); // 手柄画在本视图内容空间里，选区一动就得重画（滚动自带跟随，无需额外刷）
            if (onSelectionChanged != null) {
                onSelectionChanged.run();
            }
        }
    }
}
