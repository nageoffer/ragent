## 变更说明

- 要解决什么问题，为什么需要这个改动
- 关键实现/设计取舍（一句话即可，细节放 PR 正文或代码注释）

## 风险

- 影响范围：涉及哪些模块与链路（检索 / 记忆 / 模型路由 / 入库管线 / 前端 / 其他）
- 兼容性与回滚点：是否引入依赖、配置或数据结构变化

## 验证方式

- [ ] 后端：`./mvnw -B -ntp spotless:check` 通过，`./mvnw -B -ntp -DskipTests package` 通过
- [ ] 前端（如有改动）：`npm ci && npm run lint && npm run build` 通过
- [ ] CI 门禁（backend-maven / frontend-build-lint）全绿
- [ ] 相关功能的手动验证或测试结果

## 回退路径

- 如何回退（如 revert commit / 配置回滚），回退后是否有残留数据需要清理

## 非目标范围

- 本次明确不做的事（避免 PR 范围膨胀）
