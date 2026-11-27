# Droplet 记账 — 项目开发与协作铁律（AGENTS.md）

本文件适用于 `kvtttba/droplet` 仓库内的所有开发、测试与评审任务。**每个新开发会话启动时必须先读本文件与 [`docs/PROGRESS.md`](docs/PROGRESS.md)**。

---

## 1. 权威设计文档

- 产品需求规格：[`docs/PRD.md`](docs/PRD.md)
- 系统架构、数据库 Schema 与 CI 方案：[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)
- 进度看板与交接状态：[`docs/PROGRESS.md`](docs/PROGRESS.md)

实现过程中若发现文档与现实冲突，先明确记录变更理由并同步更新对应文档，禁止静默偏离规格。

---

## 2. 公开仓库安全红线（绝对禁止违反）

本仓库为 **GitHub Public 公开仓库**，任何提交和推送前必须强制核查：

1. **零敏感信息入库**：严禁提交任何 `API Key`、自定义个人 `BaseURL`、WebDAV URL/账号/密码、签名文件（`*.jks` / `*.keystore` / `*.p12` / `*.pem`）、`local.properties`、`.env*`、`secrets.properties`、个人邮箱/IP/主机名、真实银行卡号、真实账单 CSV 或真实消费小票原图。
2. **全量脱敏与 Mock**：单元测试、Robolectric、UI 截图测试与 CI 中涉及 AI 接口、行情源、WebDAV、票据图片的内容，一律使用本地 Mock 与虚构脱敏数据。
3. **推送前扫描**：每次 `git commit` / `git push` 前必须自检 `git diff --cached` 并运行密钥扫描（`gitleaks` 或正则预检），确认无任何凭据、私钥头、带账号密码的 URL。
4. **Git 身份**：本仓库提交使用公开 GitHub 账号与 `noreply` 邮箱，不暴露个人真实邮箱。
5. **CI 最小权限**：GitHub Actions Workflow 默认顶层权限保持 `contents: read`，绝不对外部 PR 暴露任何发版 Secrets。

---

## 3. 核心领域与工程铁律

1. **严禁浮点数处理金额与份额**：
   - 法币金额（CNY / USD / HKD）统一使用定点数 `Long`（`scale = 4`，`10000 = 1.0000`）。
   - 投资份额（含加密货币）、汇率、单价统一使用 `BigDecimal`（规范十进制字符串持久化，支持 8 位精度）。
2. **复式借贷恒平不变量**：
   - 单币种交易必须满足 `SUM(posting.amount_minor) == 0`。
   - 跨币种交易通过隐式 `Equity:Trading:{CCY}` 对冲，确保每个币种各自 `SUM(posting.amount_minor) == 0`。
   - 每条分录锁定交易发生时的 `base_amount_minor` 与 `exchange_rate_snapshot`，历史收支报表绝不因日后汇率变化而漂移。
   - 行情缓存（`market_quotes`）与汇率缓存（`exchange_rates`）独立于账本流水。
3. **平台与技术约束**：
   - Android 单端，`minSdk = 31`（Android 12+），`targetSdk = 35`，`compileSdk = 35`。
   - 技术栈：Kotlin + Jetpack Compose + Material 3（Dynamic Color + 深浅色模式）+ Room + WorkManager + OkHttp + Kotlinx Serialization。
   - 严禁引入无障碍服务（`AccessibilityService`）或通知栏监听权限。
4. **UI 审美与体验底线**：
   - 追求 Material 3 精致排版、清晰的金额层级（等宽数字 `tabular-nums`）、舒适留白与高频单手可达操作；
   - 涉及金额展示的页面必须接入全局「隐私隐藏金额」状态。
5. **WebDAV 与本地凭据安全**：
   - AI `API Key`、WebDAV 密码等敏感配置仅通过 Android Keystore 加密存储于本机，严禁打日志。
   - WebDAV 恢复前必须自动生成本地应急回滚备份，确保原子替换与失败可回滚。

---

## 4. 开发与验收工作流（1 切片 = 1 PR）

1. **分支与 PR**：按 [`docs/PROGRESS.md`](docs/PROGRESS.md) 中的切片编号（如 `M0`、`M1.1`）在独立分支开发（如 `feat/m1-1-ledger-engine`），通过 PR 合入 `main`。
2. **测试门禁**：
   - 纯领域算法与数据库变更必须配齐 L1 单元测试与 L2 Robolectric/Room 测试；
   - UI 页面变更必须配齐 L3 Compose UI / Roborazzi 截图测试，供视觉验收；
   - CI 必须通过 `gitleaks` 秘密扫描、单测/截图、以及 API 31 模拟器 E2E（含 Debug APK 构建）。
3. **强制交接更新**：每个切片完成合入 `main` 前，**必须更新 `docs/PROGRESS.md`**——勾选已完成切片、记录关键实现决策与踩坑说明，确保下一个全新会话可无缝衔接。
