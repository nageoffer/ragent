# Security Policy

本仓库为私有 fork，漏洞报告采用以下方式：

## 报告途径

- 优先使用 GitHub 的私有漏洞报告（Security tab → Report a vulnerability），适用于可造成实际危害的安全缺陷（如越权、注入、密钥泄露路径）。
- 一般问题或拿不准是否算漏洞，直接开 issue 即可。

## 原则

- 不要在 issue、PR、聊天记录或 commit message 中贴任何凭据：API Key、数据库口令、token、私钥等。
- 报告请包含：影响模块、触发条件、复现步骤、预期 vs 实际行为、建议修复方向。
- 仓库密钥只通过 GitHub Secrets / 环境变量注入，代码中出现的 `BAILIAN_API_KEY`、`SILICONFLOW_API_KEY` 等均为占位符。
