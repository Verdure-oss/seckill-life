package com.hmdp.utils;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 单元测试：缓存一致性管理器（事务提交后失效 + 延迟双删），使用 mock 隔离，不依赖外部资源。
 */
class CacheConsistencyManagerTest {

    private CacheClient cacheClient;
    private CacheConsistencyManager manager;

    @BeforeEach
    void setUp() {
        cacheClient = mock(CacheClient.class);
        manager = new CacheConsistencyManager(cacheClient, 50L, true);
    }

    /** 无事务上下文：立即删除，并延迟二次删除（双删） */
    @Test
    void noTransaction_evictImmediatelyAndDoubleDelete() {
        manager.evictMultiLevelAfterCommit("cache:shop:", 1L);

        // 第一次删除（立即）
        verify(cacheClient, timeout(1000).times(1)).evictMultiLevel("cache:shop:", 1L);
        // 第二次删除（延迟后）
        verify(cacheClient, timeout(3000).times(2)).evictMultiLevel("cache:shop:", 1L);
    }

    /** 事务提交前不删除；afterCommit 后才执行删除 */
    @Test
    void activeTransaction_evictOnlyAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            manager.evictMultiLevelAfterCommit("cache:shop:", 2L);

            // 事务尚未提交，不应删除（即修复：删除移到事务提交后）
            verify(cacheClient, never()).evictMultiLevel(any(), any());

            // 模拟事务提交，触发 afterCommit
            for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
                s.afterCommit();
            }

            verify(cacheClient, timeout(1000).times(1)).evictMultiLevel("cache:shop:", 2L);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /** 关闭双删时只删除一次 */
    @Test
    void doubleDeleteDisabled_evictOnce() {
        CacheConsistencyManager singleManager = new CacheConsistencyManager(cacheClient, 50L, false);

        singleManager.evictMultiLevelAfterCommit("cache:shop:", 3L);

        verify(cacheClient, timeout(1000).times(1)).evictMultiLevel("cache:shop:", 3L);
        // 等待超过延迟时间后确认没有第二次删除
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        verify(cacheClient, times(1)).evictMultiLevel("cache:shop:", 3L);
    }
}