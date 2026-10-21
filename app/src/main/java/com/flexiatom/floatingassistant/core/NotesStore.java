package com.flexiatom.floatingassistant.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.flexiatom.floatingassistant.core.NotesCodec.Record;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * 记事本持久层（m5-editor-asr-notes E5）。**正文一篇一个文件，索引一条一行放 prefs**——
 * 不走 Room，对账见该项 §4b（一句话：本片的查询需求只有"正则"，而 {@code REGEXP} 在 SQLite 不是内置
 * 运算符、Android 框架也没公开注册自定义函数的通路，所以 SQL 路线在唯一那一格查询上并不更便宜，
 * 代价却是给纯 Java 仓引入第一个注解处理器）。
 *
 * <p>路径：{@code getFilesDir()/notes/<id>.txt}——文档**就是文件**，文件管理器可直读、可整目录备份，
 * 与本项目"模型放 Downloads（可见可取）"同一取向。索引在独立 prefs {@code app_notes} 的 {@code index} 键，
 * 格式见 {@link NotesCodec}。
 *
 * <p><b>为什么这里用 {@code commit()} 而非常规的 {@code apply()}</b>：E5 的机读验收格是
 * 「进程被杀后文档仍在」，{@code apply()} 把落盘排到后台线程，紧随其后的进程被杀可能一次都不写；
 * {@code commit()} 同步等写完（进程被杀拦不住它，掉电才拦不住——那不在验收口径里）。
 * 代价是同步磁盘 IO，因此**所有写都走 {@code notes-io} 单线程**，绝不在主线程调用。
 *
 * <p><b>写序＝先文件后索引</b>：正文先写临时文件、{@code sync()}、再 {@code renameTo} 原子替换，
 * 之后才提交索引。这样最坏情况是"文件在、索引还没记上"（孤儿文件），下次读取会自愈成新一条
 * （{@link #indexHealed()}）；反过来则会静默丢正文。
 *
 * <p>对外一律异步（{@link #save}/{@link #load}/{@link #search}/{@link #body}/{@link #setPinned}），
 * 回调**回主线程**；参数 {@code id} 为 null/空＝还没有身份的新文档，落库时生成并回传。
 * 回调收到 {@code null} 表示这次 IO 失败（磁盘满、rename 被拒等），由调用方决定怎么告诉人。
 */
public final class NotesStore {

    private static final String TAG = "NotesStore";
    private static final String PREFS = "app_notes";
    private static final String K_INDEX = "index";
    private static final String DIR_NAME = "notes";
    private static final String SUFFIX = ".txt";
    /** 搜索读每篇正文比对，语料上限按此口径守（超出就该换成落盘倒排索引，见失效边界）。 */
    private static final int SEARCH_BODY_LIMIT = 256 * 1024;

    private static volatile NotesStore instance;

    private final SharedPreferences prefs;
    private final File dir;
    private final ExecutorService io = Executors.newSingleThreadExecutor(
            runnable -> new Thread(runnable, "notes-io"));
    private final Handler main = new Handler(Looper.getMainLooper());

    private NotesStore(Context ctx) {
        Context app = ctx.getApplicationContext();
        this.prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.dir = new File(app.getFilesDir(), DIR_NAME);
    }

    public static NotesStore of(Context ctx) {
        NotesStore local = instance;
        if (local == null) {
            synchronized (NotesStore.class) {
                local = instance;
                if (local == null) {
                    local = new NotesStore(ctx);
                    instance = local;
                }
            }
        }
        return local;
    }

    // ---------------------------------------------------------------- 异步接口

    /** 全部文档（置顶在前、组内更新时间倒序），已对孤儿文件做过自愈。 */
    public void load(Consumer<List<Record>> onMain) {
        post(() -> indexHealed(), onMain);
    }

    /**
     * 正则优先的搜索（人裁：搜索框默认正则）；语法不成立时退回字面包含。
     * 命中标题或正文皆可——所以要把每篇正文读一遍，语料大到读数明显时按 {@link #SEARCH_BODY_LIMIT} 截。
     */
    public void search(String query, Consumer<List<Record>> onMain) {
        post(() -> {
            List<Record> all = indexHealed();
            if (query == null || query.isEmpty()) {
                return all;
            }
            Pattern pattern = NotesCodec.tryCompile(query);
            List<Record> out = new ArrayList<>();
            for (Record r : all) {
                if (NotesCodec.matches(pattern, query, r.title, readBody(r.id, SEARCH_BODY_LIMIT))) {
                    out.add(r);
                }
            }
            return out;
        }, onMain);
    }

    /** 读正文；文件不在＝空串（行仍在列表里，标题还认，别让一次 IO 抖动把用户的条目变没）。 */
    public void body(String id, Consumer<String> onMain) {
        post(() -> readBody(id, Integer.MAX_VALUE), onMain);
    }

    /** 落库一篇；回传实际使用的 id（新建时是新生成的）。 */
    public void save(String id, String text, Consumer<String> onMain) {
        post(() -> saveSync(id, text), onMain);
    }

    /** 置顶/取消置顶；只动索引，不碰正文。回传该文档当下的置顶态，null＝索引里没有这一条。 */
    public void setPinned(String id, boolean pinned, Consumer<Boolean> onMain) {
        post(() -> {
            List<Record> records = NotesCodec.decode(prefs.getString(K_INDEX, ""));
            Boolean done = null;
            List<Record> next = new ArrayList<>(records.size());
            for (Record r : records) {
                if (r.id.equals(id)) {
                    r = new Record(r.id, pinned, r.updatedMs, r.title);
                    done = pinned;
                }
                next.add(r);
            }
            if (done != null) {
                persist(NotesCodec.encode(NotesCodec.sorted(next)));
            }
            return done;
        }, onMain);
    }

    // ---------------------------------------------------------------- 落盘本体（只在 notes-io 线程上跑）

    private <T> void post(java.util.function.Supplier<T> work, Consumer<T> onMain) {
        io.execute(() -> {
            T value;
            try {
                value = work.get();
            } catch (RuntimeException e) {
                Log.w(TAG, "notes io failed", e);
                value = null;
            }
            final T result = value;
            main.post(() -> {
                if (onMain != null) {
                    onMain.accept(result);
                }
            });
        });
    }

    private String saveSync(String id, String text) {
        String target = id == null || id.isEmpty() ? newId() : id;
        String body = text == null ? "" : text;
        if (!dir.isDirectory() && !dir.mkdirs()) {
            Log.w(TAG, "notes dir unavailable: " + dir.getAbsolutePath());
            return null;
        }
        File tmp = new File(dir, "." + target + ".tmp");
        File out = new File(dir, target + SUFFIX);
        try (FileOutputStream stream = new FileOutputStream(tmp)) {
            stream.write(body.getBytes(StandardCharsets.UTF_8));
            stream.flush();
            stream.getFD().sync(); // 先确保正文真到了盘上，再动索引
        } catch (IOException e) {
            Log.w(TAG, "body write failed: " + target, e);
            return null;
        }
        if (!tmp.renameTo(out)) {
            Log.w(TAG, "body rename failed: " + target);
            tmp.delete();
            return null;
        }
        boolean pinned = false;
        List<Record> next = new ArrayList<>();
        for (Record r : NotesCodec.decode(prefs.getString(K_INDEX, ""))) {
            if (!r.id.equals(target)) {
                next.add(r);
            } else {
                pinned = r.pinned; // 编辑不改置顶态
            }
        }
        next.add(new Record(target, pinned, System.currentTimeMillis(), NotesCodec.deriveTitle(body)));
        persist(NotesCodec.encode(NotesCodec.sorted(next)));
        return target;
    }

    private void persist(String encoded) {
        prefs.edit().putString(K_INDEX, encoded).commit();
    }

    /** 时间戳 base36 作 id；同毫秒撞上了往后挪，不引第二个随机源。 */
    private String newId() {
        long now = System.currentTimeMillis();
        while (true) {
            String candidate = "d" + Long.toString(now, 36);
            if (!new File(dir, candidate + SUFFIX).exists()) {
                return candidate;
            }
            now++;
        }
    }

    private String readBody(String id, int byteLimit) {
        File file = new File(dir, id + SUFFIX);
        if (!file.isFile()) {
            return "";
        }
        long length = file.length();
        int read = (int) Math.min(length, byteLimit);
        byte[] buffer = new byte[read];
        try (FileInputStream stream = new FileInputStream(file)) {
            int done = 0;
            while (done < read) {
                int n = stream.read(buffer, done, read - done);
                if (n < 0) {
                    break;
                }
                done += n;
            }
            return new String(buffer, 0, done, StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.w(TAG, "body read failed: " + id, e);
            return "";
        }
    }

    /** 索引 ＋ 自愈：正文文件在、索引没记上（写文件后被杀）时，按文件补一条，别把内容藏没。 */
    private List<Record> indexHealed() {
        List<Record> records = new ArrayList<>(
                NotesCodec.decode(prefs.getString(K_INDEX, "")));
        File[] files = dir.listFiles((d, name) -> name.endsWith(SUFFIX));
        if (files == null) {
            return NotesCodec.sorted(records);
        }
        Set<String> known = new HashSet<>();
        for (Record r : records) {
            known.add(r.id);
        }
        for (File file : files) {
            String name = file.getName();
            String id = name.substring(0, name.length() - SUFFIX.length());
            if (id.isEmpty() || id.startsWith(".") || known.contains(id)) {
                continue;
            }
            known.add(id);
            // 只为取标题而读个开头，不整篇进内存
            records.add(new Record(id, false, file.lastModified(),
                    NotesCodec.deriveTitle(readBody(id, 4096))));
        }
        // 自愈只补进内存、不反写索引：读操作不该有写副作用，真身下一次 save 会一并记下。
        return NotesCodec.sorted(records);
    }
}
