package com.hmdp.canal;

import com.alibaba.otter.canal.client.CanalConnector;
import com.alibaba.otter.canal.client.CanalConnectors;
import com.alibaba.otter.canal.protocol.Message;
import com.hmdp.config.CanalProperties;
import com.hmdp.utils.CacheClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Canal 缓存同步监听器（标准版缓存一致性）。
 * <p>
 * 订阅 MySQL binlog（tb_shop / tb_voucher），变更后统一失效多级缓存
 * （复用 {@link CacheClient#evictMultiLevel}：本地缓存 + Redis + pub/sub 广播）。
 * 相比轻量版「事务后失效+延迟双删」，本方案解耦所有写入口，一个 binlog 入口兜底；
 * 需部署 Canal Server 并开启 MySQL binlog（见 docs/canal-deploy.md）。
 * </p>
 * <p>由 {@code canal.enabled=true} 开启，默认关闭；连接失败自动重试，批处理异常回滚重投。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "canal.enabled", havingValue = "true")
public class CanalCacheSyncListener {

    /** binlog 订阅过滤：仅关注 hmdp 库的店铺与优惠券表 */
    private static final String SUBSCRIBE_FILTER = "hmdp\\.tb_shop,hmdp\\.tb_voucher";

    private final CanalProperties properties;
    private final CacheClient cacheClient;

    private volatile boolean running;
    private Thread workerThread;

    public CanalCacheSyncListener(CanalProperties properties, CacheClient cacheClient) {
        this.properties = properties;
        this.cacheClient = cacheClient;
    }

    @PostConstruct
    public void start() {
        running = true;
        workerThread = new Thread(this::run, "canal-cache-sync");
        workerThread.setDaemon(true);
        workerThread.start();
        log.info("Canal 缓存同步监听器已启动: {}:{}/{}", properties.getHost(), properties.getPort(), properties.getDestination());
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (workerThread != null) {
            workerThread.interrupt();
        }
    }

    private void run() {
        while (running && !Thread.currentThread().isInterrupted()) {
            CanalConnector connector = null;
            try {
                connector = CanalConnectors.newSingleConnector(
                        new InetSocketAddress(properties.getHost(), properties.getPort()),
                        properties.getDestination(), "", "");
                connector.connect();
                connector.subscribe(SUBSCRIBE_FILTER);
                log.info("Canal 已连接并订阅: {}", SUBSCRIBE_FILTER);

                while (running) {
                    Message message = connector.getWithoutAck(properties.getBatchSize(), 100L, TimeUnit.MILLISECONDS);
                    long batchId = message.getId();
                    if (batchId == -1L || message.getEntries().isEmpty()) {
                        continue;
                    }
                    try {
                        List<CanalEntryParser.CacheKey> cacheKeys = CanalEntryParser.parse(message.getEntries());
                        for (CanalEntryParser.CacheKey k : cacheKeys) {
                            cacheClient.evictMultiLevel(k.getKeyPrefix(), k.getId());
                        }
                        if (!cacheKeys.isEmpty()) {
                            log.info("binlog 变更失效缓存 {} 个 key（batchId={}）", cacheKeys.size(), batchId);
                        }
                        connector.ack(batchId);
                    } catch (Exception e) {
                        log.error("处理 Canal 批次失败，回滚重投: batchId={}", batchId, e);
                        connector.rollback(batchId);
                    }
                }
            } catch (Exception e) {
                log.warn("Canal 连接异常，{}ms 后重试: {}", properties.getConnectRetryIntervalMillis(), e.toString());
                try {
                    Thread.sleep(properties.getConnectRetryIntervalMillis());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            } finally {
                if (connector != null) {
                    try {
                        connector.disconnect();
                    } catch (Exception ignored) {
                        // 断开失败无需处理
                    }
                }
            }
        }
    }
}