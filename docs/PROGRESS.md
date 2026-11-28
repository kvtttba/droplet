# Droplet 记账 — 开发进度看板与跨阶段交接单

> 每个开发会话启动时先读 [`AGENTS.md`](../AGENTS.md) 与本文档；每个切片完成合入 `main` 前必须更新本文档。

## 当前状态

- **当前阶段**：`M4.2`（图片/语音/一句话记账与草稿批量确认 UI）已完成验收并合入 `main`。
- **下一个待执行切片**：**`M5.1`**（预算管理与预警、周期记账与模板）
- **最新已合入提交**：`feat/m4-2-ai-capture-drafts` 合入 `main`（CI `#46131560383` 全线绿灯，详见 `git log`）

---

## 已锁定基线摘要（勿重复讨论）

- **应用定位**：Droplet 记账，Android 单端（`minSdk = 31` / Android 12+），个人自用，无登录、本地优先，纯净无广告。
- **包名与工程标识**：`applicationId = "io.github.kvtttba.droplet"`，Debug APK 输出名 `droplet-debug.apk`。
- **技术栈与已锁定版本基线**：Gradle 8.11.1 + Kotlin DSL + `gradle/libs.versions.toml`；AGP 8.9.3、Kotlin 2.1.21、KSP 2.1.21-2.0.1、Hilt 2.56.2、Compose BOM 2025.05.01、Navigation Compose 2.8.9、Fragment 1.8.6、Lifecycle Process 2.8.7、Biometric 1.1.0、DataStore Preferences 1.1.4、Room 2.7.1、WorkManager 2.10.1、OkHttp 4.12.0、Kotlinx Serialization 1.8.1、Robolectric 4.14.1、Roborazzi 1.44.0；`compileSdk = 35` / `targetSdk = 35` / `minSdk = 31`，JVM 17 字节码目标（OpenJDK 21 运行环境兼容）。
- **核心账本**：单账本 + 多标签；底层严格复式（法币 4 位定点 `Long`，投资/加密份额 8 位 `BigDecimal`，跨币种隐式 `Equity:Trading` 对冲）；前端一木式直观表单 + 可展开专业分录模式；支持 **CNY / USD / HKD**（默认本位币 CNY）。
- **资产与行情**：覆盖 A 股、公募基金、ETF、港股、美股、贵金属/黄金、加密货币、定期存款、银行理财；免费公开行情（国内直连源优先 + 备用源 + 可选代理 + 失败保留缓存并支持手动覆写），进资产页刷新 + 后台定时更新。
- **信贷/借贷/报销/退款**：信用卡额度/账单日/还款日提醒；等额本息/等额本金/固定手续费分期自动生成还款分录与提醒；往来借贷（分次还/利息/坏账）；待报销冲抵；多次关联退款。
- **AI 记账**：不做无障碍/通知栏抓账；支持图片（小票汇总/单张截图/长截图/批量多图/系统分享）、语音（AI 接口 + 系统语音双模式）、一句话文本；OpenAI Compatible 自定义 `BaseURL`/`API Key`/`Model`（视觉/文本/语音可复用或分设）；仅传名称列表不传余额与流水；支持离线草稿与人工确认入账。
- **同步与隐私**：`BiometricPrompt` + 锁屏兜底 + 切后台重锁 + `FLAG_SECURE` + 一键隐藏金额；WebDAV 单机加密快照备份恢复（AES-256-GCM，防抖自动上传 + 定时 + 历史快照 + 票据图同步开关）。
- **测试与安全**：Public 公开仓，强制 `.gitignore` + `.gitleaks.toml` + `scripts/pre-commit-secret-check.sh` + GitHub Actions（`permissions: contents: read`）跑 L1 单测 / L2 Robolectric+Room / L3 Roborazzi UI 截图 / L4 KVM API 31 模拟器 E2E + 输出 `droplet-debug.apk`。

---

## 里程碑与切片总看板（1 切片 = 1 PR）

| 编号 | 切片名称 | 状态 | 核心交付与验收标准 | PR / 备注 |
| --- | --- | --- | --- | --- |
| `DOCS` | 需求澄清与架构定稿 | ✅ 已完成 | `PRD.md`、`ARCHITECTURE.md`、`AGENTS.md`、`PROGRESS.md`、`.gitignore` | 初始文档提交 (`4a6eb00`) |
| `M0` | 工程骨架与 CI 跑通 | ✅ 已完成 | 14 模块 Gradle 结构、Material 3 主题与 4 Tab 占位壳、`gitleaks`、GitHub Actions（L1 单测 + Roborazzi 8 张截图 + API 31 KVM 模拟器 E2E + 12 MB Debug APK）全绿 | `feat/m0-scaffold-ci` 已验收合入（CI `#45964751721`） |
| `M1.1` | 复式记账引擎与三币种 Trading 对冲 | ✅ 已完成 | `core:model` 全套复式模型 + `core:domain` 平衡校验/三币种 Trading 自动对冲/反推汇率/历史快照锁定/18 类分录 Builder + 85 项 L1 单测全绿 | `feat/m1-1-ledger-engine` 已验收合入（CI `#45968558544`） |
| `M1.2` | Room Schema、DAO 与预置分类/账户 | ✅ 已完成 | `:core:model` 跨模块扩展模型 + `:core:database` 全量 16 表 Room Schema/JSON 导出、强一致 `@Transaction` 复式校验写入器、多维 Flow 查询 DAO、WAL `VACUUM INTO` 快照导出、幂等预置初始化 + 18 项 L2 Robolectric/Room 测试（总 115 项测试全绿） | `feat/m1-2-room-database` 已验收合入（CI `#45971965896`） |
| `M1.3` | 明细首页、账户/标签管理与记账表单 | ✅ 已完成 | `:app` Hilt DI + 启动幂等预置、`:core:database` `LedgerRepository`、明细首页实时 Room Flow 月度汇总/多维筛选（多标签 ANY/ALL）/按日分组与详情编辑/作废/恢复/删除、一木式直观记账 + 跨币种反推汇率 + 一键展开专业多拆分录、账户（含期初余额）/二级分类/多标签 CRUD + 125 项测试 & 13 张 Roborazzi 截图 + API 31 实账 E2E + 13 MB Debug APK 全绿 | `feat/m1-3-ledger-ui-and-forms` 已验收合入（CI `#45976954090`） |
| `M1.4` | 生物识别隐私锁、重锁与金额隐藏 | ✅ 已完成 | `:core:security` DataStore 隐私偏好持久化、`AppLockStateMachine`（单调时钟 5 档超时重锁）、Android 12+ `BiometricPrompt`（含设备锁屏兜底）、Keystore AES-256-GCM 凭据加密器；`MainActivity` 升级 `FragmentActivity` 接入动态 `FLAG_SECURE`、`ProcessLifecycleOwner` 与全屏 `AppLockOverlay`；`PrivacySecurityScreen` 落地与全局隐身/主题持久化 + 156 项测试 & 16 张 Roborazzi 截图 + API 31 持久化 E2E + 14 MB Debug APK 全绿 | `feat/m1-4-biometric-privacy-lock` 已验收合入（CI `#45999836880`） |
| `M2.1` | 免费公开汇率与全品类行情 Provider 链 | ✅ 已完成 | 纯 JVM `:core:network` 代理配置与 OkHttp 工厂、CNY/USD/HKD 3 级主备汇率链（东方财富→Frankfurter→Open ER API）、9 类资产国内直连优先+备用源降级+单标的失败隔离行情链、5 分钟节流；`:core:security` 代理 DataStore 持久化；`:core:database` `MarketDataRepository` 缓存降级与手动覆写单价/清除；`:app` `NetworkModule` + `MarketDataEntryPoint`；185 项离线单测 & 16 张 Roborazzi 截图 + API 31 E2E + 15 MB Debug APK 全绿 | `feat/m2-1-market-fx-providers` 已验收合入（CI `#46005286947`） |
| `M2.2` | 投资持仓引擎与定存/理财计算 | ✅ 已完成 | 纯 JVM `:core:domain` 零浮点投资引擎（`PortfolioPositionEngine` 加权平均成本/定投/部分卖出结转/清仓零残差再买/分红/防超卖/8 位加密小数/跨币种行情折算、`FixedDepositCalculator` ACT/365 三模式计息与到期/提前支取分录辅助、`BankWealthValuationHelper` 公开净值与手动净值/收益金额反推、`PortfolioSummaryAggregator` 全账户与持仓净资产/三币种分布及 `includeInNetWorth` 过滤）；`:core:database` `InvestmentRepository` / `LocalInvestmentRepository` 原子复式写入与实时 Flow 持仓/总览聚合；`:app` `InvestmentModule` + `InvestmentEntryPoint`；206 项测试 & 16 张 Roborazzi 截图 + API 31 E2E + 15 MB Debug APK 全绿 | `feat/m2-2-investment-portfolio-engine` 已验收合入（CI `#46010205871`） |
| `M2.3` | 资产总览页、持仓详情与后台定时刷新 | ✅ 已完成 | `:feature:assets` 接入 Hilt 与真实 Room Flow 资产总览（CNY/USD/HKD 实时切换、真实三币种分布条、进页 5 分钟节流 + 下拉/一键刷新 + 上次更新时间、全局隐私隐身、分组账户卡与不计入总资产折叠区、9 类资产/定存/理财新建表单）；`InvestmentDetailScreen` / `Route` / `ViewModel`（8 位加密份额、加权成本与浮盈/已实现/分红/税费/净总回报、买/定投/卖实时结转预览与防超卖/分红/定存结息支取付息/手动覆写与清除/银行理财手动净值或收益反推与历史流水）；`:core:security` `MarketSyncPreferencesRepository` + `:app` WorkManager `MarketRefreshWorker`（经 `EntryPointAccessors` 访问 `MarketDataEntryPoint`）& `MarketSyncScheduler` + `:feature:settings` 行情同步与网络代理设置页；222 项测试 & 18 张 Roborazzi 截图 + API 31 投资/行情全链路 E2E + 15 MB Debug APK 全绿 | `feat/m2-3-assets-ui-and-market-sync` 已验收合入（CI `#46017227034`） |
| `M3.1` | 信用卡额度、账单日与还款本地提醒 | ✅ 已完成 | 纯 JVM `:core:domain` 零浮点 `CreditCardStatementCalculator`（29–31 号月末夹紧含闰/平年二月、同月/跨月/跨年还款日、上期已出/本期未出账单区间与倒计时、提醒窗口、已用/可用额度含溢缴款与超限保护、出账后还款/退款冲减不变量与五态判定）与 `CreditCardRepaymentHelper`（借贷恒平单/跨币种 `TRANSFER` 还款分录）；`:core:database` `CreditCardRepository` + 信用卡流水响应式查询（Room Schema `v1` 零变更）；`:core:security` `CreditCardReminderPreferencesRepository`；升级 `AssetsScreen` 信用账户分组进度条/倒计时/本期应还并直达 `CreditCardDetailScreen`（已出/未出/全部流水切换、一键立即还款表单、信用参数快捷编辑）；设置页新增「信用卡还款提醒」与 Android 13+ 通知权限引导；`:app` `POST_NOTIFICATIONS`、通知渠道/`CreditCardReminderNotificationPublisher`（支持隐私隐身与详情深链）、WorkManager `CreditCardReminderWorker`（同日防重）与 `CreditCardReminderScheduler`；244 项测试 & 19 张 Roborazzi 截图 + API 31 信用卡全链路 E2E + 15 MB Debug APK 全绿 | `feat/m3-1-credit-card-billing-reminders` 已验收合入（CI `#46030027009`） |
| `M3.2` | 分期贷款引擎与到期自动还款分录 | ✅ 已完成 | 纯 JVM `:core:domain` 零浮点 `LoanScheduleCalculator`（等额本息 `EQUAL_INSTALLMENT` / 等额本金 `EQUAL_PRINCIPAL` / 固定手续费 `FIXED_FEE`、29–31 号月末安全夹紧、末期本金尾差平齐确保 `SUM(principalMinor) == totalPrincipalMinor` 且末期剩余本金严格归零、提前结清与汇总计算）与 `LoanRepaymentHelper`（复用 `LedgerEngine.buildLoanRepayment` 生成借贷恒平 `LOAN_REPAY` 分录，支持 `MANUAL` 与 `LOAN_AUTO`）；`:core:database` 扩充 `LoanDao` 原子条件更新与 `LoanRepository`（Room Schema `v1` 零变更，支持原子创建 `LOAN` 负债户与期初余额+计划表、逐期手动/自动幂等入账、提前结清、归档与安全删除）；`:core:security` `LoanReminderPreferencesRepository`（按期次同日防重）；升级 `AssetsScreen`「分期贷款」分区与新建分期表单并直达 `LoanDetailScreen`（逐期计划表、单期一键入账、提前结清与参数维护）；`:app` `droplet_loan_reminders` 通知渠道、`LoanReminderNotificationPublisher`（支持隐私隐身与深链）、WorkManager `LoanScheduleWorker`（每日离线自动入账与到期提醒）与 `LoanScheduleScheduler`；268 项测试 & 20 张 Roborazzi 截图 + API 31 分期贷款全链路 E2E + 16 MB Debug APK 全绿 | `feat/m3-2-installment-loans` 已验收合入（CI `#46039360302`） |
| `M3.3` | 往来借贷、待报销冲抵与多次退款 | ✅ 已完成 | 纯 JVM `:core:domain` 零浮点往来/报销/退款引擎（`DebtSummaryCalculator` / `DebtTransactionHelper`、`ReimbursementCalculator` / `ReimbursementSettlementHelper`、`RefundCalculator` / `RefundHelper`，复用 `LedgerEngine` 保证借贷恒平与累计退款上限校验）；`:core:database` `DebtRepository` 与 `ReimbursementAndRefundRepository`（Room Schema `v1` 零变更，支持往来人自动联动应收/应付户、借出/借入/分次收还款含息/坏账核销、单笔与批量报销冲抵及差额转个人自付支出或超额转收入、作废/恢复/删除冲抵自动重算父单 `reimburseState`、单/跨币种多次关联退款）；`:core:security` `DebtReminderPreferencesRepository` + `:app` `droplet_debt_reminders` 通知渠道与每日离线 `DebtReminderWorker` / `DebtReminderScheduler`；升级 `AssetsScreen`「往来借贷」与「待报销」分区、`DebtDetailScreen`、`ReimbursementListScreen` 及 `LedgerScreen` 支出详情「退款」与净支出展示；287 项单测 & 23 张 Roborazzi 截图 + API 31 全链路 `test05` E2E + 17 MB Debug APK 全绿 | `feat/m3-3-debt-reimburse-refund` 已验收合入（CI `#46126060766`） |
| `M4.1` | AI 配置、Keystore 加密与 JSON 解析引擎 | ✅ 已完成 | `:core:security` 新增 `AiEndpointSlot` / `VoiceEngineMode` / `AiServiceConfig` 与 `DataStoreAiConfigPreferencesRepository`（独立 `droplet_ai_config_preferences.preferences_pb`，全槽位 API Key 经既有 `KeystoreSecretCipher` AES-256-GCM + 按槽位独立 AAD 加密落盘，严禁明文存储/日志输出，支持能力槽位独立覆盖或部分字段继承默认配置）；`:core:ai` 落地隐私最小化 `AiPromptBuilder`（严格仅注入时间/本位币 + 活跃账户名称/类型/币种 + 二级分类路径 + 活跃标签名，绝不传余额/额度/流水）、零浮点容错 `AiDraftJsonParser`（剥离 `<think>` / Markdown 代码块 / 解释前后缀、经 `BigDecimal` 转 `Money` `scale = 4` 并拦截非法/负/零/溢出数值、智能匹配本地分类/账户/目标账户/标签并高亮 `uncertainFields`）与复用 `DropletHttpClientFactory` 代理能力的 `OpenAiCompatibleClient`（BaseURL 标准化、`/models` + 降级 `/chat/completions` 脱敏连通性探测、`parseText`、`parseImages` 与 Whisper `/audio/transcriptions`）；`:app` 新增 `AiModule`/`AiEntryPoint` 与 `@AiConfigDataStore`；`:feature:settings` 落地 `AiSettingsScreen` / `Route` / `ViewModel` 并接入设置首页实时摘要；309 项单测 & 24 张 Roborazzi 截图 + API 31 全链路 `test06` E2E + 16 MB Debug APK 全绿 | `feat/m4-1-ai-config-parser` 已验收合入（CI `#46128801090`） |
| `M4.2` | 图片/语音/一句话记账与草稿批量确认 UI | ✅ 已完成 | `:core:ai` 新增 `AiDraftBatchCodec` / `AiDraftAttachmentRef`、`ReceiptImageProcessor`（安全缩放、EXIF 方向校正并经像素重编码彻底剥离 GPS/设备等 EXIF 隐私元数据、长截图重叠垂直分段切片、SHA-256 与路径穿越防护）与零浮点 `AiDraftTransactionMapper`（复用 `LedgerEngine` 构造单/跨币种支出/收入/转账/待报销恒平分录并校验必填项）；`:core:database` 保持 Room Schema `v1` 零变更，新增 `AiDraftRepository` / `LocalAiDraftRepository`（活跃草稿流与角标计数、单 SQLite 事务原子批量入账+关联本地 `attachments`+`COMMITTED` 标记及任意单条失败全批回滚）与 `AiCaptureOrchestrator`（文本/视觉/Whisper 语音转写编排、隐私最小化 Prompt 组装、未配置/网络异常自动落草稿箱重试与离线转人工核对）；`:feature:ai_capture` 落地 `AiCaptureScreen`（一句话/拍照+相册多选/双模式语音与离线草稿箱）与 `AiDraftReviewScreen`（低置信度与 `uncertainFields` 琥珀色高亮、就地微调、隐私金额隐身与一键批量入账），打通 `QuickCaptureModalSheet` 全部入口与草稿箱角标；`:app` 配置 `FileProvider`、`RECORD_AUDIO` 与 `ACTION_SEND`/`ACTION_SEND_MULTIPLE` 系统分享直达（严格在生物识别解锁后路由）；330 项单测 & 26 张 Roborazzi 截图 + API 31 全链路 `test07` E2E + 16 MB Debug APK 全绿 | `feat/m4-2-ai-capture-drafts` 已验收合入（CI `#46131560383`） |
| `M5.1` | 预算管理与预警、周期记账与模板 | ⬜ 待开始 | 月/年总预算与分类预算及预警、周期自动生成、高频一键模板 | 下一切片 |
| `M5.2` | 统计报表、收支日历与多格式导出 | ⬜ 待开始 | 收支趋势/分类环形/资产走势/收支日历（含标签筛选）、Excel/CSV/Beancount 导出 | — |
| `M5.3` | WebDAV 端到端加密快照备份与回滚恢复 | ⬜ 待开始 | WAL 一致性打包、AES-256-GCM 加密、防抖/定时上传、历史快照列表、本地回滚安全恢复 | — |

---

## 关键决策与交接备忘（给下一个切片）

### M0 已落地的核心约定
1. **模块分工**：`core:model`、`core:domain`、`core:network` 为纯 Kotlin/JVM 模块（零 Android 依赖，秒级单测）；`core:database`、`core:security`、`core:ai`、`core:sync`、`core:ui` 与 5 个 `feature:*` 模块及 `app` 为 Android 模块；开启 `TYPESAFE_PROJECT_ACCESSORS`。
2. **金额与隐私组件**：`core:model` 已提供 `Currency`、`AccountType`、`Money`（`scale = 4` 严格定点 `Long`）；`core:domain` 提供 `PrivacyAmountFormatter`；`core:ui` 提供 `DropletTheme`、`DropletIcons`、`LocalAmountsHidden`、`PrivacyAmountText` 与 `PrivacyToggleIconButton`。
3. **CI 门禁**：`.github/workflows/android-ci.yml` 在 `main` / `feat/**` / `fix/**` 推送与 PR 时自动运行 3 个 Job（`security-scan`、`unit-and-ui-snapshot`、`build-and-emulator-e2e`）。

### M1.1 已落地的核心约定
1. **领域模型 (`:core:model`)**：已补齐 `Account`（5 大 `AccountType` + 13 种 `AccountSubType` 合法配对校验 + 信用账户字段校验）、`SystemAccounts`（确定性系统权益科目 ID：`system:equity:trading:{CCY}`、`system:equity:opening:{CCY}`）、`Transaction`、`Posting`、`ExchangeRateSnapshot`（含 `ExchangeRateSource`、`convert`、`inverse`、`impliedFromAmounts`）、`Quantity`（有符号 8 位规范 `BigDecimal` 文本）、`UnitPrice`（非负 8 位单价）及业务枚举（`TransactionDisplayType`、`TransactionStatus`、`TransactionSource`、`ReimburseState`）；`Money` 补齐 `Long.MIN_VALUE` 防溢出与 `fromBigDecimal` / `sumOf`。
2. **复式引擎 (`:core:domain`)**：位于 `io.github.kvtttba.droplet.core.domain.ledger`，包含 `LedgerValidator`（单/多币种零和校验、溢出检测、单币种禁混 Trading、投资份额与账户注册表校验）、`TradingAutoHedger`（CNY/USD/HKD 二/三币种自动补齐 `Equity:Trading:{CCY}`、实付/实收反推汇率、零舍入残差锁定、幂等重平衡）、`ExchangeRateTable`（直查/取倒/三币种高精度三角换算/覆写）、`LedgerEngine`（覆盖全部典型业务场景的 18 类 Builder + 专业多拆分录直通）与 `LedgerAggregator`（历史本位币锁定汇总、实时估值、多次退款净支出重算）。

### M1.2 已落地的核心约定
1. **跨模块扩展模型 (`:core:model`)**：补齐 `Tag`、`Attachment`、`AssetClass`、`DepositInterestMode`、`InvestmentAsset`、`MarketQuote`、`CachedExchangeRate`（含 `toSnapshot()`）、`LoanMethod`、`InstallmentStatus`、`LoanPlan`、`LoanInstallment`、`DebtProfile`、`BudgetPeriod`、`Budget`、`RecurringFrequency`、`RecurringRule`、`TransactionTemplate`、`AiDraftInputType`、`AiDraftStatus`、`AiDraft`，全量遵循无浮点铁律。
2. **全量 Room 数据库 (`:core:database`)**：`DropletDatabase`（`DATABASE_NAME = "droplet_ledger.db"`，`SCHEMA_VERSION = 1`，默认 WAL 模式，`exportSchema = true` 导出 `core/database/schemas/.../DropletDatabase/1.json`）涵盖全部 16 张表（`accounts`、`transactions`、`postings`、`tags`、`transaction_tags`、`attachments`、`investment_assets`、`market_quotes`、`exchange_rates`、`loan_plans`、`loan_installments`、`debt_profiles`、`budgets`、`recurring_rules`、`templates`、`ai_drafts`）与外键级联/限制/置空、索引及 `DropletTypeConverters`。
3. **事务写入、Flow 查询、预置与快照**：
   - `LedgerTransactionWriter` 在 Room 事务内加载实时账户并执行 `LedgerValidator.requireValid(...)`，通过 `@Upsert` 原子写入 `transactions` + `postings` + `transaction_tags`（不触发附件/引用误级联），支持单笔、批量、作废与报销状态更新；
   - `TransactionDao`（配 `TransactionFilter` / `TagFilterMode.ANY|ALL` / `TransactionFilterSqlBuilder`）提供多维筛选、账户原币种余额、历史锁定本位币汇总、关联退款与待报销 Flow 查询；其余 12 个 DAO 覆盖全部表 CRUD，`ExchangeRateEntity` 列表支持 `.toExchangeRateTable()` 桥接；
   - `DefaultLedgerSeeder` / `DefaultSeedCatalog` 复用 `SystemAccounts` 确定性 ID 幂等预置 6 个系统权益科目、系统待报销户、5 个日常账户、一木风格 10 大类支出 / 3 大类收入二级分类与 7 个默认标签；
   - `DatabaseSnapshotExporter` 提供 `PRAGMA wal_checkpoint(TRUNCATE)` + 原子临时文件 `VACUUM INTO` + `PRAGMA integrity_check` + SHA-256 校验导出。

### M1.3 已落地的核心约定
1. **DI、仓储与启动预置 (`:app` & `:core:database`)**：
   - `:core:database` 新增 `LedgerRepository` / `LocalLedgerRepository`（统一封装账号/分类/标签/交易/汇率观察、原子期初余额建账、引用保护删除与幂等 `ensureSeeded`），并以 `api(libs.room.runtime)` / `api(libs.room.ktx)` 暴露 `RoomDatabase` 给 `:app`；`LedgerTransactionWriter` 补齐 `restoreTransaction`；
   - `:app` 新增 `LedgerDataModule`（`@Singleton` 绑定 `DropletDatabase`、13 个 DAO、`LedgerTransactionWriter`、`DefaultLedgerSeeder`、`LedgerEngine`、`LedgerRepository` 与 `@ApplicationScope`）；`DropletApplication.onCreate()` 异步幂等执行预置。
2. **UI 架构与可测性 (`:core:ui` & `:feature:ledger` & `:app`)**：
   - `:core:ui` 扩充 `DropletIcons` 精选矢量集并新增 `DropletIconRegistry`（字符串图标键与十六进制颜色解析 + 调色板），继续保持零 `material-icons-extended` 膨胀（Debug APK 13 MB）；
   - `:app` 采用 `DropletApp`（Hilt 生产入口）+ `DropletAppShell`（槽位注入无 Hilt 依赖，供 Roborazzi 截图测试使用）分层，所有新增页面同步提供 Stateless Screen + Hilt Route；
   - `LedgerViewModel` 在单个 `flatMapLatest { combine(transactionsFlow, summaryFlow) }` 中原子组合列表与汇总，彻底消除筛选切换时的跨帧数据错位；
   - `TransactionEditorScreen` 实现一木式直观表单（支出/收入/转账还款、大类→小类联动、跨币种实付+实收自动调用 `TradingAutoHedger.inferRatePair` 反推汇率、待报销/优惠/手续费）与一键切换专业多拆分录（实时调用 `LedgerValidator.computeCurrencyImbalances` 校验并提供一键补平），并支持已有流水编辑；
   - `AccountManagementScreen` 与 `CategoryTagManagementScreen` 从「我的」页直达，覆盖账户（含期初余额自动平衡入 `Equity:OpeningBalance:{CCY}`、计入总资产开关、信用额度/账单日/还款日/提醒天数）、二级分类与多标签完整 CRUD 及归档/恢复。

### M1.4 已落地的核心约定
1. **安全与隐私核心层 (`:core:security`)**：
   - `PrivacyPreferences` / `RelockTimeout`（`IMMEDIATE` / `SECONDS_30` / `MINUTE_1` / `MINUTES_5` / `NEVER`，默认 `MINUTE_1`）/ `AppThemePreference` + `DataStorePrivacyPreferencesRepository`（通过 `PreferenceDataStoreFactory.create` 持久化，支持 `IOException` 回退与未知枚举安全兜底）；
   - `AppLockStateMachine`（注入 `MonotonicClock`，覆盖冷启动锁定、切后台 5 档超时边界、系统锁屏兜底 Activity 切后台保活、取消/失败重试与负时长 fail-closed）；
   - `AndroidBiometricAuthenticator`（封装 Android 12+ `BiometricPrompt`，放行 `BIOMETRIC_STRONG | BIOMETRIC_WEAK | DEVICE_CREDENTIAL` 无负按钮，内置 `BiometricCapability` 含无指纹硬件仅锁屏密码回退检测）；
   - `KeystoreSecretCipher`（`AES/GCM/NoPadding` 256-bit 随机 IV + AAD 上下文绑定，二进制头 `[v1][ivLen][iv][ciphertext+128bit tag]` Base64 编码，脱敏 `SecretCipherException`，严禁日志打印明文）。
2. **宿主集成与隐私设置 (`:app` & `:feature:settings`)**：
   - `MainActivity` 继承 `FragmentActivity`，`:app` 新增 `SecurityModule` 与进程级单例 `AppLockCoordinator`（在 `DropletApplication.onCreate()` 注册 `ProcessLifecycleOwner` 监听前后台切换，配置变更不误锁），并动态响应 `WindowManager.LayoutParams.FLAG_SECURE`；
   - `DropletApp` 拆分受控 `DropletAppContent` + 生产 `DropletApp`（DataStore 持久化）+ 测试 `DropletAppShell`；冷启动未完成 DataStore 首帧加载前仅渲染底色、未解锁前不挂载账本树，全屏 `AppLockOverlay` 拦截返回键与触摸穿透；
   - `:feature:settings` 接入 Hilt，新增 `PrivacySecurityRoute` / `PrivacySecurityScreen` / `PrivacySecurityViewModel`（本机鉴权能力检测与系统安全设置直达、开启应用锁前身份校验防误锁、5 档重锁切换、立即锁定、`FLAG_SECURE` 与默认隐身开关）。

### M2.1 已落地的核心约定
1. **纯 JVM 网络与 Provider 链 (`:core:network`)**：
   - 保持零 Android 依赖与零浮点解析（全链路经 `NetworkDecimalParser` 从 `JsonPrimitive.content` 直接构造 `BigDecimal`，规范输出 8 位小数）；
   - `NetworkProxyConfig`（`NONE`/`HTTP`/`SOCKS`，校验 host/port、拒收凭据与 Scheme、`toString()` 脱敏）+ `DropletHttpClientFactory`（共享连接池按代理缓存、支持 `NetworkProxyConfigProvider` 动态切换、GB18030/UTF-8 编码解码、统一超时与协程取消联动）+ `RefreshThrottlePolicy`（默认 5 分钟窗口 + `forceRefresh`）；
   - `ExchangeRateProviderChain`（`EastMoneyExchangeRateProvider` → `FrankfurterExchangeRateProvider` → `OpenExchangeRateApiProvider`）统一产出 CNY/USD/HKD 全部 6 组有向 `CachedExchangeRate`（16 位中间精度、8 位规范输出）；
   - `MarketQuoteProviderChain` 覆盖全部 9 类 `AssetClass`（A股/ETF：东方财富→腾讯→新浪；公募基金：天天基金 JSONP→东方财富官方净值；港美股：东方财富→腾讯→Yahoo；黄金：国内 `AU9999/AUTD` 东方财富→新浪 `gds_` 严守 CNY/克，国际 `XAU` 东方财富→新浪 `hf_XAU`→`SpotXauQuoteProvider` 严守 USD/盎司；加密：OKX→Binance→CoinGecko 含 `USDT` 路由；`FIXED_DEPOSIT` 与无公开代码 `BANK_WEALTH` 零请求跳过），支持 `quoteProviderHint` 优先与 `Semaphore` 并发限流 + 单标的失败隔离。
2. **缓存降级仓库、代理持久化与 DI (`:core:database` & `:core:security` & `:app`)**：
   - `:core:security` 新增 `NetworkProxyPreferencesRepository` / `DataStoreNetworkProxyPreferencesRepository`（独立 `droplet_network_proxy_preferences.preferences_pb`，仅持久化脱敏 `proxyType`/`host`/`port`）；
   - `:core:database` 新增 `MarketDataRepository` / `LocalMarketDataRepository`（衔接 Room `ExchangeRateDao` / `MarketQuoteDao` / `InvestmentAssetDao`，支持 5 分钟单标的/汇率节流、原子事务写入、失败完整保留旧缓存与 `fetchedAt`、`manualOverrideQuote` / `clearManualOverride` 及手动覆写保护策略）；
   - `:app` 新增 `NetworkModule` 与 `MarketDataEntryPoint`、`AndroidManifest.xml` 补齐 `INTERNET` 与 `ACCESS_NETWORK_STATE` 权限，并在 `SecurityModule` 绑定 `@NetworkProxyDataStore`。

### M2.2 已落地的核心约定
1. **纯 JVM 投资持仓与定存/理财计算引擎 (`:core:domain`)**：
   - 新增 `io.github.kvtttba.droplet.core.domain.investment`（严守零浮点，全量 `Money` 4 位定点 `Long` + `BigDecimal` 8 位规范小数）：
     - `PortfolioPositionEngine`：按 `(occurredAt, createdAt, id)` 确定性回放 `POSTED` `INVEST` 流水，维护持仓份额（支持加密货币 8 位小数）、加权平均成本价、剩余成本、累计已实现盈亏、累计现金分红与交易/税费，防超卖抛出 `OversellPositionException`，并支持行情币种 `priceCurrency` 与持仓币种不一致时通过 `ExchangeRateProvider` 自动换算及缺行情成本价回退；
     - `FixedDepositCalculator`：基于 **Actual/365** 日计基准与全 `BigDecimal` 运算支持 `SIMPLE_AT_MATURITY`、`COMPOUND_ANNUAL`（整年复利 + 残余天数线性计息）与 `MONTHLY_PAYOUT`（按月付息扣除已付利息防双计），提供到期结息、部分/全额提前支取（含违约金）与按期付息双分录构造辅助；
     - `BankWealthValuationHelper`：支持公开净值（`PUBLIC_NAV`）、手动覆写净值、最新收益金额（含破净负收益）与最新总市值反推 8 位规范净值并保留审计时间戳 `fetchedAt`；
     - `PortfolioSummaryAggregator`：汇总全部资产/负债/信用（含溢缴款）/贷款/往来/系统待报销账户与投资持仓，自动以实时/应计市值替代 `INVEST_HOLDING` 账面成本杜绝双计，严格遵循 `account.includeInNetWorth` 开关，输出 `CNY / USD / HKD` 三币种分布、5 大资产分组与投资组合总盈亏，保证 `netWorth == totalAssets - totalLiabilities` 且分币种加总零误差对齐。
2. **投资业务仓储、DAO 与 Hilt DI (`:core:database` & `:app`)**：
   - `TransactionDao` 与 `InvestmentAssetDao` 补充按投资标的索引的 `INVEST` 流水查询与计数（Room Schema `v1` 零变更）；`core/database/build.gradle.kts` 将 `:core:model` 与 `:core:domain` 升级为 `api` 以向业务模块透出仓储领域返回模型；
   - 新增 `InvestmentRepository` / `LocalInvestmentRepository`，封装标的新建（自动联动或绑定 `INVEST_HOLDING` 账户）/编辑/归档/安全删除，买/定投/卖（自动结转加权成本并将收益路由至 `投资收益`、亏损路由至 `投资亏损`，含回溯时间线防超卖校验）/现金分红/定存开立与结息/按期付息/银行理财手动净值或收益更新的原子复式写入，以及响应式 `Flow` 持仓与资产总览查询（离线无缓存汇率时通过 `FallbackExchangeRateProvider` 回退参考汇率）；
   - `:app` 新增 `InvestmentModule` 与 `InvestmentEntryPoint` 完成单例绑定与编译期依赖图校验。

### M2.3 已落地的核心约定
1. **资产总览与投资持仓闭环 (`:feature:assets` & `:app`)**：
   - `:feature:assets` 接入 Hilt/KSP 与 `:core:database`、`:core:security`、`:core:network`，继续保持 Stateless Screen + Hilt Route 分层（`DropletAppShell` 同步提供 `assetsPreviewState` / `investmentDetailPreviewState` / `marketSyncPreviewState` 无 Hilt 槽位）；
   - `AssetsScreen` / `AssetsRoute` / `AssetsViewModel` 实时订阅 `InvestmentRepository.observePortfolioSummary(displayCurrency)` 与 `MarketDataRepository`，支持 CNY/USD/HKD 显示币种切换、真实三币种分布条、`PullToRefreshBox` + 顶部一键刷新、进页 5 分钟节流与失败保留缓存提示、不计入总资产折叠分区，以及 `CreateInvestmentAssetSheet`（全 9 类资产、定存年利率 `%` 规范转 8 位小数及首笔本金原子开立、自动新建或复用 `INVEST_HOLDING` 账户）；
   - `InvestmentDetailScreen` / `InvestmentDetailRoute` / `InvestmentDetailViewModel`（`DropletRoutes.INVESTMENT_DETAIL_PATTERN`）与 `InvestmentDetailSheets` 完整覆盖 8 位加密份额、加权平均成本、浮盈/已实现/分红/税费/扣费净总回报、买入/定投/卖出（基于 `PortfolioPositionEngine.previewSellFromState` 的零浮点实时结转预览与 `OversellPositionException` 超卖拦截）/现金分红/定存结息与提前支取与按期付息、行情手动覆写与清除、银行理财按净值/收益金额（支持负收益破净）/总市值反推净值及标的历史流水。
2. **WorkManager 后台定时行情同步与网络代理设置 (`:core:security` & `:app` & `:feature:settings`)**：
   - `:core:security` 新增 `MarketSyncInterval`（`MANUAL_ONLY` / `HOURS_6` / `HOURS_12` / `DAILY`，默认 `HOURS_12`）、`MarketSyncPreferences` 与 `MarketSyncPreferencesRepository` / `DataStoreMarketSyncPreferencesRepository`（独立 `droplet_market_sync_preferences.preferences_pb`，`SecurityModule` 经 `@MarketSyncDataStore` 绑定）；
   - `:app` 新增 `MarketRefreshWorker`（`CoroutineWorker` 通过 `EntryPointAccessors` 访问 `MarketDataEntryPoint.marketDataRepository()` 执行 5 分钟节流 `refreshAll(forceRefresh = false)`，失败按重试上限 `Result.retry()`）与 `MarketSyncScheduler`（`ExistingPeriodicWorkPolicy.UPDATE` + `NetworkType.UNMETERED`/`CONNECTED` 约束，`MANUAL_ONLY` 时取消唯一周期任务），并在 `DropletApplication.onCreate()` 持续订阅 `MarketSyncPreferencesRepository.preferences` 自动重调度；
   - `:feature:settings` 新增 `MarketSyncSettingsScreen` / `MarketSyncSettingsRoute` / `MarketSyncSettingsViewModel`（`DropletRoutes.MARKET_SYNC_SETTINGS`），打通自动刷新周期、仅 Wi-Fi 开关、立即强制刷新与 `NetworkProxyPreferencesRepository`（NONE / HTTP / SOCKS 与主机/端口校验及脱敏存储），`SettingsScreen` 与 `MainActivity` 实时展示当前同步与代理摘要。

### M3.1 已落地的核心约定
1. **纯 JVM 信用卡账单与还款引擎 (`:core:domain`)**：
   - 新增 `io.github.kvtttba.droplet.core.domain.credit`（零浮点，全量 `Money` 4 位定点 `Long` + `BigDecimal`）：
     - `CreditCardStatementCalculator`：结合 `Account` 已有字段（`creditLimitMinor`、`billingDay`、`dueDay`、`remindDaysBefore`），实现 29–31 号月末安全夹紧（含闰年/平年 2 月与 30 天月份）、同月（`dueDay > billingDay`）及跨月/跨年（`dueDay <= billingDay`）还款日推导，输出上期已出账单区间、本期未出账单区间、距出账/还款倒计时、`[0, effectiveRemindDaysBefore]` 提醒窗口（含逾期）、已用/可用额度（溢缴款增加可用额度、超限夹紧 0）、使用率百分比、出账后还款/退款冲减不变量 `remainingStatementDue + unbilledRemainingDebt == currentDebt` 与 5 种账单状态（`UNCONFIGURED`/`PAID_OFF`/`PENDING`/`DUE_SOON`/`OVERDUE`）；
     - `CreditCardRepaymentHelper`：复用 `LedgerEngine` 构造借贷恒平 `TRANSFER` 还款分录，支持按本期应还（`STATEMENT_DUE`）、全额欠款（`FULL_BALANCE`）、自定义金额（`CUSTOM`）、可选手续费与跨币种 `Equity:Trading:{CCY}` 自动对冲。
2. **信用卡仓储、提醒偏好与 UI 闭环 (`:core:database` & `:core:security` & `:core:ui` & `:feature:assets` & `:feature:settings`)**：
   - `AccountDao` 与 `TransactionDao` 补齐信用卡与按账户流水响应式查询（Room Schema `v1` 零变更），新增 `CreditCardRepository` / `LocalCreditCardRepository`（账单摘要/详情 Flow、快照查询、原子 `recordRepayment` 与 `updateCreditCardParameters`）；
   - `:core:security` 新增 `CreditCardReminderPreferences` / `DataStoreCreditCardReminderPreferencesRepository`（独立 `droplet_credit_card_reminder_preferences.preferences_pb`，由 `SecurityModule` `@CreditCardReminderDataStore` 绑定，存储总开关、默认提前提醒天数与按卡同日防重日期戳）；
   - `:core:ui` 新增可复用 `rememberNotificationPermissionController`（封装 Android 12/13+ `POST_NOTIFICATIONS` 运行时请求与系统通知渠道设置直达）；
   - `AssetsScreen` / `AssetsViewModel` 将信用账户分组前置紧接 Hero 卡片展示真实额度进度条、已用/可用额度、距出账/还款倒计时与本期应还并直达 `CreditCardDetailScreen`（`DropletRoutes.CREDIT_CARD_DETAIL_PATTERN`，含 `已出账单`/`未出账单`/`全部流水` 切换、一键立即还款自动预填底部表单、信用参数快捷编辑、提醒与权限引导）；`:feature:settings` 新增 `CreditCardReminderSettingsScreen`（`DropletRoutes.CREDIT_CARD_REMINDER_SETTINGS`）并接入设置首页入口。
3. **本地通知与离线定时扫描 (`:app`)**：
   - `AndroidManifest.xml` 声明 `POST_NOTIFICATIONS` 并新增单色图标 `ic_stat_droplet`；
   - 落地 `CreditCardReminderNotificationPublisher`（渠道 `droplet_credit_card_reminders`、Android 13+ 权限校验、遵循全局 `PrivacyPreferences.amountsHidden` 自动隐藏通知金额、点击 `PendingIntent` 携带 `EXTRA_CREDIT_CARD_ACCOUNT_ID` 深链直达对应卡片详情页）；
   - 落地 `CreditCardReminderWorker`（经 Hilt `CreditCardReminderEntryPoint` 扫描到期窗口仍有待还金额的信用卡并按卡同日防重复推送）、`CreditCardReminderScheduler`（WorkManager 24h 离线唯一周期任务，关闭开关自动取消）与 `CreditCardModule`，并在 `DropletApplication.onCreate()` 初始化渠道及持续订阅提醒配置自动重调度。

### M3.2 已落地的核心约定
1. **纯 JVM 分期贷款计划表与还款引擎 (`:core:domain`)**：
   - 新增 `io.github.kvtttba.droplet.core.domain.loan`（零浮点，全量 `Money` 4 位定点 `Long` + `BigDecimal` 高精度中间计算）：
     - `LoanScheduleCalculator`：基于 `:core:model` 的 `LoanPlan` / `LoanInstallment` / `LoanMethod`，完整支持等额本息（`EQUAL_INSTALLMENT`）、等额本金（`EQUAL_PRINCIPAL`）与信用卡/消费固定手续费分期（`FIXED_FEE`，支持按每期固定金额或年化费率换算），含 29–31 号月末安全夹紧（平/闰年 2 月与 30 天月份）、末期本金尾差平齐不变量（`SUM(installment.principalMinor) == totalPrincipalMinor` 且末期 `remainingPrincipalMinor == 0`）、提前结清与剩余本息汇总；
     - `LoanRepaymentHelper`：复用 `LedgerEngine.buildLoanRepayment(...)` 构造每期借贷恒平 `LOAN_REPAY` 复式分录（本金冲减 `Liability:Loan`、利息/手续费计入 `Expense:Interest`、付款账户扣减当期总额），支持 `TransactionSource.MANUAL` 与 `TransactionSource.LOAN_AUTO`。
2. **分期贷款仓储、防重偏好与资产/详情 UI 闭环 (`:core:database` & `:core:security` & `:feature:assets`)**：
   - 保持 Room Schema `v1` 零变更，扩展 `LoanDao`（新增 `markInstallmentPaidIfPending` 原子条件更新防并发双入账、`getPendingUnpostedInstallmentsUpTo` 等查询）与 `LoanRepository` / `LocalLoanRepository`：新建分期贷款自动联动创建 `AccountSubType.LOAN` 负债户并写入平衡期初分录（使 `PortfolioSummaryAggregator` 即刻体现负债）及全量逐期计划表；支持逐期手动确认还款、到期幂等自动入账（`PENDING && transactionId == null`）、提前结清（剩余本金一次性冲平并保留实际已付利息与违约金）、归档与安全删除；
   - `:core:security` 新增 `LoanReminderPreferences` / `DataStoreLoanReminderPreferencesRepository`（独立 `droplet_loan_reminder_preferences.preferences_pb`，经 `SecurityModule` `@LoanReminderDataStore` 绑定，记录每期次同日已推送日期防重）；
   - `AssetsScreen` / `AssetsViewModel` 在「信用账户」下方落地「分期贷款」分区（剩余期数、待还本金、下期到期日与金额、到期状态胶囊）与 `CreateLoanPlanSheet` 新建表单，直达 `LoanDetailScreen` / `LoanDetailRoute` / `LoanDetailViewModel` / `LoanDetailSheets`（`DropletRoutes.LOAN_DETAIL_PATTERN`，含逐期计划表、单期一键入账、提前结清、参数编辑/归档/安全删除、Android 13+ 通知权限引导与 `DropletAppShell` 预览槽位）。
3. **每日离线自动入账与到期提醒 (`:app`)**：
   - 新增通知渠道 `droplet_loan_reminders`、`LoanReminderNotificationPublisher`（遵循全局 `PrivacyPreferences.amountsHidden` 隐藏金额，点击携带 `EXTRA_LOAN_PLAN_ID` 深链直达贷款详情页）、`LoanScheduleWorker`（经 Hilt `LoanScheduleEntryPoint` 每日离线扫描并执行 `autoPostDueInstallments` 与提醒窗口推送）、`LoanScheduleScheduler`（WorkManager 24h 离线唯一周期任务）与 `LoanModule`，在 `DropletApplication.onCreate()` 初始化渠道并自动保活调度。

### M3.3 已落地的核心约定
1. **纯 JVM 往来借贷、待报销与多次退款引擎 (`:core:domain`)**：
   - 新增 `io.github.kvtttba.droplet.core.domain.debt`（严守零浮点，全量 `Money` 4 位定点 `Long` + `BigDecimal`）：
     - `DebtSummaryCalculator` & `DebtTransactionHelper`：按往来人 `DebtProfile` 聚合应收（`RECEIVABLE`）、应付（`PAYABLE`）与净往来余额、累计已收/已还本金、累计利息收支、累计坏账核销及约定还款日倒计时与提醒窗口；`buildCollectTransaction`/`buildRepayTransaction` 强制要求本金为正以确保绑定往来人流水始终命中应收/应付户并借贷恒平；
     - `ReimbursementCalculator` & `ReimbursementSettlementHelper`：按原垫付流水聚合全部 `POSTED` 关联冲抵（排除 `EQUITY` 对冲行），推演 `PENDING`/`PARTIAL`/`SETTLED` 状态与剩余待报销额，支持单笔/批量冲抵及「实报少于垫付 → 差额转个人支出 / 实报超出 → 转补贴收入」的恒平分录构造；
     - `RefundCalculator` & `RefundHelper`：按原 `POSTED` 支出汇总历史全部关联退款，计算累计已退、剩余可退上限与原单净支出，防止超额退款并支持跨币种退款。
2. **Room 仓储、状态同步与防重提醒 (`:core:database` & `:core:security` & `:app`)**：
   - 保持 Room Schema `v1` 零变更，扩展 `LoanAndDebtDao`、`TransactionDao`（关联退款与待报销聚合查询）及 `LocalLedgerRepository`（作废/恢复/删除报销冲抵流水时在同事务内自动重算父垫付单 `reimburseState`）；
   - 新增 `DebtRepository` / `LocalDebtRepository`（往来人新建自动创建或绑定 `RECEIVABLE`/`PAYABLE` 账户并接入 `PortfolioSummaryAggregator` 净资产汇总、借出/借入/分次收还款含息/坏账核销、归档与安全删除）与 `ReimbursementAndRefundRepository` / `LocalReimbursementAndRefundRepository`（待报销总览、单笔/批量原子冲抵与跨币种多次退款）；
   - `:core:security` 新增 `DebtReminderPreferences` / `DataStoreDebtReminderPreferencesRepository`（`@DebtReminderDataStore`）；`:app` 新增 `DebtModule`、`droplet_debt_reminders` 通知渠道、`DebtReminderNotificationPublisher`（遵循全局隐私隐身与 `EXTRA_DEBT_PROFILE_ID` 深链）、`DebtReminderWorker` 与 `DebtReminderScheduler`。
3. **资产/借贷/报销详情与明细退款 UI 闭环 (`:feature:assets` & `:feature:ledger` & `:app`)**：
   - `AssetsScreen` / `AssetsViewModel` 新增「往来借贷」（应收/应付汇总与往来人卡片、新建往来人表单）与「待报销」分组入口，直达 `DebtDetailScreen`（`DropletRoutes.DEBT_DETAIL_PATTERN`）与 `ReimbursementListScreen`（`DropletRoutes.REIMBURSEMENT_LIST`，支持单笔冲抵差额自付预览与批量勾选一键到账）；
   - `LedgerScreen` / `LedgerViewModel` / `LedgerUiModels` 接入实时退款与报销摘要流：在支出行展示退款徽标与净支出副行、详情页内置 `ExpenseRefundDetailCard` + 非堆叠式 `RecordRefundBottomSheet`（支持全额/部分/多次/跨币种退款）以及 `ReimbursementDetailCard` 快捷跳转；`DropletApp` 改进跨页 Snackbar 替换与进入记账页前清屏，避免旧提示遮挡底部保存按钮。

### M4.1 已落地的核心约定
1. **AI 配置模型与 Keystore 加密持久化 (`:core:security` & `:app`)**：
   - 新增 `AiEndpointSlot`（`DEFAULT` / `VISION` / `TEXT` / `VOICE`，携带独立 AAD `ai_api_key:{SLOT}`）、`VoiceEngineMode`（`ANDROID_SYSTEM` 默认 / `CUSTOM_AI`）、`AiEndpointConfig`、`AiEffectiveEndpointMetadata`、`AiServiceConfig`、`AiResolvedEndpoint`、`AiEndpointSaveInput` / `AiApiKeyUpdateAction`（`KeepExisting` / `Replace` / `Clear`）、`AiSecretMasker` 与 `AiEndpointValidator`（拒收非 http(s)、内嵌凭据、空白及查询参数），全部敏感对象的 `toString()` 严格脱敏；
   - `DataStoreAiConfigPreferencesRepository` 绑定独立 `droplet_ai_config_preferences.preferences_pb`（经 `SecurityModule` `@AiConfigDataStore` 注入）：写入前强制经既有 `KeystoreSecretCipher`（AES-256-GCM + 按槽位 AAD）加密，UI 态仅持有掩码预览（如 `••••0001`）从不持有明文，请求执行前通过 `resolveEndpointForRequest(slot)` 按需在内存解密；支持覆盖开关关闭时回退 `DEFAULT`，开启时可完整独立覆盖或留空 `BaseURL`/`API Key` 仅覆写 `Model` 自动继承默认配置；跨槽位 AAD 错配或 Keystore 失效安全标记 `isKeyCorrupted = true` 而不崩溃。
2. **隐私最小化 Prompt、零浮点容错 JSON 解析与 OpenAI Compatible 客户端 (`:core:ai` & `:app`)**：
   - `AiPromptContext.fromCatalog(accounts, tags, ...)` 自动过滤系统/归档/权益账户且不读取任何余额或信用额度；`AiPromptBuilder` 严格遵循 `ARCHITECTURE.md §5.2` 仅注入当前 ISO-8601 时间/时区/本位币 + 活跃账户名称/类型/币种 + 二级分类路径 + 活跃标签名 + `{"items":[...]}` Schema 契约；
   - `AiDraftJsonParser` 容错剥离 `<think>...</think>`、Markdown 代码块与前后解释文本，兼容 `{"items":[...]}`、别名键、裸数组与单对象；严格经 `JsonPrimitive.content → BigDecimal → Money(scale=4)` 零浮点解析并拦截 `NaN`/`Infinity`/非正/溢出金额；支持多格式时间戳与缺失回退、三币种与符号归一、本地分类（精确/分隔符/叶节点/模糊）/账户/目标账户/标签智能匹配，未命中或模糊字段自动标入 `uncertainFields` 并给出零浮点 `confidenceDecimal` 与 `needsUserReview`，输出可序列化的 `AiParsedDraftBatch` / `AiParsedTransactionCandidate` 供 M4.2 直接落库与批量确认；
   - `OpenAiEndpointUrlNormalizer` 兼容裸域名自动补 `/v1`、尾斜杠、自定义网关前缀及误粘完整路径；`OkHttpOpenAiCompatibleClient` 复用 `DropletHttpClientFactory`（自动继承 M2.1/M2.3 代理）并经 `AiErrorSanitizer` 清洗全部错误消息防止回显密钥，完整实现 `testConnection`（`GET /models` + 404/405/501 降级轻量 `POST /chat/completions`）、`parseText`（400 不支持 `response_format` 自动降级重试）、`parseImages`（多图 `image_url` data URI）与 `transcribeAudio`（multipart `/audio/transcriptions`）；`:app` 通过 `AiModule` 与 `AiEntryPoint` 完成单例装配与编译期依赖图校验。
3. **AI 服务配置 UI 与四层验证 (`:feature:settings` & `:app`)**：
   - 新增 `AiSettingsScreen` / `AiSettingsRoute` / `AiSettingsViewModel`（`DropletRoutes.AI_SETTINGS`，Stateless Screen + Hilt Route，`DropletAppShell` 提供 `aiSettingsPreviewState` 槽位）：包含隐私最小化安全说明横幅、默认全局配置卡（含密钥显隐/已加密掩码提示/清除密钥/实时连通性测试与结果胶囊）、语音转写引擎模式切换与视觉/文本/语音 3 个独立覆盖槽位开关卡；`SettingsScreen` 与 `MainActivity` 接入「AI 服务配置」入口与实时状态摘要；Room Schema `v1` 保持零变更。

### M4.2 已落地的核心约定
1. **票据图片去 EXIF 处理、长图切片与零浮点草稿入账映射 (`:core:ai`)**：
   - `core/ai/build.gradle.kts` 接入 `api(projects.core.domain)`，新增 `AiDraftAttachmentRef` 与封装了 `Json` 编解码边界的 `AiDraftBatchCodec`（避免跨模块泄露 `kotlinx.serialization.json.Json`）；
   - `ReceiptImageProcessor` / `AndroidReceiptImageProcessor` 使用平台内置 `ExifInterface` 仅在内存读取方向并经 `BitmapFactory` 子采样缩放与 `Bitmap.compress(JPEG)` 像素重编码，100% 彻底剥离 GPS/相机型号/时间戳等 EXIF 隐私元数据；落盘至 `filesDir/receipts/` 并计算 SHA-256，针对账单长截图（高宽比 `>= 2.4` 且高度超过切片阈值）自动生成带重叠区域的垂直分段 `AiImagePayload` 列表以保文字清晰度，同时拦截 `..`/绝对路径穿越；
   - `AiDraftTransactionMapper` 严格零浮点，将审核后的 `AiParsedTransactionCandidate`（支持单/跨币种 `EXPENSE`/`INCOME`/`TRANSFER`/`REIMBURSE`）交由 `LedgerEngine` 构造借贷恒平 `Transaction`（自动补齐 `Equity:Trading:{CCY}` 与 `TransactionSource.AI_IMAGE`/`AI_VOICE`/`AI_TEXT`），并在校验失败时返回精准中文提示。
2. **离线 AI 草稿仓储与全链路编排 (`:core:database` & `:app`)**：
   - 保持 Room Schema `v1` 零变更，扩展 `AiDraftDao`（`observeById`、`observeCountByStatuses`、`deleteByStatus`）、`AttachmentDao`（`countByLocalRelPath`）与 `AccountDao`（`getAll`），`core/database/build.gradle.kts` 接入 `api(projects.core.ai)`；
   - `LocalAiDraftRepository` 管理活跃草稿箱流（`PENDING_ONLINE`/`PARSING`/`READY`/`FAILED`）、编辑持久化与安全删除（仅当未入账且无 `attachments` 引用时清理本地票据文件）；`commitDraftBatch` 在单个 Room `withTransaction` 内加载含系统 `Trading`/`Reimbursement` 在内的全量账户，经 `AiDraftTransactionMapper` + `LedgerTransactionWriter.saveTransactions` + `AttachmentDao.upsertAll` + 标记 `COMMITTED` 原子执行，任意单笔校验失败全批回滚；
   - `AiCaptureOrchestrator` 串联隐私最小化 `AiPromptContext.fromCatalog`、`OpenAiCompatibleClient`（文本/视觉/Whisper 音频转写）与离线/异常降级暂存，并支持失败草稿一键重试及离线草稿 `convertDraftToManualReview`（正则零浮点提取金额并标记 `uncertainFields` 转人工核对）；`:app` `AiModule` 与 `AiEntryPoint` 补齐全部单例绑定。
3. **全场景智能记账 UI、批量确认页与系统分享 (`:feature:ai_capture` & `:app`)**：
   - `:feature:ai_capture` 接入 Hilt/KSP/Compose，全面替换 `QuickCaptureModalSheet` 占位提示（新增「草稿箱 · N」角标直达），落地 Stateless + Hilt Route 分层的 `AiCaptureScreen`（一句话自然语言多笔 / `FileProvider` 拍照 + `PickMultipleVisualMedia` 多图与长图预览 / `ANDROID_SYSTEM` 系统语音 `RecognizerIntent` 与 `CUSTOM_AI` `MediaRecorder` 应用内录音双模式 + 离线草稿箱）与 `AiDraftReviewScreen`（低置信度与 `uncertainFields` 琥珀色高亮、就地微调分类/账户/金额/币种/标签/商户备注、`PrivacyAmountText` 全局金额隐身与底部一键原子批量入账）；
   - `AndroidManifest.xml` 配置 `RECORD_AUDIO`（不强制硬件 `uses-feature`）、`FileProvider`（`res/xml/file_paths.xml`）及 `ACTION_SEND`（`image/*`、`text/plain`）/ `ACTION_SEND_MULTIPLE`（`image/*`）；`MainActivity`（`singleTop`）经 `ShareIntentParser` 在 `onCreate`/`onNewIntent` 安全接收分享内容，`DropletApp` 仅在 `!isAppLocked` 后自动导航至 `AiCapture` 预载入，绝不绕过生物识别锁；`DropletAppShell` 补充 `aiCapturePreviewState` 与 `aiDraftReviewPreviewState` 预览槽位。

### M5.1 启动入口契约（预算管理与预警、周期记账与模板）
1. 从 `main` 切出分支 `feat/m5-1-budget-recurring-templates`，更新 `app/build.gradle.kts` 中 `versionName = "0.1.0-m5.1"`。
2. **纯 JVM 领域引擎 (`:core:domain`)**：
   - 保持零浮点铁律（全量 `Money` `scale = 4` + `BigDecimal`），新增 `io.github.kvtttba.droplet.core.domain.automation`：
     - `BudgetProgressCalculator`：基于 `:core:model` 已有 `Budget`（`MONTHLY`/`YEARLY`、`categoryAccountId = null` 表示总预算或指向支出大类/小类、`limitAmountMinor`、`warnThresholdPct`），结合当期历史锁定支出（自动扣除关联退款净支出、大类预算自动聚合旗下全部子分类支出），计算已用金额、剩余金额、已用百分比、剩余天数与**日均可用额度**，并判定状态（`HEALTHY` / `WARNING` ≥ 预警阈值 / `EXCEEDED` ≥ 100%）；
     - `RecurringScheduleCalculator`：基于 `:core:model` 已有 `RecurringRule`（`DAILY`/`WEEKLY`/`MONTHLY`/`YEARLY` 及 `intervalCount`、`nextRunAt`、`endAt`），严格处理月末 29–31 号安全夹紧（平/闰年 2 月与小月），计算下一执行时间与待补齐的到期次数；
     - `TemplatePayload` 模型与序列化契约（定义标准支出/收入/转账模板负载：类型、金额、币种、分类 ID、账户 ID、目标账户 ID、商户、标签 ID 列表、备注，供 `RecurringRule.templatePayloadJson` 与 `TransactionTemplate.templatePayloadJson` 复用）。
3. **Room 仓储与本地预警调度 (`:core:database` & `:core:security` & `:app`)**：
   - **保持 Room Schema `v1` 零变更**，复用既有 `BudgetDao`（`budgets`）、`RecurringRuleDao`（`recurring_rules`）与 `TemplateDao`（`templates`），新增 `BudgetRepository`、`RecurringRuleRepository` 与 `TemplateRepository`：
     - 预算 CRUD 与响应式当期消耗进度 Flow（聚合 `TransactionDao` 当月/当年支出与退款净额）；
     - 周期规则 CRUD、暂停/恢复、到期一键执行入账（复用 `LedgerEngine` 写入 `TransactionSource.RECURRING` 并推进 `lastRunAt`/`nextRunAt`，原子防重复入账）；
     - 快捷模板 CRUD、按常用度/排序观察与一键直接入账（同步 `TemplateDao.recordUsage`）；
   - `:core:security` 新增 `AutomationAlertPreferencesRepository`（独立 DataStore 记录预算 80%/100% 周期内防重推送标记与总开关）；
   - `:app` 新增通知渠道 `droplet_budget_recurring_alerts`、`AutomationAlertNotificationPublisher`（遵循 `PrivacyPreferences.amountsHidden` 隐私隐身）、WorkManager `RecurringAndBudgetWorker`（离线执行 `autoPost = true` 周期规则自动入账、待确认周期提醒与预算超阈预警）与 `AutomationScheduler`，并在 `DropletApplication.onCreate()` 注册保活。
4. **UI 闭环与入口打通 (`:feature:settings` & `:feature:ledger` & `:feature:stats` & `:app`)**：
   - 落地 `BudgetManagementScreen`（月/年总预算与分类预算进度条、日均可用额度、预警阈值配置、新建/编辑/启停/删除）；
   - 落地 `RecurringAndTemplateScreen`（周期规则列表/新建/暂停/手动立即执行 + 快捷模板列表/新建/一键记一笔或带入记账表单）；
   - 在 `SettingsScreen`（「我的」页）、`LedgerScreen`（首页当月总预算紧凑进度条 + 快捷模板横滑入口或快捷记账联动）打通直达入口；所有金额接入 `PrivacyAmountText` / `LocalAmountsHidden`，`DropletAppShell` 补充预览槽位。
5. **测试与交付门禁（`ccarm` + `pu`）**：
   - 补齐 L1/L2 测试（`BudgetProgressCalculator` 大类聚合/退款净支出/日均可用、`RecurringScheduleCalculator` 月末夹紧与跨年、Room 仓储原子执行与防重、WorkManager Worker 离线调度与 ViewModel 测试）；
   - 补齐 L3 Roborazzi 截图（预算管理页、周期与模板管理页黄金 PNG，更新 `settings_light.png` 并肉眼验收）；
   - 补齐 L4 API 31 E2E（在 `DropletAppE2ETest` 追加 `test08_...` 覆盖新建月度总预算并验证消费后实时进度更新、新建高频快捷模板一键入账及周期规则创建与手动执行）；
   - `gitleaks` + `pu` 全量单测/截图/APK + GitHub Actions 三项 CI 全绿后更新 `docs/PROGRESS.md`（写入 `M5.2 启动入口契约`）、`--no-ff` 合入 `main` 并 `droplet-pu sync`。

---

## 踩坑记录

- **[M0] OpenJDK 21 下纯 JVM 模块编译目标**：纯 JVM 模块不要写 `kotlin { jvmToolchain(17) }`（会触发下载独立 JDK 17），统一使用 `java.sourceCompatibility/targetCompatibility = JavaVersion.VERSION_17` + `kotlin.compilerOptions.jvmTarget = JvmTarget.JVM_17`。
- **[M0] 纯 JVM 单测与 Android Release 编译提速**：执行 `./gradlew :core:model:test :core:domain:test :core:network:test testDebugUnitTest`，避免根任务 `./gradlew test` 触发所有 Android 模块的 Release 单测编译。
- **[M0] Debug APK 体积控制**：`material-icons-extended` 会让未混淆 Debug APK 膨胀至 59 MB；改用 `core:ui` 精选矢量 `DropletIcons` + `packaging.dex.useLegacyPackaging = true` 后 Debug APK 降至 ~12–16 MB，便于飞书直接交付与真机快速安装。
- **[M0] Roborazzi + Hilt 兼容性**：纯 Compose 截图测试使用 `@Config(application = Application::class, sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi")` 跳过 `DropletApplication` 的 `@HiltAndroidApp` 初始化，配合 `RoborazziOptions.CompareOptions(changeThreshold = 0.015f)` 在本地 Debian 与 GitHub Actions `ubuntu-latest` 之间稳定通过。
- **[M0] 本机 GitHub PAT 权限**：本机 `gh` PAT 具备 `contents: write` 与 `workflows: write` 权限，未开 `pull_requests: write`，可通过 `feat/**` 分支推送直接触发 CI 验证，验收后合入 `main`。
- **[M1.1] 非交互 Shell 本地跑 Android Gradle 任务**：本机 Android SDK 位于 `/data/home/android-sdk`，非交互 Shell 需显式传 `ANDROID_HOME=/data/home/android-sdk`（切勿创建/提交 `local.properties`）。
- **[M1.2] Room 主表更新严禁使用 `OnConflictStrategy.REPLACE`**：SQLite 对 `REPLACE` 按 `DELETE + INSERT` 实现，会误触发 `attachments` 的 `ON DELETE CASCADE` 与 `transactions.ref_transaction_id` / `loan_installments.transaction_id` 的 `ON DELETE SET NULL`；主表更新必须统一用 `@Upsert`（`ON CONFLICT DO UPDATE`），仅精准替换子表分录与标签关联。
- **[M1.3] `:core:database` 对外暴露 `DropletDatabase` 需用 `api(libs.room.runtime)`**：`:app` 的 Hilt DI 模块直接返回 `DropletDatabase` 时，Kotlin 编译器需要解析其超类 `RoomDatabase`，因此在 `core/database/build.gradle.kts` 中应将 `room-runtime`/`room-ktx` 声明为 `api`。
- **[M1.3] Room Flow 筛选切换防闪烁 & Roborazzi 跨时区确定性**：多查询随筛选条件联动时，须在单个 `flatMapLatest` 内 `combine` 列表流与汇总流，避免旧结果与新筛选条件拼帧；涉及日期文本的 Roborazzi 测试须固定 `TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))` 并在 `@After` 恢复，确保本机与 UTC CI 像素一致；本机 8 GB 无 Swap 环境并行跑 Robolectric + Roborazzi 时建议加 `--max-workers=2`。
- **[M1.4] `BiometricPrompt` 设备锁屏兜底 & `AUTHENTICATING` 态切后台保活**：允许 `DEVICE_CREDENTIAL` 时严禁同时调用 `setNegativeButtonText`（否则抛出 `IllegalArgumentException`）；唤起系统 PIN/密码验证页会触发 `ProcessLifecycleOwner.ON_STOP`，`AppLockStateMachine` 在 `AUTHENTICATING` 态收到 `onAppBackgrounded()` 时须保持认证中状态，避免解锁成功回调被误重锁。
- **[M1.4] `AppLockOverlay` 触摸拦截与 Roborazzi 语义树 / DataStore 单测隔离 / gitleaks 熵检**：全屏遮罩拦截底层触摸应使用 `Modifier.pointerInput` 消费事件而非 `Modifier.clickable`（避免 `mergeDescendants = true` 合并子语义节点并干扰 TalkBack 与测试断言）；DataStore 单测须通过 `PreferenceDataStoreFactory.create` 绑定独立临时文件并在 `finally` 中 `cancelAndJoin()` 防止多实例冲突；公开仓测试样例字符串须避免高熵值紧邻 `secret`/`token` 变量名以防误触 `gitleaks` 的 `generic-api-key` 规则。
- **[M2.1] Hilt 未引用绑定校验与行情接口编码/零浮点**：在 UI 尚未注入的新底层 `@Module`（如 `NetworkModule`）上同步声明 `@EntryPoint`（`MarketDataEntryPoint`），可促使 Dagger/Hilt 在编译期即校验全部依赖绑定而非跳过未引用图；腾讯/新浪公开行情接口响应可能采用 GBK/GB18030 且新浪要求 `Referer` 头，解析 JSON 数值时统一从 `JsonPrimitive.content` 直接构建 `BigDecimal` 彻底杜绝浮点精度漂移；国内 SGE 黄金（CNY/克）与国际现货 XAU（USD/盎司）须按标的区分备用源，避免跨单位错配。
- **[M2.2] KSP2 跨模块新增接口缓存、8G 无 Swap 内存控制与防双计规则**：向 `:core:database` 新增被 `:app` Hilt `@Module` 引用的接口时，若复用历史长驻 Gradle Daemon 偶发 KSP2 `NoIsolationWorkerFactory` 读取旧 jar 索引报错，执行 `./gradlew --no-build-cache :core:database:clean :app:clean :app:kspDebugKotlin` 或重启 Daemon 即可刷新；本机 8 GB 无 Swap 环境下并行运行单测 + Roborazzi + `assembleDebug` 易触发 OOM Killer，建议分步执行并加 `--max-workers=1 -Dorg.gradle.jvmargs="-Xmx2048m -Dfile.encoding=UTF-8"`；总净资产汇总须用持仓实时/应计市值替换 `INVEST_HOLDING` 账面成本避免双计，定存仅在 `MONTHLY_PAYOUT` 模式下从当前应计利息扣除 `alreadyPaidInterest`，避免 `SIMPLE_AT_MATURITY` 部分提前支取的已结利息冲减剩余本金的正常计息。
- **[M2.3] Compose `clickable` 语义合并与 E2E `ModalBottomSheet` 稳定性**：`Modifier.clickable` 默认 `mergeDescendants = true`，当父容器与子 `Text` 同时设置 `testTag` 时合并语义树仅保留父节点 `testTag`（`first-wins`），故需将 E2E 稳定标识直接放在可点击容器上（或无合并目标文本节点上）；涉及底部弹窗提交的模拟器 E2E 须等待弹窗节点消失后再滚动底层 `LazyColumn`，且 `:core:model` 的 `DecimalCanonicalizer` 为 `internal`，外部模块统一通过 `UnitPrice.of(...)`/`Quantity.of(...)` 获取规范小数串。
- **[M3.1] WorkManager 离线调度单测与共享 DB 模拟器 E2E 确定性**：无约束、零延迟的周期性 WorkManager 任务在 Robolectric `SynchronousExecutor` 下会在 `enqueueUniquePeriodicWork` 时立即同步执行，在非 Hilt 测试 `Application` 中需为调度器单测注入 `NoOpWorker` `WorkerFactory` 隔离真实 Worker 依赖解析；`DropletAppE2ETest` 多个 `@Test` 共享同一 Hilt `@Singleton` Room 数据库（`droplet_ledger.db`），须保留 `@FixMethodOrder(MethodSorters.NAME_ASCENDING)` 并按 `test01_` / `test02_` / `test03_` 递增命名避免后续用例污染前置账目断言，同时对点击触发的异步 DataStore 状态回写使用 `composeRule.waitUntil` 等待生效。
- **[M3.2] `pu` 构建机 KSP2 缓存清理、Room 原子条件防重与 `LedgerFeatureViewModelsTest` 双流竞态**：在 `ccarm` 新增 `:core:database` / `:core:security` 接口并同步到 `pu` 后，若 `pu` 长驻 Gradle Daemon 报 KSP2 `PROCESSING_ERROR` 找不到新接口，先执行 `droplet-pu gradle-nosync --stop` 重启 Daemon 即可彻底恢复；分期到期自动入账与手动确认共用 `LoanDao.markInstallmentPaidIfPending`（`WHERE id = :id AND status = 'PENDING' AND transaction_id IS NULL`）实现数据库层原子幂等，避免并发双入账；`LedgerFeatureViewModelsTest` 删除流水后需同时等待 `transactionCount == 0 && monthlyExpense == Money.zero(Currency.CNY)`，消除 Room 多流组合的瞬时竞态。
- **[M3.3] 跨币种退款 Trading 科目加载、外层 Snackbar 遮挡底部保存栏与非堆叠 BottomSheet**：`LocalReimbursementAndRefundRepository.recordRefund` 传入 `accountsById` 给 `LedgerEngine.buildRefund` 时，必须同时加载 `SystemAccounts.tradingAccountId(...)`，否则跨币种退款自动插入隐式 Trading 分录会被 `LedgerValidator` 判为 `UnknownAccount`；外层 `DropletApp` `Scaffold` 的 `SnackbarHost` 若连续排队多条提示，在快速切入全屏 `TransactionEditorScreen` 时会悬停遮挡底部 `editor_save_button`，须在 `showMessage` 内先 `dismiss()` 旧提示并在进入记账页前清屏；涉及双层底部表单交互（如流水详情→退款表单）时严禁同时挂载两个 `ModalBottomSheet`，应按状态互斥切换；报销垫付流水识别应基于 `tx.refTransactionId == null` 而非 `reimburseState != SETTLED`，确保结清后原垫付单符号不反转。
- **[M4.1] 跨模块构造默认参数类泄露与 Compose 非合并容器 `testTag` 语义断言**：`:core:ai` 中 `AiDraftJsonParser` 若在公开主构造暴露 `implementation` 依赖 `kotlinx.serialization.json.Json` 的默认参数，会导致 `:app` 调用无参构造时报 `Cannot access class 'Json'`，应改为 `internal constructor(json: Json)` + 公开无参 `constructor()` 保持模块边界洁净；Compose 中无 `clickable` 的 `Row` 与 `DropletPillBadge` 内 `Surface` 均不自动合并子 `Text` 语义，UI/E2E 断言文本时须将 `testTag` 直接置于 `Text` 或显式附加 `Modifier.semantics(mergeDescendants = true) {}`；`DropletAppE2ETest` 对所有异步 DataStore 切换（如 `relock_timeout_*`、`amounts_hidden`）须统一用 `composeRule.waitUntil` 守卫，防止前置用例瞬时帧差导致状态残留污染后续共享库 E2E 用例。
- **[M4.2] Kotlin KDoc 嵌套 `/*` 与 Robolectric Room Flow ViewModel 单测调度**：Kotlin 块注释支持嵌套，在 `/** ... */` KDoc 中书写 MIME 字面量 `image/*` 会意外开启嵌套注释导致 `Unclosed comment` 编译错误，应改用自然语言描述；Robolectric 下测试订阅了 Room `Flow` 的 ViewModel 时，`StandardTestDispatcher` + `advanceUntilIdle()` 无法等待 Room 后台线程发射更新，须沿用 `UnconfinedTestDispatcher()` + `runBlocking` + `withTimeout(5_000L) { uiState.first { ... } }` 确定性等待状态收敛；系统分享 `ACTION_SEND`/`ACTION_SEND_MULTIPLE` 在 `DropletApp` 须由 `LaunchedEffect(pendingAiSharePayload?.id, isAppLocked)` 严格守卫，确保应用隐私锁未解锁前绝不提前跳转或消费分享负载。
