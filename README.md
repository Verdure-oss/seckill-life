# hm-dianping

黑马点评实战项目（高并发本地生活服务平台）。基于 Spring Boot 2.7 + Java 17，聚焦高 QPS 场景下的缓存、秒杀与最终一致性设计。

## 核心能力

- **秒杀链路**：`seckill.lua` 原子校验（时间窗口 + 库存 + 一人一单）→ RabbitMQ 异步落库 → 唯一索引幂等兜底 → 消费重试/死信 → TTL 延迟队列实现 10 分钟超时关单与库存回补。
- **多级缓存**：Caffeine → Redis → DB，Redisson 布隆过滤器防缓存穿透（启动预热 + 新增实时登记），Redis pub/sub 多实例失效广播，事务提交后失效 + 延迟双删保证缓存一致性。
- **业务模块**：登录（验证码/Token + 双拦截器续期）、店铺查询（GEO 附近店铺、分类缓存）、探店笔记（ZSet 点赞 + Redis Pipeline 批量查询 + 关注推送 Feed 流）、用户关注（Set 交集）、签到（BitMap 连续天数）。
- **AI 助手**（默认关闭）：LangChain4j AiServices 接线，店铺查询 / 预约到店工具，会话记忆按用户持久化到 Redis。

## 技术栈

Spring Boot 2.7.18 · Java 17 · MyBatis-Plus 3.4.3 · MySQL 5.x · Redis(Lettuce) + Redisson · RabbitMQ · Caffeine · LangChain4j 1.0 · Hutool

## 本地运行

前置依赖：MySQL（库 `hmdp`，初始化脚本 `src/main/resources/db/hmdp.sql`）、Redis、RabbitMQ。

```bash
# 1. 复制无凭据模板并填入本地数据库/Redis 密码
cp src/main/resources/application-example.yaml src/main/resources/application-local.yaml
# 2. 运行测试（Windows）
mvnw.cmd test
# 3. 启动（默认端口 8084，激活 local profile）
mvnw.cmd spring-boot:run
```

> 可配置项：`hmdp.upload-dir`（图片上传目录）、`bloom.warmup.enabled`（布隆预热）、`cache.double-delete.*`（延迟双删）、`ai.openai.enabled`（AI 助手开关，Key 走环境变量）。

## 测试

单元测试为主（mock 隔离），共 40+ 用例覆盖秒杀脚本、缓存一致性、布隆过滤器、Pipeline、AI 装配等。CI（GitHub Actions）在 push 到 `master` 时基于 MySQL/Redis/RabbitMQ 服务容器执行 `./mvnw test`。

详细设计与约定见 [AGENTS.md](./AGENTS.md)。