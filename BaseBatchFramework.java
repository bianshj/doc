// BaseBatchFramework.java
// Minimal framework per your sketch:
// - interface CheckpointInfo extends Serializable
// - CheckpointInfoService<T> with load/save
// - BaseBatchlet<T> that owns the load→loop(doChunk)→save flow
// - InMemory & File checkpoint stores
// - Example SampleBatchlet with CounterCheckpoint
//
// Drop into your Jakarta Batch project and adjust package/imports as needed.

import jakarta.batch.api.AbstractBatchlet;
import jakarta.batch.runtime.context.JobContext;
import jakarta.batch.runtime.context.StepContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

// ============== 1) CheckpointInfo ==============
interface CheckpointInfo extends Serializable {
    default int version() { return 1; }
}

// A simple concrete checkpoint: only tracks "processed" count
class CounterCheckpoint implements CheckpointInfo {
    long processed;
    int chunkSize = 100;
    public void advanceBy(long n) { processed += n; }
    public String toString() { return "CounterCheckpoint{processed=" + processed + ", chunkSize=" + chunkSize + "}"; }
}

// ============== 2) CheckpointInfoService ==============
interface CheckpointInfoService<T extends CheckpointInfo> {
    T loadCheckpointInfo();
    void save(T pointInfo);
}

// In-memory store (demo/testing)
class InMemoryCheckpointInfoService<T extends CheckpointInfo> implements CheckpointInfoService<T> {
    private volatile T current;
    private final Supplier<T> factory;
    public InMemoryCheckpointInfoService(Supplier<T> factory) { this.factory = Objects.requireNonNull(factory); }
    @Override public synchronized T loadCheckpointInfo() { if (current == null) current = factory.get(); return current; }
    @Override public synchronized void save(T pointInfo) { current = pointInfo; }
}

// File-based store using Java serialization (no external libs)
class FileCheckpointInfoService<T extends CheckpointInfo> implements CheckpointInfoService<T> {
    private final Path file;
    private final Supplier<T> factory;
    public FileCheckpointInfoService(Path file, Supplier<T> factory) { this.file = file; this.factory = factory; }
    @Override public synchronized T loadCheckpointInfo() {
        if (Files.exists(file)) {
            try (ObjectInputStream ois = new ObjectInputStream(Files.newInputStream(file))) {
                @SuppressWarnings("unchecked")
                T obj = (T) ois.readObject();
                return obj;
            } catch (Exception e) {
                throw new RuntimeException("Failed to load checkpoint from " + file, e);
            }
        }
        return factory.get();
    }
    @Override public synchronized void save(T pointInfo) {
        try {
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            try (ObjectOutputStream oos = new ObjectOutputStream(Files.newOutputStream(file))) {
                oos.writeObject(pointInfo);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to save checkpoint to " + file, e);
        }
    }
}

// ============== 3) Base Batchlet ==============
abstract class BaseBatchlet<T extends CheckpointInfo> extends AbstractBatchlet {

    @Inject protected JobContext jobCtx;
    @Inject protected StepContext stepCtx;

    @Inject protected CheckpointInfoService<T> checkpointInfoService;

    private final AtomicBoolean stopFlag = new AtomicBoolean(false);

    /** 子类实现：单次 chunk 的业务逻辑；返回 true 表示已完成。 */
    protected abstract boolean doChunk(T checkpoint) throws Exception;

    /** 钩子：开始/结束一个“用户事务”（可空实现/由子类覆写接入JTA等） */
    protected void userBeginTransaction() throws Exception { /* no-op by default */ }
    protected void userEndTransaction()   throws Exception { /* no-op by default */ }
    protected void userRollbackTransaction(Throwable t) { /* optional rollback hook */ }

    /** 每个循环之后是否立即保存 checkpoint（默认 true）。子类可覆盖改为节流策略 */
    protected boolean shouldSaveAfterEachChunk() { return true; }

    @Override
    public String process() throws Exception {
        // 1) 读取检查点
        T checkpoint = checkpointInfoService.loadCheckpointInfo();

        try {
            // 2) 主循环
            while (!stopFlag.get()) {
                userBeginTransaction();
                boolean finished = false;
                try {
                    finished = doChunk(checkpoint);
                    userEndTransaction();
                } catch (Throwable t) {
                    // 业务异常：回滚、保留 checkpoint 供重启
                    try { userRollbackTransaction(t); } catch (Throwable ignored) {}
                    if (stepCtx != null) stepCtx.setExitStatus("FAILED:" + t.getClass().getSimpleName());
                    throw t;
                } finally {
                    if (shouldSaveAfterEachChunk()) {
                        checkpointInfoService.save(checkpoint); // 3) 保存检查点
                    }
                }
                if (finished) break;
            }
            if (stepCtx != null) stepCtx.setExitStatus("COMPLETED");
            return "COMPLETED";
        } finally {
            // 可在此追加“完成时清理/保留”的策略
        }
    }

    @Override
    public void stop() { stopFlag.set(true); }
}

// ============== 4) Example concrete Batchlet ==============
@Named("SampleBatchlet")
class SampleBatchlet extends BaseBatchlet<CounterCheckpoint> {

    // 你可以通过 DI 容器替换为 FileCheckpointInfoService：
    // @Inject
    // public void setCheckpointInfoService(CheckpointInfoService<CounterCheckpoint> svc) { this.checkpointInfoService = svc; }
    // 这里直接在构造器里做一个最小演示（不使用容器）：
    public SampleBatchlet() {
        // 默认使用文件存储到 ./checkpoints/sample.chk
        this.checkpointInfoService = new FileCheckpointInfoService<>(
            Path.of("checkpoints", "sample.chk"),
            CounterCheckpoint::new
        );
    }

    private static final long TOTAL = 1000; // 总任务量（演示用）

    @Override
    protected boolean doChunk(CounterCheckpoint cp) throws Exception {
        // 模拟处理：一次 chunk 处理 cp.chunkSize 条
        long remaining = TOTAL - cp.processed;
        int thisChunk = (int)Math.min(cp.chunkSize, Math.max(remaining, 0));
        // === 业务处理区域 ===
        // TODO: 你的具体逻辑（DB读写、API调用、文件处理等）
        // ====================
        cp.advanceBy(thisChunk);
        System.out.println("Processed: " + cp.processed + "/" + TOTAL + "  (chunk=" + thisChunk + ")");
        return cp.processed >= TOTAL;
    }

    @Override protected void userBeginTransaction() { /* TODO hook JTA begin if needed */ }
    @Override protected void userEndTransaction()   { /* TODO hook JTA commit if needed */ }
    @Override protected void userRollbackTransaction(Throwable t) { /* TODO hook JTA rollback if needed */ }

    // 如果不想每次 chunk 都写磁盘，可降低频率：例如每 5 次 chunk 保存一次（这里示例简单处理）
    private int chunkSinceLastSave = 0;
    @Override protected boolean shouldSaveAfterEachChunk() {
        chunkSinceLastSave++;
        if (chunkSinceLastSave >= 5) { chunkSinceLastSave = 0; return true; }
        return false;
    }
}


// 1) checkpoint 内容：由子类决定字段
class IdCursorCheckpoint implements CheckpointInfo {
    long lastId = 0;
    int committed = 0; // 本轮已处理条数（用于保存频率）
}

// 2) 基类：统一 load / save
abstract class BaseBatchlet<T extends CheckpointInfo> extends AbstractBatchlet {
    @Inject CheckpointInfoService<T> store;

    protected abstract boolean doChunk(T cp) throws Exception;
    protected int checkpointEveryItems() { return 1000; } // 策略：每1000条保存

    private long pending = 0;
    protected final void progress(T cp, long delta) {
        pending += delta;
        if (pending >= checkpointEveryItems()) {
            store.save(cp);
            pending = 0;
        }
    }

    @Override public String process() throws Exception {
        T cp = store.loadCheckpointInfo();
        while (true) {
            boolean finished = doChunk(cp);   // 子类更新 cp 的具体内容
            store.save(cp);                   // 本轮末尾也保存一次（兜底）
            if (finished) break;
        }
        return "COMPLETED";
    }
}

// 3) 子类：实现具体内容的“如何更新”
@Named
class ImportUserBatchlet extends BaseBatchlet<IdCursorCheckpoint> {

    @Inject CheckpointInfoService<IdCursorCheckpoint> injectedStore;

    @PostConstruct void init() { super.store = injectedStore; }

    @Override
    protected boolean doChunk(IdCursorCheckpoint cp) throws Exception {
        // 取 > cp.lastId 的下一批数据
        List<UserRow> rows = dao.fetchAfterId(cp.lastId, 500);
        if (rows.isEmpty()) return true;

        // 处理
        for (UserRow r : rows) {
            service.upsert(r);
            cp.lastId = r.id;   // <-- 子类负责更新“具体内容”
            cp.committed++;
        }

        // 报告进度，让基类按策略决定是否 save()
        progress(cp, rows.size());
        return false;
    }
}
