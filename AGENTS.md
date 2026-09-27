# AGENTS.md

## 项目概览

基于 Spring Boot 的高并发秒杀平台（黑马点评实战项目），核心业务为：店铺信息查询与缓存、优惠券秒杀、探店笔记（点赞/Feed流）、用户关注、附近店铺（GEO）、用户签到。

- **技术栈**：Spring Boot 2.3.12 / MyBatis-Plus 3.4.3 / Redis（Lettuce）/ Redisson 3.13.6 / Hutool 5.7 / MySQL 5.x
- **JDK**：8+（本机开发使用 JDK 21 也可编译运行）
- **Redis 承担的角色**：登录态、缓存、分布式锁、全局 ID 序列、GEO、BitMap 签到、ZSet 点赞/Feed 流、Stream 秒杀消息队列

## 环境与运行

- **前置依赖**：本地 MySQL（数据库 `hmdp`，初始化脚本见 `src/main/resources/db/hmdp.sql`）与 Redis。
- **配置体系**：
  - `application.yaml` —— 无凭据基础配置，激活 `local` profile；**随代码提交**。
  - `application-local.yaml` —— 含真实凭据（数据库/Redis 密码），已被 `.gitignore` 忽略，**禁止提交**。新环境复制 `application-example.yaml` 为 `application-local.yaml` 并填入本地密码。
  - `application-example.yaml` —— 无凭据模板，随代码提交。
- **构建与测试**：使用 Maven Wrapper，无需本机安装 Maven。
  - Windows：`mvnw.cmd test`
  - macOS/Linux：`./mvnw test`
  - 首次执行会联网下载 Maven 与依赖。

## 注意事项

- 每次改动完成后，都必须创建一个对应的 Git commit，以便后续追踪和回滚。
- 每次改动后，都必须编写或更新相关测试，并在交付给用户前，确保所有测试和验证全部通过。

## 提交规范

本项目采用 Conventional Commits 风格，以保持历史清晰、可回溯且便于自动生成变更日志。

- commit message 必须区分用途，推荐格式：`<type>: <subject>`
- 常用 type：
  - `feat:` 新功能
  - `fix:` 修复 bug
  - `docs:` 文档/注释
  - `chore:` 构建、工具、依赖、配置等杂项
  - `refactor:` 重构，不改变外部行为
  - `test:` 增补/修改测试
  - `perf:` 性能优化
  - `ci:` CI/CD 配置（GitHub Actions 等）
- subject 用英文，简介（≤50 字符），动词原形开头，不要用句号结尾。
- 一个 commit 只做一件事：不要把互不相关的改动混在一起。

## 测试策略

- 逻辑密集的组件（Redis 锁、ID 生成、校验、序列化、分支逻辑）应当优先写**单元测试**，使用 mock 隔离依赖，不依赖真实 Redis/MySQL。
- 依赖 Spring 上下文或真实外部资源的，才放进集成测试（`@SpringBootTest`）。
- 测试命名遵循 `methodName_condition_expectedBehavior` 或 `Method_criteria_behavior` 风格。
- 新增功能或修复 bug 时，必须在同一 commit 中补充或更新对应测试。

## 敏感信息处理

- 带凭据的配置（`application-local.yaml` 等）禁止提交，已加入 `.gitignore`。
- 提交前检查是否意外引入了密钥；如发现，须先清理历史再推送。

## CI（GitHub Actions）

- 定义在 `.github/workflows/ci.yml`，在 **push 到 master 分支**时自动执行；也可通过 `workflow_dispatch` 手动触发。
- CI 内置 MySQL 5.7 + Redis 6 服务容器，执行流程：
  1. 检出代码、安装 JDK 8（temurin）、Maven 依赖缓存
  2. 导入 `src/main/resources/db/hmdp.sql` 初始化 `hmdp` 库（含种子数据）
  3. 用环境变量注入 `SPRING_DATASOURCE_*` / `SPRING_REDIS_*` 连接 CI 的服务容器（CI 上无 `application-local.yaml`）
  4. 运行 `./mvnw test`
- **CI 环境**:MySQL 服务容器设密码 `ci-test-password`；Redis 服务容器**无密码**。连接凭据通过 CI 环境变量注入，不入库、无真实凭据泄露。
- 本地无 `application-local.yaml` 时，可用等价环境变量启动测试（已被验证可行）。

## 已修复问题记录

> 以下秒杀链路问题已在秒杀修复工作中解决（见提交 `SeckillScriptTest` 回归测试）。保留此记录供追溯，避免将来重新引入。

- ✅ **秒杀 Lua 脚本路径错误**：
  - `VoucherOrderServiceImpl.SECKILL_SCRIPT` 原错误加载不存在的 `lock.lua`，已改为 `seckill.lua`。
  - `SimpleRedisLock.UNLOCK_SCRIPT` 原错误加载 `lock.lua`，已改为 `unlock.lua`。
- ✅ **秒杀库存 key 不一致**：
  - `seckill.lua` 中 key 原为 `seckill:stock`/`seckill:order`（无冒号），已改为带冒号，与 Java `RedisConstants.SECKILL_STOCK_KEY` 一致。
- ✅ **秒杀消息消费者线程未启动**：
  - `VoucherOrderServiceImpl.init()` 原为 `private` 且无 `@PostConstruct`，已加上注解并补充 `ensureConsumerGroup()` 幂等创建 Stream/消费组，消费者线程（daemon）启动后才能真正消费 `stream.orders`。
- ✅ **`unlock.lua` 变量名拼写错误**：`KEYs` 已改为 `KEYS`（大写）。
- ✅ **消费者线程优雅关闭**：新增 `@PreDestroy destroy()` 关闭 executor 并中断阻塞读；消费者 `run()`/`handlePendingList()` 检查中断标志后干净退出，应用关闭时不再残留 `Connection closed` 报错。