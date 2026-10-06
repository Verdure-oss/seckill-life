# Canal（binlog 订阅）部署与缓存一致性接入指南

标准版缓存一致性方案：通过 Canal 监听 MySQL binlog，把「任何入口的 DB 变更」统一转化为缓存失效，作为下方案例中「事务后失效 + 延迟双删」轻量版之外的兜底入口（两者可共存，失效幂等）。

链路：`MySQL binlog(ROW) → Canal Server → 本应用 canal.client 订阅 → CacheClient.evictMultiLevel（本地缓存 + Redis + pub/sub 广播）`

## 1. 开启 MySQL binlog（必需）

编辑 MySQL 配置 `my.cnf`（Linux）或 `my.ini`（Windows）：

```ini
[mysqld]
server-id=1
log-bin=mysql-bin
binlog_format=ROW          # Canal 必须 ROW 模式
binlog-do-db=hmdp          # 只记录本项目库，减小 binlog 体积
```

重启 MySQL 后确认：

```sql
SHOW VARIABLES LIKE 'log_bin';        -- ON
SHOW VARIABLES LIKE 'binlog_format';  -- ROW
```

> Linux 容器版 MySQL 示例：`docker run -d --name mysql -p 3306:3306 -e MYSQL_ROOT_PASSWORD=xxx mysql:5.7 --server-id=1 --log-bin=mysql-bin --binlog-format=ROW`

## 2. 部署 Canal Server（v1.1.7，与客户端版本一致）

```bash
docker run -d --name canal-server -p 11111:11111 \
  -e canal.instance.master.address=127.0.0.1:3306 \
  -e canal.instance.dbUsername=root \
  -e canal.instance.dbPassword=你的数据库密码 \
  -e canal.instance.connectionCharset=UTF-8 \
  -e canal.instance.filter.regex=hmdp\\.tb_shop,hmdp\\.tb_voucher \
  canal/canal-server:v1.1.7
```

- `canal.instance.master.address` 指向 MySQL 地址端口；
- `canal.instance.filter.regex` 与代码内 `CanalCacheSyncListener.SUBSCRIBE_FILTER` 保持一致（只订阅店铺/优惠券表）；
- 官方镜像默认 destination 为 `example`，与 `application.yaml` 中 `canal.destination` 默认值一致。

非 Docker 部署见 [canal 官方文档](https://github.com/alibaba/canal)（下载 `canal.deployer-1.1.7.tar.gz`，配置 `conf/example/instance.properties` 后启动 `bin/startup.sh`）。

## 3. 应用侧开启

`application.yaml`（或 local 配置）中：

```yaml
canal:
  enabled: true
  host: 127.0.0.1
  port: 11111
  destination: example
  batch-size: 100
```

启动后日志应出现：
```
Canal 缓存同步监听器已启动: 127.0.0.1:11111/example
Canal 已连接并订阅: hmdp\.tb_shop,hmdp\.tb_voucher
```

## 4. 验证

1. 调 `GET /shop/{id}` 触发缓存写入；
2. 直接改数据库（任意入口，绕过应用）：`UPDATE tb_shop SET name='新名' WHERE id=1;`
3. 应用日志出现：`binlog 变更失效缓存 1 个 key（batchId=...）`；
4. 再调 `GET /shop/{id}` 返回新值（缓存已被 Canal 失效重建）。

## 注意事项

- **开启条件**：Canal 只能捕获开启 binlog 之后且订阅开始之后的变更；存量脏数据需一次性清理或等 TTL 过期自愈。
- **故障语义**：连接失败自动每 5s 重试；批次处理失败 `rollback` 重投，不丢事件；Canal 长时间不可用期间的应用内主动失效仍生效（双通道互为兜底）。
- **表扩展**：新表需同时维护 `CanalEntryParser.TABLE_KEY_PREFIX`（表名→key 前缀）与 subscribe 过滤正则；无 id 单值缓存（如商铺分类）暂未纳入，靠 TTL 自愈。
- **性能**：canal.client 1.1.7 已排除 rocketmq/zookeeper 等无用传递依赖；仅订阅两张表，binlog 消费量极低。