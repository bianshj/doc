import jakarta.batch.api.Batchlet;
import jakarta.batch.runtime.context.StepContext;
import jakarta.inject.Inject;
import java.io.Serializable;

// ただのマーカー：状態は Serializable なら何でもOK
public interface State extends Serializable {}

public abstract class SimpleStatefulBatchlet<S extends State> implements Batchlet {

    @Inject protected StepContext step;   // 毎回新インスタンスで来る（仕様）

    protected abstract S newState();      // 初回用の状態生成
    protected abstract String execute(S s) throws Exception; // 本処理（"COMPLETED" 等を返す）

    protected void onRestart(S s) throws Exception {} // 再開時に必要なら上書き

    @SuppressWarnings("unchecked")
    private S loadOrCreate() {
        Object persisted = step.getPersistentUserData();
        return (persisted != null) ? (S) persisted : newState();
    }

    private void save(S s) { step.setPersistentUserData(s); } // 終了時に保存される

    @Override
    public final String process() throws Exception {
        S state = loadOrCreate();
        if (step.getPersistentUserData() != null) onRestart(state); // 再開なら呼ぶ
        String exit;
        try {
            exit = execute(state);
            return exit; // "COMPLETED"/"STOPPED"/"FAILED" 等
        } finally {
            save(state); // Batchletは「ステップ終了時」に永続化される
        }
    }

    @Override public void stop() throws Exception { /* 必要なら割り込み処理 */ }
}

import java.util.concurrent.atomic.AtomicLong;

class IdState implements State {
    private static final long serialVersionUID = 1L;
    long lastId;                  // どこまでやったか
}

public class IdImportBatchlet extends SimpleStatefulBatchlet<IdState> {

    @Override protected IdState newState() { return new IdState(); }

    @Override protected void onRestart(IdState s) {
        System.out.println("Restart -> resume from id=" + s.lastId);
    }

    @Override protected String execute(IdState s) throws Exception {
        long maxId = 1_000; // デモ用：実際はDB等から上限取得
        for (long id = s.lastId + 1; id <= maxId; id++) {
            // 1件処理
            doWork(id);

            // 進捗を「状態」に反映（※永続化はステップ終了時）
            s.lastId = id;
        }
        return "COMPLETED";
    }

    private void doWork(long id) {
        // 実処理を書く（例：レコード移行、API呼び出し等）
    }
}


package example.batch;

import jakarta.batch.api.Batchlet;
import jakarta.batch.runtime.BatchStatus;
import jakarta.batch.runtime.context.JobContext;
import jakarta.batch.runtime.context.StepContext;
import jakarta.inject.Inject;

import java.io.Serializable;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/** 共通の実行状態（必要に応じて拡張） */
public class BaseState implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 再開用に最低限あると便利な共通項目 */
    private int attempt;                // 再実行回数
    private String statusNote;          // 任意のメモ
    private Instant lastUpdated;        // 最終更新時刻（目安）
    private Map<String, String> props = new HashMap<>(); // サブクラスが雑に入れたいとき用

    public int getAttempt() { return attempt; }
    public void setAttempt(int attempt) { this.attempt = attempt; }

    public String getStatusNote() { return statusNote; }
    public void setStatusNote(String statusNote) { this.statusNote = statusNote; }

    public Instant getLastUpdated() { return lastUpdated; }
    public void setLastUpdated(Instant lastUpdated) { this.lastUpdated = lastUpdated; }

    public Map<String, String> getProps() { return props; }
}

/**
 * 状態のロード/保存/再開検知などを共通化した基底Batchlet。
 * - 初回: persistentUserData が無ければ newInitialState() を呼ぶ
 * - 再開: persistentUserData があれば onRestart(state) を呼ぶ
 * - 実行: run(state) を呼ぶ（サブクラスが本体処理）
 * - 終了: 結果に関わらず state を setPersistentUserData(state) で保存（Batchletは終了時に永続化）
 */
public abstract class BaseStatefulBatchlet<S extends BaseState> implements Batchlet {

    @Inject protected StepContext stepCtx;
    @Inject protected JobContext jobCtx;

    private volatile boolean stopping = false;

    /** サブクラスが初期状態を作る */
    protected abstract S newInitialState();

    /**
     * サブクラスの本処理。
     * 返り値は "COMPLETED" / "FAILED" / "STOPPED" 等の ExitStatus を想定。
     * stopping フラグを見て中断可能にしておくと運用が楽です。
     */
    protected abstract String run(S state) throws Exception;

    /** 再開時に必要な補正があればここで（デフォルトNO-OP） */
    protected void onRestart(S state) throws Exception { /* no-op */ }

    /** 進捗を書き換えたら呼ぶ：最終更新時刻を入れておくとデバッグに便利 */
    protected final void touch(S state) {
        state.setLastUpdated(Instant.now());
    }

    /** 外部へログを出すためのヘルパ（必要なら置き換え） */
    protected void log(String msg) {
        System.out.printf("[%s/%s] %s%n",
                jobCtx != null ? jobCtx.getJobName() : "-",
                stepCtx != null ? stepCtx.getStepName() : "-",
                msg);
    }

    /** 再開かどうかの簡易判定 */
    protected final boolean isRestart() {
        return stepCtx != null
                && stepCtx.getBatchStatus() != null
                && (stepCtx.getBatchStatus() == BatchStatus.STARTED    // 実行中
                    || stepCtx.getBatchStatus() == BatchStatus.STARTING // 実行開始
                );
        // 実際の「再開か」は persistentUserData の有無で見る方が確実
    }

    @SuppressWarnings("unchecked")
    protected final S loadStateOrCreate() {
        Object persisted = stepCtx.getPersistentUserData();
        if (persisted != null) {
            return (S) persisted;
        }
        S fresh = newInitialState();
        touch(fresh);
        return fresh;
    }

    protected final void saveState(S state) {
        touch(state);
        stepCtx.setPersistentUserData(state);
    }

    @Override
    public final String process() throws Exception {
        S state = loadStateOrCreate();
        boolean restarted = (stepCtx.getPersistentUserData() != null);

        if (restarted) {
            state.setAttempt(state.getAttempt() + 1);
            onRestart(state);
        }

        String exit = "FAILED";
        try {
            exit = run(state);
            return exit;
        } catch (InterruptedException ie) {
            // スレッド割り込みは STOP として扱う運用例
            log("Interrupted -> STOP");
            exit = "STOPPED";
            return exit;
        } catch (Exception e) {
            // ここで必要なら state にエラー概要を書いておく
            state.setStatusNote("error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            throw e; // フレームワークに失敗を伝える
        } finally {
            // Batchlet は終了時にしか永続化されないので、ここで必ず保存する
            saveState(state);
            log("state saved. attempt=" + state.getAttempt() + ", exit=" + exit);
        }
    }

    @Override
    public void stop() throws Exception {
        stopping = true;
    }

    /** サブクラスが中断可にするためのユーティリティ */
    protected final boolean isStopping() {
        return stopping;
    }
}

package example.batch;

import java.time.Duration;

/** サブクラス固有の状態 */
class ImportState extends BaseState {
    private static final long serialVersionUID = 1L;

    private long lastProcessedId;   // 直近まで処理済みのID
    private long processedCount;    // 今回の累計処理数

    public long getLastProcessedId() { return lastProcessedId; }
    public void setLastProcessedId(long v) { lastProcessedId = v; }

    public long getProcessedCount() { return processedCount; }
    public void setProcessedCount(long v) { processedCount = v; }
}

/** 実処理 */
public class ImportBatchlet extends BaseStatefulBatchlet<ImportState> {

    @Override
    protected ImportState newInitialState() {
        ImportState s = new ImportState();
        s.setStatusNote("initial");
        return s;
    }

    @Override
    protected void onRestart(ImportState state) {
        log("Restart detected. resume from id=" + state.getLastProcessedId());
    }

    @Override
    protected String run(ImportState state) throws Exception {
        long startIdExclusive = state.getLastProcessedId();
        long endIdInclusive   = queryMaxUnprocessedId(); // 例：DBから上限を調べる

        for (long id = startIdExclusive + 1; id <= endIdInclusive; id++) {
            if (isStopping()) {
                state.setStatusNote("stopped at id=" + (id - 1));
                return "STOPPED";
            }

            // ここでビジネス処理
            processOne(id);

            // in-memory の状態を更新（※永続化はステップ終了時）
            state.setLastProcessedId(id);
            state.setProcessedCount(state.getProcessedCount() + 1);

            // 長時間ループ時は少し休む等の制御も（例）
            if (id % 10_000 == 0) {
                log("progress: id=" + id);
                Thread.sleep(Duration.ofMillis(10).toMillis());
            }
        }

        state.setStatusNote("completed up to " + endIdInclusive);
        return "COMPLETED";
    }

    private void processOne(long id) {
        // ここに1件処理
    }

    private long queryMaxUnprocessedId() {
        // ここでDBなどから未処理の上限を取得する想定。デモのため固定値
        return 200_000L;
    }
}

