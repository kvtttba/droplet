# Droplet 记账 — 开发进度看板与跨阶段交接单

> 每个开发会话启动时先读 [`AGENTS.md`](../AGENTS.md) 与本文档；每个切片完成合入 `main` 前必须更新本文档。

## 当前状态

- **当前阶段**：`M1.2`（Room Schema、DAO 与预置分类/账户）已完成验收并合入 `main`。
- **下一个待执行切片**：**`M1.3`**（明细首页、账户/标签管理与记账表单）
- **最新已合入提交**：`feat/m1-2-room-database` 合入 `main`（CI 全线绿灯，详见 `git log`）

---

## 已锁定基线摘要（勿重复讨论）

- **应用定位**：Droplet 记账，Android 单端（`minSdk = 31` / Android 12+），个人自用，无登录、本地优先，纯净无广告。
- **包名与工程标识**：`applicationId = "io.github.kvtttba.droplet"`，Debug APK 输出名 `droplet-debug.apk`。
- **技术栈与已锁定版本基线**：Gradle 8.11.1 + Kotlin DSL + `gradle/libs.versions.toml`；AGP 8.9.3、Kotlin 2.1.21、KSP 2.1.21-2.0.1、Hilt 2.56.2、Compose BOM 2025.05.01、Navigation Compose 2.8.9、Room 2.7.1、WorkManager 2.10.1、OkHttp 4.12.0、Kotlinx Serialization 1.8.1、Robolectric 4.14.1、Roborazzi 1.44.0；`compileSdk = 35` / `targetSdk = 35` / `minSdk = 31`，JVM 17 字节码目标（OpenJDK 21 运行环境兼容）。
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
| `M1.3` | 明细首页、账户/标签管理与记账表单 | ⬜ 待开始 | 首页流水与筛选、账户与分类/标签 CRUD、一木式表单 + 专业分录展开、Roborazzi 截图 + APK | 下一切片 |
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

### M1.3 启动入口契约（明细首页、账户/标签管理与记账表单）
1. 从 `main` 切出分支 `feat/m1-3-ledger-ui-and-forms`，在 `:app` 补齐 Hilt DI 模块，绑定 `DropletDatabase`、各 DAO、`LedgerTransactionWriter`、`DefaultLedgerSeeder`、`LedgerEngine` 与仓储层；App 启动时自动异步幂等执行 `DefaultLedgerSeeder.seedIfNeeded()`。
2. **明细首页（`:feature:ledger`）**：替换 M0 静态占位列表，接入 Room `Flow` 实时展示：
   - 顶部月度总支出 / 总收入 / 结余汇总卡（强制接入 `LocalAmountsHidden` 全局隐私隐藏与等宽数字 `tabular-nums`，历史汇总直取 `TransactionDao.observePeriodSummary` / `observeFilteredPeriodSummary` 锁定本位币）；
   - 多维筛选条（月份切换、多标签交并筛选、账户筛选、父/子分类筛选、关键词搜索）；
   - 按日期分组的流水列表（分类图标与主题色、商户/备注、标签 Chips、账户、金额及待报销/退款状态角标），点击支持查看/编辑/作废/删除（删除或作废后汇总与列表实时刷新）。
3. **一木式直观记账表单 + 专业分录展开模式（`:feature:ledger`）**：
   - 直观模式默认支持「支出」「收入」「转账/还款」三 Tab：单手友好金额输入、一木风格二级分类选择（大类展开小类）、付款/收款账户选择（支持 CNY/USD/HKD 币种及跨币种实付+实收自动反推汇率）、日期时间、多标签选择、商户/备注、可选优惠/手续费与「待报销」开关；
   - 一键展开/切换「专业多拆分录模式」：自由增减借方/贷方分录行，实时调用 `LedgerValidator.computeCurrencyImbalances(...)` 展示各币种借贷差额，归零后经 `LedgerTransactionWriter.saveTransaction(...)` 原子入账，并支持已有流水的编辑保存。
4. **账户、二级分类与标签管理（`:feature:ledger` / `:feature:settings`）**：
   - 账户管理页：支持新增/编辑/归档/恢复账户（支持设置初始余额并通过 `LedgerEngine.buildOpeningBalance` 平衡入 `Equity:OpeningBalance:{CCY}`、配置「是否计入总资产」开关与信用账户基础参数）；
   - 分类与标签管理页：支持支出/收入二级分类（大类 + 小类）与多标签的增删改、颜色/图标配置与归档。
5. **测试与视觉验收门禁**：
   - 配齐 ViewModel / Repository 的 L2 Robolectric 测试（真实入账、编辑、筛选、期初余额、多标签交并、跨币种入账与隐私态）；
   - 更新并补齐 `:app` Roborazzi 高清黄金截图（明细首页浅/深色与隐身态、一木式记账表单、专业分录展开态、账户/分类/标签管理页）与 API 31 模拟器 E2E 测试（真实记账 → 列表与月度汇总实时更新 → 筛选与隐身验证），确保本地单测、`verifyRoborazziDebug`、`gitleaks` 与 GitHub Actions 全线 CI 绿灯并输出精简 Debug APK。

---

## 踩坑记录

- **[M0] OpenJDK 21 下纯 JVM 模块编译目标**：纯 JVM 模块不要写 `kotlin { jvmToolchain(17) }`（会触发下载独立 JDK 17），统一使用 `java.sourceCompatibility/targetCompatibility = JavaVersion.VERSION_17` + `kotlin.compilerOptions.jvmTarget = JvmTarget.JVM_17`。
- **[M0] 纯 JVM 单测与 Android Release 编译提速**：执行 `./gradlew :core:model:test :core:domain:test :core:network:test testDebugUnitTest`，避免根任务 `./gradlew test` 触发所有 Android 模块的 Release 单测编译。
- **[M0] Debug APK 体积控制**：`material-icons-extended` 会让未混淆 Debug APK 膨胀至 59 MB；改用 `core:ui` 精选矢量 `DropletIcons` + `packaging.dex.useLegacyPackaging = true` 后 Debug APK 降至 ~12 MB，便于飞书直接交付与真机快速安装。
- **[M0] Roborazzi + Hilt 兼容性**：纯 Compose 截图测试使用 `@Config(application = Application::class, sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi")` 跳过 `DropletApplication` 的 `@HiltAndroidApp` 初始化，配合 `RoborazziOptions.CompareOptions(changeThreshold = 0.015f)` 在本地 Debian 与 GitHub Actions `ubuntu-latest` 之间稳定通过。
- **[M0] 本机 GitHub PAT 权限**：本机 `gh` PAT 具备 `contents: write` 与 `workflows: write` 权限，未开 `pull_requests: write`，可通过 `feat/**` 分支推送直接触发 CI 验证，验收后合入 `main`。
- **[M1.1] 非交互 Shell 本地跑 Android Gradle 任务**：本机 Android SDK 位于 `/data/home/android-sdk`，非交互 Shell 需显式传 `ANDROID_HOME=/data/home/android-sdk`（切勿创建/提交 `local.properties`）。
- **[M1.2] Room 主表更新严禁使用 `OnConflictStrategy.REPLACE`**：SQLite 对 `REPLACE` 按 `DELETE + INSERT` 实现，会误触发 `attachments` 的 `ON DELETE CASCADE` 与 `transactions.ref_transaction_id` / `loan_installments.transaction_id` 的 `ON DELETE SET NULL`；主表更新必须统一用 `@Upsert`（`ON CONFLICT DO UPDATE`），仅精准替换子表分录与标签关联。
