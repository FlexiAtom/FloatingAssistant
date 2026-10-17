package com.flexiatom.floatingassistant;

import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.flexiatom.floatingassistant.capture.P0Benchmark;
import com.flexiatom.floatingassistant.ocr.OcrOrtProbe;
import com.flexiatom.floatingassistant.ocr.NnapiProbe;
import com.flexiatom.floatingassistant.ocr.P1Runner;
import com.flexiatom.floatingassistant.service.SphereAccessibilityService;

public class MainActivity extends AppCompatActivity {

    public static final String EXTRA_RUN_P0 = "run_p0";
    public static final String EXTRA_P0_ITERATIONS = "p0_iterations";
    public static final String EXTRA_P0_DELAY_MS = "p0_delay_ms";
    public static final String EXTRA_P0_LABEL = "p0_label";
    /** P0 负样本：给本窗口加 FLAG_SECURE 并显示正文页，复现"银行/支付"受保护画面，触发 SECURED/BLACK 分类。 */
    public static final String EXTRA_P0_SECURE = "p0_secure";

    public static final String EXTRA_RUN_ORT = "run_ort";
    public static final String EXTRA_ORT_MODEL = "ort_model";
    public static final String EXTRA_ORT_H = "ort_h";
    public static final String EXTRA_ORT_W = "ort_w";
    public static final String EXTRA_ORT_THREADS = "ort_threads";
    public static final String EXTRA_ORT_ITERATIONS = "ort_iterations";
    public static final String EXTRA_RUN_ORT_NNAPI = "run_ort_nnapi";

    public static final String EXTRA_RUN_P1 = "run_p1";
    public static final String EXTRA_P1_MODE = "p1_mode";
    public static final String EXTRA_P1_DELAY_MS = "p1_delay_ms";
    public static final String EXTRA_P1_THREADS = "p1_threads";
    public static final String EXTRA_P1_REC_MAX_W = "p1_rec_max_w";
    public static final String EXTRA_P1_DET_LIMIT = "p1_det_limit";
    public static final String EXTRA_P1_DENSE = "p1_dense";

    /** P3 端到端演示：铺密集正文页 → 程序触发 router 一次 → 覆盖层逐框流式显示真 OCR。 */
    public static final String EXTRA_P3_DEMO = "p3_demo";
    public static final String EXTRA_P3_DEMO_DELAY_MS = "p3_demo_delay_ms";
    public static final String EXTRA_P3_INPLACE = "p3_inplace";

    /**
     * M1 框选量测入口：把 rect（屏幕 px，"x0,y0,x1,y1"）直接喂给生产的 {@code runOnceRegion}，
     * 结果打日志。存在的理由是人机分工——触摸测试由用户做，我不得注入 input；要给两趟重试定标
     * 就得有一条不经手势的触发路。与 {@code p3_inplace} 同类，属演示/量测入口，不进正式交互。
     */
    public static final String EXTRA_P4_REGION = "p4_region";
    public static final String EXTRA_P4_REGION_RECT = "p4_region_rect";
    public static final String EXTRA_P4_REGION_PAGE = "p4_region_page";

    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.main_status);
        Button enableBtn = findViewById(R.id.main_enable_accessibility);
        enableBtn.setOnClickListener(v -> startActivity(
                new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        Button settingsBtn = findViewById(R.id.main_open_settings);
        settingsBtn.setOnClickListener(v -> startActivity(new Intent(this,
                com.flexiatom.floatingassistant.settings.SettingsActivity.class)));

        maybeRunP0(getIntent());
        maybeRunOrtProbe(getIntent());
        maybeRunOrtNnapi(getIntent());
        maybeRunP1(getIntent());
        maybeRunP3Demo(getIntent());
        maybeRunP3InPlace(getIntent());
        maybeRunP4Region(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        maybeRunP0(intent);
        maybeRunOrtProbe(intent);
        maybeRunOrtNnapi(intent);
        maybeRunP1(intent);
        maybeRunP3Demo(intent);
        maybeRunP3InPlace(intent);
        maybeRunP4Region(intent);
    }

    private void maybeRunP0(Intent intent) {
        if (intent == null || !intent.getBooleanExtra(EXTRA_RUN_P0, false)) {
            return;
        }
        int iterations = intent.getIntExtra(EXTRA_P0_ITERATIONS, 30);
        long delayMs = (long) intent.getIntExtra(EXTRA_P0_DELAY_MS, 0);
        boolean secure = intent.getBooleanExtra(EXTRA_P0_SECURE, false);
        if (secure) {
            showSecurePage();
        }
        String label = intent.getStringExtra(EXTRA_P0_LABEL);
        if (label == null) {
            label = secure ? "secure" : "-";
        }
        final String lbl = label;
        new Thread(() -> P0Benchmark.run(iterations, delayMs, lbl), "p0-benchmark").start();
    }

    private void maybeRunOrtProbe(Intent intent) {
        if (intent == null || !intent.getBooleanExtra(EXTRA_RUN_ORT, false)) {
            return;
        }
        String model = intent.getStringExtra(EXTRA_ORT_MODEL);
        if (model == null) {
            return;
        }
        int h = intent.getIntExtra(EXTRA_ORT_H, 448);
        int w = intent.getIntExtra(EXTRA_ORT_W, 960);
        int threads = intent.getIntExtra(EXTRA_ORT_THREADS, 4);
        int iterations = intent.getIntExtra(EXTRA_ORT_ITERATIONS, 5);
        new Thread(() -> OcrOrtProbe.run(this, model, h, w, threads, iterations), "ort-probe").start();
    }

    private void maybeRunOrtNnapi(Intent intent) {
        if (intent == null || !intent.getBooleanExtra(EXTRA_RUN_ORT_NNAPI, false)) {
            return;
        }
        String model = intent.getStringExtra(EXTRA_ORT_MODEL);
        if (model == null) {
            model = "ch_PP-OCRv4_det_infer.onnx";
        }
        int h = intent.getIntExtra(EXTRA_ORT_H, 448);
        int w = intent.getIntExtra(EXTRA_ORT_W, 960);
        int threads = intent.getIntExtra(EXTRA_ORT_THREADS, 2);
        int iterations = intent.getIntExtra(EXTRA_ORT_ITERATIONS, 5);
        final String mdl = model;
        new Thread(() -> NnapiProbe.run(this, mdl, h, w, threads, iterations), "ort-nnapi").start();
    }

    private void maybeRunP1(Intent intent) {
        if (intent == null || !intent.getBooleanExtra(EXTRA_RUN_P1, false)) {
            return;
        }
        String mode = intent.getStringExtra(EXTRA_P1_MODE);
        if (mode == null) {
            mode = "both";
        }
        long delayMs = (long) intent.getIntExtra(EXTRA_P1_DELAY_MS, 6000);
        int threads = intent.getIntExtra(EXTRA_P1_THREADS, 2);
        int recMaxW = intent.getIntExtra(EXTRA_P1_REC_MAX_W, 0);
        int detLimit = intent.getIntExtra(EXTRA_P1_DET_LIMIT, 0);
        boolean dense = intent.getBooleanExtra(EXTRA_P1_DENSE, false);
        if (dense) {
            showDensePage();
        }
        final String m = mode;
        new Thread(() -> P1Runner.run(this, m, delayMs, threads, recMaxW, detLimit,
                "ch_PP-OCRv4_det_infer.onnx", "ch_PP-OCRv4_rec_infer.onnx", "ppocr_keys_v1.txt"),
                "p1-runner").start();
    }

    private void maybeRunP3Demo(Intent intent) {
        if (intent == null || !intent.getBooleanExtra(EXTRA_P3_DEMO, false)) {
            return;
        }
        showDensePage(); // 屏上铺真实密集正文，供生产管线 OCR
        long delayMs = (long) intent.getIntExtra(EXTRA_P3_DEMO_DELAY_MS, 3000);
        new Thread(() -> {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            SphereAccessibilityService svc = SphereAccessibilityService.getInstance();
            if (svc == null) {
                android.util.Log.e("P3DEMO", "no accessibility service connected");
                return;
            }
            svc.runOnceNow(); // 走生产路径：capture → 懒建 PpOcrEngine → 流式覆盖层
        }, "p3-demo").start();
    }

    private void maybeRunP3InPlace(Intent intent) {
        if (intent == null || !intent.getBooleanExtra(EXTRA_P3_INPLACE, false)) {
            return;
        }
        showDensePage(); // 已知排版的密集页：原位贴图坐标可核对（验位用）
        long delayMs = (long) intent.getIntExtra(EXTRA_P3_DEMO_DELAY_MS, 3000);
        new Thread(() -> {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            SphereAccessibilityService svc = SphereAccessibilityService.getInstance();
            if (svc == null) {
                android.util.Log.e("P3INPLACE", "no accessibility service connected");
                return;
            }
            svc.runOnceNowInPlace(); // M3：capture → OCR 收框 → 逐框译文按矩形原位贴
        }, "p3-inplace").start();
    }

    private void maybeRunP4Region(Intent intent) {
        if (intent == null || !intent.getBooleanExtra(EXTRA_P4_REGION, false)) {
            return;
        }
        final String rectStr = intent.getStringExtra(EXTRA_P4_REGION_RECT);
        if (rectStr == null) {
            android.util.Log.e("P4REGION", "missing p4_region_rect \"x0,y0,x1,y1\"");
            return;
        }
        final int[] rect;
        try {
            String[] parts = rectStr.split(",");
            if (parts.length != 4) {
                throw new NumberFormatException("need 4");
            }
            rect = new int[] {Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()),
                    Integer.parseInt(parts[2].trim()), Integer.parseInt(parts[3].trim())};
        } catch (NumberFormatException e) {
            android.util.Log.e("P4REGION", "bad p4_region_rect=" + rectStr);
            return;
        }
        if ("dense".equals(intent.getStringExtra(EXTRA_P4_REGION_PAGE))) {
            showDensePage();
        }
        long delayMs = (long) intent.getIntExtra(EXTRA_P3_DEMO_DELAY_MS, 3000);
        new Thread(() -> {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            SphereAccessibilityService svc = SphereAccessibilityService.getInstance();
            if (svc == null) {
                android.util.Log.e("P4REGION", "no accessibility service connected");
                return;
            }
            svc.runOnceNowRegion(rect, text ->
                    android.util.Log.i("P4REGION", "rect=" + rectStr + " text=[" + text + "]"));
        }, "p4-region").start();
    }

    // C 用：真实文本栈渲染的密集小字页（TextView 排版 + 自然折行 + 真实 AA），
    // 供无障碍截屏走生产管线，检验 det 分辨率下调在真实内容上的召回。
    private static final String DENSE_PAGE_TEXT =
            "悬浮助手全屏翻译在 Redmi 13R 5G 上验证，SoC 为天玑 6100+（mt6835），"
            + "运行 HyperOS 2.0.208.0.VGQCNXM，基于 Android 15，安全补丁级别 2026-03-01。"
            + "本段用于测试中文小字识别：标点、数字与英文混排如 v0.5、§15/§16、GPLv3、"
            + "1280×720、CPU 占用 86%，以及 URL https://example.com/path?a=1&b=2 的召回。"
            + "识别管线为 PP-OCRv4 mobile：det 输出概率图经 DB 后处理得到四边形框，"
            + "每个框从原图裁剪后送 rec 模型，CTC 解码映射到 6623 字典。"
            + "常见误识包括把“己”读成“已”、把“未”读成“末”，以及把全角括号（）读成半角()。"
            + "第二段落测试更长的连续正文：当文字尺寸接近 15 像素、行距紧凑时，"
            + "检测模型在长边 960、736、640、512 四档下的召回率会如何变化，"
            + "是本实验要回答的问题。若 640 档仍能稳定框出每一行，则可将默认上限下调，"
            + "预计节省约 160 毫秒的检测推理时间。"
            + "Mixed content test: The quick brown fox jumps over the lazy dog 0123456789. "
            + "数字串 3.14159、电话 138-0013-8000、日期 2026/10/15、百分比 45.6% 也应被完整识别。"
            + "第三段继续填充以覆盖整屏：无障碍服务通过 SurfaceControl 截取当前屏幕，"
            + "悬浮球位于左上角可能遮挡少量文字，属已知干扰。"
            + "第四段补充更多行：端侧翻译要求在一次点击内完成检测、识别与替换，"
            + "延迟预算约为一到两秒，因此每一步的耗时都必须可测量、可归因。"
            + "天玑 6100+ 有八个核心，其中两个大核容量 1024、六个小核容量 356，"
            + "推理线程数取 2 通常优于 4，因为超过大核数会引入调度与内存带宽争用。"
            + "识别模型 rec 的输入高度固定为 48，宽度按框的宽高比缩放并夹在 16 到 640 之间，"
            + "过长的行会被截断，因此 recMaxW 从 320 上调到 640 修复了长行丢字。"
            + "DB 后处理把概率统计与八邻域连通分量融合进单次扫描，去掉了单独的布尔二值化数组，"
            + "在热循环里后处理只占个位数毫秒，冷启动 JIT 才会虚高到数百毫秒。"
            + "第五段测试符号与混排：±×÷≈≠≤≥、希腊字母 αβγΔΩ、货币 ￥$€£、"
            + "以及带空格的英文短语 Hello World 与 Full-Width 全角ＡＢＣ１２３。"
            + "以上文本到此结束，用于逐字核对不同检测分辨率下的召回与准确率。";

    private void showDensePage() {
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.setBackgroundColor(android.graphics.Color.WHITE);
        TextView tv = new TextView(this);
        tv.setTextColor(android.graphics.Color.BLACK);
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 15f);
        tv.setLineSpacing(0f, 1.25f);
        tv.setPadding(20, 130, 20, 40);
        tv.setText(DENSE_PAGE_TEXT);
        sv.addView(tv);
        setContentView(sv);
    }

    /** P0 负样本页：加 FLAG_SECURE，复现受保护画面（银行/支付），供截图三态分类验证。 */
    private void showSecurePage() {
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        TextView tv = new TextView(this);
        tv.setText("FLAG_SECURE 负样本正文区。若截图返回 BLACK 或 SECURED，则证明受保护画面被三态判定拦截，"
                + "上层应提示\"此页面不支持\"。此行在白底上本应清晰可读——若截图却读到它，说明 SECURED 分类未生效。");
        tv.setTextColor(android.graphics.Color.BLACK);
        tv.setBackgroundColor(android.graphics.Color.WHITE);
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18f);
        tv.setPadding(48, 260, 48, 48);
        setContentView(tv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean connected = SphereAccessibilityService.isConnected();
        statusText.setText(connected
                ? R.string.main_status_connected
                : R.string.main_status_disconnected);
    }
}
