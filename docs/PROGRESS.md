# Droplet 记账 — 开发进度看板与跨阶段交接单

> 每个开发会话启动时先读 [`AGENTS.md`](../AGENTS.md) 与本文档；每个切片完成合入 `main` 前必须更新本文档。

## 当前状态

- **当前阶段**：需求澄清与架构设计已完成（v1.0 定稿），准备开启 **M0**。
- **下一个待执行切片**：**`M0`**（工程骨架 + Material 3 主题 + GitHub Actions CI 全线跑通）
- **最新已合入提交**：需求文档初始化提交（见 `git log`）

---

## 已锁定基线摘要（勿重复讨论）

- **应用定位**：Droplet 记账，Android 单端（`minSdk = 31` / Android 12+），个人自用，无登录、本地优先，纯净无广告。
- **技术栈**：Kotlin + Jetpack Compose + Material 3（支持动态配色与暗黑模式）+ Room + WorkManager + OkHttp + Kotlinx Serialization。
- **核心账本**：单账本 + 多标签；底层严格复式（法币 4 位定点 `Long`，投资/加密份额 8 位 `BigDecimal`，跨币种隐式 `Equity:Trading` 对冲）；前端一木式直观表单 + 可展开专业分录模式；支持 **CNY / USD / HKD**（默认本位币 CNY）。
- **资产与行情**：覆盖 A 股、公募基金、ETF、港股、美股、贵金属/黄金、加密货币、定期存款、银行理财；免费公开行情（国内直连源优先 + 备用源 + 可选代理 + 失败保留缓存并支持手动覆写），进资产页刷新 + 后台定时更新。
- **信贷/借贷/报销/退款**：信用卡额度/账单日/还款日提醒；等额本息/等额本金/固定手续费分期自动生成还款分录与提醒；往来借贷（分次还/利息/坏账）；待报销冲抵；多次关联退款。
- **AI 记账**：不做无障碍/通知栏抓账；支持图片（小票汇总/单张截图/长截图/批量多图/系统分享）、语音（AI 接口 + 系统语音双模式）、一句话文本；OpenAI Compatible 自定义 `BaseURL`/`API Key`/`Model`（视觉/文本/语音可复用或分设）；仅传名称列表不传余额与流水；支持离线草稿与人工确认入账。
- **同步与隐私**：`BiometricPrompt` + 锁屏兜底 + 切后台重锁 + `FLAG_SECURE` + 一键隐藏金额；WebDAV 单机加密快照备份恢复（AES-256-GCM，防抖自动上传 + 定时 + 历史快照 + 票据图同步开关）。
- **测试与安全**：Public 公开仓，强制 `.gitignore` + `gitleaks` + GitHub Secret Scanning / Push Protection；GitHub Actions `ubuntu-latest`（4C16G + KVM）跑 L1 单测 / L2 Robolectric+Room / L3 Roborazzi UI 截图 / L4 API 31 模拟器 E2E + 输出 Debug APK。

---

## 里程碑与切片总看板（1 切片 = 1 PR）

| 编号 | 切片名称 | 状态 | 核心交付与验收标准 | PR / 备注 |
| --- | --- | --- | --- | --- |
| `DOCS` | 需求澄清与架构定稿 | ✅ 已完成 | `PRD.md`、`ARCHITECTURE.md`、`AGENTS.md`、`PROGRESS.md`、`.gitignore` | 初始文档提交 |
| `M0` | 工程骨架与 CI 跑通 | ⬜ 待开始 | Gradle 多模块结构、Material 3 主题与 4 Tab 占位壳、`gitleaks`、GitHub Actions（单测 + Roborazzi 截图 + API 31 模拟器 + Debug APK）全绿 | 下一切片 |
| `M1.1` | 复式记账引擎与三币种 Trading 对冲 | ⬜ 待开始 | `core:model` + `core:domain` 复式校验、定点数/BigDecimal、跨币种对冲、L1 高覆盖单测 | — |
| `M1.2` | Room Schema、DAO 与预置分类/账户 | ⬜ 待开始 | `core:database` 全量表结构、预置二级分类/系统科目、L2 Robolectric + Room 测试 | — |
| `M1.3` | 明细首页、账户/标签管理与记账表单 | ⬜ 待开始 | 首页流水与筛选、账户与分类/标签 CRUD、一木式表单 + 专业分录展开、Roborazzi 截图 + APK | — |
| `M1.4` | 生物识别隐私锁、重锁与金额隐藏 | ⬜ 待开始 | `BiometricPrompt`、切后台超时重锁、`FLAG_SECURE`、首页/资产一键隐身 | — |
| `M2.1` | 免费公开汇率与全品类行情 Provider 链 | ⬜ 待开始 | CNY/USD/HKD 汇率 + 9 类资产公开源适配、降级缓存、可选代理、Mock 测试 | — |
| `M2.2` | 投资持仓引擎与定存/理财计算 | ⬜ 待开始 | 买/卖/定投/分红、加权成本与浮盈、定期存款自动计息、银行理财手动更新 | — |
| `M2.3` | 资产总览页、持仓详情与后台定时刷新 | ⬜ 待开始 | 资产页总览与币种分布、持仓行情与手动覆写价、WorkManager 定时刷新、截图 + APK | — |
| `M3.1` | 信用卡额度、账单日与还款本地提醒 | ⬜ 待开始 | 信用额度进度条、出账/还款倒计时、到期前本地通知提醒 | — |
| `M3.2` | 分期贷款引擎与到期自动还款分录 | ⬜ 待开始 | 等额本息/等额本金/固定手续费计划表、到期自动拆本息分录与通知 | — |
| `M3.3` | 往来借贷、待报销冲抵与多次退款 | ⬜ 待开始 | 往来人借入/借出/分次还/利息/坏账核销、待报销冲抵与差额转支出、多次关联退款 | — |
| `M4.1` | AI 配置、Keystore 加密与 JSON 解析引擎 | ⬜ 待开始 | OpenAI Compatible 配置页与连通性测试、Keystore 加密存储、隐私最小化 Prompt、容错解析 | — |
| `M4.2` | 图片/语音/一句话记账与草稿批量确认 UI | ⬜ 待开始 | 拍照/长截图/批量/系统分享、语音双模式、一句话多笔、离线草稿箱与批量确认流 + APK | — |
| `M5.1` | 预算管理与预警、周期记账与模板 | ⬜ 待开始 | 月/年总预算与分类预算及预警、周期自动生成、高频一键模板 | — |
| `M5.2` | 统计报表、收支日历与多格式导出 | ⬜ 待开始 | 收支趋势/分类环形/资产走势/收支日历（含标签筛选）、Excel/CSV/Beancount 导出 | — |
| `M5.3` | WebDAV 端到端加密快照备份与回滚恢复 | ⬜ 待开始 | WAL 一致性打包、AES-256-GCM 加密、防抖/定时上传、历史快照列表、本地回滚安全恢复 | — |

---

## 关键决策与交接备忘（给下一个切片）

### M0 启动入口契约
1. 建立 [`docs/ARCHITECTURE.md`](ARCHITECTURE.md) 第 1 节定义的 Gradle 多模块目录结构（采用 Kotlin DSL `*.gradle.kts` + Version Catalog `gradle/libs.versions.toml`）。
2. 配置 `minSdk = 31`、`targetSdk = 35`、`compileSdk = 35`、Java/Kotlin 目标 JVM 17（Gradle 运行环境 OpenJDK 21 兼容）。
3. 搭建 Material 3（Android 12+ Dynamic Color + 深浅色模式）主题与底部 4 Tab（明细 / 资产 / 统计 / 我的）+ 悬浮快捷记账按钮的干净 UI 壳，并接入 Roborazzi 截图测试，产出首页/资产页占位渲染图。
4. 编写 `.github/workflows/android-ci.yml`，串联 `gitleaks` 扫描、`testDebugUnitTest`（含 Roborazzi）、`ubuntu-latest` KVM API 31 模拟器 `connectedDebugAndroidTest` 与 Debug APK 构建产物上传。
5. 遵守公开仓库安全红线，禁止提交任何本地配置、私钥或敏感信息。

---

## 踩坑记录

- （暂无，开发过程中按切片持续补充）
