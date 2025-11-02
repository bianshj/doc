// 1) 单机开发/测试（JVM内排他）
LockProvider lp = new InMemoryLockProvider();

// 2) 生产/集群（DB排他，先执行相应DDL创建 batch_mutex 表）
LockProvider lp = new DbLockProvider(dataSource);

// 3) 获取锁（成功才返回 Optional 有值）
var lockOpt = lp.tryAcquire("dailyAggregation:2025-11-02", "node-A", null);
if (lockOpt.isEmpty()) {
    // 已有同名作业在跑，拒绝本次启动
    return;
}

try (JobLock lock = lockOpt.get()) {
    // 4) 启动Batch作业
    var op = jakarta.batch.runtime.BatchRuntime.getJobOperator();
    long execId = op.start("dailyAggregation", new Properties());

    // 5) 定期心跳（例如60秒）
    var stop = com.example.batchlock.util.Heartbeat.start(lock, 60);

    // ……等待作业结束（轮询/监听），结束时 stop.run() + try-with-resources 自动 unlock
}
