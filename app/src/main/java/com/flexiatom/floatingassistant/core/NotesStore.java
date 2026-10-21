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
 * <p><b>编码与换行（E6b）</b>：盘上字节按该篇索引里的档位编解码，内存缓冲**恒为换行 {@code \n}**——
 * {@code EditText} 认不了 CRLF，行号/光标行列/撤销快照那一整套也都按 {@code \n} 算，所以归一在进缓冲前、
 * 展开在出盘前，两头各一次（{@link NotesCodec#decodeBytes}/{@link NotesCodec#encodeBytes}）。
 * 换档＝一次真写：只改标签不重编码字节，索引就在撒谎，所以 {@code save} 必须带上当下档位。
 * 编码档认不得时**读按 UTF-8 兜底、写也按 UTF-8 出字节**并记一条日志，绝不因为一档脏了就丢正文。
 *
 * <p>对外一律异步（{@link #save}/{@link #load}/{@link #search}/{@link #open}/{@link #reencode}/{@link #setPinned}），
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

    /**
     * 一篇的打开结果：缓冲里的正文（已按该篇编码解码、归一成 {@code \n}）＋该篇当下的索引元数据。
     * 编辑器要拿 {@code record.charset}/{@code record.newline} 填信息带和那两个档位抽屉，
     * 所以这两样一起回，省得人那边再发一次异步读、再对一次时序。
     */
    public static final class Doc {
        public final String text;
        public final Record record;

        Doc(String text, Record record) {
            this.text = text;
            this.record = record;
        }
    }

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
                if (NotesCodec.matches(pattern, query, r.title,
                        readBody(r.id, r.charset, SEARCH_BODY_LIMIT))) {
                    out.add(r);
                }
            }
            return out;
        }, onMain);
    }

    /**
     * 打开一篇：正文按**该篇索引里记的编码**解码并归一成 {@code \n}（见 {@link NotesCodec#toBuffer}），
     * 连同索引元数据一起回——调用方要拿编码/换行档位填信息带和抽屉。
     * 文件不在＝空串（行仍在列表里，标题还认，别让一次 IO 抖动把用户的条目变没）。
     */
    public void open(String id, Consumer<Doc> onMain) {
        post(() -> {
            Record record = recordOf(id);
            return new Doc(readBody(id, record.charset, Integer.MAX_VALUE), record);
        }, onMain);
    }

    /**
     * 落库一篇；编码与换行档位由调用方给（{@code null} 走默认档）。回传实际使用的 id（新建时是新生成的）。
     * <p><b>换档必须触发一次真写</b>：光改索引里的标签、字节还是旧编码，标签就成了谎话——所以这里
     * 是先建记录（顺便把标签洗成规范形）再按它编码出盘上字节，索引与文件不可能两套。
     */
    public void save(String id, String text, String charset, String newline, Consumer<String> onMain) {
        post(() -> saveSync(id, text, charset, newline), onMain);
    }

    /**
     * 「以指定编码重新加载」（人裁：字面意思，⋮ → 文件 → 指定编码重新加载）：把档位**写进索引**，
     * 再按新档重读盘上原始字节。回传新的 {@link Doc}；{@code null}＝索引里没有这一条。
     */
    public void reencode(String id, String charset, Consumer<Doc> onMain) {
        post(() -> {
            List<Record> all = indexHealed();
            List<Record> next = new ArrayList<>(all.size());
            Record target = null;
            for (Record r : all) {
                if (!r.id.equals(id)) {
                    next.add(r);
                } else {
                    target = new Record(r.id, r.pinned, r.updatedMs, r.title, charset, r.newline);
                    next.add(target);
                }
            }
            if (target == null) {
                return null;
            }
            persist(NotesCodec.encode(NotesCodec.sorted(next)));
            return new Doc(readBody(id, target.charset, Integer.MAX_VALUE), target);
        }, onMain);
    }

    /** 置顶/取消置顶；只动索引，不碰正文。回传该文档当下的置顶态，null＝索引里没有这一条。 */
    public void setPinned(String id, boolean pinned, Consumer<Boolean> onMain) {
        post(() -> {
            List<Record> records = NotesCodec.decode(prefs.getString(K_INDEX, ""));
            Boolean done = null;
            List<Record> next = new ArrayList<>(records.size());
            for (Record r : records) {
                if (r.id.equals(id)) {
                    r = new Record(r.id, pinned, r.updatedMs, r.title, r.charset, r.newline);
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

    private String saveSync(String id, String text, String charset, String newline) {
        String target = id == null || id.isEmpty() ? newId() : id;
        String body = text == null ? "" : text;
        if (!dir.isDirectory() && !dir.mkdirs()) {
            Log.w(TAG, "notes dir unavailable: " + dir.getAbsolutePath());
            return null;
        }
        List<Record> next = new ArrayList<>();
        Record stale = null;
        for (Record r : NotesCodec.decode(prefs.getString(K_INDEX, ""))) {
            if (!r.id.equals(target)) {
                next.add(r);
            } else {
                stale = r; // 编辑不改置顶态
            }
        }
        // 先建记录：标签经它洗成规范形，盘上字节再按**同一个**记录编码，索引与文件不会两套
        Record record = new Record(target, stale != null && stale.pinned,
                System.currentTimeMillis(), NotesCodec.deriveTitle(body), charset, newline);
        if (NotesCodec.charsetFor(record.charset) == null) {
            Log.w(TAG, "charset unavailable, bytes written as UTF-8: " + record.charset);
        }
        File tmp = new File(dir, "." + target + ".tmp");
        File out = new File(dir, target + SUFFIX);
        try (FileOutputStream stream = new FileOutputStream(tmp)) {
            stream.write(NotesCodec.encodeBytes(body, record.charset, record.newline));
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
        next.add(record);
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

    /** 盘上原始字节（截到 {@code byteLimit}）；文件不在或读失败回 {@code null}，由 {@link #readBody} 换成空串。 */
    private byte[] readBytes(String id, int byteLimit) {
        File file = new File(dir, id + SUFFIX);
        if (!file.isFile()) {
            return null;
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
            if (done == read) {
                return buffer;
            }
            byte[] head = new byte[done];
            System.arraycopy(buffer, 0, head, 0, done);
            return head;
        } catch (IOException e) {
            Log.w(TAG, "body read failed: " + id, e);
            return null;
        }
    }

    /** 按该篇的编码档解码成缓冲（{@code \n} 归一＋剥 BOM 都在 {@link NotesCodec#decodeBytes} 里）。 */
    private String readBody(String id, String charset, int byteLimit) {
        byte[] raw = readBytes(id, byteLimit);
        return raw == null ? "" : NotesCodec.decodeBytes(raw, charset);
    }

    /** 索引里这一条；没记上（新建、或自愈前的孤儿）就现造一条默认档，读侧不需要分支。 */
    private Record recordOf(String id) {
        for (Record r : NotesCodec.decode(prefs.getString(K_INDEX, ""))) {
            if (r.id.equals(id)) {
                return r;
            }
        }
        return new Record(id, false, 0L, "", null, null);
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
            // 只为取标题而读个开头，不整篇进内存；孤儿文件没有索引，档位只能按默认档猜（下一次 save 记真身）
            records.add(new Record(id, false, file.lastModified(),
                    NotesCodec.deriveTitle(readBody(id, NotesCodec.DEFAULT_CHARSET, 4096)), null, null));
        }
        // 自愈只补进内存、不反写索引：读操作不该有写副作用，真身下一次 save 会一并记下。
        return NotesCodec.sorted(records);
    }
}
