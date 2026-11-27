# Droplet 记账 — 开发进度看板与跨阶段交接单

> 每个开发会话启动时先读 [`AGENTS.md`](../AGENTS.md) 与本文档；每个切片完成合入 `main` 前必须更新本文档。

## 当前状态

- **当前阶段**：`M1.4`（生物识别隐私锁、重锁与金额隐藏）已完成验收并合入 `main`（**M1 复式核心与日常记账闭环**全量收官）。
- **下一个待执行切片**：**`M2.1`**（免费公开汇率与全品类行情 Provider 链）
- **最新已合入提交**：`feat/m1-4-biometric-privacy-lock` 合入 `main`（CI `#45999836880` 全线绿灯，详见 `git log`）

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
| `M2.1` | 免费公开汇率与全品类行情 Provider 链 | ⬜ 待开始 | CNY/USD/HKD 汇率 + 9 类资产公开源适配、降级缓存、可选代理、Mock 测试 | 下一切片 |
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

### M2.1 启动入口契约（免费公开汇率与全品类行情 Provider 链）
1. 从 `main` 切出分支 `feat/m2-1-market-fx-providers`，更新 `app/build.gradle.kts` 中 `versionName = "0.1.0-m2.1"`。
2. **纯 JVM 网络与代理基础设施 (`:core:network`)**：
   - 保持 `:core:network` 纯 Kotlin/JVM 零 Android 依赖，实现 `NetworkProxyConfig`（`NONE` / `HTTP` / `SOCKS`、`host`、`port`，严禁硬编码真实个人代理地址或凭据）与 `DropletHttpClientFactory`（可切换代理的 OkHttpClient、统一超时与标准浏览器/轻量 User-Agent）；
   - 实现全品类行情与汇率抽象契约：
     - `ExchangeRateProvider` 与 `ExchangeRateProviderChain`（拉取 `CNY / USD / HKD` 实时双向汇率，主源 + 备用公开源自动降级，内部统一 `BigDecimal` 8 位精度规范化输出 `CachedExchangeRate`）；
     - `MarketQuoteProvider` 与 `MarketQuoteProviderChain`（按 `AssetClass` 分发策略链：国内直连优先 → 备用公开源兜底 → 单标的失败隔离不影响其余标的），覆盖：
       1. A 股 / 场内 ETF（东方财富 / 腾讯财经主源 + 新浪财经备用源）；
       2. 公募基金（天天基金实时估值/官方净值主源 + 东方财富基金净值备用源）；
       3. 港股 / 美股（东方财富 / 腾讯财经主源 + Yahoo Finance 备用源）；
       4. 贵金属 / 黄金（东方财富 / 上金所 AU9999 主源 + 国际现货 XAU 备用源）；
       5. 加密货币（OKX / Binance 公开 Ticker 主源 + CoinGecko 备用源，天然支持可选 HTTP/SOCKS 代理）；
       6. 定期存款（`FIXED_DEPOSIT` 不发网络请求，交由本地计息引擎）与无公开代码银行理财（`BANK_WEALTH` 若无代码跳过远程拉取保留手动估值）；
   - 实现 `RefreshThrottlePolicy`（默认 5 分钟节流窗口 `MarketNetworkContract.DEFAULT_REFRESH_THROTTLE_MINUTES`，支持 `forceRefresh = true` 绕过节流与批量请求并发限流）。
3. **缓存降级与仓库整合 (`:core:database` / `:app`)**：
   - 落地 `MarketDataRepository`（或 `MarketSyncRepository`）衔接 `:core:network` Provider 链与 Room `ExchangeRateDao` / `MarketQuoteDao`：
     - 拉取成功原子写入 `exchange_rates` / `market_quotes`（保留历史手动覆写保护策略：如 `isManualOverride == true` 时仅在用户明确覆盖或显式刷新时更新，且失败时完整保留最近一次有效缓存与 `fetchedAt`）；
     - 支持 `manualOverrideQuote(assetId, priceDecimal, currency)` 与 `clearManualOverride`；
   - 在 `:app` 新增 `NetworkModule` Hilt DI 绑定（`DropletHttpClientFactory`、`ExchangeRateProviderChain`、`MarketQuoteProviderChain`、`MarketDataRepository`），并在 `:core:security` 持久化可选代理配置（仅 `proxyType`/`host`/`port` 脱敏保存，严禁日志打印）。
4. **测试与门禁（全量本地 Mock，严禁依赖外部公网）**：
   - 在 `:core:network` 使用 `OkHttp MockWebServer` 补齐全套 L1/L2 离线测试：覆盖 9 类资产/三币种汇率正常解析、异常格式容错、主源 500/超时自动降级备用源、全部失败保留错误摘要、代理配置构建、5 分钟节流与强制刷新、数值零浮点 `BigDecimal` 精度断言；
   - 补齐 `MarketDataRepository` Room/Robolectric 测试（缓存持久化、拉取失败保留旧缓存、手动覆写单价、手动汇率桥接 `ExchangeRateTable`）；
   - 通过本地单测（`ANDROID_HOME=/data/home/android-sdk ./gradlew --max-workers=2 ...`）、`verifyRoborazziDebug`、`gitleaks`、`assembleDebug` 与 GitHub Actions 全线 CI 绿灯。

---

## 踩坑记录

- **[M0] OpenJDK 21 下纯 JVM 模块编译目标**：纯 JVM 模块不要写 `kotlin { jvmToolchain(17) }`（会触发下载独立 JDK 17），统一使用 `java.sourceCompatibility/targetCompatibility = JavaVersion.VERSION_17` + `kotlin.compilerOptions.jvmTarget = JvmTarget.JVM_17`。
- **[M0] 纯 JVM 单测与 Android Release 编译提速**：执行 `./gradlew :core:model:test :core:domain:test :core:network:test testDebugUnitTest`，避免根任务 `./gradlew test` 触发所有 Android 模块的 Release 单测编译。
- **[M0] Debug APK 体积控制**：`material-icons-extended` 会让未混淆 Debug APK 膨胀至 59 MB；改用 `core:ui` 精选矢量 `DropletIcons` + `packaging.dex.useLegacyPackaging = true` 后 Debug APK 降至 ~12–14 MB，便于飞书直接交付与真机快速安装。
- **[M0] Roborazzi + Hilt 兼容性**：纯 Compose 截图测试使用 `@Config(application = Application::class, sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi")` 跳过 `DropletApplication` 的 `@HiltAndroidApp` 初始化，配合 `RoborazziOptions.CompareOptions(changeThreshold = 0.015f)` 在本地 Debian 与 GitHub Actions `ubuntu-latest` 之间稳定通过。
- **[M0] 本机 GitHub PAT 权限**：本机 `gh` PAT 具备 `contents: write` 与 `workflows: write` 权限，未开 `pull_requests: write`，可通过 `feat/**` 分支推送直接触发 CI 验证，验收后合入 `main`。
- **[M1.1] 非交互 Shell 本地跑 Android Gradle 任务**：本机 Android SDK 位于 `/data/home/android-sdk`，非交互 Shell 需显式传 `ANDROID_HOME=/data/home/android-sdk`（切勿创建/提交 `local.properties`）。
- **[M1.2] Room 主表更新严禁使用 `OnConflictStrategy.REPLACE`**：SQLite 对 `REPLACE` 按 `DELETE + INSERT` 实现，会误触发 `attachments` 的 `ON DELETE CASCADE` 与 `transactions.ref_transaction_id` / `loan_installments.transaction_id` 的 `ON DELETE SET NULL`；主表更新必须统一用 `@Upsert`（`ON CONFLICT DO UPDATE`），仅精准替换子表分录与标签关联。
- **[M1.3] `:core:database` 对外暴露 `DropletDatabase` 需用 `api(libs.room.runtime)`**：`:app` 的 Hilt DI 模块直接返回 `DropletDatabase` 时，Kotlin 编译器需要解析其超类 `RoomDatabase`，因此在 `core/database/build.gradle.kts` 中应将 `room-runtime`/`room-ktx` 声明为 `api`。
- **[M1.3] Room Flow 筛选切换防闪烁 & Roborazzi 跨时区确定性**：多查询随筛选条件联动时，须在单个 `flatMapLatest` 内 `combine` 列表流与汇总流，避免旧结果与新筛选条件拼帧；涉及日期文本的 Roborazzi 测试须固定 `TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))` 并在 `@After` 恢复，确保本机与 UTC CI 像素一致；本机 8 GB 无 Swap 环境并行跑 Robolectric + Roborazzi 时建议加 `--max-workers=2`。
- **[M1.4] `BiometricPrompt` 设备锁屏兜底 & `AUTHENTICATING` 态切后台保活**：允许 `DEVICE_CREDENTIAL` 时严禁同时调用 `setNegativeButtonText`（否则抛出 `IllegalArgumentException`）；唤起系统 PIN/密码验证页会触发 `ProcessLifecycleOwner.ON_STOP`，`AppLockStateMachine` 在 `AUTHENTICATING` 态收到 `onAppBackgrounded()` 时须保持认证中状态，避免解锁成功回调被误重锁。
- **[M1.4] `AppLockOverlay` 触摸拦截与 Roborazzi 语义树 / DataStore 单测隔离 / gitleaks 熵检**：全屏遮罩拦截底层触摸应使用 `Modifier.pointerInput` 消费事件而非 `Modifier.clickable`（避免 `mergeDescendants = true` 合并子语义节点并干扰 TalkBack 与测试断言）；DataStore 单测须通过 `PreferenceDataStoreFactory.create` 绑定独立临时文件并在 `finally` 中 `cancelAndJoin()` 防止多实例冲突；公开仓测试样例字符串须避免高熵值紧邻 `secret`/`token` 变量名以防误触 `gitleaks` 的 `generic-api-key` 规则。
