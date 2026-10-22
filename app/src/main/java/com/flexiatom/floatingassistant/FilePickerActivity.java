package com.flexiatom.floatingassistant;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import java.util.function.Consumer;

/**
 * SAF 系统选择器的**接线盒**（m5-editor-asr-notes E6c，人裁「另存为/重新加载走 SAF 系统选择器」）。
 *
 * <p>为什么需要一扇 Activity：悬浮编辑器挂在无障碍服务窗上，服务侧根本没有
 * {@code startActivityForResult} 那一路；而 SAF 的 {@code OPEN_DOCUMENT}/{@code CREATE_DOCUMENT}
 * 只能"由谁发起、结果回谁"。所以这里只开一扇透明的、不进最近任务的空壳，把选择器转手接回来，
 * 拿到 URI 就交出去、自己退场——它不画任何东西，也不留状态。
 *
 * <p>为什么结果走**一个静态单槽**而不是广播或绑定：同一时刻只可能有一篇文档在选文件（编辑器只有一扇），
 * 而广播要签名与注册、绑定要连接生命周期，两样都比这一格的事重。代价写在 {@link #request} 里。
 *
 * <p>权限：结果里带 {@code GRANT_PERSISTABLE} 的就转成持久授权（{@code READ|WRITE} 一起要，
 * 拿不到写权限时退回只读——那时"重新加载"仍然通，"存回去"由存储层如实报失败，不假装成功）。
 */
public final class FilePickerActivity extends Activity {

    private static final String TAG = "FilePicker";
    private static final int REQ = 0x46_4c_45; // 'FLE'
    public static final String MODE_OPEN = "open";
    public static final String MODE_CREATE = "create";
    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_NAME = "name";

    /** 单槽回调：进程在选择器前台被杀就没了，结果落进 null 槽、只留一行日志（不做第二次投递的幻想）。 */
    private static Consumer<Uri> waiter;

    /**
     * 发起一次选择。{@code onResult} 收到 {@code null}＝人按了返回/取消（**不**等于失败，别当错误弹）。
     * 已有回调挂在这儿时新的会顶掉旧的并留一行日志：正常流程走不到这一格（抽屉一次只开一件事），
     * 真走到了就是上面那条"进程被杀"的账，宁可丢一次结果也不排队重放。
     */
    public static void request(Context context, String mode, String suggestedName, Consumer<Uri> onResult) {
        if (waiter != null) {
            Log.w(TAG, "replacing an outstanding picker request: " + mode);
        }
        waiter = onResult;
        Intent intent = new Intent(context, FilePickerActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_MODE, mode)
                .putExtra(EXTRA_NAME, suggestedName);
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        String mode = getIntent() == null ? MODE_OPEN : getIntent().getStringExtra(EXTRA_MODE);
        boolean create = MODE_CREATE.equals(mode);
        Intent pick = new Intent(create ? Intent.ACTION_CREATE_DOCUMENT : Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                // 打开那张表给 */*：文本类文件的 MIME 在各家 ROM 上不齐（.md/.log 常报成
                // application/octet-stream），收窄成 text/* 会让人"我那个文件呢"——选择器由系统画，
                // 收窄挡掉的是真存在的文件，不是坏文件。
                .setType(create ? "text/plain" : "*/*");
        if (create) {
            String name = getIntent().getStringExtra(EXTRA_NAME);
            pick.putExtra(Intent.EXTRA_TITLE, name == null || name.isEmpty() ? "untitled.txt" : name);
        }
        try {
            startActivityForResult(pick, REQ);
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "no documents UI on this device", e);
            deliver(null);
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ) {
            return;
        }
        Uri uri = resultCode == RESULT_OK && data != null ? data.getData() : null;
        if (uri != null) {
            persist(uri, data.getFlags());
        }
        deliver(uri);
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 只在**真收尾**时把槽交出去（null＝取消）。转屏造成的销毁不算：回调挂在静态槽上跨配置变更还在，
        // 在这儿误发一次 null，人随后选好文件就会撞上"no waiter"，那一选就真丢了。
        if (isFinishing() && waiter != null) {
            deliver(null);
        }
    }

    private void persist(Uri uri, int flags) {
        boolean persistable = (flags & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0;
        if (!persistable) {
            return; // 系统没给持久授权，这一次会话内照样能用；下次开机要重选，由存储层报"读不到"
        }
        int rw = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        try {
            getContentResolver().takePersistableUriPermission(uri, rw);
            return;
        } catch (RuntimeException readWrite) {
            Log.w(TAG, "write persist unavailable, retrying read-only: " + uri);
        }
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (RuntimeException e) {
            Log.w(TAG, "no persistable grant taken: " + uri, e);
        }
    }

    private void deliver(Uri uri) {
        Consumer<Uri> pending = waiter;
        waiter = null;
        if (pending == null) {
            Log.w(TAG, "picker result with no waiter: " + uri);
            return;
        }
        pending.accept(uri);
    }
}
