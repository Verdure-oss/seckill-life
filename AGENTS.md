# AGENTS.md

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
- subject 用英文，简介（≤50 字符），动词原形开头，不要用句号结尾。
- 一个 commit 只做一件事：不要把互不相关的改动混在一起。
- 注：现有历史中个别 `feat:`/`docs:` 描述较长，可接受，但新提交遵循以上规则。

## 测试策略

- 逻辑密集的组件（Redis 锁、ID 生成、校验、序列化、分支逻辑）应当优先写**单元测试**，使用 mock 隔离依赖，不依赖真实 Redis/MySQL。
- 依赖 Spring 上下文或真实外部资源的，才放进集成测试（`@SpringBootTest`）。
- 测试命名遵循 `methodName_condition_expectedBehavior` 或 `Method_criteria_behavior` 风格。
- 新增功能或修复 bug 时，必须在同一 commit 中补充或更新对应测试。

## 敏感信息处理

- 带凭据的配置（`application-local.yaml` 等）禁止提交，已加入 `.gitignore`。
- 提交前检查是否意外引入了密钥；如发现，须先清理历史再推送。