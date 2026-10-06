# hm-dianping · 黑马点评

> 高并发本地生活服务平台（黑马点评实战项目增强版），基于 **Spring Boot 2.7 / Java 17 / Redis / RabbitMQ**，
> 聚焦高 QPS 场景下的缓存架构、秒杀一致性、最终一致性设计与 AI 工具调用。

---

## 目录

- [功能特性](#功能特性)
- [架构与核心设计](#架构与核心设计)
- [技术栈](#技术栈)
- [项目结构](#项目结构)
- [快速开始](#快速开始)
- [配置说明](#配置说明)
- [REST API 一览](#rest-api-一览)
- [测试](#测试)
- [CI](#ci)
- [更新记录](#更新记录)

---

## 功能特性

| 模块 | 说明 | 关键实现 |
|---|---|---|
| 用户登录 | 手机验证码登录、Token 会话、登出 | Redis 存储验证码/Token；`RefreshTokenInterceptor` 滑动续期 + `LoginInterceptor` 强制登录 |
| 店铺查询 | 店铺详情、分类列表、按类型/名称分页、附近店铺 | **多级缓存**（Caffeine → Redis → DB）+ 布隆过滤器防穿透；GEO `GEOSEARCH` 距离排序 |
| 优惠券秒杀 | 秒杀券创建、异步下单、支付、超时关单 | 见 [秒杀链路](#秒杀链路) |
| 探店笔记 | 发布、热门推荐、点赞、点赞 Top5、我的/他人笔记 | ZSet 点赞、**Redis Pipeline 批量查询**点赞状态、关注者 Feed 推模式 |
| 社交关注 | 关注/取关、是否关注、共同关注 | MySQL + Redis Set 双写，`SINTER` 求交集 |
| 签到 | 每日签到、连续签到天数 | BitMap + `BITFIELD` 无符号截取 |
| AI 助手 | 智能对话（可选，默认关闭） | LangChain4j AiServices：店铺查询 / 预约到店 2 个工具，会话记忆持久化 Redis |

## 架构与核心设计

### 秒杀链路

```
用户请求
   │
   ▼
seckill.lua（原子校验，一步完成）
   ├─ 时间窗口判断（未开始/已结束 → 拒绝）
   ├─ 库存判断（Redis 预扣库存）
   └─ 一人一单（Set 去重）
   │ 通过
   ▼
RabbitMQ 异步落库（削峰）
   ├─ 消费端 Redisson 锁 + DB (user_id, voucher_id) 唯一索引 → 幂等兜底
   ├─ 消费失败 → 指数退避重试 3 次 → 死信队列（人工排查）
   └─ 同时投递 TTL 延迟队列（10 分钟）
   │
   ▼
超时未支付 → 延迟队列过期 → 关单消费者
   ├─ CAS 订单状态 未支付 → 已取消
   └─ cancelSeckill.lua 原子回补库存 + 移除已购标记 → DB 库存回补
```

**一致性保障层次**：Lua 原子预扣（Redis）→ RabbitMQ 可靠异步（重试/死信）→ DB 唯一索引最终兜底 → 延迟关单循环回补库存。

### 缓存一致性

```
写路径：DB 更新 → 事务提交(afterCommit)后失效缓存 → 延迟 500ms 二次删除（双删兜底）
同步：Redis pub/sub（cache:invalidate）广播通知各实例清除 Caffeine 本地缓存
读路径：Caffeine 本地缓存 → Redis → 布隆过滤器前置拦截 → DB（空值缓存防穿透）
```

- **防穿透**：Redisson 分布式布隆过滤器（`bloom:shop` / `bloom:voucher`），启动时预热存量数据，新增实时登记。
- **防击穿**：多级缓存下由本地缓存 + 互斥重建兜底。
- **防雪崩**：缓存 TTL 加入随机因子（±10%），避免集体失效。

### 其他亮点

- **全局唯一 ID** `RedisIdWorker`：时间戳 + 序列 + 随机位，秒杀/预约 ID 生成。
- **Feed 流**：关注推送「推模式」，ZSet 按时间戳滚动分页。
- **AI 工具调用**：`AiServices.builder()` 注册 `ShopQueryTool` / `ReservationTool`，`@MemoryId` 按用户隔离会话。

## 技术栈

| 类别 | 选型 |
|---|---|
| 框架 | Spring Boot 2.7.18、MyBatis-Plus 3.4.3 |
| 语言 | Java 17 |
| 存储 | MySQL 5.x、Redis（Lettuce + Redisson 3.13.6）、Caffeine |
| 消息 | RabbitMQ（异步下单、延迟关单、死信） |
| AI | LangChain4j 1.0（core + 主模块 + open-ai） |
| 工具 | Hutool、Lombok、GitHub Actions CI |

## 项目结构

```
src/main
├── java/com/hmdp
│   ├── ai/            # AI 助手：ChatAssistant、AIChatService、ShopQueryTool、ReservationTool、RedisChatMemoryStore
│   ├── config/        # RabbitConfig、RedissonConfig、MvcConfig、LangChain4jConfig、RedisMessageConfig、WebExceptionAdvice
│   ├── controller/    # REST 接口层（user/shop/voucher/voucher-order/blog/follow/shop-type/upload/ai）
│   ├── dto/           # 传输对象（Result、LoginFormDTO、ScrollResult、UserDTO）
│   ├── entity/        # 实体（User、Shop、Voucher、VoucherOrder、Blog、Follow 等）
│   ├── mapper/        # MyBatis-Plus Mapper
│   ├── service/       # 业务层（接口 + impl）
│   └── utils/         # CacheClient、BloomFilterUtils、BloomFilterWarmup、CacheConsistencyManager、
│                      # RedisIdWorker、LocalCache、UserHolder、RedisConstants、SystemConstants 等
└── resources
    ├── db/hmdp.sql          # 数据库初始化脚本（含唯一索引）
    ├── seckill.lua          # 秒杀原子脚本（时间窗 + 库存 + 一人一单）
    ├── cancelSeckill.lua    # 关单回补原子脚本
    └── application*.yaml    # 配置（base / example 模板）
```

## 快速开始

### 环境要求

| 依赖 | 说明 |
|---|---|
| JDK 17 | `java -version` 确认 |
| MySQL 5.x | 初始化 `src/main/resources/db/hmdp.sql`（库名 `hmdp`） |
| Redis | 默认 `127.0.0.1:6379`，可带密码 |
| RabbitMQ | 默认 `guest/guest@localhost:5672` |
| nginx（可选） | 静态图片资源服务，图片上传目录可配置 |

### 步骤

```bash
# 1) 初始化数据库
mysql -uroot -p < src/main/resources/db/hmdp.sql

# 2) 生成本地配置（模板无凭据，填入本机数据库/Redis 密码）
cp src/main/resources/application-example.yaml src/main/resources/application-local.yaml

# 3) 运行测试（Windows）
mvnw.cmd test
#    或 macOS/Linux
./mvnw test

# 4) 启动应用（端口 8084，激活 local profile）
mvnw.cmd spring-boot:run
```

> `application-local.yaml` 含真实凭据，已被 `.gitignore` 忽略，**切勿提交**。

## 配置说明

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `hmdp.upload-dir` | Windows nginx 路径 | 图片上传根目录，Linux/CI 请改为部署路径 |
| `bloom.warmup.enabled` | `true` | 启动时预热布隆过滤器（店铺/优惠券） |
| `cache.double-delete.enabled` | `true` | 缓存一致性延迟双删开关 |
| `cache.double-delete.delay-ms` | `500` | 二次删除延迟间隔 |
| `ai.openai.enabled` | `false` | AI 助手开关（开启需网络与 Key） |
| `ai.openai.api-key` | `OPENAI_API_KEY` 环境变量 | OpenAI 兼容服务 Key；`base-url`/`model` 可改 |

## REST API 一览

### 用户 `/user`

| 方法 | 路径 | 说明 | 登录 |
|---|---|---|---|
| POST | `/user/code?phone=` | 发送验证码 | 否 |
| POST | `/user/login` | 验证码登录（Token） | 否 |
| POST | `/user/logout` | 登出 | 是 |
| GET | `/user/me` | 当前用户 | 是 |
| GET | `/user/info/{id}` | 用户详情 | 是 |
| GET | `/user/{id}` | 用户信息 | 是 |
| POST | `/user/sign` | 签到 | 是 |
| GET | `/user/sign/count` | 本月连续签到天数 | 是 |

### 店铺 `/shop`、`/shop-type`

| 方法 | 路径 | 说明 | 登录 |
|---|---|---|---|
| GET | `/shop/{id}` | 店铺详情（多级缓存） | 否 |
| POST | `/shop` | 新增店铺（登记布隆过滤器） | 否 |
| PUT | `/shop` | 更新店铺（提交后失效 + 双删） | 否 |
| GET | `/shop/of/type?typeId=&current=&x=&y=` | 按类型分页；带坐标走 GEO 距离排序 | 否 |
| GET | `/shop/of/name?name=&current=` | 按名称分页 | 否 |
| GET | `/shop-type/list` | 分类列表（30 分钟缓存） | 否 |

### 优惠券 `/voucher`、`/voucher-order`

| 方法 | 路径 | 说明 | 登录 |
|---|---|---|---|
| POST | `/voucher` | 新增普通券 | 否 |
| POST | `/voucher/seckill` | 新增秒杀券（写 Redis 库存 + 时间元数据） | 否 |
| GET | `/voucher/list/{shopId}` | 店铺优惠券列表 | 否 |
| POST | `/voucher-order/seckill/{id}` | 秒杀下单（异步，返回 orderId） | 是 |
| POST | `/voucher-order/pay/{id}` | 模拟支付（未支付 → 已支付） | 是 |

### 笔记 `/blog`

| 方法 | 路径 | 说明 | 登录 |
|---|---|---|---|
| POST | `/blog` | 发布笔记（推送给粉丝 Feed） | 是 |
| PUT | `/blog/like/{id}` | 点赞 / 取消点赞 | 是 |
| GET | `/blog/of/me?current=` | 我的笔记 | 是 |
| GET | `/blog/hot?current=` | 热门笔记 | 否 |
| GET | `/blog/{id}` | 笔记详情 | 是 |
| GET | `/blog/likes/{id}` | 点赞 Top5 用户 | 是 |
| GET | `/blog/of/user?id=&current=` | 某用户笔记 | 是 |
| GET | `/blog/of/follow?lastId=&offset=` | 关注 Feed 滚动分页 | 是 |

### 关注 `/follow`、上传 `/upload`

| 方法 | 路径 | 说明 | 登录 |
|---|---|---|---|
| PUT | `/follow/{id}/{isFollow}` | 关注 / 取关 | 是 |
| GET | `/follow/or/not/{id}` | 是否关注 | 是 |
| GET | `/follow/common/{id}` | 共同关注 | 是 |
| POST | `/upload/blog` | 上传图片 | 否 |
| GET | `/upload/blog/delete?name=` | 删除图片（原项目遗留 GET 语义，可后续调整为 DELETE） | 否 |

### AI `/ai`（需 `ai.openai.enabled=true`）

| 方法 | 路径 | 说明 | 登录 |
|---|---|---|---|
| POST | `/ai/chat` | 智能对话（可触发查店铺/预约工具） | 是 |

## 测试

- **40+ 单元测试**，mock 隔离外部依赖，`mvnw test` 全绿。
- 覆盖重点：
  - `SeckillScriptTest`：秒杀 Lua 防回归（库存 key 前缀、时间窗参数、`KEYS` 拼写、消费者注解、禁用 Redis Stream）
  - `SeckillOrderConsistencyTest`：唯一索引、延迟关单、Redis 回补脚本
  - `BloomFilterUtilsTest` / `MultiLevelCacheTest` / `CacheClientTest`：缓存与布隆各分支
  - `CacheConsistencyManagerTest`：事务提交后失效、延迟双删、开关
  - `AIAssistantWiringTest`：AiServices 装配、工具真实执行、会话记忆
  - `BlogLikedPipelineTest`：Pipeline 批量点赞状态
- 集成测试 `HmDianPingApplicationTests` 需真实 MySQL/Redis；GEO 预热为手工脚本，默认 `@Disabled`。

## CI

- GitHub Actions（`.github/workflows/ci.yml`），push 到 `master` 触发。
- 服务容器：MySQL 5.7 + Redis 6 + RabbitMQ 3。
- 凭据通过 `SPRING_DATASOURCE_*` / `SPRING_REDIS_*` 环境变量注入，无真实凭据入库。

## 更新记录

| Commit | 内容 |
|---|---|
| `83b7c59` | 文档整理（AGENTS.md / README） |
| `5369e46` | 上传目录可配置化，清理过时注释与误标测试 |
| `314b5c3` | 商铺分类缓存 key 规范命名 + TTL |
| `01948e9` | 秒杀时间窗口校验（Lua 原子判断） |
| `b481a90` | 修复 AI 工具调用接线（AiServices） |
| `b832f36` | 缓存一致性增强（提交后失效 + 延迟双删） |
| `19acd90` | 分布式布隆过滤器（Redisson）+ 启动预热 |
| `f1302e7` | 点赞状态 Redis Pipeline 批量查询 |
| `7a64343` | 秒杀订单超时关单与库存回补 |
| `818b233` | 秒杀消费重试与死信队列 |
| `8368f06` | 秒杀订单幂等兜底（唯一索引） |
| `4152066` | 升级 Java 17 / Spring Boot 2.7.18 |
| `86053a9` | LangChain4j AI 工具调用 |
| `8524619` | 多级缓存（Caffeine + Redis）+ 布隆过滤器 |
| `5140888` | 秒杀异步从 Redis Stream 迁移到 RabbitMQ |

---

*仅供学习交流使用。详细约定见 [AGENTS.md](./AGENTS.md)。*