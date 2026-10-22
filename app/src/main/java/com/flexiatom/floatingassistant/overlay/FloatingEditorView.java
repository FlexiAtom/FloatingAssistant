package com.flexiatom.floatingassistant.overlay;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
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
import android.view.ViewGroup;
import android.view.ViewConfiguration;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
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
import com.flexiatom.floatingassistant.core.EditorPrefs;
import com.flexiatom.floatingassistant.core.NotesCodec;
import com.flexiatom.floatingassistant.core.NotesStore;

import java.nio.charset.Charset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

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
 * 💾 已随 E5 点亮（落库走 {@link NotesStore}）；⋮ 已随 E6 点亮（人 2026-10-22 定终版清单十二项，
 * E6a 这一档实装统计／自动换行／流畅模式三格，其余在同一格里置灰看全）。
 *
 * <p>图标：除人供 SVG 的两件几何件（软盘 {@code ic_floppy.xml}、新建纸笔 {@code ic_paper_pen.xml}）
 * 外全部 {@link SketchIcons} 运行时现绘。
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
    private final EditorPrefs prefs;
    private final TextView docName;
    /** 信息带右格＝当下这篇的「编码·换行」两档（E6b）：一格装两个标签，省一行。 */
    private final TextView encodingLabel;
    private final Runnable autosaveTick = this::persistCurrent;
    /** 写成方法引用而不是就地 lambda：字段初始化期 {@code drawerSearch} 还没赋值，javac 按 definite assignment 会拒。 */
    private final Runnable docSearchTick = this::runDocSearch;
    /** 当下这篇的身份；null＝新建、还没落过库。 */
    private String docId;
    /** 当下这两档＝E5 索引里那两列的本地影子；换档立刻走一次落库，不一致只在写的途中存在一瞬。 */
    private String docCharset = NotesCodec.DEFAULT_CHARSET;
    private String docNewline = NotesCodec.DEFAULT_NEWLINE;
    /** 盘上字节还是旧档（换档时正好有一次写在途）：脏判之外多一个"得写"的信号，别让标签成了谎话。 */
    private boolean settingStale;
    /** 换文档就自增：落库回调只对**当下这篇**有意义，旧一趟的结果一律丢掉。 */
    private int docSeq;
    /** 上一次落库的内容——与正文相等就不写盘。 */
    private String savedText = "";
    /** 一次写只在途一份：回调之后再做脏判补写，免得同一篇被并发写出两个身份。 */
    private boolean saveInFlight;
    /**
     * 缓冲里**解不出来的字**（{@code U+FFFD}）的个数——载入/重载时数一次，落库前再数一次，非 0 就挂进信息带。
     * 人裁 2026-10-22「要装闸」：带着这些字把缓冲写回盘，盘上那一版就救不回来了，所以自动通路一律停写。
     */
    private int lossyCount;
    /** 人已经在确认格里明确选过「仍要存」：这一片的闸门就此放开，之后自动落库照常（正文真含 {@code U+FFFD} 的人不被锁死）。 */
    private boolean lossyAcked;
    /** 构造时那次「取最近一篇」是否仍有效：人已经动过正文就作废，不把输入盖掉。 */
    private boolean initialLoadPending = true;
    /** 当前抽屉装的是否文档列表——搜索框只对它有反应，{@code /} 菜单那格没有搜索框。 */
    private boolean docListShowing;
    /** 列表刷新与搜索共用一个代次：只认最后一次请求的结果，早到的旧结果丢掉。 */
    private int listSeq;

    // ---------- 免截图通路（ui-blind-operation F1）：状态一变就留一行机器读得懂的戳 ----------

    /** 连珠动作（打字、挪光标）静默这么久才发一条——离散动作不等它，当场发。 */
    private static final long STAMP_QUIET_MS = 150L;
    /** 上一条发出去的戳：与新算的相同就不重复占日志。 */
    private String lastStamp = "";
    private final Runnable stampTick = this::emitStamp;

    public FloatingEditorView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        this.notes = NotesStore.of(context);
        this.prefs = new EditorPrefs(context);

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
                R.string.editor_cd_save, v -> saveNow()));
        toolbar.addView(toolButton(context, SketchIcons.of(SketchIcons.SLASH, onSurface, density),
                R.string.editor_cd_tools, v -> openDrawer(editToolsContent(context))));
        // ⋮ 随 E6 点亮（人 2026-10-22 定终版清单）：E6a 这一档实装三格，其余在同一格里置灰看全。
        toolbar.addView(toolButton(context, SketchIcons.of(SketchIcons.DOTS, onSurface, density),
                R.string.editor_cd_more, v -> openDrawer(overflowContent(context))));
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
        encodingLabel = new TextView(context);
        encodingLabel.setTextColor(onVariant);
        encodingLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        updateEncodingLabel();
        strip.addView(encodingLabel);
        editor.addView(strip, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        editor.addView(divider(context), dividerLp());

        body = new BodyEditText(context);
        body.setTextColor(onSurface);
        body.setHintTextColor(onVariant);
        body.setContentDescription(context.getString(R.string.editor_cd_body));
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        body.setBackground(null);
        body.setGravity(Gravity.TOP | Gravity.START);
        body.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        body.inputGate = prefs::smooth; // 流畅模式的闸门不碰显示标志，只挡输入法读文本（见 SmoothInputConnection）
        applyWrapFlag();   // 自动换行（E6a）按持久档现读，开局就得刷一遍
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
        scrim.setFocusable(false); // 遮罩只用于收起，不该在 Tab 链上占一格（可点击的裸 View 默认会被算成可聚焦）
        scrim.setContentDescription(context.getString(R.string.editor_cd_scrim));
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
                icon(context, R.drawable.ic_paper_pen, onSurface), R.string.editor_cd_new, null);
        drawerHeader.addView(drawerHeaderAction);
        drawer.addView(drawerHeader, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, dp(40)));
        drawer.addView(divider(context), dividerLp());

        drawerSearch = new EditText(context);
        drawerSearch.setHint(R.string.editor_search_hint);
        drawerSearch.setContentDescription(context.getString(R.string.editor_cd_search));
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
        // 键盘可达、但**触摸态不吃焦点**（F2）：焦点仍归正文，点一次不再把光标与键盘让出去。
        b.setFocusable(true);
        b.setFocusableInTouchMode(false);
        applyFocusRing(b);
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
        requestStamp(); // 光标一动就是一格状态（气泡的藏与露也算），合并到停手后发一条
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
        applyFocusRing(b);
        return b;
    }

    /**
     * 键盘导航的**可见焦点环**（ui-blind-operation F2）：只在 focused 态露一圈描边。
     * 用 {@code foreground} 而不是换 {@code background}，因为钮的底子各有安排（图标钮透明、
     * 抽屉行扁平），换底会把 M3 取色与置灰一并牵动。
     * 强度（粗细／取色）是待人裁那一格，先按"看得见"这一档做。
     */
    private void applyFocusRing(View v) {
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.RECTANGLE);
        ring.setColor(0);
        ring.setCornerRadius(dp(6));
        ring.setStroke(dp(2), M3.color(getContext(), M3.PRIMARY));
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, ring);
        states.addState(new int[]{}, new android.graphics.drawable.ColorDrawable(0));
        v.setForeground(states);
        v.setForegroundGravity(Gravity.FILL);
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
        emitStamp();
    }

    private void bindDrawer(DrawerContent content) {
        // 换内容＝换身份：文档列表那套态（docListShowing）不归零，/ 就还顶着「列表正在显示」的招牌——
        // 于是开过 ≡ 之后任何一次落库/置顶回调（它们只认 docListShowing + drawerOpen）都会把文档条目
        // 直接盖进 / 的抽屉里。人测 2026-10-22：「斜线有概率显示为三横线的内容」，条件＝这面板里开过 ≡。
        docListShowing = false;
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
        requestStamp(); // 就地换内容（≡↔/ 之间、平滑开关后重装）也要留痕；用合并那条，避免读到还没亮起来的抽屉
    }

    private void closeDrawer() {
        scrim.setVisibility(GONE);
        drawer.setVisibility(GONE);
        emitStamp();
    }

    /** 条目＝整行宽的扁平菜单项。**触摸态不吃焦点**（点了不交正文光标与键盘），键盘态可走到（F2）。 */
    private View drawerRow(DrawerContent.Item item) {
        Button b = new Button(getContext());
        b.setText(item.label);
        b.setAllCaps(false);
        b.setFocusable(true);
        b.setFocusableInTouchMode(false);
        applyFocusRing(b);
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

    /** ≡＝文档列表（E5）：栏头右上＝新建（人供纸笔几何件），搜索框＝默认正则，条目＝一篇文档，**长按＝置顶/取消置顶**。 */
    private DrawerContent docListContent() {
        Context context = getContext();
        return new DrawerContent(context.getString(R.string.editor_drawer_title),
                icon(context, R.drawable.ic_paper_pen, M3.color(context, M3.ON_SURFACE)),
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
        Runnable go = () -> notes.open(id, doc -> {
            if (doc == null) {
                return; // 读挂了就保持现状，不要把正文换成空、也不收起抽屉——让人的编辑现场原样留着
            }
            installDoc(doc.record.id, doc.text, doc.record.charset, doc.record.newline, false);
            settleAfterReload();
        });
        if (blockedByLossy()) {
            confirmSwitchAway(go); // 闸门拦住了那次落库，切过去就是白丢改动 → 问
            return;
        }
        persistCurrent();
        go.run();
    }

    private void newDocument() {
        Runnable go = () -> {
            installDoc(null, "", NotesCodec.DEFAULT_CHARSET, NotesCodec.DEFAULT_NEWLINE, true);
            closeDrawer();
        };
        if (blockedByLossy()) {
            confirmSwitchAway(go);
            return;
        }
        persistCurrent();
        go.run();
    }

    private void togglePin(String id, boolean pinned) {
        notes.setPinned(id, pinned, ignored -> {
            if (docListShowing && drawerOpen()) {
                refreshDocList(drawerSearch.getText().toString());
            }
        });
    }

    /** 把正文换成另一篇：清撤销栈、认作已落库（载入不是用户的编辑，不该进撤销、也不该被自动落库写回）。 */
    private void installDoc(String id, String text, String charset, String newline, boolean caretToEnd) {
        docId = id;
        docCharset = charset;
        docNewline = newline;
        settingStale = false;
        lossyAcked = false; // 换了一篇＝重新问起，上一篇的「仍要存」不延续
        lossyCount = NotesCodec.countLossy(text);
        docSeq++; // 在途的那一趟落库从此不属于当下这篇，回调只许改它的簿记
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
        updateEncodingLabel();
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
            notes.open(id, doc -> {
                if (doc == null || !initialLoadPending) {
                    return; // 等结果的这段时间里人已经打了字，就别把输入盖掉
                }
                installDoc(id, doc.text, doc.record.charset, doc.record.newline, true);
            });
        });
    }

    /**
     * 落库当下这篇（💾 与自动落库与关面板与换档同一条通路）。四种情况不写盘：在途一次没回、
     * 正文与上次落库相同**且档位已与盘上一致**、空正文的新文档（那还不是一篇"文档"，生成一条空记录只是脏列表）、
     * **闸机关着**（缓冲里有解不出来的字而人还没说要存，见 {@link #blockedByLossy}）。
     */
    private void persistCurrent() {
        mainHandler.removeCallbacks(autosaveTick);
        String text = body.getText().toString();
        if (!lossyAcked && lossyIn(text) > 0) {
            return; // 装闸：自动通路不打断人，只是**不写盘**——要存这一版得人自己按软盘（见 saveNow）
        }
        if (saveInFlight || (text.equals(savedText) && !settingStale)
                || (text.isEmpty() && docId == null)) {
            return;
        }
        saveInFlight = true;
        final int seq = docSeq;
        final String pendingId = docId;
        final String charset = docCharset;
        final String newline = docNewline;
        notes.save(pendingId, text, charset, newline, savedId -> {
            saveInFlight = false;
            if (savedId == null || seq != docSeq) {
                return; // 写挂了：正文一个字都不动，停手后下一次自动落库自会再试；换了篇：这一趟的簿记不再归当下这篇
            }
            if (docId == null) {
                docId = savedId;
            }
            savedText = text;
            if (charset.equals(docCharset) && newline.equals(docNewline)) {
                settingStale = false; // 这一趟的字节真按当下这两档出去了
            }
            updateDocName(); // 信息带从「未命名」换成标题＝落库成功的人眼凭据
            if (!body.getText().toString().equals(savedText) || settingStale) {
                mainHandler.postDelayed(autosaveTick, AUTOSAVE_MERGE_MS); // 期间又打了字或又换了档，补一次
            }
            if (docListShowing && drawerOpen()) {
                refreshDocList(drawerSearch.getText().toString());
            }
        });
    }

    /** 数一次坏字，顺手刷新信息带上的 ⚠N——闸机的状态和那格数字都从这一条来。 */
    private int lossyIn(String text) {
        int count = NotesCodec.countLossy(text);
        if (count != lossyCount) {
            lossyCount = count; // 没变就不刷标签：标签只由这两档与这个数决定，而刷新会带动状态戳
            updateEncodingLabel();
        }
        return count;
    }

    /** 闸机关着＝缓冲里有解不出来的字，而这个人还没明确说过「就存这一版」。 */
    private boolean blockedByLossy() {
        return !lossyAcked && lossyIn(body.getText().toString()) > 0;
    }

    /** 💾＝人主动要存，所以带坏字时**问一次**；自动落库那条通路不问人，直接停写（见 {@link #persistCurrent}）。 */
    private void saveNow() {
        if (!lossyAcked && lossyIn(body.getText().toString()) > 0) {
            Context context = getContext();
            List<DrawerContent.Item> items = new ArrayList<>(3);
            items.add(greyRow(context.getString(R.string.editor_lossy_force_hint, lossyCount, docCharset)));
            items.add(new DrawerContent.Item(context.getString(R.string.editor_lossy_force_go), () -> {
                lossyAcked = true; // 这一篇此后照常打字照常存，不再每 1.2 秒问一遍
                persistCurrent();
                closeDrawer();
            }));
            items.add(new DrawerContent.Item(context.getString(R.string.action_cancel), this::closeDrawer));
            openDrawer(new DrawerContent(context.getString(R.string.editor_lossy_force_title),
                    null, null, false, items));
            return;
        }
        persistCurrent();
    }

    /**
     * 换篇/新建那一下也要落库，闸门拦住了就得问：三选一（存了再走／不存就走／留下不动）。
     * 不替人决定，因为「丢掉这些改动」和「把乱码存进去」两种损失，谁轻谁重只有人自己知道。
     */
    private void confirmSwitchAway(Runnable switchAway) {
        Context context = getContext();
        List<DrawerContent.Item> items = new ArrayList<>(4);
        items.add(greyRow(context.getString(R.string.editor_lossy_switch_hint, lossyCount)));
        items.add(new DrawerContent.Item(context.getString(R.string.editor_lossy_switch_save), () -> {
            lossyAcked = true;
            persistCurrent();
            switchAway.run();
        }));
        items.add(new DrawerContent.Item(context.getString(R.string.editor_lossy_switch_drop), switchAway));
        items.add(new DrawerContent.Item(context.getString(R.string.action_cancel), this::closeDrawer));
        openDrawer(new DrawerContent(context.getString(R.string.editor_lossy_title), null, null, false, items));
    }

    /** 解出坏字当场说清三件事：几个字、盘上那版没动、自动保存已停；给一条「换一档重解」回去。 */
    private void warnLossy() {
        Context context = getContext();
        List<DrawerContent.Item> items = new ArrayList<>(3);
        items.add(greyRow(context.getString(R.string.editor_lossy_hint, lossyCount)));
        items.add(new DrawerContent.Item(context.getString(R.string.editor_lossy_repick), this::reloadWithCharset));
        items.add(new DrawerContent.Item(context.getString(R.string.editor_lossy_ok), this::closeDrawer));
        openDrawer(new DrawerContent(context.getString(R.string.editor_lossy_title), null, null, false, items));
    }

    /** 重载完的收尾：有坏字就把话说在眼前，没坏字就收起抽屉。 */
    private void settleAfterReload() {
        if (lossyCount > 0) {
            warnLossy();
        } else {
            closeDrawer();
        }
    }

    /** 标题＝正文首行（存储层同一口径），空白篇显示「未命名」。 */
    private void updateDocName() {
        docName.setText(titleOf(NotesCodec.deriveTitle(body.getText().toString())));
        requestStamp();
    }

    private String titleOf(String title) {
        return title == null || title.isEmpty()
                ? getContext().getString(R.string.editor_untitled) : title;
    }

    // ---------- E6b 两档（保存编码／换行符）：都**按篇**存，落 E5 索引那两列，不是全局档 ----------

    /**
     * 常用六档（人裁：先给常用的，其余走「其他…」那张全量表）。{@code UTF-8 BOM} 不是 JDK 的
     * charset 名而是本 app 的档（见 {@link NotesCodec#BOM_LABEL}），列在这儿因为它是 Windows 记事本那一格。
     */
    private static final String[] COMMON_CHARSETS = {
            NotesCodec.DEFAULT_CHARSET, NotesCodec.BOM_LABEL, "GBK", "GB18030", "Big5", "ISO-8859-1"};

    private void updateEncodingLabel() {
        String base = docCharset + "·" + docNewline;
        encodingLabel.setText(lossyCount > 0
                ? getContext().getString(R.string.editor_info_lossy, base, lossyCount) : base);
        requestStamp();
    }

    /** 缓冲与盘上不一致＝有未保存的改动（重载那两格先拿它问一次人，人裁 2026-10-22）。换档也算：档还没落到盘上。 */
    private boolean dirty() {
        return settingStale || !body.getText().toString().equals(savedText);
    }

    /** 重新加载文件＝丢掉缓冲里未落库的字，按当下两档从盘上重读。新文档还没文件可重读，菜单那格对它置灰。 */
    private void reloadFromDisk() {
        final String id = docId;
        if (id == null) {
            return;
        }
        Runnable go = () -> {
            mainHandler.removeCallbacks(autosaveTick); // 决定放弃了，就先把排队的自动落库收回来
            notes.open(id, doc -> {
                if (doc == null || !id.equals(docId)) {
                    return; // 读挂了保持现状；等结果的这段时间人已经换了篇，别把另一篇盖进来
                }
                installDoc(id, doc.text, doc.record.charset, doc.record.newline, false);
                settleAfterReload();
            });
        };
        if (dirty()) {
            confirmDiscard(R.string.editor_more_reload, go);
        } else {
            go.run();
        }
    }

    /** 以指定编码重新加载＝挑一档，把这一档**写进索引**再按它重读盘上字节（这篇的编码从此就是它）。 */
    private void reloadWithCharset() {
        openCharsetPicker(R.string.editor_more_reload_charset, charset -> {
            final String id = docId;
            if (id == null) {
                return;
            }
            Runnable go = () -> {
                mainHandler.removeCallbacks(autosaveTick);
                notes.reencode(id, charset, doc -> {
                    if (doc == null || !id.equals(docId)) {
                        return;
                    }
                    installDoc(id, doc.text, doc.record.charset, doc.record.newline, false);
                    settleAfterReload();
                });
            };
            if (dirty()) {
                confirmDiscard(R.string.editor_more_reload_charset, go);
            } else {
                go.run();
            }
        });
    }

    /**
     * 换档统一走这一条：改影子、刷信息带、认作"盘上字节还是旧档"，再叫落库那一条通路补一次写——
     * **换档＝一次真写**，只改标签不重出字节，索引就在撒谎。
     */
    private void applyDocSettings() {
        updateEncodingLabel();
        settingStale = true;
        closeDrawer();
        persistCurrent();
    }

    /** 放弃未保存改动的确认。抽屉位＝本 app 唯一的对话框替身：悬浮窗架构里没有 AlertDialog 这回事。 */
    private void confirmDiscard(int titleRes, Runnable proceed) {
        Context context = getContext();
        List<DrawerContent.Item> items = new ArrayList<>(3);
        items.add(greyRow(context.getString(R.string.editor_discard_hint)));
        items.add(new DrawerContent.Item(context.getString(R.string.editor_discard_confirm), proceed));
        items.add(new DrawerContent.Item(context.getString(R.string.action_cancel),
                () -> bindDrawer(overflowContent(context)))); // 取消＝回到 ⋮ 那一眼，不让人重新找位置
        openDrawer(new DrawerContent(context.getString(titleRes), null, null, false, items));
    }

    private void openCharsetPicker(int titleRes, java.util.function.Consumer<String> onPick) {
        showCharsets(getContext().getString(titleRes), COMMON_CHARSETS, true, onPick);
    }

    private void openNewlinePicker() {
        Context context = getContext();
        List<DrawerContent.Item> items = new ArrayList<>(3);
        items.add(newlineRow(context, R.string.editor_newline_lf, "LF"));
        items.add(newlineRow(context, R.string.editor_newline_crlf, "CRLF"));
        items.add(newlineRow(context, R.string.editor_newline_cr, "CR"));
        openDrawer(new DrawerContent(context.getString(R.string.editor_more_newline),
                null, null, false, items));
    }

    /** 三档按人的叫法显示（Linux/Windows/MacOS），标签 LF/CRLF/CR 不再同排——一栏两个名字只会糊。 */
    private DrawerContent.Item newlineRow(Context context, int labelRes, String tag) {
        return checkRow(context.getString(labelRes), tag.equals(docNewline),
                () -> {
                    if (blockedByLossy()) {
                        warnLossy(); // 换行档也要靠一次真写才成立，写不出去就别让标签跑在字节前头
                        return;
                    }
                    docNewline = tag;
                    applyDocSettings();
                });
    }

    /**
     * 编码档位表：一行一档、当下这篇的档打勾；平台没有的档**显示但点不动**——GBK/Big5 这类在各家 ROM
     * 支持不齐，点出一篇乱码比灰着更坏。{@code offerFullList} 只给常用那张表挂「其他…」入口。
     */
    private void showCharsets(String title, String[] labels, boolean offerFullList,
                              java.util.function.Consumer<String> onPick) {
        Context context = getContext();
        List<DrawerContent.Item> items = new ArrayList<>(labels.length + 1);
        for (String label : labels) {
            items.add(NotesCodec.charsetFor(label) == null
                    ? greyRow(label) : checkRow(label, label.equals(docCharset), () -> onPick.accept(label)));
        }
        if (offerFullList) {
            String[] all = Charset.availableCharsets().keySet().toArray(new String[0]);
            items.add(new DrawerContent.Item(context.getString(R.string.editor_charset_other, all.length),
                    () -> showCharsets(context.getString(R.string.editor_charset_all), all, false, onPick)));
        }
        openDrawer(new DrawerContent(title, null, null, false, items));
    }

    /**
     * 「指定保存的编码」这一格：**保持文本、当场把字节按新档重出一遍**（人裁 2026-10-22「是保持文本的前提下
     * 改编码，而不是单纯保存」）。前提守在这一条上：新档装不下正文里的字就不换——JDK 的默认动作是把那几个字
     * 悄悄替换成 {@code ?}，那是拿用户的正文去换一个标签。
     */
    private void openSaveCharsetPicker() {
        openCharsetPicker(R.string.editor_more_save_charset, charset -> {
            if (blockedByLossy()) {
                warnLossy(); // 换档＝用这缓冲里的字重出字节，字是乱码出来的字节也是 → 先拦，理由说在前头
                return;
            }
            String missing = NotesCodec.unmappable(body.getText().toString(), charset);
            if (!missing.isEmpty()) {
                refuseCharset(charset, missing);
                return;
            }
            docCharset = charset;
            applyDocSettings();
        });
    }

    /** 装不下＝不换档、不落库、正文一个字都不动；把这几个字点出来，再给一条「换一档重选」回去。 */
    private void refuseCharset(String charset, String missing) {
        Context context = getContext();
        List<DrawerContent.Item> items = new ArrayList<>(2);
        items.add(greyRow(context.getString(R.string.editor_charset_nofit, charset,
                missing.codePointCount(0, missing.length()), listSample(missing))));
        items.add(new DrawerContent.Item(context.getString(R.string.editor_charset_repick),
                () -> openSaveCharsetPicker()));
        openDrawer(new DrawerContent(context.getString(R.string.editor_charset_refused),
                null, null, false, items));
    }

    /** 点名点到 12 个字为止：抽屉只有 100dp 宽，全列出来会把提示挤没。 */
    private static String listSample(String chars) {
        int total = chars.codePointCount(0, chars.length());
        int limit = Math.min(total, 12);
        StringBuilder out = new StringBuilder();
        int index = 0;
        for (int i = 0; i < limit; i++) {
            int codePoint = chars.codePointAt(index);
            index += Character.charCount(codePoint);
            if (i > 0) {
                out.append(' ');
            }
            out.appendCodePoint(codePoint);
        }
        if (index < chars.length()) {
            out.append("…");
        }
        return out.toString();
    }

    /**
     * /＝编辑工具，**只管行级与全文级**（人裁 2026-10-22：选区级的复制/剪切/粘贴/翻译/TTS 归**选择气泡**，
     * 不进这一格）。实装十一项，一律作用于**光标所在行**（显示沿用 MT 短名）；
     * 格式化代码/压缩代码仍按人给的原始次序传 null 置灰占位——整眼菜单先看全，缺的档期一眼可见。
     */
    private DrawerContent editToolsContent(Context context) {
        List<DrawerContent.Item> items = new ArrayList<>(13);
        items.add(toolItem(R.string.editor_line_copy, this::copyLine));
        items.add(toolItem(R.string.editor_line_cut, this::cutLine));
        items.add(toolItem(R.string.editor_line_delete, this::deleteLine));
        items.add(toolItem(R.string.editor_line_clear, this::clearLine));
        items.add(toolItem(R.string.editor_line_replace, this::replaceLineWithClipboard));
        items.add(toolItem(R.string.editor_line_duplicate, this::duplicateLine));
        items.add(toolItem(R.string.editor_case_upper, () -> transformLineCase(true)));
        items.add(toolItem(R.string.editor_case_lower, () -> transformLineCase(false)));
        items.add(toolItem(R.string.editor_indent_add, this::indentLine));
        items.add(toolItem(R.string.editor_indent_remove, this::unindentLine));
        items.add(toolItem(R.string.editor_format_code, null));
        items.add(toolItem(R.string.editor_comment_toggle, this::toggleCommentLine));
        items.add(toolItem(R.string.editor_compress_code, null));
        return new DrawerContent(context.getString(R.string.editor_cd_tools),
                null, null, false, items);
    }

    private DrawerContent.Item toolItem(int labelRes, Runnable action) {
        return new DrawerContent.Item(getContext().getString(labelRes), action);
    }

    // ---------- ⋮＝溢出菜单（E6）：人 2026-10-22 定终版清单＝动作八项＋开关四项，按人给的原始次序排 ----------

    /**
     * E6a 实装三格＝统计、自动换行、流畅模式；E6b 再点亮四格＝重新加载文件、以指定编码重新加载、
     * 指定保存的编码、换行符（换行符是人后补的第 13 项，位置排在同为档位选择类的「指定保存的编码」之后）。
     * 两格「重载」对还没落过库的新文档置灰——它没有"文件"可重读。余下仍传 null 置灰（与 {@code /} 同一口径：
     * 整眼菜单先看全，缺的档期一眼可见）。「只读模式」要按篇存，等 E5 索引续列那一格；末尾的「翻译全文」
     * **不在**人这份清单里，是原提案留下的格子，占位等待裁。
     */
    private DrawerContent overflowContent(Context context) {
        boolean hasFile = docId != null;
        List<DrawerContent.Item> items = new ArrayList<>(14);
        items.add(toolItem(R.string.editor_more_save_as, null));
        items.add(new DrawerContent.Item(context.getString(R.string.editor_more_reload),
                hasFile ? this::reloadFromDisk : null));
        items.add(new DrawerContent.Item(context.getString(R.string.editor_more_reload_charset),
                hasFile ? this::reloadWithCharset : null));
        items.add(toolItem(R.string.editor_more_save_charset, this::openSaveCharsetPicker));
        items.add(toolItem(R.string.editor_more_newline, this::openNewlinePicker));
        items.add(toolItem(R.string.editor_more_stats, this::openStats));
        items.add(toolItem(R.string.editor_more_find, null));
        items.add(toolItem(R.string.editor_more_syntax, null));
        items.add(toolItem(R.string.editor_more_goto_line, null));
        items.add(checkItem(R.string.editor_more_word_wrap, prefs.wrap(), this::toggleWrap));
        items.add(checkItem(R.string.editor_more_read_only, false, null));
        items.add(checkItem(R.string.editor_more_smooth, prefs.smooth(), this::toggleSmooth));
        items.add(checkItem(R.string.editor_more_complete, false, null));
        items.add(toolItem(R.string.editor_more_translate_all, null));
        return new DrawerContent(context.getString(R.string.editor_cd_more),
                null, null, false, items);
    }

    /** 勾选类条目＝前缀一枚勾或空框（人原话「以下是是否勾选类的」）；行首对齐已有 START 重力，符号不会飘。 */
    private DrawerContent.Item checkItem(int labelRes, boolean checked, Runnable action) {
        return checkRow(getContext().getString(labelRes), checked, action);
    }

    private DrawerContent.Item checkRow(String label, boolean checked, Runnable action) {
        Context context = getContext();
        return new DrawerContent.Item(context.getString(
                checked ? R.string.editor_check_on : R.string.editor_check_off, label), action);
    }

    private DrawerContent.Item greyRow(String label) {
        return new DrawerContent.Item(label, null);
    }

    private void toggleWrap() {
        prefs.setWrap(!prefs.wrap());
        applyWrapFlag();
        bindDrawer(overflowContent(getContext())); // 原地换内容刷新勾选态，不重走展开流程
    }

    private void toggleSmooth() {
        prefs.setSmooth(!prefs.smooth());
        // 闸门是每次调用现读的，但**已建立**的连接归输入法管，它不重建就一直用旧档——主动要它重取一次。
        InputMethodManager imm = (InputMethodManager)
                getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.restartInput(body);
        }
        bindDrawer(overflowContent(getContext()));
    }

    /** 自动换行只改这一个标志；流畅模式不碰显示与输入标志，它挡的是输入法读文本那一路。 */
    private void applyWrapFlag() {
        body.setHorizontallyScrolling(!prefs.wrap());
        body.invalidate();
    }

    /**
     * 行数／非空白字形数／光标所在行列（{@code {lines, glyphs, caretLine, caretCol}}）。
     * 统计那格与状态戳共用这一套口径——两处各数各的，早晚会给出两个不同的行数。
     */
    private static int[] textMetrics(String text, int caret) {
        int lines = 1;
        int glyphs = 0;
        int caretLine = 1;
        int lineStart = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                lines++;
                if (i < caret) {
                    caretLine++;
                    lineStart = i + 1;
                }
            } else if (!Character.isWhitespace(c)) {
                glyphs++;
            }
        }
        return new int[]{lines, glyphs, caretLine, caret - lineStart + 1};
    }

    /** 统计（E6a）：整屏都是置灰行——这一格只给人看一眼，不该有任何可点的东西。 */
    private void openStats() {
        String text = body.getText().toString();
        int caret = Math.max(0, Math.min(body.getSelectionStart(), text.length()));
        int[] m = textMetrics(text, caret);
        Context context = getContext();
        List<DrawerContent.Item> items = new ArrayList<>(7);
        items.add(greyRow(context.getString(R.string.editor_stat_lines, m[0])));
        items.add(greyRow(context.getString(R.string.editor_stat_glyphs, m[1])));
        items.add(greyRow(context.getString(R.string.editor_stat_chars, text.length())));
        // 字节数按**这篇的编码档与换行档**算：同一篇正文 GBK 比 UTF-8 少三成，硬标 UTF-8 就不是它落盘的大小了
        items.add(greyRow(context.getString(R.string.editor_stat_bytes,
                NotesCodec.encodeBytes(text, docCharset, docNewline).length, docCharset)));
        items.add(greyRow(context.getString(R.string.editor_stat_caret, m[2], m[3])));
        items.add(greyRow(context.getString(R.string.editor_stat_encoding, docCharset)));
        items.add(greyRow(context.getString(R.string.editor_stat_newline, docNewline)));
        openDrawer(new DrawerContent(context.getString(R.string.editor_more_stats),
                null, null, false, items));
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

    /** 缩进档位与注释符：人裁 2026-10-22「先默认是 Python 的」，其余语言等各自的档期再谈。 */
    private static final int INDENT_SPACES = 4;
    private static final String COMMENT_MARK = "#";

    /** 光标在本行的列号（夹进 [0, 行宽]，行尾偏移之外不算）。 */
    private int caretColumn(Line line) {
        int caret = Math.max(0, Math.min(body.getSelectionStart(), line.end));
        return caret - line.start;
    }

    /** 换掉整行并把光标落在给定列——列夹到新行宽以内，否则光标会跨过行尾跑到下一行去。 */
    private void rewriteLine(Line line, String replacement, int caretColumn) {
        Editable text = body.getText();
        text.replace(line.start, line.end, replacement);
        int caret = Math.min(Math.max(0, caretColumn), replacement.length());
        body.setSelection(Math.min(line.start + caret, text.length()));
    }

    /**
     * 转大写/转小写（人裁 2026-10-22：就是字面意思，作用在光标所在行）。
     * {@code Locale.ROOT} 不是可选项：按设备 locale 折大小写时，土耳其语环境会把 i 折成没有点的
     * 「İ/I」——代码行经不起这种改动。
     */
    private void transformLineCase(boolean upper) {
        Line line = cursorLine();
        int column = caretColumn(line);
        String changed = upper
                ? line.text.toUpperCase(Locale.ROOT) : line.text.toLowerCase(Locale.ROOT);
        rewriteLine(line, changed, column);
    }

    /** 增加缩进：行首插一档空格，光标跟着右移一档，落在原来那个字符上。 */
    private void indentLine() {
        Line line = cursorLine();
        int column = caretColumn(line);
        rewriteLine(line, " ".repeat(INDENT_SPACES) + line.text, column + INDENT_SPACES);
    }

    /** 减小缩进：吃掉行首至多一档空格；行首没有空格就整条不动（不动文本＝也不脏化、不占撤销栈）。 */
    private void unindentLine() {
        Line line = cursorLine();
        int strip = 0;
        while (strip < INDENT_SPACES && strip < line.text.length()
                && line.text.charAt(strip) == ' ') {
            strip++;
        }
        if (strip == 0) {
            return;
        }
        int column = caretColumn(line);
        rewriteLine(line, line.text.substring(strip), Math.max(0, column - strip));
    }

    /**
     * 切换注释（Python 的 {@code #}）：注释符落在**行首空白之后**，缩进不被顶掉——
     * 否则连按两次就把一条缩进行推到了行首。取消时把 {@code #} 后面那一个空格一并吃掉，行还原回原样。
     */
    private void toggleCommentLine() {
        Line line = cursorLine();
        int column = caretColumn(line);
        int lead = 0;
        while (lead < line.text.length() && line.text.charAt(lead) == ' ') {
            lead++;
        }
        if (line.text.startsWith(COMMENT_MARK, lead)) {
            int cut = COMMENT_MARK.length();
            if (lead + cut < line.text.length() && line.text.charAt(lead + cut) == ' ') {
                cut++;
            }
            String head = line.text.substring(0, lead);
            int next = column <= lead ? column : Math.max(lead, column - cut);
            rewriteLine(line, head + line.text.substring(lead + cut), next);
        } else {
            String prefix = COMMENT_MARK + " ";
            int next = column < lead ? column : column + prefix.length();
            rewriteLine(line, line.text.substring(0, lead) + prefix + line.text.substring(lead), next);
        }
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
        requestStamp();
    }

    /** 连珠变化的入口：合并成一戳再发（打字期间一条也不发，停手 150 ms 后只剩最后一条）。 */
    private void requestStamp() {
        mainHandler.removeCallbacks(stampTick);
        mainHandler.postDelayed(stampTick, STAMP_QUIET_MS);
    }

    /**
     * 当下这一屏的全部可机读事实，摊成一行。它存在的唯一理由：**悬浮面板是
     * {@code TYPE_ACCESSIBILITY_OVERLAY}，{@code uiautomator dump} 里根本没有它**（本机实测 2026-10-22），
     * 所以不看截屏就没有第二条通路——状态、抽屉里有什么、闸门开合都只能从这儿读。
     */
    private String currentStamp() {
        String text = body.getText().toString();
        int caret = Math.max(0, Math.min(body.getSelectionStart(), text.length()));
        int[] m = textMetrics(text, caret);
        return "doc=" + (docId == null ? "new" : docId)
                + " title=[" + docName.getText() + "]"
                + " chars=" + text.length() + " lines=" + m[0] + " glyphs=" + m[1]
                + " caret=" + m[2] + ":" + m[3]
                + " sel=" + Math.abs(body.getSelectionEnd() - body.getSelectionStart())
                + " dirty=" + (dirty() ? 1 : 0)
                + " charset=" + docCharset + " newline=" + docNewline
                + " lossy=" + lossyCount + " gate=" + (blockedByLossy() ? "closed" : "open")
                + " smooth=" + (prefs.smooth() ? 1 : 0) + " wrap=" + (prefs.wrap() ? 1 : 0)
                + " drawer=" + (drawerOpen() ? "[" + drawerTitle.getText() + "]" : "closed")
                + " bubble=" + (bubble.getVisibility() == VISIBLE ? bubbleItems : 0)
                + " focus=" + focusBrief()
                + " undo=" + undoStack.size() + " redo=" + redoStack.size();
    }

    /**
     * 焦点落在谁身上，附**窗的触摸态**：F2 的机读凭据——不看截屏也要知道 Tab 走到了哪一格。
     * {@code (touch)} 表示窗还在触摸态（此时只有 {@code focusableInTouchMode} 的控件能持有焦点），
     * 键盘把焦点交给扁平钮之后应当翻成 {@code (key)}。
     */
    private String focusBrief() {
        View f = findFocus();
        String who;
        if (f == null) {
            who = "none";
        } else if (f == body) {
            who = "正文";
        } else if (f == this) {
            who = "面板";
        } else {
            String label = labelOf(f);
            who = label.isEmpty() ? f.getClass().getSimpleName() : label;
        }
        return who + (isInTouchMode() ? "(touch)" : "(key)");
    }

    /**
     * 焦点链＝按视图树顺序深度优先收「可聚焦且当前可见」的控件。没有 {@code nextFocus*} 覆盖时
     * 这就是 Tab 的前进序；**方向键**另按几何取最近者（{@link #focusChain} 只保证 Tab 那一路）。
     */
    private List<View> focusChain() {
        List<View> out = new ArrayList<>();
        collectFocusable(this, out);
        return out;
    }

    private void collectFocusable(View view, List<View> out) {
        if (view.getVisibility() != VISIBLE) {
            return;
        }
        // 面板自己那格不算：它 setFocusableInTouchMode(true) 只是为了让返回键落到本视图树。
        if (view != this && view.isFocusable()) {
            out.add(view);
        }
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                collectFocusable(group.getChildAt(i), out);
            }
        }
    }

    private String focusChainBrief() {
        List<View> chain = focusChain();
        View cur = findFocus();
        StringBuilder out = new StringBuilder(160);
        for (int i = 0; i < chain.size(); i++) {
            View v = chain.get(i);
            out.append(i == 0 ? "" : " | ").append(v == cur ? "*" : "").append('#').append(i)
                    .append(' ').append(labelOf(v).isEmpty() ? v.getClass().getSimpleName() : labelOf(v));
        }
        return "chain(" + chain.size() + "):" + (out.length() == 0 ? " 空——一个都走不到" : out);
    }

    /**
     * 量测用键盘通路：{@code here|list|first|last|next|prev|enter|esc}。
     *
     * <p>它把实体键的那几步换成**直接调用框架用的同一支方法**（{@code focusSearch}/{@code requestFocus}
     * /{@link #dispatchKeyEvent}），所以能证「链子顺序对不对、走到的那格能不能触发、ESC 收不收」；
     * **证不到**「键盘的键有没有投递到这扇覆盖窗」——那一段只有外接键盘实按（F2 归人）。
     * 移焦点那一格照 {@code ViewRootImpl.performFocusNavigation} 的做法临时开 {@code focusableInTouchMode}
     * 再关回去：不开的话触摸态下 {@code requestFocus} 会被直接拒掉。
     */
    private String focusForTest(String how) {
        switch (how) {
            case "":
            case "here":
                return "focus=" + focusBrief();
            case "list":
                return focusChainBrief();
            case "first":
            case "last":
            case "next":
            case "prev":
                return moveFocus(how);
            case "enter": {
                View f = findFocus();
                if (f == null) {
                    return "enter: 没有焦点项";
                }
                boolean handled = f.performClick();
                return "enter → [" + labelOf(f) + "] performClick=" + handled;
            }
            case "esc":
                return dispatchKeyEvent(new KeyEvent(0L, 0L,
                        KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE, 0, 0)) ? "esc consumed" : "esc passed";
            default:
                return "unknown how";
        }
    }

    private String moveFocus(String how) {
        List<View> chain = focusChain();
        if (chain.isEmpty()) {
            return "no focusable view at all";
        }
        View cur = findFocus();
        int at = cur == null ? -1 : chain.indexOf(cur);
        int to = switch (how) {
            case "first" -> 0;
            case "last" -> chain.size() - 1;
            case "next" -> (at < 0 ? -1 : at + 1) % chain.size();
            default -> (at <= 0 ? chain.size() : at) - 1;
        };
        if (to < 0 || to >= chain.size()) {
            return "out of chain (at=" + at + " size=" + chain.size() + ")";
        }
        View target = chain.get(to);
        boolean wasTouchFocusable = target.isFocusableInTouchMode();
        target.setFocusableInTouchMode(true);
        boolean gained = target.requestFocus(View.FOCUS_DOWN);
        if (!wasTouchFocusable) {
            target.setFocusableInTouchMode(false);
        }
        return "move " + how + " → #" + to + " [" + labelOf(target) + "] gained=" + gained
                + " now=" + focusBrief();
    }

    /** 只在真变了时落一行（差分）：否则自动落库回调每次都会刷一条一模一样的。 */
    private void emitStamp() {
        String stamp = currentStamp();
        if (stamp.equals(lastStamp)) {
            return;
        }
        lastStamp = stamp;
        android.util.Log.i("EDITORSTATE", stamp);
    }

    /**
     * 量测用（ui-blind-operation F1/F3）：**不经触摸**执行一条编辑器动作，回「动作结果 + 当时状态戳」一行。
     *
     * <p>语法 {@code <verb>[:<arg>]}，空串＝只读状态：
     * {@code open-list} {@code open-tools} {@code open-more} {@code open-newline}
     * {@code open-save-charset} {@code open-reload-charset} {@code open-stats}
     * {@code pick:<任一可点件的文案或可读音名称>} {@code rows}（读抽屉里全部条目，置灰标 {@code (=)}）
     * {@code close-drawer} {@code type:<文本>}（插在光标处，{@code \n} 算换行）
     * {@code caret:<偏移>} {@code sel:<起>:<止>} {@code save} {@code undo} {@code redo} {@code back}
     * {@code focus:<here|list|first|last|next|prev|enter|esc>}（F2 键盘通路，见 {@link #focusForTest}）。
     *
     * <p>每条都走控件自己的 {@code performClick()} 与真实 {@code Editable} 通路，所以这条只证
     * 「动作→状态」的因果；**触摸命中、键盘手感、读屏语音仍归人**（{@code back} 走的是
     * {@link #dispatchKeyEvent} 那格，与实体返回键同一支代码）。
     */
    public String runActionForTest(String spec) {
        String verb;
        String arg;
        int colon = spec == null ? -1 : spec.indexOf(':');
        if (colon < 0) {
            verb = spec == null ? "" : spec.trim();
            arg = "";
        } else {
            verb = spec.substring(0, colon);
            arg = spec.substring(colon + 1);
        }
        String outcome;
        switch (verb) {
            case "":
            case "state":
                outcome = "read";
                break;
            case "open-list":
                openDocDrawer();
                outcome = "opened list";
                break;
            case "open-tools":
                openDrawer(editToolsContent(getContext()));
                outcome = "opened tools";
                break;
            case "open-more":
                openDrawer(overflowContent(getContext()));
                outcome = "opened more";
                break;
            case "open-newline":
                openNewlinePicker();
                outcome = "opened newline";
                break;
            case "open-save-charset":
                openSaveCharsetPicker();
                outcome = "opened save-charset";
                break;
            case "open-reload-charset":
                reloadWithCharset();
                outcome = "opened reload-charset";
                break;
            case "open-stats":
                openStats();
                outcome = "opened stats";
                break;
            case "pick":
                outcome = pickByName(arg);
                break;
            case "rows":
                outcome = listDrawerRows();
                break;
            case "close-drawer":
                closeDrawer();
                outcome = "drawer closed";
                break;
            case "type":
                // 插在光标处、不整篇替换：这样「闸后再打字」量的才是人那一条通路（替换会把坏字连根换掉）
                body.getText().insert(Math.max(0, Math.min(body.getSelectionStart(),
                        body.getText().length())), arg.replace("\\n", "\n"));
                requestStamp();
                outcome = "typed " + body.getText().length();
                break;
            case "caret":
                outcome = setCaret(arg);
                break;
            case "sel":
                outcome = setSelection(arg);
                break;
            case "save":
                saveNow();
                outcome = "saveNow";
                break;
            case "undo":
                undo();
                outcome = "undo";
                break;
            case "redo":
                redo();
                outcome = "redo";
                break;
            case "back":
                outcome = dispatchKeyEvent(new KeyEvent(0L, 0L,
                        KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0, 0)) ? "back consumed" : "back passed";
                break;
            case "focus":
                outcome = focusForTest(arg);
                break;
            default:
                outcome = "unknown verb";
        }
        emitStamp();
        return "action=" + spec + " → " + outcome + " | " + currentStamp();
    }

    /**
     * 按名称点**整块面板**里的一个可点件：可见文案或可读音名称命中即点，返回点到了谁。
     * 抽屉行、栏头那颗、顶栏六个图标钮、气泡六项因此都在同一扇门里——这也是补
     * {@code contentDescription} 的用处所在（图标钮没文案，只有名称，光看 dump 永远点不到）。
     */
    private String pickByName(String needle) {
        if (needle.isEmpty()) {
            return "empty needle";
        }
        View hit = findClickableByName(this, needle);
        if (hit == null) {
            return "no control matching [" + needle + "]";
        }
        hit.performClick();
        return "clicked [" + labelOf(hit) + "]";
    }

    /**
     * 抽屉当前每一条的名字，置灰的带 {@code (=)} 前缀——这是「不看屏也能读到菜单」的那半，
     * 与 {@link #pickByName} 配对：先 {@code rows} 看清有什么，再按名点。栏头那颗用 {@code (+)} 标出。
     */
    private String listDrawerRows() {
        if (!drawerOpen()) {
            return "rows: no drawer";
        }
        StringBuilder out = new StringBuilder(96);
        if (drawerHeaderAction.getVisibility() == VISIBLE) {
            out.append(" (+)").append(drawerHeaderAction.getContentDescription());
        }
        for (int i = 0; i < drawerList.getChildCount(); i++) {
            if (drawerList.getChildAt(i) instanceof Button b) {
                out.append(b.isEnabled() ? " | " : " | (=)").append(b.getText());
            }
        }
        return "rows:" + out;
    }

    /** 深度优先：先遇到编辑器层，再遮罩、抽屉、气泡（添加序即层级序），置灰与隐藏的件一律跳过。 */
    private View findClickableByName(View view, String needle) {
        if (view != this && view.isShown() && view.isEnabled() && view.isClickable()
                && labelOf(view).contains(needle)) {
            return view;
        }
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                View hit = findClickableByName(group.getChildAt(i), needle);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    /** 一个控件的可读音名称＝有文案取文案，没文案取 {@code contentDescription}（图标钮那一类）。 */
    private static String labelOf(View view) {
        if (view instanceof TextView tv && tv.getText().length() > 0) {
            return tv.getText().toString();
        }
        Object desc = view.getContentDescription();
        return desc == null ? "" : desc.toString();
    }

    private String setCaret(String arg) {
        int offset = parseIntOr(arg, -1);
        if (offset < 0) {
            return "bad offset";
        }
        body.setSelection(Math.min(offset, body.getText().length()));
        return "caret " + offset;
    }

    private String setSelection(String arg) {
        int cut = arg.indexOf(':');
        if (cut < 0) {
            return "bad range";
        }
        int start = parseIntOr(arg.substring(0, cut), -1);
        int end = parseIntOr(arg.substring(cut + 1), -1);
        if (start < 0 || end < 0) {
            return "bad range";
        }
        int len = body.getText().length();
        body.setSelection(Math.min(start, len), Math.min(end, len));
        return "sel " + start + ":" + end;
    }

    private static int parseIntOr(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
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

    /**
     * 返回键与 **ESC** 同一支语义（F2）：抽屉开着先收抽屉，否则关窗。
     * ESC 这条是给外接键盘留的——人报「TalkBack 把双击抢走、窗口没法关闭」，读屏态下
     * 自绘控件收不到原始双击，关闭只剩键盘这一出口。
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if ((code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE)
                && event.getAction() == KeyEvent.ACTION_UP) {
            if (drawerOpen()) {
                closeDrawer(); // 抽屉态：第一下收抽屉，面板不动
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
        /** 流畅模式（⋮ 的开关）的闸门档：本类是 static 嵌套，读不到外层，由外面把读法挂进来。 */
        SmoothInputConnection.Gate inputGate;
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

        /**
         * 流畅模式的挂闸处：把系统那条真连接包一层，档位每次调用现读，所以连接本身不必随开关重建
         * （重建时机归输入法，我们叫不动它）。见 {@link SmoothInputConnection}。
         */
        @Override
        public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
            return SmoothInputConnection.of(super.onCreateInputConnection(outAttrs), inputGate);
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
