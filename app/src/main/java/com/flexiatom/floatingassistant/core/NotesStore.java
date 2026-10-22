package com.flexiatom.floatingassistant.core;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.util.Log;

import com.flexiatom.floatingassistant.core.NotesCodec.Record;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
 * <p><b>外部文件（E6c）</b>：索引第七列绑一个 URI（{@code content://} 由 SAF 选择器给出，人裁走系统选择器）。
 * 绑上之后**那个文件就是这篇的正文**，读写都以它为准；应用内 {@code notes/<id>.txt} 降级成**镜像**——
 * 每次成功写外部后顺手再写一份，写不上不判这次保存失败（它只是兜底与备份，不是第二真值；两份真值的
 * 判据是「谁被读」，读的一侧只走 URI）。这么做的理由有两条，都在人这边：搜索要逐篇读正文（
 * {@link #SEARCH_BODY_LIMIT} 那一格），有镜像就不必为搜索去敲每个 provider；以及 provider 写挂了
 * （权限被撤、云盘没网）时，那一份旧镜像还留在盘上，比直接丢强。
 * <p>外部读回来的字节会现算一次换行档位（{@link NotesCodec#detectNewline}，人裁「盘上第一个换行是什么档
 * 就认什么档」）：读操作仍然**不反写索引**（下面 {@link #indexHealed()} 那条同一口径），带回去的
 * {@link Doc#record} 里已是检测到的档位，视图侧把它认作"盘上字节与标签不一致"，下一次自然落库顺手记正。
 *
 * <p><b>读外部失败＝回 {@code null}</b>，与"应用内文件不在＝空串"故意不同：一篇住在外部文件上的文档，
 * 读不到就是读不到，不能把它显示成一篇空文档——那等于在人眼前把正文擦没。
 *
 * <p>对外一律异步（{@link #save}／{@link #load}／{@link #search}／{@link #open}／{@link #openUri}
 * ／{@link #reencode}／{@link #setPinned}），
 * 回调**回主线程**；参数 {@code id} 为 null/空＝还没有身份的新文档，落库时生成并回传。
 * 回调收到 {@code null} 表示这次 IO 失败（磁盘满、rename 被拒、外部文件读不到等），由调用方决定怎么告诉人。
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
    private final ContentResolver resolver;
    private final File dir;
    private final ExecutorService io = Executors.newSingleThreadExecutor(
            runnable -> new Thread(runnable, "notes-io"));
    private final Handler main = new Handler(Looper.getMainLooper());

    private NotesStore(Context ctx) {
        Context app = ctx.getApplicationContext();
        this.prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.resolver = app.getContentResolver();
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
     *
     * <p>{@code name}＝外部文件的显示名（绑了才有，应用内的篇为 {@code null}）：那串
     * {@code content://} URI 不该逼人读完，所以要在**这条 IO 已经所在的线程**上顺手问一次
     * {@link #displayName}，而不是让视图回头在主线程再查一遍。
     */
    public static final class Doc {
        public final String text;
        public final Record record;
        public final String name;

        Doc(String text, Record record, String name) {
            this.text = text;
            this.record = record;
            this.name = name;
        }
    }

    /** 全部文档（置顶在前、组内更新时间倒序），已对孤儿文件做过自愈。 */
    public void load(Consumer<List<Record>> onMain) {
        post(() -> indexHealed(), onMain);
    }

    /**
     * 正则优先的搜索（人裁：搜索框默认正则）；语法不成立时退回字面包含。
     * 命中标题或正文皆可——所以要把每篇正文读一遍，语料大到读数明显时按 {@link #SEARCH_BODY_LIMIT} 截。
     * <p>绑了外部文件的篇，这里读的是**应用内那份镜像**（见类注释）：搜索是每次敲键都跑的事，
     * 不该拿它去敲每个 provider；代价是镜像写不上时那一篇会搜不到——那时盘上真身仍在，
     * 打开它仍读得到，所以这条误差只影响搜索一格。
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
     * 打开一篇：绑了外部文件的**以那个文件为准**（读不到就回 {@code null}，不当空文档），
     * 没绑的读应用内那份（文件不在＝空串，见类注释里那条区分）。
     * 外部读回来的字节现算一次换行档，随 {@link Doc#record} 一起带回。
     */
    public void open(String id, Consumer<Doc> onMain) {
        post(() -> readDoc(recordOf(id)), onMain);
    }

    /**
     * 打开一个**外部文件**（E6c「打开文件…」，人给的十二项写的是「至少包含」，这一格是补的）：
     * 正文＝那个 URI 上的字节，编码先按 UTF-8 解（外部文件没有索引可问；解出坏字由视图那边的闸机说话，
     * 人要改就走到「以指定编码重新加载」），换行按盘上第一个现算。
     * 回传的记录 {@code id} 为 null——这篇在索引里还不存在，第一次落库才生成身份并连带把 URI 记上。
     */
    public void openUri(String uri, Consumer<Doc> onMain) {
        post(() -> {
            byte[] raw = readExternal(uri);
            if (raw == null) {
                return null;
            }
            String text = NotesCodec.decodeBytes(raw, NotesCodec.DEFAULT_CHARSET);
            return new Doc(text, new Record(null, false, System.currentTimeMillis(),
                    NotesCodec.deriveTitle(text), NotesCodec.DEFAULT_CHARSET,
                    NotesCodec.detectNewline(raw), uri), displayName(uri));
        }, onMain);
    }

    /**
     * 落库一篇；编码与换行档位由调用方给（{@code null} 走默认档）。回传实际使用的 id（新建时是新生成的）。
     * <p><b>换档必须触发一次真写</b>：光改索引里的标签、字节还是旧编码，标签就成了谎话——所以这里
     * 是先建记录（顺便把标签洗成规范形）再按它编码出盘上字节，索引与文件不可能两套。
     * <p>{@code uri} 三态：非 null＝把这篇**绑到／改绑到**那个外部文件；null＝沿用索引里已有的绑定
     * （所以视图侧不必每次把 URI 传回来）；空串＝解绑（正文搬回应用内）。绑上之后盘上字节以外部文件为准，
     * 应用内那份降为镜像（见类注释）。
     */
    public void save(String id, String text, String charset, String newline, String uri,
                     Consumer<String> onMain) {
        post(() -> saveSync(id, text, charset, newline, uri), onMain);
    }

    /**
     * 「以指定编码重新加载」（人裁：字面意思，⋮ → 文件 → 指定编码重新加载）：把档位**写进索引**，
     * 再按新档重读原始字节（绑了外部文件就读那个）。回传新的 {@link Doc}；
     * {@code null}＝索引里没有这一条，或外部文件读不到。
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
                    target = new Record(r.id, r.pinned, r.updatedMs, r.title, charset, r.newline, r.uri);
                    next.add(target);
                }
            }
            if (target == null) {
                return null;
            }
            persist(NotesCodec.encode(NotesCodec.sorted(next)));
            return readDoc(target);
        }, onMain);
    }

    /**
     * 「以指定编码重新加载」对一个**还没进索引的外部文件**（刚「打开文件…」，一次落库都没发生过）：
     * 没有记录可写，就只按新档重读那串字节，档位随 {@link Doc#record} 带回；视图把它当影子，
     * 下一次自然落库才把 URI 与这一档一起记进索引。读不到回 {@code null}。
     */
    public void reencodeUri(String uri, String charset, Consumer<Doc> onMain) {
        post(() -> {
            byte[] raw = readExternal(uri);
            if (raw == null) {
                return null;
            }
            String text = NotesCodec.decodeBytes(raw, charset);
            return new Doc(text, new Record(null, false, System.currentTimeMillis(),
                    NotesCodec.deriveTitle(text), charset, NotesCodec.detectNewline(raw), uri),
                    displayName(uri));
        }, onMain);
    }

    /**
     * 改名（人裁 2026-10-23「三点的左栏应该提供重命名文件，并且支持改后缀」）。**只动文件名，不动正文**：
     * {@code file://} 走同目录 {@code renameTo}（目标已存在就拒，不覆盖别人），其余走
     * {@code DocumentsContract.renameDocument}——后缀就是名字的一部分，改 {@code .txt→.md} 与改名是同一条路。
     * 回传**新 URI**（provider 可能换一个 documentId，调用方不能拿旧串继续写），失败回 {@code null}。
     */
    public void rename(String uri, String newName, Consumer<String> onMain) {
        post(() -> {
            String moved = renameSync(uri, newName);
            if (moved != null) {
                rebind(uri, moved);
            }
            return moved;
        }, onMain);
    }

    /**
     * 把索引里指着旧名的每一格改成新名。**不交给"下一次保存"去做**：有损闸关着时保存那条路整条不走
     * （实测 10-23：GBK 文件按 UTF-8 读 → {@code gate=closed} → 名字改了、索引还指着已经不存在的旧名，
     * 「重新加载」当场读不到）。改名动的是身份，不是正文，所以这一列由改名自己在同一次工作线程里改掉。
     */
    private void rebind(String oldUri, String newUri) {
        List<Record> records = NotesCodec.decode(prefs.getString(K_INDEX, ""));
        List<Record> next = new ArrayList<>(records.size());
        boolean changed = false;
        for (Record r : records) {
            if (oldUri.equals(r.uri)) {
                r = new Record(r.id, r.pinned, r.updatedMs, r.title, r.charset, r.newline, newUri);
                changed = true;
            }
            next.add(r);
        }
        if (changed) {
            persist(NotesCodec.encode(NotesCodec.sorted(next)));
        }
    }

    private String renameSync(String uri, String name) {
        String target = name == null ? "" : name.replace('/', ' ').trim();
        if (uri == null || uri.isEmpty() || target.isEmpty()) {
            return null;
        }
        if (uri.startsWith("file://")) {
            File from = new File(Uri.parse(uri).getPath());
            File parent = from.getParentFile();
            if (parent == null) {
                return null; // 没有父目录就不知道往哪搁新名字，别拿进程工作目录当落点
            }
            File to = new File(parent, target);
            if (!from.isFile() || (to.exists() && !to.equals(from))) {
                Log.w(TAG, "rename refused: " + uri + " → " + target);
                return null;
            }
            return from.renameTo(to) ? Uri.fromFile(to).toString() : null;
        }
        try {
            Uri moved = DocumentsContract.renameDocument(resolver, Uri.parse(uri), target);
            return moved == null ? null : moved.toString();
        } catch (IOException | RuntimeException e) {
            // renameDocument 的失败是抛出来的（provider 不让改名＝FileNotFoundException／SecurityException），
            // 而"改不成"是这次动作的正常结局之一，说成一行日志、回 null，让它自己说话
            Log.w(TAG, "rename failed: " + uri + " → " + target, e);
            return null;
        }
    }

    /** 置顶/取消置顶；只动索引，不碰正文。回传该文档当下的置顶态，null＝索引里没有这一条。 */
    public void setPinned(String id, boolean pinned, Consumer<Boolean> onMain) {
        post(() -> {
            List<Record> records = NotesCodec.decode(prefs.getString(K_INDEX, ""));
            Boolean done = null;
            List<Record> next = new ArrayList<>(records.size());
            for (Record r : records) {
                if (r.id.equals(id)) {
                    r = new Record(r.id, pinned, r.updatedMs, r.title, r.charset, r.newline, r.uri);
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

    private String saveSync(String id, String text, String charset, String newline, String uri) {
        String body = text == null ? "" : text;
        String target = id == null || id.isEmpty() ? idOfBoundRow(uri) : id;
        if (target == null) {
            target = newId();
        }
        List<Record> next = new ArrayList<>();
        Record stale = null;
        for (Record r : NotesCodec.decode(prefs.getString(K_INDEX, ""))) {
            if (!r.id.equals(target)) {
                next.add(r);
            } else {
                stale = r; // 编辑不改置顶态，也不改绑定——绑定只在调用方明确给出 uri 时才动
            }
        }
        String bound = uri != null ? uri : (stale == null ? "" : stale.uri);
        // 先建记录：标签经它洗成规范形，盘上字节再按**同一个**记录编码，索引与文件不会两套
        Record record = new Record(target, stale != null && stale.pinned,
                System.currentTimeMillis(), NotesCodec.deriveTitle(body), charset, newline, bound);
        if (NotesCodec.charsetFor(record.charset) == null) {
            Log.w(TAG, "charset unavailable, bytes written as UTF-8: " + record.charset);
        }
        byte[] data = NotesCodec.encodeBytes(body, record.charset, record.newline);
        if (record.external()) {
            // 外部那份**是**这篇的正文：写不成就是这次保存失败，索引与镜像都不许先走一步
            if (!writeExternal(record.uri, data)) {
                return null;
            }
            if (!writeBody(target, data)) {
                Log.w(TAG, "mirror unavailable for external doc: " + target); // 镜像少一份，不判这次保存失败
            }
        } else if (!writeBody(target, data)) {
            return null;
        }
        next.add(record);
        persist(NotesCodec.encode(NotesCodec.sorted(next)));
        return target;
    }

    /**
     * 「打开文件」那一路第二次存同一份外部文件时，找回它**已经有的那一行**（{@code id==null} 才会走到这儿）。
     * 不找就会每开一次多一行：几行都指着同一个文件，列表里同篇重复，删一篇还剩另一篇（E8 的账）。
     * 只管这一路，**不**替「另存为」合并——两篇内部文档另存到同一个文件是两回事，那第二篇的行是真身份，吞掉就是丢数据。
     */
    private String idOfBoundRow(String uri) {
        if (uri == null || uri.isEmpty()) {
            return null;
        }
        for (Record r : NotesCodec.decode(prefs.getString(K_INDEX, ""))) {
            if (uri.equals(r.uri)) {
                return r.id;
            }
        }
        return null;
    }

    /** 应用内那份正文：临时文件 → {@code sync()} → 原子替换（先文件后索引，见类注释的写序）。 */
    private boolean writeBody(String id, byte[] data) {
        if (!dir.isDirectory() && !dir.mkdirs()) {
            Log.w(TAG, "notes dir unavailable: " + dir.getAbsolutePath());
            return false;
        }
        File tmp = new File(dir, "." + id + ".tmp");
        File out = new File(dir, id + SUFFIX);
        try (FileOutputStream stream = new FileOutputStream(tmp)) {
            stream.write(data);
            stream.flush();
            stream.getFD().sync(); // 先确保正文真到了盘上，再动索引
        } catch (IOException e) {
            Log.w(TAG, "body write failed: " + id, e);
            return false;
        }
        if (!tmp.renameTo(out)) {
            Log.w(TAG, "body rename failed: " + id);
            tmp.delete();
            return false;
        }
        return true;
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

    /**
     * 外部文件的字节（整篇读，截到 {@code byteLimit}）。读不到一律回 {@code null} 并留一行原因——
     * provider 挂了、权限被撤、URI 过期都是这一格，调用方按"读不到"说话，绝不回空串冒充空文档。
     */
    private byte[] readExternal(String uri, int byteLimit) {
        try (InputStream stream = resolver.openInputStream(Uri.parse(uri))) {
            if (stream == null) {
                Log.w(TAG, "external open returned null: " + uri);
                return null;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(1024, Math.min(byteLimit, 1 << 20)));
            byte[] chunk = new byte[8192];
            int total = 0;
            int n;
            while ((n = stream.read(chunk)) > 0) {
                int room = byteLimit - total;
                if (room <= 0) {
                    break; // 截到了上限（搜索那一路用得到，正文不该被 provider 的脾气撑爆内存）
                }
                out.write(chunk, 0, Math.min(n, room));
                total += n;
            }
            return out.toByteArray();
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "external read failed: " + uri, e);
            return null;
        }
    }

    private byte[] readExternal(String uri) {
        return readExternal(uri, Integer.MAX_VALUE);
    }

    /**
     * 写外部文件：{@code "wt"}＝截断重写。留着 {@code "w"} 不截是另一个口径（追加或原地改长度），
     * 而"另存为"要的是那个文件从此就是这一版正文。
     */
    private boolean writeExternal(String uri, byte[] data) {
        try (OutputStream stream = resolver.openOutputStream(Uri.parse(uri), "wt")) {
            if (stream == null) {
                Log.w(TAG, "external open returned null (write): " + uri);
                return false;
            }
            stream.write(data);
            stream.flush();
            return true;
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "external write failed: " + uri, e);
            return false;
        }
    }

    /**
     * 按这一条记录读正文：绑外部的以那个 URI 为准（读不到回 {@code null}），没绑的读应用内那份
     * （不在＝空串）。外部那份还现算一次换行档（人裁「盘上第一个换行是什么档就认什么档」），
     * 检测出与索引不同就换一条内存里的记录带回，**不反写索引**——读操作不留写副作用，
     * 视图侧把它当"标签与盘上不一致"，下一次自然落库顺手记正。
     */
    private Doc readDoc(Record record) {
        if (record.external()) {
            byte[] raw = readExternal(record.uri);
            if (raw == null) {
                return null;
            }
            String detected = NotesCodec.detectNewline(raw);
            Record current = detected.equals(record.newline)
                    ? record
                    : new Record(record.id, record.pinned, record.updatedMs, record.title,
                            record.charset, detected, record.uri);
            return new Doc(NotesCodec.decodeBytes(raw, current.charset), current,
                    displayName(current.uri));
        }
        return new Doc(readBody(record.id, record.charset, Integer.MAX_VALUE), record, null);
    }

    /** 按该篇的编码档解码成缓冲（{@code \n} 归一＋剥 BOM 都在 {@link NotesCodec#decodeBytes} 里）。 */
    private String readBody(String id, String charset, int byteLimit) {
        byte[] raw = readBytes(id, byteLimit);
        return raw == null ? "" : NotesCodec.decodeBytes(raw, charset);
    }

    /**
     * 外部文件的显示名（给人眼与读屏用，{@code content://} 那串 URI 谁也不该被迫读完它）。
     * provider 不给这一列时退回 URI 末段，再不行退回整串——名字只是提示，读不出来也不该把功能带走。
     */
    public String displayName(String uri) {
        if (uri == null || uri.isEmpty()) {
            return "";
        }
        try (Cursor cursor = resolver.query(Uri.parse(uri),
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                String name = cursor.getString(0);
                if (name != null && !name.isEmpty()) {
                    return name;
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "display name unavailable: " + uri, e);
        }
        int slash = uri.lastIndexOf('/');
        return slash >= 0 && slash + 1 < uri.length() ? uri.substring(slash + 1) : uri;
    }

    /** 索引里这一条；没记上（新建、或自愈前的孤儿）就现造一条默认档，读侧不需要分支。 */
    private Record recordOf(String id) {
        for (Record r : NotesCodec.decode(prefs.getString(K_INDEX, ""))) {
            if (r.id.equals(id)) {
                return r;
            }
        }
        return new Record(id, false, 0L, "", null, null, null);
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
                    NotesCodec.deriveTitle(readBody(id, NotesCodec.DEFAULT_CHARSET, 4096)), null, null, null));
        }
        // 自愈只补进内存、不反写索引：读操作不该有写副作用，真身下一次 save 会一并记下。
        return NotesCodec.sorted(records);
    }
}
