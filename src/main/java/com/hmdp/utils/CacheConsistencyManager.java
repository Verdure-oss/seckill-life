package com.hmdp.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 缓存一致性管理器：解决 Cache-Aside 模式下「更新 DB 后主动失效缓存」的竞态问题。
 * <p>
 * 1. 事务提交后删除缓存（afterCommit）：避免在事务提交前删缓存，导致读线程把旧值写回；
 * 2. 延迟二次删除（双删）：兜底「删除后、读线程回写旧值」的经典竞态窗口。
 * 相比 Canal/binlog 订阅，本方案零外部依赖、实现轻量，适合单体项目；
 * 多实例场景下 Redis pub/sub 广播（evictMultiLevel 内置）负责同步失效各实例本地缓存。
 * </p>
 */
@Slf4j
@Component
public class CacheConsistencyManager {

    private final CacheClient cacheClient;
    private final long doubleDeleteDelayMs;
    private final boolean doubleDeleteEnabled;

    private final ScheduledExecutorService delayedEvictExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "cache-consistency-delayed-evict");
                t.setDaemon(true);
                return t;
            });

    public CacheConsistencyManager(
            CacheClient cacheClient,
            @Value("${cache.double-delete.delay-ms:500}") long doubleDeleteDelayMs,
            @Value("${cache.double-delete.enabled:true}") boolean doubleDeleteEnabled) {
        this.cacheClient = cacheClient;
        this.doubleDeleteDelayMs = doubleDeleteDelayMs;
        this.doubleDeleteEnabled = doubleDeleteEnabled;
    }

    /**
     * 在事务提交后清除多级缓存；若启用双删，则延迟一段时间后再删一次。
     * 无事务上下文时立即执行，保证非事务调用方也能正常失效缓存。
     *
     * @param keyPrefix 缓存键前缀
     * @param id        业务主键
     */
    public void evictMultiLevelAfterCommit(String keyPrefix, Object id) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            // 事务提交成功后才失效缓存；事务回滚时 DB 未变更，无需失效
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    doEvict(keyPrefix, id);
                }
            });
        } else {
            doEvict(keyPrefix, id);
        }
    }

    private void doEvict(String keyPrefix, Object id) {
        cacheClient.evictMultiLevel(keyPrefix, id);
        if (doubleDeleteEnabled) {
            // 延迟二次删除，兜住读线程在首次删除后回写旧值的窗口
            delayedEvictExecutor.schedule(
                    () -> cacheClient.evictMultiLevel(keyPrefix, id),
                    doubleDeleteDelayMs,
                    TimeUnit.MILLISECONDS);
        }
    }
}