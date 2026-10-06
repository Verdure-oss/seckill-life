# AGENTS.md

## 项目概览

黑马点评实战项目（`hm-dianping`）：基于 Spring Boot 的高并发本地生活服务平台。核心业务：店铺信息查询与多级缓存、优惠券秒杀、探店笔记（点赞/Feed 流）、用户关注、附近店铺（GEO）、用户签到，并集成 LangChain4j AI 助手（店铺查询/预约工具）。

- **技术栈**：Spring Boot 2.7.18 / Java 17 / MyBatis-Plus 3.4.3 / Redis（Lettuce 6.1）/ Redisson 3.13.6 / RabbitMQ / Caffeine / Guava(依赖内) / Hutool 5.7 / LangChain4j 1.0.0 / MySQL 5.x
- **Redis 承担的角色**：登录态（验证码/Token）、多级缓存（含 Redisson 布隆过滤器防穿透）、分布式锁（Redisson）、全局 ID 序列、GEO 附近店铺、BitMap 签到、ZSet 点赞/Feed 流。
- **RabbitMQ 承担的角色**：秒杀异步落库、消费重试/死信(DLQ)、TTL 延迟队列实现超时关单。（秒杀已从 Redis Stream 迁移到 RabbitMQ）

## 核心链路设计

### 秒杀链路（库存/一人一单/最终一致性）
1. 下单入口执行 `seckill.lua` **原子校验**：时间窗口（`seckill:meta:` 毫秒，0=不限时）+ 库存扣减 + Set 一人一单；返回码 0 成功 / 1 库存不足 / 2 重复下单 / 3 未开始 / 4 已结束。
2. 校验通过后发 RabbitMQ 消息**异步落库**；`RedisIdWorker` 生成全局订单号。
3. **幂等兜底**：`tb_voucher_order` 上 `(user_id, voucher_id)` 唯一索引，消费端捕获 `DuplicateKeyException` 视为已下单。
4. **重试/死信**：`application.yaml` 配置消费重试 3 次，耗尽后经 DLX 进入 `seckill.order.dlq` 人工排查。
5. **超时关单**：下单同时发一条到 `seckill.order.close.delay`（TTL=10 分钟），过期进 `seckill.order.close`；`handleOrderTimeout` 先 CAS 将仅「未支付」订单置为「已取消」，再执行 `cancelSeckill.lua` 原子回补 Redis 库存+已购标记，`removed==1` 时才回补数据库库存（防重复关单虚增）。
6. 支付：`/voucher-order/pay/{id}` CAS 将「未支付」置为「已支付」。

### 缓存与一致性
- **多级缓存**：Caffeine 本地缓存 → Redis → DB（`CacheClient.queryWithMultiLevelCache`）；**Redisson RBloomFilter**（`BloomFilterUtils`）在查 DB 前拦截不可能存在的 key 防穿透，启动时用 `BloomFilterWarmup` 预热现有店铺/券 id，新增店铺/秒杀券时实时登记。
- **失效时机**：`CacheConsistencyManager` 在**事务提交后**（`afterCommit`）删除多级缓存，并**延迟二次删除**兜底读-改竞态（`cache.double-delete.enabled`）；多实例靠 Redis pub/sub(`cache:invalidate`) 广播失效本地缓存。
- 其他：逻辑过期/互斥锁等查询策略保留在 `ShopServiceImpl`（当前默认走多级缓存）。

### AI 助手（默认关闭）
- `ai.openai.enabled=false` 时相关 Bean 不注册（`@ConditionalOnProperty`），不影响启动与 CI。
- 启用后，`LangChain4jConfig` 通过 `AiServices` 装配 `ChatAssistant`：注册 `ShopQueryTool`(查店铺) 与 `ReservationTool`(预约)，会话记忆经 `RedisChatMemoryStore` 按用户隔离持久化。
- 注意依赖：**必须包含 `langchain4j` 主模块**（提供 `AiServices`/`MessageWindowChatMemory`），仅有 `langchain4j-core` + `langchain4j-open-ai` 时工具无法接线。

## 环境与运行

- **前置依赖**：本地 MySQL（数据库 `hmdp`，初始化脚本见 `src/main/resources/db/hmdp.sql`）、Redis、RabbitMQ（guest/guest，默认 `localhost:5672`，秒杀异步链路必需）。
- **配置体系**：
  - `application.yaml` —— 无凭据基础配置，激活 `local` profile；**随代码提交**。
  - `application-local.yaml` —— 含真实凭据（数据库/Redis 密码），已被 `.gitignore` 忽略，**禁止提交**。新环境复制 `application-example.yaml` 为 `application-local.yaml` 并填入密码。
  - `application-example.yaml` —— 无凭据模板，随代码提交。
- **可配置项**（均在 `application.yaml`）：`bloom.warmup.enabled`（布隆预热）、`cache.double-delete.enabled/delay-ms`（延迟双删）、`hmdp.upload-dir`（图片上传目录，默认 Windows nginx 路径，Linux/CI 需覆盖）、`ai.openai.*`（AI 开关/Key，Key 走环境变量）。
- **构建与测试**：使用 Maven Wrapper。Windows：`mvnw.cmd test`；macOS/Linux：`./mvnw test`。首次执行会联网下载 Maven 与依赖。

## 注意事项

- 每次改动完成后，都必须创建一个对应的 Git commit，以便后续追踪和回滚。
- 每次改动后，都必须编写或更新相关测试，并在交付给用户前，确保所有测试和验证全部通过。

## 提交规范

本项目采用 Conventional Commits 风格，以保持历史清晰、可回溯且便于自动生成变更日志。

- commit message 必须区分用途，推荐格式：`<type>: <subject>`
- 常用 type：`feat:` 新功能 / `fix:` 修复 bug / `docs:` 文档注释 / `chore:` 构建工具依赖配置等杂项 / `refactor:` 重构不改外部行为 / `test:` 增补测试 / `perf:` 性能优化 / `ci:` CI 配置。
- subject 使用中文，简明扼要（≤50 字符），不需要句末句号。
- 一个 commit 只做一件事：不要把互不相关的改动混在一起。

## Agent 交互约定

1. 本项目所有 Agent 对话、代码评审、PR 描述、commit 提交描述统一使用中文交流。
2. 代码内注释：可按需选择中文/英文，业务复杂逻辑推荐中文注释方便阅读。

## 测试策略

- 逻辑密集的组件（Redis 锁、ID 生成、校验、序列化、Lua 脚本、分支逻辑、AI 装配）优先写**单元测试**，用 mock 隔离依赖，不依赖真实 Redis/MySQL/RabbitMQ。
- 依赖 Spring 上下文或真实外部资源的，才放进集成测试（`@SpringBootTest`）；手工数据预热（如 GEO 加载）用 `@Disabled` 标注，不作为自动化测试执行。
- 测试命名遵循 `methodName_condition_expectedBehavior` 风格。
- 新增功能或修复 bug 时，必须在同一 commit 中补充或更新对应测试。

## 敏感信息处理

- 带凭据的配置（`application-local.yaml` 等）禁止提交，已加入 `.gitignore`。
- 提交前检查是否意外引入了密钥；如发现，须先清理历史再推送。

## CI（GitHub Actions）

- 定义在 `.github/workflows/ci.yml`，在 **push 到 master 分支**时自动执行；也可通过 `workflow_dispatch` 手动触发。
- CI 内置 **MySQL 5.7 + Redis 6 + RabbitMQ(3-management)** 三个服务容器，执行流程：
  1. 检出代码、安装 JDK 17（temurin）、Maven 依赖缓存
  2. 导入 `src/main/resources/db/hmdp.sql` 初始化 `hmdp` 库（含种子数据）
  3. 用环境变量注入 `SPRING_DATASOURCE_*` / `SPRING_REDIS_*` 连接 CI 服务容器（CI 上无 `application-local.yaml`）
  4. 运行 `./mvnw test`
- **CI 环境**：MySQL 服务容器设密码 `ci-test-password`；Redis 服务容器**无密码**；RabbitMQ 用默认 guest/guest。连接凭据通过 CI 环境变量注入，不入库、无真实凭据泄露。
- 本地无 `application-local.yaml` 时，可用等价环境变量启动测试（已被验证可行）。

## 防回归提醒

> 秒杀链路历史修过多轮问题，均已由回归测试覆盖；改动相关代码务必保持通过，避免重新引入：
> - `SeckillScriptTest`：Lua 引用路径、库存 key 前缀、`KEYS` 大小写、Redis Stream 已移除、**时间窗校验**、RabbitMQ 消费者注解。
> - `SeckillOrderConsistencyTest`：DB 唯一索引兜底、RabbitMQ 死信/延迟关单、`cancelSeckill.lua` 回补。
> - `CacheClientTest`/`MultiLevelCacheTest`/`CacheConsistencyManagerTest`：逻辑过期、多级缓存、布隆拦截、提交后失效+延迟双删。
> - `AIAssistantWiringTest`：AiServices 装配后工具真实执行、会话记忆接线。