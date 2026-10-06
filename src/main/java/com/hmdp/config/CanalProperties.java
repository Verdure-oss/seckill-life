package com.hmdp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Canal（binlog 订阅）配置。
 * <p>
 * 标准版缓存一致性方案：监听 MySQL binlog 变更，统一失效多级缓存。
 * 默认关闭，需先按 docs/canal-deploy.md 部署 Canal Server 并开启 MySQL binlog。
 * </p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "canal")
public class CanalProperties {

    /** 是否启用 Canal 缓存同步（默认关闭，不影响本地与 CI） */
    private boolean enabled = false;

    /** Canal Server 地址 */
    private String host = "127.0.0.1";

    /** Canal Server 端口（默认 11111） */
    private int port = 11111;

    /** Canal destination（对应 canal.properties 中的 canal.destinations，默认 example） */
    private String destination = "example";

    /** 单批拉取条数上限 */
    private int batchSize = 100;

    /** 连接失败后的重试间隔（毫秒） */
    private long connectRetryIntervalMillis = 5000L;
}